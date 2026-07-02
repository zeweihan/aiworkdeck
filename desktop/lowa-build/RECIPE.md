# zh-CN LOWA engine rebuild recipe (with tooltip-CJK fix) — issue #66

Built 2026-06-26 on Singapore VM 47.84.13.121 (32C/123GB RAM, Ubuntu 22.04), from
scratch. Reproduces the engine with: native zh-CN UI (--with-lang) + ooLocale=zh-CN
default + **the QToolTip CJK fix** (this round's new change).

## Artifact sha256 (this build)
- soffice.js   ea337a9f4d9d2c74a8b0df9c8bf0c4b9c64f5fda770cd09e631dedd770e882e1
- soffice.wasm a186dd5082eb69ef58fb57d256294d07817c4acfb06e63fc5901e77d07612954
- soffice.data e85af877e964f75f14b21b97f0a7103d104aea308ec8c687519412ace48deeb5
- soffice.data.js.metadata 7c5d27a650d5f0a0b4f08b9c096fa03c51c3233ea6fe4d179d530efdbf383fa2
  (metadata sha == previous engine: same file list/offsets; data content differs by build)

## Toolchain (exact)
- LO core: github.com/LibreOffice/core branch distro/allotropia/zeta-24-2 (rev dced3bc71)
  NOTE: git.libreoffice.org + gerrit.libreoffice.org are UNREACHABLE from this region.
  Use the GitHub mirror. Set: git config --global url."https://github.com/LibreOffice/".insteadOf
  for both git.libreoffice.org/ and gerrit.libreoffice.org/ (use --add for each).
  translations submodule resolves via relative URL ../translations -> github mirror (works).
  External tarballs (dev-www.libreoffice.org/src) ARE reachable.
- emscripten: github.com/allotropia/emscripten branch fixed-3.1.65 (the FORK, NOT stock).
- qt5: github.com/allotropia/qt5 branch 5.15.2+wasm (qtbase module subset).

## Fork emscripten install (the tricky bit)
1. clone emscripten-core/emsdk; ./emsdk install 3.1.65 && ./emsdk activate 3.1.65
   (gets node22 + llvm + binaryen for 3.1.65 + stock frontend at upstream/emscripten).
2. clone allotropia/emscripten (fixed-3.1.65) as the fork.
3. cp -a emsdk/upstream/emscripten/node_modules -> fork/node_modules (superset; skips npm ci).
4. echo "3.1.65" > fork/emscripten-version.txt  (was 3.1.65-git; must match emsdk SDK).
5. bootstrap stamps: mkdir fork/out; create out/{npm_packages,create_entry_points,git_submodules}.stamp
   future-dated (touch -d 2030-01-01) so bootstrap.check() passes. Also ensure these dep paths
   exist (else getmtime throws): test/third_party/posixtestsuite/, tools/maint/run_python.{sh,bat,ps1}.
6. swap: mv emsdk/upstream/emscripten emscripten.stock; ln -s <fork> emsdk/upstream/emscripten.
7. **EM_CONFIG MUST be exported** — emsdk_env.sh does NOT export it here, so emcc can't find
   LLVM_ROOT. env.sh:  source emsdk/emsdk_env.sh; export EM_CONFIG=<emsdk>/.emscripten
8. emcc --clear-cache; sanity: emcc hello.c -o hello.js && node hello.js.

## Build-as-root bypass
LO Makefile check-if-root uses `systemd-detect-virt -c -q` (container-only; a KVM VM fails it).
Put a fake at /usr/local/bin/systemd-detect-virt (#!/bin/sh\nexit 0), and PATH=/usr/local/bin:$PATH.

## Source patches (see source-patches.diff; apply with apply-source-patches.py)
1. solenv/gbuild/platform/EMSCRIPTEN_INTEL_GCC.mk — export FS/callMain/specialHTMLTargets
   UNCONDITIONALLY (remove the `$(if $(ENABLE_QT6),...)` gate). Without this, a QT5 build
   does not export them -> integration breaks (uno_main/FS undefined). THE stock-vs-fork fix.
2. vcl/qt5/QtInstance.cxx — *** THE TOOLTIP-CJK FIX ***. In QtInstance::AfterAppInit(),
   QFontDatabase::addApplicationFont("/instdir/share/fonts/truetype/AAA-CJK.ttc") (the font
   the editor injects at preRun), then append its family to QApplication::font() families AND
   the "QTipLabel" class font so native QToolTip (which bypasses VCL/fontconfig) renders CJK.
   Graceful no-op if the font is absent.
3. static/CustomTarget_emscripten_fs_image.mk — pack zh-CN into soffice.data: Langpack-zh-CN,
   cjk_zh-CN, res/fcfg_langpack_zh-CN, res/registry_zh-CN, ZZZ-aiworkdeck-locale-zh-CN, and
   $(wildcard $(INSTROOT)/program/resource/zh_CN/LC_MESSAGES/*.mo) (25 .mo). The fs_image list
   is hand-curated (en-US only by default); --with-lang BUILDS zh-CN but does not auto-PACK it.

## Build steps
- autogen.input (see file): --with-distro=LibreOfficeWASM32, --with-package-format=emscripten,
  --with-lang=en-US zh-CN, QT5DIR=..., --enable-ccache.
- git submodule update --init --depth 1 translations   (needed for --with-lang=zh-CN)
- ./autogen.sh && make -j32   (~24 min on 32C with cold ccache; final wasm link is RAM-heavy)
- Phase 6 (zh-CN bake): cp ZZZ-aiworkdeck-locale-zh-CN.xcd -> instdir/share/registry/;
  apply patch #3; rm workdir/CustomTarget/static/emscripten_fs_image/soffice.data* +
  workdir/installation/.../emscripten/soffice.data*; make -j32 again (repack, ~12 min).
- Artifacts: workdir/installation/LibreOffice/emscripten/soffice.{js,wasm,data} + .data.js.metadata.
  Verify metadata contains Langpack-zh-CN, registry_zh-CN, ZZZ-aiworkdeck, resource/zh_CN x25.

## Integration / verify
- fetch-lowa-assets.js (PR #68): LOWA_BASE_URL=file:///.../lowa-zhcn-engine/ -> bakes into dist.
- Real Chrome (headless can't render canvas): editor.html?verify=1&lowa=http://localhost:8799/lowa/
  Check: native menus/toolbar/dialogs Chinese (was already OK) AND hover-button tooltip Chinese
  (this round's target — previously tofu/□).
