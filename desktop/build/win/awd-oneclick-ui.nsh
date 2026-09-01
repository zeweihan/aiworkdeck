; AI WorkDeck「搜狗式」一键安装 UI 引擎（桌面端与 Office 插件端共用，dev-board#339）。
; 形态：单张无边框大卡片（大图背景 + 透明热区当按钮），点「立即安装」后主窗收起为
; 桌面右上角小卡片跑进度，完成后小卡片给「立即体验/完成」。
;
; 为什么全用位图 + 透明热区而不是原生控件：NSIS 原生按钮是 Win32 灰按钮，做不出
; 现代大按钮；文字预渲染进位图还能用品牌字体且高分屏下绝对清晰。位图按 DPI
; （100/125/150/200%）和语言（zh/en）在运行期选择，配套 ManifestDPIAware，
; 根治老向导整窗被系统拉伸出点阵字的问题。
;
; 调用契约（include 本文件之前定义）：
;   !define AWD_UI_ART "<已渲染位图目录>"      ; render-oneclick-art.mjs 的产物目录
;   !define AWD_UI_DIR_CHOICE                  ; 可选：启用「自定义安装」路径展开（桌面端）
;   !define AWD_UI_DIR_LEAF "AI WorkDeck"      ; DIR_CHOICE 时必填：自选目录强制追加的子目录名
;   !define AWD_UI_REQUIRED_KB "<n>"           ; 可选：安装所需磁盘空间（KB）。设了才有磁盘闸；
;                                              ;   桌面端直接给 electron-builder 的 APP_64_UNPACKED_SIZE
;   !define AWD_UI_REQUIRED_EXTRA_KB "<n>"     ; 可选：所需空间之外的余量（KB），默认 0
;   !define AWD_UI_TERMS_URL / AWD_UI_PRIVACY_URL
;   !macro AwdUiOnLaunch                       ; 可选：完成卡「立即体验」的动作（缺省=仅关闭）
; 然后在页面序列里插：
;   !insertmacro AWD_UI_PAGE_WELCOME           ; 大卡片首页（内部给紧随其后的 MUI_PAGE_INSTFILES
;                                              ;   挂 SHOW 回调，把窗口变成角落小卡片）
;   <MUI_PAGE_INSTFILES 由调用方/electron-builder 插入>
;   !insertmacro AWD_UI_PAGE_FINISH            ; 角落完成卡
;
; 坐标契约：所有热区坐标必须与 oneclick-*.html 里的绝对定位一致（两边都以 96dpi
; 基准像素书写，运行期统一乘 $AwdScale）。改布局要两处同步改。
;
; 静默安装（/S，桌面端自动更新走这条路）不进任何 GUI 代码，行为不受影响。

!ifndef AWD_ONECLICK_UI_INCLUDED
!define AWD_ONECLICK_UI_INCLUDED

!include "LogicLib.nsh"
!include "WinMessages.nsh"
!include "FileFunc.nsh"

; 大文件校验交给 lzma 解压自身的错误检测：CRC 预检对几百 MB 的包意味着启动前
; 长时间的「verifying installer」进度条，搜狗式秒开体验第一步就是去掉它。
CRCCheck off
ManifestDPIAware true

; ---- 基准几何（96dpi 像素；与 oneclick-*.html 严格同步）----
!define AWDUI_W 760
!define AWDUI_H 500
!define AWDUI_H_EXP 568
!define AWDUI_MINI_W 360
!define AWDUI_MINI_H 132
; 大卡片热区
!define AWDUI_CTA_X 460
!define AWDUI_CTA_Y 414
!define AWDUI_CTA_W 260
!define AWDUI_CTA_H 72
!define AWDUI_TERMS_X 146
!define AWDUI_TERMS_Y 416
!define AWDUI_PRIV_X 232
!define AWDUI_PRIV_Y 416
!define AWDUI_LINK_W 54
!define AWDUI_LINK_H 24
!define AWDUI_TOGGLE_X 44
!define AWDUI_TOGGLE_Y 448
!define AWDUI_TOGGLE_W 130
!define AWDUI_TOGGLE_H 26
!define AWDUI_CLOSE_X 712
!define AWDUI_CLOSE_Y 8
!define AWDUI_MIN_X 668
!define AWDUI_MIN_Y 8
!define AWDUI_WBTN_W 40
!define AWDUI_WBTN_H 32
; 自定义安装展开行（仅 AWD_UI_DIR_CHOICE）
!define AWDUI_DIREDIT_X 128
!define AWDUI_DIREDIT_Y 522
!define AWDUI_DIREDIT_W 396
!define AWDUI_DIREDIT_H 30
!define AWDUI_BROWSE_X 540
!define AWDUI_BROWSE_Y 521
!define AWDUI_BROWSE_W 88
!define AWDUI_BROWSE_H 32
!define AWDUI_SPACE_X 644
!define AWDUI_SPACE_Y 527
!define AWDUI_SPACE_W 104
!define AWDUI_SPACE_H 20
; 角落小卡片
!define AWDUI_BAR_X 20
!define AWDUI_BAR_Y 100
!define AWDUI_BAR_W 320
!define AWDUI_BAR_H 6
!define AWDUI_DONEBTN_X 236
!define AWDUI_DONEBTN_Y 80
!define AWDUI_DONEBTN_W 104
!define AWDUI_DONEBTN_H 36
!define AWDUI_MCLOSE_X 320
!define AWDUI_MCLOSE_Y 6
!define AWDUI_MCLOSE_W 34
!define AWDUI_MCLOSE_H 28

