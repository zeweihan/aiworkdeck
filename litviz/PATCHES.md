# 对 vendor 引擎打的本地补丁

`litviz/skills/mqc-litigation-visual-redraw/` 与 `litviz/skills/mqc-timeline-master/`
原则上都是上游原样拷贝（见 `UPSTREAM.md`）。下面每一条都是**不得不打**
的补丁，代码里对应位置都有 `[AWD-PATCH n]` 标记，搜这个标记能找全。

升级引擎时逐条复核：上游若已自行修掉，就删掉这一条，别叠着打。

2026-08-25 升级到 monorepo new-litigation-visualization `0b2c8f8`（重画仍是 v1.0.2）时
已逐条复核：三条补丁上游都还没修，全部保留原样。

2026-09-20 起多一条 PATCH 4（歸藏风 强调色换品牌色）。它和 1-3 不是一类：前三条是
**修上游的 bug**，上游修好就能删；PATCH 4 是**我们的品牌色覆盖**，上游永远不会"修"它，
每次升级都必须重新套用，不要因为"上游没这个问题"就把它删掉。

2026-09-24 起多两条：PATCH 5（`mqc-timeline-master/scripts/render_multiband.py`
的三泳道横向重叠）与 PATCH 6（`export_pptx.py` 纵向裱框后标题被背景矩形收养，
由 PATCH 5 把 dev-board#888 这份材料路由到纵向后一并暴露、一并修），
都是**修上游的 bug**，上游修好就能删。

---

## PATCH 1 · 跨平台探测中文宋体（`scripts/render.py` · `_best_installed_song`）

**症状**：macOS / Windows 上出的 PNG，标题是一排豆腐块，正文中文正常。

**根因**：标题的 CSS 字体栈首选「方正小标宋简体」，那是商业字体，用户机器上没有。
上游为此写了 `_best_installed_song()`，挑一款本机真装了的宋体顶上——但它只用
`fc-list` 探测。fontconfig 只有 Linux 是标配，macOS / Windows 的干净机器上没有这个
命令，`except` 吞掉异常后返回 `None`，顶替逻辑整个失效。正文用的是另一套 sans
字体栈、光栅器能自行回退，所以坏的只有标题——这也是它长期没被发现的原因。

上游 `9c6558f` 修过 `doctor.py` 的同类问题（"doctor crashed on Windows (no fc-list)"），
但 `render.py` 里这处没跟着修。

**改法**：探测扩成三路，优先级从高到低——
1. `LITVIZ_TITLE_FONT` 环境变量（显式指定，一律优先）；
2. `fc-list`（Linux 上仍然走原路径，行为不变）；
3. 按平台字体目录认文件名（`Songti.ttc` → `Songti SC`、`simsun.ttc` → `SimSun` 等），
   并支持 `LITVIZ_FONT_DIR` 指向宿主应用自带的字体目录。

只在探不到时才多做事，Linux 上逐字节等价。**值得回馈上游。**

## PATCH 2 · 去掉 PEP 701 语法（`scripts/render_relation.py` · `render()` 内节点输出）

**症状**：`import render` 直接 `SyntaxError`，整个出图链路在 import 期就死，
和地图内容无关。

**根因**：

```python
out.append(f'<g data-role="node" data-id="{nid}"{" data-emph=\"1\"" if nid == _hub else ""}>')
```

f-string 的表达式里写了反斜杠转义。这是 PEP 701 放开的写法，**只有 Python ≥ 3.12
能编译**。上游 `doctor.py` 自称要求「Python ≥ 3.9」，与实际不符——它自己的开发机
是 3.12+，这条线没人踩到。

我们桌面端打包的运行时是 **Python 3.11.12**（`desktop/scripts/prepare-python-service.js`
里的 `PY_VERSION`），正好落在坑里。

**改法**：把三元表达式提出来赋值再插值，输出逐字节不变。

```python
emph_attr = ' data-emph="1"' if nid == _hub else ''
out.append(f'<g data-role="node" data-id="{nid}"{emph_attr}>')
```

