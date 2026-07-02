#!/usr/bin/env python3
# Patch static/CustomTarget_emscripten_fs_image.mk to pack zh-CN into soffice.data.
# Run AFTER the first full make (instdir must exist for the $(wildcard) to hit).
import sys
mk = "/root/lowa-build/core/static/CustomTarget_emscripten_fs_image.mk"
s = open(mk).read()
if "ZZZ-aiworkdeck-locale-zh-CN" in s:
    print("fs_image .mk already patched")
    sys.exit(0)
anchor = "endif # !ENABLE_WASM_STRIP_CHART\n"
assert s.count(anchor) == 1, f"anchor count = {s.count(anchor)}"
block = anchor + """
# AI Workdeck (#66): pack the zh-CN UI catalogs (.mo) + registry + the default
# UI-locale override so the self-built engine ships Simplified Chinese natively
# (resources produced by --with-lang=zh-CN). Mirrors the en-US registry entries.
gb_emscripten_fs_image_files += \\
    $(INSTROOT)/$(LIBO_SHARE_FOLDER)/registry/Langpack-zh-CN.xcd \\
    $(INSTROOT)/$(LIBO_SHARE_FOLDER)/registry/cjk_zh-CN.xcd \\
    $(INSTROOT)/$(LIBO_SHARE_FOLDER)/registry/res/fcfg_langpack_zh-CN.xcd \\
    $(INSTROOT)/$(LIBO_SHARE_FOLDER)/registry/res/registry_zh-CN.xcd \\
    $(INSTROOT)/$(LIBO_SHARE_FOLDER)/registry/ZZZ-aiworkdeck-locale-zh-CN.xcd \\
    $(wildcard $(INSTROOT)/program/resource/zh_CN/LC_MESSAGES/*.mo)
"""
s = s.replace(anchor, block, 1)
open(mk, "w").write(s)
print("fs_image .mk patched")
