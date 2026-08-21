// project-overview.vue 的文件暂存区：__staging_area__ 目录的懒建/加载、拖入与移出
// （移出按 stagingOriginalParents 回原目录，原目录已删则退回根目录）、清空与折叠。
// 经展开进组件 methods（纯搬移，Phase 2 外置），`this` 即 project-overview 页面实例。

import { getProjectFiles, createFolder, batchMoveFiles, getStageUsage } from '@/services/api.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { accountPageUrl } from '@/utils/siteLinks.js'

export const stagingAreaMethods = {
    // 免费额度用量（顶部用量条的数据源）。取不到就当作「不限制」处理——
    // 旧后端没有该端点，不能因此让用量条显示成一堆 0/0。
    async loadStagingUsage() {
      if (!this.stagingFolderId) return
      try {
        const usage = await getStageUsage(this.projectId, this.stagingFolderId)
        this.stagingUsage = usage && typeof usage === 'object' ? usage : null
      } catch (e) {
        this.stagingUsage = null
      }
    },
    async ensureStagingFolder() {
      if (this.stagingFolderId) return
      try {
        // Changed from .stagezone to __staging_area__ to avoid dotfile filtering
        const folderName = '__staging_area__'
        console.log('EnsureStaging: Fetching root files...')
        const response = await getProjectFiles(this.projectId, null)

        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(response) ? response : (response?.data || [])
        console.log('EnsureStaging: Got files count:', files.length, 'Looking for:', folderName)

        let folder = files.find ? files.find(f => f.name === folderName) : null
        console.log('EnsureStaging: Found folder?', folder ? folder.id : null)

        if (!folder) {
          console.log('EnsureStaging: Creating folder...')
          try {
             const createResp = await createFolder(this.projectId, null, folderName)
             // createFolder may also return wrapped response
             folder = createResp?.data || createResp
          } catch(err) {
             console.error('EnsureStaging: Create failed:', err)

             // Retry: Maybe it exists now (concurrency) or previous fetch missed it?
             // Or maybe create failed because it already exists.
             console.log('EnsureStaging: Retrying fetch...')
             const retryResponse = await getProjectFiles(this.projectId, null)
             const retryFiles = Array.isArray(retryResponse) ? retryResponse : (retryResponse?.data || [])
             folder = retryFiles.find ? retryFiles.find(f => f.name === folderName) : null
          }
        }

        if (folder) {
           this.stagingFolderId = folder.id
           console.log('EnsureStaging: stagingFolderId set to:', folder.id)
        }
      } catch (e) {
        console.error('Failed to ensure staging folder:', e)
      }
    },
    async loadStagingFiles() {
      console.log('LoadStaging: Starting...')
      if (!this.stagingFolderId) await this.ensureStagingFolder()
      if (!this.stagingFolderId) {
          console.warn('LoadStaging: No folder ID, aborting.')
          return
      }
      try {
        console.log('LoadStaging: Fetching files for ID:', this.stagingFolderId)
        const response = await getProjectFiles(this.projectId, this.stagingFolderId)
        // Normalize response: API returns { code: 0, data: [...] } or possibly just array
        const files = Array.isArray(response) ? response : (response?.data || [])
        console.log('LoadStaging: Got files count:', files.length)
        this.stagingFiles = files
        // Debug: log file names
        if (files.length > 0) {
          console.log('LoadStaging: Files:', files.map(f => f.name).join(', '))
        }
        this.loadStagingUsage()
      } catch (e) {
        console.error('Failed to load staging files:', e)
        uni.showToast({ title: this.$t('workbenchOps.loadStagingFailed'), icon: 'none' })
      }
    },
    async onStagingDrop(files) {
      console.log('onStagingDrop received:', files)

      // Critical Fix: Ensure files is an array.
      // If event object passed accidentally, return.
      if (!files) return

      // Check if this is a native DOM Event object (not a Vue emitted payload)
      // This can happen if the native @drop event bubbles up
      if (files instanceof Event || (files.type && files.target && files.currentTarget)) {
        // Silently ignore - the actual files should be processed by FileStagingArea's onDrop
        return
      }

      if (!Array.isArray(files)) {
         // Try to recover if it's a single file object
         if (files.id && files.name) {
             files = [files]
         } else {
             console.warn('onStagingDrop: Unexpected argument format, ignoring:', typeof files)
             return
         }
      }

      if (files.length === 0) return
      if (!this.stagingFolderId) await this.ensureStagingFolder()

      const fileIds = files.map(f => f.id)

      // 记录每个文件进入暂存区前的原始 parentId
      for (const file of files) {
        if (file.id && file.parentId !== undefined) {
          this.stagingOriginalParents[file.id] = file.parentId
        }
      }

      try {
        // Move to staging folder
        await batchMoveFiles(this.projectId, fileIds, this.stagingFolderId)

        // Reload staging files
        await this.loadStagingFiles()
        // Reload file tree (to remove from original location)
        if (this.$refs.fileTree) {
            this.$refs.fileTree.loadFiles()
        }

        // Auto-pin
        this.stagingPinned = true
        uni.showToast({ title: this.$t('workbenchOps.addedToStaging'), icon: 'success' })
      } catch (e) {
        if (e && e.quotaExceeded) {
          // 免费额度拦截：后端一个文件都没移动，缓存区里的存量原样保留。
          // 用 modal 而不是 toast——这条提示要说清「已有文件不会被删除」，toast 放不下。
          this.stagingPinned = true
          this.loadStagingUsage()
          uni.showModal({
            title: this.$t('workbenchOps.stagingFullTitle'),
            content: e.message,
            confirmText: this.$t('workbenchOps.learnMore'),
            cancelText: this.$t('workbenchOps.okKnown'),
            success: (r) => {
              if (r.confirm) openExternalUrl(accountPageUrl())
            }
          })
          return
        }
        console.error('Failed to move files to staging:', e)
        uni.showToast({ title: this.$t('workbenchOps.addToStagingFailed'), icon: 'none' })
      }
    },
    // 真实 OS 文件拖拽（Finder/Explorer/桌面）落进暂存区（FileStagingArea.onDrop 的
    // 第 3 分支：dataTransfer.files 是原生 File 列表，而不是"项目里已有文件"的引用）。
    // 与 onStagingDrop（移动已有文件）不同——这些文件磁盘上有、项目里没有，得先真
    // 传上去。复用 FileTree 的上传队列（$refs.fileTree.confirmUpload 读的就是
    // selectedFiles/selectedUploadParent，上传对话框本身也只是把这两个字段填好再
    // 调它）而不是另起一套上传逻辑——分片续传/并发控制那一整套已经在那里踩过坑。
    async onStagingDropFiles(fileList) {
      if (!fileList || fileList.length === 0) return
      if (!this.stagingFolderId) await this.ensureStagingFolder()
      if (!this.stagingFolderId) {
        uni.showToast({ title: this.$t('workbenchOps.addToStagingFailed'), icon: 'none' })
        return
      }
      const fileTree = this.$refs.fileTree
      if (!fileTree || typeof fileTree.confirmUpload !== 'function') {
        console.warn('[ProjectOverview] onStagingDropFiles: fileTree ref not ready')
        return
      }
      fileTree.selectedFiles = Array.from(fileList)
      fileTree.selectedUploadParent = this.stagingFolderId
      fileTree.isFolderUpload = false
      this.stagingPinned = true
      await fileTree.confirmUpload()
      uni.showToast({ title: this.$t('workbenchOps.addedToStaging'), icon: 'none' })
      // confirmUpload 只是把上传队列发出去，不等它落盘完成（进度另有 FileTree 自己的
      // 批量上传状态条）。这里做的是尽力而为的补拉，不是完成通知——暂存列表另有
      // 独立状态（stagingFiles），FileTree 完成一批后只会刷新它自己的树，不知道
      // 目标是暂存区。真正做到"传完立即精确出现"需要给 confirmUpload 加完成回调，
      // 超出这条缺陷的范围；先用两次延时补拉覆盖常见的小文件场景。
      this.loadStagingFiles()
      setTimeout(() => this.loadStagingFiles(), 2500)
    },
    handleStagingClear() {
       // Optional: Move all back to root? Or just clear list (which creates orphans in .stagezone)?
       // User didn't specify, but "Clear" usually means empty the list.
       // Logic: Move all files in staging back to root.
       if (this.stagingFiles.length === 0) return
       const ids = this.stagingFiles.map(f => f.id)

       batchMoveFiles(this.projectId, ids, null).then(() => {
          this.loadStagingFiles()
          this.$refs.fileTree.loadFiles()
       })
    },
    async handleStagingRemove(id) {
       // 恢复到文件原来的目录，如果找不到则移动到根目录
       if (!this.stagingFolderId) return

       // 获取原始 parentId（如果有记录的话）
       const originalParentId = this.stagingOriginalParents[id]
       // 注意：originalParentId 可能是 null（原本就在根目录）或 undefined（未记录）
       // 两者都应该移动到根目录
       const targetParentId = originalParentId !== undefined ? originalParentId : null

       try {
         await batchMoveFiles(this.projectId, [id], targetParentId)
         // 清理该文件的原始目录记录
         delete this.stagingOriginalParents[id]
         await this.loadStagingFiles()
         this.$refs.fileTree.loadFiles()
       } catch (e) {
         console.error('Failed to remove from staging:', e)
         // 如果恢复失败（可能原目录已删除），尝试移动到根目录
         if (targetParentId !== null) {
           try {
             await batchMoveFiles(this.projectId, [id], null)
             delete this.stagingOriginalParents[id]
             await this.loadStagingFiles()
             this.$refs.fileTree.loadFiles()
             uni.showToast({ title: this.$t('workbenchOps.movedToRootFallback'), icon: 'none' })
             return
           } catch (e2) {
             console.error('Fallback to root also failed:', e2)
           }
         }
         uni.showToast({ title: this.$t('workbenchOps.removeFromStagingFailed'), icon: 'none' })
       }
    },
    handleStagingCompare(files) {
        if (!files || files.length !== 2) return;
        this.onCompareDocumentsRequest(files);
    },
    handleStagingOpen(file) {
      if (!file) return
      this.openFile(file)
    },
    handleStagingCollapse() {
      // User explicitly collapsed staging area
      this.stagingPinned = false
      this.stagingManuallyCollapsed = true
    },
}
