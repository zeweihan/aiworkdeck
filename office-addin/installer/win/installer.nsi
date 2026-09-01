; AI WorkDeck Office 插件 Windows 安装器（搜狗式一键安装 UI，dev-board#339）。
; 机制：把生产 manifest 拷到 %LOCALAPPDATA%，再写 HKCU\...\WEF\Developer 注册表
; sideload 键（微软官方 Windows sideload 路径）。全程 HKCU / 当前用户，免管理员。
; UI 引擎与桌面端共用 desktop/build/win/awd-oneclick-ui.nsh：单张大卡片一键安装，
; 点击后收起为右上角小进度卡，完成卡提示重开 Office。位图在构建时由
; render-oneclick-art.mjs 渲染（build-installers.mjs 负责调用，产物不入库）。
; 构建期由 build-installers.mjs 传入：
;   -DVERSION= -DMANIFEST= -DOUTFILE= -DARTDIR=（图标所在目录）
;   -DAWD_UI_ENGINE=（引擎 nsh 绝对路径） -DGENART=（渲染位图目录） -DLEGALBASE=（法务页站点）
; 刻意不设目录选择：装的只是一份清单文件，位置是实现细节（引擎不开 AWD_UI_DIR_CHOICE）。

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
; 卸载器仍走 MUI 经典页，页眉沿用品牌位图
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "${ARTDIR}\installerHeader.bmp"

; ---- 一键安装 UI ----
!define AWD_UI_ART "${GENART}"
!define AWD_UI_TERMS_URL "${LEGALBASE}/zh/legal/terms"
!define AWD_UI_PRIVACY_URL "${LEGALBASE}/zh/legal/privacy"
!include "${AWD_UI_ENGINE}"

!insertmacro AWD_UI_PAGE_WELCOME
; AWD_UI_PAGE_INSTFILES = 「角落进度卡的 SHOW 钩子 + MUI_PAGE_INSTFILES」原子宏。
; 别拆成两句、更别把钩子挪到欢迎页宏里：夹在中间的任何 MUI 页都会在编译期把
; MUI_PAGE_CUSTOMFUNCTION_SHOW 吃掉（dev-board#356）。
!insertmacro AWD_UI_PAGE_INSTFILES
!insertmacro AWD_UI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; 语言：按系统语言自动选择（默认中文优先）
!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

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
