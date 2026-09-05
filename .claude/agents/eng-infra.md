---
name: eng-infra
description: 工程基建领域。任务涉及构建、发版、CI workflow、签名公证、测试体系（lowa-e2e/app-e2e/desktop-e2e/mvn test）、本地开发启动、Docker 附属服务时，先读本文档再动代码。
---

# 工程基建 领域地图

职责边界：构建/发版/CI/测试/本地开发。各领域自己的业务测试内容见对应领域文档。

## CI（.github/workflows/）

- **ci.yml**（push master + 所有 PR）：三并行 job——backend（temurin **21**，`mvn -B test`）、frontend（node 20，`check:emits` + `check:locales` + `check:nav:full` + `test:project-home` + `test:commands` + `build:h5`）、desktop（仅 `npm ci`）。不打安装包。
- **desktop-build.yml**（发版主 workflow）：触发 = workflow_dispatch / tag `v*` / PR 改动 desktop|backend|frontend 路径。矩阵：tag 或手动 = mac+win；**普通 PR 只跑 windows**（mac runner 1h+）。每平台步骤顺序（**不可乱**，PR#176）：build:h5 → build:zetaoffice → fetch-lowa-assets（LOWA_BASE_URL=自建 zh-CN 引擎 24.2.8-zhcn-r2）→ desktop npm test → mvn package(-Djavacpp.platform) + prepare-backend(jar+jlink JRE) → 四个 Python 服务 prepare-python-service（pptx/mineru/kokoro/asr） → **(mac)sign-mac-natives.sh → 冒烟（backend /api/admin/wizard 120s、pptx alembic+/health、mineru /docs、kokoro /health+voices 验 zf_001、asr /health 验 modelReady:false）→ pack-pysvc** → electron-builder（mac 签名+公证，先抬 maxfiles/ulimit 524288 防 EMFILE；win 未签名 issue #12）→ 失败时 notarytool history/log 打印 Apple 拒因 → upload-artifact（dmg/exe + windows 腿单独产的 patch/*）。**发布收口在独立的 `release` job**（`needs: [build]`，不加 `if: always()`，故 build 任一矩阵腿失败则整个 release 不跑，避免 mac/win 各自往同一 tag 独立发布出半成品 Release，dev-board#74）：下载两平台 artifact 合并后统一跑 softprops/action-gh-release 附 dmg/exe/patch + 双语 body。
- **star-history.yml**（周一 cron）：重画 star SVG 强推 star-history 分支。

## 发版链路

0. **版本规则 0.X.Y**（docs/INCREMENTAL_UPDATE_DESIGN.md）：X=大版本全量安装包；Y=小版本应用内补丁（overlay 机制，组件=backend-app/frontend-h5/zetaoffice-wrapper/pysvc-src）。小版本 tag 触发 CI `patch-gate` job（desktop/scripts/patch-gate.sh）：改壳（desktop/）、pom、LOWA 引擎、requirements.lock 都会被拒——这些只能随大版本走。补丁产物+签名 manifest 由 build-patch-assets.js 在 windows job 生成（私钥=secret UPDATE_SIGNING_KEY，备份 ~/.ssh/aiworkdeck_update_signing.pem；公钥内置 update-service.js，换钥须发大版本）；镜像同步 deploy/update-mirror-sync.sh 在官网 ECS 跑。
1. 版本号**单一来源 `desktop/package.json` version**（backend 拆为 backend/app.jar + backend/lib/，启动 `java -cp "app.jar:lib/*" com.checkba.CheckbaApplication`，见 backend-service.js javaLaunchArgs；frontend version 不参与）。
2. `git tag v<ver> && git push origin v<ver>` → 触发 desktop-build 双平台。auto 模式下 tag 推送不被分支保护拦；可用 Monitor 等 PR 合并后自动打 tag（v0.8.0 配方）。
3. 产物：mac 仅 dmg（**arm64 only**，已放弃 Intel）；win 仅 nsis exe——**单包双架构**（dev-board#341）：主体 x64 全量 + 纯 arm64 Electron 壳（desktop-build.yml 在打包前 `electron-builder --dir --arm64` 产出、剪掉 resources/ 后由 installer.nsh 的 customInstall 在 ARM64 机器上覆盖进安装目录；壳约 260MB 未压缩、装器 +约 106MB）。ARM64 上渲染与 LOWA WASM 原生、JVM/Python 走转译层（javacv/torch 无 windows-arm64 natives，这是为什么不出纯 arm64 包）；x64 机器行为不变；本地构建无壳目录自动降级纯 x64。electron-builder 配置在 desktop/package.json "build" 字段（appId com.aiworkdeck.desktop、extraResources 打入 frontend/dist、backend.jar、jre、python、pysvc.tar.gz+meta、**graphviz、skills（随包内置 skill，v0.11.1 以前漏打）、litviz（诉讼可视化引擎）**；`notarize: true`（**不能写 teamId**：公证走 ASC API Key，@electron/notarize 把 teamId 归为密码凭据、与 API Key 并存即报 "Cannot use password credentials, API key credentials ... at once"，v0.34.0 首次 tag 构建踩过；团队由 issuer 决定=境内主体 8WKHZVR2W8，2026-09-05 起，此前香港主体 X9B97KVA84，dev-board#447）；entitlements desktop/build/entitlements.mac.plist）。**win 侧 `nsis` 字段（2026-08-31 起搜狗式一键 UI，dev-board#339）**：`oneClick:false` 但页面全部换装 `build/win/awd-oneclick-ui.nsh` 引擎——无边框大卡片（立即安装大按钮/协议链接/「自定义安装」展开路径行），点击后主窗收起为桌面右上角小进度卡，完成卡「立即体验」；`allowToChangeInstallationDirectory:false`（目录选择收进卡片，engine 强制追加 `AI WorkDeck` 子目录）；`include: build/installer.nsh` 只做桌面端接线（customWelcomePage/customInstallMode/customFinishPage 三钩子）。引擎设 `ManifestDPIAware`（根治高分屏点阵字）+ `CRCCheck off`（去掉大包启动前 verifying 长进度）；**卸载器仍走 MUI 经典页**，`installerSidebar`/`installerHeader` BMP 只为它保留；**静默安装（/S，自动更新路径）不进 GUI 代码**。改卡片布局必须同步改引擎 AWDUI_* 常量与 `build/win/oneclick-*.html` 的绝对定位（两边同一 96dpi 基准）。
4. 签名抖动：Apple 时间戳抖动 = rerun 即可（连挂两次也 rerun）；公证轮询抖动排查见 ci-macos 记录。
4.1. **macOS 签名与公证的主体 = 境内 Team `8WKHZVR2W8`（北京京微资易），2026-09-05 起（dev-board#447）**。
   两条链路各自的凭据：**签名**仍是 `CSC_LINK`（Developer ID Application 的 .p12 base64）+
   `CSC_KEY_PASSWORD`，`desktop/scripts/sign-mac-natives.sh` 那一步与 electron-builder 各自
   从它导一次；**公证改走 ASC API Key**，三个 secret `APPLE_API_KEY_B64`（.p8 的 base64）/
   `APPLE_API_KEY_ID` / `APPLE_API_ISSUER`——旧的 `APPLE_ID` / `APPLE_APP_SPECIFIC_PASSWORD` /
   `APPLE_TEAM_ID` 已从 workflow 摘除。三个地雷：
   ① electron-builder 认的 `APPLE_API_KEY` 是 **.p8 的文件路径**不是内容，所以 secret 只能存
   base64、在 run 步骤里解码到 `$RUNNER_TEMP/AuthKey.p8` 再 `export`（`env:` 块拼不出 `$RUNNER_TEMP`）；
   ② 它对这三个变量是「全有或全无」——见到任意一个非空就要求三个都在，否则
   `InvalidConfigurationError`，三个全空才静默跳过公证，**fork PR 靠的就是全空这条路**，
   所以解码那段必须包在 `[ -n "$APPLE_API_KEY_B64" ]` 里，`else` 分支还要 `unset` 另两个；
   ③ 它先看 `APPLE_ID`/`APPLE_APP_SPECIFIC_PASSWORD`（option 1）再看 API Key（option 2），
   两套同时给会走旧路——别为了「保险」把旧 env 留着。
   `notarytool history/log` 同样改 `--key/--key-id/--issuer`，**API Key 认证不接 `--team-id`**
   （团队由 issuer 唯一确定），此前那条「APPLE_TEAM_ID secret 会让 history API 报 403」的注释
   只对 Apple ID 认证成立。团队号全仓只剩 `desktop/package.json` 的 `mac.notarize.teamId` 一处。
   换主体对存量 mac 用户无影响：桌面端没有应用内自动更新（`build.publish` 为空、不带
   electron-updater），也不用钥匙串/safeStorage，签名主体变了不会让旧版失效或掉数据。
   **`office-addin/installer/build-installers.mjs` 不共用这套**：它在维护者 Mac 上取钥匙串里
   **第一条** `Developer ID Application` 身份，两个主体的证书都装着时选到哪张不确定（见「已知地雷」）。
