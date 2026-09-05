# Spec：桌面端 PPT 实时编辑桥（Impress / slide_* 原语集）

调研与设计日期：2026-08-07。状态：设计定稿待排期，**本文档不含产品代码改动**（仅一次性调研探针，见附录 A，不入库）。

前置：`docs/superpowers/specs/2026-08-07-document-capability-matrix.md`「五、最值得复核的三个判定」第 1 条把「PPT 桌面端走批量文件重写还是建 Impress 实时编辑桥」列为待维护者拍板的架构分岔。维护者已拍板走实时编辑桥（「迟早得走」）。本文档是该决策的落地设计。

---

## 摘要（先看这三条）

1. **引擎结论 = (c)：Impress 在编译期就被裁掉了，必须重烧引擎。** 但裁掉的方式不是源码补丁，而是 LibreOffice 自己的一个 configure 开关 `--with-wasm-module`（默认值 `'calc writer'`）。**打开 Impress = 在 `desktop/lowa-build/autogen.input` 加一行**，不需要新的源码补丁，四个既有补丁的锚点全部不受影响。工程量落在「再跑一次 mega-build.sh + 重新自托管 + 体积/回归验收」，不是「改 LibreOffice 源码」。
2. **原语集取名 `slide_*`**，与 `doc_*`（Writer）/`sheet_*`（Calc）三分，工具名 = action 名不做映射。共 20 个原语，覆盖 Office 插件 PPT 面现有 14 个 command 的全部能力，另加 4 项桌面独有能力（备注页读写、可见定位、版式/母版、图片插入）。
3. **PptxTools 批量路线保留，不废弃**，且要把两条路线的分工写进工具描述。核心理由不是「服务端也要用」（那只是其一），而是 **pptx 经 Impress 往返会整篇重写文件**，保真度风险显著高于 python-pptx 的定点改写——这一条同时是本项目的头号风险。

---

## 一、引擎具备性查实：结论 (c)，Impress 编译期即被裁

### 1.1 结论

| 判定项 | 结论 |
|---|---|
| Impress（sd 模块）是否在 WASM 引擎里 | **否**。sd/sdext/slideshow/animations/starmath 整批未参与构建 |
| 打开链路是否只是「被限制」 | **不是**。不存在被限制的模块——document factory、pptx 的 Filter/Type 注册、Impress UI 资源三者同时缺席 |
| 需不需要重烧 | **需要**。但改动是一个 configure 参数，非源码补丁 |

原有记忆口径「引擎仅 Writer+Calc 实锤（PR#165）」**准确**，本次只是把「为什么」查到了根上，并证明代价比想象的小。

### 1.2 证据链（从产物往回推，五条独立证据互相印证）

**证据 1：fs 镜像里没有 Impress/Draw 的模块资源。**
解包 `soffice.data.js.metadata`（1656 个文件），`share/config/soffice.cfg/modules/` 下只有：
`StartModule(1) / scalc(245) / schart(54) / sglobal(55) / sweb(49) / swform(53) / swreport(54) / swriter(302) / swxform(55)`。
没有 `simpress/`、没有 `sdraw/`、没有 `soffice.cfg/simpress/`。`share/registry/` 里 `impress.xcd` 和 `draw.xcd` 在，但内容只剩 Setup/Common/Embedding/Jobs 段——`impress.xcd` 里 `PowerPoint` 出现 0 次、`FilterFactory` 0 次、`TypeDetection` 0 次。

**证据 2：services.rdb 里没有 sd 的任何一个库。**
从 fs 镜像取出 `/instdir/program/services/services.rdb`（168656 字节，101 个 `uri=`、723 个 `<implementation>`）：
- Writer 侧齐全：`libswlo.a` / `libswdlo.a` / `libmswordlo.a` / `libwriterfilterlo.a`
- Calc 侧齐全：`libsclo.a` / `libscdlo.a` / `libscfiltlo.a`
- **sd 侧一个都没有**：无 `libsdlo.a` / `libsdui*` / `libsddlo.a`
带 `Impress` 字样的注册实现只有 5 个，全部是 xmloff 的 ODF 导入器（`com.sun.star.comp.Impress.XML*Importer`）；带 `ppt` 的只有 1 个：`com.sun.star.comp.oox.ppt.PowerPointImport`。
**关键推论**：`com.sun.star.comp.Draw.PresentationDocument`（Impress 文档工厂）未注册 → `private:factory/simpress` 无实现可造；pptx 导入过滤器 `Impress MS PowerPoint 2007 XML` 的 `DocumentService` 正是 `com.sun.star.presentation.PresentationDocument`，所以即使 oox 的 pptx 解析器在，也没有能装内容的文档模型。
pptx **导出**同理缺失：`com.sun.star.comp.Impress.oox.PowerPointExport` 的注册处是 `sd/util/sd.component`（不是 oox），sd 不在 → 导出也没有。

**证据 3：wasm 二进制里没有 sd 的 RTTI。**
brotli 解压 `soffice.wasm`（33.9 MB → 150 MB）后按 Itanium mangling 前缀计数：
`N2sw`（namespace sw）307 次、`N2sc`（namespace sc）146 次、**`N2sd` 0 次**；`SdDrawDocument` / `SdXImpressDocument` / `DrawDocShell` / `SdPage` 均 0 次。
对照组：`N3oox3ppt` 46 次（oox 的 pptx 解析器**在**）、`N3oox3xls` 213 次。所以缺的确实是 sd 这一层，不是 OOXML 解析层。
（`private:factory/simpress`、`Impress MS PowerPoint 2007 XML` 这类字符串在 wasm 里能 grep 到，但它们来自被去重的全局字符串池与配置数据，不是代码存在的证据——证据 3 的 RTTI 计数才是。）

**证据 4：上游 configure 的开关（根因）。**
`LibreOffice/core` 分支 `distro/allotropia/zeta-24-2`：

- `configure.ac:2155-2159`
  ```
  AC_ARG_WITH(wasm-module,
      AS_HELP_STRING([--with-wasm-module=<writer/calc/impress>],
          [Specify which main module to build for wasm.
          Default value is 'calc writer'.]),
  , [with_wasm_module='calc writer'])
  ```
