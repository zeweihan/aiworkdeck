# 对 vendor 引擎打的本地补丁

`litviz/engine/` 原则上是上游原样拷贝（见 `UPSTREAM.md`）。下面每一条都是**不得不打**
的补丁，代码里对应位置都有 `[AWD-PATCH n]` 标记，搜这个标记能找全。

升级引擎时逐条复核：上游若已自行修掉，就删掉这一条，别叠着打。

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

打完补丁后，整个 `engine/scripts/` 在 Python 3.9 上都能 import，3.11 自然覆盖。
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

---

## 回归怎么跑

```bash
python3 litviz/engine/tests/run_checks.py
```

上游自带 149 项，要 graphviz（`brew install graphviz`）。补丁不该让任何一项变红——
两条补丁都是等价改写，红了就是改错了。