4.5. **Office 插件安装器是发版硬步骤（2026-08-19 维护者定，自 v0.21.0 之后的发版起强制）**：
   每次发版都要重建并上架插件的 dmg+exe——在维护者 Mac 上
   `cd office-addin/installer && npm run build:installers`（版本自动取
   desktop/package.json；mac .app 走本机钥匙串 Developer ID Application 签名，给齐
   NOTARY_* 三变量 DMG 自动公证装订，配方见 installer/README.md），产物上传
   addin 服务器 `/opt/aiworkdeck/cloud/web/office-addin/dl/` 并切稳定名软链
   （`AI-WorkDeck-Office-Addin.dmg/.exe`）。安装器只带 manifest，但版本号要与
   桌面端同步，用户侧才对得上号。不做这步 = 发版没发插件端，维护者原话
   「不然用户不会弄」。
   **WPS 加载项（2026-08-28 起）随同一步发**：`cd office-addin && npm run build &&
   npm run build:wps`，把 dist-wps/ 覆盖上传**北京**一台的
   `/opt/aiworkdeck/cloud/web/wps-addin/`（在线模式无安装器概念，覆盖静态目录即
   全量用户生效；壳文件 no-cache 的 nginx 口径见 office-addin/README.md「WPS 加载项」章）。
   **新加坡刻意不铺 WPS 壳**（2026-08-29 实地核对：SG 只有 office-addin/，没有
   wps-addin/）——WPS 是国内市场的产品，国际站不提供；别照 Office 插件那样"两台对齐"。
4.6. **插件云后端 jar 随发版同步部署（`backend/` 在发版区间有任何改动就必须做；
   2026-08-31 v0.29.0 漏发实锤后定为硬步骤）**：addin.aiworkdeck.com 与
   addin.workdeck.ai 两台跑的就是**主仓 backend jar 本体**（application-cloud.yml
   profile），不是 deploy/cloud/ 里另有一套代码——deploy/cloud/ 只是 nginx/systemd/
   env 模板。**判据是 `git diff v<上一版>..v<本版> -- backend/` 非空**，别再只看
   deploy/cloud/ 没变就跳过（v0.29.0 就是这么漏的：插件端换了新版、后端 jar 还是
   旧的，PR#671 的 `POST /api/projects/ensure-addin-link` 端点不存在，插件弹
   「需升级云后端」降级提示）。配方（memory cloud-backend-deployed 同源）：
   `cd backend && rm -f target/backend-*.jar && JAVA_HOME=<jdk21> mvn -B -DskipTests
   -Djavacpp.platform=linux-x86_64 clean package`（瘦包 ~424M，先删旧产物防脏包坑）
   → rsync --partial **串行**传两台 `/opt/aiworkdeck/cloud/backend.jar.new` →
   sha256 对账 → 旧件备份 `backend.jar.rollback-<date>` → mv 换入 →
   `systemctl restart aiworkdeck-cloud` → 冒烟：journal 无 ERROR、新端点返回体
   不再与「不存在的端点」相同（后者恒为 `{"code":1,"message":"服务器内部错误"}` + 200，
   这也是判「接口没上」最快的探法）。表结构靠 `ddl-auto: update` 自动建，无手动迁移。
5. **EN 走查（打 tag 前必过）**：① 以英文语言设置跑 app-e2e 全量（含 J12 英文旅程：切 en-US 断言工作台四列英文锚点 + AI 过程卡工具名无中文，语言键 `awd_app_language`，切语言必须整页 reload）；② 编辑器 boot 用 `?uilang=en-US` 并以 office_thread.js 的 ooLocale 诊断确认 en-US 生效（issue #66 的诊断口径）；③ 人工过一遍英文主界面截图（工作台/设置/AI 面板）。
6. DMG 安装窗口视觉（PR#204）：`build.dmg` 里的 `contents` 坐标是**图标中心、原点在窗口内容区左上角（不含标题栏）**；窗口尺寸由背景图 1x 像素尺寸决定（660x420），所以没写 `window`。背景图 `desktop/build/background.png` + `background@2x.png` 由 electron-builder 自动合成 hidpi TIFF，源文件是 `desktop/build/dmg-background.html`（顶部注释有 headless Chrome 重新生成命令）。改图标落位必须同步改 HTML 里的光晕/箭头位置，否则错位。
6.5. **win 安装器美术管线**（`desktop/scripts/render-win-installer-art.mjs`，安装器 UI 重设计新增）：`build/win/*.html`（美术源文件）→ headless Chrome 截图 → ImageMagick 转 24 位 BMP3 入库为 `installerSidebar.bmp`/`installerHeader.bmp`。**sips 只能出 32 位 BMP，NSIS/MUI2 只认无 alpha 的经典 BMP，必须用 `magick`**——这是个地雷，脚本会校验 BM 头与色深不合格宁可失败也不入库。只有维护者改美术时手动跑一次，产物入库后 CI 与用户构建都不需要 Chrome/ImageMagick。
6.5.1. **一键安装 UI 位图管线**（`desktop/scripts/render-oneclick-art.mjs`，dev-board#339）：`build/win/oneclick-*.html` → Chrome（`--force-device-scale-factor`）→ 24 位 BMP3，zh/en × 100/125/150/200% 共 24 张/产品，**产物不入库**（gitignore `generated/`），构建现场渲染：桌面端由 desktop-build.yml 的「Render one-click installer art」步骤（缺了它 NSIS 编译 File 找不到位图直接失败）、插件端由 build-installers.mjs 调用。三个地雷：makensis 的相对 File 路径按**脚本所在目录**解析（所有 -D 路径给绝对路径）；直接调 makensis 必须 `-INPUTCHARSET UTF8`（脚本内含中文字符串）；BMP 尺寸校验必须是基准×倍率，错一像素热区全歪。视觉回归用 `.github/workflows/installer-ui-smoke.yml`（Windows runner 编译 `build/win/ui-harness.nsi` 测试壳真跑全流程，`desktop/scripts/installer-smoke.ps1` 按基准坐标点击热区逐阶段截图上传 artifact）——没有 Windows 真机时唯一的视觉验证手段。
   **本机 makensis 不能用来预检**（2026-09-01 实测，Darwin 27）：homebrew 的 v3.12 与
   electron-builder 缓存的 v3.04 两个 mac 构建都一样——非 ASCII 源码报 `Bad text encoding`，
   纯 ASCII 脚本走到写产物时 `std::bad_alloc` 崩。**未改动的 HEAD 文件同样复现**，不是谁改坏的，
   别在这上面查半天。改 NSIS 只能靠 installer-ui-smoke。**要出插件安装器 exe 走
   `.github/workflows/addin-installer.yml`**（workflow_dispatch，Windows runner 上跑
   `build-installers.mjs --skip-mac`，按 `desktop/package.json` 的版本号出国内/国际两份，
   分目录上传避免同名覆盖）——v0.31.0 发版实测：引擎修好了本机却出不了包，这条路是补上的。
   mac 那一半（swiftc + Developer ID 签名 + 公证）仍只能在维护者 Mac 上跑 `--skip-win`。
   **两份变体必须分别上架到两台机**：北京 `8.152.169.44` 与新加坡 `8.219.94.204` 各有一份
   `/opt/aiworkdeck/cloud/web/office-addin/dl/`，托管地址焙进包内 manifest，不是通用包；
   0.30.0 那次两台挂的是同一份（字节数一模一样），国际用户装到的包指向 addin.aiworkdeck.com，
   已在 0.31.0 纠正。**核对法：两站 `curl -L .../AI-WorkDeck-Office-Addin.exe` 的字节数应当不同**
   （URL 长度差压缩后约 2 字节），一样就是挂错了。
