# Which AI answers, and why

The brief excludes OpenAI. Of the remaining providers, the app uses
**Google Gemini**, through a server function that you own.

## The choice

| | Google Gemini | Anthropic Claude | DeepSeek / others |
|---|---|---|---|
| Permanent free tier | **Yes** — Flash and Flash-Lite models | No — trial credits only | No |
| Paid price, budget model | `gemini-3.5-flash-lite` $0.30 / $2.50 per million tokens | Haiku 5.5 $0.10 / $0.50 | cheap, but no standing free tier |
| Numbers and tables | good on the Flash models | good | good |
| Medical language | good | good | good |
| Data used for training | free tier: yes; **paid tier: no** | no | read their terms |
| Works from a serverless function | yes, plain HTTP | yes | yes |

Gemini is the only one of the three with a standing free tier [1](https://aionx.co/technical-ai-tutorials/ai-api-comparison-complete/), and Anthropic has none at all [4](https://useweckr.com/how-to/ai-api-free-tiers). That alone decides it for an app that somebody may use for years without paying: the owner can start at zero cost and move to the paid tier when there are real users.

**Model:** `gemini-3.5-flash-lite` — 500 questions a day on the free tier, fast, and good enough for "what do these numbers say". It is set in one place (`WALHAD_AI_MODEL`) and can be changed without touching the app.

## The privacy point, said plainly

On Gemini's **free** tier, Google may use what is sent to improve its own
products. Glucose numbers are health data. So:

- to try it out: the free tier is fine;
- before other people use the app: move to the **paid** tier. One question here
  is roughly 1,500 tokens in and 400 out, a small fraction of a cent. A hundred
  questions a day costs a couple of euros a month, and Google then does not use
  the data for training.

The app also says this on the screen, so the person wearing the sensor knows
where their numbers go.

## If you want a different provider

The function speaks two shapes:

- `WALHAD_AI_PROVIDER=gemini` (default) — Google's own format.
- `WALHAD_AI_PROVIDER=openai-style` with `WALHAD_AI_URL=…` — the common chat
  format, which Mistral, DeepSeek and self-hosted models also speak.

OpenAI is not offered, as the brief requires. Changing provider is one secret
and one deploy; the app is not touched.

## What happens when the AI is not there

The app has its own engine (`core/Advisor.kt`) that reads the same numbers and
writes what it sees, in English, Somali or Arabic, on the phone. When the server
does not answer, the app shows that instead and says so in as many words:
*"Answered on your phone, not by an AI service."* It never pretends an AI answer
was given.
