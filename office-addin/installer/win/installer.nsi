; AI WorkDeck Office 插件 Windows 安装器（NSIS）。
; 机制：把生产 manifest 拷到 %LOCALAPPDATA%，再写 HKCU\...\WEF\Developer 注册表
; sideload 键（微软官方 Windows sideload 路径）。全程 HKCU / 当前用户，免管理员。
; 构建期由 build-installers.mjs 传入 -DVERSION= -DMANIFEST= -DOUTFILE=。

Unicode true
!define APPNAME "AI WorkDeck Office Add-in"
!define ADDIN_ID "5d9024e8-b355-46a1-ad19-e47aa9f12f65"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\AIWorkDeckOfficeAddin"

Name "${APPNAME}"
OutFile "${OUTFILE}"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\AIWorkDeck\OfficeAddin"
ShowInstDetails show

Page directory
Page instfiles
UninstPage uninstConfirm
UninstPage instfiles

Section "Install"
  SetOutPath "$INSTDIR"
  File "/oname=manifest.xml" "${MANIFEST}"
  WriteRegStr HKCU "Software\Microsoft\Office\16.0\WEF\Developer" "${ADDIN_ID}" "$INSTDIR\manifest.xml"
  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "AI WorkDeck"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  MessageBox MB_OK "安装完成。重新启动 Word / Excel / PowerPoint 后，在「开始」选项卡右侧点击「打开 AI WorkDeck」。"
SectionEnd

Section "Uninstall"
  DeleteRegValue HKCU "Software\Microsoft\Office\16.0\WEF\Developer" "${ADDIN_ID}"
  Delete "$INSTDIR\manifest.xml"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
SectionEnd
