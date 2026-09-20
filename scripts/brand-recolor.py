#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
"""把三个仓里烧死旧品牌色的位图资产换成东方清雅配色（dev-board#731）。

背景：三仓都没有 logo 的矢量源（SVG/AI/Figma），logo 与图标全是位图 PNG/ICO，
      所以只能对栅格做重上色。维护者拍板「保形换调」：形状一笔不改，只换两个色。
          深松绿族（#06462C / #1A5336 / #014026 …） -> 墨竹青 #2E5A50
          薄荷绿族（#68D7AB / #5BD197 …）           -> 竹月青 #89A8A0
          白、透明度、尺寸、文件名一律不动；浅茶金不进 logo。

做法：不做「阈值内的颜色替换」——那会在抗锯齿边缘留下锯齿色带。
      这些图都是三种纯色（深色 / 浅色 / 白）的线性混合：边缘像素是覆盖率混色，
      渐变字标是同族深浅混色。所以取三组「旧色 -> 新色」对应点，拟合一个
      **全局仿射映射** out = M·in + t：
        - M 在三个锚点上精确命中新色；
        - 锚点张成的平面内按重心坐标线性插值，等价于「覆盖率不变、端点换色」；
        - 平面外的残差（压缩噪点）沿平面法向原样保留，不放大。
      仿射映射是连续的，逐像素同一把尺子，数学上不可能引入新的色带或锯齿；
      M 的最大奇异值实测 1.02（脚本每次都打印），噪点不会被放大。

锚点自适应：同一族在不同导出件里差几个色阶（#06462C / #08462C / #014026），
      脚本会在每张图里找「离族内旧色最近的高频实际色」当锚点（半径 ANCHOR_RADIUS），
      这样大面积平涂区换完正好落在 #2E5A50 / #89A8A0 上，而不是差两三个色阶。

幂等：已经是新色的图（主色落在新锚点附近）直接跳过，重复跑不会二次位移。

依赖：Pillow + numpy。本机用 /opt/homebrew/bin/python3.13（系统 python3.14 没装 numpy）。
用法：
      python3 scripts/brand-recolor.py --list            只列清单不动文件
      python3 scripts/brand-recolor.py --dry-run         采样并打印前后色值，不写盘
      python3 scripts/brand-recolor.py                   写盘
      --web / --mobile 指定另外两个仓的根目录（默认按 5-Tech 同级目录猜）
      --backup <dir>   写盘前把原图按仓/路径备份过去（出对比图用）
"""
from __future__ import annotations

import argparse
import collections
import pathlib
import shutil
import sys

import numpy as np
from PIL import Image

# ---------------------------------------------------------------- 色表

TARGET_DEEP = "#2E5A50"   # 墨竹青 roles.light.accent
TARGET_LIGHT = "#89A8A0"  # 竹月青 base.bamboo
WHITE = "#FFFFFF"         # 白留白，图标底与字标反白都不动

# 族 -> (旧深色, 旧浅色)。两族的深色差一大截（#06462C vs #1A5336），必须分开拟合。
FAMILIES = {
    # 设计软件导出的品牌位图（字标 / 图标 / App 图标 / favicon）
    "raster": ("#06462C", "#68D7AB"),
    # 工作台 UI 里按 CSS 令牌色描的单色图标
    "css": ("#1A5336", "#5BD197"),
}

ANCHOR_RADIUS = 22.0   # 在这个 RGB 欧氏半径内找本图实际锚点色
MIN_ANCHOR_COUNT = 8   # 低于这个出现次数的「实际色」是压缩噪点，不配当锚点，退回族内旧色
DONE_RADIUS = 14.0     # 主色已落在新锚点这个半径内 = 已经换过，跳过

# ---------------------------------------------------------------- 资产清单
# (相对路径, 族)。逐条列全，不用通配符——这份清单本身就是盘点结果，要能审。

UI_ICONS = [  # frontend/src/static 下 26 张烧死 #1A5336 的单色图标
    "MPIS-TTS_selected.png", "batch_select.png", "bottom-bar_selected.png",
    "checklist_selected.png", "close_hover.png", "copy_selected.png",
    "delete_selected.png", "documents_selected.png", "download_selected.png",
    "folder-opened.png", "history_hover.png", "icon_new_folder.png",
    "left-bar_selected.png", "meeting_selected.png", "new-document.png",
    "new-web_selected.png", "plus_hover.png", "record-rec_selected.png",
    "recycle-bin.png", "rename_selected.png", "restore.png",
    "right-bar_selected.png", "screenshop_selected.png", "sort.png",
    "square_selected.png", "temporary_selected.png",
]