6.5.2. **磁盘空间闸**（dev-board#350）：引擎新增两个可选契约 `AWD_UI_REQUIRED_KB` /
   `AWD_UI_REQUIRED_EXTRA_KB`，只有设了前者才编译出闸（插件端装的是一份清单，不设）。
   桌面端在 `build/installer.nsh` 里直接把 **electron-builder 的 `-D APP_64_UNPACKED_SIZE`**
   接过来——那是打包时对 win-unpacked 整个目录实测的解包体积（KB），**不许手抄常量**，
   包体每次发版都在长；这个 define 是命令行 `-D` 传的，在生成脚本第一行就存在，和
   `$launchLink`/`StdUtils` 那批「晚于 include 才出现」的东西不是一回事。余量 512MB
   （ARM64 壳 260MB + 解压临时占用 + 不把盘塞到 0）。取不到可用空间时**一律放行**——
   闸设得过严会把装得下的用户挡在门外，比不设闸更糟。反向用例在 CI 里实跑：
   `ui-harness.nsi` 收 `-DREQUIRED_KB`，smoke 工作流多编一个所需空间约 858GB 的
   `harness-nospace.exe`，用 `installer-smoke.ps1 -ExpectBlocked` 断言「弹了提示框 +
   进程没退 + 窗口还是 760 宽的大卡片（开装了会缩成 360×132 的角落进度卡）」。
6.5.3. **`uni-h5` 补丁（`frontend/scripts/patch-uni-h5-scrollview.mjs` + frontend `package.json` 的 `postinstall`，dev-board#349）**：
   uni-h5 的 scroll-view 在 `onMounted` 里排一个 `nextTick` 补写 scrollTop/scrollLeft，
   回调对元素 ref **没有空判**；组件在同一批 flush 里被卸载（`v-if` 分支翻转、路由离开）时
   `main.value` 已是 null，控制台常驻两条同根因的错误（`TypeError: Cannot set properties
   of null (setting 'scrollTop')` + 我们 main.js 全局兜底打出的「未处理的 Promise rejection」）。
   补丁给 `uni-h5.es.js`/`uni-h5.cjs.js` 各加三处真值判断（两条 `_scroll*Changed` 的直写 +
   动画分支 `scrollTo` 的入口），锚点在文件内必须唯一，**对不上就 exit 1 让安装失败**——
   升级 `@dcloudio/uni-h5` 后若报「结构已变」，先搜新版的 `_scrollTopChanged` 看是否已自带
   空判，是就删掉补丁与 postinstall 钩子。产物侧靠 `npm ci` 触发 postinstall（ci.yml 的
   frontend job 与 desktop-build.yml 的 Build frontend 步骤都会跑到）。
   回归护栏在 `tests/app-e2e/run.mjs` 结尾：**控制台异常信号整体不判死**（历史噪音多），
   但 `setting 'scrollTop'` 这一条单拎出来判死。
6.5.4. **NSIS 地雷：`Quit` 在 nsDialogs 回调里不生效**（dev-board#354，CI 实锤）：
   `nsDialogs::Show` 跑的是它自己的消息循环——`while (g_dialog.hwDialog) { GetMessage(...); ... }`，
   **不看 `GetMessage` 的返回值**，只看对话框句柄还在不在。而 NSIS 的 `Quit` 编译成
   `g_quit_flag++` + `PostQuitMessage(0)`，那条 `WM_QUIT` 被这个循环取走后直接丢弃，
   循环照转，安装器关不掉。所以**自定义页的任何 `${NSD_OnClick}` 回调里都不要用 `Quit`**，
   一律 `SendMessage $HWNDPARENT ${WM_COMMAND} <id> 0` 交回 NSIS 页面机
   （1 = 下一步，2 = 取消/退出），由页面机销毁对话框、Show 的循环才会退出。
   引擎里三处按钮都是这个写法。诊断配方（当时靠它一轮 CI 定案，值得复用）：
   同一行相邻热区点一下看 `IsIconic`，证明**点击落到了热区上**；再从外部 `PostMessage`
   一发 `WM_COMMAND`/`IDCANCEL`，证明**退出通道本身是通的**——两条对照一起把结论
   钉死在「关窗动作写错了」而不是「点击没命中」。用例：`installer-smoke.ps1 -CloseOnly`
   （smoke 工作流的「Drive zh harness close button」步骤），是这条路径唯一的覆盖。
6.5.5. **NSIS 地雷：`MUI_PAGE_CUSTOMFUNCTION_SHOW` 会被「路过的页」在编译期抢走**
   （dev-board#356，实机翻车、CI 全绿）：MUI2 的 `Pages.nsh` 里
   `MUI_PAGE_FUNCTION_CUSTOM` 展开成 `Call <fn>` **紧跟一句 `!undef`**——这个 define
   是「谁先展开谁吃掉」的一次性货。一键 UI 原先把安装页的 SHOW 回调
   （`AwdInstFilesShow`，负责把主窗收成 360×132 角落进度卡）挂在 `AWD_UI_PAGE_WELCOME`
   宏里，赌「紧随其后的就是 `MUI_PAGE_INSTFILES`」。ui-harness 里确实如此，
   **桌面端真产物不是**：electron-builder 的 `assistedInstaller.nsh` 页序是
   `customWelcomePage → [licensePage] → PAGE_INSTALL_MODE → [MUI_PAGE_DIRECTORY]
   → customPageAfterChangeDir → MUI_PAGE_INSTFILES`，中间那张「安装模式」页
   （`perMachine=false` 才有）先把 SHOW 吃掉；更阴的是它自己又被我们的
   `customInstallMode`（`$isForceCurrentInstall=1`）在 PRE 里 `Abort` 跳过，
   那句 `Call` 连跑都不会跑。两头都不响 = 安装页塌回 NSIS 原生向导
   （进度条贴顶、原生「上一步」飘在窗口中间、装完停在「已完成」不走完成卡）。
   现在的写法：引擎导出 `AWD_UI_INSTFILES_HOOK`（只 define）与
   `AWD_UI_PAGE_INSTFILES`（钩子 + 页的原子宏）。**自己掌握页序的调用方
   （ui-harness、Office 插件安装器）一律用原子宏**；桌面端页序在 electron-builder
   手里，钩子挂 `customPageAfterChangeDir`——那是唯一紧贴 INSTFILES 之前的钩子，
   **升级 electron-builder 要回头核一眼这个页序还成不成立**。
   回归护栏：`ui-harness.nsi` 的 `-DEB_PAGE_ORDER` 复刻那张「运行期被 Abort 跳过、
   编译期照吃 MUI 定义」的页，smoke 工作流多编一个 `harness-eborder.exe` 实跑；
   同时 `installer-smoke.ps1` 在「点完立即安装」后**真做断言**了（此前只截图：
   窗口宽度必须缩到小卡片、原生「上一步」按钮 IDC 3 必须不可见）——
   老坑之所以能瞒过八个月，就是因为那一步只截图不断言。
   **教训**：`ui-harness` 只覆盖引擎，不覆盖「引擎怎么被真产物接线」；
   凡是与 electron-builder 模板契约有关的改动，用例必须复刻它的页序。
