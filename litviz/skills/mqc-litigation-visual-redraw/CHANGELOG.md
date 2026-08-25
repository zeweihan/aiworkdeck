# Changelog

## v1.0.2

Editable PowerPoint output, and a way to check rendered results without eyes.

### Added
- **`.vsdx` export** (`scripts/export_vsdx.py`) — the figure, re-openable in
  **ProcessOn**, Visio, WPS and Edraw. Of the ten formats ProcessOn imports, eight
  are outline/mind-map formats that would flatten dates, routes and arrow direction
  into a tree, and its own `.pos` is private and unverifiable; `.vsdx` is a published
  standard and LibreOffice reads it back, so the output is measured rather than hoped over.
- **`scripts/checkpoint.py`** — generates the three checkpoint questions
  (structure · style · emphasis) instead of asking the model to compose them.
  Their consequences were already enforced deterministically, so the questions are too.
- **`checkpoint` block in the map** — `emphasis_source` (`user` / `model` / none)
  and `confirmed`. The deep red now only renders when the map says where that
  choice came from, and an unconfirmed figure is written as `*-draft.*`.
- **lint gained the two failures that stay inside the canvas**: text wider than
  its own module, and text printed over other text.
- **README rebuilt for the release**: positioning rewritten around the editable
  hand-offs, a `30 秒开始` block, `适合 / 不适合`, an FAQ, and the author's long-form
  graphics. The badge row now obeys the discipline it describes — one grey, one red,
  spent on this release's actual claim.
- **`assets/longform/`** — the author's hand-designed long-form graphics, with the
  「输出的格式」 panel rebuilt for five formats and tool logos normalised to a common
  CONTENT size (their source files differ in padding by 36 percentage points).
- **`scripts/make_gallery.py`** — regenerates the README's showcase images from
  `examples/`. They had gone stale unnoticed, still advertising flat arrowheads and
  a blue dot-grid long after both were fixed; a guard now asserts they are current.
- **`scripts/audit_edges.py`** — measures edge INK to catch rasterisation asymmetry.
- **`doctor.py --fix-fonts`** — installs IBM Plex Mono instead of only reporting it missing.
- **`--formats=`** to narrow the delivery set; the default writes all five.
- **`.pptx` export** (`scripts/export_pptx.py`) — every box, colour, word, line and
  arrow is a native PowerPoint object, transcribed from the master SVG so the deck
  IS the delivered figure. Written with the standard library alone (a .pptx is a
  ZIP of XML), keeping the zero-dependency rule. All seven layouts, all three modes.
- **`scripts/verify_pptx.py`** — renders a generated deck and measures where the
  text actually landed, against the master. Catches text that never rendered,
  moved, or changed size. The gap it closes had let a deck ship with 33%-oversized
  type while every other check passed.
- **README rebuilt for the release**: positioning rewritten around the editable
  hand-offs, a `30 秒开始` block, `适合 / 不适合`, an FAQ, and the author's long-form
  graphics. The badge row now obeys the discipline it describes — one grey, one red,
  spent on this release's actual claim.
- **`assets/longform/`** — the author's hand-designed long-form graphics, with the
  「输出的格式」 panel rebuilt for five formats and tool logos normalised to a common
  CONTENT size (their source files differ in padding by 36 percentage points).
- **`scripts/make_gallery.py`** — regenerates the README's showcase images from
  `examples/`. They had gone stale unnoticed, still advertising flat arrowheads and
  a blue dot-grid long after both were fixed; a guard now asserts they are current.
- **`scripts/audit_edges.py`** — measures the INK on each edge of a bar to catch
  rasterisation asymmetries (see Fixed).
