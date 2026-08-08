---
name: litigation-visual
description: 诉讼可视化领域。任务涉及时间轴/流程图/当事人关系图出图、litviz 引擎与 vendor 升级、semantic-map 契约、litigation_* 工具、左栏「诉讼可视化」面板、graphviz 打包时，先读本文档再动代码。
---

# 诉讼可视化 领域地图

职责边界：把案件材料画成图这条链路。不含 AI 编排器本身（ai-chat 领域）、不含
skill 注入机制（plugin-system 领域）、不含文件预览组件（utility-tools 领域）。

## 一句话架构

**模型只做抽取，脚本负责画。** 模型读材料 → 产出 `semantic-map.json`；确定性 Python
脚本算全部几何（列宽、日期比例、折行、防碰撞、连线路由、配色）。出图质量取决于
JSON 对不对，不取决于模型对像素多聪明——这是上游的核心设计，别绕过它。

**绝对不要让模型手写 SVG 坐标。**

## 关键文件

**引擎（vendor，别手改）**
- `litviz/engine/` — 上游 mqc-litigation-visual-redraw v1.0.2（作者缪奇川，MIT）原样拷贝。
- `litviz/UPSTREAM.md` — vendor 来源、commit、升级步骤。
- `litviz/PATCHES.md` — 三条本地补丁（代码里搜 `[AWD-PATCH n]`）。升级时逐条复核。
- `litviz/README.md` — 分工、命令、依赖矩阵。

**我们写的**
- `litviz/cli.py` — **机器契约层，后端唯一入口**。stdout 恰好一行 JSON，引擎的人类
  输出全转 stderr。子命令 doctor / validate / checkpoint / render。
- `litviz/tests/test_cli.py` — 契约测试 + 连带跑上游 149 项回归。

**后端**
- `service/ai/LitigationVisualService.java` — 进程边界：定位 Python 与 litviz 目录、
  跑 cli.py、解 JSON。含参考文档读取（带路径穿越防护）。
- `service/ai/tools/LitigationVisualTools.java` — 三个 @Tool：`litigation_reference`
  （渐进披露读规范）、`litigation_checkpoint`（三问）、`litigation_render`（出图）。
- `service/ai/LitigationPngService.java` — SVG→PNG（Batik，纯 Java）。位图是插进文书
  的那一环，见「已知地雷」里 PNG 那条。
- `service/LitigationVisualPanelService.java` — 面板后端：图廊、换风格、拼 kickoff prompt。
- `controller/LitigationVisualController.java` — /api/litigation-visual。
- `backend/skills/litigation-visual/` — skill.yml + prompt.md（精简路由，细则按需读）。

**前端**
- `components/LitigationVisualPanel.vue` — 左栏面板：选材料 → 开始出图 → 图廊 → 换风格。
- `config/leftSidebarPlugins.js` — key `litigation-visual`。
- `pages/project-overview/project-overview.vue` — 面板分支 + 三个 handler
  （`handleLitigationStart` / `handleLitigationOpenFile` / `handleLitigationScopeSelect`）。

**打包**
- `desktop/scripts/prepare-graphviz.js` — 烙最小 graphviz（约 4MB）。
- `desktop/package.json` extraResources — `graphviz` / `skills` / `litviz` 三项。
- `desktop/main/services/backend-service.js` — 打包态注入 `LITVIZ_DIR` /
  `AWD_PYTHON_HOME` / `LITVIZ_GRAPHVIZ_DIR` / `AI_SKILLS_BUILTIN_DIR`。

## 核心契约

**semantic-map.json**：`layout` + `title_text` + 各布局的数据字段 + `provenance` +
`checkpoint`。schema 在 `litviz/engine/schemas/semantic-map.schema.json`，
字段说明在 `references/semantic-map-schema.md`。

**七种布局**：`numbered_point_timeline`（安全默认）/ `dated_point_timeline` /
`proportional_gantt` / `graphviz_flow` / `graphviz_relation` / `relation_tree` /
`comparison_table`。

**草稿闸**：`checkpoint.confirmed` 不为 true → 产物一律 `*-draft.*`。这是上游的安全
设计（未经确认的读法不许当终稿归档），**包装层不许抹平**。

**红色授权制**：深红 `#991B1B` 只标一处，且必须 `checkpoint.emphasis_source` 交代
来源。地图说不清红从哪来，引擎一点都不画。脚本强制，不靠模型自觉。

**产物落点**：一图一文件夹，内含五种格式 + `<名>.map.json`（语义地图，留着才能
「换风格」不重新问模型）。身份靠 `wpsFileId` 前缀
`project_litviz_` / `project_litvizmap_`。

## 依赖矩阵（实测，别凭名字猜）

| | 需要 graphviz？ |
|---|---|
| 三种时间轴 / `relation_tree` / `comparison_table` / **`graphviz_relation`** | 否 |
| `graphviz_flow`（流程图） | **是** |

`graphviz_relation` 名字里带 graphviz，但 v1.0.2 起已换成确定性的无 graphviz 布局
（`_layout_nodes`），那个文件里的 `run_dot` 是死代码。这条矩阵由
`litviz/tests/test_cli.py` 的「无 graphviz 时的布局矩阵」钉住。

Python 下限 **3.11**（与打包运行时一致）。引擎原本要 3.12+，已由 PATCH 2 抹平。

