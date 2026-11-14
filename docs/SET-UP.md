# Walhad Glucose Scan 4.1 — how to set it up

Install `Walhad-GlucoseScan-v4.1.apk` over the old version. Your values and settings stay.

The app has three things that use the internet (new since version 4.0). All three are **off** until you
set them up. You find them under **More**.

---

## 1. Values from LibreLinkUp (the most important one)

The sensor stays connected to FreeStyle LibreLink by Bluetooth, always. This app takes the
values from LibreLinkUp over the internet: one value each minute, the same number as in
LibreLink.

You need a LibreLinkUp account that follows your sensor (the e-mail and password of the
**LibreLinkUp** app, not of LibreLink).

1. Open the app → **More** → **LibreLinkUp**.
2. Type the e-mail and the password. Press **Connect**.
3. Allow notifications. Press **Allow background use**.

Then Home shows **LIVE** at the top.

- If you used "Bluetooth values" in this app before: the app switches them off. Then scan
  the sensor once with FreeStyle LibreLink, and check in LibreLink that its values and
  alarms come again.
- Keep the alarms of LibreLink switched on. They need no internet.
- To stop: More → LibreLinkUp → **Switch off**. The password is removed from the phone.

---

## 2. AI (optional)

The app asks an AI to explain your numbers in plain words. The AI is Google Gemini, and it
has **a free level: no card, no payment**. The key must be **your own** — a key belongs to
the account that made it, and the app cannot carry a key of its own.

1. In the app: **More** → **AI** → press **Get a free key**. The browser opens on
   `aistudio.google.com/apikey`.
2. Sign in with a Google account → **Create API key** → copy the key (a long text that
   starts with `AIza`).
3. Back in the app: choose **Gemini**, paste the key into the field, press **Save**.
4. Open **Analysis** → go down to "Ask an AI" → choose the language (English, Somali,
   Arabic) → press **Ask the AI now**. The answer comes after some seconds.

The app asks `gemini-3.5-flash-lite`, which is free and takes 500 questions a day. If it
ever stops working (Google changes the names of its models): **More** → **AI** → **Find
models**, pick one from the list, **Save**.

- An AI can be wrong. Do not change insulin or medicine because of it.
- Give the key to nobody. On the free level Google may read what is sent, to make its own
  products better.

---

## 3. Online copy (your database)

Once, make the database:

1. In a browser open `supabase.com`. Make an account (you can sign in with GitHub). Make a
   **new project**. Wait until it is ready.
2. In the app: More → **Online copy** → **Copy the set-up text**.
3. In the Supabase project: open **SQL Editor**, paste the text, press **Run**.
4. In the Supabase project: press **Connect** at the top. Copy the **Project URL** and the
   **Publishable key**.

Then, in the app (More → Online copy):

5. Paste the Project URL and the Publishable key into the first two fields.
6. Type an e-mail and a password of your choice. Press **Create account**.
7. Supabase sends you an e-mail. Open the link in it once. (The page may show an error.
   That does not matter.)
8. Press **Log in**.

From now on the app copies new values by itself. On a new phone: log in the same way and
press **Get the values back from the copy**.

Two things about the free plan: if you do not use it for a week, Supabase puts the project
to sleep (open supabase.com, press **Restore**, wait two minutes, nothing is lost); and it
makes no backups, so keep the phone as the real home of your values. More detail, and the
other places you could use, are in `Where-to-deploy-Walhad-online.md`.

---

## If something does not work

Each of the three screens says what is wrong. LibreLinkUp also has "Technical details" →
**Copy**. Send me that text, or a photo of the screen.

These three things were built and tested with pretended servers. They were never tried
with the real LibreLinkUp, the real Gemini or the real Supabase. So please try them and
tell me what happens.