6.5.6. **无边框卡片的拖动：nsDialogs 页靠 STN_CLICKED，instfiles 页只能靠真标题带**
   （dev-board#366）。用户反馈「进度卡不能拖、点不了、像卡死」，两个症状一个根：
   `AwdGuiInit` 把 `WS_CAPTION` 剥掉做无边框，系统从此不给任何可拖的区域，而进度卡的
   位图上本来就一个可点的东西都没有（mini-install 美术只有品牌行、状态行、进度轨道），
   于是「点不了」=「拖不了」。**消息泵不是嫌疑人**：Section 跑在 NSIS 自己开的
   `install_thread` 上（`Source/exehead/Ui.c` 的 `WM_NOTIFY_START` → `CreateThread`），
   UI 线程照常泵消息，1.7GB 解压期间进度条也是这么动起来的；红一轮的日志里
   `SendMessageTimeout(WM_NULL)` 在安装期间 3 秒内有应答，把这条钉死了。
   两条路各自的约束：
   - **欢迎卡/完成卡是 nsDialogs 页**，脚本能跑。背景位图加 `SS_NOTIFY`，static 控件的
     `STN_CLICKED` 是在 `WM_LBUTTONDOWN` 里发的（与 BUTTON 抬起才发 `BN_CLICKED` 不同，
     Wine `static.c` 与 Windows 同款），回调里鼠标还按着，`ReleaseCapture` +
     `SendMessage $HWNDPARENT WM_NCLBUTTONDOWN HTCAPTION 0` 就把这一按交给系统的模态
     拖动循环，抬起才回来。**z 序地雷（第二轮 CI 实锤）**：Win32 子窗口创建时插在
     z 序**最底**（Wine `win32u/window.c`：`insert_after = WS_CHILD ? HWND_BOTTOM : HWND_TOP`，
     注释原话「yes, even if the CBT hook was called with HWND_TOP」），也就是后建的在
     **下面**——引擎里原先那句「创建序在位图之后 = z 序在其上」是反的，此前没露馅只因
     没 `SS_NOTIFY` 的 static 对命中测试是 `HTTRANSPARENT`，点击穿过位图落到下面的热区。
     一加 `SS_NOTIFY` 位图变 `HTCLIENT`，拖动通了、随后「自定义安装」「立即安装」全哑
     （run 33576325194）。所以位图的 `SetWindowPos(HWND_BOTTOM)` 必须放在**所有控件建完
     之后、`nsDialogs::Show` 之前**，建完位图立刻压底是无效的（那时它本来就在底）。
   - **进度卡（instfiles 页）脚本一行都不能跑**：同一套执行引擎、同一个栈，UI 线程若在
     Section 执行期间再进 NSIS 代码就是两条线程并发踩 `$0`-`$9`；nsDialogs 在这一页也没有
     落脚点。所以 `AwdInstFilesShow` 把 `WS_CAPTION` 加回来当拖动带（不带 `WS_SYSMENU`，
     无图标无按钮），Win11 用 `DwmSetWindowAttribute` 35/36/34 把标题带、文字、边框染成
     卡片色（看起来只是卡片顶上多一条空白），Win10 显示系统标题带；外框尺寸用
     `AdjustWindowRectEx` 实算别手抄；`SetWindowPos` 必须带 `SWP_FRAMECHANGED`。
     进完成卡 `AwdFinishCreate` 再剥掉，先 `ClientToScreen` 记下客户区原点再换样式，
     卡片内容不跳。
   - 「自定义安装」展开改 `SWP_NOMOVE`：原先用初始化时记的坐标重定位，卡片能拖之后
     那就成了「一点自定义安装就弹回屏幕中央」。
   **否决过的路**：System 插件的回调是同线程协程（只在 `System::Call` 里等着的那次调用
   能收到），做不了 WndProc 子类化；往 RWX 内存写一段 x86 WndProc 能跑但等于在未签名
   安装器里放 shellcode；自编插件 DLL 要三条 workflow 各加编译步骤。
   回归护栏：`installer-smoke.ps1` 的 `DragAssert` 对三张卡各拖一次（真实鼠标
   `mouse_event` 按下、分步绝对移动、抬起，断言窗口矩形位移 ≥ 指针位移一半、进程活着）、
   `AssertResponsive` 在安装期间发 `WM_NULL`、展开自定义安装后断言左上角没动。
   三轮 CI 同一份测试脚本（f9af58b0 之后 `installer-smoke.ps1` 一字未动）：红
   https://github.com/zeweihan/aiworkdeck/actions/runs/33575900306（三张卡 `moved (0,0)`）、
   半红 https://github.com/zeweihan/aiworkdeck/actions/runs/33576325194（欢迎卡拖动
   `moved (100,40)` 但热区被位图挡住、进不了进度卡——就是上面的 z 序地雷）、
   绿 https://github.com/zeweihan/aiworkdeck/actions/runs/33576754273 。

6.6. **`dmg-builder` 补丁（`desktop/scripts/patch-dmg-builder.js` + package.json `postinstall`，安装器 UI 重设计新增）**：macOS 26.2+ 起 Finder 拒读 dmgbuild 写入 `.DS_Store` 的 `pBBk` 背景书签，导致桌面端主 DMG 背景不显示（electron-builder#9072 / dmgbuild#273，同版 Obsidian/Podman Desktop 同期中招）。`npm ci`/`npm install` 后自动对 `node_modules/dmg-builder/vendor/dmgbuild/core.py` 做定点补丁（跳过 Bookmark 生成，`icvp` 里的 alias 通道保留，老系统照常工作）。**升级 electron-builder 后若补丁脚本报「结构已变」**：先确认新版是否已自带该修复，再决定要不要删掉本补丁，不要盲目跳过。

## 测试命令总表

