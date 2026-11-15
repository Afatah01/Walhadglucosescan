# Which AI answers, and why

**Short answer: Google Gemini, through a server this project owns. OpenAI is not used anywhere.**

## How the app reaches the AI

The app never talks to Google. It talks to one small server function that you own, and that
function talks to Google.

```
phone  ->  Supabase Edge Function "walhad-ai"  ->  Google Gemini
              (the provider key lives here only)
```

That split is the whole point. The key that costs money is never inside the app, so it can be
rotated, capped or switched off without shipping a new version of the app. Nothing a user can
extract from the APK gives them the provider key.

## Why Gemini

| | Google Gemini | Anthropic Claude | DeepSeek / others |
|---|---|---|---|
| Standing free tier | **Yes**, on Flash models | No — trial credits only | No |
| Paid price, budget model | cents per thousand questions | similar | cheap, but no free tier |
| Serverless-friendly | yes, plain HTTPS | yes | yes |

Gemini is the only one of the three with a permanent free tier rather than a one-off credit. For
an app somebody may use for years without paying, that is the difference between "works" and
"stops after a month".

**OpenAI is deliberately not offered.** The brief rules it out, and the code has no OpenAI path
except the generic chat format documented below.

## Privacy — read this before real users

On Gemini's **free** tier, Google may use what is sent to improve its own products. Glucose
numbers are health data.

- Trying it out, or personal use: the free tier is fine.
- Before other people use the app: move to the **paid** tier. A question here is roughly 1,500
  tokens in and 400 out, which costs a small fraction of a cent. A hundred questions a day is a
  couple of euros a month. On the paid tier Google does not use the data for training.

The app also says on the screen whose words you are reading, and the server stores nothing:
only a hash of a random per-install id, the date, and a count. No glucose value, question or
answer is ever written down.

## What happens when the AI is not there

The app has its own engine (`app/src/main/kotlin/com/glucosescan/app/core/Advisor.kt`) that
reads the same numbers and writes what it sees, on the phone, in English, Somali or Arabic. It
needs no internet and no key.

If the server cannot be reached, is over its daily limit, or answers with something this app will
not show, the app answers from that engine instead and says plainly that the words came from the
phone. **It never shows its own words as if an AI had written them.** Every answer carries a
badge saying where it came from, and the badge comes from the answer itself, not from a guess.

## Switching provider

The function speaks two shapes:

- `WALHAD_AI_PROVIDER=gemini` (the default) — Google's own format.
- `WALHAD_AI_PROVIDER=openai-style` plus `WALHAD_AI_URL=…` — the common chat format that
  Mistral, DeepSeek and self-hosted models also speak.

Changing provider is one secret and one deploy. The app is not rebuilt.

## What the server refuses

1. Anything without the app secret.
2. More than 20 kB of numbers or 500 characters of question.
3. More than `WALHAD_DAILY_LIMIT` questions from one install per day (20 by default).
4. **Any answer that names a dose or tells somebody to change one.** The server re-reads every
   answer before it is sent on and drops it if it drifts into dosing. A dropped answer becomes
   "the AI could not be reached", and the phone answers instead.

That last check matters because a system prompt is a request, not a guarantee. The check is the
guarantee.
