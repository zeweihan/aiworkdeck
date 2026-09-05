# Office/WPS 插件「项目选择」语义定稿：归档绑定 + 对话/文档双镜像

日期：2026-08-30。dev-board#297（P0）/#298（P1）/#299（P2）。
维护者已拍板的四个决策：绑定模型按影子容器方案；同步会话桌面端只读 + fork-from-here
式「另起分支继续」；文档镜像固定路径覆盖；拿不到文档字节的环境只提示不硬凑。

## 背景与现状

插件连云后端（addin.aiworkdeck.com），下拉里的「我的项目」是云端库的项目，与桌面端
项目是两个世界；桌面项目只以 `remote::<deviceId>::<key>` 只读哨兵出现（跨设备传输入口）。
当前项目选择的全部 effect：云端会话分组、附件仓、AI 项目上下文、懒建「插件临时项目」。
正在编辑的文档只作内联正文随消息走，从不落任何项目；对话历史桌面端不可见。

架构教义（mobile-sync）：桌面端是项目唯一权威源，云端只做目录镜像 + 中转暂存
（ACK 即删 + TTL 兜底）。本设计把插件工作纳入同一教义。

## P0 归档绑定（#297）

- 新实体 `AddinProjectLink`：`(userId, deviceId, projectKey)` 唯一 → `cloudProjectId`。
- 新端点 `POST /api/projects/ensure-addin-link`，body `{deviceId, projectKey, name}`：
  find-or-create link 与影子项目（`projectType:'BLANK'`，名取桌面项目名），
  返回 `{projectId, deviceId, projectKey, name}`。鉴权同 `/api/projects/my`。
- `GET /api/projects/my` 滤掉 link 表指向的影子项目（桌面端 link 表恒空，无副作用）。
- 插件端：`onProjectSelect` 的 `remote::` 分支从「退回显示 + 开传输面板」改为真正绑定：
  调 ensure-addin-link → `projectId = 影子项目 id`，另存
  `awd_addin_archive_{projectId}` = `{deviceId, projectKey, name, deviceName}`（localStorage
  + OfficeRuntime.storage 镜像）；下拉渲染时凡 projectId 有绑定映射，显示值用对应
  remote:: 哨兵（选中态落在远程条目上）。跨设备传输面板入口保留在「+」菜单，
  下拉选远程项目不再打开它。
- chatSession 不感知绑定（projectId 就是真实云项目）；绑定信息仅供 P2 上传时取
  (deviceId, projectKey)。

## P1 对话镜像（#298）

云端半边：
- `ProjectAiMessage` 加列 `sourceChannel`（String(32)，可空）。
- 新实体 `AddinConvSyncOutbox`：`(userId, sourceMessageId)` 唯一；字段
  deviceId/projectKey/conversationId/role/content/displayContent/sourceChannel/
  messageCreatedAt/createdAt。
- 挂点：`ProjectAiMessageService` 消息 insert/upsert 后（saveMessage 与
  upsertAssistantMessage 两处收敛调 `AddinConvSyncService.record`）：项目在 link 表里
  才记 outbox；refresh 语义 = 删旧行插新行（新 id），保证「取件与 ack 之间被更新」
  的行存活。sourceChannel 由 `ClientCapabilityService` 内存态推导
  （office/wps × word/excel/powerpoint → `office-word` 等；取不到给 `office`）。
- 新端点（鉴权同 /api/mobile/*，X-Session-Id 收 awdt_）：
  `GET /api/mobile/conversations/inbox?deviceId=`（限量批次，附会话当前标题）、
  `POST /api/mobile/conversations/ack` body `{ids}`（只删这些 id）。

桌面半边：
- `ProjectAiMessage` 加列 `sourceMessageId`（Long，可空）+ 查重方法。
- `MobileRelayClientService.pollInbox` finally 里追加 `pollConversationSync()`
  （与 pollTransferCommands 同款挂法）：拉件 → projectKey=Long 解析本地项目
  （查不到留置不 ack，同 media 地雷 3）→ 按 sourceMessageId upsert 落
  `project_ai_message`（保留 messageCreatedAt 为 createdAt、role 归一大写、
  content 空白跳过、userId=本地用户、conversationTitle 落会话首条）→ ack。
- 只读 + 分支：会话列表两处（`GET /api/ai/conversations` 与项目级
  `/api/projects/{pid}/conversations`）的 summary 带出 sourceChannel；前端工作台
  历史抽屉与 ConversationList.vue 各加来源角标（「Word 插件」等文案表共享）。
  打开 sourceChannel 非空的会话：输入区禁用 + 「另起分支继续」按钮 →
  `POST /api/ai/conversation/{id}/fork`（复制全部消息为新 conv-<毫秒> 本地会话，
  标题加「（分支）」后缀，新会话 sourceChannel 置空）→ 前端切到新会话。
  fork 端点校验 canUseConversation。

不变式：导入消息 createdAt 必须保留原始时间戳且逐条严格递增（同刻多条时 +1ms
序列化）；content 非空白；role 只认 USER/ASSISTANT。

## P2 文档镜像（#299）

- 云端 `storeMediaTx` 白名单加 `document`；其余（配额 3GB 共池、TTL、ACK 即删）复用。
- 桌面 `landAndAck`：mediaType=document → 根目录「插件文档」、落
  `插件文档/<原始文件名>` 固定路径（无日期层、无 marker）、**原子覆盖**：
  字节先写同目录临时文件再 rename 顶替，然后才动库（文件行已在则更新 fileSize，
  不在则 createFile 传显式 storagePath）。写失败旧文件完好、不 ack、下轮重试；
  覆盖同字节幂等无害。**绝不带日期目录**（覆盖语义的锚点是路径唯一）。
- 插件端采集（仅绑定项目 + 宿主可行时）：轮次 bubble_end 且本轮有成功的写入类
  office_command → `getFileAsync(Office.FileType.Compressed)` 分片读全量 →
  SHA-256 与上次上传比对，变了才 `POST /api/mobile/media`
  （deviceId/projectKey 取绑定，clientMediaId=UUID，fileName=文档名+正确扩展名）。
  失败静默重试下一轮，不打断对话。
- 能力矩阵（官方文档实锤）：Compressed 在桌面版 Win/Mac 三宿主可用；网页版
  Word/Excel 不可用（PPT 网页版可用）；iPad 分片 64KB。manifest 权限
  ReadWriteDocument 已够。**getFileAsync 取到的是内存态还是上次保存态官方无断言**：
  设计上按「镜像可能滞后于未保存编辑」接受，不构成数据安全问题。
- WPS 家族：理论路径 `Document.FullName` + `Application.FileSystem.readAsBinaryString`
  全部待真机验证（代码库零先例）。v1 探测能力，不可用则一次性提示「此环境无法
  归档文档副本」，不上传任何重构产物。
- 「插件临时项目」与普通云项目会话：无绑定，无镜像，行为不变。

## 发布面

云后端部署即生效（outbox/端点/白名单）；桌面拉取半边随桌面端发版；插件端随
静态资源部署即生效。顺序：先合并主仓（两半边同源），云后端与静态资源先上，
桌面端随下个发版带出。
