# 桌面登录页重做与应用内对话框统一 设计（2026-09-23）

dev-board：#846（国际站切换 + 登录注册合一）、#847（左栏重设计 + 统一文案体系）、#848（「解锁正式版」文案清扫）、#849（AwdDialog 全局接管）。
维护者已于 2026-09-23 审阅设计稿 v2 并批准（三条复审意见已落入本文）。

## 1. 背景与根因

- 海外用户在桌面端无法用邮箱登录：`backend/src/main/resources/application.yml` 的 `ai.account.sites.intl.enabled` 自 PR#322（2026-08-08）起为 `false`，站点选择器只在可选站点 ≥ 2 时渲染，于是整个入口不存在。链路其余部分早已打通：官网 `POST /api/auth/exchange-key` 接受 `{email, code}` 并 find-or-create；桌面后端 `AccountController.login` / `AccountService.loginWithEmailCode` 已转发邮箱那条。
- 登录与注册本就是同一条链路（官网验证码端点「不存在即注册」，回包 `isNewUser`），页面上却仍有「登录 / 注册」两个页签与「注册后就是正式版」的提示。
- 手机端**没有**大陆/国际切换，它切的是「手机号 / 邮箱」并只打一个后端。桌面账户是官网账户，两站账户库独立、币种与支付通道不同，所以桌面端切的必须是**站点**。
- 登录成功 toast 仍说「正式版已解锁」；官方版自 2026-08-18 起不再有「解锁」概念。
- 系统弹窗（`uni.showModal`）观感差：全仓没有全局 `body` 字体规则、`index.html` 标 `lang="en"`，uni 弹窗挂在 `document.body` 上掉到浏览器默认衬线字；71 处调用里 32 处漏英文 Cancel、24 处连 OK 一起漏。

## 2. 登录页（`frontend/src/pages/unlock/unlock.vue`）

### 2.1 版式（v2 定稿）

- **整页一块底**：暖底渐变 + 左下一团极淡竹月青光晕，左右两栏之间没有可见分界；右侧登录卡悬浮（`--awd-surface`、18px 圆角、两层阴影）。窄窗口（< 1080px）左栏整块不渲染，卡片居中。
- **左栏**（纯展示，无交互）：
  - 眉标 `AI WorkDeck · 社区版` / `AI WorkDeck · Community Edition`
  - 标题（衬线字栈）`让工作，回到一处。` / `Bring your work together.`（「回到一处」「together.」用 `--awd-accent` 着色）
  - lead `为法律人与文档工作者打造的 AI 工作台。文档、沟通、研究，在同一个空间里有序展开。` / `The AI workspace for lawyers and document professionals. Documents, conversations and research, all in one place.`
  - 一条 56px 浅茶金细线（`--awd-gold-line`）
  - 十类工作来源胶囊（与官网 ConvergenceHero 同名同序）：Word 文档 / 邮件 / 微信与沟通（intl：团队沟通）/ 数据表 / 录音笔 / 手机 / 相机 / PDF 材料 / 法规与案例库 / 案件文件夹；每枚胶囊前一个 6px 方点，颜色按来源类别取文档蓝、珊瑚、研究淡紫、暖金、竹月青五色（与官网 hero 的角色配色同族，不是主色）。图标沿用 `config/icons.js` 的 SVG 体系，没有对应图标时只用色点。
  - 小字 `十类工作来源，汇聚到同一张案头` / `Ten kinds of work, gathered onto one desk`
  - 左下角：缔约主体（按站点：北京京微资易科技有限公司 / Zhen Shan Mei Grace Legacy Limited · Hong Kong）+ 版本号 + AGPL-3.0
  - 鼠标视差保留但幅度压到 2° 以内；`prefers-reduced-motion` 时不动。**不再有**假界面线框与 mock-window。
- **右栏登录卡**自上而下：Logo → 站点分段控件 → 标题 → 说明 → 标识符输入 → 验证码 + 发送按钮 → 人机验证挂点 → 主按钮「继续」→ 两项同意 → 分隔线 → 底部一行（左：语言切换；右：`使用账户 Key`，仅 `trialCodeEnabled` 时）。

### 2.2 站点分段控件

- 两段：`中国大陆` / `国际 · International`（英文界面下 `Mainland China` / `International`）。数据源仍是 `GET /api/site` 的 `sites`；`multiSite=false`（私有部署单站）时整个控件不渲染，`pinned=true` 时渲染但禁用并显示当前站。
- 切换调用现有 `POST /api/site/select`。**未登录态不弹确认**：解锁页上本机没有账户连接、权益缓存或平台密钥可清（`accountConnected=false` 且 `mode≠account`）。若 `GET /api/license/status` 报 `accountConnected=true`（罕见：已连账户又回到解锁页），才保留现有确认框。
- 切到 intl：标识符字段换成邮箱、说明文案换成邮箱口径、验证码按钮与错误文案跟随；已输入的手机号 / 邮箱各自保留在自己的字段里。
- 首次启动预选：`site.json` 不存在时按 `getAppLanguage()` / 系统语言判断，非中文 → intl，否则 cn。预选只发生一次，落盘后不再自动改。

