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
;   !insertmacro AWD_UI_PAGE_WELCOME           ; 大卡片首页
;   !insertmacro AWD_UI_PAGE_INSTFILES         ; 角落进度卡（= SHOW 钩子 + MUI_PAGE_INSTFILES）
;   !insertmacro AWD_UI_PAGE_FINISH            ; 角落完成卡
; 页序由 electron-builder 之类的外部模板掌握、MUI_PAGE_INSTFILES 不归调用方插时，
; 改插 !insertmacro AWD_UI_INSTFILES_HOOK，且**必须紧贴那句 MUI_PAGE_INSTFILES 之前**
;（原因见文件末尾 AWD_UI_INSTFILES_HOOK 的注释，dev-board#356）。
;
; 坐标契约：所有热区坐标必须与 oneclick-*.html 里的绝对定位一致（两边都以 96dpi
; 基准像素书写，运行期统一乘 $AwdScale）。改布局要两处同步改。
;
; 可拖动契约（dev-board#366）：三张卡都是去掉了 WS_CAPTION 的无边框窗，系统不再给
; 任何可拖的标题带，所以「能拖」要自己接：
;   - 欢迎卡 / 完成卡是 nsDialogs 页，背景位图带 SS_NOTIFY，STN_CLICKED 在鼠标**按下**
;     那一刻就发（static 控件对 WM_LBUTTONDOWN 的处理，与 BUTTON 抬起才发 BN_CLICKED
;     不同），回调里 ReleaseCapture + 给主窗发 WM_NCLBUTTONDOWN/HTCAPTION，把还按着的
;     这一下交给系统的模态拖动循环——按在任何热区之外的地方都能拖走整张卡；
;   - 进度卡期间脚本一个字都跑不了（Section 在 NSIS 的 install_thread 上执行，UI 线程
;     的消息泵是活的、但同一套执行引擎不许两条线程并发进 NSIS 代码，nsDialogs 在
;     instfiles 页也没有落脚点），所以这一段把 WS_CAPTION 加回来当拖动带：无 WS_SYSMENU
;     即无图标无按钮，Win11 用 DWM 把标题带与文字染成卡片白（看起来只是卡片顶上多了
;     一条空白），老系统显示系统标题带。进完成卡再去掉。
;   installer-ui-smoke 对三张卡各拖一次并断言窗口矩形跟着走。
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
!ifndef WM_NCLBUTTONDOWN
  !define WM_NCLBUTTONDOWN 0x00A1
!endif
; WS_CAPTION（= WS_BORDER|WS_DLGFRAME）：初始化时剥掉，进度卡期间加回当拖动带
!define AWDUI_WS_CAPTION 0x00C00000
!define AWDUI_HTCAPTION 2

!define MUI_CUSTOMFUNCTION_GUIINIT AwdGuiInit

Var AwdScale      ; 100 / 125 / 150 / 200
Var AwdLang       ; zh / en
Var AwdFont       ; 高 DPI 适配的雅黑句柄（真控件用）
Var AwdDialog
Var AwdImgHero
Var AwdImgMini
Var AwdBgWnd      ; 当前 nsDialogs 页的背景位图控件（建完所有控件后压底用）
Var AwdExpanded
!ifdef AWD_UI_DIR_CHOICE
Var AwdDirEdit
Var AwdBrowseBtn
Var AwdSpaceLabel
!endif
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

  ; 背景大图（含按钮/文案的全部视觉）。带 SS_NOTIFY 是为了拖动：按在热区之外的任何
  ; 地方，位图的 STN_CLICKED 把这一按交给 AwdDragClick。
  ; **z 序地雷**：子窗口创建时是插到 z 序**最底**的（后建的在下面，不是在上面），
  ; 以前位图没有 SS_NOTIFY、对命中测试透明，点击穿过它落到下面的热区，所以没露馅；
  ; 一加 SS_NOTIFY 位图就变成 HTCLIENT，会把「立即安装」的点击整个吃掉
  ;（installer-ui-smoke 实锤：拖动通了、随后所有热区全哑）。所以位图必须在
  ; **所有控件都建完之后**再压到 HWND_BOTTOM（见 nsDialogs::Show 之前那句）。
  nsDialogs::CreateControl STATIC ${WS_VISIBLE}|${WS_CHILD}|${WS_CLIPSIBLINGS}|${SS_BITMAP}|${SS_NOTIFY} 0 0 0 $R7 $R8 ""
  Pop $AwdBgWnd
  ${NSD_SetImage} $AwdBgWnd "$PLUGINSDIR\awd-hero.bmp" $AwdImgHero
  ${NSD_OnClick} $AwdBgWnd AwdDragClick

  ; 热区（都建在位图之后，最后再把位图压底）
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

  ; 位图压到 z 序最底：所有热区/真控件都已建好，这一句之后它们才真的在位图之上
  System::Call 'user32::SetWindowPos(p $AwdBgWnd, p 1, i 0, i 0, i 0, i 0, i 0x13)'
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

