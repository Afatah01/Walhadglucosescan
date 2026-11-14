#!/usr/bin/env python3
"""Compares the compiled classes of two APK builds, class by class.

Usage: compare_dex.py OLD_DUMP.txt NEW_DUMP.txt
The dumps are made with:  dexdump -d classes.dex > dump.txt

Things that change only because other classes were added (the number of a class,
positions in the file, index numbers of strings, types, fields and methods) are
removed before the comparison. What stays is the code itself: names, flags,
instructions, constants, line numbers.

A class that differs only in the source line numbers (lines were added above it
in the same file) is listed apart, as "moved".
"""
import re
import sys

CLASS_START = re.compile(r"^Class #\d+\s+-\s*$")
DESCRIPTOR = re.compile(r"^\s*Class descriptor\s*:\s*'(L[^']+;)'")
CODE_LINE = re.compile(r"^[0-9a-f]{6}:")
INDEX_COMMENT = re.compile(
    r"\s*//\s*(method|field|type|string|class|proto|call_site|method_handle)@[0-9a-f]+")


def classes(path):
    result = {}
    name, lines = None, []

    def close():
        if name is not None:
            result[name] = lines

    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.rstrip("\n")
        if CLASS_START.match(line):
            close()
            name, lines = "?", []
            continue
        if name is None:
            continue
        found = DESCRIPTOR.match(line)
        if found:
            name = found.group(1)
        if CODE_LINE.match(line) and "|" in line:
            line = line[line.index("|"):]                      # drop the file position and the raw bytes
            line = re.sub(r"^\|\[[0-9a-f]+\]", "|[x]", line)   # position of the method in the file
        line = INDEX_COMMENT.sub("", line)
        line = re.sub(r"(source_file_idx\s*:\s*)\d+", r"\1N", line)
        lines.append(line)
    close()
    return result


def without_line_numbers(lines):
    return [re.sub(r"(0x[0-9a-f]{4} line=)\d+", r"\1N", line) for line in lines]


def group_of(name):
    prefix = "Lcom/glucosescan/app/"
    if not name.startswith(prefix):
        return "libraries"
    rest = name[len(prefix):]
    return rest.split("/")[0] if "/" in rest else "(screens)"


def short(name):
    return name.split("/")[-1].rstrip(";")


def main():
    old, new = classes(sys.argv[1]), classes(sys.argv[2])
    print(f"classes: old {len(old)}, new {len(new)}")
    groups = {}
    for name in sorted(set(old) | set(new)):
        group = groups.setdefault(
            group_of(name), {"same": 0, "moved": [], "changed": [], "new": [], "gone": []})
        if name not in old:
            group["new"].append(short(name))
        elif name not in new:
            group["gone"].append(short(name))
        elif old[name] == new[name]:
            group["same"] += 1
        elif without_line_numbers(old[name]) == without_line_numbers(new[name]):
            group["moved"].append(short(name))
        else:
            group["changed"].append(short(name))
    for key, group in groups.items():
        print(f"{key}: identical {group['same']}; "
              f"moved {len(group['moved'])} {group['moved'][:40]}; "
              f"changed {len(group['changed'])} {group['changed'][:40]}; "
              f"new {len(group['new'])} {group['new'][:40]}; gone {group['gone']}")


if __name__ == "__main__":
    main()
