#!/usr/bin/env python3
"""Writes CalibrationTables.kt from the two number tables in the original
FactoryCalibration.swift (Glucose Direct). The numbers are copied as text.

    python3 tools/generate_calibration_tables.py <path to FactoryCalibration.swift>
"""
import re
import sys
from pathlib import Path

src = Path(sys.argv[1]).read_text()
out = Path(__file__).resolve().parent.parent / "app/src/main/kotlin/com/glucosescan/app/libre/CalibrationTables.kt"


def table(name):
    body = re.search(r"private let %s = \[(.*?)\n\]" % name, src, re.S).group(1)
    values = [v.strip() for v in body.replace("\n", " ").split(",") if v.strip()]
    assert len(values) == 1023, (name, len(values))
    return values


def kotlin(name, values):
    lines = []
    for i in range(0, len(values), 8):
        chunk = values[i:i + 8]
        lines.append("    " + ", ".join(v if ("." in v or "e" in v.lower()) else v + ".0" for v in chunk) + ",")
    return "internal val %s: DoubleArray = doubleArrayOf(\n%s\n)\n" % (name, "\n".join(lines))


text = """// GENERATED FILE. Do not edit by hand.
// Made by tools/generate_calibration_tables.py from the tables t1 and t2 in
// Library/Content/Calibrations/FactoryCalibration.swift of Glucose Direct
// (MIT License, Copyright (c) 2023 Reimar Metzen).
//
// Each table has 1023 rows. The row is chosen by a number that the factory
// stored in the sensor (i2).

package com.glucosescan.app.libre

""" + kotlin("CALIBRATION_T1", table("t1")) + "\n" + kotlin("CALIBRATION_T2", table("t2"))
out.write_text(text)
print("wrote", out, len(text), "bytes")
