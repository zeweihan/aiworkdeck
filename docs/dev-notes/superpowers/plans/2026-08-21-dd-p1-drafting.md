# 尽调 P1（模板画像 + 插件宿主 SPI + 尽调 JAR 插件）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 能「学模板 → 入库整理 → 按章节按主体起草 → 每句挂底稿」，其中模板画像、HOUSE 单源、EvidenceLink AI 面、插件宿主 SPI 内置于主仓，尽调流水线以独立 JAR 插件落在私有仓，并用样本项目跑通 6 章黄金对照与全局断言。

**Architecture:** 主仓新增 `backend/plugin-api` Maven 子模块（纯接口）+ `PluginHostImpl`/`PluginJobService`（实现 + 后台任务表）+ `HostAware` 注入；`TemplateTools`（docx4j 读模板 → styleProfile）+ `house-default.json` 单源 + `DocxStyleHelper(profile)` + worker `set_style_profile/apply_style_profile`；`doc_link_evidence/doc_list_evidence` 包装 P0 的 EvidenceLinkService。插件仓 `aiworkdeck-dd-plugin` 提供 `dd_*` 工具（`dd_ingest` 为后台任务）、句式库、表格模板、skill、黄金对照跑分器。

**Tech Stack:** Spring Boot 3 / JPA / docx4j 11.3.2 / langchain4j 0.36.0 @Tool；LibreOffice WASM worker；uni-app Vue3；Maven 多模块；插件仓 Maven（`provided` 依赖 plugin-api）。

**Spec:** `docs/superpowers/specs/2026-08-21-dd-p1-drafting-design.md`（下称 SPEC）；P0 契约见 `docs/superpowers/specs/2026-08-21-evidence-link-p0-design.md` 与 `.claude/agents/ai-doc-bridge.md`「EvidenceLink 契约」。

## Global Constraints

- 全局禁 emoji；commit 尾行 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- `mvn` 用 JDK 21；前端 npm；`docs/` 入库 `git add -f`；改 `office_thread.js` 后 `npm run build:zetaoffice` 再跑 lowa-e2e。
- 新增 doc_*/dd_* 工具四件套：@Tool + EDITOR_ACTIONS（仅需新 action 时）+ `toolDisplayNames.js` + `RealToolBeans`（内置工具）；插件工具不进 RealToolBeans。
- 改 `AgentOrchestrator`/`ToolRegistry` 构造器必须同步 EvalHarness 与测试构造点。
- 行为约束段挂 prompt 末位。
- 样本项目只读、不复制出本机、不入库、不贴看板/PR；跑分器期望文件 `golden/expect.local.json` 与报告 `report.local.md` 在插件仓 `.gitignore`。
- `plugin-api` 只加不破；版本 `1.0.0` 起。
- 插件 JAR 与宿主同 JVM：插件代码不得触碰 `SystemSettingService` 之外的宿主内部类（只用 `com.checkba.plugin.api.*`），`PluginHostImplTest` 用 ArchUnit 风格反射断言插件仓无 `import com.checkba.service`（插件仓自己的测试）。
- 所有 OCR/LLM 调用走宿主 `Text.ocr`/`Llm.complete`（平台 Credits），插件不自带 key。
- 每单元一棵 worktree，同树只跑一个改文件的 agent；后合者 rebase。

## 单元与合并顺序

| 单元 | 看板 | 仓/分支 | 依赖 | 模型档 |
|---|---|---|---|---|
| H 插件宿主 SPI（plugin-api + HostImpl + Jobs + spec v2.4） | 新卡 | 主仓 `feat/plugin-host-api` | P0 单元 A 已合并（Evidence 子接口） | 主模型（契约） |
| I 模板画像 + HOUSE 单源（后端） | 新卡 | 主仓 `feat/style-profile-backend` | 无 | 主模型（docx4j 细节多） |
| J worker 样式原语 + doc_* 工具 + Office 插件 HOUSE | 新卡 | 主仓 `feat/style-profile-worker` | I 的 `house-default.json`；P0 单元 G 已合并（同文件） | 主模型 |
| K doc_link_evidence/doc_list_evidence + `_模板/` UX | 新卡 | 主仓 `feat/doc-link-evidence` | P0 A/B 已合并 | 主模型（契约） |
| L 插件仓骨架 + dd_ingest 流水线 | 新卡 | 插件仓 `main` 首批 | H 发布到 `~/.m2` | 主模型（规则判定多） |
| M 插件起草工具 + 句式库 + 表格模板 + skill | 新卡 | 插件仓 | L、I、K | 主模型 |
| N 黄金对照跑分器 + 样本实跑 + 定稿 | 新卡 | 插件仓 `golden/` | H-M 全部 | **自己做** |

合并顺序：H → I → J → K 可与 H 并行；L 在 H 发布后；M 在 L/I/K 后；N 最后。

