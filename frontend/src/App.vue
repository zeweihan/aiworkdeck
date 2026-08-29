<script>
import { getSessionId } from '@/utils/auth.js'
import { openLocalRootPath, openLocalFilePath } from '@/utils/ideOpen.js'
import { track } from '@/utils/telemetryClient.js'
import { host, isDesktopHost } from '@/services/host.js'
import { mountFeedbackWidget } from '@/utils/feedbackWidget.js'
import { mountRecordingIndicator } from '@/utils/recordingIndicator.js'
import { initWindowChrome, refreshDragStrip } from '@/utils/windowChrome.js'
import { mountGlobalBack, refreshGlobalBack } from '@/utils/globalBack.js'
import { initAppMenuBridge } from '@/utils/appMenuBridge.js'
import { getAppLanguage, APP_LANGUAGE_EVENT } from '@/utils/appLanguage.js'
import { initAppTheme } from '@/utils/appTheme.js'
import { saveAppLanguageRemote } from '@/services/api.js'

export default {
  onLaunch: function () {
    console.log('App Launch')
    // 无边框窗口适配：挂平台/全屏 class + 页面级拖拽条。最早做，
    // 免得启动页先渲染出来再跳一下（见 utils/windowChrome.js）。
    initWindowChrome()
    // 外观主题：也要最早——晚一步首屏会先白一下再变深（见 utils/appTheme.js）
    initAppTheme()
    // 应用语言：最早读一次（首启在此完成猜测并持久化），并把镜像写透到
    // 桌面主进程（菜单等原生文案）与后端 system_setting（prompt/文案语言）。
    // 之后语言切换（设置页 setAppLanguage）经 APP_LANGUAGE_EVENT 走同一条同步链。
    const syncLanguageMirrors = (lang) => {
      try { if (host.appLanguage && host.appLanguage.set) host.appLanguage.set(lang) } catch (e) { /* ignore */ }
      // fire-and-forget：后端没起来也不能拦启动，下次切换/启动会再补。
      // 浏览器态未登录时不发——POST 会回「未登录」，request 包装器据此清会话
      // 并 reLaunch 登录页，启动链会被这条后台同步搅乱；桌面 local-mode 恒可发。
      if (isDesktopHost() || getSessionId()) {
        try { saveAppLanguageRemote(lang).catch(() => {}) } catch (e) { /* ignore */ }
      }
    }
    syncLanguageMirrors(getAppLanguage())
    try { uni.$on(APP_LANGUAGE_EVENT, syncLanguageMirrors) } catch (e) { /* ignore */ }
    // 常驻反馈浮窗：挂在页面树之外，全应用一个实例（见 utils/feedbackWidget.js）
    mountFeedbackWidget()
    // 会议「录音中」浮动指示器：同一模式，录音时才显形（见 utils/recordingIndicator.js）
    mountRecordingIndicator()
    // 全局返回键：页面栈深度 > 1 时出现在顶部拖拽条里（见 utils/globalBack.js）
    mountGlobalBack()
    // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
    // 只记页面路径枚举（pages.json 里的 13 个页面，2026-08-08 三级导航加了
    // project-list 与 project-home 两页，2026-08-20 加了 calendar 全局日历页，
    // 2026-08-27 首启向导页下线），query 参数不采集
    const navTrack = (routeType) => ({
      invoke(args) {
        try {
          const page = String((args && args.url) || '').split('?')[0]
          if (page) track('ui.nav', { page, branch: routeType })
        } catch (e) { /* 静默 */ }
        return true
      },
      // 全局返回键的可见性跟着页面栈走，跳转完成后重算一次（含 navigateBack，
      // 它没有 url、不参与埋点，但会改变栈深度）。
      // 无边框窗口的拖拽条同理：自带顶栏的页面要整条让开，否则那一页顶栏里的
      // 按钮全部点不动（见 utils/windowChrome.js 的 OWN_TITLEBAR_ROUTES）。
      complete() { refreshGlobalBack(); refreshDragStrip() },
    })
    ;['navigateTo', 'redirectTo', 'reLaunch', 'switchTab', 'navigateBack'].forEach((t) => {
      try { uni.addInterceptor(t, navTrack(t)) } catch (e) { /* 静默 */ }
    })
    // 应用菜单：命令表 → 菜单树的下发与动作派发全部收在 appMenuBridge 里。
    // App 级注册一次，天然避开 project-overview 的页面栈多实例问题。
    initAppMenuBridge()
    // Dock/访达「打开方式」进来的路径不是菜单命令，主进程直发，单独接。
    if (host.menu && host.menu.onAction) {
      host.menu.onAction(async (data) => {
        if (!data || data.action !== 'open-path' || !data.path) return
        if (!getSessionId()) {
          uni.showToast({ title: this.$t('shell.pleaseLoginFirst'), icon: 'none' })
          return
        }
        try {
          if (data.isDirectory) await openLocalRootPath(data.path)
          else await openLocalFilePath(data.path)
        } catch (e) {
          uni.showToast({ title: (e && e.message) || this.$t('shell.menuActionFailed'), icon: 'none' })
        }
      })
    }
    // IDE 化：拖一个文件夹到窗口任意位置 = 打开为项目（capture 段拦截，
    // 避免文件上传等既有 drop 区把「文件夹」误当文件收走；单个目录才接管）
    if (host.fs && host.fs.getPathForFile) {
      window.addEventListener('dragover', (e) => { e.preventDefault() }, false)
      window.addEventListener('drop', (e) => {
        try {
          const items = e.dataTransfer && e.dataTransfer.items
          if (!items || items.length !== 1) return
          const entry = items[0].webkitGetAsEntry && items[0].webkitGetAsEntry()
          if (!entry || !entry.isDirectory) return
          e.preventDefault()
          e.stopPropagation()
          const path = host.fs.getPathForFile(e.dataTransfer.files[0])
          if (!path) return
          if (!getSessionId()) {
            uni.showToast({ title: this.$t('shell.pleaseLoginFirst'), icon: 'none' })
            return
          }
          uni.showModal({
            title: this.$t('shell.openFolderTitle'),
            content: this.$t('shell.openFolderConfirm', { name: entry.name }),
            confirmText: this.$t('shell.open'),
            success: (r) => {
              if (r.confirm) {
                openLocalRootPath(path).catch((err) => {
                  uni.showToast({ title: (err && err.message) || this.$t('shell.openFolderFailed'), icon: 'none' })
                })
              }
            },
          })
        } catch (err) {
          console.warn('folder drop failed:', err)
        }
      }, true)
    }
  },
  onShow: function () {
    console.log('App Show')
  },
  onHide: function () {
    console.log('App Hide')
  },
}
</script>