ASSETS: dict[str, list[tuple[str, str]]] = {
    "cloud": [
        ("frontend/src/static/logo_full_v2.png", "raster"),
        ("frontend/src/static/new_full_logo.png", "raster"),
        ("frontend/src/static/iconmark_v2.png", "raster"),
        ("frontend/src/static/icon.png", "raster"),
        ("desktop/build/icon.png", "raster"),
        (".github/assets/icon.png", "raster"),
        ("office-addin/assets/icon-16.png", "raster"),
        ("office-addin/assets/icon-32.png", "raster"),
        ("office-addin/assets/icon-64.png", "raster"),
        ("office-addin/assets/icon-80.png", "raster"),
        ("office-addin/appsource/assets/store-logo-300.png", "raster"),
        ("docs/marketing/community-launch/ph-assets/thumbnail-240.png", "raster"),
        ("office-addin/installer/win/installer.ico", "raster"),
    ] + [(f"frontend/src/static/{n}", "css") for n in UI_ICONS],
    "web": [
        ("app/icon.png", "raster"),
        ("app/favicon.ico", "raster"),
        ("public/logo.png", "raster"),
        ("public/new_full_logo.png", "raster"),
        ("public/og.png", "raster"),
    ],
    "mobile": [
        ("ios/Resources/Assets.xcassets/AppIcon.appiconset/Icon-1024.png", "raster"),
        ("android/app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png", "raster"),
        ("android/app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png", "raster"),
        ("android/app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png", "raster"),
        ("android/app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png", "raster"),
        ("android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png", "raster"),
        ("android/store/icon-512.png", "raster"),
        ("android/store/wechat-open/icon-108.png", "raster"),
        ("android/store/wechat-open/icon-28.png", "raster"),
        ("android/store/feature-graphic-1024x500-zh.png", "raster"),
        ("android/store/feature-graphic-1024x500-en.png", "raster"),
        ("harmony/AppScope/resources/base/media/foreground.png", "raster"),
        ("harmony/AppScope/resources/base/media/app_icon.png", "raster"),
        ("harmony/entry/src/main/resources/base/media/foreground.png", "raster"),
        ("harmony/entry/src/main/resources/base/media/startIcon.png", "raster"),
    ],
}

# 明确不动的（写在这里是为了下次盘点时不用重新判一遍）：
#   frontend/static/logo.png            金色抽象图形，全仓无引用，疑似废弃
#   legal/AI Workdeck Trademark.png     商标注册申请图样，注册主体的法律文件
#   pptx-service/**                     第三方受限许可（CC BY-NC-SA），另算
#   */screenshots/**, doc/verification/ 产品界面截图，要重跑截图流水线而不是重上色
#   aiworkdeck_mobile/docs/design/**    历史评审存档图
#   *_monochrome.png / monochrome.png   纯黑白，没有品牌色
#   desktop/build/、office-addin/installer/ 下的安装器美术（DMG 背景、NSIS 位图）
#     另有 HTML 源码可重渲染，由负责安装器的那一路改，本脚本不碰；
#     installer.ico 例外——它没有 HTML 源码，是纯位图资产，已列入 ASSETS 由本脚本处理

# ---------------------------------------------------------------- 工具


def hex2rgb(s: str) -> np.ndarray:
    s = s.lstrip("#")
    return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def rgb2hex(c) -> str:
    c = np.clip(np.round(np.asarray(c, dtype=float)), 0, 255).astype(int)
    return "#%02X%02X%02X" % (c[0], c[1], c[2])


def affine(old: list[np.ndarray], new: list[np.ndarray]):
    """三组对应点 -> 仿射映射 (M, t)，平面法向保持恒等。"""
    o = np.stack(old)
    n = np.stack(new)
    u = np.stack([o[1] - o[0], o[2] - o[0]], axis=1)
    nrm = np.cross(u[:, 0], u[:, 1])
    nrm = nrm / np.linalg.norm(nrm)
    basis_old = np.column_stack([u, nrm])
    v = np.stack([n[1] - n[0], n[2] - n[0]], axis=1)
    basis_new = np.column_stack([v, nrm])
    m = basis_new @ np.linalg.inv(basis_old)
    t = n[0] - m @ o[0]
    return m, t