## File Structure

**主仓**
- Create `backend/plugin-api/pom.xml` + `src/main/java/com/checkba/plugin/api/{HostAware,PluginHost,ToolCall,Files,Text,Tags,Evidence,Jobs,Docs,Settings,Llm,FileInfo,TagInfo,OcrResult,OcrOptions,LlmOptions,JobHandle,JobStatus,JobBody,JobContext,ConflictPolicy,LinkView,TargetInput,HostQuotaException}.java`
- Modify `backend/pom.xml`（依赖 plugin-api）、根 `pom.xml`（若主仓是单模块：把 backend 改成聚合，或把 plugin-api 作为 backend 的子目录模块——先看 `backend/pom.xml` 有无 `<modules>`；没有则新建根聚合 `backend/pom.xml` 保持不动、plugin-api 独立 `mvn install` 到 `~/.m2`，backend 以普通依赖引用。**选后者**，最小改动）
- Create `backend/src/main/java/com/checkba/service/plugin/{PluginHostImpl,PluginHostFactory,PluginJobService,PluginHostQuota}.java`、`model/entity/PluginJob.java`、`repository/PluginJobRepository.java`、`controller/PluginJobController.java`
- Modify `service/ai/PluginService.java` `loadJar`（HostAware 注入）、`ToolRegistry`（插件工具调用前 `PluginHostFactory.bindCall(ctx)`）
- Modify `docs/PLUGIN_SPEC.md`（v2.4）、`examples/hello-plugin`（HostAware 示例 + pom provided plugin-api）
- Create `backend/src/main/resources/style-profiles/house-default.json`
- Create `backend/src/main/java/com/checkba/util/style/{StyleProfile,StyleProfiles,DocxProfileReader,Units}.java`、`service/ai/tools/TemplateTools.java`
- Modify `util/DocxStyleHelper.java`、`service/ai/tools/FileTools.java`（write_docx 参数）、`service/ai/AiDocxExportService.java`
- Modify `frontend/src/zetaoffice/public/office_thread.js`、`frontend/src/zetaoffice/libreofficeExecutorClient.js`（EDITOR_ACTIONS）、`office-addin/taskpane/lib/officeExecutor.js`（HOUSE → 构建时内联 JSON）、`office-addin` 构建脚本
- Modify `service/ai/tools/DocumentEditTools.java`（doc_apply_style_profile、doc_insert_toc、doc_set_page_setup、doc_format_selection/doc_format_table 加参、doc_link_evidence、doc_list_evidence）、`service/ai/EditorBridgeService.java`（open 后追发 set_style_profile）、`service/ai/ContextAssemblerService.java`（两句提示）
- Modify `frontend/src/components/project-home/ProjectHomePane.vue`（「已学习」一行）、`frontend/src/components/FileTree.vue`（`_模板/` 图标）
- Tests：`backend/src/test/java/com/checkba/service/plugin/*`、`util/style/*`、`service/ai/tools/TemplateToolsTest`、`DocumentEditToolsEvidenceTest`、`HouseProfileParityTest`；`frontend/tests/evidence/houseProfile.test.mjs`；lowa-e2e 新组「样式画像」

**插件仓 `aiworkdeck-dd-plugin`**
- `manifest.json`、`pom.xml`、`README.md`、`.gitignore`
- `src/main/java/com/aiworkdeck/dd/{DdTools,HostHolder}.java`、`ingest/{IngestJob,Scanner,DocketNo,Dedupe,StateWords,VersionChain,Classifier,PartyTagger,IngestResult}.java`、`draft/{ChapterMaterials,TableBuilder,Phrasebook,GapCandidates,DocketIndex}.java`
- `src/main/resources/dd/{chapters.yml,phrasebook.yml,table-templates.yml}`
- `skills/due-diligence/{skill.yml,prompt.md}`
- `golden/{run.sh,GoldenRunner.java 或 run.mjs,expect.example.json}`（`expect.local.json`、`report.local.md` 忽略）
- `src/test/java/...`（向量测试）

---

# 单元 H：插件宿主 SPI

### Task H1: `plugin-api` 模块（纯接口）

**Files:** 上表 plugin-api 全部；`examples/hello-plugin/pom.xml`

