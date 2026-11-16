#!/bin/sh
# Downloads the pinned external plugins listed in plugins.lock into the given folder
# and verifies each SHA-512. Usage: scripts/fetch-plugins.sh <target-dir> [plugins.lock]
set -eu
TARGET="${1:?target dir}"
LOCK="${2:-$(dirname "$0")/../plugins.lock}"
mkdir -p "$TARGET"
grep -v '^#' "$LOCK" | grep -v '^$' | while IFS='|' read -r name url sha; do
  if [ -f "$TARGET/$name" ] && [ "$( (sha512sum "$TARGET/$name" 2>/dev/null || shasum -a 512 "$TARGET/$name") | cut -d' ' -f1)" = "$sha" ]; then
    echo "ok      $name"
    continue
  fi
  echo "fetch   $name"
  curl -fsSL -A "skycore-server-setup" -o "$TARGET/$name.part" "$url"
  got="$( (sha512sum "$TARGET/$name.part" 2>/dev/null || shasum -a 512 "$TARGET/$name.part") | cut -d' ' -f1)"
  if [ "$got" != "$sha" ]; then
    echo "SHA-512 mismatch for $name" >&2
    rm -f "$TARGET/$name.part"
    exit 1
  fi
  mv "$TARGET/$name.part" "$TARGET/$name"
done