- **Font profiles** for the deck: `master` (the SVG's own faces, default) and
  `safe` (`--pptx-fonts=safe`, faces that ship with Windows).

### Changed
- **奇川流 → 奇川风, 歸葬流 → 歸藏风.** 葬 was a mis-spelling of the blogger 歸藏's
  name. Pinyin identifiers (`guizang`, `--guizang`) are unchanged — that is his own
  romanisation. Old names still parse, for maps written against v1.0.1.
- **歸藏风 dot grid is light grey, not Klein blue.** The blue is this style's single
  anchor colour and belongs on solid blocks, not on the backdrop. Latin and numeral
  runs now carry tracking.
- **白描 squares its modules off** to a near right angle. Bars (`rx=0`) and terminal
  pills are exempt. Layout stays byte-identical to 奇川风.
- **The timeline axis bar is a right angle** in every mode; the numbered axis line
  takes square ends. A time ruler is a bar, not a pill.

### Fixed
- **The arrowhead read as a flat stub.** `refX` was pinned at 11 for every line
  weight, putting the line's square cap where the triangle is only 0.4x as wide.
  The junction is now derived from the stroke width, so the head stays sharp and
  seamless at any weight.
- **A bar's lower rule printed heavier than its upper one.** The raster ran at a
  fractional scale (150 dpi = 1.5625x), so integer coordinates fell mid-pixel.
  Rasters are now pinned to an exact integer scale and hairlines to integer widths.
- **A bar's lower rule printed heavier than its upper one** — raster pinned to an
  exact integer scale, hairline widths made integers.
- **歸藏风 lost its background entirely**: two `<defs>` blocks with the paper rect
  between them, and the parser took "everything after the last one".
- **Mode transforms failed silently** when a regex stopped matching. Every step
  that must fire now reports a miss by name, and a guard asserts there are none.
- Tuning constants gathered into `style-tokens.json` → `tuning`, named and
  explained (values unchanged; the masters are byte-identical).
- Stale docs: seven layouts (not six), the real guard count, the version footer,
  `ranksep` (0.70 → 1.1), and a literal `$\n` in six reference files.

## v1.0.1 — 三种视觉模式（2026-07）

### 新增
- **三档视觉模式**，共用同一套几何（确定性布局 + 正交走线），只换表达：
  - **奇川风**（默认）— 宋体 + 灰阶 + 唯一深红，办案 / 个人品牌；
  - **白描** `--baimiao` — 纯黑白线稿、纯色块变框线模块，法院 / 打印 / 卷宗；
  - **歸藏风** `--guizang` — 克莱因蓝 `#002FA7` + 浅灰 + 白，无衬线中文 + IBM Plex Mono
    数字英文、直角发丝边、纯色蓝块白字、点阵底、大居中轻标题，线上传播 / 讲课。
- 也可在 semantic-map 里写 `"visual_mode": "白描" | "歸藏风"`。
- **`scripts/doctor.py`** 环境自检：裸仓库 clone 后第一步，逐项检查 graphviz、
  光栅化器、中文衬线 / 无衬线 / IBM Plex Mono，缺失时说明会退化成什么；
  必需项缺失退出码为 1，可用于 CI 门禁。
- `gallery/`：7 种图表 × 3 档成套图 + 每种类型的三档并列对照图。

### 工作流
- CHECKPOINT 改为**一轮三问**：结构确认 / 红色重点 / 视觉模式（按使用场景）。
- **红色改为 opt-in**：深红只标用户指定的元素；**用户跳过或不回应 → 默认奇川风且完全不用红**，
  模型不得自行挑选强调。交付摘要须写明所用模式与强调元素（或 none）。

### 修复
- 关系图布局与走线重写：枢纽居中辐射、每边 ≤2 条连线、连线不穿节点、
  箭头精确落在边框、标签不压线不互相遮挡（`references/relationship-spec.md` 冻结）。
- 修正标签放置的两个 bug：命中空位后未置位导致被覆盖；圆角把竖线拆成两段
  导致侧标签定位基准错误。
- 白描：无边框填充块（甘特期间条、时间轴色带）补发丝黑边，否则渲染后不可见；
  编号圆改为圆框 + 黑数字。

### 测试
- 回归自测 **88 项**全部通过，含「白描」「歸藏风」两条模式守卫与 doctor 守卫。

### 长文本 / 溢出
- **超长标题自动折行**（`fit_title`）：按画布宽度均分成多行、内容下移、画布长高；
  只插入换行，不改一个字，也不缩字号。标题本来放得下的图逐字节不变。
- **注释按真实行数预留高度**：关系图不再假设注释只有两行。
- **`lint.py` 改为测量文字实际宽度**（中文≈1em、拉丁≈0.55em），
  居中标题"锚点在画布内、字却甩出边界"的盲区被补上。

### 可编辑 drawio
- **`.drawio` / `.drawio.svg` 跟随视觉模式**：白描导出白底黑线黑字；歸藏风导出蓝/灰/白，
  并把**枢纽节点提升为纯蓝块白字**（关系图没有深色块可映射，由关键当事人承载强调，
  与 SVG 一致）；奇川风逐字节不变。只改颜色，id/几何/结构不动，仍可在 draw.io 正常编辑。

### 文档 / 展示
- README：新增「同一张图 · 三种模式」竖排三档对照（原横排 8.3:1 扁条在 GitHub 上
  高度仅 92px，细节不可读，已修正为 0.45–0.9 比例）。
- Badges 增至 10 个（版本、Claude Skill、测试数、视觉模式、图表类型、输出格式、脱敏）。
- 回归自测 **90 项**全部通过。
