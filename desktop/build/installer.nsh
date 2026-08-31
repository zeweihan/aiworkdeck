; AI WorkDeck 桌面端 NSIS 定制（electron-builder assisted 安装器的 include 钩子）。
; 一键安装 UI（搜狗式大卡片 + 右上角进度小卡片，dev-board#339）：视觉与交互全在
; win/awd-oneclick-ui.nsh 引擎里，这里只做桌面端接线：
;   - 位图由 scripts/render-oneclick-art.mjs 在打包前渲染到 build/win/generated/
;     （desktop-build.yml 的 Windows 打包步骤负责调用；本地想编译也要先跑它）；
;   - 首页替换 electron-builder 的 Welcome 页（customWelcomePage 钩子）；
;   - 安装模式页强制按当前用户装（customInstallMode），路径选择收进首页的
;     「自定义安装」展开行，所以 package.json 里 allowToChangeInstallationDirectory=false；
;   - 完成页替换成角落完成卡（customFinishPage 钩子），「立即体验」等价于原
;     assisted 模板的 StartApp（ExecShellAsUser 打开 $launchLink）。
; 卸载器不走一键 UI，保持 MUI 经典页（installerSidebar/uninstallerSidebar 仍在用）。
; 静默安装（/S，自动更新路径）不进 GUI 代码，行为与从前一致。

!define AWD_UI_ART "${BUILD_RESOURCES_DIR}/win/generated"
!define AWD_UI_DIR_CHOICE
!define AWD_UI_DIR_LEAF "AI WorkDeck"
!define AWD_UI_TERMS_URL "https://www.aiworkdeck.com/zh/legal/terms"
!define AWD_UI_PRIVACY_URL "https://www.aiworkdeck.com/zh/legal/privacy"

!macro AwdUiOnLaunch
  ; 这段宏在 electron-builder 生成脚本的最前部展开，晚于它的一切都引用不得：
  ; $launchLink（Var 未声明）、APP_EXECUTABLE_FILENAME（common.nsh 未 include）、
  ; StdUtils 插件（!addplugindir 未执行）在 -WX 下都是三连实锤的编译失败。
  ; 用核心指令 ExecShell + 命令行 -D 阶段就存在的 PRODUCT_FILENAME。
  ; 语义：本安装器 RequestExecutionLevel user 且强制按当前用户装、全程不提权，
  ; ExecShell 继承的就是桌面用户上下文，assisted 模板用 ExecShellAsUser 防的
  ;（提权后启动落在 admin 身份）在这条路径上不存在。
  ExecShell "open" "$INSTDIR\${PRODUCT_FILENAME}.exe"
!macroend

!include "win\awd-oneclick-ui.nsh"

!macro customWelcomePage
  !insertmacro AWD_UI_PAGE_WELCOME
!macroend

!macro customInstallMode
  ; 桌面端始终按当前用户安装（历史默认即 perMachine=false），跳过安装模式选择页
  StrCpy $isForceCurrentInstall "1"
!macroend

!macro customFinishPage
  !insertmacro AWD_UI_PAGE_FINISH
!macroend