| 命令 | 目录 | 覆盖 |
|---|---|---|
| `mvn -B test` | backend/ | 51 个 *Test.java：IDOR/鉴权/分片上传/编排/记忆/证据/市场/脱敏 + **回放评测 OrchestratorReplayEvalTest**（resources/ai-eval/cases/ 10 组）+ DesktopContextSmokeTest。**必须 JDK 21**（本机默认 25 SIGBUS） |
| `OPENROUTER_API_KEY=… mvn test -Dtest=RealLlmSmokeTest` | backend/ | 真实 LLM 冒烟（默认跳过） |
| `npm test` | desktop/ | service-manager / model-manager / pysvc-runtime / overlay（补丁覆盖层）/ update-service（验签/下载/激活/回滚，本地 HTTP 伪造更新服务器） |
| `npm run check:emits` | frontend/ | @event 绑定 vs $emit 声明静态护栏（scripts/check-emit-bindings.mjs） |
| `npm run test:commands` | frontend/ | 命令注册表守卫（tests/commands/，17 条）：加速键查重、编辑器保留键黑名单、Esc/Enter/Tab、macOS 系统截图键、客户视图过滤、菜单树可序列化。**已进 CI**，改 config/commands/ 会被它拦 |
| `npm run test:lowa-e2e` | frontend/ | LOWA 真引擎+键盘链路（tests/lowa-e2e/run.mjs，puppeteer-core 无头，基线 19 组 169 断言；不经应用页面，天然无登录前置） |
| `npm run test:lowa-big` | frontend/ | LOWA 大文档基线组（tests/lowa-e2e/big-doc.mjs：150 页/30 表/20 图夹具由 fixtures/gen-big-doc.py 生成到 $TMPDIR，需 python-docx+pillow；六项硬阈三次中位数，约 6 分钟；与 run.mjs 共用 _boot.mjs）。改 office_thread.js 全文路径（枚举/修订/格式化/导出）后必跑 |
| `npm run test:app-e2e` | frontend/ | 全应用真人模拟（tests/app-e2e/run.mjs；PR-A 去登录后 J1=首启解锁门（试用码），其余旅程 local-mode 免登直达，不再注册 qa_bot_*；需 dev:h5 **5174** + local-mode 后端（默认 9696，冷启动可用新 jar 9797 顶班 + 隔离 user.home/H2/cwd，APP_E2E_JAR 供 J11，**J11/J13 还要 JAVA_HOME**）。**裸 jar 顶班必须补内置资源**：`AI_SKILLS_BUILTIN_DIR=<repo>/backend/skills` + 把 `backend/plugins` 拷进 cwd——缺了 `/api/skills/list` 返回 `[]`，脱敏与语音两步必然超时，**症状长得像 UI 回归**（2026-08-29 踩过，run.mjs 头注释有判别法）。**发版前必跑**（含 J12 英文旅程） |
  - **冷启动全配方（2026-08-19 实测走通）**：① 隔离后端起法必须 `cd backend/` 再起 jar——`ai.skills.dir: skills` 是相对 cwd 的，cwd 落在仓库根会把内置 skill 全丢掉（症状：`Skill 不存在: desensitize`）；② 全新 H2 未解锁会让套件卡死在解锁门，按 `tests/_lib/license-gate.mjs` 的 SEED_RECIPE 往隔离 `user.home/.aiworkdeck/license.json` 播存量 trial 票据（宽限期内合法，别开 trial-code 开关）；③ 5174 可能被别的 worktree 的旧 dev 服务占着（症状：断言全打在旧代码上），自起专用端口并设 `APP_E2E_BASE`；④ `APP_E2E_JAR` 一律绝对路径（runner 在临时目录 spawn，相对路径必挂 J11）；⑤ **自起专用端口的 dev server 必须同时带 `VITE_API_BASE_URL` 指向同一个隔离后端**（`npx uni --port 5175` 若不带这个环境变量，编译进浏览器包的 `api.js` 在 `localhost`/`127.0.0.1` 场景会硬编码回退到 `http://localhost:9696`——跟 `APP_E2E_BASE`/`APP_E2E_BACKEND` 完全是两码事，后两个只管 Node 脚本自己的 fetch 与 `page.goto` 目标；漏了这一步的症状是浏览器控制台刷屏「网络请求失败…端口 9696」，J1-J10 因为多数步骤不硬依赖响应内容而看着照样绿，到 J11 云端协作因为真的要读写数据才开始大面积报错，很容易误判成回归）。
  - **J13（资源包安装）另起隔离后端时，同一台隔离后端不能只改 `ai.packs.base-urls`/`ai.plugins.registry-public-key` 就完事**：`--ai.skills.dir` 也必须显式给绝对路径（指向 `backend/skills` 的一份拷贝，不要指向原路径，防意外写入污染仓库文件）——隔离后端的 cwd 不是 `backend/`，相对路径解析不到内置 skill；命令行覆盖用 Spring 的 `--ai.packs.base-urls[0]=` 这种下标写法实测可行（`-D` 系统属性同理），构建测试用 pack 源与签名密钥对的公用逻辑在 `frontend/tests/_lib/pack-stub.mjs`（`startPackStub()`）。UI 侧把浏览器指到隔离后端不需要另起一个 dev server，新开一个 `browser.newPage()` 并在 `evaluateOnNewDocument` 里注入 `window.checkbaDesktop.apiBaseUrl`（`host.js` 的 `getApiBaseUrl()` 对它的优先级高于 `VITE_API_BASE_URL`，与真实 Electron 壳换后端端口同一条注入路径）即可。
| `npm run test:feedback-e2e` | frontend/ | 反馈浮窗全链路（dev Electron + CDP：真走主进程框选截图、Chromium 假麦克风录音、提交后从 API 回读附件字节）。需 dev:h5 + local-mode 后端，同 desktop-e2e 的端口约定 |
| `npm run test:desktop-e2e` | frontend/ | 桌面保存链路（弹 dev Electron 窗口，webview 真 LOWA 插文本→保存→API 下载验内容；PR-A 后免登直达，provision 会自动用试用码解锁+置向导）。`APP_E2E_BACKEND` 的端口会经 `CHECKBA_BACKEND_PORT` 传给 Electron 壳——渲染层的基址是壳注入的，只改 `VITE_API_BASE_URL` 对它无效 |

每日全量 QA：`scripts/qa-nightly.sh`（crontab，跑在 ~/aiworkdeck-qa/repo 专用克隆，报告 ~/aiworkdeck-qa/reports/，失败 gh 开 issue 标签 qa-nightly，引擎取自已安装 app）。

## 端口体系（2026-08 起）

- **打包态桌面后端：5269 → 5369 → 5169 → 随机**（`desktop/main/services/backend-service.js` allocateBackendPort：真实 bind 探测；被占时先探 `/api/admin/wizard` 验明是否自家后端，**并对比 build 指纹**（spawn 时注入 `AWD_BACKEND_BUILD`=app.jar 的 size-mtime，wizard 响应 `build` 字段回显，PR#589/dev-board#139：更新后残留的陈旧后端曾被静默复用导致新前端打旧后端 404）——同指纹复用；指纹不一致按端口找 pid 定点终止后原地重启（终止失败退而复用保可用性，因为旧进程握着 H2 文件锁）；陌生进程降级下一个端口。dev 态不校验指纹（restart-backend.sh 拉起的后端无指纹）。service-manager 的 verifyReuse/reallocatePort 契约即为此加）。实际端口经 BrowserWindow additionalArguments → preload → `window.checkbaDesktop.apiBaseUrl` 注入渲染层，`frontend/src/services/api.js` 最优先读它。
- **dev 态后端仍 9696**：restart-backend.sh / e2e / CI 全部不变；`CHECKBA_BACKEND_PORT` 可显式覆盖两种模式。注入优先级高于 `VITE_API_BASE_URL`，所以**凡是走 Electron 壳的测试/脚本，要换后端必须改 `CHECKBA_BACKEND_PORT`**，只指 dev server 的环境变量不管用（desktop-e2e 曾因此整条链失败）。
- pptx/mineru/kokoro/asr 打包态是动态回环端口（由后端内部转发，前端不可见）；编辑器静态服务器 47613 因 COOP/COEP 跨源隔离必须独立源，勿并入。

## 本地开发启动

- 一键：`./restart-all.sh`（Docker 服务 + 后端 + 前端 + 桌面）。
- 后端：`cd backend && ./restart-backend.sh`（mvn package -DskipTests → kill 9696 → nohup java -jar，prod 配置，日志 backend/app.log）。
- 前端：`cd frontend && npm run dev:h5`（5173；e2e 用 `npx uni --port 5174`）。**npm 不是 pnpm**。
- 桌面：`cd desktop && npm run dev`（AIWORKDECK_DESKTOP_DEV=1 electron .；dev Electron 复用已跑的 9696 后端不另起 java）。`npm run clean` 清用户数据目录。predev 钩子会跑 `scripts/brand-dev-electron.js` 把 node_modules 里的 Electron.app 改名（见下条）。
- Docker 附属：`docker compose up`（mineru 8001、pptx 5001；easyvoice 9549 段已停用，语音合成改走桌面捆绑的 Kokoro）。

## 关键构建脚本（desktop/scripts/）

- `prepare-backend.js` — fat jar→backend.jar + jlink 裁剪 JRE 到 bundled/<plat>/。
- `prepare-python-service.js` — python-build-standalone 3.11.12 + pip site-packages + 服务源码；mineru 纯 pip 无源码。
- `prepare-graphviz.js` — 烙最小 graphviz（仅布局引擎，不带任何渲染后端；闭包约 4MB）到 bundled/<plat>/graphviz/。**只有诉讼可视化的流程图布局要它**，而诉讼可视化自 v0.21.0 起改走 native pack 分发（[[native-pack-distribution]]），所以**这个脚本只在 `pack-release.yml` 里跑，不在 desktop-build.yml 的安装包构建链里**——它在 desktop-build.yml 里只作为缓存键的哈希输入出现（2026-08-29 核对）。按「安装包里为什么没有 graphviz」排查的人别再往构建链上找。mac 需 install_name_tool 重定位 + ad-hoc 重签（改过的 Mach-O 不重签会被内核 SIGKILL）；脚本自带正反两条自检（详见 `.claude/agents/litigation-visual.md`）。
- `pack-pysvc.js` — 上万小文件→单 pysvc.tar.gz + meta（首启解压进度条；解压逻辑在 desktop/main/services/pysvc-runtime.js）。**必须在签名与冒烟之后跑**。
- `fetch-lowa-assets.js` — LOWA 运行时+CJK 字体进 frontend/dist/zetaoffice/，保留 brotli + .encodings.json 侧车。
- `sign-mac-natives.sh` — 签 electron-builder 够不到的 Mach-O（JRE + jar 内嵌 dylib），时间戳退避重试，nested jar 用 zip -0 回写。
- `desktop/lowa-build/mega-build.sh` — 从源码重建 zh-CN LOWA（Ubuntu 22.04，无人值守）。