打完补丁后，整个 `mqc-litigation-visual-redraw/scripts/` 在 Python 3.9 上都能 import，3.11 自然覆盖。
**同样值得回馈上游**（顺带把 doctor.py 的版本门槛说法一并纠正）。

## PATCH 3 · schema 的 layout 枚举补上 `comparison_table`（`schemas/semantic-map.schema.json`）

**症状**：`comparison_table`（A vs B 对比表）一律画不出来，报
`schema: layout: 'comparison_table' is not one of [...]`——**但只在装了 `jsonschema`
的机器上**。

**根因**：`render.py` 的 `choose()` 认 `comparison_table`，`examples/comparison-table.json`
也在，SKILL.md 把它写成关系族的 A-vs-B 变体；唯独 JSON schema 的 `layout` 枚举里
没有这一项。`common.py` 里 schema 校验是 `try: import jsonschema` 的可选路径，**没装
就整段跳过**——上游开发机大概没装这个可选包，所以这条一直没被踩到。

我们这边两种解释器行为不一致（3.13 装了 jsonschema 会红、3.11 没装则放行），
这种"看装了什么可选包才决定功不功能"的不确定性本身就不能带进发行版。

**改法**：枚举里补上 `comparison_table`，并在同级留一个 `_awd_patch_3` 说明键
（JSON 没法写注释，多余的键 JSON Schema 会忽略）。**值得回馈上游。**

## PATCH 4 · 歸藏风的强调色换成墨竹青（克莱因蓝 `#002FA7` → `#2E5A50`）

**为什么非改不可**：引擎出的图是产品里的一等产物（诉讼可视化面板、导出的 pptx/drawio），
和工作台同屏显示。上游 歸藏风 用克莱因蓝作唯一高饱和锚点，换到东方清雅体系
（`design/tokens/awd-palette.json` v2.0.0）之后，那支蓝是全产品里唯一一处不属于任何
色族的颜色，同屏就是一块外来色。

**改了什么 / 没改什么**：**只并入强调色，不套整套体系**。上游那套克制的灰阶美学
（`#FAFAF8` 纸、`#333333` 墨、`#737373`/`#BDBDBD`/`#D4D4D2` 灰阶、几何、字号、留白）
一个值都没动——那是"法律文书插图"的专业调性，硬塞竹月青/玉脂白/浅茶金会毁掉它。
**深红 `#991B1B` 原样保留**，它是"唯一重点/对抗方"的授权标记，与本系列 LOGO 同源，
不参与本次换色。

`IKB` / `accent6` 这些上游标识符**名字一个没改**，只换值——改名会把 diff 摊大、
升级时每一处都要人工判，收益为零。

**动到的位置**（源码里都有 `[AWD-PATCH 4]` 标记，grep 这个标记能找全）：

| 文件 | 位置 |
|---|---|
| `scripts/render.py` | `to_guizang()` docstring、`IKB` 常量、收尾 `THEME` 白名单 |
| `scripts/export_drawio.py` | `theme_drawio()` 里的 `IKB` 常量 |
| `scripts/export_pptx.py` | `_THEME` 里的 `<a:accent6>` |
| `references/visual-style.md` | 歸藏风 palette 条目、guard 白名单条目 |
| `references/STANDARDS.md` | lint 一节里"歸藏风 legitimately uses…"的说明 |
| `tests/run_checks.py` | **8 处**字面断言（点阵不许用强调色、pptx 底纹不许是强调色、歸藏风 deck 必须带强调色、`THEME` 集合、菱形决策节点与实心块、drawio 的 `allowed` 集合与必含断言） |

**为什么连 vendored 测试也一起改**：上游那 8 处守卫把 `002FA7` 写成了字面量。
不同步改，换色当天 `run_checks.py` 立刻从 146/149 掉到 138/149——**而且掉的是真守卫**
（"歸藏风 丢了强调色"这类断言仍然有价值，只是要断言新的值）。把字面量跟着换，
守卫的语义一条没丢，仍然守着"歸藏风 只许出现白名单里那几个色"。

