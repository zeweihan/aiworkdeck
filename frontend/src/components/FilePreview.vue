<template>
  <view class="file-preview">
    <view v-if="!file" class="preview-placeholder">
      <text>请从左侧选择文件进行预览</text>
    </view>
    <view v-else class="preview-content">
      <!-- 文件信息头部 -->
      <view class="preview-header">
        <view class="preview-title-row">
          <text class="preview-title">{{ file.name }}</text>
          <button
            v-if="canEdit"
            class="btn-edit"
            type="primary"
            size="mini"
            @tap="handleEdit"
          >
            编辑
          </button>
        </view>
        <view class="preview-meta">
          <text class="meta-item" v-if="file.fileType">类型：{{ file.fileType }}</text>
          <text class="meta-item" v-if="file.fileSize">大小：{{ formatFileSize(file.fileSize) }}</text>
        </view>
      </view>

      <!-- 预览内容区域 -->
      <view class="preview-body">
        <!-- Word 文档零配置只读渲染：docx-preview 本地解析，无需任何密钥，数据不出本机（承接 #18 T6） -->
        <view v-if="isWord && useDocxPreview && !docxRenderFailed" class="preview-docx">
          <view v-if="docxLoading" class="docx-loading"><text>正在渲染文档…</text></view>
          <view ref="docxContainer" class="docx-host"></view>
        </view>

        <!-- PPTX 零配置只读渲染：pptx-preview 本地解析（自建 LOWA 引擎无 Impress
             模块，演示文稿由前端渲染承接） -->
        <view v-else-if="isPptx && usePptxPreview && !pptxRenderFailed" class="preview-pptx">
          <view v-if="pptxLoading" class="docx-loading"><text>正在渲染演示文稿…</text></view>
          <view ref="pptxContainer" class="pptx-host"></view>
        </view>

        <!-- 其余 Office 文件（ppt 二进制等，或渲染失败/非 H5）：暂不支持在线预览（#79，
             WPS 预览回退已移除） -->
        <view v-else-if="isOffice" class="preview-unsupported">
          <text>该文件暂不支持在线预览</text>
          <text class="preview-hint">文件类型: {{ file.fileType || '未知' }}，可下载后在本地打开</text>
          <button class="btn-download" type="default" size="mini" @tap="handleDownload">
            下载文件
          </button>
        </view>

        <!-- PDF 预览：本地 blob 由浏览器/Electron 内置 PDF 引擎原生渲染，数据不出本机（#36） -->
        <view v-else-if="isPdf" class="preview-pdf">
          <!-- #ifdef H5 -->
          <iframe v-if="blobUrl" :src="blobUrl" class="preview-iframe" frameborder="0"></iframe>
          <!-- #endif -->
          <!-- #ifndef H5 -->
          <web-view v-if="blobUrl" :src="blobUrl" />
          <!-- #endif -->
        </view>

        <!-- 图片/SVG 预览 -->
        <view v-else-if="isImage" class="preview-image">
          <image :src="blobUrl" mode="aspectFit" class="preview-img" @error="handleImageError" />
        </view>

        <!-- 视频预览：与图片/音频一致走带鉴权的 blob——直链 <video src> 不带
             X-Session-Id，后端 401，表现为 MEDIA_ERR_SRC_NOT_SUPPORTED（真机证实） -->
        <view v-else-if="isVideo" class="preview-video">
          <video
            v-if="blobUrl"
            ref="videoPlayer"
            :src="blobUrl"
            controls
            autoplay
            class="preview-video-player"
            @error="handleVideoError"
            @loadeddata="onVideoLoaded"
          >
            您的浏览器不支持视频播放
          </video>
          <view v-else class="loading-video"><text>视频加载中…</text></view>
        </view>

        <!-- 音频预览 -->
        <view v-else-if="isAudio" class="preview-audio">
           <view class="audio-wrapper">
            <view class="audio-icon">🎵</view>
            <text class="audio-name">{{ file.name }}</text>
            <view class="preview-audio-player" v-html="audioPlayerHtml"></view>
           </view>
        </view>

        <!-- 文本预览 -->
        <view v-else-if="isText" class="preview-text">
          <text class="text-content">{{ textContent }}</text>
        </view>

        <!-- 压缩包预览：条目列表 + 解压到当前目录 -->
        <view v-else-if="isArchive" class="preview-archive">
          <view class="archive-toolbar">
            <text class="archive-count">{{ archiveLoading || archiveError ? '' : archiveEntries.length + ' 个条目' }}</text>
            <button class="btn-extract" size="mini" :disabled="archiveLoading || extracting || !!archiveError" @tap="handleExtract">
              {{ extracting ? '解压中…' : '解压' }}
            </button>
          </view>
          <view v-if="archiveLoading" class="archive-status"><text>正在读取压缩包…</text></view>
          <view v-else-if="archiveError" class="archive-status archive-error"><text>{{ archiveError }}</text></view>
          <scroll-view v-else scroll-y class="archive-list">
            <view v-for="(entry, i) in archiveEntries" :key="i" class="archive-entry">
              <text class="entry-icon">{{ entry.dir ? '📁' : '📄' }}</text>
              <text class="entry-path">{{ entry.path }}</text>
              <text class="entry-size" v-if="!entry.dir">{{ formatFileSize(entry.size) }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 不支持预览的文件类型 -->
        <view v-else class="preview-unsupported">
          <text>该文件类型暂不支持预览</text>
          <text class="preview-hint">文件类型: {{ file.fileType || '未知' }}</text>
          <text class="preview-hint">文件ID: {{ file.wpsFileId || file.id }}</text>
          <button class="btn-download" type="default" size="mini" @tap="handleDownload">
            下载文件
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getFileDownloadUrl, getArchiveEntries, extractArchive } from '@/services/api.js'
import { getAuthHeaders, getSessionId } from '@/utils/auth.js'