## 部署与其它

- `deploy/web/` — Web 服务器版（瘦客户端 Phase A2/鸿蒙路线）：nginx.conf.example、能力探针 probe/、后端 prod 只听 127.0.0.1。
- `deploy/cloud/` — 官方托管的插件云后端（addin.aiworkdeck.com）：nginx server 块、systemd unit、env 模板、部署实录。
- `deploy/update-mirror-sync.sh` — 官网 ECS 上每小时一跑的镜像同步（补丁产物 + 安装包 + installers/latest.json）。
  **安装包留最新两版，别改回「只留最新一版」**：官网 /start 的下载按钮是服务端渲染的，
  数据源（官网仓 lib/latest-release.ts）对 latest.json 用了 `revalidate: 300`。旧包一删、
  而页面缓存里还是旧文件名，用户点下载就是 404（2026-08-18 发 v0.18.0 实测到）；
  留一版也给「已经点了下载、1.4GB 正在传」的用户留余地。改动这段先跑
  `bash deploy/update-mirror-sync_prune_test.sh`（纯本地，不碰网络与真镜像目录）。
  **服务器上跑的是一份副本**（`/www/wwwroot/update/desktop/update-mirror-sync.sh`，cron 每小时 :17），
  合并 PR 不会让它生效，要 scp 覆盖过去。
  **选版不用 `releases/latest`**：那是仓库级「最新」，正式 pack release 也算数
  （2026-08-19 实测顶掉应用版 2.5 小时，脚本连挂三轮、镜像停更）。脚本从
  `/releases` 列表挑 tag 形如 `v<数字>` 的正式版；pack-release.yml 那边同时标
  `prerelease: true` 双保险。latest.json 落地后会回调官网 `/api/revalidate-release`
  （配置在服务器 `/etc/aiworkdeck/mirror-sync.env`，token 与官网 env 同值），
  让 /start 下载链立即刷新；没配置/回调失败靠页面 `revalidate: 300` 兜底。
- `deploy/publish-pack.sh` — 原生资源包（native pack）上架：check 本地验产物 /
  sign 在官网机上用 env 私钥签 manifest（私钥不落本机）/ publish 双机上架
  `/www/wwwroot/plugin-packs/` 暂存校验后换入、双机验证后才切 manifest 指针 /
  verify 两站对账。产物来自 workflow `pack-release.yml`（workflow_dispatch，
  tag `pack-<id>-v<ver>`，win 侧 graphviz 只能在 win runner 出）。规范
  `docs/NATIVE_PACK_DISTRIBUTION.md`。
- **`deploy/publish-lowa-engine.sh` — 换 LOWA 引擎必须走它，别手工传**（issue #310）。
  `check-build <目录>` 只在本地验产物；`publish <目录> <版本号>` 发到两台机；`verify <版本号>` 切指针前必跑。
  它把三件必须同时做对的事绑在一起：按形态判定该压不该压、**两台机都同步**（新加坡是本地镜像直出、
  不回源北京，而 CI 从境外解析走新加坡——只传北京会让境内 curl 全 200 而 CI 报 404）、
  以及内容级终验。校验不走「自己算的哈希对自己」这种闭环：wasm 断言解压后是 `\0asm`，
  data 断言解压后字节数等于 metadata 里的 `remote_package_size`（emscripten 自带真值，
  下游 fetch-lowa-assets.js 对 data 只查「长度 ≥1024」，兜不住双重压缩）。
  落盘先落 web root 之外的暂存区、校验通过再 rename 换入，旧版本自动备份到 `/root/lowa-engine-backup/`。
- 官网部署在独立仓库（website/，gitignore 掉），服务器 ssh -i ~/.ssh/aiworkdeck_ops root@47.92.111.102；ECS 8.137.95.63(~/.ssh/checkba_ecs)。
- 模型不进包：mineru/kokoro/asr 模型首启在"组件管理"下载（下载进度按字节级整体，PR#142）。asr 的 faster-whisper medium 约 1.5GB，依赖闭包 182MB（压缩后约 57MB 进安装包，大头是 onnxruntime 70MB + PyAV 44MB，两者都是 faster-whisper 的硬依赖）。

## 已知地雷

**装好的应用开着，三套 Electron e2e 全起不来（2026-08-23 修）**：`main.js` 的
`requestSingleInstanceLock` 按 userData 目录判重，dev 实例与 `/Applications/AI WorkDeck.app`
默认同一个目录，第二个进程被 `app.quit()` 当场顶掉。现象极不好认——Electron 起来、
打了 `DevTools listening`、随即无声退出，套件只看见「CDP 端点未就绪」或连 ws 时
`ECONNREFUSED`。`tests/_lib/electron-cdp.mjs` 的 `spawnElectron` 现在给 dev 实例挑
自己的 `--user-data-dir`（按 CDP 端口取名），`waitForCdpWs` 收下子进程句柄、进程先退
就立刻报退出码。**新 profile 是空的**：`appLanguage` 对全新安装按 `navigator.language`
猜语言（Electron 常报 en-*），断言中文字面量的套件必须像 desktop-e2e 那样先写
`localStorage.awd_app_language='zh-CN'` 再整页 `goto`（只改 hash 是同文档导航，模块不重来）。

**dev vite 的状态会被文件churn 弄脏**：worktree 里大批文件被改回改去（合并冲突、
stash 误 pop 之类）之后，5174 上的页面可能变成**整页无样式**（`.page-project-overview`
的 display 变 block、文档高两万多像素），而 CSS 其实都在、控制台一个错都没有。
按「点在视口外」的形态红在 e2e 里，很容易误读成布局回归。**先重启 vite 再判**。



- **`@ActiveProfiles("desktop")` 的 `@SpringBootTest` 必须同时把 `spring.datasource.url` 覆盖成
  `jdbc:h2:mem:`**（写法照 IdorAuthIntegrationTest / DesktopContextSmokeTest）：desktop profile
  的默认数据源是 `~/.aiworkdeck/local` 文件库且带 `AUTO_SERVER=TRUE`，开发机上测试会直接附着到
  **正在运行的桌面应用的真实数据库**读写。ChangeSignalWiringTest 曾漏掉这条——它在幽灵项目 7 里
  建「新建文件夹*」再 permDelete，运行中途崩一次就永久残留垃圾行，此后同名检查让本机所有运行
  deterministically 红，还查不出所以然（2026-08-20 实测，dev-board#57）。同病残留的清理配方：
  H2 Shell 带同一 URL（AUTO_SERVER 允许附着活库）先只读核对 name/created_at/user_id 再删。
- CI 签名→冒烟→打包顺序不可乱（PR#176）。
- **Windows 上 node:test 的 teardown 删「被本进程 HTTP 服务读流碰过的」临时目录，必须用异步
  `fs.promises.rm(dir, {recursive, force, maxRetries, retryDelay})`，不能用 rmSync**（PR#436，
  drawio-server.test.js 的 rmrf 帮手是范本）：读流 autoClose 的 fs.close 回调可能还排在事件
  循环里，rmSync 的 maxRetries 是同步忙等、会把事件循环连同那个 close 一起卡死，给多大预算
  都是 ENOTEMPTY 跑穿（run 32237671073 实测证伪过 rmSync 重试版）。纯文件读写的测试不受影响。
- **drawio-server 测试在 CI 按改动面跳过（PR#612）**：这组用例真起 HTTP 服务+真读写 Temp
  目录，Windows runner 的 Defender 会拿排他句柄锁临时文件 → teardown EPERM（hookFailed），
  外层重试只是把 8 秒的步骤拖成几分钟再挂，v0.26.0 首发 run 更锁死 110 分钟（force-cancel
  才解）。desktop-build.yml 的「Detect drawio changes」在 PR/push 区间未碰
  `desktop/main/drawio-server.js` / `desktop/tests/drawio-server.test.js` /
  `desktop/scripts/fetch-drawio-assets.js` 时注入 `SKIP_DRAWIO_TESTS=1`（测试文件顶层早退）；
  **tag 发版与 workflow_dispatch 永远全量**。Desktop unit tests 步骤有 `timeout-minutes: 15`
  兜底。改动这三份文件之一时，自查该 PR 的 Detect 步骤输出应为 FULL。