**没动 `CHANGELOG.md:94`**（"克莱因蓝 `#002FA7`"）——那是上游的历史记录，改它等于篡改沿革。

**`scripts/lint.py` 不用改。** 交接时的说法是"lint.py 的颜色白名单不同步改会让渲染产出假红"，
**实测不成立**：`lint.py` 第 4 项检查用的是 `_REJECTED_BLUE` **黑名单**（Tailwind slate 族 +
几个常见蓝），`#002FA7` 本来就不在里面，`#2E5A50` 更不在。真正的白名单在
`render.py` 的 `THEME` 集合与 `run_checks.py` 的 `THEME`/`allowed` 集合里——上表已覆盖。
实测：五张 歸藏风 图（flowchart / relationship / relation-tree / timeline-gantt /
comparison-table）渲染后 `lint_svg()` 零告警，输出色集恰好等于白名单。

**回归结论**：补丁前 146/149，补丁后 146/149，失败项仍是且仅是那 3 项 README 文档守卫。

**上游该不该回馈**：不该。这是我们的品牌色，不是上游的 bug。升级引擎时这条**永远保留**，
每次都要重新套用。

---

## PATCH 5 · 三条以上泳道时，泳道号覆盖几何层号却不检查带内碰撞（`mqc-timeline-master/scripts/render_multiband.py` · `render()` 内 `if lane_band:` 分支）

**症状**（dev-board#888，真机 QA-048-20260923 bug-008）：三方对读（申请方 / 北岸质检室 /
回应方）的编号型时间轴导出 PPTX 后，E-02 卡片盖住 E-01 右侧，E-01 总价只剩
"18,64..."，E-04/E-05 的日期与正文互相覆盖。draw.io / PNG / SVG 同一案例据当时记录
也重叠过，只是那份证据后来被 draw.io 编辑/保存重写，正式记录只留下了 PPTX 这一份。

**根因**：不在 `export_pptx.py`——它只把母版 SVG 的几何按 96px/inch 换算成 EMU 原样
转录，SVG 本身在这里就已经画出了重叠的矩形。声明三条以上泳道时，`render()` 用

```python
if lane_band:
    band = {e["id"]: lane_band.get(e.get("lane"), 0) for e in evs}
    max_bands = max(band.values()) + 1
```

把每个事件的横带号**直接按所属泳道覆盖**掉了 `assign_bands()` 算出的、带防撞检查
的 `band`——这是设计使然（"同一类主体永远在同一条带上，不管排不排得开"，见同一
文件上方大段注释），但覆盖之后从没再检查**同一条带内部**挨不挨得下。dev-board#888
的六事件材料里，"申请方"这条泳道占了六项里的五项，其中 E-01/E-02 在轴上只隔一个
列距（约 140px）却要摆 214px 宽的卡，真实横向重叠 74px——这正是 export_pptx.py
转录出来、PPTX 里两个 `<p:sp>` 相交的那 74px。

用 dev-board#888 的六事件材料手工重建 semantic map（三条泳道）直接调
`render_multiband.render()` 复现，逐步加打印确认：`assign_bands()` 算出的 `band`
本来是 `{'E-01': 0, 'E-02': 1, ...}`（正确错开），`if lane_band:` 覆盖后变成
`{'E-01': 0, 'E-02': 0, ...}`（被摁进同一条带）。

**改法**：覆盖之后补一次同侧同带的横向碰撞检查（按 x 排序，相邻两项间距
`< 卡宽 + CARD_CLEAR` 就判定装不下），撞了就 `raise ValueError`，带上机械理由
（撞的是哪两个事件、隔多少 px、卡宽要多少 px）。这是这份引擎一贯的做法——
`render_dated_v2.py` 的 "collision: refuse, do not invent" 一节、`[D5]`/`[D6]`
那两条同侧净空门禁，都是同一个原则：排不下要拒绝，不能悄悄画出重叠。拒绝之后
`render_figure.deliver()` 的阶梯会自动改试纵向（`render_vcolumns`），所以修完
仍然交得出一张图，只是这一档案例的交付形态从横向变成了纵向。

