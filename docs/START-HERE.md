# Walhad Glucose Scan — do this, in this order

Plain words, no jargon. Six small steps. Do them one at a time.

---

## Step 1 — Make your account safe (5 minutes, do this first)

You sent me your GitHub password-token in the chat. It opens all 13 of your
repositories. It must go away.

1. Open **github.com** in a browser and log in.
2. Click your picture, top right → **Settings**.
3. Scroll down, left side → **Developer settings** → **Personal access tokens** → **Tokens (classic)**.
4. Find the token → **Delete** → confirm.

**Done when:** the token is gone. (The app on GitHub stays there. Nothing breaks.)

---

## Step 2 — Put the app on your phone (5 minutes)

1. On the phone, open **github.com/Afatah01/Walhadglucosescan** in Chrome.
2. Tap **Releases** (right side) → **Walhad Glucose Scan 4.1**.
3. Tap **Walhad-GlucoseScan-v4.1.apk**. It downloads.
4. Open the file. Android asks "Allow from this source?" → **Allow**.
5. Press **Install**, then **Open**.

**Done when:** the app opens and asks about the safety notice. Read it, accept it.

---

## Step 3 — Use it for one day, nothing else (one day)

Just scan your sensor with the phone and look at the numbers. Do not switch anything
on yet.

**Done when:** you have scanned a few times and the numbers look right.

**Important:** keep LibreLink on your phone with its alarms. This app is an extra
look, not a medical device.

---

## Step 4 — Turn on the values by themselves (10 minutes)

This is the best part: the number comes by itself, one each minute, no scanning.

Use your LibreLink account — the one you already use. In LibreLink: **Settings** (or
**Share**) → **LibreLinkUp** → switch on **Share my glucose data** → invite the e-mail
you want to use. Accept the invitation in that e-mail.

Then in my app: **More** → **Values from LibreLinkUp** → type that e-mail and that
password → **Log in** → choose your name.

**Done when:** after a minute or two, the app shows a value with the word **LIVE** at
the top.

---

## Step 5 — Make the online copy (10 minutes)

This is the only "deploy". It keeps your values safe if the phone is lost, and gives
them back to a new phone.

1. On a computer, open **supabase.com** → **Start your project** → sign in with GitHub.
2. **New project**. Give it a name. Choose the region closest to you. Wait two minutes.
3. In my app: **More** → **Online copy** → **Copy the set-up text**.
4. Back on supabase.com: **SQL Editor** (left side) → paste → **Run**.
5. Top of the page: **Connect** → copy **Project URL** and **Publishable key**.
6. In my app: paste both. Type an e-mail and a password of your own → **Create account**.
7. Open the e-mail Supabase sends, click the link once → back in the app: **Log in**.

**Done when:** the app says the copy is on.

Two things to know: if you do not use it for a week, the free project sleeps — open
supabase.com, press **Restore**, wait two minutes, nothing is lost. And the free plan
keeps no backups, so the phone stays the real home of your values.

---

## Step 6 — Only if you want it: the AI (5 minutes, optional)

Skip this if you like. It explains your numbers in English, Somali or Arabic.

1. In the app: **More** → **AI** → press **Get a free key**. The browser opens.
2. Sign in with a Google account → **Create API key** → copy the key.
3. Back in the app: choose **Gemini**, paste the key, press **Save**.
4. On the Analysis screen, press **Ask the AI now**.

It is free (no card). The key must be your own — I cannot give you one, because a key is
tied to the account that makes it, and yours is the only one that should read your numbers.
An AI can be wrong — never change insulin because of it.

## What I suggest you do not do yet

- Do not pay for anything. Everything above is free.
- Do not put your LibreLink password on a server. Abbott can block accounts that do that.
- Do not delete anything from your phone before Step 5 is done.

## Tell me

Tell me how Step 4 and Step 5 go. If a screen shows an error, take a photo and send it.
These two were tested with pretended servers, never with the real ones — so you are the
first to try them for real.