; 大卡片右上角 ✕。**这里不能用 Quit**（dev-board#354，CI 实锤）：Quit 只是
; g_quit_flag++ 加一发 PostQuitMessage，而此刻正处在 nsDialogs::Show 自己的消息
; 循环里——那个循环只看对话框句柄还在不在，不看 GetMessage 的返回值，WM_QUIT 被它
; 取走后直接丢弃，循环照转，安装器就此关不掉，用户只能去任务管理器杀。
; 改走「取消」（IDCANCEL=2）交回 NSIS 页面机：页面机会销毁对话框，Show 的循环
; 随之退出。与完成卡两个按钮用的 SendMessage WM_COMMAND 同族，那条路已被
; zh/en 两条冒烟流程实跑覆盖。三个安装器都没定义 MUI_ABORTWARNING，不会多弹确认框。
Function AwdCloseClick
  Pop $0
  SendMessage $HWNDPARENT ${WM_COMMAND} 2 0
FunctionEnd

Function AwdMinClick
  Pop $0
  ShowWindow $HWNDPARENT ${SW_MINIMIZE}
FunctionEnd

; 无边框卡片的拖动（dev-board#366）。挂在背景位图的 OnClick 上：static 控件的
; STN_CLICKED 是在 WM_LBUTTONDOWN 里发的，回调跑到这里时鼠标还按着，
; ReleaseCapture 后给主窗发 WM_NCLBUTTONDOWN/HTCAPTION，DefWindowProc 就当用户按住了
; 标题栏，进入系统自己的模态拖动循环，抬起才回来——与 WinForms 无边框窗的经典写法
; 同一条路。SendMessage 是同步的，整段拖动都在这一句里完成。
Function AwdDragClick
  Pop $0
  System::Call 'user32::ReleaseCapture()'
  SendMessage $HWNDPARENT ${WM_NCLBUTTONDOWN} ${AWDUI_HTCAPTION} 0
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
  ; SWP_NOMOVE：只改高度，左上角留在原地。卡片现在能拖，用初始化时记的坐标重定位
  ; 会把用户刚拖走的卡片弹回屏幕中央。
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i 0, i 0, i R5, i R6, i 0x26)'
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
; 提示只走对话框，**没有**去染红展开行里那个「可用 X GB」标签：试过
; SetCtlColors + InvalidateRect、再试 SetCtlColors + RedrawWindow(同步重绘)，
; 两轮 installer-ui-smoke 截图都证明运行期改这个控件的颜色不生效（文案对、标签还是灰的）。
; 没有 Windows 真机可调，而对话框已经把「需要多少 / 该盘有多少 / 怎么办」说全了，
; 与其留一段看着像生效其实没生效的代码，不如不留。要改文案也不行：标签宽
; ${AWDUI_SPACE_W} 是按「可用 XX GB」排的版，加字会溢出，且文案与美术位图、热区坐标
; 是同一套 96dpi 基准，改一处要改三处。
Function AwdSpaceRefused
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

  ; 缩到小卡片并钉到工作区右上角。进度卡期间脚本一行都跑不了（见文件头「可拖动契约」），
  ; 拖动带只能是真标题栏：把初始化时剥掉的 WS_CAPTION 加回来（不带 WS_SYSMENU，
  ; 无图标无按钮）。外框 = 客户区 + 标题带 + 边框，由 AdjustWindowRectEx 按当前样式
  ; 实算，别手抄 31px——不同系统、不同 DPI 都不一样。
  ${AwdPx} $2 ${AWDUI_MINI_W}
  ${AwdPx} $3 ${AWDUI_MINI_H}
  System::Call 'user32::GetWindowLong(p $HWNDPARENT, i -16) i .r0'
  IntOp $0 $0 | ${AWDUI_WS_CAPTION}
  System::Call 'user32::SetWindowLong(p $HWNDPARENT, i -16, i r0)'
  ; Win11：标题带与文字染成卡片白、边框染成位图自带的 1px 框色 #DCE5DF，看起来只是
  ; 卡片顶上多了一条空白。DWMWA_CAPTION_COLOR=35 / TEXT_COLOR=36 / BORDER_COLOR=34，
  ; 值是 COLORREF（BGR）；Win10 及更早不认这几个属性，调用失败无害，显示系统标题带。
  System::Call '*(i 0x00FFFFFF) p .r1'
  System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 35, p r1, i 4)'
  System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 36, p r1, i 4)'
  System::Free $1
  System::Call '*(i 0x00DFE5DC) p .r1'
  System::Call 'dwmapi::DwmSetWindowAttribute(p $HWNDPARENT, i 34, p r1, i 4)'
  System::Free $1
  System::Call 'user32::GetWindowLong(p $HWNDPARENT, i -20) i .r1'
  System::Call '*(i 0, i 0, i r2, i r3) p .r4'
  System::Call 'user32::AdjustWindowRectEx(p r4, i r0, i 0, i r1)'
  System::Call '*$4(i .r5, i .r6, i .r7, i .r8)'
  System::Free $4
  IntOp $R1 $7 - $5     ; 外框宽
  IntOp $R2 $8 - $6     ; 外框高
  System::Call '*(i, i, i, i) p .r4'
  System::Call 'user32::SystemParametersInfo(i 0x30, i 0, p r4, i 0)'
  System::Call '*$4(i .r5, i .r6, i .r7, i .r8)'
  System::Free $4
  ${AwdPx} $0 16
  IntOp $9 $7 - $R1
  IntOp $9 $9 - $0
  IntOp $R0 $6 + $0
  ; 0x34 = SWP_FRAMECHANGED|SWP_NOACTIVATE|SWP_NOZORDER：改过样式必须带 FRAMECHANGED，
  ; 否则非客户区不重算，标题带画不出来
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i r9, i R0, i R1, i R2, i 0x34)'

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

  ; 去掉进度卡期间加回的标题带，回到无边框（直接跳到本页的路径上它本来就没有，
  ; 再剥一次无害）。客户区左上角留在原地——用户可能已经把进度卡拖到别处，
  ; 卡片内容不该因为标题带消失而跳一下。
  System::Call '*(i 0, i 0) p .r1'
  System::Call 'user32::ClientToScreen(p $HWNDPARENT, p r1)'
  System::Call '*$1(i .r2, i .r3)'
  System::Free $1
  System::Call 'user32::GetWindowLong(p $HWNDPARENT, i -16) i .r0'
  IntOp $0 $0 & 0xFF37FFFF
  System::Call 'user32::SetWindowLong(p $HWNDPARENT, i -16, i r0)'
  ${AwdPx} $R7 ${AWDUI_MINI_W}
  ${AwdPx} $R8 ${AWDUI_MINI_H}
  System::Call 'user32::SetWindowPos(p $HWNDPARENT, i 0, i r2, i r3, i R7, i R8, i 0x34)'
  Call AwdFillPageArea

  nsDialogs::Create 1018
  Pop $AwdDialog
  ${If} $AwdDialog == error
    Abort
  ${EndIf}
  SetCtlColors $AwdDialog "" 0xFFFFFF
  System::Call 'user32::SetWindowPos(p $AwdDialog, i 0, i 0, i 0, i R7, i R8, i 0x14)'

  ; 背景位图同欢迎卡：SS_NOTIFY + OnClick 拖动，热区建完再压底（z 序地雷见欢迎卡）
  nsDialogs::CreateControl STATIC ${WS_VISIBLE}|${WS_CHILD}|${WS_CLIPSIBLINGS}|${SS_BITMAP}|${SS_NOTIFY} 0 0 0 $R7 $R8 ""
  Pop $AwdBgWnd
  ${NSD_SetImage} $AwdBgWnd "$PLUGINSDIR\awd-mini-done.bmp" $AwdImgHero
  ${NSD_OnClick} $AwdBgWnd AwdDragClick

  ${AwdHotspot} $2 ${AWDUI_DONEBTN_X} ${AWDUI_DONEBTN_Y} ${AWDUI_DONEBTN_W} ${AWDUI_DONEBTN_H}
  ${NSD_OnClick} $2 AwdLaunchClick
  ${AwdHotspot} $2 ${AWDUI_MCLOSE_X} ${AWDUI_MCLOSE_Y} ${AWDUI_MCLOSE_W} ${AWDUI_MCLOSE_H}
  ${NSD_OnClick} $2 AwdFinishCloseClick

  System::Call 'user32::SetWindowPos(p $AwdBgWnd, p 1, i 0, i 0, i 0, i 0, i 0x13)'
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
!macroend