// docx-preview 依赖 Chromium DOM，仅 H5/桌面构建启用；其它平台落 Office 占位分支
// #ifdef H5
const IS_H5 = true
// #endif
// #ifndef H5
const IS_H5 = false
// #endif

export default {
  name: 'FilePreview',
  props: {
    file: {
      type: Object,
      default: null
    },
    baseUrl: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      textContent: '',
      loading: false,
      blobUrl: '',
      docxLoading: false,
      docxRenderFailed: false,
      // docx-preview 仅在 H5/桌面（Chromium）渲染；非 H5 落 Office 占位分支
      useDocxPreview: IS_H5,
      pptxLoading: false,
      pptxRenderFailed: false,
      usePptxPreview: IS_H5,
      // 压缩包预览状态
      archiveEntries: [],
      archiveLoading: false,
      archiveError: '',
      extracting: false
    }
  },
  computed: {
    fileUrl() {
      if (!this.file) {
        console.log('FilePreview: file 为空')
        return ''
      }
      const fileId = this.file.wpsFileId || this.file.id
      const url = getFileDownloadUrl(fileId)
      console.log('FilePreview fileUrl:', { file: this.file, fileId, url })
      return url
    },
    isPdf() {
      // PDF 走本地原生渲染（fetch 成 blob → Chromium/Electron 内置 PDF 引擎），无需 WPS（#36）
      if (!this.file || !this.file.fileType) return false
      return this.file.fileType.toLowerCase() === 'pdf'
    },
    isOffice() {
      if (!this.file || !this.file.fileType) return false
      const type = this.file.fileType.toLowerCase()
      return ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'].includes(type)
    },
    isWord() {
      if (!this.file || !this.file.fileType) return false
      return ['doc', 'docx'].includes(this.file.fileType.toLowerCase())
    },
    isPptx() {
      // 仅 pptx（OOXML）；ppt 97 二进制前端渲染库不支持，落占位下载分支
      if (!this.file || !this.file.fileType) return false
      return this.file.fileType.toLowerCase() === 'pptx'
    },
    isImage() {
      if (!this.file || !this.file.fileType) return false
      const type = this.file.fileType.toLowerCase()
      return ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg'].includes(type)
    },
    isVideo() {
      if (!this.file || !this.file.fileType) return false
      const type = this.file.fileType.toLowerCase()
      return ['mp4', 'webm', 'ogg', 'mov', 'mkv', 'avi'].includes(type)
    },
    isAudio() {
       if (!this.file || !this.file.fileType) return false
       const type = this.file.fileType.toLowerCase()
       return ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac'].includes(type)
    },
    audioPlayerHtml() {
      if (!this.isAudio || !this.fileUrl) return ''
      // Use standard HTML audio tag, bypassing UniApp component resolution
      return `<audio src="${this.blobUrl}" controls style="width: 100%; height: 50px; outline: none;"></audio>`
    },
    isText() {
      if (!this.file || !this.file.fileType) return false
      const type = this.file.fileType.toLowerCase()
      return ['txt', 'md', 'json', 'xml', 'html', 'css', 'js', 'java', 'py', 'sh', 'sql', 'log'].includes(type)
    },
    isArchive() {
      if (!this.file || !this.file.fileType) return false
      return ['zip', 'rar', '7z'].includes(this.file.fileType.toLowerCase())
    },
    canEdit() {
      // Office 文件且有 wpsFileId 可以编辑
      if (!this.file || !this.file.fileType) return false
      const type = this.file.fileType.toLowerCase()
      return ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx'].includes(type) && !!this.file.wpsFileId
    }
  },
  watch: {
    file: {
      immediate: true,
      handler(newFile) {
        console.log('FilePreview file 变化:', newFile)
        // 清理旧的 blobUrl
        if (this.blobUrl) {
          URL.revokeObjectURL(this.blobUrl)
          this.blobUrl = ''
        }

        this.docxRenderFailed = false
        this.pptxRenderFailed = false

        if (!newFile) return

        if (this.isText) {
          this.loadTextContent()
        } else if (this.isWord && this.useDocxPreview) {
          this.renderDocx()
        } else if (this.isPptx && this.usePptxPreview) {
          this.renderPptx()
        } else if (this.isImage || this.isVideo || this.isAudio || this.isPdf) {
           this.loadMediaResource()
        } else if (this.isArchive) {
          this.loadArchiveEntries()
        }
      }
    }
  },
  beforeUnmount() {
    if (this.blobUrl) {
      URL.revokeObjectURL(this.blobUrl)
    }
  },
  mounted() {
    console.log('FilePreview mounted, file:', this.file, 'fileUrl:', this.fileUrl)
  },
  methods: {
    async loadTextContent() {
      if (!this.file || !this.fileUrl) return

      this.loading = true
      try {
        const response = await uni.request({
          url: this.fileUrl,
          method: 'GET',
          header: getAuthHeaders()
        })
        this.textContent = response.data || ''
      } catch (error) {
        console.error('加载文本内容失败:', error)
        this.textContent = '加载失败'
      } finally {
        this.loading = false
      }
    },
    async loadMediaResource() {
        if (!this.file || !this.fileUrl) return

        this.loading = true
        // 竞态防护：记录本次请求序号，onload 时若已切到别的文件则丢弃陈旧响应，避免显示错文件
        const reqId = (this._mediaReqId = (this._mediaReqId || 0) + 1)
        console.log('loadMediaResource: 开始加载', this.fileUrl)

        const headers = getAuthHeaders() || {}
        console.log('loadMediaResource: 使用认证头', headers)
        const mimeType = this.getMimeType(this.file.fileType)
        const self = this
        
        // 使用 XMLHttpRequest 来正确处理大文件的 arraybuffer 响应
        const xhr = new XMLHttpRequest()
        xhr.open('GET', this.fileUrl, true)
        xhr.responseType = 'blob'  // 直接获取 blob，避免 arraybuffer 大小限制
        
        // 设置认证头
        Object.keys(headers).forEach(key => {
          xhr.setRequestHeader(key, headers[key])
        })
        
        xhr.onload = function() {
          if (self._mediaReqId !== reqId) return // 已切换到别的文件，丢弃陈旧响应
          if (xhr.status === 200) {
            const blob = xhr.response
            console.log('loadMediaResource: 获取到数据', blob.size, 'bytes, MIME:', mimeType || blob.type)
            
            // 如果 blob 没有正确的 MIME 类型，重新创建一个带类型的 blob
            let finalBlob = blob
            if (mimeType && blob.type !== mimeType) {
              finalBlob = new Blob([blob], { type: mimeType })
            }
            
            self.blobUrl = URL.createObjectURL(finalBlob)
            console.log('loadMediaResource: blobUrl 已创建', self.blobUrl)
          } else {
            console.error('loadMediaResource: 请求失败', xhr.status)
            uni.showToast({
              title: '资源加载失败: ' + xhr.status,
              icon: 'none'
            })
          }
          self.loading = false
        }
        
        xhr.onerror = function() {
          console.error('loadMediaResource: 网络错误')
          uni.showToast({
            title: '网络错误，资源加载失败',
            icon: 'none'
          })
          self.loading = false
        }
        
        xhr.onprogress = function(event) {
          if (event.lengthComputable) {
            const percent = Math.round((event.loaded / event.total) * 100)
            console.log('loadMediaResource: 下载进度', percent + '%', event.loaded, '/', event.total)
          }
        }
        
        xhr.send()
    },
    // 带鉴权下载为 Blob（复用 loadMediaResource 的 XHR 鉴权方式，但返回 Promise<Blob>）
    fetchAuthedBlob() {
      return new Promise((resolve, reject) => {
        if (!this.fileUrl) return reject(new Error('文件地址为空'))
        const headers = getAuthHeaders() || {}
        const xhr = new XMLHttpRequest()
        xhr.open('GET', this.fileUrl, true)
        xhr.responseType = 'blob'
        Object.keys(headers).forEach(key => xhr.setRequestHeader(key, headers[key]))
        xhr.onload = () => xhr.status === 200 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status))
        xhr.onerror = () => reject(new Error('网络错误'))
        xhr.send()
      })
    },
    // Word 文档零配置只读渲染：docx-preview 在本地（Chromium）解析 .docx，无需任何密钥
    async renderDocx() {
      this.docxLoading = true
      this.docxRenderFailed = false
      try {
        const blob = await this.fetchAuthedBlob()
        await this.$nextTick()
        const ref = this.$refs.docxContainer
        const container = ref && (ref.$el || ref)
        if (!container) throw new Error('渲染容器未就绪')
        container.innerHTML = ''
        const { renderAsync } = await import('docx-preview')
        await renderAsync(blob, container, null, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true,
          experimental: true
        })
      } catch (error) {
        console.error('docx 本地渲染失败，落 Office 占位分支:', error)
        this.docxRenderFailed = true
      } finally {
        this.docxLoading = false
      }
    },
    // PPTX 零配置只读渲染：pptx-preview 在本地（Chromium）解析 .pptx
    async renderPptx() {
      this.pptxLoading = true
      this.pptxRenderFailed = false
      try {
        const blob = await this.fetchAuthedBlob()
        const buf = await blob.arrayBuffer()
        await this.$nextTick()
        const ref = this.$refs.pptxContainer
        const container = ref && (ref.$el || ref)
        if (!container) throw new Error('渲染容器未就绪')
        container.innerHTML = ''
        const { init } = await import('pptx-preview')
        const width = Math.max(320, (container.clientWidth || 960) - 32)
        const previewer = init(container, { width, height: Math.round(width * 9 / 16) })
        await previewer.preview(buf)
      } catch (error) {
        console.error('pptx 本地渲染失败，落 Office 占位分支:', error)
        this.pptxRenderFailed = true
      } finally {
        this.pptxLoading = false
      }
    },
    getMimeType(fileType) {
        if (!fileType) return ''
        const type = fileType.toLowerCase()
        const map = {
            'jpg': 'image/jpeg',
            'jpeg': 'image/jpeg',
            'png': 'image/png',
            'gif': 'image/gif',
            'webp': 'image/webp',
            'svg': 'image/svg+xml',
            'bmp': 'image/bmp',
            'pdf': 'application/pdf',
            'mp4': 'video/mp4',
            'webm': 'video/webm',
            'ogg': 'video/ogg',
            'mov': 'video/quicktime',
            'mkv': 'video/x-matroska',
            'avi': 'video/x-msvideo',
            'mp3': 'audio/mpeg',
            'wav': 'audio/wav',
            'm4a': 'audio/mp4',
            'flac': 'audio/flac',
            'aac': 'audio/aac'
        }
        return map[type] || ''
    },
    handleEdit() {
      if (this.canEdit) {
        this.$emit('edit', this.file)
      }
    },
    // 压缩包条目列表（后端解析，zip/7z/rar）
    async loadArchiveEntries() {
      this.archiveLoading = true
      this.archiveError = ''
      this.archiveEntries = []
      try {
        const res = await getArchiveEntries(this.file.projectId, this.file.id)
        this.archiveEntries = (res && res.entries) || []
      } catch (e) {
        this.archiveError = (e && e.message) || '压缩包读取失败'
      } finally {
        this.archiveLoading = false
      }
    },
    // 解压到压缩包所在目录下的新文件夹；成功后通知宿主刷新资源管理器
    async handleExtract() {
      if (this.extracting) return
      this.extracting = true
      try {
        const folder = await extractArchive(this.file.projectId, this.file.id)
        uni.showToast({ title: '已解压到「' + ((folder && folder.name) || '') + '」', icon: 'success' })
        this.$emit('extracted', folder)
      } catch (e) {
        uni.showModal({ title: '解压失败', content: (e && e.message) || '解压失败', showCancel: false })
      } finally {
        this.extracting = false
      }
    },
    onVideoLoaded(e) {
      console.log('视频加载成功，可以播放')
      if (e.target) {
        console.log('视频信息:', {
          duration: e.target.duration,
          videoWidth: e.target.videoWidth,
          videoHeight: e.target.videoHeight
        })
      }
    },
    handleImageError(e) {
      console.error('图片加载失败:', e)
      uni.showToast({
        title: '图片加载失败',
        icon: 'none'
      })
    },
    handleVideoError(e) {
      console.error('视频加载失败:', e)
      // 获取更详细的错误信息
      const video = e.target
      if (video && video.error) {
        const errorCodes = {
          1: 'MEDIA_ERR_ABORTED - 用户中止',
          2: 'MEDIA_ERR_NETWORK - 网络错误',
          3: 'MEDIA_ERR_DECODE - 解码错误（可能是编码格式不支持）',
          4: 'MEDIA_ERR_SRC_NOT_SUPPORTED - 不支持的视频格式或编码'
        }
        console.error('视频错误代码:', video.error.code, errorCodes[video.error.code] || '未知错误')
        console.error('视频错误消息:', video.error.message)
      }
      console.log('当前 blobUrl:', this.blobUrl)
      console.log('视频 src:', video ? video.src : 'N/A')
      uni.showToast({
        title: '视频播放失败，可能是编码格式不支持',
        icon: 'none'
      })
    },
    handleAudioError(e) {
      console.error('音频加载失败:', e)
      uni.showToast({
        title: '音频播放失败',
        icon: 'none'
      })
    },
    handleDownload() {
      if (this.fileUrl) {
        console.log('下载文件:', this.fileUrl)
        // #ifdef H5
        // H5端直接打开下载链接
        window.open(this.fileUrl + (this.fileUrl.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(getSessionId()), '_blank')
        // #endif
        // #ifndef H5
        uni.downloadFile({
          url: this.fileUrl,
          header: getAuthHeaders(),
          success: (res) => {
            if (res.statusCode === 200) {
              uni.openDocument({
                filePath: res.tempFilePath,
                success: () => {
                  console.log('打开文档成功')
                },
                fail: (err) => {
                  console.error('打开文档失败:', err)
                  uni.showToast({
                    title: '打开文档失败',
                    icon: 'none'
                  })
                }
              })
            }
          },
          fail: (err) => {
            console.error('下载文件失败:', err)
            uni.showToast({
              title: '下载失败',
              icon: 'none'
            })
          }
        })
        // #endif
      }
    },
    formatFileSize(bytes) {
      if (!bytes) return '0 B'
      const k = 1024
      const sizes = ['B', 'KB', 'MB', 'GB']
      const i = Math.floor(Math.log(bytes) / Math.log(k))
      return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
    }
  }
}
</script>

