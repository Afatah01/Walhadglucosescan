// Walhad Glucose Scan — the AI door.
//
//   phone  ->  this function  ->  the AI provider
//
// The provider key lives here and nowhere else: not in the app, not on GitHub.
// The app only knows the address of this function and one app key. If that key
// is ever copied out of the app, the owner rotates it here and the copy stops
// working; the provider key is never touched.
//
// Deploy: see backend/README.md

import { serve } from "https://deno.land/std@0.224.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

/** Which provider answers. "gemini" is the only one wired up today. */
const PROVIDER = (Deno.env.get("WALHAD_AI_PROVIDER") ?? "gemini").toLowerCase();
const MODEL = Deno.env.get("WALHAD_AI_MODEL") ?? "gemini-3.5-flash-lite";

/** How many questions one app key may ask in a day. 0 or empty = no limit. */
const DAILY_LIMIT = Number(Deno.env.get("WALHAD_DAILY_LIMIT") ?? "200");

/** The longest question that is taken, and the longest report. */
const MAX_QUESTION = 500;
const MAX_REPORT = 20_000;

const SYSTEM = `You are the analysis assistant inside a glucose app. You are given a block of
numbers that the app counted from a glucose sensor, and a question from the person who wears it.

Rules you never break:
1. Only use the numbers you are given. Never invent a reading, an event, a time or a dose.
2. Never give an insulin dose, a medicine, a dose change, or a treatment plan. If the person
   needs one, say: that is for their doctor or diabetes nurse to decide.
3. Never diagnose. Describe what the numbers show and what they may mean to discuss.
4. Say when the data is thin: if the period is short or has holes, say that the picture is
   uncertain before you say anything else about it.
5. If a value is dangerously low (under 54 mg/dL) or very high (over 250 mg/dL), say first
   that the person should follow the plan their clinician gave them, and contact them.
6. Write in short, plain sentences. No bullet-point lists of medical jargon. Two or three
   short paragraphs at most, unless the question asks for a list.
7. Answer in the language you are told to answer in.`;

const LANGUAGES: Record<string, string> = {
  en: "Answer in English.",
  so: "Answer in Somali (Soomaali).",
  ar: "Answer in Arabic (العربية)."
};

serve(async (req: Request): Promise<Response> => {
  if (req.method !== "POST") {
    return answer({ error: "Use POST." }, 405);
  }

  // ---- 1. who is calling
  const appKey = req.headers.get("x-walhad-key") ?? "";
  const expected = Deno.env.get("WALHAD_APP_KEY") ?? "";
  if (expected === "" || appKey === "" || !safeEqual(appKey, expected)) {
    return answer({ error: "Not authorised." }, 401);
  }

  // ---- 2. what they are asking
  let body: { question?: string; report?: string; language?: string; history?: string[] };
  try {
    body = await req.json();
  } catch {
    return answer({ error: "The body is not JSON." }, 400);
  }
  const question = (body.question ?? "").toString().slice(0, MAX_QUESTION);
  const report = (body.report ?? "").toString().slice(0, MAX_REPORT);
  const language = (body.language ?? "en").toString().slice(0, 5);
  const history = Array.isArray(body.history)
    ? body.history.slice(-6).map((line) => line.toString().slice(0, MAX_QUESTION))
    : [];
  if (report.trim() === "") {
    return answer({ error: "No data was sent." }, 400);
  }

  // ---- 3. is there room left today
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL") ?? "",
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? ""
  );
  if (DAILY_LIMIT > 0) {
    const used = await questionsToday(supabase, appKey);
    if (used >= DAILY_LIMIT) {
      return answer({ error: "limit", message: "The daily number of questions is used up." }, 429);
    }
    await supabase.from("walhad_ai_usage").insert({ app_key: hash(appKey), model: MODEL });
  }

  // ---- 4. ask the provider
  const key = Deno.env.get("WALHAD_AI_KEY") ?? "";
  if (key === "") {
    return answer({ error: "The AI service is not set up on the server." }, 503);
  }
  const started = Date.now();
  let text: string;
  try {
    text = PROVIDER === "gemini"
      ? await askGemini(key, question, report, history, language)
      : await askOpenAiStyle(key, question, report, history, language);
  } catch (e) {
    console.error("provider failed", e);
    return answer({ error: "The AI service did not answer." }, 502);
  }

  // ---- 5. check what came back before it goes to the phone
  const checked = check(text);
  if (!checked.ok) {
    console.error("answer refused", checked.why);
    return answer({ error: "The AI service answered something this app will not show." }, 502);
  }

  return answer({
    answer: checked.text,
    model: MODEL,
    provider: PROVIDER,
    seconds: Math.round((Date.now() - started) / 1000),
    local: false
  }, 200);
});