- 发版有个跨仓的时间差：镜像脚本删旧包 vs 官网页面 ISR 缓存。两边任何一边改保留策略/缓存时长前，先看 `deploy/update-mirror-sync.sh` 里 prune_old_installers 的注释。
- macOS CI 红要当真查（Apple 协议过期事件后恢复）；`--admin` 合并与 worktree 删分支技巧见 ci-macos 记录。
- **`office-addin/installer/build-installers.mjs` 挑签名身份靠「第一条 Developer ID Application」**
  （`security find-identity -v -p codesigning` 的输出顺序，不是按主体筛的）。维护者 Mac 上
  同时装着香港 `X9B97KVA84` 与境内 `8WKHZVR2W8` 两张 Developer ID Application 之后，
  插件安装器签到哪个主体名下就不确定了。出插件 dmg 前先 `security find-identity -v -p codesigning`
  核一眼落在第一条的是谁；要钉死主体得给脚本加显式身份参数（尚未做）。
- iCloud 驱逐会掏空本地文件（打包/测试环境两次踩）；EMFILE 用抬 ulimit 解。
- **iCloud 上「读一下」不是免费的**：被驱逐的文件是 dataless 占位，`stat` 秒回真实大小但
  `st_blocks=0`，**一 read 就同步触发下载**（本机实测 23KB 文件首次 read 耗 1.28 秒，延迟
  决定、与大小无关）。所以凡是「扫用户文件夹」的代码只许 stat，绝不许顺手读内容——
  `LocalRootWatchService.WATCH_FILE_HASHER` 就是为此把 DirectoryWatcher 的默认内容哈希
  换成了 mtime 哈希（默认值会在建立监听时把整棵树逐字节读一遍，等于打开项目就把整个
  文件夹从 iCloud 拉回来）。回归用例 `LocalRootWatchServiceTest`，拿命名管道当"未下载文件"。
  已知仍会读穿全树的是版本记录的 `git add .`（JGit 必须读内容才能存 blob），但它被
  `repoService.isInitialized` 挡着，只有开了版本记录的项目才走到。
- **macOS 菜单栏左上角那个应用名只认 .app 包的 `CFBundleName`**，跟 `app.name`、跟菜单模板
  第一项的 label 都无关（实测 `app.setName('AI WorkDeck')` 之后菜单栏照旧写 Electron）。
  打包版由 electron-builder 按 `build.productName` 写好；dev 跑的是 node_modules 里的
  Electron.app，所以要靠 `scripts/brand-dev-electron.js` 就地改名（Electron 的 dist 包是
  linker-signed adhoc，`Info.plist=not bound`，改它不破坏签名）。**做官网/README 截图前
  务必确认这一步跑过**，否则截出来的图左上角是 Electron。
- worktree 冷启动跑双 e2e 完整配方见 v0.7.7 发版实录；worktree merge 报 stash failed 用 cherry-pick 绕。
- 坏 pnpm node_modules 遇到过——本项目一律 npm。
- **worktree 的 node_modules 落后于新增依赖**（如 TOTP 带来的 `qrcode`）时，vite 会推一个盖满视口的 `vite-error-overlay`，坐标点击全被它吃掉，e2e 表现成"点了没反应"的超时而非编译错误。冷启动 worktree 跑 e2e 前先 `npm install`；desktop-e2e 的点击已加命中校验，会直接报出遮挡者和它的文案。
- **`dist/zetaoffice` 里的 glue 会随 master 前进而过期，且会伪装成产品回归**（2026-08-18 实测踩到）：
  `office_thread.js` / `zeta.js` 是 `src/zetaoffice/public/` 的构建产物，构建一次就躺在 dist 里不动了。
  worktree 活得久一点、master 又改过那份源码，跑出来的就是**旧引擎行为**。这次的现象是 lowa-e2e
  三条署名断言红：期望 `AI WorkDeck` 实际 `AI Workdeck`——看着像"品牌统一漏改、还会写进用户的 Word
  修订署名"，一查源码一处小写都没有，纯粹是 dist 旧。重建后 391 通过 0 失败。
  **判据**：断言值和源码里的常量对不上、而源码明明是对的 → 先重建 glue，别急着开 bug。
  重建配方（`emptyOutDir: true` 会清空整个目录）：先把 `lowa/` 与 cjk 字体挪走 → `npm run build:zetaoffice`
  → 再挪回来。只借兄弟 worktree 的 `lowa/`+字体，glue 一律本树构建（见记忆「引擎 glue 与载荷之分」）。
- **`dist/zetaoffice` 里的 glue 会随 master 前进而过期，且会伪装成产品回归**（2026-08-18 实测踩到）：
  `office_thread.js` / `zeta.js` 是 `src/zetaoffice/public/` 的构建产物，构建一次就躺在 dist 里不动了。
  worktree 活得久一点、master 又改过那份源码，跑出来的就是**旧引擎行为**。这次的现象是 lowa-e2e
  三条署名断言红：期望 `AI WorkDeck` 实际 `AI Workdeck`——看着像"品牌统一漏改、而且会写进用户的 Word
  修订署名"，一查源码一处小写都没有，纯粹是 dist 旧。重建后 391 通过 0 失败。
  **判据**：断言值和源码里的常量对不上、而源码明明是对的 → 先重建 glue，别急着开 bug。
  重建配方（`emptyOutDir: true` 会清空整个目录）：先把 `lowa/` 与 cjk 字体挪走 → `npm run build:zetaoffice`
  → 再挪回来。只借兄弟 worktree 的 `lowa/`+字体，glue 一律本树构建。
- **e2e 起 Electron 必须整棵进程树一起收，CDP 端口不能写死**（2026-08-17 定位）：
  `spawn('npx', ['electron', ...])` 之后 `elec.kill()` 只打得到 npx，真正的 Electron 是**孙子进程**，
  收不到信号会活下来继续占着 CDP 端口（实测跑完一轮之后它还在 LISTEN，端口不释放）。下一轮撞上它有两种死法：
  残留还应答 CDP → 你连上去驱动的是**上一轮那个窗口**；残留只占端口不应答 → 干等 60 秒报「CDP 端点未就绪」。
  正解三件套（desktop-e2e 已落）：① `detached: true` 起进程组 + `process.kill(-pid)` 整组收（并挂 `process.on('exit'/'SIGINT')`）；
  ② 每轮现挑一个空闲端口（`DESKTOP_E2E_CDP_PORT` 可覆盖），别写死 9333——维护者常年多开，并行会话必撞；
  ③ 连之前用 `lsof` 核一遍持端口的 pid 在不在自己那棵树里，不是就当场报死。
  这三件套 + 输入加固已收进 **`frontend/tests/_lib/electron-cdp.mjs`**，desktop / feedback / meeting
  三套共用（以前各抄一份，同一个坑要踩三次）。**app-e2e / lowa-e2e 不受影响**——它们用
  `puppeteer.launch()` 自己起无头 Chrome，进程由 puppeteer 管，也自带下面那三个开关。
