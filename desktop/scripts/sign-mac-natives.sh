#!/usr/bin/env bash
# Sign Mach-O binaries that Apple notarization inspects but electron-builder
# does not reach (Epic #18 T2):
#   1. the jlink-trimmed JRE in <bundle-dir>/jre
#   2. native libs nested inside the backend dependency jars in
#      <bundle-dir>/backend/lib/*.jar (bytedeco opencv/ffmpeg dylibs ship
#      adhoc/linker-signed, which notarization rejects)
#
# Only unsigned/adhoc binaries are re-signed; vendor-signed ones (Temurin JRE,
# Playwright's node) keep their valid Developer ID signatures.
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
  # Apple 时间戳服务在大批量签名下会抖动（"The timestamp service is not
  # available"，run 29552522258），单次失败直接挂整个 release 构建——退避重试
  local attempt
  for attempt in 1 2 3; do
    if codesign "${args[@]}" "$f" 2>/dev/null; then
      echo "  signed: $f"
      return 0
    fi
    echo "  codesign failed (attempt $attempt), retrying: $f" >&2
    sleep $((attempt * 10))
  done
  codesign "${args[@]}" "$f" # 最后一次不吞 stderr，把真实错误暴露给构建日志
  echo "  signed: $f"
}

# --- 1) 捆绑的运行时（jlink JRE + python-build-standalone + pip 装的 C 扩展） ---
for runtime_dir in "$BUNDLE_DIR/jre" "$BUNDLE_DIR/python" "$BUNDLE_DIR/pysvc"; do
  [ -d "$runtime_dir" ] || continue
  find "$runtime_dir" -type f | while read -r f; do
    file -b "$f" | grep -q 'Mach-O' || continue
    needs_sign "$f" || continue
    sign_file "$f"
  done
done

# --- 2) natives nested in the backend dependency jars -----------------------
# 增量更新拆分（设计 §4.1）后依赖 jar 是 backend/lib/ 下的独立文件，直接原地
# 更新条目即可——不再需要旧 fat jar 时代的 zip -0 回写（嵌套 jar 免压缩是
# Spring Boot loader 的要求，classpath 直启没有这个约束）。
LIBDIR="$BUNDLE_DIR/backend/lib"

for libjar in "$LIBDIR"/*.jar; do
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
  echo "re-signed natives in backend/lib/$(basename "$libjar")"
done

echo "sign-mac-natives done: $BUNDLE_DIR"
