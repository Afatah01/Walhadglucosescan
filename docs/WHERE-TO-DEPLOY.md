# Where to put the online parts of Walhad Glucose Scan (free plans)

Checked in November 2026. Prices and limits can change, so the numbers below are what the
providers say today.

## First: what of the app needs the internet at all?

Three things, and only three. Each one stays off until you set it up.

| Thing | Who runs it | Do you need to deploy something? |
| --- | --- | --- |
| Values from LibreLinkUp | Abbott (their servers) | No. You only give the app your LibreLink login. |
| The AI explanation | Google / OpenAI | No. You only make a key and paste it in the app. |
| Online copy of your values | **Your database — you choose** | **Yes. This is the only thing you deploy.** |

So "where do I deploy?" really means: **where does my database live?** The app is a phone
app; it does not run on Render or anywhere else.

## My advice: Supabase, free plan

Because the app already speaks Supabase. The code is written, tested (603 tests) and in
your repository. Any other database means new code in the app plus a small server in
between, which is at least half a day of work and more that can break.

**What the free plan gives** (supabase.com, free, no card):

- 500 MB database, 1 GB files, 5 GB traffic out per month
- Unlimited API calls, 50,000 users
- 2 projects
- **Not** included: automatic backups, logs older than one day
- **The catch:** a project you do not use for **one week goes to sleep**

**500 MB is a lot for this app.** One value is about 50 bytes. Even one value every minute
is about 2 MB a month. 500 MB holds more than twenty years of that.

**About the sleep.** If you do not open the app for a week, Supabase pauses the free
project. Your data is not lost (it is kept for up to a year). To wake it: open
supabase.com, choose the project, press **Restore**, wait two minutes. After that the app
works again. If you use the app every day, it never sleeps. The paid plan ($25 a month)
never sleeps and makes backups.

## The steps (Supabase)

1. Open `supabase.com`, make an account (you can sign in with GitHub). Press **New
   project**. Choose the region closest to you. Wait about two minutes.
2. On the phone: More → **Online copy** → **Copy the set-up text**.
3. In Supabase: **SQL Editor** → paste the text → **Run**. This makes the table
   `glucose_values` and the rule that only you can read it.
4. In Supabase: press **Connect** at the top. Copy the **Project URL** and the
   **Publishable key** (the long one that is safe to put in an app).
5. In the app: paste the two, then type an e-mail and a password of your own and press
   **Create account**.
6. Open the e-mail Supabase sends and click the link once. (The page may show an error —
   that does not matter.) Press **Log in** in the app.

From then on the app copies new values by itself. On a new phone: log in the same way and
press **Get the values back from the copy**.

**Keep this in mind:** the free plan has no backups. The phone stays the real home of your
values; more → Report gives you a PDF you can keep.

## The other choices (and why I would not take them for this app)

| Where | Free plan | Why not for us today |
| --- | --- | --- |
| **Neon** (Postgres) | 0.5–1 GB, sleeps after 5 minutes of no use, wakes in under a second, no card | A very good database. But it has no login and no rule system, so I would have to write a small server and change the app. Pick this if you want plain SQL that you can open with any tool. |
| **Firebase** (Firestore) | 1 GB, 50,000 reads and 20,000 writes **a day** | The daily limit is the problem: one screen showing 14 days is 20,000 values — one look uses up the day. Also new code in the app. |
| **Turso, PocketBase, MongoDB Atlas** | 5–9 GB, generous | Good services, but each one means new code in the app. PocketBase also needs a server that stays on. |
| **Render** (web service or Postgres) | Web service sleeps after 15 minutes; the free Postgres is deleted after 30 days unless you add a card | Fine for a small server you test with. Not for data you want to keep. |
| **Fly.io, Railway, Koyeb** | Small always-on servers, mostly need a card | Only if you later want a server of your own (see below). |

## If you want more than the database

- **A download page for the app** (what it is, pictures, the APK). Best place: **GitHub
  Pages** or **Cloudflare Pages**. Free, never sleeps, works straight from your
  repository. Say the word and I write the page, push it, and give you the two clicks to
  switch it on. Render is the wrong tool for this: its free web service sleeps after 15
  minutes and takes half a minute to wake up.
- **A web page that shows your values** (for you, or for family). Possible: it can read
  the same Supabase table. It is a static page, so it can also sit on GitHub Pages for
  free. Your data stays protected by the same rule.
- **A server that fetches LibreLinkUp for you.** I advise against it. Abbott can block
  accounts that are used this way, and the app already gets the values on the phone
  without any server.

## What I suggest now

1. Make the Supabase project with the six steps above (about ten minutes).
2. Tell me when it is there, or send me a photo of the screen if something looks wrong.
3. If you also want the download page, say "make the page" and I will build it and put it
   in your repository.

Nothing here was tried with the real Supabase yet — the app was tested against a pretended
one. So please try it and tell me what happens.
