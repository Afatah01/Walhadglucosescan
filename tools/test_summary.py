#!/usr/bin/env python3
"""Prints how many tests ran and which failed, from the Gradle result files."""
import glob
import html
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest"
total = failures = errors = skipped = 0
for path in sorted(glob.glob(root + "/*.xml")):
    text = open(path, encoding="utf-8").read()
    m = re.search(r'<testsuite name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', text)
    if not m:
        continue
    name, t, s, f, e = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    total += t
    skipped += s
    failures += f
    errors += e
    mark = "" if f + e == 0 else "   <-- FAILED"
    print(f"{t:4d}  {name.split('.')[-1]}{mark}")
    for case, kind, message in re.findall(r'<testcase name="([^"]+)"[^>]*>\s*<(failure|error) message="([^"]*)"', text):
        print("        ", case, "::", html.unescape(message)[:700].replace("\n", " | "))
print(f"total {total}, failures {failures}, errors {errors}, skipped {skipped}")