!ifndef BUILD_UNINSTALLER

!include "nsDialogs.nsh"

; 个别样式常量在不同 NSIS 头文件版本里缺席，补保险定义
!ifndef SS_BITMAP
  !define SS_BITMAP 0x0000000E
!endif
!ifndef SS_NOTIFY
  !define SS_NOTIFY 0x00000100
!endif
!ifndef ES_AUTOHSCROLL
  !define ES_AUTOHSCROLL 0x0080
!endif
!ifndef BS_PUSHBUTTON
  !define BS_PUSHBUTTON 0x0000
!endif
!ifndef WS_TABSTOP
  !define WS_TABSTOP 0x00010000
!endif
!ifndef WS_BORDER
  !define WS_BORDER 0x00800000
!endif

!define MUI_CUSTOMFUNCTION_GUIINIT AwdGuiInit

Var AwdScale      ; 100 / 125 / 150 / 200
Var AwdLang       ; zh / en
Var AwdFont       ; 高 DPI 适配的雅黑句柄（真控件用）
Var AwdDialog
Var AwdImgHero
Var AwdImgMini
Var AwdExpanded
!ifdef AWD_UI_DIR_CHOICE
Var AwdDirEdit
Var AwdBrowseBtn
Var AwdSpaceLabel
!endif
Var AwdWinX       ; 大卡片左上角（展开时保持不动）
Var AwdWinY
!ifdef AWD_UI_REQUIRED_KB
Var AwdFreeMb     ; 目标盘可用空间（MB）；"" = 读不出来
Var AwdNeedMb     ; 本次安装所需空间（MB）
Var AwdDriveRoot  ; 目标盘根（如 "C:"），只为提示文案
!endif

; px = base * scale / 100
!macro _AwdPx var base
  IntOp ${var} ${base} * $AwdScale
  IntOp ${var} ${var} / 100
!macroend
!define AwdPx "!insertmacro _AwdPx"

; 透明点击热区：SS_NOTIFY 空文本 STATIC + NULL 背景刷（SetCtlColors transparent），
; 位图在其下方 z 序，视觉全靠位图、命中全靠热区。坐标是缩放后的像素。
!macro _AwdHotspot outvar x y w h
  ${AwdPx} $R1 ${x}
  ${AwdPx} $R2 ${y}
  ${AwdPx} $R3 ${w}
  ${AwdPx} $R4 ${h}
  nsDialogs::CreateControl STATIC ${WS_VISIBLE}|${WS_CHILD}|${WS_CLIPSIBLINGS}|${SS_NOTIFY} 0 $R1 $R2 $R3 $R4 ""
  Pop ${outvar}
  SetCtlColors ${outvar} "" "transparent"
!macroend
!define AwdHotspot "!insertmacro _AwdHotspot"

