// Walhad Glucose Scan — the AI door.
//
//   phone  ->  this function  ->  Google Gemini
//
// The provider key lives here and nowhere else: not in the app, not on GitHub.
// The app only knows the address of this function and one shared secret.
//
// If this function cannot be reached, the app does not stop: it answers with
// its own rules engine on the phone (core/Advisor.kt) and says plainly that the
// words came from the phone, not from an AI. It never pretends otherwise.
//
// Deploy: see README.md in this folder.

import { createClient } from "jsr:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-walhad-secret",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

/** The model that answers. Change it here; the app is not touched. */
const MODEL = Deno.env.get("WALHAD_AI_MODEL") ?? "gemini-2.5-flash";

/** Questions one install may ask per day. This is what actually caps the bill. */
const DAILY_LIMIT = Number(Deno.env.get("WALHAD_DAILY_LIMIT") ?? "20");

const MAX_REPORT = 20_000;
const MAX_QUESTION = 500;
const MAX_HISTORY = 6; // three exchanges of follow-up

type Turn = { q?: unknown; a?: unknown };
type Payload = {
  report?: unknown;
  question?: unknown;
  language?: unknown;
  history?: unknown;
  install?: unknown;
};

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ ok: false, problem: "server", detail: "use POST" }, 405);

  const secret = Deno.env.get("WALHAD_APP_SECRET");
  const apiKey = Deno.env.get("GEMINI_API_KEY");

  if (!secret || !apiKey) {
    // Nothing is set up yet. The app must fall back to its own engine.
    return json({ ok: false, problem: "server", detail: "the AI service is not set up yet" }, 503);
  }

  // 1. Is it our app?
  //
  // The secret is baked into the APK, so it deters casual abuse rather than
  // guaranteeing anything against somebody who decompiles the app. The real
  // protection is that the provider key is only ever here, plus the daily
  // limit below. Rotate the secret any time with `supabase secrets set`.
  if (req.headers.get("x-walhad-secret") !== secret) {
    return json({ ok: false, problem: "key", detail: "not allowed" }, 401);
  }

  // 2. What is being asked?
  let payload: Payload;
  try {
    payload = (await req.json()) as Payload;
  } catch {
    return json({ ok: false, problem: "server", detail: "the body is not JSON" }, 400);
  }

  const report = str(payload.report, MAX_REPORT);
  const question = str(payload.question, MAX_QUESTION);
  const language = str(payload.language, 8) || "en";
  const history: Turn[] = Array.isArray(payload.history)
    ? (payload.history as Turn[]).slice(-MAX_HISTORY)
    : [];

  if (!report.trim()) {
    return json({ ok: false, problem: "empty", detail: "no numbers were sent" }, 400);
  }

  // The install id is only used to count questions, so it is hashed before it
  // is written down. Nothing about the person is stored: no glucose value, no
  // question, no answer.
  const install = await sha256(str(payload.install, 64) || "unknown");

  // 3. How many questions has this install asked today?
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
  );
  const day = new Date().toISOString().slice(0, 10);
  const { data: usage, error: usageError } = await supabase
    .from("ai_usage")
    .select("calls")
    .eq("install_id", install)
    .eq("day", day)
    .maybeSingle();

  if (usageError) {
    return json({ ok: false, problem: "server", detail: usageError.message }, 500);
  }
  if ((usage?.calls ?? 0) >= DAILY_LIMIT) {
    return json(
      { ok: false, problem: "limit", detail: `daily limit of ${DAILY_LIMIT} questions reached` },
      429,
    );
  }

  // 4. Ask the provider.
  const contents = [
    ...history.flatMap((turn) => [
      { role: "user", parts: [{ text: str(turn.q, MAX_QUESTION) }] },
      { role: "model", parts: [{ text: str(turn.a, 4000) }] },
    ]),
    { role: "user", parts: [{ text: userText(report, question) }] },
  ];

  let answer = "";
  try {
    const response = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
        body: JSON.stringify({
          system_instruction: { parts: [{ text: systemText(language) }] },
          contents,
          generationConfig: { temperature: 0.4, maxOutputTokens: 2048 },
        }),
      },
    );

    if (response.status === 429) {
      return json({ ok: false, problem: "limit", detail: "the AI provider is rate limited" }, 429);
    }
    if (response.status === 401 || response.status === 403) {
      return json({ ok: false, problem: "key", detail: "the provider refused the key" }, 502);
    }
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 300);
      return json({ ok: false, problem: "server", detail }, 502);
    }

    const body = (await response.json()) as {
      candidates?: { content?: { parts?: { text?: string }[] }; finishReason?: string }[];
    };
    const candidate = body.candidates?.[0];
    answer = (candidate?.content?.parts ?? [])
      .map((part) => part.text ?? "")
      .join("")
      .trim();

    if (!answer) {
      const reason = candidate?.finishReason;
      if (reason && reason !== "STOP" && reason !== "MAX_TOKENS") {
        return json({ ok: false, problem: "blocked", detail: reason }, 422);
      }
      return json({ ok: false, problem: "empty", detail: "the AI answered nothing" }, 502);
    }
  } catch (error) {
    return json({ ok: false, problem: "server", detail: String(error).slice(0, 300) }, 502);
  }

  // 5. Read it back before it goes to any phone.
  //
  // The rules in the prompt are not enough on their own: a model can still
  // drift into giving a dose. This app is not allowed to show one, so the
  // answer is dropped here rather than passed through.
  if (looksLikeDose(answer)) {
    return json(
      { ok: false, problem: "blocked", detail: "the answer was withheld by the safety check" },
      422,
    );
  }

  // 6. Count the question and answer.
  await supabase
    .from("ai_usage")
    .upsert(
      { install_id: install, day, calls: (usage?.calls ?? 0) + 1, updated_at: new Date().toISOString() },
      { onConflict: "install_id,day" },
    );

  return json({ ok: true, answer, model: MODEL, source: "ai" });
});