- `configure.ac:4124-4145`：Emscripten 主机下先把四个 strip 开关全部置 TRUE，再按 `--with-wasm-module` 逐项解除：
  `calc` 解除 `ENABLE_WASM_STRIP_ACCESSIBILITY`+`ENABLE_WASM_STRIP_CALC`；`writer` 解除 `ENABLE_WASM_STRIP_WRITER`；`impress` 解除 `ENABLE_WASM_STRIP_ACCESSIBILITY`+**`ENABLE_WASM_STRIP_BASIC_DRAW_MATH_IMPRESS`**。
- `RepositoryModule_host.mk:53/139/146/153`：`ENABLE_WASM_STRIP_BASIC_DRAW_MATH_IMPRESS` 为真时，`animations`、`sd`、`sdext`、`slideshow`、`starmath` 四段模块目录整体不进构建。
- `filter/Configuration_filter.mk:567/683`、`postprocess/CustomTarget_registry.mk:73/98/338`：同一开关同时裁掉 fcfg_draw/fcfg_impress/fcfg_math 的 Type 与 Filter 注册。
- `static/CustomTarget_emscripten_fs_image.mk:855-1135`：同一开关裁掉 sdraw/simpress 的全部 UI 资源。

我们的 `desktop/lowa-build/autogen.input` 没有出现过 `--with-wasm-module`，所以吃的是默认值 `'calc writer'`——**Impress 从来没有被编进去过**。

**证据 5：真引擎运行期探针（本次实跑，非静态推断）。**
探针脚本见附录 A，跑在 `/Users/zewei/aiworkdeck-qa/repo/frontend/dist/zetaoffice`（生产同款 24.2.8-zhcn-r3 引擎）+ 无头 Chrome：

```
=== probe_modules ===
{ "success": true,
  "swriter": true,
  "scalc": true,
  "simpress": "error: com.sun.star.lang.IllegalArgumentException com::sun::star::lang::IllegalArgumentException,",
  "sdraw":    "error: com.sun.star.lang.IllegalArgumentException com::sun::star::lang::IllegalArgumentException," }

=== load_document(probe.pptx, 29109 bytes) ===
{ "success": false,
  "message": "load_document failed: file: loadComponentFromURL returned null | stream: loadComponentFromURL returned null" }
```

probe.pptx 是 python-pptx 生成的合法两页演示文稿（含中文标题）。两条加载策略（MEMFS 文件路径 + private:stream 显式 FilterName）都返回 null，与证据 2 的推论完全一致。

### 1.3 一个顺带查实的因果

`FilePreview.vue` 的注释「LOWA 无 Draw 时 Writer 会把 PDF 二进制当文本导入，满屏乱码（2026-07 真机截图证实）」找到了根因：`filter/Configuration_filter.mk:567` 起的被裁块里就包含 `pdf_Portable_Document_Format` 这个 Type。开 Impress 会连带把 Draw 的 PDF 导入类型注册回来——这是 Phase 0 必须复验的**行为变化**（详见 §2.4）。

---

## 二、Phase 0：引擎重烧配方

### 2.1 去哪个仓改什么

引擎构建仓**不在本仓库**。本仓库里的 `desktop/lowa-build/` 是「构建系统的定义与补丁集」，构建在一台裸 Ubuntu 22.04 VM 上跑，源码是 `github.com/LibreOffice/core` 分支 `distro/allotropia/zeta-24-2` 的临时 checkout。

需要改的**只有本仓一个文件**：

`desktop/lowa-build/autogen.input`，在 `--with-lang` 那一段旁边加一行：

```
--with-distro=LibreOfficeWASM32
--with-package-format=emscripten
--with-lang=en-US zh-CN
--with-wasm-module=calc writer impress          # ← 新增（默认是 'calc writer'）
```

空格分隔的多值写法有先例保障：`--with-lang=en-US zh-CN` 就是这么写的，已两次全程验证。

**不需要新的源码补丁。** 四个既有补丁逐一核对：

| 补丁 | 开 Impress 后是否受影响 | 依据 |
|---|---|---|
| `EMSCRIPTEN_INTEL_GCC.mk`（导出 FS/callMain） | 不受影响 | 与模块无关 |
| `vcl/qt5/QtInstance.cxx`（tooltip CJK） | 不受影响 | 与模块无关 |
| `CustomTarget_emscripten_fs_image.mk`（zh-CN 打包） | **不受影响**，`fs-image-patch.py:10` 的锚点是 `endif # !ENABLE_WASM_STRIP_CHART`，与 `ENABLE_WASM_STRIP_BASIC_DRAW_MATH_IMPRESS` 的块（855-1135 行）不重叠；simpress/sdraw 的 UI 资源会由上游那个 `ifneq` 自动打进来 | 已核对上游文件 |
| `frmpaint.cxx`（页边修订表格锚点，r3） | 不受影响 | 属 sw 模块 |

`ZZZ-aiworkdeck-locale-zh-CN.xcd`（默认 ooLocale）同样不受影响。

### 2.2 构建端要预期的变化

- **模块变多**：新增 `animations` / `sd` / `sdext` / `slideshow` / `starmath`（Impress 与 Math/Draw 是同一个开关，24.2 没有更细的粒度——**开 Impress 就一定同时得到 Draw 与 Math**）。
- **外部库被重新启用**：`configure.ac:4146-4156` 里，`ENABLE_WASM_STRIP_BASIC_DRAW_MATH_IMPRESS` 为真时会把 `test_libcdr/libetonyek/libfreehand/libmspub/libpagemaker/libqxp/libvisio/libzmf` 全部关掉；解除后这 8 个外部 tarball 要下载并编译。`dev-www.libreoffice.org/src` 在大陆 VM 上是通的（RECIPE 已实测），但会拉长构建时间。
- **构建耗时**：r3 在大陆 8C VM 上 make 约 5.8 小时；本次预估 **7-9 小时**（新加坡 32C VM 上 r2 全程 40 分钟，本次预估 60-90 分钟）。emsdk 预埋缓存配方照旧（见记忆 `lowa-engine-r3-margin-patch`）。
- **两阶段 zh-CN 焙入流程不变**（先全量 make → 打 fs-image 补丁 + 拷 ZZZ → 删 fs_image 产物重跑 make）。

### 2.3 产物与分发

- 版本号 `24.2.8-zhcn-r4`；自托管路径 `https://www.aiworkdeck.com/lowa-engine/24.2.8-zhcn-r4/`，**r3 保留不删**。
- `desktop/scripts/fetch-lowa-assets.js` 的 `LOWA_BASE_URL` 默认值切到 r4（CI 用），本地可用环境变量指回 r3 做 A/B。
- **回退路径必须先于上线验证**：把 base url 改回 r3 重新 `fetch-lowa-assets.js` + 跑一遍 lowa-e2e，确认「一行配置回退」真的可行。引擎 URL 跨版本不变的缓存策略（`zetaoffice-server.js` 的 `/lowa/*` ETag/304 复验）意味着换版本后首启会重新下载并重编译 wasm，这是**一次性**的启动变慢，要在发版说明里写清。