; ---------- 初始化：DPI、语言、美术释放、窗口变形 ----------
Function AwdGuiInit
  ; 系统 DPI（进程已声明 DPI aware，GetDeviceCaps 拿到的就是真实值）
  System::Call 'user32::GetDC(i 0) p .r1'
  System::Call 'gdi32::GetDeviceCaps(p r1, i 88) i .r2'
  System::Call 'user32::ReleaseDC(i 0, p r1)'
  IntOp $0 $2 * 100
  IntOp $0 $0 / 96
  ${If} $0 >= 175
    StrCpy $AwdScale 200
  ${ElseIf} $0 >= 140
    StrCpy $AwdScale 150
  ${ElseIf} $0 >= 113
    StrCpy $AwdScale 125
  ${Else}
    StrCpy $AwdScale 100
  ${EndIf}

  ${If} $LANGUAGE == 2052
    StrCpy $AwdLang "zh"
  ${Else}
    StrCpy $AwdLang "en"
  ${EndIf}

  ; 只释放本机需要的那一组位图（/oname 带绝对路径，不动 $OUTDIR）
  InitPluginsDir
  !macro _AwdArtSet L S
    ${If} $AwdLang == "${L}"
    ${AndIf} $AwdScale = ${S}
      ; 路径必须用反斜杠：Windows makensis 的 File 不吃混合斜杠（mac makensis 两种都认）
      File "/oname=$PLUGINSDIR\awd-hero.bmp" "${AWD_UI_ART}\oneclick-hero-${L}-${S}.bmp"
      File "/oname=$PLUGINSDIR\awd-mini-install.bmp" "${AWD_UI_ART}\oneclick-mini-install-${L}-${S}.bmp"
      File "/oname=$PLUGINSDIR\awd-mini-done.bmp" "${AWD_UI_ART}\oneclick-mini-done-${L}-${S}.bmp"
    ${EndIf}
  !macroend
  !insertmacro _AwdArtSet zh 100
  !insertmacro _AwdArtSet zh 125
  !insertmacro _AwdArtSet zh 150
  !insertmacro _AwdArtSet zh 200
  !insertmacro _AwdArtSet en 100
  !insertmacro _AwdArtSet en 125
  !insertmacro _AwdArtSet en 150
  !insertmacro _AwdArtSet en 200

  ; 真控件字体（编辑框/浏览按钮）：按 DPI 建雅黑
  ${AwdPx} $0 15
  IntOp $0 0 - $0
  System::Call 'gdi32::CreateFont(i r0, i 0, i 0, i 0, i 400, i 0, i 0, i 0, i 1, i 0, i 0, i 5, i 0, t "Microsoft YaHei UI") p .r1'
  StrCpy $AwdFont $1

  ; 去掉标题栏与系统菜单 → 无边框卡片；Win11 下顺手要个圆角（旧系统忽略）
  System::Call 'user32::GetWindowLong(p $HWNDPARENT, i -16) i .r0'
  IntOp $0 $0 & 0xFF37FFFF
  System::Call 'user32::SetWindowLong(p $HWNDPARENT, i -16, i r0)'
  System::Call '*(i 2) p .r1'
  System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 33, p r1, i 4)'
  System::Free $1
  ; 无边框窗口默认没有投影，向客户区各延展 1px 玻璃帧换来 DWM 投影，
  ; 玻璃边缘被我们满幅的位图盖住，只剩卡片外的影子（老系统调用失败无害）
  System::Call '*(i 1, i 1, i 1, i 1) p .r1'
  System::Call 'dwmapi::DwmExtendFrameIntoClientArea(p $HWNDPARENT, p r1)'
  System::Free $1

  ; 大卡片尺寸并在工作区居中
  ${AwdPx} $2 ${AWDUI_W}
  ${AwdPx} $3 ${AWDUI_H}
  System::Call '*(i, i, i, i) p .r4'
  System::Call 'user32::SystemParametersInfo(i 0x30, i 0, p r4, i 0)'
  System::Call '*$4(i .r5, i .r6, i .r7, i .r8)'
  System::Free $4
  IntOp $9 $7 - $5
  IntOp $9 $9 - $2
  IntOp $9 $9 / 2
  IntOp $9 $9 + $5
  IntOp $R0 $8 - $6
  IntOp $R0 $R0 - $3
  IntOp $R0 $R0 / 2
  IntOp $R0 $R0 + $6
  StrCpy $AwdWinX $9
  StrCpy $AwdWinY $R0
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i r9, i R0, i r2, i r3, i 0x24)'
FunctionEnd

; 每页都要压一遍的原生装饰：MUI 头图/标题/分隔线/品牌行/三大按钮
Function AwdHideChrome
  !macro _AwdHide id
    GetDlgItem $0 $HWNDPARENT ${id}
    ShowWindow $0 ${SW_HIDE}
  !macroend
  !insertmacro _AwdHide 1
  !insertmacro _AwdHide 2
  !insertmacro _AwdHide 3
  !insertmacro _AwdHide 1028
  !insertmacro _AwdHide 1034
  !insertmacro _AwdHide 1035
  !insertmacro _AwdHide 1036
  !insertmacro _AwdHide 1037
  !insertmacro _AwdHide 1038
  !insertmacro _AwdHide 1039
  !insertmacro _AwdHide 1045