def count_near(pixels: np.ndarray, ref: np.ndarray, radius: float) -> int:
    """落在 ref 半径内的像素个数。"""
    return int((np.linalg.norm(pixels - ref, axis=1) < radius).sum())


def dominant_near(pixels: np.ndarray, ref: np.ndarray, radius: float):
    """在 pixels 里找离 ref 最近邻域内出现次数最多的颜色。"""
    d = np.linalg.norm(pixels - ref, axis=1)
    sel = pixels[d < radius]
    if len(sel) == 0:
        return None, 0
    cnt = collections.Counter(map(tuple, sel.astype(int)))
    col, n = cnt.most_common(1)[0]
    return np.array(col, dtype=float), n


def opaque_rgb(img: Image.Image) -> np.ndarray:
    a = np.asarray(img.convert("RGBA"))
    flat = a.reshape(-1, 4)
    return flat[flat[:, 3] >= 250][:, :3].astype(float)


def sample_report(img: Image.Image, refs: list[np.ndarray]) -> list[str]:
    px = opaque_rgb(img)
    out = []
    for r in refs:
        col, n = dominant_near(px, r, ANCHOR_RADIUS)
        out.append(f"{rgb2hex(col)}x{n}" if col is not None else "-")
    return out


def recolor_array(arr: np.ndarray, m: np.ndarray, t: np.ndarray) -> np.ndarray:
    """arr: HxWx3 或 HxWx4 uint8。只动 RGB，alpha 原样。"""
    rgb = arr[..., :3].astype(float)
    shape = rgb.shape
    out = rgb.reshape(-1, 3) @ m.T + t
    out = np.clip(out, 0, 255).round().astype(np.uint8).reshape(shape)
    if arr.shape[-1] == 4:
        return np.concatenate([out, arr[..., 3:4]], axis=-1)
    return out


def recolor_image(img: Image.Image, m: np.ndarray, t: np.ndarray) -> Image.Image:
    mode = img.mode
    if mode in ("L", "LA", "1", "I", "F"):
        raise ValueError(f"灰度图不含品牌色，不处理（mode={mode}）")
    if mode == "P":
        rgba = img.convert("RGBA")
        out = Image.fromarray(recolor_array(np.asarray(rgba), m, t), "RGBA")
        return out.quantize(palette=img)
    src = "RGBA" if ("A" in mode or mode == "RGBA") else "RGB"
    arr = np.asarray(img.convert(src))
    return Image.fromarray(recolor_array(arr, m, t), src)


# ---------------------------------------------------------------- 单文件处理


def process(path: pathlib.Path, family: str, dry_run: bool, backup: pathlib.Path | None,
            rel: str, repo: str) -> dict:
    old_deep_default, old_light_default = (hex2rgb(x) for x in FAMILIES[family])
    tgt_deep, tgt_light, white = hex2rgb(TARGET_DEEP), hex2rgb(TARGET_LIGHT), hex2rgb(WHITE)

    img = Image.open(path)
    is_ico = path.suffix.lower() == ".ico"
    sizes = sorted(img.ico.sizes()) if is_ico else None
    probe = img.ico.getimage(sizes[-1]) if is_ico else img

    px = opaque_rgb(probe)
    if len(px) == 0:
        return {"rel": rel, "status": "skip", "why": "没有不透明像素"}

    # 幂等保护：新色出现、旧色一个像素都不剩 = 已经换过，重复跑不再二次位移。
    # 这里用的是「半径内像素总数」而不是众数计数，16x16 这种只有几个不透明像素的图也判得准。
    old_hits = count_near(px, old_deep_default, ANCHOR_RADIUS) + \
        count_near(px, old_light_default, ANCHOR_RADIUS)
    new_hits = count_near(px, tgt_deep, DONE_RADIUS) + count_near(px, tgt_light, DONE_RADIUS)
    if old_hits == 0 and new_hits > 0:
        return {"rel": rel, "repo": repo, "status": "skip", "why": "已是新配色"}

    # 本图里的实际锚点色（出现次数太少的是压缩噪点，不配当锚点，退回族内旧色）
    near_deep, cnt_deep = dominant_near(px, old_deep_default, ANCHOR_RADIUS)
    near_light, cnt_light = dominant_near(px, old_light_default, ANCHOR_RADIUS)
    if cnt_deep < MIN_ANCHOR_COUNT:
        near_deep, cnt_deep = None, 0
    if cnt_light < MIN_ANCHOR_COUNT:
        near_light, cnt_light = None, 0

    anchor_deep = near_deep if near_deep is not None else old_deep_default
    anchor_light = near_light if near_light is not None else old_light_default

    m, t = affine([anchor_deep, anchor_light, white], [tgt_deep, tgt_light, white])
    sv = np.linalg.svd(m, compute_uv=False)

    before = sample_report(probe, [anchor_deep, anchor_light, white])
    info = {
        "rel": rel, "repo": repo, "family": family,
        "size": f"{probe.width}x{probe.height}", "mode": img.mode,
        "anchors": f"{rgb2hex(anchor_deep)}({cnt_deep}) / {rgb2hex(anchor_light)}({cnt_light})",
        "svmax": float(sv.max()), "before": before,
    }

    if is_ico:
        frames = []
        for s in sizes:
            frames.append(recolor_image(img.ico.getimage(s), m, t).convert("RGBA"))
        after_probe = frames[-1]
    else:
        out = recolor_image(img, m, t)
        after_probe = out

    info["after"] = sample_report(after_probe, [tgt_deep, tgt_light, white])
    info["status"] = "dry" if dry_run else "written"

    if dry_run:
        return info

    if backup is not None:
        dst = backup / repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dst)

    if is_ico:
        frames[-1].save(path, format="ICO", sizes=[(s[0], s[1]) for s in sizes],
                        append_images=frames[:-1])
        info["frames"] = ",".join(f"{s[0]}" for s in sizes)
    else:
        params = {}
        if img.format == "PNG":
            params["optimize"] = True
        out.save(path, format=img.format, **params)
    return info


