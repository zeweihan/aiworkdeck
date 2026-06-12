#!/usr/bin/env bash
# Sign Mach-O binaries that Apple notarization inspects but electron-builder
# does not reach (Epic #18 T2):
#   1. the jlink-trimmed JRE in <bundle-dir>/jre
#   2. native libs nested inside the Spring Boot fat jar's BOOT-INF/lib/*.jar
#      (bytedeco opencv/ffmpeg dylibs ship adhoc/linker-signed, which
#      notarization rejects)
#
# Only unsigned/adhoc binaries are re-signed; vendor-signed ones (Temurin JRE,
# Playwright's node) keep their valid Developer ID signatures. Nested jars are
# spliced back into the fat jar with zip -0 (stored) — the Spring Boot loader
# requires nested jars to be uncompressed.
#
# Usage: sign-mac-natives.sh <identity> <bundle-dir> <entitlements-plist>
set -euo pipefail

IDENTITY="$1"
BUNDLE_DIR="$2"
ENTITLEMENTS="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"

needs_sign() {
  local info
  if ! info=$(codesign -dv "$1" 2>&1); then
    return 0 # unsigned
  fi
  echo "$info" | grep -q 'flags=.*adhoc' && return 0
  return 1 # validly signed (vendor Developer ID) — leave intact
}

sign_file() {
  local f="$1"
  local args=(--force --timestamp --options runtime --sign "$IDENTITY")
  if file -b "$f" | grep -q 'executable'; then
    args+=(--entitlements "$ENTITLEMENTS")
  fi
  codesign "${args[@]}" "$f"
  echo "  signed: $f"
}

# --- 1) JRE ---------------------------------------------------------------
find "$BUNDLE_DIR/jre" -type f | while read -r f; do
  file -b "$f" | grep -q 'Mach-O' || continue
  needs_sign "$f" || continue
  sign_file "$f"
done

# --- 2) natives nested in the fat jar --------------------------------------
JAR="$(cd "$BUNDLE_DIR" && pwd)/backend.jar"
WORK="$(mktemp -d)"
(cd "$WORK" && unzip -qq "$JAR" 'BOOT-INF/lib/*.jar')

for libjar in "$WORK"/BOOT-INF/lib/*.jar; do
  # candidate native entries: dylib/jnilib/so plus extension-less files —
  # bytedeco also ships bare CLI executables (ffmpeg, ffprobe, tesseract,
  # opencv_*) and python .so bindings that notarization rejects when
  # adhoc-signed/unsigned. The Mach-O magic check below filters out
  # non-binary matches (LICENSE etc.). Paths contain no whitespace in
  # practice.
  natives=$(unzip -Z1 "$libjar" | grep -vE '/$' | grep -E '\.(dylib|jnilib|so)$|(^|/)[^./]+$' || true)
  [ -n "$natives" ] || continue

  EXT="$(mktemp -d)"
  (cd "$EXT" && unzip -qq "$libjar" $natives)

  changed=0
  while IFS= read -r entry; do
    f="$EXT/$entry"
    [ -f "$f" ] || continue
    file -b "$f" | grep -q 'Mach-O' || continue
    needs_sign "$f" || continue
    sign_file "$f"
    changed=1
  done <<< "$natives"
  [ "$changed" -eq 1 ] || continue

  (cd "$EXT" && zip -qq "$libjar" $natives)
  rel="BOOT-INF/lib/$(basename "$libjar")"
  (cd "$WORK" && zip -qq -0 -X "$JAR" "$rel")
  echo "re-signed natives in $rel"
done

echo "sign-mac-natives done: $BUNDLE_DIR"
