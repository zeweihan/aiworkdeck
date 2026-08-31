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

!macro customInstall
  ; Windows 单包双架构（dev-board#341）：主体照旧是 x64 全量（后端 javacv 无
  ; windows-arm64 natives，Python 侧同样，只能留在转译层跑）；安装器额外携带一份
  ; 纯 arm64 Electron 壳（app.asar 零原生依赖、双架构通用，壳只有运行时文件，
  ; 压缩后约 +45MB）。装到 ARM64 Windows 时覆盖到 $INSTDIR，渲染与 LOWA WASM
  ; 走原生，重计算留在转译层由 #340 的看门狗兜住。x64 机器上这段不执行。
  ; 壳目录由 desktop-build.yml 在打包前用 electron-builder --dir --arm64 生成并
  ; 剪掉 resources/；本地无此目录时降级为纯 x64 安装器（只提示不报错，-WX 下
  ; !warning 会打断编译）。
  !if /FileExists "${BUILD_RESOURCES_DIR}\win\arm64-shell\AI WorkDeck.exe"
    ReadRegStr $0 HKLM "SYSTEM\CurrentControlSet\Control\Session Manager\Environment" "PROCESSOR_ARCHITECTURE"
    ${If} $0 == "ARM64"
      SetOutPath $INSTDIR
      File /r "${BUILD_RESOURCES_DIR}\win\arm64-shell\*"
    ${EndIf}
  !else
    !echo "arm64-shell 缺席：本次产物为纯 x64 安装器（CI 之外的构建路径属正常）"
  !endif
!macroend