; 安装页的 SHOW 回调（把窗口变成角落小进度卡）。
;
; **必须紧贴 MUI_PAGE_INSTFILES 之前插，中间不许再夹任何 MUI 页**——
; MUI_PAGE_CUSTOMFUNCTION_SHOW 是「谁先展开谁吃掉」的一次性 define：MUI2 的
; Pages.nsh 里 MUI_PAGE_FUNCTION_CUSTOM 展开成 `Call <fn>` + 紧跟一句 `!undef`，
; 所以夹在中间的任何 MUI 页都会在**编译期**把它抢走，安装页就此没有 SHOW 回调。
; 更阴的是抢走它的那个页在运行期还可能被 Abort 跳过（electron-builder 的「安装模式」
; 页就是），于是那句 Call 连跑都不会跑——两头都不响，界面塌成原生向导（dev-board#356）。
!macro AWD_UI_INSTFILES_HOOK
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW AwdInstFilesShow
!macroend

; 自己掌握页序的调用方（测试壳、插件安装器）用这个：钩子和页在同一个宏里，抢不走。
!macro AWD_UI_PAGE_INSTFILES
  !insertmacro AWD_UI_INSTFILES_HOOK
  !insertmacro MUI_PAGE_INSTFILES
!macroend

!macro AWD_UI_PAGE_FINISH
  Page custom AwdFinishCreate
!macroend

!else
; 卸载器构建通道：一键 UI 只作用于安装器，卸载器保持 MUI 经典页（仍受益于 DPI 声明）
!macro AWD_UI_PAGE_WELCOME
!macroend
!macro AWD_UI_INSTFILES_HOOK
!macroend
!macro AWD_UI_PAGE_FINISH
!macroend
!endif

!endif # AWD_ONECLICK_UI_INCLUDED