## 已知地雷

- **GVBINDIR 必须运行时显式设**。graphviz 把插件目录**编译期焊死**在 libgvc 里，
  指向构建机的安装路径。构建机上那个路径真的存在，所以自检会假绿；用户机器上
  报 `Format: "plain" not recognized`。`litviz/cli.py` 负责设，`prepare-graphviz.js`
  有一条**反向自检**（不给 GVBINDIR 就必须失败）守着这个契约。
- **install_name_tool 改过的 Mach-O 必须补 ad-hoc 签名**，否则 Apple Silicon 上
  内核直接 SIGKILL——不报错、不弹窗，进程凭空消失。
- **PNG 由服务端 Batik 出，不靠引擎**（`LitigationPngService`）。引擎自己的 PNG 依赖
  外部光栅器（rsvg-convert / inkscape / soffice / cairosvg），桌面端一个都不随包分发，
  所以那条路在多数用户机器上是空的。**位图不是可选项**：`doc_insert_image` 只收
  jpg/png/gif/bmp/webp，没有 PNG 这张图就插不进用户正在写的起诉状——那是主线工作流。
  - 中文靠**注册随包字体 + 内存里给字体栈追加末位兜底**，不指望系统字体：SVG 标题
    首选方正小标宋等商业字体，干净的 Windows 上一个都没有，落到通用 serif 就是
    Times（不含汉字）。正文那条 sans 栈里有 Microsoft YaHei 所以看着正常，
    于是这个 bug 只坏标题、特别容易漏——与 PATCHES.md 的 PATCH 1 是同一个坑的两侧。
  - 追加而非替换，且插在通用关键字之前（通用关键字一旦命中就不再往后找）。
    磁盘上的 SVG 母版一个字节不动。
  - `LitigationVisualServiceTest` 断言的是「引擎侧 PNG 的有无与本机 doctor 报的
    光栅器能力一致」，不是写死的文件数——写死会让测试变成「构建机装了什么」的探针。
  - `LitigationPngServiceTest` 里字体栈重写与「Batik 接通了」两组**不依赖字体文件**，
    在没跑过 fetch-lowa-assets 的机器（含 CI 后端 job）上照跑；CJK 覆盖与真图光栅化
    两组按字体存在与否 skip。
- **Windows 侧打包体积 19.7 MB，macOS 只有 4.3 MB**（CI 实测）。差的约 15 MB 是
  各种渲染后端 DLL（pango/cairo/gd/poppler…），我们只用 `-Tplain` 其实用不到。
  没削是因为 Windows 上算不出 DLL 依赖闭包（没有 `otool -L` 的等价物），
  盲删的后果是运行期 dlopen 失败、且只在用户机器上出现。**要削得先有台 Windows 验证。**
- **`.drawio` / `.vsdx` 必须挡在 LOWA 编辑器之外**（`fileOpenTabs.js` 的
  `externalSourceTypes`）。它们带 wpsFileId，不挡就会走「可编辑」兜底分支，
  被只有 Writer+Calc 的引擎当文本导入，满屏乱码——与当年 PDF 同一类事故。
- **触发词必须原样出现在 prompt 正文里**才命中 skill 注入（pinnedSkillId 只裁工具
  不注入 prompt）。所以 kickoff prompt 由服务端拼，不交给前端。
- 新增 `litigation_*` 工具要同步**三处**：`frontend/src/utils/toolDisplayNames.js`
  （否则面板里显示英文代号）、skill.yml 的 `allowed_tools`（漏列即对模型隐藏该能力）、
  以及 `RealToolBeans.instantiateAll()`（评测用的工具 bean 清单是手工维护的，
  漏了就等于那个工具在回放评测里从未注册、可见性断言形同虚设——
  `EvalToolBeanParityTest` 现在会红）。
- skill 命中时的工具可见性由回放用例 `skill-litigation-visual-tools-visible`
  （`backend/src/test/resources/ai-eval/cases/cases-skill.json`）守住。
- 上游 `doctor.py` 自称「Python ≥ 3.9」与实际不符（见 PATCHES.md PATCH 2）；
  `schemas/` 的 layout 枚举曾漏 `comparison_table`（PATCH 3）。**升级引擎后
  重跑 `litviz/tests/test_cli.py`，别信上游的自述。**

## 验证

```bash
python3 litviz/tests/test_cli.py                       # 契约 + 上游 149 项（预期 146/149）
cd backend && mvn test -Dtest='Litigation*,BuiltinSkillsTest,SkillRegistryTest'
node desktop/scripts/prepare-graphviz.js --from "$(brew --prefix graphviz)" --out /tmp/gv
cd frontend && npm run check:emits && npm run build:h5
```

上游 149 项预期 **146/149**：缺的 3 项是 README 文档守卫（我们没 vendor 上游 README），
测试会断言失败的**正好是且仅是**那 3 项，别把它当成可以忽略的红。

要跑全 `LitigationPngServiceTest`（含中文覆盖与真图光栅化）得先有随包字体：
`node desktop/scripts/fetch-lowa-assets.js`，或从已安装的发行版
`Resources/frontend/dist/zetaoffice/` 拷 `cjk-serif.otf` 与 `cjk.ttc` 过去。
没有字体时那两组自动 skip，其余照跑。