FunctionEnd

; 把页面容器 1018 撑满整个窗口客户区（w/h 为缩放后像素）
Function AwdFillPageArea
  ; 入参：$R7 = w，$R8 = h
  GetDlgItem $0 $HWNDPARENT 1018
  System::Call 'user32::SetWindowPos(p r0, i 0, i 0, i 0, i R7, i R8, i 0x14)'
FunctionEnd

; ---------- 首页：大卡片 ----------
Function AwdWelcomeCreate
  Call AwdHideChrome
  StrCpy $AwdExpanded 0

  ${AwdPx} $R7 ${AWDUI_W}
  !ifdef AWD_UI_DIR_CHOICE
    ${AwdPx} $R8 ${AWDUI_H_EXP}
  !else
    ${AwdPx} $R8 ${AWDUI_H}
  !endif
  Call AwdFillPageArea

  nsDialogs::Create 1018
  Pop $AwdDialog
  ${If} $AwdDialog == error
    Abort
  ${EndIf}
  SetCtlColors $AwdDialog "" 0xFFFFFF
  System::Call 'user32::SetWindowPos(p $AwdDialog, i 0, i 0, i 0, i R7, i R8, i 0x14)'

  ; 背景大图（含按钮/文案的全部视觉）
  nsDialogs::CreateControl STATIC ${WS_VISIBLE}|${WS_CHILD}|${WS_CLIPSIBLINGS}|${SS_BITMAP} 0 0 0 $R7 $R8 ""
  Pop $1
  ${NSD_SetImage} $1 "$PLUGINSDIR\awd-hero.bmp" $AwdImgHero

  ; 热区（创建序在位图之后 = z 序在其上）
  ${AwdHotspot} $2 ${AWDUI_CTA_X} ${AWDUI_CTA_Y} ${AWDUI_CTA_W} ${AWDUI_CTA_H}
  ${NSD_OnClick} $2 AwdInstallClick
  ${AwdHotspot} $2 ${AWDUI_TERMS_X} ${AWDUI_TERMS_Y} ${AWDUI_LINK_W} ${AWDUI_LINK_H}
  ${NSD_OnClick} $2 AwdTermsClick
  ${AwdHotspot} $2 ${AWDUI_PRIV_X} ${AWDUI_PRIV_Y} ${AWDUI_LINK_W} ${AWDUI_LINK_H}
  ${NSD_OnClick} $2 AwdPrivacyClick
  ${AwdHotspot} $2 ${AWDUI_CLOSE_X} ${AWDUI_CLOSE_Y} ${AWDUI_WBTN_W} ${AWDUI_WBTN_H}
  ${NSD_OnClick} $2 AwdCloseClick
  ${AwdHotspot} $2 ${AWDUI_MIN_X} ${AWDUI_MIN_Y} ${AWDUI_WBTN_W} ${AWDUI_WBTN_H}
  ${NSD_OnClick} $2 AwdMinClick

  !ifdef AWD_UI_DIR_CHOICE
    ${AwdHotspot} $2 ${AWDUI_TOGGLE_X} ${AWDUI_TOGGLE_Y} ${AWDUI_TOGGLE_W} ${AWDUI_TOGGLE_H}
    ${NSD_OnClick} $2 AwdToggleCustom

    ; 展开行的真控件（收起时窗口矮、天然不可见，仍显式隐藏防误触 Tab 序）
    ${AwdPx} $R1 ${AWDUI_DIREDIT_X}
    ${AwdPx} $R2 ${AWDUI_DIREDIT_Y}
    ${AwdPx} $R3 ${AWDUI_DIREDIT_W}
    ${AwdPx} $R4 ${AWDUI_DIREDIT_H}
    nsDialogs::CreateControl EDIT ${WS_CHILD}|${WS_TABSTOP}|${ES_AUTOHSCROLL}|${WS_BORDER} 0 $R1 $R2 $R3 $R4 "$INSTDIR"
    Pop $AwdDirEdit
    SendMessage $AwdDirEdit ${WM_SETFONT} $AwdFont 1

    ${AwdPx} $R1 ${AWDUI_BROWSE_X}
    ${AwdPx} $R2 ${AWDUI_BROWSE_Y}
    ${AwdPx} $R3 ${AWDUI_BROWSE_W}
    ${AwdPx} $R4 ${AWDUI_BROWSE_H}
    ${If} $AwdLang == "zh"
      StrCpy $0 "浏 览"
    ${Else}
      StrCpy $0 "Browse"
    ${EndIf}
    nsDialogs::CreateControl BUTTON ${WS_CHILD}|${WS_TABSTOP}|${BS_PUSHBUTTON} 0 $R1 $R2 $R3 $R4 $0
    Pop $AwdBrowseBtn
    SendMessage $AwdBrowseBtn ${WM_SETFONT} $AwdFont 1
    ${NSD_OnClick} $AwdBrowseBtn AwdBrowseClick

    ${AwdPx} $R1 ${AWDUI_SPACE_X}
    ${AwdPx} $R2 ${AWDUI_SPACE_Y}
    ${AwdPx} $R3 ${AWDUI_SPACE_W}
    ${AwdPx} $R4 ${AWDUI_SPACE_H}
    nsDialogs::CreateControl STATIC ${WS_CHILD} 0 $R1 $R2 $R3 $R4 ""
    Pop $AwdSpaceLabel
    SendMessage $AwdSpaceLabel ${WM_SETFONT} $AwdFont 1
    SetCtlColors $AwdSpaceLabel 0x8A9590 0xFFFFFF
  !endif

  nsDialogs::Show
