# litviz —— 诉讼可视化

把案件材料画成能直接进诉讼材料的图：时间轴、流程图、当事人关系图。

出图引擎来自 [mqc-litigation-visual-redraw](https://github.com/MiaoQichuan/mqc-litigation-visual-redraw)
（作者 **缪奇川**，MIT），vendor 在 `mqc-litigation-visual-redraw/`（重画引擎）与 `mqc-timeline-master/`（时间轴大师）下。改它之前先读 [UPSTREAM.md](UPSTREAM.md)
与 [PATCHES.md](PATCHES.md)。

## 分工

引擎有一条刻意的设计，我们完整继承：**模型只做抽取，脚本负责画。**

- **模型**：读原件（判决书 / 证据 / 手绘翻拍 / 一段案情），逐字转录，产出一份
  `semantic-map.json`。判断题（哪个是胜负手、事件先后、上下摆放）落在这份 JSON 里。
- **脚本**：算全部几何——列宽、日期比例、文字折行、防重叠堆叠、连线路由、配色。

好处是出图质量取决于 JSON 对不对，而不取决于模型对像素有多聪明。弱模型也能出对。
所以 **prompt 里绝不要让模型手写 SVG 坐标**。

## 目录

```
litviz/
  cli.py          我们写的机器契约层——后端唯一入口（stdout 只有一行 JSON）
  tests/          我们的契约测试
  mqc-litigation-visual-redraw/   上游原样 vendor：重画引擎（补丁走 PATCHES.md 登记）
  mqc-timeline-master/            上游原样 vendor：时间轴大师（同上；靠兄弟目录名找共享内核，别改名）
```

## 命令

```bash
python3 litviz/cli.py doctor
python3 litviz/cli.py validate   --map map.json
python3 litviz/cli.py checkpoint --map map.json --suggest 3
python3 litviz/cli.py render     --map map.json --out /项目/图/时间轴 --mode 奇川风
```

`--out` 是**不带扩展名的路径前缀**。一次跑完写出五种格式：`.svg`（母版）、`.png`
（预览）、`.drawio`、`.pptx`、`.vsdx`。后三种是**原生可编辑图形**，不是贴图——
律师用哪个工具接着改，不是我们该替他猜的。

## 两件必须知道的事

**一、草稿闸。** 地图里 `checkpoint.confirmed` 不为 `true` 时，产物一律写成
`*-draft.*`。这是上游的安全设计：未经用户确认的读法，不许当终稿归档进诉讼材料。
包装层不许抹平它，`cli.py` 如实回报 `draft: true`。

**二、红色是授权制。** 深红 `#991B1B` 只标一处，且必须由用户授权
（`checkpoint.emphasis_source`）。地图说不清红色从哪来，引擎就一点都不画。
这条是脚本强制的，不靠模型自觉。

## 依赖

引擎是**纯 stdlib**，Python ≥ 3.11 即可（打完 PATCH 2 后 3.9 也能跑）。

| | |
|---|---|
| graphviz `dot` | **只有流程图（`graphviz_flow`）需要**。其余 6 种布局零原生依赖。 |
| SVG 光栅器 | 只影响 `.png`。没有也不影响 `.svg` 母版——而浏览器原生就能渲染 SVG，桌面端预览走的正是这条路。 |
| 中文字体 | 标题走宋体栈。见 PATCH 1；`LITVIZ_TITLE_FONT` / `LITVIZ_FONT_DIR` 可指定。 |

`graphviz_relation` 名字里带 graphviz，但 v1.0.2 起已换成确定性的无 graphviz 布局，
那个文件里的 `run_dot` 是死代码。**别凭布局名猜依赖**，依赖矩阵由
`tests/test_cli.py` 的「无 graphviz 时的布局矩阵」那一组钉住。

## 验证

```bash
python3 litviz/tests/test_cli.py
```

跑我们的契约测试，并连带跑上游自带的 149 项回归。预期 `146/149`——缺的 3 项是
README 文档守卫，我们没 vendor 上游 README（与出图链路无关），属结构性缺席，
测试会断言**失败的正好是且仅是那 3 项**。