**Interfaces（Produces，逐字）：**
```java
package com.checkba.plugin.api;
public interface HostAware { void setHost(PluginHost host); }
public interface PluginHost { String pluginId(); ToolCall call(); Files files(); Text text(); Tags tags(); Evidence evidence(); Jobs jobs(); Docs docs(); Settings settings(); Llm llm(); }
public record ToolCall(Long projectId, String conversationId, Long userId, String modelId) {}
public enum ConflictPolicy { FAIL, RENAME }
public record FileInfo(long id, String name, Long parentId, boolean folder, String fileType, long size, String path, String sha256, String metaJson) {}
public record TagInfo(long id, String name, String type, String color) {}
public record OcrOptions(boolean blocks, String language) { public static OcrOptions text() { return new OcrOptions(false, "zh"); } }
public record OcrResult(String text, java.util.List<OcrBlock> blocks) {}
public record OcrBlock(String text, int page, double x, double y, double w, double h) {}
public record LlmOptions(String modelId, double temperature, int maxTokens) { public static LlmOptions cheap() { return new LlmOptions(null, 0.0, 2048); } }
public record TargetInput(Long fileId, String locatorJson, String relation, String method, Short confidence, String note) {}
public record LinkView(long id, String linkKey, long docFileId, String anchorText, String sectionPath, String sectionTitle, String status, java.util.List<TargetView> targets) {}
public record TargetView(long id, long fileId, String fileName, String locatorJson, String relation, String method) {}
public interface JobContext { void progress(long done, long total, String message); void checkCancelled() throws InterruptedException; ToolCall call(); void result(String resultJson); }
@FunctionalInterface public interface JobBody { void run(JobContext ctx) throws Exception; }
public record JobHandle(String jobId) {}
public record JobStatus(String jobId, String kind, String title, String status, long done, long total, String message, String resultJson, String error) {}
public class HostQuotaException extends RuntimeException { public HostQuotaException(String m) { super(m); } }
public interface Files { /* 见 SPEC §2.2，方法签名逐字 */ }
public interface Text { String extract(long projectId, long fileId, int maxChars); OcrResult ocr(long projectId, long fileId, OcrOptions o); java.util.List<String> pdfPageTexts(long projectId, long fileId, int fromPage, int toPage); }
public interface Tags { TagInfo getOrCreate(long projectId, String name, String type); void tagFile(long projectId, long fileId, long tagId); java.util.List<TagInfo> tagsOf(long projectId, long fileId); }
public interface Evidence { LinkView create(long projectId, long docFileId, String linkKey, String anchorText, String sectionPath, String sectionTitle, java.util.List<TargetInput> targets); LinkView addTargets(long projectId, String linkKey, java.util.List<TargetInput> targets); java.util.List<LinkView> listByDoc(long projectId, long docFileId); java.util.List<LinkView> listByFile(long projectId, long fileId); }
public interface Jobs { JobHandle start(String kind, String title, JobBody body); JobStatus status(String jobId); void cancel(String jobId); }
public interface Docs { String exec(String action, java.util.Map<String,Object> params); void refreshFiles(); void openFile(long fileId, java.util.Map<String,Object> locator); }
public interface Settings { String get(String key); void set(String key, String value); String projectStyleProfileJson(long projectId); }
public interface Llm { String complete(String systemPrompt, String userPrompt, LlmOptions o); }
```
- [ ] 建模块（`groupId com.checkba`, `artifactId plugin-api`, `version 1.0.0`, Java 21, 无第三方依赖）；`mvn -q -f backend/plugin-api/pom.xml install`。
- [ ] `backend/pom.xml` 加依赖 `com.checkba:plugin-api:1.0.0`；`examples/hello-plugin/pom.xml` 加 `provided` 依赖；示例加 `HostAware` 工具 `helloListFiles(projectId)`。
- [ ] 编译 + Commit `feat(plugin-api): 插件宿主 SPI v1.0.0（纯接口）`。

### Task H2: `PluginJobService` + 实体 + REST + SSE

- [ ] 实体 `PluginJob{id(String ULID), pluginId, kind, title, status, done, total, message, resultJson(TEXT), error, projectId, userId, conversationId, createdAt, updatedAt}`；Repository `findByProjectIdOrderByCreatedAtDesc`、`findByStatusIn`。
- [ ] 失败测试 `PluginJobServiceTest`：start → running → progress 写库（节流 500ms）→ done 带 resultJson；cancel → body 的 `checkCancelled` 抛 InterruptedException → status cancelled；每插件并发 2，第 3 个 queued；启动时把库里 running 的标 failed("宿主重启")。
- [ ] 实现：`ThreadPoolExecutor` 每插件一份（`ConcurrentHashMap<pluginId, ExecutorService>` 2 线程）；`JobContext.progress` 节流写库 + 若 conversationId 非空 `editorBridgeService.sendClientAction("plugin_job_progress", {jobId, done, total, message, status})`（看 `sendDualNamedAction` 的单名版本，没有就加 `sendClientAction(name, conversationId, fields)`）。
- [ ] `PluginJobController`：`GET /api/plugin-jobs?projectId=`、`GET /api/plugin-jobs/{id}`、`POST /api/plugin-jobs/{id}/cancel`（登录 + 项目成员）。
- [ ] 前端：`BackgroundTaskIndicator` 消费 `plugin_job_progress`（看它今天吃什么事件，加一种来源；`api.js` 加三封装；`agentClientActions.js` 加分支）。
- [ ] Commit。