### 2.3 登录与注册合一

- 去掉 `unlock-tabs` 里的「登录 / 注册」页签；`mode` 只剩 `'login'`（走 `handleLogin`）与 `'code'`（账户 Key，仅 `trialCodeEnabled` 时通过底部链接进入，进入后用同一张卡展示 textarea + 「返回」链接）。
- 标题 `登录或注册` / `Sign in or create account`；说明 `验证码通过即登录；未注册的手机号会自动创建账户。` / `Enter the code we text you. New numbers get an account automatically.`（intl：`未注册的邮箱会自动创建账户。` / `Enter the code we email you. New addresses get an account automatically.`）。
- 主按钮 `继续` / `Continue`；提交中 `正在登录` / `Signing in`。
- 共创赠金推广位（`promoActive`）保留，改为标题下的一行浅色提示，不再分登录/注册两副文案；2026-10-01 起自动消失（现有逻辑）。
- 成功 toast：`res.isNewUser` → `已创建账户并登录` / `Account created and signed in`；否则 `已登录` / `Signed in`。`mustBindPhone` 弹窗保留。

### 2.4 语言切换

- 底部 `中文 · English`，点击调 `setAppLanguage()` 并整页 reload（切语言必须整页 reload，见 eng-infra J12）。
- 选国际站时，若用户从未手动选过语言（`awd_app_language` 未落盘），自动切到 en-US；用户手动切过就尊重用户。

### 2.5 云端浏览器登录页 `pages/login/login.vue`

只换左栏视觉为同一套（眉标 / 标题 / lead / 十类来源 / 主体行），登录逻辑、4005/TOTP 步骤一行不动。

## 3. 统一文案体系（brand-copy）

- 新建 `design/copy/brand-copy.json`（与 `design/tokens/awd-palette.json` 同一机制）：`brand`、`edition`、`tagline`、`lead`、`sources[10]`、`sourcesCaption`、`valueStatement` 七组 zh/en。`tagline` / `lead` / `sources` 逐字取自官网仓 master（Codex 2026-09-23 定稿，`components/sections/EditionHome.tsx`、`Hero.tsx`、`ConvergenceHero.tsx`）。`valueStatement` 记维护者拍板的核心价值「帮法律人聚焦专业判断」（zh）/「Keep lawyers focused on professional judgment」（en），本轮登录页不展示，供各端后续统一引用。
- 桌面 locale（`onboarding.unlock.brand.*`）的值必须与 JSON 逐字相等，`scripts/check-brand-copy.mjs` 做对拍并进 CI frontend job。
- 官网与手机端对齐另开卡（p/website、p/mobile），不在本轮范围。

## 4. 文案清扫（#848）

| key | zh 现值 | zh 新值 | en 新值 |
|---|---|---|---|
| `loggedIn` | 已登录，正式版已解锁 | 已登录 | Signed in |
| `registered` | 已注册并登录，正式版已解锁 | 已创建账户并登录 | Account created and signed in |
| `registerHintPhone` / `registerHintEmail` | …注册后就是正式版。 | 删除（合一后由 2.3 的说明取代） | 删除 |
| `fullUnlocked` | 正式版已解锁 | 已激活 | Activated |
| `accountAndUnlocked` | 已连接账户，正式版已解锁 | 已连接账户 | Account connected |

另：`services/api.js` 的 `loginAccount` 注释补 `{email, code}` 形状；`desktop/main/main.js:1323` 附近「获取正式版」陈旧注释改掉；`frontend/tests/app-e2e/run.mjs` J1 段的页签断言改成新结构（见 §7）。保留：`workbench.js` 试用票据过渡期 chip、`account.js` 的 `paidEdition/trialEdition`、`UnlockHint.vue` 的 SKU 解锁文案。

## 5. 应用内对话框（#849）

### 5.1 组件 `frontend/src/components/AwdDialog.vue` + 宿主

