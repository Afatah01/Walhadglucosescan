#!/usr/bin/env bash
# For a build machine with little memory (about 2 GB): adds 4 GB of swap space and tells
# Gradle to use little memory. On a normal PC you do not need this.
#
#   bash tools/low-memory.sh
#
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$HERE/env.sh"

if ! sudo swapon --show 2>/dev/null | grep -q /swapfile; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
fi

mkdir -p "$GRADLE_USER_HOME"
cat > "$GRADLE_USER_HOME/gradle.properties" <<'PROPS'
org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8
org.gradle.workers.max=1
org.gradle.parallel=false
org.gradle.daemon=false
org.gradle.vfs.watch=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false
PROPS
free -m | sed -n 1,3p
echo "[low-memory] done"