### Task H3: `PluginHostImpl` + 注入 + 配额

- [ ] `PluginHostFactory.forPlugin(pluginId)` 返回绑定实例（缓存）；`PluginHostFactory.bindCall(ToolContext)`/`clear()` 由 `ToolRegistry` 在分发插件工具前后调用（与 `ToolContextHolder.set` 同一处）。
- [ ] `PluginHostImpl` 各子接口实现：
  - `Files` → `ProjectFileService`/`ProjectFileRepository`/`StorageServiceFactory`；`createFolderPath` 抽 `ProjectFileService.ensureFolderPath(projectId, userId, segments)` 公共方法（把 `FileTools.move_file` 内联循环与 `MeetingRecordingService.ensureFolder` 收敛到它）；`write` 用 P0 单元 F 的 `ConflictPolicy.RENAME`；`sha256` 算一次写 `metaJson.sha256`+`sha256At(mtime)`。
  - `Text` → `DocumentTextService`（extract）、OCR 走既有 `ocr_*` 工具的服务类（grep `OcrService`/`AliyunOcr`，平台网关那条）、`PdfEditService`/PDFBox 分页文本。
  - `Tags` → `TagService.getOrCreateTag` + `FileTagService`。
  - `Evidence` → `EvidenceLinkService`（createdByKind=`plugin`）。
  - `Docs` → `EditorBridgeService.executeEditorCommand`（action 必须在 `EDITOR_ACTIONS` 且以 `doc_`/`sheet_` 对应的下发名白名单内；无 conversationId 抛 `IllegalStateException("no active conversation")`）。
  - `Settings` → `SystemSettingService` 键 `plugin.<id>.<key>`；`projectStyleProfileJson` 按 SPEC §3.4 解析（I 单元提供 `StyleProfiles.resolveForProject`，H 先留接口调用、I 合并后接通——H 先实现为只查 SystemSetting + house-default）。
  - `Llm` → 平台通道（grep `AiProviderRouter`/`ChatModelFactory` 的「便宜档」调用入口，与 `AutoTaggingService` 同一条）。
  - 每方法先 `requireRead/Write`；`PluginHostQuota` 滑动窗口 60 次/分钟/插件。
- [ ] `PluginService.loadJar`：`if (instance instanceof HostAware h) h.setHost(pluginHostFactory.forPlugin(pluginId));`（PluginService 不能循环依赖 EditorBridgeService——用 `ObjectProvider<PluginHostFactory>` 懒取）。
- [ ] 测试 `PluginHostImplTest`（Mockito）：非成员拒绝；配额第 61 次抛；`Docs.exec` 无会话抛；`Files.write` 走 RENAME。
- [ ] `docs/PLUGIN_SPEC.md` v2.4（§4 HostAware、§11 SPI 表、§10 版本演进）；`.claude/agents/plugin-system.md` 加「宿主 SPI」节。
- [ ] 全量 `mvn -q test`；Commit、PR、auto-merge；`mvn install` plugin-api 到 `~/.m2`。

---

# 单元 I：模板画像与 HOUSE 单源（后端）

### Task I1: `house-default.json` + `StyleProfile` 模型 + 对拍测试

- [ ] 写 `house-default.json`：把 `DocxStyleHelper` 与 `office_thread.js HOUSE` 现值翻成 SPEC §3/样式盘点 §2 schema（正文楷体_GB2312/Arial 12pt 两端对齐 段前 0 段后 18 最小行距 16 首行 2 chars；主标题 16pt 粗居中；Heading 2-6 = 正文 + 粗；表格 Grid 实线 1.5pt 10pt 单元格段前后 0.2 lines 最小行距 12 首行粗居中 垂直居中 数字居右；表后首段段前 18pt）。
- [ ] `StyleProfile`（Jackson 树 + 类型化访问器 `body()`, `heading(level)`, `table()`, `page()`, `headerFooter()`, `toc()`；`Length{value,unit}`、`LineSpacing{rule,value,unit}`、`Font{eastAsia,western,cs,theme}`；`merge(StyleProfile overrides)` 叶子覆盖）；`Units.toTwips(Length, fontSizePt)`、`toHalfPoints`、`charsToFirstLineChars`；`StyleProfiles.houseDefault()`（classpath 缓存）、`StyleProfiles.parse(json)`、`StyleProfiles.resolveForProject(projectId, explicitJson)`（SPEC §3.4；读 `_模板/画像.json` 用 `ProjectFileService` 按名查 + StorageService 读）。
- [ ] 测试：`StyleProfileTest`（解析 house-default；merge；单位换算向量：2 chars@12pt=480 firstLineChars 值 200；16pt atLeast → `spacing line=320 lineRule=atLeast`）。
- [ ] Commit。