// ------------------------------------------------------------------ providers

async function askGemini(
  key: string,
  question: string,
  report: string,
  history: string[],
  language: string
): Promise<string> {
  const contents: { role: string; parts: { text: string }[] }[] = [];
  for (const line of history) {
    contents.push({ role: "user", parts: [{ text: line }] });
    contents.push({ role: "model", parts: [{ text: "(asked before)" }] });
  }
  contents.push({
    role: "user",
    parts: [{
      text:
        "Here are my glucose numbers, counted by the app:\n\n" +
        report +
        "\n\n" +
        (question.trim() === "" ? "Please tell me what you see in these numbers." : question) +
        "\n\n" +
        (LANGUAGES[language] ?? LANGUAGES.en)
    }]
  });

  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${MODEL}:generateContent?key=${key}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: SYSTEM }] },
        contents,
        generationConfig: { temperature: 0.3, maxOutputTokens: 900 },
        safetySettings: [
          { category: "HARM_CATEGORY_MEDICAL", threshold: "BLOCK_NONE" }
        ]
      })
    }
  );
  if (!response.ok) {
    throw new Error(`gemini ${response.status}: ${(await response.text()).slice(0, 400)}`);
  }
  const json = await response.json();
  const parts = json?.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p: { text?: string }) => p.text ?? "").join("").trim();
  if (text === "") {
    throw new Error("gemini answered nothing");
  }
  return text;
}

/** Any other provider that speaks the OpenAI chat format (Mistral, DeepSeek, a local model). */
async function askOpenAiStyle(
  key: string,
  question: string,
  report: string,
  history: string[],
  language: string
): Promise<string> {
  const base = Deno.env.get("WALHAD_AI_URL") ?? "https://api.mistral.ai/v1";
  const messages: { role: string; content: string }[] = [{ role: "system", content: SYSTEM }];
  for (const line of history) {
    messages.push({ role: "user", content: line });
  }
  messages.push({
    role: "user",
    content:
      "Here are my glucose numbers, counted by the app:\n\n" +
      report +
      "\n\n" +
      (question.trim() === "" ? "Please tell me what you see in these numbers." : question) +
      "\n\n" +
      (LANGUAGES[language] ?? LANGUAGES.en)
  });

  const response = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
    body: JSON.stringify({ model: MODEL, messages, temperature: 0.3, max_tokens: 900 })
  });
  if (!response.ok) {
    throw new Error(`provider ${response.status}: ${(await response.text()).slice(0, 400)}`);
  }
  const json = await response.json();
  const text = (json?.choices?.[0]?.message?.content ?? "").trim();
  if (text === "") {
    throw new Error("the provider answered nothing");
  }
  return text;
}

// ------------------------------------------------------------------ the guard

/**
 * The last look at the answer before it goes to the phone. An AI can still write
 * something it was told not to, and this app is not allowed to show a dose.
 */
function check(text: string): { ok: boolean; text: string; why?: string } {
  const clean = text.replace(/\s+/g, " ").trim();
  if (clean.length < 20) {
    return { ok: false, text: clean, why: "too short" };
  }
  if (clean.length > 6000) {
    return { ok: false, text: clean.slice(0, 6000), why: "too long" };
  }
  const dose = /\b(take|inject|give|increase|decrease|reduce|raise|lower)\b[^.]{0,40}\b(\d+(\.\d+)?\s*(units?|iu|u\b|mg|ml|ml\/h))\b/i;
  if (dose.test(clean)) {
    return { ok: false, text: clean, why: "looks like a dose" };
  }
  return { ok: true, text: clean };
}

// ------------------------------------------------------------------ helpers

function answer(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" }
  });
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

async function questionsToday(supabase: ReturnType<typeof createClient>, appKey: string): Promise<number> {
  const since = new Date();
  since.setUTCHours(0, 0, 0, 0);
  const { count, error } = await supabase
    .from("walhad_ai_usage")
    .select("id", { count: "exact", head: true })
    .eq("app_key", hash(appKey))
    .gte("asked_at", since.toISOString());
  if (error) {
    console.error("usage count failed", error);
    return 0; // counting is not worth refusing a question over
  }
  return count ?? 0;
}

/** The key itself is never written down: only a short fingerprint of it. */
async function hash(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
}
