<script>
import { getSessionId } from '@/utils/auth.js'
import { openLocalRootPath, openLocalFilePath } from '@/utils/ideOpen.js'
import { track } from '@/utils/telemetryClient.js'
import { host, isDesktopHost } from '@/services/host.js'
import { mountFeedbackWidget } from '@/utils/feedbackWidget.js'
import { mountRecordingIndicator } from '@/utils/recordingIndicator.js'
import { initWindowChrome } from '@/utils/windowChrome.js'
import { initAppMenuBridge } from '@/utils/appMenuBridge.js'
import { getAppLanguage, APP_LANGUAGE_EVENT } from '@/utils/appLanguage.js'
import { saveAppLanguageRemote } from '@/services/api.js'

export default {
  onLaunch: function () {
    console.log('App Launch')
    // 无边框窗口适配：挂平台/全屏 class + 页面级拖拽条。最早做，
    // 免得启动页先渲染出来再跳一下（见 utils/windowChrome.js）。
    initWindowChrome()
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
    // 埋点：页面路由唯一收口（全仓 50 处 navigateTo/reLaunch 直调，拦截器一处全覆盖）；
    // 只记页面路径枚举（pages.json 里的 13 个页面，2026-08-08 三级导航加了
    // project-list 与 project-home 两页），query 参数不采集
    const navTrack = (routeType) => ({
      invoke(args) {
        try {
          const page = String((args && args.url) || '').split('?')[0]
          if (page) track('ui.nav', { page, branch: routeType })
        } catch (e) { /* 静默 */ }
        return true
      }
    })
    ;['navigateTo', 'redirectTo', 'reLaunch', 'switchTab'].forEach((t) => {
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
/* AI Workdeck Global Overrides */
/* uni.showModal Style Override (Web/H5) */
uni-modal .uni-modal {
    border-radius: 12px;
    box-shadow: 0 12px 32px rgba(18, 52, 77, 0.16);
    overflow: hidden;
}

uni-modal .uni-modal__title {
    font-size: 18px;
    font-weight: 600;
    color: #1A5336; /* Forest Green */
    padding-top: 24px;
}

uni-modal .uni-modal__content {
    font-size: 15px;
    color: #6C757D;
    padding-bottom: 24px;
}

uni-modal .uni-modal__ft {
    border-top: 1px solid #E9ECEF;
}

uni-modal .uni-modal__btn {
    font-size: 16px;
    font-weight: 500;
}

/* Cancel Button */
uni-modal .uni-modal__btn_default {
    color: #6C757D !important;
}

/* Confirm Button */
uni-modal .uni-modal__btn_primary {
    color: #5BD197 !important; /* Mint Green */
    font-weight: 600;
}

/* Toast Override */
uni-toast .uni-toast {
   background: rgba(26, 83, 54, 0.9);
   border-radius: 8px;
}
uni-toast .uni-toast__content {
    color: #fff;
}

/* ============ 无边框窗口：给系统窗口控件让位 ============
   桌面壳去掉了系统标题栏（main.js titleBarStyle: 'hidden'），窗口控件浮在
   网页内容上。类名由 utils/windowChrome.js 挂在 documentElement 上。
   设计见 docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md */

/* 选择器一律写成 html.is-xxx：组件的 scoped 样式带 [data-v-] 属性选择器，
   跟 `.is-mac .project-header` 同权重（0,2,0），而它注入得更晚会赢。
   加上元素选择器变成 (0,2,1) 才压得住 project-overview.scss 的 `padding: 0 18px`。 */

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
html.is-desktop .project-header .icon-btn {
    -webkit-app-region: no-drag;
}

/* mac：三颗交通灯占住左上角（右缘约 72px，留 16px 呼吸） */
html.is-mac .project-header {
    padding-left: 88px;
}
/* 全屏时交通灯隐藏，留白归零 */
html.is-mac.is-fullscreen .project-header {
    padding-left: 18px;
}

/* win：右上角原生最小化/最大化/关闭 */
html.is-win .project-header {
    padding-right: 148px;
}

/* 没有自己顶栏的页面（登录、项目列表、设置…）的拖拽条。
   工作台的 .project-header（z-index 200）盖在它上面，那边不受影响。 */
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

/* ---- 逐页让位：只加在真机走查确认「左上角本来就有内容」的页面上 ----
   不做全局 padding 注入——13 个页面布局差异太大，全局注入必然出回归。
   走查方法与结论见 spec §3.4；改这几个页面的顶部结构时记得回来看一眼。

   走查结论（1400x900，mac）：13 页里只有 login / variable-library /
   plugin-market 三页的左上角压着实体内容，其余页面顶部是空白背景，
   拖拽条覆盖率 100%。 */

/* login：整条 top-nav 就是这一页的标题栏 */
html.is-desktop .top-nav {
    -webkit-app-region: drag;
}
html.is-desktop .top-nav .nav-item,
html.is-desktop .top-nav .nav-logo {
    -webkit-app-region: no-drag;
}
html.is-mac .top-nav {
    padding-left: 96px;
}
html.is-win .top-nav {
    padding-right: 160px;
}

/* variable-library：顶部卡片里的项目名紧贴左边 */
html.is-mac .page-variable-library .header-card {
    padding-left: 96px;
}

/* plugin-market：hero 的分类标签在最上一行 */
html.is-desktop .page-plugin-market .hero {
    padding-top: 38px;
}
</style>