**验证**：
- `litviz/tests/test_timeline_pptx_overlap.py`——用 dev-board#888 的六事件材料
  重建 semantic map，跑 `render_figure.deliver()` → `export_pptx.export()`，解析
  `ppt/slides/slide1.xml` 里每个 `roundRect`（事件卡片）的 `<a:off>/<a:ext>`，
  断言两两不相交，且金额文本 "18,640.00" / "2,300.00" 完整出现在某个 `<a:t>` 里；
  另有一个两泳道防呆用例（确认新加的检查不误伤不触发 `lane_band` 覆盖的场景）和
  一个约 200 字长文本用例（确认换行也不会导致重叠）。补丁前后跑了一次 A/B
  （`git stash` 挑出这一个文件）：补丁前 `test_six_event_three_lane_case_...`
  精确复现 `(0, 1)` 相交（E-01/E-02），补丁后三个用例全绿。
- `mqc-timeline-master/tests/run_checks.py`：补丁前后各跑一次，"渲染 27 张，3 项
  未过"字面不变（那 3 项是本机没装 `docx@9`/`reportlab` 与未 vendor 的
  `docs/adr/`，与本补丁无关，打补丁前就存在）；三泳道那组守卫（`ABC` 循环分配、
  同侧事件天然隔 3 个列距，从不触发本补丁新加的检查）与 24 项"故意改坏必须报错"
  的守卫全部照常通过。

**上游该不该回馈**：该。这是上游自己的设计漏了一步防撞检查，不是我们的定制。

**验证这份补丁时顺带发现的第二个缺陷**：PATCH 5 把 dev-board#888 这份密集材料
的交付形态从横向改判成纵向之后，纵向这条路自己又带出一个此前从没在这个案例上
出现过的问题——标题被画进了几乎铺满整页的背景矩形里。查下去发现这是
`export_pptx.py` 一个独立的、更早就存在的缺陷（不含泳道的既有示例
`vertical-single-column.json` 单独跑一遍就能复现，与 PATCH 5、与泳道毫无关系），
只是原来没有任何一张纵向出的图恰好被直接喂进 `export_pptx.export()` 去验证过，
所以一直没露出来；PATCH 5 一上，#888 这张图第一次真的落进了这条路径，缺陷才
第一次变成这张图**自己的**交付面。按维护者的判断，这条缺陷因此并入本卡一并修，
见下面 PATCH 6，不再另开卡。

---

## PATCH 6 · 纵向裱框之后标题被画布背景矩形收养（`mqc-litigation-visual-redraw/scripts/export_pptx.py` · `parse_svg()` 的 `rect` 分支 + `attach_text()`）

**症状**：dev-board#888 六事件材料被 PATCH 5 路由到纵向形态后，PPTX 里的标题
「样品采购交接争议事实经过时间轴」不是页顶的一行小字，而是一个 `x=42 y=29
w=700 h=680`（页高 738px 的 92%）的巨大矩形，横压在第 3 张事件卡上。

**根因**：`attach_text()` 用"形状面积 ≥ 画布面积的 90%"这一条面积比阈值来
判断一个矩形是不是画布背景（背景不许被当成标题等自由文字的容器，否则文字被
折进背景、背景本身又变成一个巨大的可点选对象）。这个阈值只在**未裱框**的画布
上成立：`render_vcolumns.py` 自己画的 `<rect data-role="canvas-bg" width=...
height=...>` 是按**裱框前**的画布尺寸画的；`render_figure._frame_file()` 用
`paper.frame()` 给纵向长图裱白边时，整段原内容被包进一层
`<g transform="translate(dx,dy)">`、外层 `<svg width height>` 相应变大，但
背景矩形的绝对尺寸没变——分母（裱框后的画布）变大了，分子不变，占比就可能从
100% 掉到 90% 以下。一旦掉过线，背景矩形就不再被当成背景，而是被当成一个普通
形状参与"哪个矩形该收养这段居中文字"的判定——它比任何卡片都大、标题又恰好在
它中心水平居中，于是标题被收进了背景。