### 2.4 Phase 0 验收口径（缺一不可）

1. `probe_modules` 四项全 `true`（swriter/scalc/simpress/sdraw）。
2. 真 pptx 往返：`load_document(probe.pptx)` 成功 → `get_document_text` 之外用新增的 `debug_slide_dump` 读回页数/形状数 → `export_document({name:'x.pptx'})` 非 0 字节 → 再 `load_document` 回来，页数/形状数/各页文字**逐字相等**。这一步是 §7 R3 的量化基线。
3. **既有 lowa-e2e 38 步基线零回归**（Writer/Calc 全套），特别是页边修订（依赖 r3 的 frmpaint 补丁）与中文 tooltip。
4. **中文 UI 复验**：真 Chrome 开 `editor.html?verify=1`，菜单/对话框/悬浮 tooltip 中文；`get_ui_lang` 返回 zh-CN。
5. **体积与内存测量并记账**：`soffice.wasm`/`soffice.data` 的 br 与 raw 体积对比 r3；空白 Writer 文档 boot 后的常驻内存对比 r3。**若 wasm(br) 增幅超过 30%（33.9 MB → 44 MB 以上），暂停并让维护者决策**（首启下载与安装包体积是产品指标）。
   预期增幅偏小的依据：`svx`/`svxcore`/`drawinglayer`/`xmloff` 的绘图层与 `oox` 的 pptx 解析层**已经在包里**（services.rdb 实证），新增的主要是 sd 的 UI/文档外壳 + slideshow + starmath。但这只是推断，必须实测。
6. **PDF 行为复验**：Draw 回来后 `pdf_Portable_Document_Format` 类型会重新注册。确认 `FilePreview.vue` 的 PDF 分支仍走 Chromium 原生渲染（**不要**因为「现在 Draw 能开 PDF 了」就把 PDF 路由到编辑器——Draw 打开 PDF 是逐页转形状，编辑体验与阅读体验都不如原生）。

---

## 三、UNO Impress API 面调研（逐条对照插件 PPT 面）

插件 PPT 面当前是 **14 个 command**（`OfficeEditTools.java` 的 `office_ppt_*` 方法组，调研时点计数）：get_slides / replace_text / format_text / add_slide / delete_slide / add_text_box / move_slide / add_shape / get_slide_details / delete_shape / add_table / table_read / table_set_cell / set_hyperlink。

对照 UNO：

| # | 插件 command | UNO 对应物 | 可行性 |
|---|---|---|---|
| 1 | ppt_get_slides | `XDrawPagesSupplier.getDrawPages()` → 逐页 `XShapes` → `XText.getString()` | 稳。纯读 |
| 2 | ppt_get_slide_details | 同上 + `XShape.getPosition/getSize`（1/100 mm）+ `XNamed.getName()` + `supportsService` 判形状类型 | 稳。桌面端的形状标识用 `XNamed` 的 Name（比插件的 shapeId 更稳定，能自己命名） |
| 3 | ppt_replace_text | `XReplaceable`（Impress 文档实现）或逐 shape 遍历 `XText` 手工替换 | **需 spike**：`XReplaceable` 在 Impress 文档上的实现覆盖不确定（是否含表格单元格内文本）。备选的逐 shape 遍历一定可行，且能覆盖表格 |
| 4 | ppt_format_text | shape 的 `XText.createTextCursorByRange` + `CharHeight/CharWeight/CharFontName/CharColor/CharPosture/CharUnderline` | 稳。与 Writer 的字符格式属性同名同语义，`office_thread.js` 现有字符格式代码可直接复用 |
| 5 | ppt_add_slide | `XDrawPages.insertNewByIndex(i)` + 页属性 `Layout`（short，AutoLayout id） | 稳，且**优于插件**：插件只能追加到末尾再 moveTo（还要 PowerPointApi 1.8），UNO 直接按 index 插入；插件的 title/body 是硬塞文本框，UNO 用 AutoLayout 生成真正的标题/内容**占位符**（导出 pptx 后是规范的 placeholder） |
| 6 | ppt_delete_slide | `XDrawPages.remove(page)` | 稳 |
| 7 | ppt_move_slide | **无直接 API**：`GenericDrawPage.Number` 是 `[readonly]`（已核对 idl），`XDrawPages` 只有 insert/remove | **两条路，需 spike 定夺**：(a) `.uno:MovePageUp/MovePageDown/MovePageFirst/MovePageLast` 派发（四个命令在 `DrawImpressCommands.xcu` 中已核实存在），配合 `XDrawView.setCurrentPage` 选中目标页；(b) `XDrawPageDuplicator.duplicate(page)` 后删原页（会换掉对象标识）。**优先 (a)**——与「删除键必须走 .uno: 派发」（PR#164/166）是同一条经验 |
| 8 | ppt_add_text_box | `doc.createInstance('com.sun.star.drawing.TextShape')` → `page.add(shape)` → `setPosition/setSize` → `getText().setString()` | 稳 |
| 9 | ppt_add_shape | `RectangleShape` / `EllipseShape` / `CustomShape`（三角形等经 `CustomShapeGeometry`） | 稳。矩形/椭圆直接；三角形要写 `CustomShapeGeometry` 属性序列，**需 spike** |
| 10 | ppt_delete_shape | `XShapes.remove(shape)` | 稳 |
| 11 | ppt_add_table | `createInstance('com.sun.star.drawing.TableShape')` → `page.add` → `shape.getPropertyValue('Model')` → `XTable` | 稳。`svx/source/unodraw/tableshape.cxx` 已在包里（`libsvxcorelo.a` 在 services.rdb），只是没有 Impress 文档能承载它 |
| 12 | ppt_table_read | `XTable`（继承 `XCellRange` + `XColumnRowRange`，已核对 idl）→ `getCellByPosition(col,row)` → `XText.getString()` | 稳 |
| 13 | ppt_table_set_cell | 同上 → `setString()` | 稳 |
| 14 | ppt_set_hyperlink | 形状文本内插 `com.sun.star.text.TextField.URL`；或形状级 `OnClick=ClickAction.DOCUMENT` + `Bookmark=url` | **需 spike**：两种语义不同（文本内链接 vs 整形状点击动作），且 pptx 往返保真度需实测。倾向文本内 TextField.URL（与 pptx 的 `a:hlinkClick` 对应） |