# ---------------------------------------------------------------- 入口


def main() -> int:
    here = pathlib.Path(__file__).resolve()
    cloud_default = here.parent.parent
    tech = cloud_default.parent
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cloud", type=pathlib.Path, default=cloud_default)
    ap.add_argument("--web", type=pathlib.Path, default=tech / "1-1 aiworkdeckweb")
    ap.add_argument("--mobile", type=pathlib.Path, default=tech / "1-3 aiworkdeck_mobile")
    ap.add_argument("--only", choices=["cloud", "web", "mobile"], action="append")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--backup", type=pathlib.Path)
    opts = ap.parse_args()

    roots = {"cloud": opts.cloud, "web": opts.web, "mobile": opts.mobile}
    wanted = opts.only or ["cloud", "web", "mobile"]

    if opts.list:
        for repo in wanted:
            for rel, fam in ASSETS[repo]:
                print(f"{repo:6} {fam:9} {rel}")
        return 0

    rows, missing, failed = [], [], []
    for repo in wanted:
        root = roots[repo].resolve()
        if not root.exists():
            missing.append(f"{repo}: 仓根不存在 {root}")
            continue
        for rel, fam in ASSETS[repo]:
            p = root / rel
            if not p.exists():
                missing.append(f"{repo}: {rel}")
                continue
            try:
                rows.append(process(p, fam, opts.dry_run, opts.backup, rel, repo))
            except Exception as exc:  # noqa: BLE001 - 单张失败不该中断整批
                failed.append(f"{repo}: {rel}: {exc}")

    w = max((len(r["rel"]) for r in rows), default=10)
    print(f"{'状态':<7}{'仓':<7}{'资产':<{w + 2}}{'锚点(旧深/旧浅)':<28}"
          f"{'处理前 深/浅/白':<34}{'处理后 深/浅/白'}")
    for r in rows:
        if r["status"] == "skip":
            print(f"{'跳过':<6}{r.get('repo', ''):<7}{r['rel']:<{w + 2}}{r['why']}")
            continue
        b = "/".join(x.split("x")[0] for x in r["before"])
        a = "/".join(x.split("x")[0] for x in r["after"])
        print(f"{r['status']:<7}{r['repo']:<7}{r['rel']:<{w + 2}}{r['anchors']:<28}{b:<34}{a}")

    print(f"\n处理 {sum(1 for r in rows if r['status'] in ('written', 'dry'))} 张，"
          f"跳过 {sum(1 for r in rows if r['status'] == 'skip')} 张，"
          f"缺失 {len(missing)}，失败 {len(failed)}")
    svs = [r["svmax"] for r in rows if "svmax" in r]
    if svs:
        print(f"所有仿射映射的最大奇异值 {max(svs):.3f}（≈1 表示噪点不会被放大）")
    for x in missing:
        print("  缺失 " + x)
    for x in failed:
        print("  失败 " + x)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
