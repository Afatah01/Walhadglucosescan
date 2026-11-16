// Walhad Glucose Scan — the AI door. Plain JavaScript, no imports.
//
//   phone  ->  this function  ->  Groq (openai/gpt-oss-120b)
//
// The Groq key lives here and nowhere else: not in the app, not on GitHub.
// The app only knows the address of this function and one shared secret.
//
// This file is the source that runs on Supabase as the walhad-ai-core function.
//
// Groq sits behind Cloudflare: a request with no User-Agent is refused before
// Groq ever sees it. The reasoning model spends tokens thinking out of the same
// max_tokens budget, so the budget is generous (4000) and the effort is low;
// message.reasoning is never read — only message.content is the answer.

var CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, x-walhad-secret",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

var MODEL = Deno.env.get("WALHAD_AI_MODEL") || "openai/gpt-oss-120b";
var GROQ_URL = Deno.env.get("GROQ_BASE_URL") || "https://api.groq.com/openai/v1";
var DAILY_LIMIT = Number(Deno.env.get("WALHAD_DAILY_LIMIT") || "20");

var MAX_REPORT = 20000;
var MAX_QUESTION = 500;
var MAX_HISTORY = 6;
var TIMEOUT_MS = 45000;
var MAX_ATTEMPTS = 2;

var USER_AGENT = "WalhadGlucoseScan/5.0 (+https://github.com/Afatah01/Walhadglucosescan)";

function json(body, status) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: Object.assign({}, CORS, { "Content-Type": "application/json" }),
  });
}

function str(value, max) {
  if (value == null) return "";
  return String(value).slice(0, max);
}

function serviceHeaders() {
  var key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
  return { apikey: key, Authorization: "Bearer " + key };
}

async function sha256(text) {
  var bytes = new TextEncoder().encode(text);
  var digest = await crypto.subtle.digest("SHA-256", bytes);
  var hex = new Uint8Array(digest);
  var out = "";
  for (var i = 0; i < hex.length; i++) {
    out += (hex[i] < 16 ? "0" : "") + hex[i].toString(16);
  }
  return out;
}

async function callsToday(install, day) {
  var base = Deno.env.get("SUPABASE_URL") || "";
  var url = base + "/rest/v1/ai_usage?install_id=eq." + install + "&day=eq." + day + "&select=calls";
  try {
    var res = await fetch(url, { headers: serviceHeaders() });
    if (!res.ok) return 0;
    var rows = await res.json().catch(function () { return []; });
    if (!Array.isArray(rows) || rows.length === 0) return 0;
    var first = rows[0];
    return typeof first.calls === "number" ? first.calls : 0;
  } catch (e) {
    return 0;
  }
}

async function countCall(install, day, calls) {
  var base = Deno.env.get("SUPABASE_URL") || "";
  var url = base + "/rest/v1/ai_usage?on_conflict=install_id,day";
  try {
    await fetch(url, {
      method: "POST",
      headers: Object.assign(serviceHeaders(), {
        "Content-Type": "application/json",
        Prefer: "resolution=merge-duplicates,return=minimal",
      }),
      body: JSON.stringify([
        { install_id: install, day: day, calls: calls, updated_at: new Date().toISOString() },
      ]),
    });
  } catch (e) {
    // A missed count must never cost the person their answer.
  }
}

function systemText() {
  return [
    "You explain the numbers of a glucose sensor to the person who wears it. That person is not a doctor.",
    "",
    "Rules you never break:",
    "1. Answer in English.",
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
    "9. If asked what the glucose will be in 15 or 30 minutes, or where it is heading: use only",
    "   the recent readings with their timestamps that are in the message. Say clearly that the",
    "   future value is an estimate, not a measured number. If the recent readings are too few,",
    "   too old, or too jumpy to base an estimate on, say so instead of giving a number.",
    "10. If an estimate is heading toward low glucose, put the person's own clinician plan and",
    "    getting help FIRST, before anything else. Never recommend an insulin or medicine dose change.",
    "11. End with one short sentence saying this is not medical advice.",
  ].join("\n");
}

function userText(report, question) {
  var own = question.trim().replace(/\s+/g, " ");
  var head = "These are my glucose numbers, measured by my sensor:\n\n" + report.trim();
  if (own) {
    return head + "\n\nMY OWN QUESTION\n" + own + "\n";
  }
  return head + "\n\nPlease tell me what you see in these numbers.\n";
}

function looksLikeDose(text) {
  var amount = /\b\d+(\.\d+)?\s*(units?|iu|ml\/h|mg\/dl of insulin)\b/i;
  var instruction =
    /\b(increase|decrease|reduce|raise|lower|change|adjust|start|stop|take|inject|give|administer)\b[^.]{0,60}\b(dose|dosage|insulin|basal|bolus|injection|units?|medication|medicine|pump)\b/i;
  return amount.test(text) || instruction.test(text);
}

function pickAnswer(body) {
  if (typeof body !== "object" || body === null) return "";
  var choices = body.choices;
  if (!Array.isArray(choices) || choices.length === 0) return "";
  var message = choices[0].message;
  if (typeof message !== "object" || message === null) return "";
  var content = message.content;
  return typeof content === "string" ? content.trim() : "";
}