FunctionEnd

Function AwdTermsClick
  Pop $0
  ExecShell "open" "${AWD_UI_TERMS_URL}"
FunctionEnd

Function AwdPrivacyClick
  Pop $0
  ExecShell "open" "${AWD_UI_PRIVACY_URL}"
FunctionEnd

Function AwdCloseClick
  Pop $0
  Quit
FunctionEnd

Function AwdMinClick
  Pop $0
  ShowWindow $HWNDPARENT ${SW_MINIMIZE}
FunctionEnd

!ifdef AWD_UI_DIR_CHOICE
Function AwdToggleCustom
  Pop $0
  ${If} $AwdExpanded = 0
    StrCpy $AwdExpanded 1
    ShowWindow $AwdDirEdit ${SW_SHOW}
    ShowWindow $AwdBrowseBtn ${SW_SHOW}
    ShowWindow $AwdSpaceLabel ${SW_SHOW}
    Call AwdUpdateSpace
    ; 尺寸必须在 AwdUpdateSpace 之后算：它的输出寄存器会踩 $0-$2
    ;（CI 实锤：剩余 32GB 把窗口宽度写成 32px）
    ${AwdPx} $R6 ${AWDUI_H_EXP}
  ${Else}
    StrCpy $AwdExpanded 0
    ShowWindow $AwdDirEdit ${SW_HIDE}
    ShowWindow $AwdBrowseBtn ${SW_HIDE}
    ShowWindow $AwdSpaceLabel ${SW_HIDE}
    ${AwdPx} $R6 ${AWDUI_H}
  ${EndIf}
  ${AwdPx} $R5 ${AWDUI_W}
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i $AwdWinX, i $AwdWinY, i R5, i R6, i 0x24)'
FunctionEnd

Function AwdBrowseClick
  Pop $0
  ${NSD_GetText} $AwdDirEdit $1
  ${If} $AwdLang == "zh"
    StrCpy $0 "选择安装位置"
  ${Else}
    StrCpy $0 "Choose install location"
  ${EndIf}
  nsDialogs::SelectFolderDialog $0 $1
  Pop $1
  ${If} $1 != error
    ${NSD_SetText} $AwdDirEdit $1
    Call AwdUpdateSpace
  ${EndIf}
FunctionEnd

Function AwdUpdateSpace
  ; 先把颜色复位：磁盘闸拦下时 AwdSpaceRefused 会把这个标签染红（dev-board#350），
  ; 用户改完路径再进来必须变回常态，否则换到大盘上了标签还红着，反而误导。
  SetCtlColors $AwdSpaceLabel 0x8A9590 0xFFFFFF
  ${NSD_GetText} $AwdDirEdit $0
  ${GetRoot} $0 $1
  ${If} $1 == ""
    StrCpy $1 "C:"
  ${EndIf}
  ${DriveSpace} "$1\" "/D=F /S=G" $2
  ${If} $2 == ""
    SendMessage $AwdSpaceLabel ${WM_SETTEXT} 0 "STR:"
  ${ElseIf} $AwdLang == "zh"
    SendMessage $AwdSpaceLabel ${WM_SETTEXT} 0 "STR:可用 $2 GB"
  ${Else}
    SendMessage $AwdSpaceLabel ${WM_SETTEXT} 0 "STR:$2 GB free"
  ${EndIf}
FunctionEnd
!endif