<style>
/* AI WorkDeck Global Overrides */
/* uni.showModal Style Override (Web/H5) */
uni-modal .uni-modal {
    border-radius: 12px;
    box-shadow: 0 12px 32px rgba(18, 52, 77, 0.16);
    overflow: hidden;
}

uni-modal .uni-modal__title {
    font-size: 18px;
    font-weight: 600;
    color: var(--awd-accent-text); /* Forest Green */
    padding-top: 24px;
}

uni-modal .uni-modal__content {
    font-size: 15px;
    color: var(--awd-text-2);
    padding-bottom: 24px;
}

uni-modal .uni-modal__ft {
    border-top: 1px solid var(--awd-border);
}

uni-modal .uni-modal__btn {
    font-size: 16px;
    font-weight: 500;
}

/* Cancel Button */
uni-modal .uni-modal__btn_default {
    color: var(--awd-text-2) !important;
}

/* Confirm Button */
uni-modal .uni-modal__btn_primary {
    color: var(--awd-mint) !important; /* Mint Green */
    font-weight: 600;
}

/* Toast Override */
uni-toast .uni-toast {
   background: var(--awd-accent);
   border-radius: 8px;
}
uni-toast .uni-toast__content {
    color: var(--awd-text-on-accent);
}

/* ============ 颜色语义令牌（浅色/深色两套取值） ============
   改造前全前端有 3950 处硬编码颜色、435 种色值，文字/背景/边框各自混着四套
   并行灰阶（Bootstrap 的 #6C757D/#ADB5BD、Tailwind slate 的 #64748B/#94A3B8、
   Tailwind gray 的 #6B7280/#D1D5DB，外加 #333/#666/#999 遗留）。收敛成下面这
   一套语义令牌：**按用途取名，不按色值取名**——深色模式只是同一批名字换一组值。

   用 CSS 自定义属性而非 scss 变量：各组件的 <style scoped> 有的写 scss 有的写
   纯 css，自定义属性两边都能用、天然穿透 scoped，且能在运行时整体切换。

   切换机制：utils/theme.js 在 documentElement 上挂 data-theme="light|dark"
   （「跟随系统」解析成其中之一后再挂，页面里不出现第三种状态）。 */
