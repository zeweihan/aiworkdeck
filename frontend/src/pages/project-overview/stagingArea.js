// project-overview.vue 的文件暂存区：__staging_area__ 目录的懒建/加载、拖入与移出
// （移出按 stagingOriginalParents 回原目录，原目录已删则退回根目录）、清空与折叠。
// 经展开进组件 methods（纯搬移，Phase 2 外置），`this` 即 project-overview 页面实例。

import { getProjectFiles, createFolder, batchMoveFiles } from '@/services/api.js'

export const stagingAreaMethods = {
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
      } catch (e) {
        console.error('Failed to load staging files:', e)
        uni.showToast({ title: '加载暂存区文件失败', icon: 'none' })
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
        uni.showToast({ title: '已加入暂存区', icon: 'success' })
      } catch (e) {
        console.error('Failed to move files to staging:', e)
        uni.showToast({ title: '加入暂存区失败', icon: 'none' })
      }
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
             uni.showToast({ title: '原目录已不存在，已移至根目录', icon: 'none' })
             return
           } catch (e2) {
             console.error('Fallback to root also failed:', e2)
           }
         }
         uni.showToast({ title: '移出暂存区失败', icon: 'none' })
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