**桌面独有、插件做不到的四项：**

| 能力 | UNO | 插件侧现状 |
|---|---|---|
| 备注页（Speaker Notes）读写 | `XPresentationPage.getNotesPage()`（已核对 idl：`com::sun::star::drawing::XDrawPage getNotesPage()`）→ 在备注页上找 `com.sun.star.presentation.NotesShape` → `XText` | 能力矩阵 PPT #13：PowerPoint JS API 1.1-1.10 检索不到，**API 做不了** |
| 可见定位（AI 操作到哪、用户看到哪） | `XDrawView.setCurrentPage(page)` + `XSelectionSupplier.select(shape)` | Office.js 有 `slide.select`，但插件是「用户自己的 PowerPoint 窗口」，产品语义不同 |
| 版式与母版 | 页属性 `Layout`（short）；`XMasterPageTarget.getMasterPage/setMasterPage`；`XMasterPagesSupplier.getMasterPages()` | 能力矩阵 PPT #10 判「低价值不补」（插件侧 `applyLayout` 要 1.8）。桌面端做起来便宜，可做 |
| 图片插入 | `GraphicObjectShape` + `com.sun.star.graphic.GraphicProvider.queryGraphic({URL})`，字节先写 MEMFS（同 `load_document` 的 SimpleFileAccess 路子） | 能力矩阵 PPT #11：`addPicture` 是 **BETA/PREVIEW ONLY**，官方明确禁止生产使用 |

**明确做不了 / 不做的：** 切换动画与对象动画（能力矩阵 PPT #14）。UNO 有动画对象模型（`animations` 模块会随本次重烧进包），但复杂度极高且法律场景无诉求，**不排期**。

### 3.1 编组地雷预判（照搬 zetajs 硬规则）

- **`Layout` 是 short**（已核对 `presentation/DrawPage.idl`：`[property] short Layout;`）→ set 必须走 `shortAny()`，裸 number 会编组成 long 被严格 setter 拒绝且常被 try 吞掉（`VertOrient`/`OutlineLevel` 已踩过）。
- **`Effect`/`Speed`/`ClickAction` 是枚举**，读回可能是裸 short → 比较一律走 `enumEq`/`unoEnumVal`（`ParaAdjust` 已踩过）。
- **`Point`/`Size` 是值结构体**（`new css.awt.Point({X,Y})`），单位 **1/100 mm**；插件面用磅（pt）→ 原语对外统一用**磅**，worker 内换算（1 pt = 35.28 (1/100 mm)），换算函数集中一处。
- **图片字节 sequence<byte> 有符号且只收纯 Array** → `Array.from(new Int8Array(...))`，与 `load_document` 同一条路。
- 服务构造器首参必须是 component context（`css.graphic.GraphicProvider.create(context)`）。

---

## 四、原语集设计

### 4.1 命名论证：`slide_*`

三个候选：

| 候选 | 优点 | 否决/采纳理由 |
|---|---|---|
| `doc_ppt_*` | 前缀是 `doc_`，`ClientCapabilityService.isToolVisible`（:112 `lowaOnly = startsWith("doc_") \|\| startsWith("sheet_")`）**不用改** | **否决**。`doc_*` 在 `ai-doc-bridge.md` 里已经是硬契约：「doc_* 是 Writer 专属，`xModel.getText()` 在 Calc 文档上必然失败」。让 `doc_` 前缀下同时存在 Writer 与 Impress 两套语义，会毁掉这条不变式，也提高模型误调率 |
| `ppt_*` | 直白 | **否决**。与 `PptxTools` 的 `pptx_*`（12 个工具）只差一个字母，模型与人都容易混，而这两条路线恰恰要长期并存并明确分工 |
| **`slide_*`** | 与 `sheet_*` 严格对仗（应用名词单数），三分清晰：`doc_`(Writer) / `sheet_`(Calc) / `slide_`(Impress)；与 `pptx_*` 一眼可分 | **采纳** |

代价是要改 `ClientCapabilityService.java:112` 一行加 `|| toolName.startsWith("slide_")`——与当年加 `sheet_` 完全同款的一行改动。**这一行是硬性的**：漏了它，`slide_*` 会漏进 Office 插件会话与 `none` 会话，变成 30 秒超时的死路径（`ai-doc-bridge.md` 已把这条列为地雷）。

**工具名 = action 名，不做映射**（沿用 `sheet_*` 的口径，`doc_*` 的映射表是历史包袱）。

### 4.2 原语表（20 个）

单位约定：位置尺寸对外一律**磅（pt）**；页码 `slideNumber` **1 起**（与插件面一致，也与用户看到的页码一致）；表格 `row`/`col` **0 起**（与 `doc_table_*`/`office_ppt_table_*` 一致）。
形状定位统一用 `shapeName`（UNO `XNamed` 的 Name）——`slide_get_page` 会给每个未命名形状分配并写回一个稳定名 `__awd_shape_N`，此后所有原语按名定位，避免用会漂移的 index。