html,
html[data-theme='light'] {
    /* 表面：底色 → 卡片 → 悬停/凹陷 → 更重的填充 */
    --awd-bg: #F8F9FA;
    --awd-surface: #FFFFFF;
    --awd-surface-2: #F1F3F5;
    --awd-surface-3: #E9ECEF;
    /* 文字三阶 + 强调底上的反白 */
    --awd-text: #2C3338;
    --awd-text-2: #6C757D;
    --awd-text-3: #ADB5BD;
    --awd-text-on-accent: #FFFFFF;
    /* 边框三阶 */
    --awd-border-subtle: #F1F3F5;
    --awd-border: #E9ECEF;
    --awd-border-strong: #CBD5E1;
    /* 品牌：accent 作底、accent-text 作字——深色下两者取值不同，必须分开 */
    --awd-accent: #1A5336;
    --awd-accent-hover: #164429;
    --awd-accent-text: #1A5336;
    --awd-accent-soft: #E6F9F0;
    --awd-accent-wash: rgba(26, 83, 54, 0.04);
    --awd-mint: #5BD197;
    /* mint 亮绿底上的文字：两个主题下 mint 都是亮色，配对文字恒为深墨 */
    --awd-text-on-mint: #14301F;
    /* 语义状态 */
    --awd-danger: #E74C3C;
    --awd-danger-text: #C53030;
    --awd-danger-soft: #FEF2F2;
    --awd-warning: #F59E0B;
    --awd-warning-text: #B45309;
    --awd-warning-soft: #FFF7ED;
    --awd-info: #3B82F6;
    --awd-info-text: #1D4ED8;
    --awd-info-soft: #EFF6FF;
    /* 阴影与遮罩 */
    --awd-shadow-sm: 0 1px 2px rgba(18, 52, 77, 0.06);
    --awd-shadow-md: 0 4px 16px rgba(18, 52, 77, 0.08);
    --awd-shadow-lg: 0 12px 32px rgba(18, 52, 77, 0.16);
    --awd-overlay: rgba(0, 0, 0, 0.45);
    /* 空态光晕（工作区/首屏那圈柔光）：浅色是品牌绿薄雾；深色不能沿用——
       半透明绿铺在深底上会变成一团脏绿雾，改成不带色相的极淡提亮 */
    --awd-halo-1: #E6F9F0;
    --awd-halo-2: rgba(26, 83, 54, 0.04);
    /* 整页斜向柔光的落点色（项目列表页/设置页） */
    --awd-halo-page: #E6F9F0;
    /* 毛玻璃表面（登录页那种半透明卡片） */
    --awd-glass: rgba(255, 255, 255, 0.75);
    --awd-glass-border: rgba(255, 255, 255, 0.5);
    /* 编辑器纸外工作区（纸张本身由 LOWA 引擎渲染，永远是纸白，不参与主题） */
    --awd-canvas: #F1F3F5;
    color-scheme: light;
}

