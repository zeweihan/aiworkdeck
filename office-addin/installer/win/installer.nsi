; AI WorkDeck Office 插件 Windows 安装器（NSIS + MUI2 品牌向导）。
; 机制：把生产 manifest 拷到 %LOCALAPPDATA%，再写 HKCU\...\WEF\Developer 注册表
; sideload 键（微软官方 Windows sideload 路径）。全程 HKCU / 当前用户，免管理员。
; 构建期由 build-installers.mjs 传入 -DVERSION= -DMANIFEST= -DOUTFILE= -DARTDIR=。
; 美术资产（侧栏/页眉 BMP、图标 ico）在 win/ 下入库，由 installer/render-art.mjs 生成。
; 刻意不设目录选择页：装的只是一份清单文件，位置是实现细节，少一步是一步。

Unicode true
!include "MUI2.nsh"

!define APPNAME "AI WorkDeck Office Add-in"
!define ADDIN_ID "5d9024e8-b355-46a1-ad19-e47aa9f12f65"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\AIWorkDeckOfficeAddin"

Name "${APPNAME}"
OutFile "${OUTFILE}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\AIWorkDeck\OfficeAddin"
BrandingText "AI WorkDeck"

; 文件属性（右键 exe → 详细信息），显得专业也方便排查版本
VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1033 "ProductName" "${APPNAME}"
VIAddVersionKey /LANG=1033 "FileDescription" "AI WorkDeck Office Add-in Installer"
VIAddVersionKey /LANG=1033 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1033 "LegalCopyright" "AI WorkDeck"

!define MUI_ICON "${ARTDIR}\installer.ico"
!define MUI_UNICON "${ARTDIR}\installer.ico"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${ARTDIR}\installerHeader.bmp"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${ARTDIR}\installerSidebar.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${ARTDIR}\installerSidebar.bmp"
!define MUI_WELCOMEPAGE_TITLE "$(WelcomeTitle)"
!define MUI_WELCOMEPAGE_TEXT "$(WelcomeText)"
!define MUI_FINISHPAGE_TITLE "$(FinishTitle)"
!define MUI_FINISHPAGE_TEXT "$(FinishText)"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; 语言：按系统语言自动选择（默认中文优先）
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

LangString WelcomeTitle ${LANG_SIMPCHINESE} "欢迎安装 AI WorkDeck Office 插件"
LangString WelcomeText ${LANG_SIMPCHINESE} "本向导会把 AI WorkDeck 任务窗格安装到 Microsoft Word、Excel 与 PowerPoint。$\r$\n$\r$\n安装仅写入一份插件清单文件，不会修改 Office 本身，也不需要管理员权限。$\r$\n$\r$\n点击「下一步」开始。"
LangString FinishTitle ${LANG_SIMPCHINESE} "安装完成"
LangString FinishText ${LANG_SIMPCHINESE} "完全退出并重新打开 Word / Excel / PowerPoint 后，在「开始」选项卡右侧点击「打开 AI WorkDeck」。$\r$\n$\r$\n如需卸载，随时可在「设置 → 应用」中完成。"
LangString WelcomeTitle ${LANG_ENGLISH} "Welcome to AI WorkDeck for Office"
LangString WelcomeText ${LANG_ENGLISH} "This wizard installs the AI WorkDeck task pane for Microsoft Word, Excel and PowerPoint.$\r$\n$\r$\nOnly a single add-in manifest file is written. Office itself is not modified, and no administrator rights are required.$\r$\n$\r$\nClick Next to begin."
LangString FinishTitle ${LANG_ENGLISH} "Installation Complete"
LangString FinishText ${LANG_ENGLISH} "Fully quit and reopen Word / Excel / PowerPoint, then click 'Open AI WorkDeck' on the right side of the Home tab.$\r$\n$\r$\nYou can uninstall anytime from Settings > Apps."

Section "Install"
  SetOutPath "$INSTDIR"
  File "/oname=manifest.xml" "${MANIFEST}"
  WriteRegStr HKCU "Software\Microsoft\Office\16.0\WEF\Developer" "${ADDIN_ID}" "$INSTDIR\manifest.xml"
  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "AI WorkDeck"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  DeleteRegValue HKCU "Software\Microsoft\Office\16.0\WEF\Developer" "${ADDIN_ID}"
  Delete "$INSTDIR\manifest.xml"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
SectionEnd