function stripMarkdown(text) {
  var out = text;
  out = out.replace(/```[a-zA-Z]*\n?/g, "");
  out = out.replace(/^#{1,6}\s+/gm, "");
  out = out.replace(/\*\*\*([^*]+)\*\*\*/g, "$1");
  out = out.replace(/\*\*([^*]+)\*\*/g, "$1");
  out = out.replace(/\*([^*\n]+)\*/g, "$1");
  out = out.replace(/__([^_]+)__/g, "$1");
  out = out.replace(/`([^`]+)`/g, "$1");
  var bullet = String.fromCharCode(8226);
  var lines = out.split("\n").map(function (line) {
    var t = line.replace(/^[ \t]+/, "");
    if (t.indexOf("- ") === 0) return "- " + t.slice(2);
    if (t.indexOf("* ") === 0) return "- " + t.slice(2);
    if (t.indexOf(bullet + " ") === 0) return "- " + t.slice(2);
    return line;
  });
  out = lines.join("\n");
  out = out.replace(/\n{3,}/g, "\n\n");
  return out.trim();
}

async function handler(req) {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ ok: false, problem: "server", detail: "use POST" }, 405);

  var secret = Deno.env.get("WALHAD_APP_SECRET");
  var apiKey = Deno.env.get("GROQ_API_KEY");

  if (!secret || !apiKey) {
    return json({ ok: false, problem: "server", detail: "GROQ_API_KEY is not set on the server" }, 503);
  }

  if (req.headers.get("x-walhad-secret") !== secret) {
    return json({ ok: false, problem: "key", detail: "not allowed" }, 401);
  }

  var payload;
  try {
    payload = await req.json();
  } catch (e) {
    return json({ ok: false, problem: "server", detail: "the body is not JSON" }, 400);
  }

  var report = str(payload.report, MAX_REPORT);
  var question = str(payload.question, MAX_QUESTION);
  var history = Array.isArray(payload.history) ? payload.history.slice(-MAX_HISTORY) : [];

  if (!report.trim()) {
    return json({ ok: false, problem: "empty", detail: "no numbers were sent" }, 400);
  }

  var install = await sha256(str(payload.install, 64) || "unknown");

  var day = new Date().toISOString().slice(0, 10);
  var calls = await callsToday(install, day);
  if (calls >= DAILY_LIMIT) {
    return json(
      { ok: false, problem: "limit", detail: "daily limit of " + DAILY_LIMIT + " questions reached" },
      429,
    );
  }

  var messages = [{ role: "system", content: systemText() }];
  for (var h = 0; h < history.length; h++) {
    messages.push({ role: "user", content: str(history[h].q, MAX_QUESTION) });
    messages.push({ role: "assistant", content: str(history[h].a, 4000) });
  }
  messages.push({ role: "user", content: userText(report, question) });

  for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    var maxTokens = attempt === 1 ? 4000 : 8000;
    var controller = new AbortController();
    var timer = setTimeout(function () { controller.abort(); }, TIMEOUT_MS);

    var response;
    try {
      response = await fetch(GROQ_URL + "/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + apiKey,
          "User-Agent": USER_AGENT,
        },
        body: JSON.stringify({
          model: MODEL,
          temperature: 0.4,
          max_tokens: maxTokens,
          reasoning_effort: "low",
          stream: false,
          messages: messages,
        }),
        signal: controller.signal,
      });
    } catch (error) {
      clearTimeout(timer);
      var timedOut = String(error).indexOf("Abort") !== -1;
      return json(
        {
          ok: false,
          problem: timedOut ? "server" : "no_internet",
          detail: timedOut ? "the AI service did not answer in time" : String(error).slice(0, 300),
        },
        timedOut ? 504 : 502,
      );
    }
    clearTimeout(timer);

    if (response.status === 429) {
      return json({ ok: false, problem: "limit", detail: "Groq is rate limited" }, 429);
    }
    if (response.status === 401 || response.status === 403) {
      return json({ ok: false, problem: "key", detail: "Groq refused the key" }, 502);
    }
    if (!response.ok) {
      var detail = (await response.text()).slice(0, 300);
      return json({ ok: false, problem: "server", detail: detail }, 502);
    }

    var body = await response.json().catch(function () { return null; });
    var answer = pickAnswer(body);

    if (!answer) {
      if (attempt < MAX_ATTEMPTS) continue;
      return json({ ok: false, problem: "empty", detail: "the AI answered nothing" }, 502);
    }

    var clean = stripMarkdown(answer);
    if (looksLikeDose(clean)) {
      return json(
        { ok: false, problem: "blocked", detail: "the answer was withheld by the safety check" },
        422,
      );
    }

    await countCall(install, day, calls + 1);

    return json({ ok: true, answer: clean, model: MODEL, source: "ai" });
  }

  return json({ ok: false, problem: "empty", detail: "the AI answered nothing" }, 502);
}

Deno.serve(handler);