html[data-theme='dark'] {
    /* 阶差要比浅色更舍得拉开：深色下人眼对低亮度差极不敏感，页面与卡片只差
       3-4 个灰阶时卡片会整个"沉进"背景里（第一版 #17191C/#1E2125 就是这样，
       维护者实测反馈「卡片边框也看不见」）。现在页面最深、卡片明显抬起、
       边框独立成一档，三者互相认得出。 */
    --awd-bg: #131518;
    --awd-surface: #1C2024;
    --awd-surface-2: #23272C;
    --awd-surface-3: #2C3137;
    --awd-text: #E7EAEC;
    --awd-text-2: #A6ADB4;
    --awd-text-3: #767E86;
    /* 深色下 accent 底仍是深绿，反白文字对比度约 5.4:1，语义与浅色一致 */
    --awd-text-on-accent: #FFFFFF;
    --awd-border-subtle: #23272C;
    --awd-border: #373D44;
    --awd-border-strong: #4B525A;
    --awd-accent: #24714A;
    --awd-accent-hover: #2E8B5A;
    /* 绿字直接用森林绿在深底上读不出来，换高亮薄荷（对比度约 8:1） */
    --awd-accent-text: #6FD9A3;
    --awd-accent-soft: rgba(91, 209, 151, 0.13);
    --awd-accent-wash: rgba(91, 209, 151, 0.06);
    --awd-mint: #5BD197;
    --awd-text-on-mint: #14301F;
    --awd-danger: #E05A4E;
    --awd-danger-text: #FF8A80;
    --awd-danger-soft: rgba(231, 76, 60, 0.15);
    --awd-warning: #D9971F;
    --awd-warning-text: #F0B23C;
    --awd-warning-soft: rgba(245, 158, 11, 0.14);
    --awd-info: #3B6FD4;
    --awd-info-text: #7FAEF9;
    --awd-info-soft: rgba(59, 130, 246, 0.15);
    /* 深色下阴影靠纯黑加重，浅色那套带蓝的柔光在深底上等于没有 */
    --awd-shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.4);
    --awd-shadow-md: 0 4px 16px rgba(0, 0, 0, 0.45);
    --awd-shadow-lg: 0 12px 32px rgba(0, 0, 0, 0.55);
    --awd-overlay: rgba(0, 0, 0, 0.6);
    --awd-halo-1: rgba(255, 255, 255, 0.045);
    --awd-halo-2: rgba(255, 255, 255, 0);
    --awd-halo-page: #171A1E;
    --awd-glass: rgba(28, 32, 36, 0.74);
    --awd-glass-border: rgba(255, 255, 255, 0.10);
    --awd-canvas: #101214;
    color-scheme: dark;
}

/* 深色下的品牌字标：logo 是深墨字 + 绿标记的透明 PNG，铺在深底上几乎看不见。
   invert + hue-rotate 把明度翻过来、把色相转回去：墨字变浅、绿仍是绿。
   注意作用对象——uni-image 在 H5 下真正显示的是那个 background-image 的 div，
   同级的 <img> 只是隐藏的加载器，只给 img 加滤镜是没有效果的（实测踩过）。 */
html[data-theme='dark'] .awd-brand-logo > div,
html[data-theme='dark'] .awd-brand-logo > img {
    filter: invert(1) hue-rotate(180deg) saturate(1.35) brightness(1.1);
}

/* ============ 无边框窗口：给系统窗口控件让位 ============
   桌面壳去掉了系统标题栏（main.js titleBarStyle: 'hidden'），窗口控件浮在
   网页内容上。类名由 utils/windowChrome.js 挂在 documentElement 上。
   设计见 docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md */

/* 选择器一律写成 html.is-xxx：组件的 scoped 样式带 [data-v-] 属性选择器，
   跟 `.is-mac .project-header` 同权重（0,2,0），而它注入得更晚会赢。
   加上元素选择器变成 (0,2,1) 才压得住 project-overview.scss 的 `padding: 0 18px`。 */

/* ---- 保留区变量：给「让位」一个不打权重官司的表达 ----
   抬权重只赢一轮。`.compact-mode .project-header { padding: 0 16px }` 是 (0,4,0)，
   照样把上面那条 (0,2,1) 的 88px 让位整条吃掉——窗口窄于 1360px 就必然被交通灯
   压住项目名。逐页抬权重是个追不完的坑，所以改成变量：
     · 语义是**距窗口边缘的绝对位置**——顶栏内容的左缘不得早于这个值；
     · 页面在**自己的样式表里**用 `padding-left: max(自己的边距, var(...))` 消费它，
       跟自己的 padding 简写同属一处，不存在谁压谁；
     · 非 mac / 全屏 / 浏览器版下变量是 0，max() 自动退回页面原本的边距。
   只对「紧贴窗口左缘的顶栏」成立；带外边距的页面（admin/个人中心/项目列表都是
   `padding: 40px 24px`）内容本来就落在交通灯下方，不需要让位。 */
html {
    --awd-titlebar-safe-inline-start: 0px;
    --awd-titlebar-safe-inline-end: 0px;
}