| # | 原语 / action | 参数 | 返回 | UNO | 对应插件 command | 期 |
|---|---|---|---|---|---|---|
| 1 | `slide_get_overview` | — | `{slideCount, slides:[{number,name,layout,layoutName,masterName,titleText,shapeCount,hasNotes,hasTable}]}` | `XDrawPagesSupplier` | ppt_get_slides | 1 |
| 2 | `slide_get_page` | `slideNumber` | `{number, width, height, layout, masterName, notesText, shapes:[{name,kind,left,top,width,height,text,isTable,rows,cols}]}` | `XShapes` + `XShape` + `supportsService` | ppt_get_slide_details | 1 |
| 3 | `slide_read_notes` | `slideNumber?`（缺省全篇） | `{notes:[{slideNumber,text}]}` | `XPresentationPage.getNotesPage()` → NotesShape | **桌面独有** | 1 |
| 4 | `slide_write_notes` | `slideNumber`, `text` | `{success, slideNumber}` | 同上 → `XText.setString()` | **桌面独有** | 1 |
| 5 | `slide_goto` | `slideNumber`, `shapeName?` | `{success, slideNumber, selected}` | `XDrawView.setCurrentPage` + `XSelectionSupplier.select` | **桌面独有**（可见定位） | 1 |
| 6 | `slide_set_shape_text` | `slideNumber`, `shapeName`, `text` | `{success, previousText}` | `XText.setString()` | （插件用 replace_text 近似） | 1 |
| 7 | `slide_replace_text` | `searchText`, `replaceText`, `slideNumber?`（缺省全篇）, `all?` | `{replaced, hits:[{slideNumber,shapeName}]}` | 逐 shape + 表格单元格遍历 | ppt_replace_text | 1 |
| 8 | `slide_add_page` | `position?`（1 起，插到第 N 页之后；缺省末尾）, `layout?`, `title?`, `body?` | `{success, slideNumber, layout}` | `insertNewByIndex` + `Layout`(shortAny) + 占位符填字 | ppt_add_slide | 2 |
| 9 | `slide_delete_page` | `slideNumber` | `{success, slideCount}` | `XDrawPages.remove`（剩 1 页时拒绝） | ppt_delete_slide | 2 |
| 10 | `slide_move_page` | `slideNumber`, `toPosition` | `{success, from, to}` | `.uno:MovePage*` 派发 + 页数/顺序双口径复核 | ppt_move_slide | 2 |
| 11 | `slide_set_layout` | `slideNumber`, `layout?`, `masterName?` | `{success, layout, masterName}` | `Layout`(shortAny) + `XMasterPageTarget.setMasterPage` | **桌面独有** | 2 |
| 12 | `slide_add_text_box` | `slideNumber`, `text`, `left?`,`top?`,`width?`,`height?`, `fontSize?`,`bold?`,`color?` | `{success, shapeName}` | `TextShape` | ppt_add_text_box | 2 |
| 13 | `slide_add_shape` | `slideNumber`, `shapeType`(rectangle/ellipse/triangle/line), `left?`,`top?`,`width?`,`height?`, `text?`, `fillColor?` | `{success, shapeName}` | `RectangleShape`/`EllipseShape`/`CustomShape` | ppt_add_shape | 2 |
| 14 | `slide_delete_shape` | `slideNumber`, `shapeName?` \| `matchText?` | `{success, deleted}` | `XShapes.remove` | ppt_delete_shape | 2 |
| 15 | `slide_set_shape_geometry` | `slideNumber`, `shapeName`, `left?`,`top?`,`width?`,`height?` | `{success, before, after}` | `setPosition`/`setSize` | **桌面独有**（AI 逐步调排版） | 2 |
| 16 | `slide_format_text` | `slideNumber`, `shapeName?`, `anchorText?`, `fontName?`,`fontSize?`,`bold?`,`italic?`,`underline?`,`color?`, `alignment?` | `{success, applied}` | `XTextCursor` + Char*/`ParaAdjust` | ppt_format_text | 3 |
| 17 | `slide_add_table` | `slideNumber`, `rows`/`cols` 或 `rowsJson`, `left?`,`top?`,`width?`,`height?` | `{success, shapeName, rows, cols}` | `TableShape` → `Model`(XTable) | ppt_add_table | 3 |
| 18 | `slide_table_read` | `slideNumber`, `shapeName?` | `{shapeName, rows, cols, cells:[[...]], note?}` | `XTable.getCellByPosition` | ppt_table_read | 3 |
| 19 | `slide_table_set_cell` | `slideNumber`, `shapeName?`, `row`, `col`, `text` | `{success, previous}` | 同上 → `setString` | ppt_table_set_cell | 3 |
| 20 | `slide_set_hyperlink` | `slideNumber`, `searchText`, `url` | `{success, shapeName, via}` | `TextField.URL`（备选 `OnClick`+`Bookmark`） | ppt_set_hyperlink | 3 |

可选（不排期，列出以免将来重新调研）：`slide_insert_image`（`GraphicObjectShape`，桌面独有，插件的 `addPicture` 是 preview-only）。若维护者要，落在 Phase 3 尾。

### 4.3 统一守卫与错误口径

worker 侧新增三个解析器，全部集中在一处（对标 `resolveSheet`/`resolveWriterTable`）：

- `isImpressDoc()` — `xModel.supportsService('com.sun.star.presentation.PresentationDocument')`。
- `resolvePage(p)` — 校验文档类型 + `slideNumber` 越界；命中后**顺手 `setCurrentPage`**（拟人：操作在哪页用户看得见，和 `resolveSheet` 切活动表同一口径）。返回 `{page}` 或 `{error}`。
- `resolveShape(page, p)` — 按 `shapeName` / `matchText` 定位；给未命名形状补 `__awd_shape_N`。返回 `{shape}` 或 `{error}`。

失败统一走 `slideFail()`，**同时写 `error` 与 `message` 两个字段**——`handleEditorCommand` 只回传 `result.error`，只写 `message` 的话模型收到 `{"error":"null"}`（`doc_table_*` 已踩过）。

非 Impress 文档的错误文案，对标 `NOT_SPREADSHEET_MSG`：
> 当前打开的不是演示文稿：slide_\* 原语仅对 pptx/ppt/odp 生效。Word 文档请用 doc_\* 原语，表格请用 sheet_\* 原语；要操作演示文稿请先用 doc_open_file 打开它。

---

## 五、架构接线

### 5.1 改动清单（按「四件套」纪律逐项列全）

