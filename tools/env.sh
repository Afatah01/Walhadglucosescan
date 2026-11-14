# Source this file before building:   source tools/env.sh
# It only sets where the build tools live. Nothing is installed by this file.
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-/usr/lib/jvm/java-21-openjdk-amd64}"
export TOOLS_DIR="${TOOLS_DIR:-$HOME/.local/toolchains}"
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS_DIR/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# Keep the big download caches out of the project folder.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.cache/gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.cache/android-user}"
export TEST_USER_HOME="${TEST_USER_HOME:-$HOME/.cache/test-home}"
export ROBOLECTRIC_REPO="${ROBOLECTRIC_REPO:-$HOME/.cache/robolectric-m2}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