### Task I2: `DocxProfileReader` + `docx_inspect_template`

- [ ] fixtures：用 python-docx 或 docx4j 在测试里生成 `template-sample.docx`（Heading 1-3 自动编号 `一、（一）1.`，正文楷体 12pt 段后 18 无首行缩进，一张 3×3 表单元格级边框 + gridCol，目录域 `TOC \o "1-2"`，页脚 `第 PAGE 页`）。脚本 `backend/src/test/resources/fixtures/gen-template-sample.py` 入库，产物入库（小）。
- [ ] 失败测试 `DocxProfileReaderTest`：对 fixture 断言 SPEC §3.1 的样本级字段（字体槽、size、numbering kind/numFmt/lvlText 三级、table.borders.source=cell、gridCol twips、toc levels "1-2"、footer pageNumber.enabled）。
- [ ] 实现 `DocxProfileReader.read(List<InputStream>) -> StyleProfile`：
  - styles：`StyleDefinitionsPart` → 按 `outlineLvl` 或样式名正则（`Heading (\d)`/`标题 (\d)`/`heading (\d)`）定级；`PropertyResolver` 只补缺省，声明值直接读 `Style.getPPr()/getRPr()` 链（basedOn 递归）。
  - 实例统计：遍历 `document.xml` 段落，按 `pStyle`+`numPr`+文本编号正则（`^[一二三四五六七八九十]+、`、`^（[一二三四五六七八九十]+）`、`^\d+\.`）聚合众数；`numbering.kind = numPr 存在 ? auto : (正则命中 ? literal : none)`。
  - numbering：`NumberingDefinitionsPart` → `abstractNum/lvl` 的 `numFmt/lvlText/start/suff/ind`。
  - table：每表 `tblPr/tblBorders` 与 `tcPr/tcBorders` 计数决定 `borders.source`；`tblGrid/gridCol`；首行 `b`/`shd`/`tblHeader`；单元格按内容类型（数字/日期/序号正则）统计 `jc`；`sz` 众数。
  - sectPr：pgSz/pgMar/docGrid；header/footer 文本与 `PAGE/NUMPAGES` 域；TOC `instrText`。
  - 多份：逐字段众数 + `confidence = 众数占比`；`learnedFrom[]`。
- [ ] `TemplateTools.docx_inspect_template(fileIds, options)`（`AgentToolComponent`；`.doc` 返回提示；结果 JSON 顶 `ToolFileGuard.MAX_TOOL_TEXT_CHARS`）；三处注册。
- [ ] 对样本报告定稿 docx 本机跑一次（不入库），核对 SPEC §3.1 断言，结果数字记入报告。
- [ ] Commit。

### Task I3: `DocxStyleHelper(profile)` + `write_docx(styleProfileJson)`

- [ ] 失败测试 `DocxStyleHelperProfileTest`：用样本级 profile（楷体 12 / 无首行 / 段后 18 / 三级自动编号 / 单元格边框 / 列宽 2019+7007 / TOC 1-2 / 页脚页码）渲染一段 markdown（含 3 级标题 + 表格），保存后用 `DocxProfileReader` 回读 → 字段相等；用 `StyleProfiles.houseDefault()` 渲染的结果与改造前 `applyStandardFormat()` 的输出**逐字段一致**（先在改造前把旧输出的 profile 读出来存成 fixture `house-before.json`，这就是 `HouseProfileParityTest`）。
- [ ] 实现：`applyStandardFormat(pkg)` → `applyProfile(pkg, StyleProfiles.houseDefault())`；`applyProfile` 按 SPEC §3.2 补：`NumberingDefinitionsPart`（auto）/ literal 前缀拼接 / `TcPr.tcBorders` / `TblGrid`+`TcW` / 表头 `shd`+`tblHeader` / `SectPr` / `HeaderPart`/`FooterPart` + `FldChar` PAGE/NUMPAGES / TOC 域 + `settings.xml updateFields=true` / `keepNext`。
- [ ] `FileTools.write_docx` 加可选 `styleProfileJson`（`@P` 说明：缺省自动取项目画像）；`AiDocxExportService.export(..., StyleProfile)` 透传；调用点全部编译通过。
- [ ] 全量 `mvn -q test`；Commit、PR、auto-merge。

---

# 单元 J：worker 样式原语 + doc_* 工具 + Office 插件 HOUSE

### Task J1: worker `ACTIVE_PROFILE` + 新 action