<style lang="scss" scoped>
.file-preview {
  height: 100%;
  display: flex;
  flex-direction: column;
  background-color: #ffffff;
}

.preview-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9ca3af;
  font-size: 28rpx;
}

.preview-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.preview-header {
  padding: 24rpx;
  border-bottom: 1rpx solid #e5e7eb;
  background-color: #ffffff;
}

.preview-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12rpx;
}

.preview-title {
  font-size: 32rpx;
  font-weight: 500;
  color: #1f2430;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn-edit {
  margin-left: 16rpx;
}

.preview-meta {
  display: flex;
  gap: 24rpx;
}

.meta-item {
  font-size: 24rpx;
  color: #6b7280;
}

.preview-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.preview-pdf,
.preview-office {
  width: 100%;
  height: 100%;
}

/* Word 文档零配置只读渲染容器（docx-preview） */
.preview-docx {
  width: 100%;
  height: 100%;
  overflow: auto;
  background-color: #f3f4f6;
}

/* PPTX 零配置只读渲染容器（pptx-preview） */
.preview-pptx {
  width: 100%;
  height: 100%;
  overflow: auto;
  background-color: #f3f4f6;
}

.pptx-host {
  display: block;
  width: 100%;
  padding: 16rpx;
}

.docx-host {
  display: block;
  width: 100%;
}

