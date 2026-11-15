# The AI door (backend)

The app never talks to an AI company, and nobody using the app types a key.
The app talks to one small server function that you own. That function holds
the key of the AI provider.

```
phone  ->  Supabase Edge Function "walhad-ai"  ->  Google Gemini
              (your key lives here only)
```

If the function cannot be reached, the app does not stop: it answers with its
own rules (the engine in `app/src/main/kotlin/com/glucosescan/app/core/Advisor.kt`)
and says clearly that the answer came from the phone, not from an AI.

## What you need (once)

1. A **Supabase** project — free, no card. <https://supabase.com/dashboard>
   (If you already set up the online copy in the app, use that same project.)
2. A **Google AI Studio** key — free. <https://aistudio.google.com/apikey>

## Deploy, in four commands

```bash
# 1. once: install the Supabase command line tool
npm install -g supabase

# 2. once: connect this folder to your project
supabase login
supabase link --project-ref YOUR-PROJECT-REF      # Dashboard -> Project settings -> General

# 3. the table that counts the questions (so you can see the cost)
psql "$DATABASE_URL" -f backend/supabase/usage.sql
#    (or paste backend/supabase/usage.sql into Dashboard -> SQL Editor -> Run)

# 4. the function, and the secrets it reads
supabase functions deploy walhad-ai
supabase secrets set WALHAD_AI_KEY=your-google-ai-studio-key
supabase secrets set WALHAD_APP_KEY=$(openssl rand -hex 24)
supabase secrets set WALHAD_DAILY_LIMIT=200
```

`supabase secrets list` shows you what is set. `WALHAD_APP_KEY` is the one value
the app needs — paste it into the app under **More → AI → Server key**, together
with the function address:

```
https://YOUR-PROJECT-REF.supabase.co/functions/v1/walhad-ai
```

## Secrets

| Name | What it is | Who may see it |
|---|---|---|
| `WALHAD_AI_KEY` | the Google AI Studio key | the server only. Never the app, never GitHub. |
| `WALHAD_APP_KEY` | a password the app sends | the app and the server. Rotate it any time. |
| `WALHAD_AI_PROVIDER` | `gemini` (default) or `openai-style` | – |
| `WALHAD_AI_MODEL` | `gemini-3.5-flash-lite` (default) | – |
| `WALHAD_DAILY_LIMIT` | questions per key per day, `200` by default | – |
| `WALHAD_AI_URL` | only for `openai-style` providers | – |

Rotating the app key:

```bash
supabase secrets set WALHAD_APP_KEY=$(openssl rand -hex 24)
supabase functions deploy walhad-ai
```

Every copy of the old key stops working at once. The provider key is untouched.

## What the function does with each question

1. Refuses anything without the app key (401).
2. Takes at most 500 characters of question and 20 kB of numbers (400 if empty).
3. Counts the questions of that key today and refuses over the limit (429).
4. Asks Gemini with a fixed set of rules: use only the numbers given, no doses,
   no diagnosis, say when the data is thin, answer in the language of the user.
5. Reads the answer back before it goes to the phone. If it is empty, far too
   long, or looks like it names a dose, the function throws it away and says the
   service answered something this app will not show (502).
6. Returns `{ answer, model, provider, seconds, local: false }`.

Nothing is kept: no glucose value, no question, no answer is written to the
database. Only a row per question with a fingerprint of the app key, the time and
the model — so that you can see how much is being asked, and nothing else.

## Costs

On the Gemini **free** tier: nothing. It is limited (a few hundred questions a
day, less for the bigger models) and Google may use what is sent to improve its
own products.

On the Gemini **paid** tier: a question here is roughly 1,500 tokens in and 400
out, which costs a small fraction of a cent. A hundred questions a day is a
couple of euros a month. The paid tier also stops Google from using the data for
training — for real glucose data, that is the setting to choose.

## Trying it by hand

```bash
curl -i https://YOUR-PROJECT-REF.supabase.co/functions/v1/walhad-ai \
  -H "x-walhad-key: YOUR-APP-KEY" \
  -H "Content-Type: application/json" \
  -d '{"language":"en","report":"Mean glucose 154 mg/dL. Time in range 61 %.","question":"What do you see?"}'
```

Expect `200` with an `answer`, or `401` if the app key is wrong.