// ---------------------------------------------------------------- the words

/**
 * The rules the AI must follow. Plain language, no diagnosis, no doses, and
 * only the numbers that were actually sent.
 */
function systemText(language: string): string {
  const names: Record<string, string> = {
    en: "English",
    so: "Somali (Soomaali)",
    ar: "Arabic (العربية)",
  };
  const name = names[language] ?? "English";

  return [
    "You explain the numbers of a glucose sensor to the person who wears it. That person is not a doctor.",
    "",
    "Rules you never break:",
    "1. Answer in " + name + ".",
    "2. Use short, plain sentences and everyday words. No jargon without explaining it.",
    "3. Never give a dose of insulin or of any medicine. Never say to change, start or stop a dose,",
    "   a medicine, or a pump setting. If a dose or a change is what the person needs, say:",
    "   that is for their doctor or diabetes nurse to decide.",
    "4. Never name a diagnosis. Describe what the numbers show and what is worth discussing.",
    "5. Only use the numbers you were given. Never invent a reading, a time, a meal or a dose.",
    "   If the numbers are too few or too patchy to say something, say exactly that first.",
    "6. If you see values under 54 mg/dL (3.0 mmol/L), or many low values, or values above",
    "   250 mg/dL (13.9 mmol/L), say first and clearly that the person should follow the plan",
    "   their clinician gave them and contact them. Do not bury this in the middle.",
    "7. Plain text only. No tables, no markdown, no asterisks, no headings. Start list lines with a dash.",
    "8. At most about 350 words unless the question asks for more.",
    "9. End with one short sentence saying this is not medical advice.",
  ].join("\n");
}

/** The numbers, then the person's own question if there is one. */
function userText(report: string, question: string): string {
  const own = question.trim().replace(/\s+/g, " ");
  const head = "These are my glucose numbers, measured by my sensor:\n\n" + report.trim();
  return own ? head + "\n\nMY OWN QUESTION\n" + own + "\n" : head + "\n\nPlease tell me what you see in these numbers.\n";
}

/**
 * The last check before an answer leaves the server. A number followed by a
 * unit, or a verb telling somebody to move a dose, means the answer is dropped.
 */
function looksLikeDose(text: string): boolean {
  const amount = /\b\d+(\.\d+)?\s*(units?|iu|ml\/h|mg\/dl of insulin)\b/i;
  const instruction =
    /\b(increase|decrease|reduce|raise|lower|change|adjust|start|stop|take|inject|give|administer)\b[^.]{0,60}\b(dose|dosage|insulin|basal|bolus|injection|units?|medication|medicine|pump)\b/i;
  return amount.test(text) || instruction.test(text);
}

function str(value: unknown, max: number): string {
  return value == null ? "" : String(value).slice(0, max);
}

async function sha256(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
