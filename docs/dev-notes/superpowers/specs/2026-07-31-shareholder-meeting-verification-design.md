# 股东大会核查功能设计（2026-07-31）

## 背景与目标

律师到上市公司股东会现场后，客户提供投票结果（Excel/Word），律师对照董事会决议与
股东大会通知撰写（或填充会前初稿）法律意见书，并组一套底稿。本功能把该流程产品化：
左栏「股东大会」占位插件（`leftSidebarPlugins.js` key `shareholder-meeting`）填成真功能。

参考内核 skill：`ecm-qc-shareholders-meeting-witness`（QC 审查视角），移植其领域知识
（12 项字段交叉核对、三种特殊表决方式校验、表决复算、巨潮拉取脚本），方向反转为
「撰写 + 核查」。

最终产出：
1. 法律意见书（docx）
2. 一套底稿（会议材料 + 交叉核对底稿表，意见书中每项事实断言可在核对表中溯源）

## 总体形态

面板 + AI 编排。面板管材料与会话，核查执行走 AI 聊天面板标准编排链路（skill 注入），
产出落项目文件树底稿夹。一次核查 = 一个会话（一家公司一次股东会），支持多会话并存。

## 后端

### 实体 ShareholderMeetingCheck（一张表）

projectId、companyName、stockCode、meetingName、meetingDate、
status（DRAFT→READY→RUNNING→DONE）、noticeFileId、resolutionFileId、
voteResultFileIds（JSON 数组）、templateFileId（可选）、otherFileIds（JSON）、
conversationId、workpaperFolderId、createdBy、时间戳。

### ShareholderMeetingController（/api/shareholder-meeting）

鉴权抄 DdController 的 requireMember 三件套（X-Session-Id → projectMemberService）。
接口：会话 CRUD、材料关联（项目已有文件或上传）、巨潮拉取、「开始核查」准备接口
（建底稿夹 + 复制材料 + 组装 kick-off prompt 返回前端）。

### CninfoAnnouncementService

移植内核 skill 的 fetch_cninfo_announcements.py（416 行）：
HttpClient POST `http://www.cninfo.com.cn/new/hisAnnouncement/query`（浏览器 UA）、
org_id 查询（szse_stock.json / gfzr_stock.json）、通知/决议挑选启发式、下载 PDF
落底稿夹并注册文件树。失败降级：提示用户上传；都没有则 prompt 中显式声明未经交叉核对。

### 新 AI 工具 extract_file_text

把 FileController.extractDocumentText（Tika parseToString）上提成 Service 并暴露
@Tool。背景：现有 read_file 对 docx/xlsx 静默返回空串，PDF 仅 OCR（≤20 页），
AI 无法读取 Word/Excel——全局能力补强。需同步前端工具名中文映射表（PR#172 地雷）。

## 底稿夹结构（「开始核查」时创建）

```
股东大会核查/<公司简称>_<届次>/
├── 01-会议通知/          ← 关联材料复制进来（底稿自包含，可整夹交付）
├── 02-董事会决议/
├── 03-投票结果/
├── 04-核查底稿/          ← AI 生成：交叉核对表.docx（12 项字段逐字比对 +
│                            议案三标签 + 表决复算，每项结论注明出处）
└── 05-法律意见书/        ← AI 生成：法律意见书.docx
```

文件写入走 AiDocxExportService 模式（createFile → StorageService.save → 回写 size
+ RAG 增量刷新），不走 FileTools 的直接写盘路线（OSS 模式会失效）。

## 核查执行链路

「开始核查」→ 后端准备接口 → 前端把 kick-off prompt 交给 AI 聊天面板发送
（AGENT 模式）→ skill 触发词命中注入 → AI 执行工作流 → extract_file_text 读材料
→ run_python 复算 → write_docx 产出 → 面板轮询会话 runStatus 显示进度。