- 单例宿主挂在 `App.vue` 根部（`<AwdDialogHost/>`），维护一个队列；`frontend/src/utils/dialog.js` 导出 `showDialog(opts)`（Promise）与 `showSheet(opts)`。
- 视觉：宽 440px（窄窗口 `calc(100vw - 32px)`），`--awd-surface`，16px 圆角，`0 24px 64px rgba(35,32,26,.28)` 阴影；标题 17px/600 无衬线左对齐；正文 14px `--awd-text-2` 行高 1.65；按钮靠右，取消为描边、确认为 `--awd-accent` 实底；`danger:true` 时确认按钮 `--awd-danger`，标题左侧一枚 24px 圆形叹号（`--awd-danger-soft` 底 + `--awd-danger` 字），**与标题同行**。遮罩 `--awd-overlay` + `backdrop-filter: blur(3px)`；进场 160ms 缩放淡入，`prefers-reduced-motion` 时无动画。
- 交互：Enter 触发确认、Esc 触发取消（`showCancel:false` 时 Esc 也关）、焦点困在对话框内、打开时把焦点放到取消按钮（危险动作）或确认按钮（普通）。`editable:true` 渲染一个输入框，回包 `content`，与 uni 语义一致。
- 深色主题跟随 `html[data-theme='dark']` 令牌，不硬编码色值。
- 层级：宿主 z-index 10000，高于现有 `.awd-mask`（9999），从结构上解决 `App.vue:17-37` 那段 uni-modal 被自家浮层压住的历史坑。

### 5.2 全局接管（调用点零改动）

- `frontend/src/main.js`（H5 入口）在 `uni` 就绪后把 `uni.showModal` / `uni.showActionSheet` 替换为转发到 `showDialog` / `showSheet` 的实现，回调形状与 uni 完全一致：`success({confirm, cancel, content?})`、`fail`、`complete`、`success({tapIndex})`。
- 默认文案：`confirmText` 缺省取 `t('common.confirm')`，`cancelText` 缺省取 `t('common.cancel')`。`confirmColor` 若等于 `--awd-danger` 的值或任一红色字面量（`#DC3545`、`#B5483C`）映射为 `danger:true`；`DdRequestEditor.vue:444`、`TagManager.vue:229` 两处改为直接传 `danger:true`（仅这两行）。
- 只在 H5 构建下替换（小程序端不存在这个问题，`#ifdef H5`）。
- 71 处调用点不改。

### 5.3 全局字体与语言标签

- `App.vue` 全局样式补 `html, body { font-family: var(--awd-font-sans) }`，令牌值取 `uni.scss` 已定义的 `$awd-font-sans` 字栈并同步进 `:root` 的 `--awd-font-sans` / `--awd-font-serif` / `--awd-font-mono`（三仓色源脚本只管颜色，字体令牌手写，不进 check-palette）。
- `frontend/index.html` 的 `lang` 改为 `zh-CN`，运行时 `setAppLanguage()` 同步 `document.documentElement.lang`。
- `uni.showToast` 仍用原生，`App.vue` 里那段 `uni-toast` 覆盖样式改成同一字体、圆角 10px、`--awd-shadow-md`。
- Electron `dialog.showMessageBoxSync`（app-menu 的重新加载确认）保持原生。主进程 `checkba:ui-confirm`（无人调用）本轮不删，落实记录里点名。

## 6. 后端

- `application.yml`：`ai.account.sites.intl.enabled: true`。`application-desktop.yml` 不另写。
- `SiteController`：`GET /api/site` 回包不变。`POST /api/site/select` 不变。
- 无新端点。

## 7. 测试

- `frontend/tests/app-e2e/run.mjs` J1：页签断言删除；新增断言：`.unlock-site-seg` 存在且两段文案；点第二段后标识符字段 placeholder 变为邮箱口径；`.unlock-btn` 文案 `继续`；页面文本不含 `正式版已解锁`、`获取正式版`、`用账号密码登录`；成功后落到 `pages/project-list/project-list`。
- 新增 `frontend/tests/dialog/awd-dialog.test.mjs`（node 直跑，jsdom 或纯逻辑）：`uni.showModal` 接管后 `success` 回包形状、缺省按钮文案走 i18n、`confirmColor` 红色映射 danger、Esc/Enter 语义。
- `scripts/check-brand-copy.mjs` 对拍进 `check:locales` 同一 CI 步骤。
- `backend`：`SiteProfileServiceTest` 增一条 `multiSite()` 在默认配置下为 true。
- 真渲染走查：`npm run dev:h5` 起页面，puppeteer 截解锁页 cn / intl / en-US 三张与「重启应用」「丢弃」两个弹窗，附在落实记录。

## 8. 不做的事

- 不做「手机号 / 邮箱」方法优先的切换（桌面账户按站点分库，方法与站点一一对应）。
- 不按站点过滤模型清单（双主站红线 1）。
- 不删主进程 `checkba:ui-confirm`。
- 不改 `pages/login/login.vue` 的登录逻辑。
