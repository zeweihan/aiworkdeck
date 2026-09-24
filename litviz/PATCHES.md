# 对 vendor 引擎打的本地补丁

`litviz/skills/mqc-litigation-visual-redraw/` 原则上是上游原样拷贝（见 `UPSTREAM.md`）。下面每一条都是**不得不打**
的补丁，代码里对应位置都有 `[AWD-PATCH n]` 标记，搜这个标记能找全。

升级引擎时逐条复核：上游若已自行修掉，就删掉这一条，别叠着打。

2026-08-25 升级到 monorepo new-litigation-visualization `0b2c8f8`（重画仍是 v1.0.2）时
已逐条复核：三条补丁上游都还没修，全部保留原样。

2026-09-20 起多一条 PATCH 4（歸藏风 强调色换品牌色）。它和 1-3 不是一类：前三条是
**修上游的 bug**，上游修好就能删；PATCH 4 是**我们的品牌色覆盖**，上游永远不会"修"它，
每次升级都必须重新套用，不要因为"上游没这个问题"就把它删掉。

2026-09-24 起多一条 PATCH 6（pptx 导出的背景判定），和 1-3 同类，上游修好就能删。
编号 5 留给 dev-board#888 在另一分支上的 PATCH 5，不是漏号。

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

## PATCH 6 · pptx 导出的背景判定改用包含关系（`scripts/export_pptx.py` · `attach_text()`）

**症状**：时间轴出的 pptx 里，标题的形状是一个跟内容区一样大的框（纵向单列样例量到
700x811、偏移 42,29），文字在框里垂直居中，看起来就是"标题跑到页面正中"；律师点标题，
选中的是整块内容底。

**根因**：`attach_text()` 把居中于某个形状的文字折进该形状，背景靠"面积 >= 画布 90%"
排除，画布尺寸取自最外层 `<svg width height>`。时间轴渲染器自己画一张内容底
（`render_vcolumns.py` / `render_multiband.py` 带 `data-role="canvas-bg"`，
`render_dated_v2.py` / `render_spans_v2.py` / `paginate.py` 的内容底**不带**这个标记），
之后 `paper.frame()` 把内容裹进 `translate(dx,PAD_TB)`、把外层 `<svg>` 放大到整幅、
再铺一张整幅白底。整幅白底仍是 100%，被排除；内容底相对整幅只剩 80-90%
（纵向单列 700x811 / 784x869 = 83.3%），从 90% 那条线底下漏过去，成了合法宿主，
把水平居中的标题收养了。docstring 里对背景的定义本来就是"几何上包住一切"，
面积比只是它的近似，框一裱就失真。

**改法**：原来的 90% 判定原样保留，旁边补一条包含关系判定：形状按面积从大到小排，
一个形状若包住了**除更大的背景之外的所有其他图元**（矩形/椭圆/多边形按外接框，
文字按锚点，连线按全部点，容差 1px），就算背景，不做宿主。这样整幅白底和内容底
逐层都被认出来，与面积比、与 `data-role` 标记都无关。

误判方向是安全的：一张图里如果恰好只有一个内容框、它包住了其余一切，那它的居中
文字不被收养，留成一个位置完全正确的独立文本框，只是少了"双击框即编辑"这点便利，
不会错位。

`mqc-timeline-master/scripts/export_formats.py::_svg_for_export()` 在交给本导出器前
摘掉 `data-role="canvas-bg"` 那一行的做法**保留**，作为双保险；但它只认带标记的内容底，
日期型、期间型与分页产物的内容底没有标记，摘不掉——这几种图在本补丁之前经
`export_formats` 导出仍然会把标题折进内容底，本补丁同时修好了它们。

**回归**：新增 `litviz/tests/test_timeline_pptx_title_backdrop.py`（纵向单列样例渲染 +
`paper.frame()` + 导出，断言标题只出现在一个形状里、框高 < 整幅 25%、y < 整幅 20%，
且背景大小的形状不含文字；另有两条直接调 `attach_text()` 的单元断言）。补丁前 3 条里
红 2 条，补丁后全绿。重画引擎自带 8 个样例 x 三种风格（默认/白描/歸藏风）共 24 份 pptx
补丁前后逐字节相同；`run_checks.py` 仍是 146/149，失败项仍是且仅是那 3 项 README 文档守卫。
时间轴 8 个样例（含分页产物共 13 份）直接裱框后导出：每份多出 1 个对象，即标题从内容底里
独立出来；经 `export_formats` 摘标记后导出：带标记的 6 份逐字节不变，不带标记的 7 份
（日期型 1、期间型 1、分页页面 5）各多 1 个对象。

**上游该不该回馈**：**该**。这是上游导出器的通用缺陷，任何"先画内容底、再加外框"的
调用方都会踩到。

---

## 回归怎么跑

```bash
python3 litviz/skills/mqc-litigation-visual-redraw/tests/run_checks.py
```

上游自带 149 项，要 graphviz（`brew install graphviz`）。补丁不该让任何一项变红——
两条补丁都是等价改写，红了就是改错了。