.docx-loading {
  padding: 32rpx;
  text-align: center;
  color: #6b7280;
  font-size: 28rpx;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
}

.preview-hint {
  display: block;
  font-size: 24rpx;
  color: #9ca3af;
  margin-top: 8rpx;
}

.preview-image {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24rpx;
  background: #282828;
}

.preview-img {
  max-width: 100%;
  max-height: 100%;
}

.preview-text {
  padding: 24rpx;
  overflow-y: auto;
  height: 100%;
}

.text-content {
  font-size: 28rpx;
  color: #1f2430;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.preview-unsupported {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24rpx;
  color: #9ca3af;
  font-size: 28rpx;
}

.preview-video {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #000;
}

.preview-video-player {
  width: 100%;
  height: 100%;
}

.loading-video {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: #ffffff;
  font-size: 28rpx;
}

.preview-audio {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f8f9fa;
}

.audio-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;
  width: 80%;
}

.audio-icon {
  font-size: 80rpx;
}

.audio-name {
  font-size: 32rpx;
  color: #334155;
  font-weight: 500;
  text-align: center;
}

.preview-audio-player {
  width: 100%;
}

.btn-download {
  margin-top: 16rpx;
}

/* 压缩包预览 */
.preview-archive {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.archive-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 24rpx;
  border-bottom: 1rpx solid #e5e7eb;
}

.archive-count {
  font-size: 24rpx;
  color: #6b7280;
}

.archive-status {
  padding: 32rpx;
  text-align: center;
  color: #6b7280;
  font-size: 28rpx;
}

.archive-error {
  color: #b91c1c;
}

.archive-list {
  flex: 1;
  min-height: 0;
}

.archive-entry {
  display: flex;
  align-items: center;
  gap: 12rpx;
  padding: 12rpx 24rpx;
  border-bottom: 1rpx solid #f3f4f6;
}

.entry-icon {
  font-size: 26rpx;
}

.entry-path {
  flex: 1;
  font-size: 26rpx;
  color: #1f2430;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-size {
  font-size: 24rpx;
  color: #9ca3af;
}
</style>

