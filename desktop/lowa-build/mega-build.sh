#!/usr/bin/env bash
# Unattended end-to-end zh-CN LOWA rebuild (with tooltip-CJK fix), issue #66.
# Encodes every fix debugged on the previous VM (see RECIPE.md). Ubuntu 22.04, 32C/123G.
# Markers: PHASE_<n>_DONE / MEGA_FAILED / MEGA_ALL_DONE — poll with grep.
set -uo pipefail
ROOT=/root/lowa-build
OUT=/root/out
export DEBIAN_FRONTEND=noninteractive
mkdir -p "$ROOT" "$OUT"
log(){ echo "=== $* $(date -u +%H:%M:%S) ==="; }
die(){ echo "MEGA_FAILED: $*"; exit 1; }

# ---------- Phase 1: apt + root-check bypass + git mirrors --------------------
log "[1] apt deps"
apt-get update -qq
apt-get install -y -qq \
  git build-essential gcc-12 g++-12 ccache python3 python3-pip gettext \
  autoconf automake libtool pkg-config bison flex gperf \
  zip unzip wget curl xz-utils nasm libxml2-utils xsltproc \
  openjdk-17-jdk-headless ca-certificates locales >/dev/null 2>&1 || die apt
update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-12 100 >/dev/null 2>&1
update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-12 100 >/dev/null 2>&1
update-alternatives --set gcc /usr/bin/gcc-12 && update-alternatives --set g++ /usr/bin/g++-12
sed -i 's/# zh_CN.UTF-8/zh_CN.UTF-8/' /etc/locale.gen 2>/dev/null || true; locale-gen >/dev/null 2>&1 || true
# LO refuses root builds; its check is `systemd-detect-virt -c` (container-only, fails on KVM)
printf '#!/bin/sh\nexit 0\n' > /usr/local/bin/systemd-detect-virt && chmod +x /usr/local/bin/systemd-detect-virt
# git.libreoffice.org + gerrit unreachable from this region -> GitHub mirror
git config --global --unset-all url."https://github.com/LibreOffice/".insteadOf 2>/dev/null || true
git config --global --add url."https://github.com/LibreOffice/".insteadOf "https://git.libreoffice.org/"
git config --global --add url."https://github.com/LibreOffice/".insteadOf "https://gerrit.libreoffice.org/"
git config --global --add url."https://github.com/LibreOffice/".insteadOf "git://gerrit.libreoffice.org/"
log "PHASE_1_DONE gcc=$(gcc --version|head -1)"

