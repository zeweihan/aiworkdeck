# AI Workdeck 安全审计报告（2026-08-02）

范围：backend（Java Spring）、frontend（uni-app/Vue3）、desktop（Electron）、
pptx/mineru/kokoro/easyvoice 附属服务、部署配置与依赖。

方法：12 个独立视角并行审计（越权、注入、口令与凭证、SSRF、压缩包与文档解析、
Electron、前端 XSS、AI 工具与提示注入、Python 服务、配置与部署），每条候选发现
再交由独立 agent 做对抗性证伪，只保留能给出完整利用链、置信度 ≥ 8/10 的结论。

结论：**39 项确认高危 + 12 项中低危**。已修复 40 项，2 项需产品决策，
1 项只能由维护者本人处理（密钥轮换）。`mvn test` 664 项全通过。

---

## 一、最紧急：公开仓库的 git 历史里有可用的 API Key

`github.com/zeweihan/aiworkdeck` 是公开仓库（69 star / 11 fork）。以下两个凭证
可以直接从历史里取出：

- Google/Gemini：`AIzaSyDpX0_...RLUCc`
- OpenRouter：`sk-or-v1-c8a0ea74...25c9f`

commit `e2b37cab`(#10) 与 `87da4e33`(#22) 把它们从工作区删掉了，但 **git 历史不受影响**，
`git log -p` 即可还原。11 个 fork 意味着即使重写 origin 历史也收不回来。

**这一项我没有也不应替你处理：必须去两个平台后台吊销并换发新 key。**
在轮换完成前，应视为已泄露。

---

## 二、已修复的确认高危（按性质归类）

### 1. 会话凭证可预测（CRITICAL）
`AuthController.generateSessionId()` 原为 `"session_" + currentTimeMillis() + Math.random()` 的
13 位小数。`Math.random()` 背后是 48 位 LCG：攻击者注册一个账号拿到自己的会话 ID，
即可反解生成器状态，推算其他人的会话 ID（时间戳部分本就可枚举）。注册接口开放，
全站没有 Spring Security，每个控制器都只认这一个 header——等于全站账号接管，
包括 `admin`（拿到后 `/api/admin/config` 明文返回企查查、Tushare、阿里云 OCR、
北大法宝、Bocha、ElevenLabs、OpenRouter、Google 全部密钥）。

已改为 `SecureRandom` 32 字节，去掉时间戳前缀。

### 2. AI 端点缺鉴权（CRITICAL）
- `/api/agent/chat` 未登录即可驱动全套工具，且 `projectId` 由请求体给定
- `/api/ai/history`、会话元数据可匿名按 `conversationId` 读取，而 id 是
  `conv-<毫秒时间戳>`，可枚举
- `/api/agent/connect/{id}` 无归属校验，emitter 表只按 conversationId 索引，
  新连接直接覆盖旧连接——猜到 id 即可劫持他人整条输出流
- `/api/ai/agent/editor-result` 不认人不认会话，拿到 requestId 就能往别人的
  Agent 循环里塞伪造的工具结果
- `/api/agent/ppt/generate` 未登录时顶着写死的 `10001` 号用户往任意项目写文件

### 3. AI 工具越过租户边界（CRITICAL）
`ToolRegistry` 已经把 `projectId/conversationId/userId` 强制改写为服务端上下文，
LLM 伪造不了；但 `fileId` 是普通参数，工具直接 `findById(fileId)` 取库——
模型读的文档正文、网页内容里混入的注入指令，可以驱使它点名别家项目的文件。
新增 `ToolFileGuard`，覆盖 8 处按 ID 取文件的调用点。

`FileTools` 的路径围栏原本是服务端安装根（`user.dir`），而各租户的
`data/projects/{id}` 与 `skills/`、`plugins/` 扫描目录并排在它下面：
前者是跨租户读写卷宗，后者写进去的文本会在下次扫描后进入**所有用户**的系统提示词。
围栏已收敛到当前项目目录，并做 normalize 后的包含校验，无项目上下文时拒绝。

### 4. 提示注入可提权到 SYSTEM 角色（HIGH）
`ContextAssemblerService` 把文档正文塞进 system message 的 CDATA 里。正文中出现
`]]>` 即可提前闭合，其后的文字在模型看来与本服务自己拼的「SYSTEM ENFORCEMENT」
同属 system 角色——一份对方律师发来的 docx 就能以最高信任位下指令。已中和
`]]>` 与文件名属性里的引号/尖括号。

### 5. 存储键由客户端指定（CRITICAL）
`ProjectFileController` 把请求体的 `filePath` 原样落库，可指向他人项目的文件，
再借这条记录下载或覆盖对方文档。现在存储键一律服务端按
`projects/{projectId}/` 生成，服务层再做一次前缀与 `..` 校验。

### 6. 读权限被当写权限用（HIGH）
`ProjectFileController` 的 12 个变更类接口、`FileController` 的上传接口，
全部只校验 `hasReadPermission`——READ_ONLY 成员与 CLIENT 角色可以改名、移动、
删除乃至彻底销毁项目文件，并就地覆盖已签署的合同。已改为 `hasWritePermission`。

### 7. 挂载任意服务器目录（CRITICAL）
`/api/projects/open-local` 接受绝对路径作为项目根。这在**单机桌面版**是功能本意
（"服务器"就是用户自己的电脑），在共享部署里等于任意租户挂载 `/etc`、
他人数据目录或应用自身的 `plugins/`。已加部署模式开关，仅 desktop profile 开放。

### 8. SSRF（CRITICAL / HIGH）
- AI 工具 `browse_url` 完全没有目标校验，可打 `169.254.169.254`（云元数据换实例凭证）、
  内网服务、本机管理端口，且内容原样回给模型与用户
- `BrowserProxyController` 自己写的内网判定漏了 `100.64.0.0/10`，
  阿里云元数据 `100.100.100.200` 正在其中，而该接口无需登录

新增 `SsrfGuard`（按解析后的 IP 判断，覆盖回环/链路本地/私有/ULA/CGNAT/多播），
两处统一使用；`browse_url` 另用 `page.route` 在跳转与子请求上复校，
`BrowserProxyController` 原有的手动跳转循环逐跳复校。

### 9. 前端 XSS（CRITICAL / HIGH）
- `BrowserPane` 的 iframe sandbox 带 `allow-same-origin`，而后端把第三方 HTML
  以本应用同源的形式代理回来——被访问的站点可以读取本应用的会话凭证。已移除该 flag。
- `MarkdownPreview` 用 `markdown-it html:true` + `v-html` 渲染他人上传的 .md
  与模型输出，是跨租户存储型 XSS。已关闭原始 HTML。
- `ChatInterface` 把服务端返回的文件名未转义拼进 innerHTML。已转义。

### 10. Electron 任意本地文件读（HIGH）
`checkba:fs-read-file` 用敏感路径黑名单防护，但黑名单挡不住
`~/.aiworkdeck/local.mv.db`（装着所有租户文档与全部供应商密钥的 H2 库）、
`~/Library`、`~/Documents`。已改为主进程登记制白名单：只有用户自己复制文件时
主进程才登记该路径，读取时按 realpath 比对（顺带堵掉符号链接绕过）。

### 11. 附属服务未鉴权且对外监听（CRITICAL）
`pptx-service` 的 `/api/pptx/format` 直接拿请求体的 `output_path` 写文件，
`/api/settings` 可改写全局 LLM 出网地址。而 `app.run(host='0.0.0.0')`——
**桌面版也一样**，同一局域网（会议室、咖啡厅 WiFi）的任何人都能调。
docker-compose 又把 pptx/mineru/easyvoice 三个端口发布在 `0.0.0.0`。

已改：默认只监听回环（容器内由 `PPTX_BIND_HOST` 放开，但发布地址限制为
`127.0.0.1`）；`output_path` 限制在源文件所在目录内；设置写入需共享口令；
`FLASK_ENV` 固定 production，easyvoice 关 DEBUG。

### 12. 弱默认口令与其他配置（CRITICAL / HIGH）
- `DataInitializer` 首启种 `admin` / `123`，而管理员判定就是用户名等于 `admin`。
  云端部署改为随机一次性强口令（打印到启动日志一次）；桌面 profile 保留原默认
  （单机本地、且全员皆管理员，与向导页提示一致）。
- 首次安装向导未鉴权，可改写 AI baseUrl 与系统提示词。已改为：全新安装可匿名
  提交一次，之后（含管理员 reset 打开的窗口）必须携带管理员会话。
- 后端默认绑 `0.0.0.0`。已改为 `127.0.0.1`，与 `deploy/web/README.md` 既有
  基线一致（团队版由 nginx 反代）。
- 云端连接无归属列，任何登录用户可借他人设备令牌克隆对方云端项目。已加 `userId`
  归属（旧行 fail closed）。
- 客户访问码不可作废，被移出的客户拿旧码再登一次就自己回到项目里。已加
  `revokedAt`，移出客户时自动置位。

### 13. 其余匿名可调接口
`/api/customers/companies`（返回全局尽调标的名单）、`/api/tts/*`、
`/api/external/company/basic`、`/api/files/{id}/upload-status`、
`/api/projects/{id}/doc-links` 均已补登录/成员校验。

---

## 三、需要你决策的两项（未改动）

### 1. Electron `webSecurity: false`
**不能直接打开。** 打包态 `mainWindow.loadFile()` 让 renderer 的 origin 是 `file://`，
前端据此把 API base 指向 `http://localhost:9696`，于是每个后端调用都是
`Origin: null` 的跨域请求；后端 CORS 白名单不含 `null`，开启 webSecurity 后
连登录都会被浏览器拦掉。

有个坑要特别提醒：dev 模式加载的是 `http://localhost:5173`，**在 CORS 白名单里**，
所以 dev/CDP 冒烟会通过，而打包版是坏的。验证必须在打包版上做。

两条可行路径：
- （推荐）用本地 HTTP 服务托管 `frontend/dist`，改用 `loadURL('http://127.0.0.1:<固定端口>/')`，
  origin 就落进现有白名单。代价是 localStorage 换 origin，升级后一次性掉登录与本地设置。
- 用 `session.defaultSession.webRequest.onHeadersReceived` 给本机各服务端口补
  CORS 响应头，只影响主进程，不放松云端后端。

**在这项修好之前，注入到渲染层的脚本仍可用 `fetch('file:///...')` 读任意本地文件，
绕过第 10 项的白名单。** 第 10 项缩小了攻击面，但桌面端并未完全闭合。

### 2. `/api/browser/proxy` 的鉴权
该接口是以 `<iframe src>` 加载的，加不了 `X-Session-Id` header。退回到
`?token=<sessionId>` 反而更糟：注入的 `proxify()` 会把每次页内跳转都重写回这个
接口，会话 ID 会出现在 iframe 自己的 URL 里，被访问站点的脚本用
`location.search` 就能读走，还会随 Referer 外泄——那是直接的会话失窃。

正确做法是服务端签发一个「仅限代理用途、绑定会话、短时效」的票据，需要改
BrowserPane 与新增服务端状态。当前残留风险：一个不需要登录的公网代理
（可被当作匿名转发用），但内网与云元数据地址已经拦死。

---

## 四、关于「扫出 100 多个高危」

事务所 IT 团队的扫描器报的数量，和实际可利用的风险不是一回事。实测：

| 来源 | 数量 | 说明 |
|---|---|---|
| frontend npm audit | 67（14 high） | 几乎全是 uni-app 构建工具链的 devDependencies，不进产物 |
| desktop npm audit | 10（9 high / 1 critical） | tar 那批来自 electron-builder，属构建期 |
| Java 直接依赖（OSV 查询） | 2 | spring-boot 3.2.4、poi-ooxml 5.2.5 各一条 |

合计约 79 条依赖告警——**「100 多个高危」大概率主要来自这里**，而其中绝大多数
不出现在用户手上的产物里。真正该紧张的是本报告第二节那 39 项：那些是自有代码里
可以走完整条利用链的洞，扫描器基本扫不出来（它们不理解「这个 projectId 来自请求体」
或者「这个 fileId 没有和会话项目比对」）。

依赖侧建议（未自动执行，都是破坏性升级）：
- **Electron `^30` 已 EOL**，Chromium 漏洞不再修，而本应用会在浏览器面板里加载
  用户指定的任意网站——这是真实暴露面，不是理论风险。升级到受支持的大版本。
- Spring Boot `3.2.4` → `3.2.14+`（含 CVE-2025-22235）。另注意 Spring Boot 3.x
  整条线 2026-06-30 已 EOL，面向大所采购时会被问到。

---

## 五、其余已知残留

- `SESSION_STORE` 是进程内 Map，**没有过期**。可预测性已修，但被窃取的会话 ID
  永久有效，且重启即全员掉线。要做的是 TTL + 清扫，或换签名令牌——属行为变更，
  留给你定。
- `conversationId` 仍是 `conv-${Date.now()}`。新会话「零消息即无主」的设计是必需的
  （前端先开 SSE 再发第一条消息），残留一个毫秒级的抢占竞态。彻底关闭需要前端
  改用 CSPRNG 生成 id。
- `CloudSyncService.connect` 的 `serverUrl` 未加内网拦截，是**有意为之**：这个功能
  就是连用户自己指定的团队服务器，局域网地址是正常用法。
- 客户访问码是项目级共享的，作废会影响该项目全部客户；按单个客户精确回收在
  当前数据模型下做不到。
- `/api/agent/chat` 用读权限而非写权限把关，以免 READ_ONLY 成员的 AI 面板整个失效。
  代价是只读成员仍可在**本项目内**驱动写类工具。要收紧的话改一行，但会牺牲该角色的
  AI 能力。

---

## 六、验证

- `mvn test`：664 项通过，0 失败（新增会话 ID 熵、SsrfGuard、ToolFileGuard、
  open-local 开关、向导鉴权、upload-status 匿名拒绝等回归用例）
- 未运行：`npm run test:lowa-e2e` / `test:app-e2e`（需要引擎资源与常驻后端）。
  本次改动触及编辑器桥接、文件上传与 Electron IPC，**发版前应跑一遍这两套**。
