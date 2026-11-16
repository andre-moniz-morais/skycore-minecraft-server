#!/bin/sh
# Builds SkyCore and runs the server directly on this machine (no Docker). Requires Java 25+.
# Usage: scripts/run-local.sh [server-dir]   (default: ./run)
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/run}"
(cd "$ROOT/skycore" && ./gradlew build -q)
"$ROOT/scripts/prepare-server.sh" "$DIR"
if ! grep -q "eula=true" "$DIR/eula.txt"; then
  echo "Read https://aka.ms/MinecraftEULA and set eula=true in $DIR/eula.txt (or run with EULA=true)." >&2
  exit 1
fi
cd "$DIR"
exec java -Xms${MEMORY:-4G} -Xmx${MEMORY:-4G} -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 \
  -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
  -Duser.timezone=${TZ:-Europe/Lisbon} -jar paper.jar --nogui
