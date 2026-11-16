#!/bin/sh
# Prepares a server directory: Paper jar, config templates (first run only), plugins (always refreshed).
# Usage: scripts/prepare-server.sh <server-dir>
# Used by scripts/run-local.sh and by the Docker entrypoint.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:?server dir}"
PAPER_JAR="${PAPER_JAR:-$ROOT/paper-26.3-253.jar}"
SKYCORE_JAR="${SKYCORE_JAR:-$ROOT/skycore/build/libs/SkyCore.jar}"
PLUGIN_CACHE="${PLUGIN_CACHE:-$ROOT/build/plugins}"

mkdir -p "$DIR/plugins" "$DIR/config"
cp "$PAPER_JAR" "$DIR/paper.jar"

# Config templates are only copied when missing, so live edits are never overwritten.
for f in server.properties bukkit.yml spigot.yml server-icon.png config/paper-global.yml config/paper-world-defaults.yml; do
  [ -f "$DIR/$f" ] || cp "$ROOT/server/$f" "$DIR/$f"
done
[ -f "$DIR/eula.txt" ] || echo "eula=${EULA:-false}" > "$DIR/eula.txt"

"$ROOT/scripts/fetch-plugins.sh" "$PLUGIN_CACHE" "$ROOT/plugins.lock"
grep -v '^#' "$ROOT/plugins.lock" | grep -v '^$' | cut -d'|' -f1 | while read -r name; do
  cp "$PLUGIN_CACHE/$name" "$DIR/plugins/$name"
done
cp "$SKYCORE_JAR" "$DIR/plugins/SkyCore.jar"
echo "Server prepared in $DIR"
