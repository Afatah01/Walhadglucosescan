#!/usr/bin/env bash
# One-time setup of the build tools on a fresh Debian/Ubuntu Linux machine.
# (On a normal PC you do not need this: just open the project in Android Studio.)
#
#   bash tools/setup-build-env.sh
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/env.sh"

CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
ANDROID_PLATFORM="platforms;android-35"
ANDROID_BUILD_TOOLS="build-tools;35.0.0"

mkdir -p "$TOOLS_DIR" "$ANDROID_HOME" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$TEST_USER_HOME"

echo "[setup] 1/3 Java 21"
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends openjdk-21-jdk-headless unzip zip curl
fi
"$JAVA_HOME/bin/java" -version 2>&1 | head -1

echo "[setup] 2/3 Android command line tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fsSL -o "$TOOLS_DIR/cmdline-tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  rm -rf "$ANDROID_HOME/cmdline-tools/tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/tmp"
  unzip -q "$TOOLS_DIR/cmdline-tools.zip" -d "$ANDROID_HOME/cmdline-tools/tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$ANDROID_HOME/cmdline-tools/tmp" "$TOOLS_DIR/cmdline-tools.zip"
fi

echo "[setup] 3/3 Android SDK packages"
yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" | grep -v '^\[' | tail -3 || true
sdkmanager --list_installed 2>/dev/null | sed -n '1,12p'

echo "[setup] DONE-ANDROID"
