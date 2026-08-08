# Title fonts (宋体) — policy

Chart **titles** use a Song (宋体) face for a 公文-title look; body text stays sans.
The title font is resolved by the stack in `assets/style-tokens.json → title_font`,
which degrades through **well-known** faces and **never falls to 仿宋 (FangSong)**:

1. **真身 · commercial, name-referenced only, NEVER bundled**
   方正小标宋 (`FZXiaoBiaoSong-B05S`, `方正小标宋简体`, `方正小标宋_GBK`).
   Proprietary — do **not** commit its `.ttf/.otf` here. To get the real face,
   install 方正小标宋 on the machine that opens the SVG or renders the PNG (many
   lawyers already have it via WPS / 方正字库).

2. **优先回退 · OFL, verifiable, well-known**
   思源宋体 (Source Han Serif) — registered as `思源宋体` / `Source Han Serif SC` /
   `Noto Serif CJK SC` depending on the OS, so all three aliases are listed (same
   font). This is what the render environment here actually uses, so the PNG is a
   clean, known Song. Bold is requested via `font-weight:700` (a real Bold face,
   e.g. Noto Serif CJK Bold); the SVG master adds a hairline 0.3 stroke, which the
   soffice PNG path strips (LibreOffice would otherwise outline the title in the
   wrong font).

3. **兜底 · usually already installed**
   华文中宋 / STZhongsong — ships with MS Office / WPS, so roughly half of Chinese
   legal machines already have it. A dignified last-resort title Song before the
   generic `serif`. The chain **never** reaches 仿宋 (FangSong).

The delivered **PNG** is rasterized with whatever Song is installed on the render
machine (best-available wins). The **SVG** master carries the full stack, so it
shows 方正小标宋 wherever that font is installed. No obscure/unverifiable fonts are
placed in the default chain.

> **把法律画出来 · Make the Law Visible** ｜ 新诉讼可视化 New Litigation Visualization ｜ 缪奇川 出品 ｜ v1.0.2