- **"点了没反应"先分清是输入通道坏了还是界面坏了**：`page.mouse.click()` 之后在 `window` 捕获期录
  pointerdown/mousedown/mouseup/click，**一个都没有 = 输入通道的问题**，同坐标重试没有意义。
  这一档故障的特征很干净也很反直觉：**`mouseMoved` 照常送达，`mousePressed`/`mouseReleased`/`dispatchKeyEvent`
  被静默丢弃**，而渲染器一切正常（evaluate、布局度量、elementFromPoint、`$refs`、页面栈全对），且一旦坏了整轮不恢复。
  已逐条实测排除：窗口被遮挡/隐藏、别的 App 抢焦点、无边框标题栏的 app-region 拖拽带、页面栈堆两个工作台、
  坐标重排点空、OOPIF/webview 盖住、连错了别人的 Electron。
  **`visibilityState: hidden` 不是故障信号**——这台机器上窗口本来常年 hidden（被终端盖着），通过的轮次同样是 hidden，
  别再拿它当线索（上一版这条记错了，害人查了一轮窗口可见性）。
  主因就是上面那条进程泄漏：修掉之后失败率从 **6/36 掉到 1/61**；剩下的极少数还没定位到 Chromium 内部机制。
  desktop-e2e 的处置是**一次诚实的恢复**：整页重载让浏览器重建这一页的输入路径，然后仍旧用真实鼠标重点一次，
  恢复不了就报死并在错误里点明"是输入通道坏了不是界面问题"。
  **不要退回"页面内 `dispatchEvent` 伪造点击"兜底**（#390 曾这么干，已撤）——桌面端就这一条真实输入的覆盖，
  换成假的之后这一步就再也挡不住真的界面回归了。
  **根因已坐实（2026-08-18）**：是"这一页没被当成有焦点/活动窗口"。把加固摘掉重跑 120 轮，
  复现 3 轮、共 8 次判定，**每一次都是**：掉事件时 press=false → 开
  `Emulation.setFocusEmulationEnabled` → press=true。按下与按键跟焦点绑定、`mouseMoved` 不绑定，
  所以现象才是"移动送得到、点击和按键送不到"。
  解药两层，都在 `_lib/electron-cdp.mjs`：起 Electron 带
  `--disable-backgrounding-occluded-windows` / `--disable-renderer-backgrounding` /
  `--disable-background-timer-throttling`（`puppeteer.launch` 自带，手动 spawn 再 connect 就没有），
  连上页面后开 `hardenPageInput`（即焦点仿真），导航多的地方用 `reassertFocusEmulation` 再压一次
  （emulation 挂在 CDP 会话上，会话/导航都可能把它丢掉）。
  **同一批现场还证伪了「整页重载」这个解药**：重载三次按下通道仍然是死的，那一轮照红不误。
  所以恢复路径必须先补焦点仿真，重载只能算兜底的兜底——别再把重载当主力。

- **meeting-e2e 曾在 master 上整轮红（10/10），四个坑叠在一起**（2026-08-17 修复）：
  ① 用的还是"写 `checkba_last_project_id` → reload 直达工作台"的老配方，而 2026-08 起启动一律落
  项目列表页，于是第一步就卡死——改成 desktop-e2e 同款"轮询到真进工作台为止，在列表上就点卡片"；
  ② 拿「资源管理器」当工作台就绪判据，但**左栏面板是记住上次的**，上一轮把它切到「会议录音」之后
  下一轮永远等不到这四个字——改用 `.page-project-overview` 这种与面板无关的根节点；
  ③ rail 按钮是**开关**，面板已经开着时再点一下正好关上——改成"确保打开"而不是"点一下"；
  ④ **没钉死界面语言**，Electron 常带 `--lang=en-GB`，rail 会变成 "Meeting Recording"，
  中文选择器全失配（现象很像"skill 没启用"，极易误判）——补上 `awd_app_language=zh-CN`。
  ②③是同一类：**上一轮留下的状态破坏下一轮**，写这类断言前先问"上一轮跑完留下了什么"。
- **app-e2e 的两类陈旧断言**（2026-08-18 扫出来的，各红过一轮）：
  ① **左栏 rail 的标题直接抄了 i18n 文案**，而文案会改——「EasyVoice」在 #389 改成「语音合成」，
  J6 那条 `[title="EasyVoice"]` 就一直等不到。改文案时记得回看 J6 那一行；标题的真源是
  `src/config/leftSidebarPlugins.js` 的 `label`（i18n 键 `config.sidebar.*`）。
  ② **界面上出现 ≠ 已经落库**：概览档案那步 `waitText` 一过就立刻读 `/profile` 接口，
  可 @blur 触发的 PUT 还在飞、ProfileHeader 又是乐观更新的，于是间歇性读到一条全 null 的记录。
  凡是"改了界面再回接口对账"的断言，都要轮询到后端认账为止，别赌那一拍。
  （这一类和 #403「不再赌时间」是同一个毛病，写新断言前先问一句：我等的是渲染还是持久化？）
- 分支保护拦 gh pr merge 时权宜 = 用户网页点 Bypass rules and merge（白名单未配成）。
- `docs/` 在 .gitignore，入库要 `git add -f`。
- **改跨类 `public static final String` 常量的值必须 `mvn clean test`**：这类常量在编译期被内联进
  调用方的字节码，maven 增量编译不重编未改动的测试类，于是出现「源码一致、字节码不一致」的假失败
  （2026-08 改 Ollama 设置键时踩过：测试报 expected qwen3:14b but was qwen3-vl:8b，`javap` 确认
  测试类里内联的还是老键名）。CI 是 clean build 所以只坑本地。
- **删「零引用」的 Spring bean 前先想有没有 `@ConditionalOnMissingBean` 角色**：
  `service/ai/DynamicContentRetriever` 全仓 grep 不到调用方，但它靠存在本身压住了 langchain4j
  `RagAutoConfig`——删掉后自动配置自己造 `contentRetriever`，而本项目有两个
  `EmbeddingStore<TextSegment>`，全部 `@SpringBootTest` 一起 NoUniqueBeanDefinition 起不来
  （2026-08 清理孤儿类时踩过，16 个 context 加载失败）。`DesktopContextSmokeTest` 是这条的护栏。
- **`@Table` 的 `indexes` / `uniqueConstraints` 里必须写 snake_case 物理列名**：这两处不参与
  PhysicalNamingStrategy 的驼峰转换（本仓没配自定义策略，用 Spring Boot 默认的
  CamelCaseToUnderscoresNamingStrategy，字段 `projectId` 落成列 `project_id`）。
  Hibernate 6.4.4 会先把它当**逻辑名**查一次，所以写驼峰往往侥幸能解析、索引其实建对了；
  但一旦查不到就把字符串原样塞进 DDL，而 `@UniqueConstraint` 是内联在 `create table` 里的，
  整条建表语句失败、Hibernate 只打一行 WARN（`GenerationTarget encountered exception`）
  就继续启动——**表根本没建出来，启动看着正常，第一次查询才炸**。
  `EntityIndexColumnNamingTest` 是这条的护栏：反射扫全部 `@Entity`，对着 H2 的
  INFORMATION_SCHEMA 逐条对账列名与索引，新实体写错驼峰会自动红。
- **Electron 主进程禁用 `spawnSync`/任何同步阻塞调用**：v0.21.0 新机首启 10-30 秒无窗口、
  Dock 弹强制退出的根因是 `pptx-service.js` 的 `prepare()` 用 `spawnSync` 跑 alembic 迁移——
  这一步冻住的是**主进程**，Node 事件循环停摆等于整个 app 失去响应，系统据此判定"无响应"。
  改成 `spawn` + Promise 等 `exit`（模式抄 `pysvc-runtime.js` 的 `extractTarOnce`），语义不变
  （非零退出码报错、stderr 截断）。同理，首启解压进度窗（`main.js` 的 `ensurePysvcReady`）
  不能在解压完就销毁——后面 `createServices→allocatePorts→startEager` 还要拉起 Java 后端等
  本机服务，这段同样耗时且此前完全没有 UI；真正销毁要挪到 `createMainWindow()` 之后
  （挂在主窗口 `ready-to-show`，避免双窗口叠加闪烁），窗口本身在这期间只切文案不重开。
  `startEager` 逐个 `await` 也一样是白等：各服务端口已在 `allocatePorts` 统一分配好，
  互相没有启动时序依赖，串行只是把首启时间累加，已改 `Promise.all` 且保留单服务失败互不影响。

## 验证（改本领域自身时）

- 改 workflow：推 PR 触发 windows 矩阵先验；mac 变更合并后用手动 workflow_dispatch 验证。
- 改构建脚本：本地跑对应脚本 + desktop `npm test`（pysvc-runtime 有覆盖）。