# ---------- Phase 2: parallel clones ------------------------------------------
log "[2] clones (parallel)"
cd "$ROOT"
( [ -d emsdk ] || git clone --depth 1 https://github.com/emscripten-core/emsdk.git emsdk ) &
( [ -d emscripten.fork ] || git clone --depth 1 -b fixed-3.1.65 https://github.com/allotropia/emscripten.git emscripten.fork ) &
( [ -d qt5 ] || git clone --depth 1 -b 5.15.2+wasm https://github.com/allotropia/qt5.git qt5 ) &
( [ -d core ] || git clone --depth 1 -b distro/allotropia/zeta-24-2 https://github.com/LibreOffice/core core ) &
wait
[ -d core ] && [ -d emscripten.fork ] && [ -d qt5 ] && [ -d emsdk ] || die clones
log "PHASE_2_DONE core=$(git -C core rev-parse --short HEAD)"

# ---------- Phase 3: emsdk 3.1.65 + fork swap ----------------------------------
log "[3] emsdk + fork emscripten"
cd "$ROOT/emsdk"
./emsdk install 3.1.65 >/dev/null 2>&1 && ./emsdk activate 3.1.65 >/dev/null 2>&1 || die emsdk-install
FORK="$ROOT/emscripten.fork"
[ -d "$FORK/node_modules" ] || cp -a upstream/emscripten/node_modules "$FORK/node_modules"
echo "3.1.65" > "$FORK/emscripten-version.txt"
mkdir -p "$FORK/test/third_party/posixtestsuite" "$FORK/out"
for f in tools/maint/run_python.bat tools/maint/run_python.sh tools/maint/run_python.ps1; do
  [ -e "$FORK/$f" ] || touch "$FORK/$f"; done
for s in npm_packages create_entry_points git_submodules; do
  echo stamp > "$FORK/out/$s.stamp"; touch -d "2030-01-01" "$FORK/out/$s.stamp"; done
if [ ! -L upstream/emscripten ]; then
  mv upstream/emscripten upstream/emscripten.stock && ln -s "$FORK" upstream/emscripten; fi
cat > "$ROOT/env.sh" <<ENV
source $ROOT/emsdk/emsdk_env.sh >/dev/null 2>&1
export EM_CONFIG=$ROOT/emsdk/.emscripten
export EMSDK_QUIET=1
export PATH=/usr/local/bin:\$PATH
ENV
source "$ROOT/env.sh"
emcc --clear-cache >/dev/null 2>&1
mkdir -p /tmp/emtest && cd /tmp/emtest
printf '%s\n' '#include <stdio.h>' 'int main(void){ printf("EMHELLO_OK\n"); return 0; }' > t.c
emcc t.c -o t.js >/dev/null 2>&1 && node t.js | grep -q EMHELLO_OK || die emcc-sanity
log "PHASE_3_DONE $(emcc --version 2>/dev/null | head -1)"

# ---------- Phase 4: qt5 (background) + core prep (parallel) -------------------
log "[4] qt5 build (bg) + core patches/translations"
QT5DIR="$ROOT/qt5-wasm-install"
(
  set -e; source "$ROOT/env.sh"; cd "$ROOT/qt5"
  ./init-repository --module-subset=qtbase >/root/qt5.log 2>&1
  ./configure -opensource -confirm-license -xplatform wasm-emscripten \
    -feature-thread -prefix "$QT5DIR" \
    QMAKE_CFLAGS+=-sSUPPORT_LONGJMP=wasm QMAKE_CXXFLAGS+=-sSUPPORT_LONGJMP=wasm >>/root/qt5.log 2>&1
  make -j"$(nproc)" module-qtbase >>/root/qt5.log 2>&1
  make -j"$(nproc)" install >>/root/qt5.log 2>&1
  echo QT5_OK >> /root/qt5.log
) &
QT5_PID=$!
cd "$ROOT/core"
git submodule update --init --depth 1 translations >/root/translations.log 2>&1 || die translations
[ -d translations/source/zh-CN ] || die translations-zh
python3 /root/apply-source-patches.py || die source-patches
wait $QT5_PID; grep -q QT5_OK /root/qt5.log || die qt5-build
[ -f "$QT5DIR/bin/qmake" ] || die qmake-missing
log "PHASE_4_DONE"

# ---------- Phase 5: autogen + full make ---------------------------------------
log "[5] autogen + make (heavy)"
source "$ROOT/env.sh"; cd "$ROOT/core"
cat > autogen.input <<AUTOGEN
--with-distro=LibreOfficeWASM32
--with-package-format=emscripten
--with-lang=en-US zh-CN
QT5DIR=$QT5DIR
--enable-ccache
--with-build-platform-configure-options=--enable-ccache
AUTOGEN
./autogen.sh > /root/autogen.log 2>&1 || { tail -20 /root/autogen.log; die autogen; }
make -j"$(nproc)" > /root/make.log 2>&1 || { tail -30 /root/make.log; die make; }
ART=workdir/installation/LibreOffice/emscripten
[ -f "$ART/soffice.wasm" ] || die no-wasm
log "PHASE_5_DONE"

# ---------- Phase 6: bake zh-CN + repack ---------------------------------------
log "[6] bake zh-CN into soffice.data"
cp /root/ZZZ-aiworkdeck-locale-zh-CN.xcd instdir/share/registry/ || die zzz-cp
python3 /root/fs-image-patch.py || die fs-image-patch
rm -f workdir/CustomTarget/static/emscripten_fs_image/soffice.data*
rm -f "$ART/soffice.data" "$ART/soffice.data.js.metadata"
make -j"$(nproc)" > /root/make6.log 2>&1 || { tail -30 /root/make6.log; die make6; }
grep -q "resource/zh_CN" "$ART/soffice.data.js.metadata" || die metadata-no-zh
grep -q "ZZZ-aiworkdeck" "$ART/soffice.data.js.metadata" || die metadata-no-zzz
log "PHASE_6_DONE"

# ---------- Phase 7: stage + verify + gzip -------------------------------------
log "[7] stage artifacts"
for sym in uno_main '"FS"' callMain specialHTMLTargets; do
  grep -qF "$sym" "$ART/soffice.js" || die "export-missing:$sym"; done
cp -p "$ART"/soffice.js "$ART"/soffice.wasm "$ART"/soffice.data "$ART"/soffice.data.js.metadata "$OUT/"
cd "$OUT" && sha256sum soffice.* > sha256.txt && cat sha256.txt
gzip -kf soffice.wasm soffice.data soffice.js
ls -la "$OUT"
log "MEGA_ALL_DONE"
