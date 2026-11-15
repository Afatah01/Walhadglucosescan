#!/usr/bin/env python3
"""Publishes version 4.3.0 on GitHub and points the page at it.

    python3 tools/publish-4.3.py <github token>

What it does, in this order:

  1. pushes the two commits that are waiting (the built-in AI, the fixed cards)
  2. makes the release v4.3.0 as a draft -- a published release of this
     repository can never take files, because the repository is immutable
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
TAG = "v4.3.0"
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = "/home/user/Walhad-GlucoseScan-v4.3.apk"
SHEET = os.path.join(HERE, "docs", "screens-sheet.png")
PAGE = os.path.join(HERE, "docs", "index.html")

NOTES = """## The AI is built in

Two services are offered, and only these two: **Gemini** and **OpenAI**. The app
fills in the address and the model by itself, so the only thing left is to paste
your own key. The button **Get a free key** opens the page where that key is made.

- **Gemini** — free of charge, 500 questions a day. The model is `gemini-3.5-flash-lite`.
- **OpenAI** — no free models; one question here costs a small fraction of a cent.
- **Find models** asks the service which models it has and marks the free ones. When the
  service cannot be reached, the models the app knows are offered instead.
- A key that the service refuses is said out loud, because no model helps then.

## The page

The feature cards stood squeezed on a phone: one rule put the last card into a second
column, and the browser made that column even when there is only room for one. The six
other cards were pressed into 46 px. The rule now knows how many columns there are.

## Installing

Download the APK below and open it. The certificate is the one used since version 1.0,
so version 4.2 is replaced and nothing is lost.

**This app is not a medical device. Keep LibreLink and its alarms on. Never change
insulin because of a number in this app.**

610 tests pass, lint reports no error. The classes of every package but the AI screen
are byte for byte the same as in 4.2 (`tools/compare_dex.py`).
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
        sys.exit("usage: python3 tools/publish-4.3.py <github token>")
    token = sys.argv[1].strip()
    size = os.path.getsize(APK)
    digest = hashlib.sha256(open(APK, "rb").read()).hexdigest()
    print("APK %d bytes, sha256 %s" % (size, digest))

    print("1 pushing the two commits ...")
    git(token, "push", "origin", "main")

    print("2 making the draft release %s ..." % TAG)
    release = call(token, "POST", "/repos/%s/releases" % REPO,
                   {"tag_name": TAG, "name": "Walhad Glucose Scan 4.3.0", "body": NOTES, "draft": True, "prerelease": False})
    number = release["id"]

    print("3 uploading the files ...")
    for path, ctype in ((APK, "application/vnd.android.package-archive"), (SHEET, "image/png")):
        name = os.path.basename(path)
        raw = open(path, "rb").read()
        call(token, "POST", "/repos/%s/releases/%d/assets?name=%s" % (REPO, number, name), raw=raw, ctype=ctype,
             host="https://uploads.github.com")
        print("   %s (%d bytes)" % (name, len(raw)))

    print("4 publishing ...")
    call(token, "PATCH", "/repos/%s/releases/%d" % (REPO, number), {"draft": False})
    address = "https://github.com/%s/releases/download/%s/Walhad-GlucoseScan-v4.3.apk" % (REPO, TAG)
    print("   %s" % address)

    print("5 pointing the page at it ...")
    page = open(PAGE, encoding="utf-8").read()
    page, n = re.subn(r"releases/download/v4\.2\.1/Walhad-GlucoseScan-v4\.2\.apk", address, page)
    print("   %d download links" % n)
    page, m = re.subn(r"<span>Version 4\.2</span>", "<span>Version 4.3</span>", page)
    print("   %d version line(s)" % m)
    open(PAGE, "w", encoding="utf-8").write(page)

    print("6 committing and pushing the page ...")
    subprocess.run(["git", "-C", HERE, "add", "docs/index.html"], check=True)
    subprocess.run(["git", "-C", HERE, "-c", "user.name=Abdifatah Elmi",
                    "-c", "user.email=137742037+Afatah01@users.noreply.github.com",
                    "commit", "-m", "Version 4.3 on the download buttons"], check=True)
    git(token, "push", "origin", "main")
    print("done")


if __name__ == "__main__":
    main()