关键依据（探查结论）：
- pinnedSkillId 只裁剪工具不注入 prompt（ContextAssemblerService 用 match(userPrompt)
  重新匹配），触发词必须在 prompt 文本里；
- 同一 conversationId 只能有一条 SSE emitter（覆盖式 put），面板不得自拉流；
- ASK 模式跳过 skill 注入，必须 AGENT 模式；
- ChatInterface 目前不 expose sendMessage，需加 expose 的 sendExternalPrompt。

## Skill：backend/skills/shareholder-meeting-verification/

skill.yml：id `shareholder-meeting-verification`，triggers 含「股东大会核查」等；
allowed_tools：extract_file_text、read_file、list_files、search_project_files、
write_docx、run_python、todo_write（逐一核对 ToolRegistry 真名，防白名单静默失效）。

prompt.md 工作流：读材料 → 提取会议要素与议案清单 → 12 项字段交叉核对（通知 vs
决议 vs 投票结果）→ 每条议案打「特别决议/累积投票/中小投资者单独计票」三标签并校验
→ 表决复算（2/3 门槛、回避基数剔除、中小投资者分母）→ 生成核查底稿表 → 生成/填充
法律意见书。

意见书底本：内置模板按金杜样例结构写进 prompt.md（标题 → 致公司 → 引言六段
〔委托依据/审查文件清单/保证段/见证边界/勤勉尽责/公告同意〕→ 一、召集召开程序 →
二、出席人员与召集人资格 → 三、表决程序与表决结果〔逐议案票数 + 中小投资者单独
计票 + 特别决议标注〕→ 结论意见 → 签章页）。用户关联了本所模板/初稿时，AI 读取
并沿用其结构措辞，只填数和核对（会前初稿票数为【】占位是行业常态，填充初稿与
从零生成是同一链路的两种输入）。

硬约束继承自内核 skill：事实可追溯（指不出出处只写【核实】不编数）、见证边界
（不对议案商业合理性发表意见）、无参考文件时显式声明未经交叉核对、规范用语
（「中国台湾省」、适用范围不含港澳台）、不臆断外部事实年份。

## 前端 ShareholderMeetingPanel.vue

照 DdFilesPanel 模式：会话列表 + 新建（公司/代码/届次/日期）→ 会话详情：四个材料
槽位（通知、决议、投票结果[可多]、模板[可选]），每槽位「从项目选择」
（FilePickerDialog，需加 accept 过滤 prop）或上传；「从巨潮拉取」兜底；「开始核查」
+ 状态展示 + 产出文件快捷打开。

接入 project-overview 四步：leftSidebarPlugins.js 已有配置（占位坑）；补 components
注册、面板分发区 v-else-if 分支（插在 PluginPane 动态分支之前）、事件 handler
（开始核查 → ChatInterface.sendExternalPrompt）。props 声明 projectId 用
[String, Number]。

## 验证

1. `cd backend && mvn test`（JDK 21）：新增巨潮挑选启发式、prompt 组装单测；
   不碰 AgentOrchestrator 构造器（EvalHarness 地雷）。
2. 真材料端到端：罗欣药业样例套件（通知 + 决议 PDF）+ 自造交易所格式投票结果
   Excel，dev Electron 走完整链路，对照金杜内核后终稿检查产出结构与数字。
3. `npm run check:emits` + 面板基本旅程。

## MVP 边界（不做）

- zip 一键导出（文件树已支持下载）
- 出席登记表 OCR 专项
- 意见书修订痕迹（Track Changes）输出——那是内核 QC 场景，二期可把内核 skill
  整体作为第二阶段接入
- 不动编排器核心

## 已确认的决策

- 材料复制进底稿夹而非仅引用（底稿自包含）
- 核查执行走聊天面板可见链路而非纯后台静默跑
- extract_file_text 做成全局工具而非私有解析
- 内置模板托底 + 用户模板由 AI 解析灵活运用（非死板占位符填充）
- 参考文件获取：上传为主 + 巨潮兜底
