<script>
import { getSessionId } from '@/utils/auth.js'
import { openFolderFlow, openFileFlow, openLocalRootPath, openLocalFilePath } from '@/utils/ideOpen.js'

export default {
  onLaunch: function () {
    console.log('App Launch')
    // IDE 化应用菜单动作（桌面壳菜单栏 文件→打开文件夹/打开文件/新建/最近打开）。
    // App 级注册一次，天然避开 project-overview 的页面栈多实例问题。
    if (typeof window !== 'undefined' && window.checkbaDesktop
        && window.checkbaDesktop.menu && window.checkbaDesktop.menu.onAction) {
      window.checkbaDesktop.menu.onAction(async (data) => {
        const action = data && data.action
        if (!action) return
        if (!getSessionId()) {
          uni.showToast({ title: '请先登录', icon: 'none' })
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
          uni.showToast({ title: (e && e.message) || '操作失败', icon: 'none' })
        }
      })
    }
    // IDE 化：拖一个文件夹到窗口任意位置 = 打开为项目（capture 段拦截，
    // 避免文件上传等既有 drop 区把「文件夹」误当文件收走；单个目录才接管）
    if (typeof window !== 'undefined' && window.checkbaDesktop
        && window.checkbaDesktop.fs && window.checkbaDesktop.fs.getPathForFile) {
      window.addEventListener('dragover', (e) => { e.preventDefault() }, false)
      window.addEventListener('drop', (e) => {
        try {
          const items = e.dataTransfer && e.dataTransfer.items
          if (!items || items.length !== 1) return
          const entry = items[0].webkitGetAsEntry && items[0].webkitGetAsEntry()
          if (!entry || !entry.isDirectory) return
          e.preventDefault()
          e.stopPropagation()
          const path = window.checkbaDesktop.fs.getPathForFile(e.dataTransfer.files[0])
          if (!path) return
          if (!getSessionId()) {
            uni.showToast({ title: '请先登录', icon: 'none' })
            return
          }
          uni.showModal({
            title: '打开文件夹',
            content: `把「${entry.name}」作为项目打开？`,
            confirmText: '打开',
            success: (r) => {
              if (r.confirm) {
                openLocalRootPath(path).catch((err) => {
                  uni.showToast({ title: (err && err.message) || '打开失败', icon: 'none' })
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