/* ---- 左栏面板密度令牌 ----
   左栏那一列宽度只有 260px，每个面板却各写各的边距（EasyVoice 是 16px 页边距 +
   24px 段间距 + 白卡片套白卡片，插件广场是 6-10px + 26px 分组头），并排看密度差一倍。
   基准取插件广场（MarketSidebarPanel）那一套——它是维护者点过名的形态。

   用 CSS 自定义属性而不是 scss 变量，是因为各面板的 `<style scoped>` 有的写 scss
   有的写纯 css，自定义属性两边都能用、且天然穿透 scoped。 */
html {
    --awd-panel-pad-x: 10px;          /* 面板内容左右边距 */
    --awd-panel-gap: 8px;             /* 同组元素间距 */
    --awd-panel-gap-lg: 14px;         /* 跨组间距（不要再用 24px） */
    --awd-panel-sec-h: 26px;          /* 分组头行高 */
    --awd-panel-row-h: 28px;          /* 输入框/按钮等单行控件高度 */
    --awd-panel-radius: 6px;
    --awd-panel-fs-sec: 11px;         /* 分组头字号（配 700 字重） */
    --awd-panel-fs: 12px;             /* 行文字号 */
    --awd-panel-fs-meta: 11px;        /* 次要信息 */
    /* 颜色一律转发到上面的语义令牌，左栏因此自动跟随主题。
       text-2/-3/-4 三阶在浅色下原本是 #495057/#868E96/#ADB5BD，收敛到统一灰阶后
       只剩两阶可用（text-2 与 text-3 合并），差别肉眼几乎不可辨。 */
    --awd-panel-border: var(--awd-border);
    --awd-panel-hover: var(--awd-surface-2);
    --awd-panel-text: var(--awd-text);
    --awd-panel-text-2: var(--awd-text-2);
    --awd-panel-text-3: var(--awd-text-2);
    --awd-panel-text-4: var(--awd-text-3);
    --awd-panel-accent: var(--awd-accent-text);
    --awd-panel-accent-2: var(--awd-mint);
    --awd-panel-accent-wash: var(--awd-accent-wash);
}
/* mac：三颗交通灯占住左上角（右缘约 70px，留 18px 呼吸） */
html.is-mac {
    --awd-titlebar-safe-inline-start: 88px;
}
/* 全屏时交通灯隐藏，保留区归零 */
html.is-mac.is-fullscreen {
    --awd-titlebar-safe-inline-start: 0px;
}
/* win：右上角原生最小化/最大化/关闭 */
html.is-win {
    --awd-titlebar-safe-inline-end: 148px;
}

/* 工作台顶栏就是标题栏：空白处可拖动窗口 */
html.is-desktop .project-header {
    -webkit-app-region: drag;
}

/* 顶栏里每一个能点的东西都必须显式退出拖拽区。
   漏掉一个，那个按钮就点不动——这是本段最容易出错的地方，加控件时记得同步。 */
html.is-desktop .project-header .project-name,
html.is-desktop .project-header .rename-container,
html.is-desktop .project-header .rename-input,
html.is-desktop .project-header .project-switcher,
html.is-desktop .project-header .switcher-mask,
html.is-desktop .project-header .switcher-menu,
html.is-desktop .project-header .project-status-badge,
html.is-desktop .project-header .work-status-chip,
html.is-desktop .project-header .collab-chip,
html.is-desktop .project-header .trial-chip,
html.is-desktop .project-header .header-center,
html.is-desktop .project-header .header-tools,
html.is-desktop .project-header .top-bar-btn,
html.is-desktop .project-header .icon-btn,
/* 右上角头像（2026-08-19 从 rail 底部搬上来；2026-08-27 下拉恢复成两项：
   设置 / 退出登录，dev-board#205——菜单与全屏 mask 都要能点）。 */
html.is-desktop .project-header .header-account,
html.is-desktop .project-header .avatar-btn,
html.is-desktop .project-header .avatar-menu,
html.is-desktop .project-header .avatar-menu-mask {
    -webkit-app-region: no-drag;
}

/* 工作台顶栏与项目概览顶栏的让位写在各自的样式表里（消费上面那两个变量），
   不在这里写——它们都有自己的 padding 简写，写在这里就要打权重官司。
   见 pages/project-overview/project-overview.scss 与
   pages/project-home/project-home.scss 里的 `max(…, var(--awd-titlebar-safe-*))`。 */