| 层 | 文件 | 改什么 |
|---|---|---|
| 引擎 | `desktop/lowa-build/autogen.input` | 加 `--with-wasm-module=calc writer impress`（Phase 0） |
| 引擎分发 | `desktop/scripts/fetch-lowa-assets.js` | `LOWA_BASE_URL` 默认切 r4 |
| 后端工具 | **新建** `backend/.../tools/SlideEditTools.java` | 20 个 `@Tool`。**新建而非塞进 `DocumentEditTools`**：后者的 `doc_*`+`sheet_*` 已经 70 个以上且还在长，再加 20 个不利于维护。`ToolRegistry` 构造器注入 `List<AgentToolComponent>` 自动发现（:108/:116/:125），**无需改 ToolRegistry** |
| 后端能力过滤 | `backend/.../ClientCapabilityService.java:112` | `lowaOnly` 加 `\|\| toolName.startsWith("slide_")`。**漏掉即死路径** |
| 后端上下文 | `backend/.../ContextAssemblerService.java`（`# Active Document` 段的 `switch (capability)` 与 `activeDocumentReminder()` 两处） | LOWA 分支目前**不分文档类型**，只点名 `doc_*`（Calc 就已经欠这笔债）。改成按 activeContext 扩展名三分支：docx→doc_\*、xlsx→sheet_\*、pptx→slide_\*。**约束要挂末位**（记忆 `prompt-constraints-need-last-position`） |
| 前端白名单 | `frontend/src/composables/libreofficeExecutorClient.js`（`EDITOR_ACTIONS`，:15 起） | 加 20 个 action + 1 个诊断 `get_doc_kind` |
| worker | `frontend/src/zetaoffice/public/office_thread.js` | 三个 resolver + 20 个 action 实现；`retarget`（:1637）里 Writer 专属两步加类型守卫（见 5.3） |
| 工具中文名 | `frontend/src/utils/toolDisplayNames.js` | 20 个中文名 |
| 宿主 tab 路由 | `frontend/src/pages/project-overview/fileOpenTabs.js:306-319` | `wpsFormats` 加 `pptx/ppt/pptm/potx/odp` |
| 宿主预览回退 | `frontend/src/components/FilePreview.vue:37` | pptx-preview 分支保留（web/h5 与引擎不可用时仍走它），仅在桌面 + 引擎可用时让位给编辑器。`isEditorOpenableFile` 已由 `libreOfficePreferred` 把关，改动很小 |
| 编辑器 UI | `frontend/src/components/LibreOfficeEditor.vue:17/59` | 「审阅」按钮与 `ReviewPanel` 按文档类型隐藏（见 5.4） |

### 5.2 保活池与 Impress 文档共存

`librePool.js` 的池是**文档类型无关**的（key 是 `'left:'+fileId`，值是 `LibreOfficeEditor` 实例），Impress 文档天然能进池，**架构上不需要改**。但有两处必须调：

1. **预热备胎的过继**（`maybeAdoptLibreSpare`）：备胎是 boot 出来的空白 **swriter**，过继后走 `load_document` → `loadComponentFromURL` 换成 Impress 文档并 `retarget`。这条路**理论可行**（retarget 本来就是换 model），但必须真机验证：同一个 Qt 窗口从 Writer view 换成 Impress view 是否稳定。**若不稳定，退化方案是「pptx 不吃备胎」**——`maybeAdoptLibreSpare` 里按扩展名跳过，代价只是 pptx 首开慢一档（回到 r3 之前的体感），不影响正确性。
2. **LRU 权重**：`LIBRE_KEEPALIVE_MAX = 3`（`librePool.js:9`）。Impress 文档（母版 + 主题 + 位图）常驻内存显著高于 docx。建议**给 Impress 实例记双倍权重**（一个 Impress 实例占两个位），即 `Impress×1 + Writer×1` 就触发淘汰。这比简单调小 MAX 更精细，也不伤 Writer 用户的既有体验。具体阈值在 Phase 1 用真实 pptx 测了内存再定。

### 5.3 自动保存与文档类型守卫

- **`export_document` 不用改**：`IMPORT_FILTERS['pptx'] = 'Impress MS PowerPoint 2007 XML'` 已在 `office_thread.js:83`，且该 filter 的注册 `Flags` 是 `IMPORT EXPORT ...`（已核对 `filter/source/config/fragments/filters/impress_MS_PowerPoint_2007_XML.xcu`）——同名双向可用。整文件 multipart 上传链路与 docx 完全一致。
- **`installModifyListener`（`XModifyBroadcaster`）**：Impress 模型同样实现 `XModifiable`，`modified` 事件应能正常触发。**这是自动保存的唯一触发信号，Phase 1 必须专门断言**（改一个形状文字 → 宿主收到 `modified` → 走完保存链路 → 后端字节变了）。
- **`retarget`（:1637）里两步 Writer 专属操作要加类型守卫**：
  - `xModel.setPropertyValue('RecordChanges', true)` — Impress 无此属性，现有 `try{}catch{}` 已兜住，但会白抛异常。
  - `showDeletionsInMargin()` — 内部有 try/catch，但会往 boot-log 里打「ShowChangesInMargin 设置失败」的噪声，在 pptx 场景下是误导性日志。
  两处改成 `if (isWriterDoc())` 前置判定。
- **`installKeyHandler`（`ctrl.addKeyHandler`）**：Impress 的 DrawController 是否实现 `XUserInputInterception` 需 spike；失败已被 `retarget` 里的 `try{}catch{}` 兜住，最坏情况是 key 日志缺失，不影响功能。
- **`docLoadFailed` 空文档覆盖闸（PR#194）照常生效**，语义与格式无关。
- **流式写入（`stream_insert`/`stream_flush`）不接 Impress**：`HOUSE` 是 Writer 的排版语义（首行缩进 2 字符、段后 18 磅），在幻灯片上无意义。`doc_start_stream` 遇到 Impress 文档直接报错，让模型改走 `slide_*`。

### 5.4 审阅面板与「无修订」这件事

Impress **没有 redline**（`RecordChanges` 属性都不存在）。因此：

- `LibreOfficeEditor.vue` 的「审阅」按钮（:17）与 `ReviewPanel`（:59）在 Impress 文档上必须**隐藏**，不能只是点了没反应。
- 判定依据：新增 worker 诊断 action **`get_doc_kind`** → 返回 `{kind: 'writer'|'calc'|'impress'|'unknown'}`；`load_document` 的返回值里也带上 `kind`，宿主一次拿到无需二次往返。
  **顺手还清一笔旧债**：xlsx 文档现在也会显示「审阅」按钮（Calc 同样无 redline），这次一并按 kind 隐藏。
- **安全网 = 文档检查点 + `doc_undo`**，与 `sheet_*` 的口径逐字一致：所有 `slide_*` 写类工具必须打 `@ToolMeta(fileEffect = "MODIFIED")`，编排器（`AgentOrchestrator:~168`）会在本轮首个 MODIFIED 工具前对 activeFileId 建检查点。**工具描述里要对模型明说「PPT 没有修订机制，改动直接生效，误改靠 doc_restore_checkpoint 回滚」**（照抄 `office_ppt_*` 已有的措辞口径）。

### 5.5 IME 覆盖层与键盘路径

`UI_COMMANDS`（`office_thread.js` 里的常量表）里的 `.uno:` 槽是 Writer 语义：`SelectAll` 在 Impress 里是「选中本页全部形状」而不是「选中全部文字」，`GoToStartOfLine` 等只在形状进入文字编辑态时有效。