- [ ] `office_thread.js`：`const HOUSE_DEFAULT_JSON = <构建时内联 house-default.json>`（vite 插件或 `build:zetaoffice` 前置脚本把 `backend/src/main/resources/style-profiles/house-default.json` 复制到 `frontend/src/zetaoffice/public/house-default.json`，worker `importScripts` 或内联；前端测试 `houseProfile.test.mjs` 断言两份文件 sha256 相同）；`let ACTIVE_PROFILE = parse(HOUSE_DEFAULT_JSON)`；`applyHouseChar/applyHousePara/styleTableStandard/insertStyledTable/stream_insert` 改读 `ACTIVE_PROFILE`（单位换算：chars→按当前字号折 1/100mm；lines→按行距）。
- [ ] 新 action：`set_style_profile {profile}`（校验 schemaVersion，merge 到默认）；`apply_style_profile {scope}`（先改 `ParagraphStyles` 的 `Standard`/`Heading 1..6`、`Table Contents`/`Table Heading` 定义，再按 scope 最小直接格式，保留 `keepWeight`；分批 500 段 + `truncated:false`）；`insert_toc {levels, title}`（`com.sun.star.text.ContentIndex` + `update()`）；`set_page_setup {width,height,margins,orientation}`（页面样式属性，单位 mm）；`edit_header_footer` 加 `pageNumberPattern`（`第 {PAGE} 页 共 {NUMPAGES} 页` → `PageNumber`/`PageCount` 文本域）、`fontName/fontSize`；`format_selection` 加 `fontNameAsian`；`format_table` 加 `borderColor/borderStyle(single|double|dashed)/outsideBorderWidthPt/insideBorderWidthPt/headerFill/repeatHeader/columnWidthsCm`。
- [ ] `libreofficeExecutorClient.js` EDITOR_ACTIONS 加 `set_style_profile/apply_style_profile/insert_toc/set_page_setup`。
- [ ] lowa-e2e 新组「样式画像」：`set_style_profile`（楷体 12 / 无首行 / 段后 18）→ 插表 → `get_formatting` 首段/表格首格对拍；`apply_style_profile` 后 Heading 1 段 `get_formatting` 字号 12 且粗；`insert_toc` 后 `get_outline` 前出现目录；`edit_header_footer` 页码域存在（`debug_*` 注入读页脚文本含数字）。
- [ ] build + e2e 全绿；Commit。

### Task J2: doc_* 工具与 open 后追发

- [ ] `DocumentEditTools`：`doc_apply_style_profile(scope)`（后端解析项目画像后下发 `set_style_profile` + `apply_style_profile`）、`doc_insert_toc(levels,title)`、`doc_set_page_setup(...)`、`doc_format_selection` 加 `fontNameAsian`、`doc_format_table` 加新参、`doc_edit_header_footer` 加 `pageNumberPattern/fontName/fontSize`；`toolDisplayNames.js` 三个新名；`RealToolBeans` 不变（同类）。
- [ ] `EditorBridgeService.sendOpenFileAction` 成功后追发 `set_style_profile`（`StyleProfiles.resolveForProject`，非 HOUSE 时才发）。
- [ ] `ContextAssemblerService` docx 分支加：「项目有模板画像时用 doc_apply_style_profile / write_docx 自动套用」。
- [ ] 单测：`DocumentEditToolsStyleTest`（参数映射）；`ParagraphIndexBaseTest` 仍绿。
- [ ] Commit。

### Task J3: Office 插件 HOUSE → JSON

- [ ] `office-addin/taskpane/lib/officeExecutor.js` `HOUSE` 改为从 `house-default.json` 构建时内联（office-addin 构建脚本复制同一份资源）；`office_apply_standard_format` 读它；`office-addin` 测试加 sha256 一致断言。
- [ ] `.claude/agents/ai-doc-bridge.md`「改标准格式规范要改三处」改成「改 house-default.json 一处，三处对拍测试钉住」；`doc-editor.md` 加新 action。
- [ ] Commit、rebase（含 P0 G）、PR、auto-merge。

---

# 单元 K：EvidenceLink AI 面 + `_模板/` UX

### Task K1: `doc_link_evidence` / `doc_list_evidence`

**Interfaces（Produces）：**
```
doc_link_evidence(anchorQuote?, anchorId?, targetsJson, method?, relation?, note?) -> {linkKey, targetIds[], sectionPath, status:'unverified'}
  targetsJson: [{fileId?|path?, locator?, relation?, method?, note?}]；anchorQuote 与 anchorId 二选一；quote 必须唯一命中
doc_list_evidence(docFileId?, fileId?, sectionPath?, status?) -> {links:[{linkKey, anchorText, sectionPath, status, targets:[{targetId, fileId, fileName, locator, relation, method}]}]}
```
- [ ] 失败测试 `DocumentEditToolsEvidenceTest`：mock `EditorBridgeService.executeEditorCommand` 按 action 返回（`find_text_locations` 1 命中 → `set_selection` → `bookmark_selection` → `set_selection_hyperlink` → `get_bookmark_context`）；断言 `EvidenceLinkService.create` 收到 `createdByKind=ai`、`sectionPath` 来自 context；0/多命中 → 返回 error 文案不建链；`path` → fileId 解析（`FileTools` 的路径索引复用）。
- [ ] 实现（DocumentEditTools 注入 `EvidenceLinkService`——构造器变了，**同步 EvalHarness/测试构造点**）；url 用 `https://checkba-internal.local/open?u=checkba://filelink?k=<key>&projectId=<pid>`；`toolDisplayNames.js` 两名；`RealToolBeans` 不变。
- [ ] `ContextAssemblerService`：「写事实陈述后必须 doc_link_evidence；无底稿写【待补：…】」挂约束末位。
- [ ] Commit。