; ---------- 磁盘空间闸（dev-board#350）----------
; 以前只把可用空间显示成「可用 X GB」，不足照装不误：NSIS 在解压中途报
; 「Error opening file for writing」，留下一个残缺安装。这里在真正开装之前拦一道。
; 只有调用方给了 AWD_UI_REQUIRED_KB 才编译进来（插件端装的是一份清单，不设闸）。
; 静默安装（/S，自动更新路径）不进 GUI 代码，这道闸对它不生效——那条路的失败处理
; 归更新器自己管，此处不越界。
!ifdef AWD_UI_REQUIRED_KB
!ifndef AWD_UI_REQUIRED_EXTRA_KB
  !define AWD_UI_REQUIRED_EXTRA_KB 0
!endif

; MB → "X.Y"（GB，保留一位小数）。整数取整会把「需要 4 / 可用 3」显示成只差 1 GB，
; 实际可能差 1.9 GB——差多少是用户要不要去清盘的唯一依据，不能糊。
!macro _AwdGbText outvar mb
  IntOp $R5 ${mb} / 1024
  IntOp $R6 ${mb} % 1024
  IntOp $R6 $R6 * 10
  IntOp $R6 $R6 / 1024
  StrCpy ${outvar} "$R5.$R6"
!macroend
!define AwdGbText "!insertmacro _AwdGbText"

; 读 $INSTDIR 所在盘的可用空间 → $AwdFreeMb / $AwdDriveRoot，读不到留空串。
; FileFunc 的 GetRoot/DriveSpace 会踩调用方的 $0-$2（引擎里已有实锤记录，见
; AwdToggleCustom 的注释：剩余 32GB 把窗口宽度写成 32px），所以进出各存取一次，
; 结果一律落在 Var 上——调用方拿到的 $0-$2 与调用前逐字节一致。
Function AwdReadFreeSpace
  Push $0
  Push $1
  Push $2
  StrCpy $AwdFreeMb ""
  StrCpy $AwdDriveRoot ""
  StrCpy $0 "$INSTDIR"
  ${GetRoot} $0 $1
  ${If} $1 != ""
    StrCpy $AwdDriveRoot $1
    ${DriveSpace} "$1\" "/D=F /S=M" $2
    StrCpy $AwdFreeMb $2
  ${EndIf}
  Pop $2
  Pop $1
  Pop $0
FunctionEnd

; 空间够不够。出参：$AwdNeedMb 恒为所需 MB；栈顶 1=不足 / 0=可以装。
; **读不出可用空间时一律放行**：宁可让 NSIS 自己在解压时报错，也不能因为一次读盘
; 失败（网络盘、UNC 路径、DriveSpace 在某些卷上返回空）把装得下的用户挡在门外。
; 同理，读出 0 或非数字也按「读不出来」处理——IntCmp 会把非数字当 0，那会误判成不足。
Function AwdSpaceShort
  IntOp $AwdNeedMb ${AWD_UI_REQUIRED_KB} + ${AWD_UI_REQUIRED_EXTRA_KB}
  IntOp $AwdNeedMb $AwdNeedMb / 1024
  Call AwdReadFreeSpace
  ${If} $AwdFreeMb == ""
    Push 0
  ${ElseIf} $AwdFreeMb <= 0
    Push 0
  ${ElseIf} $AwdFreeMb < $AwdNeedMb
    Push 1
  ${Else}
    Push 0
  ${EndIf}
FunctionEnd

