#!/usr/bin/env python3
"""Publishes version 5.0.0 on GitHub and points the page at it.

    python3 tools/publish-5.0.py <github token>

What it does, in this order:

  1. pushes the commits that are waiting
  2. makes the release v5.0.0 as a draft -- this repository is immutable, so a
     published release can never take files
  3. uploads the APK and the sheet of screenshots
  4. publishes the release
  5. writes the new address into every download button of docs/index.html
  6. commits and pushes that

The token is only an argument here. It is not written down anywhere.
"""
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "Afatah01/Walhadglucosescan"
LOGIN = "Afatah01"
TAG = "v5.0.0"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = "/home/user/Walhad-GlucoseScan-v5.0.apk"
SHEET = os.path.join(HERE, "docs", "screens-sheet.png")
PAGE = os.path.join(HERE, "docs", "index.html")

NOTES = """## The AI is built in, and there is nothing to fill in

There is no key to paste and no provider to pick. The app knows one address,
which is a small server this project owns; the key that costs money lives on
that server and is never inside the app.

- **Gemini** is the provider, through that server. OpenAI is not used anywhere.
  `docs/AI-PROVIDER.md` says why, and what the free tier means for health data.
- **The phone always answers.** No internet, no address, daily limit used up:
  the app reads the numbers itself and says so. It never shows its own words as
  if an AI had written them. Every answer carries a badge saying where it came
  from.
- The server re-reads every answer and **drops any that names a dose** or tells
  somebody to change one.

## Insights, a fifth tab

Seven / 14 / 30 / 90 days, seven ready-made questions to press, a box for your
own, and follow-ups that hang on to the last exchange.

## A report a doctor can read

Three pages. New: how steady the values were (CV, SD, estimated HbA1c, how much
of the day has values), every low and every high with the time it began, how
long it lasted and its lowest or highest value, and what is worth raising at the
appointment. It reaches back 1, 7, 14, 30 or 90 days.

## A screen of its own for scanning

Pictures the sensor, shows the number the sensor itself gives over NFC (or says
it is not available -- it never invents one), and says plainly what the phone
cannot do: NFC does not work with the screen off, and a Libre 3 has no NFC at
all.

## The home screen answers nine questions

In target range, average, lowest, highest, time low, time high, variation (CV),
estimated HbA1c, and how many lows and highs there were.

## Installing

Download the APK below and open it. The certificate is the one used since
version 1.0, so version 4.3 is replaced and nothing is lost.

**This app is not a medical device. Keep LibreLink and its alarms on. Never
change insulin because of a number in this app.**

657 tests pass, lint reports no error.

**NFC has not been tested on real hardware.** `docs/NFC-COMPATIBILITY.md` lists
the six checks. Please try them on your own sensor before trusting the number.
"""


def call(token, method, path, body=None, raw=None, ctype="application/octet-stream", host="https://api.github.com"):
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
    request = urllib.request.Request(host + path, data=data, method=method)
    request.add_header("Authorization", "Bearer " + token)
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    request.add_header("User-Agent", "walhad-publish")
    if data is not None:
        request.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(request) as answer:
            text = answer.read().decode("utf-8")
            return json.loads(text) if text.strip() else {}
    except urllib.error.HTTPError as e:
        print("!! %s %s -> %d\n%s" % (method, path, e.code, e.read().decode("utf-8", "replace")[:900]))
        raise


def git(token, *args):
    basic = base64.b64encode(("%s:%s" % (LOGIN, token)).encode()).decode()
    subprocess.run(["git", "-C", HERE, "-c", "http.extraHeader=Authorization: Basic " + basic] + list(args), check=True)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: python3 tools/publish-5.0.py <github token>")
    token = sys.argv[1].strip()
    size = os.path.getsize(APK)
    digest = hashlib.sha256(open(APK, "rb").read()).hexdigest()
    print("APK %d bytes, sha256 %s" % (size, digest))

    print("1 pushing the commits ...")
    git(token, "push", "origin", "main")

    print("2 making the draft release %s ..." % TAG)
    release = call(token, "POST", "/repos/%s/releases" % REPO,
                   {"tag_name": TAG, "name": "Walhad Glucose Scan 5.0.0", "body": NOTES,
                    "draft": True, "prerelease": False})
    number = release["id"]

    print("3 uploading the files ...")
    for path, ctype in ((APK, "application/vnd.android.package-archive"), (SHEET, "image/png")):
        name = os.path.basename(path)
        raw = open(path, "rb").read()
        call(token, "POST", "/repos/%s/releases/%d/assets?name=%s" % (REPO, number, name), raw=raw,
             ctype=ctype, host="https://uploads.github.com")
        print("   %s (%d bytes)" % (name, len(raw)))

    print("4 publishing ...")
    call(token, "PATCH", "/repos/%s/releases/%d" % (REPO, number), {"draft": False})
    address = "https://github.com/%s/releases/download/%s/Walhad-GlucoseScan-v5.0.apk" % (REPO, TAG)
    print("   %s" % address)

    print("5 pointing the page at it ...")
    page = open(PAGE, encoding="utf-8").read()
    page, n = re.subn(r"releases/download/v4\.3\.0/Walhad-GlucoseScan-v4\.3\.apk", address, page)
    print("   %d download links" % n)
    page, m = re.subn(r"<span>Version 4\.3</span>", "<span>Version 5.0</span>", page)
    print("   %d version line(s)" % m)
    open(PAGE, "w", encoding="utf-8").write(page)

    print("6 committing and pushing the page ...")
    subprocess.run(["git", "-C", HERE, "add", "docs/index.html"], check=True)
    subprocess.run(["git", "-C", HERE, "-c", "user.name=Abdifatah Elmi",
                    "-c", "user.email=137742037+Afatah01@users.noreply.github.com",
                    "commit", "-m", "Version 5.0 on the download buttons"], check=True)
    git(token, "push", "origin", "main")
    print("done")


if __name__ == "__main__":
    main()
