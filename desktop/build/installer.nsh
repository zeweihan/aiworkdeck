; AI WorkDeck 桌面端 NSIS 定制（electron-builder assisted 安装器的 include 钩子）。
; 只做一件事：加回 Welcome 页——MUI2 的品牌侧栏大图只挂在 Welcome/Finish 两页上，
; electron-builder 默认不出 Welcome 页，等于侧栏图只在最后一页闪现一次。
; 页面文案一律用 NSIS/MUI 官方本地化字符串（跟随系统语言，中英文都专业），
; 这里刻意不硬编码任何语言的字符串。
!macro customWelcomePage
  !insertmacro MUI_PAGE_WELCOME
!macroend
