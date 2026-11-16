#!/bin/sh
# Container entrypoint: refreshes plugins (SkyCore + pinned jars), copies config templates on first
# run only, then runs Paper as PID 1 so `docker stop` triggers a clean save (SIGTERM).
set -eu
/opt/skycore/scripts/prepare-server.sh /data
if [ "$(printf '%s' "$EULA" | tr '[:upper:]' '[:lower:]')" = "true" ]; then
  echo "eula=true" > /data/eula.txt
fi
if ! grep -q "eula=true" /data/eula.txt; then
  echo "You must accept the Minecraft EULA (https://aka.ms/MinecraftEULA): set EULA=true in docker-compose.yml." >&2
  exit 1
fi
cd /data
exec java -Xms"$MEMORY" -Xmx"$MEMORY" \
  -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 \
  -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 \
  -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
  -Duser.timezone="$TZ" -jar paper.jar --nogui