/* 没有自己顶栏的页面（项目列表、设置、个人中心…）的拖拽条。

   **自带顶栏的几页由 JS 把它整条藏掉**（utils/windowChrome.js 的
   OWN_TITLEBAR_ROUTES）。原先靠 z-index 让工作台顶栏「盖在它上面」是错的：
   z-index 只管画谁在上面和 DOM 事件命中，而窗口拖拽区是壳按 app-region 另算
   的一套，fixed 的这条带子永远最后合成，会把它底下所有 no-drag 抠洞盖回成
   可拖，于是顶栏里的按钮一个都点不动（v0.18.0 的顶栏死区）。 */
.awd-window-drag-strip {
    display: none;
}
html.is-desktop .awd-window-drag-strip {
    display: block;
    position: fixed;
    top: 0;
    left: 0;
    right: 0;
    height: 38px;
    -webkit-app-region: drag;
    z-index: 1;
}

/* 全局返回键（utils/globalBack.js）。落在拖拽条这条空白带里，紧挨着保留区右缘——
   mac 上就是交通灯右手边，Finder/Safari 里返回键该在的位置。
   z-index 高于拖拽条一档；no-drag 必须写，否则整条带子是 drag 区、按钮点不动。
   display 由 JS 在 inline-flex / none 之间切，这里不要写 display。 */
.awd-global-back {
    position: fixed;
    top: 5px;
    left: max(12px, var(--awd-titlebar-safe-inline-start));
    z-index: 2;
    align-items: center;
    gap: 4px;
    height: 28px;
    padding: 0 11px 0 8px;
    border: 1px solid var(--awd-border);
    border-radius: 14px;
    background: var(--awd-surface);
    color: var(--awd-accent-text);
    font-size: 12px;
    line-height: 1;
    cursor: pointer;
    user-select: none;
    -webkit-app-region: no-drag;
    box-shadow: 0 2px 10px rgba(18, 52, 77, 0.12);
    transition: box-shadow 0.15s ease, border-color 0.15s ease;
}

.awd-global-back:hover {
    border-color: var(--awd-mint);
    box-shadow: 0 4px 16px rgba(26, 83, 54, 0.18);
}

/* ---- 逐页让位 ----
   不做全局 padding 注入——13 个页面布局差异太大，全局注入必然出回归。
   走查方法与结论见 spec §3.4；改这几个页面的顶部结构时记得回来看一眼。

   走查结论（mac）：紧贴窗口左上角、会被交通灯压住的只有五处——
   工作台 .project-header、项目概览 .home-topbar（这两处的让位写在各自的样式表
   里，见上）、login .top-nav、variable-library .header-card、plugin-market .hero。
   其余页面（admin / 个人中心 / 项目列表 / newproject）根容器都是
   `padding: 40px 24px`，内容起点落在交通灯下方，不需要让位。
   注意窄窗口：让位一律用 max(自己的边距, var(--awd-titlebar-safe-*)) 表达，
   不要写死像素——写死的那版在全屏下会留一段莫名其妙的缩进。 */

/* login：整条 top-nav 就是这一页的标题栏 */
html.is-desktop .top-nav {
    -webkit-app-region: drag;
}
html.is-desktop .top-nav .nav-item,
html.is-desktop .top-nav .nav-logo {
    -webkit-app-region: no-drag;
}
html.is-desktop .top-nav {
    padding-left: max(48px, var(--awd-titlebar-safe-inline-start));
    padding-right: max(48px, var(--awd-titlebar-safe-inline-end));
}

/* project-home 薄壳页：这条 52px 顶栏就是这一页的标题栏。
   它在 OWN_TITLEBAR_ROUTES 里，body 级拖拽条会让开，拖拽由它自己承担；
   两个出口按钮照例要显式退出拖拽区，否则又是一对点不动的按钮。 */
html.is-desktop .home-topbar {
    -webkit-app-region: drag;
}
html.is-desktop .home-topbar .home-back,
html.is-desktop .home-topbar .home-enter {
    -webkit-app-region: no-drag;
}

/* variable-library：顶部卡片里的项目名紧贴左边 */
html.is-desktop .page-variable-library .header-card {
    padding-left: max(8px, var(--awd-titlebar-safe-inline-start));
}

/* plugin-market：hero 的分类标签在最上一行 */
html.is-desktop .page-plugin-market .hero {
    padding-top: 38px;
}
</style>