**Phase 1 不改 UI_COMMANDS**，但要在 lowa-e2e 里单开一组，把「pptx 打开后用覆盖层敲中文/退格/Cmd+Z」跑一遍，把实际行为记录成基线。若发现卡死类问题（修订模式手工删除卡死的同类），再按 kind 分两套 `.uno:` 映射表。**绝对不要退回 DOM 键盘事件路线**（PR#164/166 教训）。

---

## 六、分期实施

| 期 | 内容 | 工作量档位 | 验收口径 |
|---|---|---|---|
| **Phase 0** 引擎具备性 | `autogen.input` 加 `--with-wasm-module`；VM 重烧 r4；自托管上传；`fetch-lowa-assets.js` 切 base；回退路径验证 | **中**（改动 1 行；构建 7-9h 大陆 / 1-1.5h 新加坡；含体积与回归验收）。**这是独立一期，必须先合并并发一个引擎版本，Phase 1 才能开工** | §2.4 六条，缺一不可 |
| **Phase 1** 打开/读取/文本 | 原语 1-7（overview/get_page/read_notes/write_notes/goto/set_shape_text/replace_text）+ `get_doc_kind`；宿主接线全套（tab 路由、FilePreview 让位、审阅按钮按 kind 隐藏、备胎过继验证、LRU 权重）；`SlideEditTools` 新建 + 能力过滤一行 + 上下文分支 + 中文名 | **大**（跨后端/白名单/worker/宿主四层，是把「pptx 能在编辑器里打开并被 AI 改文字」这条链路从零打通） | lowa-e2e **新增组 21**：打开 probe.pptx → overview 页数正确 → 改第 1 页标题 → 备注写入读回 → 触发 autosave → export 回来页数/文字一致；desktop-e2e 增一条 pptx 落盘；既有 38 步零回归 |
| **Phase 2** 页与形状结构 | 原语 8-15（add/delete/move page、set_layout、add_text_box/add_shape/delete_shape/set_shape_geometry） | **中**（单个原语都不复杂，`move_page` 与 `CustomShapeGeometry` 各需一次 spike） | lowa-e2e **新增组 22**：插页到中间位置 → 顺序断言 → 移动页 → 顺序断言 → 加文本框/形状 → get_page 断言 → 删形状 → 断言消失；每步都用「结构计数变化」双口径复核，不信 dispatch 返回值 |
| **Phase 3** 格式与表格 | 原语 16-20（format_text、add_table/table_read/table_set_cell、set_hyperlink） | **中** | lowa-e2e **新增组 23**：建表 → 写格 → 读回二维数组一致 → 文字设字体字号加粗 → get_page 读回格式 → 设超链接 → export/reload 后链接仍在 |

e2e 前置：harness 需要新增探针 `debug_fresh_presentation`（`private:factory/simpress`，对标现有 `debug_fresh_document`），**且必须跟着生产 `retarget` 做同样的类型守卫**，否则断言跑在与生产不同的状态上（`debug_fresh_document` 漏做 `showDeletionsInMargin` 已经踩过一次）。

**每期都要跑 `npm run test:lowa-e2e`（编辑器三件套改动的硬规矩），Phase 1 起还要 `npm run test:desktop-e2e`。**

---

## 七、风险清单

### R1（最高）pptx 经 Impress 往返的保真度

Impress 打开 pptx 是「OOXML → LibreOffice 内部绘图模型 → 再导出 OOXML」的**整篇重写**，不是定点改写。自动保存链路会在用户改一个字后就把整个文件重写一遍。已知高风险丢失/降级项：SmartArt（LO 转成普通形状组）、自定义主题与配色方案、切换与对象动画、嵌入字体、部分图表。docx 的往返在 LO 里已经很成熟，**pptx 的往返成熟度明显低一档**。

缓解：
1. Phase 0 验收里的「往返自检」量化基线（页数/形状数/逐页文字），并把它做成 lowa-e2e 常驻断言。
2. Phase 1 在**首次自动保存前**跑一次运行时往返自检（load→export→再 load 比对），差异超阈值时在编辑器里给用户一条明确提示：「这份 PPT 含引擎不能完整保留的元素（如 SmartArt/动画），在这里编辑保存会丢失它们；建议改用批量编辑（不打开文件直接改）」。
3. 工具描述里对模型写明：**只改数值/文字且要求最高保真的场景，优先 `pptx_*` 批量路线**。

这条风险单独就足以证明 §8 的双轨结论。

### R2（高）内存与体积

两个叠加面：
- **引擎体积**：wasm(br) 33.9 MB / data(br) 16.5 MB 是现状；开 Impress 会同时带进 Draw + Math + slideshow + animations。若超阈值（§2.4 第 5 条 +30%），首启下载与安装包体积都受影响，且 V8 WASM 代码缓存重建（`zetaoffice-server.js` 固定端口 47613 那套优化的收益）会被换版本抵消一次。
- **文档内存**：Impress 文档在 WASM 线性内存里的占用远高于同尺寸 docx（位图解码后常驻）。保活池上限 3 个实例是按 Writer 文档定的，`Impress×3` 有撞上 wasm 4 GB 线性内存上限的现实可能。缓解见 §5.2 的双倍权重方案；Phase 1 必须用真实的律所汇报 PPT（含大量图片）实测，不能用 python-pptx 生成的空壳测。

### R3（中高）引擎重烧的一次性成本与不可逆感

重烧一次 7-9 小时（大陆）/1-1.5 小时（新加坡），且四个既有补丁与 zh-CN 焙入流程都要重跑一遍验证。风险不在「跑不通」（配方已两次全程验证），在**发现回归后的回退成本**。

缓解：r3 与 r4 并存自托管；`LOWA_BASE_URL` 一行切换；**回退路径必须在 r4 上线前先验证一次**（§2.3），不要等出事才第一次尝试。

### 其余（已识别，不进 top3）

- `slide_move_page` 无直接 API，两条路都有代价（见 §3 第 7 行）——按「删除键必须走 .uno:」的既有经验优先派发路线，并用顺序变化双口径复核。
- IME 覆盖层在 Impress 下的键盘语义差异（§5.5）。
- `XReplaceable` 在 Impress 上的覆盖面不确定（§3 第 3 行）——有确定可行的备选（逐 shape 遍历），只是慢一点。
- 三套原语并存后的模型误调率上升——靠 `ContextAssemblerService` 按文档类型三分支的末位提醒压制（§5.1）。

---

## 八、与 PptxTools 的关系：**双轨保留**

结论：**保留 `pptx_*` 批量路线，不废弃**。

