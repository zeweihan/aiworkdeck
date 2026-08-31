; awd-oneclick-ui.nsh 的独立测试壳：不打真实产物，用假 payload 走完整个
; 「大卡片 → 角落进度卡 → 完成卡」流程，供 installer-ui-smoke 工作流在
; Windows runner 上编译、驱动并逐阶段截图（dev-board#339）。
; 编译参数：
;   makensis -INPUTCHARSET UTF8 -DHARNESS_LANG=SimpChinese|English
;            -DGENART=<位图目录> -DPAYLOAD=<假 payload 目录> -DOUTFILE=<exe> ui-harness.nsi
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
!macro AwdUiOnLaunch
  ; 测试壳没有可启动的应用，空动作
!macroend
!include "awd-oneclick-ui.nsh"

!insertmacro AWD_UI_PAGE_WELCOME
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