### Task K2: `_模板/` 识别与概览页一行

- [ ] `FileTree.vue`：名为 `_模板` 的根级文件夹显示模板图标 + 悬浮说明（locale `files.templateFolderHint`）。
- [ ] `ProjectHomePane.vue`：若存在 `_模板/画像.json`，渲染一行「已学习模板：{eastAsia} {size}pt / {headings.length} 级编号 / {table.samples} 类表格」（读文件走既有 `getFileText`/下载接口；失败静默）。
- [ ] `check:emits`、`test:project-home`；Commit、PR、auto-merge。

---

# 单元 L：插件仓骨架 + `dd_ingest`

### Task L1: 骨架

- [ ] `git clone https://github.com/zeweihan/aiworkdeck-dd-plugin`（空仓）；`pom.xml`（`com.aiworkdeck:dd-plugin:0.1.0`，provided `com.checkba:plugin-api:1.0.0` + `dev.langchain4j:langchain4j-core:0.36.0`，shade 不打 provided；JUnit5 + snakeyaml 打进 JAR）；`manifest.json`（id `due-diligence`、name `尽调报告`、permissions `[file_read,file_write,editor,network]`、tools[] 六个、skills `["skills/due-diligence"]`、backendJars `["dd-plugin-0.1.0.jar"]`）；`.gitignore` 含 `golden/*.local.*`、`target/`。
- [ ] `HostHolder`（静态持有 `PluginHost`，`DdTools implements HostAware`）；`DdTools` 先有 `dd_ping` 一个 @Tool 返回 `host.pluginId()`；本地装：`mvn package` → 拷到 `backend/plugins/due-diligence/` → 后端 rescan → 对话里调 `dd_ping` 通。
- [ ] Commit to main。

### Task L2: 流水线各环节（纯函数 + 向量测试）

- [ ] `DocketNo.parse(name)`：向量 `1-1-1 收购人营业执照.pdf → {1,1,1,null}`、`6-1-2-2 …` → 四段、`<GP>营业执照副本20251105.pdf` → null、`9-1 甲自查报告.pdf` → {9,1,null,null}。
- [ ] `StateWords.strip(name)`：`【修订】4 关于…承诺函（公章，甲签字；一式3份）.docx` → title `关于…承诺函`, states `[修订]`, instructions `公章，甲签字；一式3份`；`xxx(1).pdf` → copyIndex 1；`xxx---副本(1).pdf` → copy；`…-mkckh-mkhzw.docx` → proofreaders `[mkckh, mkhzw]`；`（待补充）` 子夹名 → pending。
- [ ] `VersionChain.order(list)`：向量取样本报告命名规律（`_1116_初稿` < `_1116_初稿_v2` < `_1118_初稿_v3` < `_1119_内核稿` < `_1119_内核稿_v2` < `_1119_内核稿（内核回复1119）` < `_1120_拟定稿` < `_1121_定稿` < `_1121_定稿_1604`；`.doc/.pdf` 成对 pdf 标 exportOf；序号前缀 `1-2`→`2-1` 改名仍同组：按标题主干分组）。
- [ ] `Dedupe.group(files)`：字节相同 → 同组；同主干同扩展名大小差 <1% → 同组（近似）；图像同主干尺寸 4 倍 → 同组（需 `OcrResult` 之前先用尺寸，P1 只按文件大小比例 2-6 倍 + 同主干）；实体 = 编号最小者，其余 `dupOf`。
- [ ] `Classifier.chapterOf(path, name, firstText)`：章节夹 `^(\d+)-` 直接；散件按 `chapters.yml` 关键词表；再不中由 `Llm.complete`（接口注入，测试用假实现）返回 `{chapter, confidence}`。
- [ ] `PartyTagger.partiesIn(name, text, aliases)`：前缀匹配主体简称/别名；四段编号末段映射自然人序。
- [ ] 每个类一个测试文件，全绿；Commit。

### Task L3: `IngestJob` + `dd_ingest` 工具