; 就地拦下并说清楚差多少。
Function AwdSpaceRefused
  !ifdef AWD_UI_DIR_CHOICE
    ; 展开态下把「可用 X GB」染红（收起态这个标签本来就不可见，只靠下面的对话框）。
    ; 只改颜色不改文案：标签宽 ${AWDUI_SPACE_W} 是按「可用 XX GB」排的版，加字会溢出，
    ; 而文案与美术位图、热区坐标是同一套基准，改一处要改三处。
    SetCtlColors $AwdSpaceLabel 0xC0392B 0xFFFFFF
    ; 必须 RedrawWindow 同步重绘，不能只 InvalidateRect：提示框在卡片中部、盖不住
    ; 右下角这个标签，关框时不会顺带擦除重画它，只打脏标记的话颜色一直是旧的
    ;（首轮 CI 截图实锤：文案对了、标签还是灰的）。
    ; RDW_INVALIDATE|RDW_ERASE|RDW_UPDATENOW = 0x1|0x4|0x100
    System::Call 'user32::RedrawWindow(p $AwdSpaceLabel, p 0, p 0, i 0x105)'
  !endif

  ${AwdGbText} $R3 $AwdNeedMb
  ${AwdGbText} $R4 $AwdFreeMb
  ${If} $AwdLang == "zh"
    !ifdef AWD_UI_DIR_CHOICE
      MessageBox MB_OK|MB_ICONEXCLAMATION "可用空间不足，暂时无法安装。$\n$\n安装需要约 $R3 GB，$AwdDriveRoot 盘当前可用 $R4 GB。$\n可以点「自定义安装」换一个磁盘，或清理后重试。"
    !else
      MessageBox MB_OK|MB_ICONEXCLAMATION "可用空间不足，暂时无法安装。$\n$\n安装需要约 $R3 GB，$AwdDriveRoot 盘当前可用 $R4 GB。$\n请清理磁盘后重试。"
    !endif
  ${Else}
    !ifdef AWD_UI_DIR_CHOICE
      MessageBox MB_OK|MB_ICONEXCLAMATION "Not enough free disk space.$\n$\nSetup needs about $R3 GB; drive $AwdDriveRoot has $R4 GB free.$\nUse Custom install to pick another drive, or free up space and try again."
    !else
      MessageBox MB_OK|MB_ICONEXCLAMATION "Not enough free disk space.$\n$\nSetup needs about $R3 GB; drive $AwdDriveRoot has $R4 GB free.$\nFree up space and try again."
    !endif
  ${EndIf}
FunctionEnd
!endif

Function AwdInstallClick
  Pop $0
  !ifdef AWD_UI_DIR_CHOICE
    ${NSD_GetText} $AwdDirEdit $0
    ${If} $0 != ""
      ; 去尾部反斜杠
      StrCpy $1 $0 1 -1
      ${If} $1 == "\"
        StrCpy $0 $0 -1
      ${EndIf}
      ; 自选目录强制带上产品子目录，防止把文件铺满用户选的裸盘根/工具目录
      StrLen $1 "${AWD_UI_DIR_LEAF}"
      IntOp $1 0 - $1
      StrCpy $2 $0 "" $1
      ${If} $2 != "${AWD_UI_DIR_LEAF}"
        StrCpy $0 "$0\${AWD_UI_DIR_LEAF}"
      ${EndIf}
      StrCpy $INSTDIR $0
    ${EndIf}
  !endif
  !ifdef AWD_UI_REQUIRED_KB
    ; 必须在 $INSTDIR 定下来之后再算——用户可能刚在「自定义安装」里换过盘。
    ; AwdSpaceShort 内部完整保存/恢复 $0-$2，上面那段路径处理的结果不受影响。
    Call AwdSpaceShort
    Pop $1
    ${If} $1 = 1
      Call AwdSpaceRefused
      Return
    ${EndIf}
  !endif
  ; 等价于按下「下一步」，交回 NSIS 页面机
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

