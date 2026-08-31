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
  ; 与 assisted 模板 StartApp 同款：以桌面用户身份启动，避免继承安装器的提权上下文
  ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" ""
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
