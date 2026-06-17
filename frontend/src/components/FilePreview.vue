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
        <!-- Office 文件预览（使用 WPS 预览模式） -->
        <view v-if="isOffice && file.wpsFileId" class="preview-wps">
          <WpsEditor
            :file-id="file.wpsFileId"
            :file-name="file.name"
            :app-id="wpsAppId"
            :mode="'view'"
            :auto-load="true"
            :container-style="wpsContainerStyle"
            @ready="onWpsPreviewReady"
            @error="onWpsPreviewError"
          />
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

        <!-- 视频预览 -->
        <view v-else-if="isVideo" class="preview-video">
          <!-- 优先使用直接 URL 播放（支持流式传输，无需等待下载完成） -->
          <video
            ref="videoPlayer"
            :src="fileUrl"
            controls
            autoplay
            class="preview-video-player"
            @error="handleVideoError"
            @loadeddata="onVideoLoaded"
          >
            <source :src="fileUrl" type="video/mp4">
            您的浏览器不支持视频播放
          </video>
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
import { getFileDownloadUrl } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
import WpsEditor from '@/components/WpsEditor.vue'

export default {
  name: 'FilePreview',
  components: {
    WpsEditor
  },
  props: {
    file: {
      type: Object,
      default: null
    },
    baseUrl: {
      type: String,
      default: ''
    },
    wpsAppId: {
      type: String,
      default: '' // 由父组件从后端动态获取并传入
    }
  },
  data() {
    return {
      textContent: '',
      loading: false,
      blobUrl: '',
      wpsContainerStyle: {
        width: '100%',
        height: '100%'
      }
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

        if (!newFile) return

        if (this.isText) {
          this.loadTextContent()
        } else if (this.isImage || this.isVideo || this.isAudio || this.isPdf) {
           this.loadMediaResource()
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
          method: 'GET'
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
    // WPS 预览相关方法
    onWpsPreviewReady(instance) {
      console.log('WPS 预览加载成功', instance)
    },
    onWpsPreviewError(error) {
      console.error('WPS 预览加载失败:', error)
      uni.showToast({
        title: '预览加载失败，请稍后重试',
        icon: 'none'
      })
    },
    handleDownload() {
      if (this.fileUrl) {
        console.log('下载文件:', this.fileUrl)
        // #ifdef H5
        // H5端直接打开下载链接
        window.open(this.fileUrl, '_blank')
        // #endif
        // #ifndef H5
        uni.downloadFile({
          url: this.fileUrl,
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
.preview-office,
.preview-wps {
  width: 100%;
  height: 100%;
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
</style>

