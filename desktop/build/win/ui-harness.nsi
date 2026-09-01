; awd-oneclick-ui.nsh 的独立测试壳：不打真实产物，用假 payload 走完整个
; 「大卡片 → 角落进度卡 → 完成卡」流程，供 installer-ui-smoke 工作流在
; Windows runner 上编译、驱动并逐阶段截图（dev-board#339）。
; 编译参数：
;   makensis -INPUTCHARSET UTF8 -DHARNESS_LANG=SimpChinese|English
;            -DGENART=<位图目录> -DPAYLOAD=<假 payload 目录> -DOUTFILE=<exe>
;            [-DREQUIRED_KB=<n>] [-DEB_PAGE_ORDER] ui-harness.nsi
; REQUIRED_KB 打开磁盘空间闸（dev-board#350）：给个小值走「够用」分支，给个大到
; 不可能满足的值走「拦下」分支——installer-ui-smoke 两个分支都编译并实跑截图。
; EB_PAGE_ORDER 复刻 electron-builder 的页序（欢迎页与 INSTFILES 之间夹一张运行期被
; Abort 跳过、编译期照吃 MUI 定义的页），是 dev-board#356 的回归用例。
Unicode true
!include "MUI2.nsh"

Name "AI WorkDeck"
OutFile "${OUTFILE}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\AWDUIHarness"

!define AWD_UI_ART "${GENART}"
!define AWD_UI_DIR_CHOICE
!define AWD_UI_DIR_LEAF "AI WorkDeck"
!define AWD_UI_TERMS_URL "https://www.aiworkdeck.com/zh/legal/terms"
!define AWD_UI_PRIVACY_URL "https://www.aiworkdeck.com/zh/legal/privacy"
!ifdef REQUIRED_KB
  !define AWD_UI_REQUIRED_KB "${REQUIRED_KB}"
!endif
!macro AwdUiOnLaunch
  ; 测试壳没有可启动的应用，空动作
!macroend
!include "awd-oneclick-ui.nsh"

!insertmacro AWD_UI_PAGE_WELCOME
!ifdef EB_PAGE_ORDER
  ; 复刻 electron-builder（assisted 安装器 + perMachine=false）的真实页序：欢迎页与
  ; INSTFILES 之间夹着 multiUserUi.nsh 的「安装模式」页。这张页在运行期被 PRE 里的
  ; Abort 跳过（桌面端的 customInstallMode 强制按当前用户装），但它在**编译期**照样
  ; 展开 MUI_PAGE_FUNCTION_CUSTOM SHOW，把 MUI_PAGE_CUSTOMFUNCTION_SHOW 吃掉并 !undef。
  ; 下面这段与 multiUserUi.nsh 的结构逐句对应（MUI_PAGE_INIT → PageEx custom →
  ; PRE 里先 Abort、后两句 MUI_PAGE_FUNCTION_CUSTOM），是 dev-board#356 的病灶复刻：
  ; SHOW 钩子只要不是紧贴 INSTFILES 挂的，这条流水线就必须红。
  !insertmacro MUI_PAGE_INIT
  Function AwdHarnessInstallModePre
    Abort
    !insertmacro MUI_PAGE_FUNCTION_CUSTOM PRE
    !insertmacro MUI_PAGE_FUNCTION_CUSTOM SHOW
  FunctionEnd
  PageEx custom
    PageCallbacks AwdHarnessInstallModePre
    Caption " "
  PageExEnd
!endif
!insertmacro MUI_PAGE_INSTFILES
!insertmacro AWD_UI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "${HARNESS_LANG}"

Section "Install"
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD}\*"
  ; 给截图流水线留出稳定的进度窗口
  Sleep 2000
  Sleep 2000
  Sleep 2000
SectionEnd