; ---------- 安装页：右上角小卡片 ----------
Function AwdInstFilesShow
  Call AwdHideChrome
  ; 拷完直接滑到完成卡，不停在「已完成，请点下一步」
  SetAutoClose true

  ; 缩到小卡片并钉到工作区右上角
  ${AwdPx} $2 ${AWDUI_MINI_W}
  ${AwdPx} $3 ${AWDUI_MINI_H}
  System::Call '*(i, i, i, i) p .r4'
  System::Call 'user32::SystemParametersInfo(i 0x30, i 0, p r4, i 0)'
  System::Call '*$4(i .r5, i .r6, i .r7, i .r8)'
  System::Free $4
  ${AwdPx} $0 16
  IntOp $9 $7 - $2
  IntOp $9 $9 - $0
  IntOp $R0 $6 + $0
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i r9, i R0, i r2, i r3, i 0x24)'

  StrCpy $R7 $2
  StrCpy $R8 $3
  Call AwdFillPageArea

  ; 当前页对话框（instfiles 内层）铺满小卡片
  FindWindow $1 "#32770" "" $HWNDPARENT
  System::Call 'user32::SetWindowPos(p r1, i 0, i 0, i 0, i r2, i r3, i 0x14)'

  ; 原生控件：只留进度条，状态文本/详情列表/详情按钮全隐藏
  GetDlgItem $0 $1 1006
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $1 1016
  ShowWindow $0 ${SW_HIDE}
  GetDlgItem $0 $1 1027
  ShowWindow $0 ${SW_HIDE}

  ; 小卡片背景图，压到 z 序最底，让进度条浮在其上
  System::Call 'user32::CreateWindowEx(i 0, t "STATIC", t "", i 0x5400000E, i 0, i 0, i r2, i r3, p r1, i 0, i 0, p 0) p .r4'
  System::Call 'user32::LoadImage(i 0, t "$PLUGINSDIR\awd-mini-install.bmp", i 0, i 0, i 0, i 0x10) p .r5'
  StrCpy $AwdImgMini $5
  SendMessage $4 0x0172 0 $5
  System::Call 'user32::SetWindowPos(p r4, p 1, i 0, i 0, i 0, i 0, i 0x13)'

  ; 进度条挪进卡片布局位，并剥掉系统主题换品牌配色：
  ; 主题态的绿块条既土又不可调色，SetWindowTheme("","") 退回经典绘制后
  ; PBM_SETBARCOLOR/PBM_SETBKCOLOR 生效，得到扁平细条（叠在位图画的圆角轨道上）
  GetDlgItem $0 $1 1004
  ${AwdPx} $R1 ${AWDUI_BAR_X}
  ${AwdPx} $R2 ${AWDUI_BAR_Y}
  ${AwdPx} $R3 ${AWDUI_BAR_W}
  ${AwdPx} $R4 ${AWDUI_BAR_H}
  System::Call 'user32::SetWindowPos(p r0, i 0, i R1, i R2, i R3, i R4, i 0x14)'
  System::Call 'uxtheme::SetWindowTheme(p r0, w "", w "")'
  SendMessage $0 0x0409 0 0x004C7A1E    ; PBM_SETBARCOLOR = 品牌绿 #1E7A4C（COLORREF BGR）
  SendMessage $0 0x2001 0 0x00EEF2EC    ; PBM_SETBKCOLOR = 轨道浅薄荷 #ECF2EE
FunctionEnd

; ---------- 完成页：角落完成卡 ----------
Function AwdFinishCreate
  Call AwdHideChrome

  ; 尺寸位置沿用安装页（重申一遍，防御直接跳到本页的路径）
  ${AwdPx} $R7 ${AWDUI_MINI_W}
  ${AwdPx} $R8 ${AWDUI_MINI_H}
  Call AwdFillPageArea

  nsDialogs::Create 1018
  Pop $AwdDialog
  ${If} $AwdDialog == error
    Abort
  ${EndIf}
  SetCtlColors $AwdDialog "" 0xFFFFFF
  System::Call 'user32::SetWindowPos(p $AwdDialog, i 0, i 0, i 0, i R7, i R8, i 0x14)'

  nsDialogs::CreateControl STATIC ${WS_VISIBLE}|${WS_CHILD}|${WS_CLIPSIBLINGS}|${SS_BITMAP} 0 0 0 $R7 $R8 ""
  Pop $1
  ${NSD_SetImage} $1 "$PLUGINSDIR\awd-mini-done.bmp" $AwdImgHero

  ${AwdHotspot} $2 ${AWDUI_DONEBTN_X} ${AWDUI_DONEBTN_Y} ${AWDUI_DONEBTN_W} ${AWDUI_DONEBTN_H}
  ${NSD_OnClick} $2 AwdLaunchClick
  ${AwdHotspot} $2 ${AWDUI_MCLOSE_X} ${AWDUI_MCLOSE_Y} ${AWDUI_MCLOSE_W} ${AWDUI_MCLOSE_H}
  ${NSD_OnClick} $2 AwdFinishCloseClick

  nsDialogs::Show
FunctionEnd

Function AwdLaunchClick
  Pop $0
  !ifmacrodef AwdUiOnLaunch
    !insertmacro AwdUiOnLaunch
  !endif
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

Function AwdFinishCloseClick
  Pop $0
  SendMessage $HWNDPARENT ${WM_COMMAND} 1 0
FunctionEnd

; ---------- 页面插入宏 ----------
!macro AWD_UI_PAGE_WELCOME
  Page custom AwdWelcomeCreate
  ; 紧随其后的 MUI_PAGE_INSTFILES 会消费这个 SHOW 回调（自定义 Page 不消费 MUI 定义）
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW AwdInstFilesShow
!macroend

!macro AWD_UI_PAGE_FINISH
  Page custom AwdFinishCreate
!macroend

!else
; 卸载器构建通道：一键 UI 只作用于安装器，卸载器保持 MUI 经典页（仍受益于 DPI 声明）
!macro AWD_UI_PAGE_WELCOME
!macroend
!macro AWD_UI_PAGE_FINISH
!macroend
!endif

!endif # AWD_ONECLICK_UI_INCLUDED
