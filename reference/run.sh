#!/usr/bin/env bash
# Re-creates the reference results from the ORIGINAL Glucose Direct Swift code.
#
#   bash reference/run.sh
#
# Needs a Swift toolchain for Linux (https://www.swift.org/install/linux/).
# Set SWIFTC to the compiler if it is not on the PATH.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"

ORIGINAL_REPO="https://github.com/creepymonster/GlucoseDirect.git"
ORIGINAL_COMMIT="ffe5500a010f0e14adef0653047c9d9f8cf550d7"   # release branch, 2026-09-27
SWIFTC="${SWIFTC:-$HOME/.local/toolchains/swift/usr/bin/swiftc}"
WORK="${REF_WORK_DIR:-$HOME/.cache/glucosescan-reference}"
OUT="$ROOT/app/src/test/resources/reference/reference_vectors.txt"

mkdir -p "$WORK" "$(dirname "$OUT")"

# 1. the original source, at a fixed commit
if [ ! -d "$WORK/GlucoseDirect/.git" ]; then
  git clone -q "$ORIGINAL_REPO" "$WORK/GlucoseDirect"
fi
git -C "$WORK/GlucoseDirect" fetch -q origin "$ORIGINAL_COMMIT" 2>/dev/null || true
git -C "$WORK/GlucoseDirect" checkout -q "$ORIGINAL_COMMIT"
G="$WORK/GlucoseDirect"

# 2. the original files that hold the logic (compiled UNCHANGED)
UNCHANGED=(
  Library/Content/Calibrations/CustomCalibration.swift
  Library/Content/SensorReading.swift
  Library/Content/SensorReadingError.swift
  Library/Content/SensorType.swift
  Library/Content/SensorRegion.swift
  Library/Content/SensorState.swift
  Library/Content/SensorFamily.swift
  Library/Content/Sensor.swift
  Library/Content/SensorGlucose.swift
  Library/Content/SensorTrend.swift
  Library/Content/Glucose.swift
  Library/Content/GlucoseUnit.swift
  Library/Extensions/Data.swift
  Library/Extensions/Date.swift
  Library/Extensions/LocalizedString.swift
  Library/DirectConfig.swift
)

BUILD="$WORK/build"
rm -rf "$BUILD"; mkdir -p "$BUILD"
SOURCES=()
for f in "${UNCHANGED[@]}"; do
  SOURCES+=("$G/$f")
done

# Two original files keep helpers and tables file-private. A copy of each gets a
# few lines appended (Hooks*.swift) so the harness can reach them. The original
# text of both files is not changed.
cat "$G/App/Modules/SensorConnector/SensorUtility.swift" "$HERE/Hooks.swift" > "$BUILD/SensorUtility+Hooks.swift"
cat "$G/Library/Content/Calibrations/FactoryCalibration.swift" "$HERE/HooksCalibration.swift" > "$BUILD/FactoryCalibration+Hooks.swift"

# LibreNFC.swift needs Apple's CoreNFC, so the file cannot be compiled here. The one function
# that builds the "enable streaming" NFC command is pure logic. Its text, and the text of the
# two small types it uses, are cut out of the original file as they are and put into a file
# of their own. Nothing of that text is changed.
NFC="$G/App/Modules/SensorConnector/LibreConnection/LibreNFC.swift"
{
  echo "import Foundation"
  echo
  echo "struct NfcCommandCopy {"
  awk '/^    private func nfcCommand\(/{on=1} on{print} on&&/^    }$/{on=0}' "$NFC"
  echo
  echo "    func hook_enableStreaming(unlockCode: UInt32, patchInfo: Data, sensorUID: Data) -> (UInt8, Data) {"
  echo "        let command = nfcCommand(.enableStreaming, unlockCode: unlockCode, patchInfo: patchInfo, sensorUID: sensorUID)"
  echo "        return (command.code, command.parameters)"
  echo "    }"
  echo "}"
  echo
  awk '/^private struct NFCCommand \{/{on=1} on{print} on&&/^}$/{on=0}' "$NFC"
  echo
  awk '/^private enum Subcommand/{on=1} on{print} on&&/^}$/{on=0}' "$NFC"
} > "$BUILD/NfcCommand+Copy.swift"
grep -q "private func nfcCommand" "$BUILD/NfcCommand+Copy.swift" || { echo "nfcCommand was not found in LibreNFC.swift" >&2; exit 1; }
grep -q "case enableStreaming = 0x1E" "$BUILD/NfcCommand+Copy.swift" || { echo "Subcommand was not found in LibreNFC.swift" >&2; exit 1; }

SOURCES+=("$BUILD/SensorUtility+Hooks.swift" "$BUILD/FactoryCalibration+Hooks.swift" "$BUILD/NfcCommand+Copy.swift" "$HERE/Shims.swift" "$HERE/main.swift")

# 3. compile and run (UTC, so that minute rounding does not depend on the machine)
"$SWIFTC" -O -o "$BUILD/refgen" "${SOURCES[@]}"
( cd "$HERE" && TZ=UTC "$BUILD/refgen" "$OUT" "$HERE/samples/libre2_examples.txt" )
echo "original commit: $(git -C "$G" rev-parse HEAD)"
echo "sha256: $(sha256sum "$OUT" | cut -d' ' -f1)"
