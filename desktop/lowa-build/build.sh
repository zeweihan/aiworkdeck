#!/usr/bin/env bash
# Build a zh-CN-enabled LibreOffice WASM (LOWA) engine for AI Workdeck (#66).
#
# RUN ON A BUILD MACHINE WITH >=64 GB RAM, many cores, ~100 GB disk. NOT in CI's
# default runners, NOT in the agent sandbox. Hours of build time. This is a
# researched scaffold — expect to iterate on the build host (LO WASM is finicky).
#
# Output: $OUT/{soffice.js,soffice.wasm,soffice.data,soffice.data.js.metadata}
# Feed those into desktop/scripts/fetch-lowa-assets.js (point LOWA_CDN at them, or
# copy locally) to self-host the Chinese engine in the installer.
set -euo pipefail

# ---- config (override via env) ----------------------------------------------
ROOT="${ROOT:-$HOME/lowa-build}"            # working root
OUT="${OUT:-$ROOT/out}"                      # artifacts land here
CORES="${CORES:-$(nproc 2>/dev/null || echo 8)}"
EMSDK_REPO="${EMSDK_REPO:-https://github.com/allotropia/emscripten}"
EMSDK_REF="${EMSDK_REF:-fixed-3.1.65}"       # allotropia emscripten fork for LO 24.2 WASM
QT5_REPO="${QT5_REPO:-https://github.com/allotropia/qt5}"
QT5_REF="${QT5_REF:-5.15.2+wasm}"
LO_REPO="${LO_REPO:-https://git.libreoffice.org/core}"
LO_REF="${LO_REF:-distro/allotropia/zeta-24-2}"   # MUST match the engine we embed
QT5DIR="${QT5DIR:-$ROOT/qt5-wasm-install}"
HERE="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$ROOT" "$OUT"

# ---- 1. emscripten (allotropia fork) ----------------------------------------
if [ ! -d "$ROOT/emsdk" ]; then
  git clone "$EMSDK_REPO" "$ROOT/emsdk"
fi
cd "$ROOT/emsdk"
git fetch --all --tags
git checkout "$EMSDK_REF"
# The fork pins the SDK version it needs; 'latest' here follows the fork's default.
./emsdk install latest
./emsdk activate latest
# shellcheck disable=SC1091
source ./emsdk_env.sh

# ---- 2. Qt5 for wasm (allotropia fork) --------------------------------------
if [ ! -d "$ROOT/qt5" ]; then
  git clone "$QT5_REPO" "$ROOT/qt5"
fi
cd "$ROOT/qt5"
git fetch --all --tags
git checkout "$QT5_REF"
if [ ! -f "$QT5DIR/bin/qmake" ]; then
  ./init-repository --module-subset=qtbase
  ./configure -opensource -confirm-license -xplatform wasm-emscripten \
    -feature-thread -prefix "$QT5DIR" \
    QMAKE_CFLAGS+=-sSUPPORT_LONGJMP=wasm \
    QMAKE_CXXFLAGS+=-sSUPPORT_LONGJMP=wasm
  make -j"$CORES" module-qtbase
  make -j"$CORES" install
fi

# ---- 3. LibreOffice core (zeta-24-2) with --with-lang=en-US zh-CN ------------
if [ ! -d "$ROOT/core" ]; then
  git clone "$LO_REPO" "$ROOT/core"
fi
cd "$ROOT/core"
git fetch --all
git checkout "$LO_REF"
# Drop in our autogen.input (substituting the real QT5DIR).
sed "s#@QT5DIR@#$QT5DIR#g" "$HERE/autogen.input" > "$ROOT/core/autogen.input"
./autogen.sh                 # patched to use emconfigure (per README.wasm.md)
make -j"$CORES"              # <-- the heavy step; link may need ~64 GB RAM

# ---- 4. collect artifacts ---------------------------------------------------
SRC="$ROOT/core/workdir/installation/LibreOffice/emscripten"
for f in soffice.js soffice.wasm soffice.data soffice.data.js.metadata; do
  if [ -f "$SRC/$f" ]; then cp -v "$SRC/$f" "$OUT/"; else echo "WARN missing $SRC/$f"; fi
done
echo "Done. zh-CN LOWA artifacts in $OUT"
echo "Next: point desktop/scripts/fetch-lowa-assets.js at these (replace the CDN source)."
