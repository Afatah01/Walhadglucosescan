# The AI door — deploy it once

This is the small server the app talks to. It is the only place the
OpenAI key lives. The address and the shared secret are already baked into the
app, so **nobody using it ever types a key or picks a provider** — there is
nothing to fill in.

```
phone  ->  Supabase Edge Function "walhad-ai"  ->  OpenAI
              (the provider key stays here)
```

If this function cannot be reached — no internet, or it is not deployed yet —
the app does not break. It answers with its own rules engine on the phone
(`app/src/main/kotlin/com/glucosescan/app/core/Advisor.kt`) and labels the
answer as coming from the phone, not from an AI. It never pretends otherwise.

## Your project

| | |
|---|---|
| Project ref | `lhrocwxukovudxmjbaiy` |
| Function URL | `https://lhrocwxukovudxmjbaiy.supabase.co/functions/v1/walhad-ai` |
| App secret | `a274936ddec05a084677d6f1d6fe501328044569820fc4e88716887741a65d65` |

The URL and the secret are already in `app/build.gradle.kts`. You do not need
to touch the app at all.

## What you still need to do (once)

You need an **OpenAI API key** — from <https://platform.openai.com/api-keys> —
and to run these commands. Your key is already written to `supabase/.env`,
which git keeps out of the repository, so step 4 can read it from there
instead of your typing it again.

```bash
# 1. install the Supabase command line (once, ever)
brew install supabase/tap/supabase      # Windows: scoop install supabase ; Linux: see supabase.com/docs

# 2. sign in and connect this folder to the project
supabase login
supabase link --project-ref lhrocwxukovudxmjbaiy

# 3. create the table that counts questions
supabase db push

# 4. put the two secrets in — the app secret is already chosen, use it exactly
#    (the key is read from supabase/.env, which is never committed)
supabase secrets set --env-file supabase/.env
supabase secrets set WALHAD_APP_SECRET=a274936ddec05a084677d6f1d6fe501328044569820fc4e88716887741a65d65

# 5. ship it
supabase functions deploy walhad-ai
```

If `supabase db push` gives you trouble, you can instead open
**SQL Editor** in the Supabase dashboard, paste the contents of
`supabase/migrations/20250101000000_walhad_ai.sql` and press Run.

That is it. The next scan with the app gets AI analysis.

Then delete `supabase/.env`: the key lives on the server now, and the file on
your machine has served its purpose.

## Keep the key out of the app

The key is never in the Android source, never in `secrets.properties`, and never
in the APK. It is read at run time out of the server's environment, by a
function that runs on Supabase. Somebody who takes the APK apart finds only the
address of that function and a shared secret that lets it through the door —
and even that is capped at `WALHAD_DAILY_LIMIT` questions a day per install.

## Optional settings

| Secret | Default | What it does |
|---|---|---|
| `WALHAD_AI_MODEL` | `gpt-4o-mini` | Which model answers. Change it here; the app is not rebuilt. |
| `WALHAD_DAILY_LIMIT` | `20` | Questions per install per day. This is what caps the bill. |

```bash
supabase secrets set WALHAD_DAILY_LIMIT=30
```

## Rotating the secret

The secret is inside the APK, so assume somebody determined can read it. If you
want to change it:

```bash
supabase secrets set WALHAD_APP_SECRET="$(openssl rand -hex 32)"
supabase functions deploy walhad-ai
```

then put the new value in `secrets.properties` (see
`secrets.properties.example`) and rebuild the app. Old builds stop working. The
OpenAI key was never exposed either way, so there is nothing else to change.

## Being straight about the security

The shared secret is compiled into the app. Someone who decompiles the APK can
read it. It is a **deterrent, not a guarantee**. What actually protects you:

- the OpenAI key is only ever on the server, so an attacker never gets it;
- every install is capped at `WALHAD_DAILY_LIMIT` questions a day;
- the payload is size-limited (20 kB of numbers, 500 characters of question);
- no glucose value, question or answer is ever stored;
- the server re-reads every answer and drops any that drifts into giving a dose.

## What is stored

Only this, in the `ai_usage` table:

| Column | Value |
|---|---|
| `install_id` | SHA-256 hash of a random per-install id |
| `day` | the date in UTC |
| `calls` | how many questions that day |

No glucose reading, no question text, no AI answer, no account, no email.

## Checking it works

```bash
curl -i https://lhrocwxukovudxmjbaiy.supabase.co/functions/v1/walhad-ai \
  -H 'Content-Type: application/json' \
  -H 'x-walhad-secret: a274936ddec05a084677d6f1d6fe501328044569820fc4e88716887741a65d65' \
  -d '{"report":"Mean glucose 154 mg/dL. Time in range 61%. 2 lows, longest 35 minutes, at 03:12.","question":"What do you see?","language":"en"}'
```

You should get back:

```json
{"ok":true,"answer":"...","model":"gpt-4o-mini","source":"ai"}
```

Before step 5 it returns `404` — that just means the function is not deployed.

## Watching the logs

```bash
supabase functions logs walhad-ai --tail
```

Or in the dashboard: **Edge Functions → walhad-ai → Logs**.
