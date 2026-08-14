<script>
import { getSessionId } from '@/utils/auth.js'
import { openFolderFlow, openFileFlow, openLocalRootPath, openLocalFilePath } from '@/utils/ideOpen.js'
import { track } from '@/utils/telemetryClient.js'
import { host, isDesktopHost } from '@/services/host.js'
import { mountFeedbackWidget } from '@/utils/feedbackWidget.js'
import { getAppLanguage, APP_LANGUAGE_EVENT } from '@/utils/appLanguage.js'
import { saveAppLanguageRemote } from '@/services/api.js'

export default {
  onLaunch: function () {
    console.log('App Launch')
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
    // IDE 化应用菜单动作（桌面壳菜单栏 文件→打开文件夹/打开文件/新建/最近打开）。
    // App 级注册一次，天然避开 project-overview 的页面栈多实例问题。
    if (host.menu && host.menu.onAction) {
      host.menu.onAction(async (data) => {
        const action = data && data.action
        if (!action) return
        if (!getSessionId()) {
          uni.showToast({ title: this.$t('shell.pleaseLoginFirst'), icon: 'none' })
          return
        }
        try {
          if (action === 'open-folder') {
            await openFolderFlow()
          } else if (action === 'open-file') {
            await openFileFlow()
          } else if (action === 'create-folder') {
            uni.reLaunch({ url: '/pages/newproject/index?auto=create-folder' })
          } else if (action === 'open-recent' && data.projectId) {
            uni.reLaunch({ url: `/pages/project-overview/project-overview?id=${data.projectId}` })
          } else if (action === 'open-path' && data.path) {
            // Dock/Finder「打开方式」进来的路径（主进程已判好目录/文件）
            if (data.isDirectory) await openLocalRootPath(data.path)
            else await openLocalFilePath(data.path)
          }
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
</style>