论证（三条，按分量排序）：

1. **保真度是两条路线的本质差异，不是实现质量差异。** `pptx_*` 走 python-pptx / 服务端点，**直接改 OOXML，不经 ODF 中转**，改一个单元格数字就只动那一个 `<a:t>`；Impress 桥改一个字会整篇重写（R1）。「只改数值、要求最高保真」这个场景，批量路线在架构上就更优——不是暂时更优。
2. **无引擎环境仍然只有批量路线。** 云后端实例、Office 插件会话、CI 批处理都没有 LOWA 引擎。`pptx_generate`（banana-slides 整篇生成）、`pptx_generate_outline`、`pptx_refine_outline`、`pptx_inspect_format`、`pptx_apply_format` 是这些场景的唯一路径。
3. **生成式能力 Impress 桥永远不覆盖。** `pptx_generate`（整篇 AI 生成）与 `pptx_edit_image`（整页转图片再 AI 重绘）不是「编辑」，是另一条产品线。

**分工口径**（要写进两侧的工具描述，让模型自己选对）：

| 场景 | 走哪条 |
|---|---|
| 文件已在编辑器里打开、要逐步可见地改（用户在看） | `slide_*` |
| 从零生成整篇 PPT / 生成大纲 / 图片再生成 | `pptx_*` |
| 文件在盘上未打开、只改若干数值或文字、要求最高保真 | `pptx_*` |
| 需要读结构再决定怎么改 | 两条都能读；已打开就用 `slide_get_overview`，未打开用 `pptx_inspect_format` |

**双轨的已知冲突点（必须处理）**：`pptx_*` 直接改盘上文件，而编辑器实例还持有旧字节，随后的 autosave 会把旧内容写回去，覆盖掉批量改动。既有机制已经有解：`PptxTools.java:653` 的 `sendReloadFileAction`。要做的是**审计 `pptx_*` 所有写类工具是否都调了它**（目前只在一处见到），漏掉的补上；宿主侧对应的是 `librePool.reloadActiveLibreInstances(fileId)` → `LibreOfficeEditor.reloadFromBackend()`。**不能用 closeFile / 淘汰活动实例代替**——那会 `flushSave` 把旧字节写回，正是要避免的事。

---

## 附录 A：调研探针（一次性，不入库）

探针脚本 `impress-probe.mjs` 与 `probe.pptx` 生成方式（放在 scratchpad，未提交）：

```
# 1. 生成合法两页 pptx（含中文）
python3 -c "
from pptx import Presentation
p=Presentation(); s=p.slides.add_slide(p.slide_layouts[1])
s.shapes.title.text='探针幻灯片一'; s.placeholders[1].text='正文占位符'
s2=p.slides.add_slide(p.slide_layouts[5]); s2.shapes.title.text='第二页'
p.save('probe.pptx')"

# 2. 起 COOP/COEP 静态服务 + 无头 Chrome 引导真引擎，执行 probe_modules 与 load_document
node impress-probe.mjs <distDir> <engineDir> probe.pptx <frontend/node_modules>
```

脚本是 `frontend/tests/lowa-e2e/run.mjs` 的最小裁剪版（同款 COOP/COEP 头 + `.encodings.json` 回放 + `window.__loExecutor` 驱动）。输出见 §1.2 证据 5。

静态取证的可复现命令（无需跑引擎）：

```
# fs 镜像文件清单与模块 UI 目录
python3 -c "import json,collections;m=json.load(open('<engineDir>/soffice.data.js.metadata'));
c=collections.Counter(f['filename'].split('/soffice.cfg/modules/')[1].split('/')[0]
 for f in m['files'] if '/soffice.cfg/modules/' in f['filename']);print(dict(c))"

# services.rdb（从 soffice.data 按 metadata 偏移切片，先 brotli -d）
brotli -d -c <engineDir>/soffice.data > soffice.raw.data
# 取 /instdir/program/services/services.rdb 的 [start,end) 区间，grep uri= / <implementation name=

# wasm RTTI 计数
brotli -d -c <engineDir>/soffice.wasm > soffice.raw.wasm
python3 -c "d=open('soffice.raw.wasm','rb').read()
[print(d.count(k),k) for k in (b'N2sw',b'N2sc',b'N2sd',b'N3oox3ppt')]"
```

## 附录 B：本次核对过的上游文件（`LibreOffice/core` @ `distro/allotropia/zeta-24-2`）

- `configure.ac:2151-2159, 4118-4166` — `--enable-wasm-strip` / `--with-wasm-module` / 四个 strip 开关
- `distro-configs/LibreOfficeWASM32.conf` — 全文仅 5 行，**不含**任何模块裁剪，裁剪来自 configure.ac 的 Emscripten 分支
- `RepositoryModule_host.mk:51-166` — sd/sdext/slideshow/animations/starmath 的门控
- `static/CustomTarget_emscripten_fs_image.mk:855-1135` — simpress/sdraw UI 资源门控
- `postprocess/CustomTarget_registry.mk:69-109, 334-345` — impress/draw/math 的 xcu 与 filter 门控
- `filter/Configuration_filter.mk:567, 683` — fcfg_draw / fcfg_math 门控（含 `pdf_Portable_Document_Format`）
- `filter/source/config/fragments/filters/impress_MS_PowerPoint_2007_XML.xcu` — `Flags: IMPORT EXPORT ...`、`DocumentService: com.sun.star.presentation.PresentationDocument`
- `oox/util/oox.component` — 注册 `com.sun.star.comp.oox.ppt.PowerPointImport`（**在包里**）
- `sd/util/sd.component` — 注册 `com.sun.star.comp.Impress.oox.PowerPointExport`（**不在包里**）
- `offapi/com/sun/star/drawing/XDrawPages.idl` — 只有 `insertNewByIndex` / `remove`，无 move
- `offapi/com/sun/star/drawing/GenericDrawPage.idl` — `Number` 是 `[readonly]`
- `offapi/com/sun/star/presentation/DrawPage.idl` — `Layout` 是 `short`
- `offapi/com/sun/star/presentation/XPresentationPage.idl` — `getNotesPage()`
- `offapi/com/sun/star/table/XTable.idl` — 继承 `XCellRange` + `XColumnRowRange`
- `officecfg/.../Office/UI/DrawImpressCommands.xcu` — `.uno:MovePageUp/Down/First/Last`、`InsertPage`、`DeletePage`、`DuplicatePage`、`RenamePage` 均存在