- [ ] `dd_ingest(projectId, rootFolderId, partiesJson?, reportDocFileId?)` → `host.jobs().start("dd_ingest", "尽调入库整理", ctx -> …)` 返回 `{jobId}`；`dd_ingest_status(jobId)` → JobStatus。
- [ ] Job 体按 SPEC §5.2 八步；每文件 `ctx.progress`；写 `_尽调/入库.json` + `入库报告.md`（`host.files().write(... RENAME)`）；`metaJson` 各字段经 `host.files().setMeta`；PARTY 标签经 `host.tags()`；zip 不解压（有同名目录）；结束 `ctx.result(json)`；`host.docs().refreshFiles()` 若有会话。
- [ ] 本机对样本底稿池 1 的**scratch 副本**建 IDE 本地文件夹项目跑一次，记录：有效文件数、实体数、重复组数、版本链组数、未归类数、PARTY 数（只报数字）。
- [ ] Commit。

---

# 单元 M：起草工具 + 句式库 + 表格模板 + skill

### Task M1: 资源文件

- [ ] `chapters.yml`：收购报告书法律意见书十二章 + 二级标题（按 16 号准则），每章关键词；`phrasebook.yml`：六种句式（SPEC/样本 §2.5）带槽位与 `evidenceKinds`；`table-templates.yml`：主体基本情况 9×2、自然人 2×5、出资结构 N×4+合计、控制企业 N×5、三年财务 9×4（中间表头行）、任职 N×3（repeatHeader），每格 `source` 类型（licenseField/sheetCell/manual）与数字格式规则。
- [ ] 单测：YAML 可解析、槽位闭合、每模板列数与格式规则一致。Commit。

### Task M2: 起草工具

- [ ] `dd_chapter_materials(projectId, chapter)`、`dd_table(projectId, templateId, partyTagId)`（执照字段来自 `host.text().ocr` 的 `blocks` 正则抽 名称/信用代码/法定代表人/注册资本/成立日期/住所；xls 来自 `host.text().extract` 的 sheet 文本按行列解析——P1 只支持 `.xls/.xlsx` 文本抽取能对上「项目/年度」表头的情形，否则格子留 `-` 并进缺口）、`dd_phrase(kind, slotsJson)`、`dd_gap_candidates(projectId)`、`dd_docket_index(projectId, reportDocFileId)`。返回 JSON，`dd_table` 每格带 `source:{fileId, locator}` 供模型随后 `doc_link_evidence`。
- [ ] 单测（假 host）；Commit。

### Task M3: skill

- [ ] `skills/due-diligence/skill.yml`（id、触发词「尽调报告/法律意见书/入库整理/底稿」、`allowedTools` 全列、`enabled_by_default:false`）；`prompt.md` 按 SPEC §5.4（约束段末位：不得编造、每句必挂、找不到写【待补】、表格数字必须来自 source）。
- [ ] 本机装插件 → 广场可见「尽调报告」（付费项元数据由官网 registry 提供，本地用 `ai.plugins.dir` 手放测试）→ 对话触发 skill → 跑一章。
- [ ] Commit、打 tag `v0.1.0`。

---

# 单元 N：黄金对照（自己做）

- [ ] `golden/run.mjs`（或 Java main）：读 `DD_SAMPLE_ROOT`，`rsync` 底稿池 1 到 `$TMPDIR/dd-golden/pool1`，调后端 API 新建本地文件夹项目（`localRoot`），触发 `dd_ingest` 并轮询 `/api/plugin-jobs/{id}`；对 `expect.local.json`（按样本画像 §8 六章手工整理，只在本机）逐条断言；再用对话 API 跑 skill 起草 6 章（真 LLM），`doc_list_evidence` 取链接，`dd_docket_index` 取目录，`docx_inspect_template` 回读出稿；输出 `report.local.md`。
- [ ] 断言清单 = SPEC §5.5；任一不过 → 回到 L/M 修（不改期望去迎合）。
- [ ] 三次跑取稳定项；把**数字**写进看板母卡与记忆；文件名一个不写。
- [ ] 对抗复核（找→反驳→确认）：H（鉴权/配额/任务状态机）、I（docx4j 单位换算）、K（doc_link_evidence 唯一命中）、L（去重误合并）。
- [ ] 「还原病灶即转红」抽查：`Dedupe` 去掉近似分支跑向量；`DocxProfileReader` 把 `借 PropertyResolver 补缺省` 改成全用 resolver 跑 `firstLineChars` 断言；`PluginHostQuota` 上限改 1000 跑配额测试。三处必须转红。
- [ ] 领域文档：`plugin-system.md`（SPI + dd 插件位置）、`ai-doc-bridge.md`（样式画像 + doc_link_evidence）、`doc-editor.md`、`plugin-marketplace.md`（付费插件 due-diligence 上架待 P3）；记忆 `dd-p1-shipped.md`。