这个面积比阈值失效的场景，`mqc-timeline-master` 自己的 `export_formats.py`
（时间轴管线自己拼 pptx/vsdx 的那层）其实**已经踩过并写了 workaround**——它的
`_svg_for_export()` 在喂给 `export_pptx.export()` 之前，先把 SVG 里那一行
`<rect data-role="canvas-bg" .../>` 正则删掉（见该文件同名函数的长注释）。
但这只是**调用方那一侧**的补丁：任何直接拿一张裱过框的纵向时间轴 SVG 去调
`export_pptx.export()`、不经过 `export_formats.deliver()` 的调用方（本卡的
回归测试、`dev-board#888` 的复现脚本都是这样调的），仍然会踩中这个 bug——而
`export_pptx.py` 本身早就是既有的、单独可调用的机器契约（`litviz/skills/
mqc-litigation-visual-redraw/scripts/render.py` 也直接调它），没有理由要求
每一个调用方都单独知道"记得先剥掉 canvas-bg"这条隐藏规矩。

**改法**：渲染器自己已经在背景矩形上标了 `data-role="canvas-bg"`——这条信息
比"算一遍面积占比"更直接也更不会被裱框这类几何变换破坏，所以让 `attach_text()`
直接认这个标记，而不是继续单靠会被裱框拖垮的面积比：

1. `parse_svg()` 解析 `<rect>` 时把 `data-role` 原样存进图元的 `role` 字段
   （之前只挑了 `x/y/w/h/rx/fill/pattern/stroke/sw`，`data-role` 被直接丢弃）。
2. `attach_text()` 在挑"可以收养文字的宿主形状"时，先按 `role != "canvas-bg"`
   过滤掉一遍，再叠加原来的 90% 面积比检查（面积比检查保留，作为没有这个标记
   的 SVG——比如姊妹引擎 `render.py` 自己画的纸张背景——的兜底；那份背景永远
   画成整幅画布大小，不裱框，原阈值对它继续成立）。

**验证**：
- `litviz/tests/test_timeline_pptx_overlap.py` 的
  `test_six_event_three_lane_case_has_no_overlapping_shapes` 与
  `test_long_body_variant_still_has_no_overlapping_shapes` 各加了两条断言：
  标题所在 `<p:sp>` 的 `<a:ext>` 高度不超过页高的 15%，且不与任何事件卡片矩形
  相交。A/B（`git stash` 只挑出 `export_pptx.py` 这一个文件、保留 PATCH 5）：
  补丁前 `test_six_event_three_lane_case_...` 报
  `680.0 not less than or equal to 110.7`（标题矩形高 680px，超过页高 738px
  的 15% 上限 110.7px）与标题跟全部 6 张卡片相交，`test_long_body_variant_...`
  报标题与全部 6 张卡片相交；补丁后三个用例全绿。
- 用 `vertical-single-column.json`（不含泳道，独立于 PATCH 5）单独复现过一次：
  `render_vcolumns.render()` → `paper.frame()` 裱框 → `export_pptx.export()`，
  补丁前标题矩形 `x=42 y=29 w=700 h=811`，补丁后标题落在它自己的小文本框里。
- `mqc-timeline-master/tests/run_checks.py` 与 `mqc-litigation-visual-redraw/
  tests/run_checks.py`：补丁前后各跑一次，两边的失败项字面不变（时间轴那边仍是
  `docx@9`/`reportlab`/`docs/adr` 三项环境缺席，引擎那边仍是 146/149、缺席的
  正好是那 3 项未 vendor 的 README 文档守卫），确认这条补丁不影响任何既有回归。

**上游该不该回馈**：该。这是上游自己的面积比启发式在"裱框"这类几何变换下失效
的 bug，`data-role` 标记本来就是渲染器自己打的，用它而不是重新猜面积占比是更
稳的做法。

---

## 回归怎么跑

```bash
python3 litviz/skills/mqc-litigation-visual-redraw/tests/run_checks.py
```

上游自带 149 项，要 graphviz（`brew install graphviz`）。补丁不该让任何一项变红——
两条补丁都是等价改写，红了就是改错了。
