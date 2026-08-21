<template>
  <view class="file-preview">
    <view v-if="!file" class="preview-placeholder">
      <text>{{ $t('files.selectFilePrompt') }}</text>
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
            {{ $t('files.edit') }}
          </button>
        </view>
        <view class="preview-meta">
          <text class="meta-item" v-if="file.fileType">{{ $t('files.typeLabel', { type: file.fileType }) }}</text>
          <text class="meta-item" v-if="file.fileSize">{{ $t('files.sizeLabel', { size: formatFileSize(file.fileSize) }) }}</text>
        </view>
      </view>

      <!-- 预览内容区域 -->
      <view class="preview-body">
        <!-- Word 文档零配置只读渲染：docx-preview 本地解析，无需任何密钥，数据不出本机（承接 #18 T6） -->
        <view v-if="isWord && useDocxPreview && !docxRenderFailed" class="preview-docx">
          <view v-if="docxLoading" class="docx-loading"><text>{{ $t('files.renderingDoc') }}</text></view>
          <view ref="docxContainer" class="docx-host"></view>
        </view>

        <!-- PPTX 零配置只读渲染：pptx-preview 本地解析（自建 LOWA 引擎无 Impress
             模块，演示文稿由前端渲染承接） -->
        <view v-else-if="isPptx && usePptxPreview && !pptxRenderFailed" class="preview-pptx">
          <view v-if="pptxLoading" class="docx-loading"><text>{{ $t('files.renderingSlides') }}</text></view>
          <view ref="pptxContainer" class="pptx-host"></view>
        </view>

        <!-- 其余 Office 文件（ppt 二进制等，或渲染失败/非 H5）：暂不支持在线预览（#79，
             WPS 预览回退已移除） -->
        <view v-else-if="isOffice" class="preview-unsupported">
          <text>{{ $t('files.officePreviewUnsupported') }}</text>
          <text class="preview-hint">{{ $t('files.fileTypeHintDownload', { type: file.fileType || $t('files.unknown') }) }}</text>
          <button class="btn-download" type="default" size="mini" @tap="handleDownload">
            {{ $t('files.downloadFile') }}
          </button>
        </view>

        <!-- PDF 预览：本地 blob 由浏览器/Electron 内置 PDF 引擎原生渲染，数据不出本机（#36） -->
        <view v-else-if="isPdf" class="preview-pdf">
          <!-- #ifdef H5 -->
          <iframe v-if="blobUrl" :src="pdfSrc" class="preview-iframe" frameborder="0"></iframe>
          <!-- #endif -->
          <!-- #ifndef H5 -->
          <web-view v-if="blobUrl" :src="pdfSrc" />
          <!-- #endif -->
        </view>

        <!-- 图片/SVG 预览：缩放平移查看器。用原生 img 配 CSS transform 而不是 uni 的
             image 组件——transform 不好控。换文件时的状态重置见 resetImageViewState。
             滚轮/拖拽/双击绑在容器而不是 img 本身：图片小于容器时四周还有留白，
             绑在 img 上会让留白区域变成"死区"，滚轮/拖拽在那里没反应。 -->
        <view
          v-else-if="isImage"
          class="preview-image"
          :class="{ 'is-panning': imagePanning }"
          ref="imageViewport"
          @wheel="handleImageWheel"
          @mousedown="handleImagePanStart"
          @dblclick="handleImageDblClick"
        >
          <img
            v-if="blobUrl"
            :src="blobUrl"
            class="preview-img"
            :style="imageTransformStyle"
            draggable="false"
            @load="handleImageLoad"
            @error="handleImageError"
          />
          <!-- EvidenceLink 图片定位框：locator.rect 是 0..1 归一化坐标，按当前缩放平移换算 -->
          <view v-if="evidenceRectStyle" class="evidence-rect" :style="evidenceRectStyle"></view>
          <view v-if="imageReady" class="image-toolbar" @mousedown.stop>
            <button class="img-tool-btn" size="mini" @tap="imageZoomOutBtn">−</button>
            <text class="img-zoom-pct">{{ imageZoomPercentText }}</text>
            <button class="img-tool-btn" size="mini" @tap="imageZoomInBtn">＋</button>
            <view class="img-tool-sep"></view>
            <button class="img-tool-btn img-tool-btn-text" size="mini" @tap="imageZoomActual">1:1</button>
            <button class="img-tool-btn img-tool-btn-text" size="mini" @tap="imageZoomFit">{{ $t('files.fitWindow') }}</button>
          </view>
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
            {{ $t('files.videoNotSupported') }}
          </video>
          <view v-else class="loading-video"><text>{{ $t('files.videoLoading') }}</text></view>
        </view>

        <!-- 音频预览：自绘播放器。
             原来是 v-html 注一个裸 <audio controls>，用的是 Chromium 默认媒体控件——
             一条深灰药丸，跟整个浅色外壳格格不入，还不认应用的配色。
             播放器本体是 new window.Audio()：模板里写不了 <audio>，uni-h5 的编译器
             会把这个标签替换成不存在的组件（FeedbackWidget 与会议录音面板同坑）。 -->
        <view v-else-if="isAudio" class="preview-audio">
           <view class="audio-card">
            <view class="audio-head">
              <svg class="audio-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path v-for="(d, gi) in ICONS.audioLines" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <text class="audio-name">{{ file.name }}</text>
            </view>

            <view v-if="!blobUrl" class="audio-loading"><text>{{ $t('files.audioLoading') }}</text></view>

            <template v-else>
              <!-- 进度条：整条都可点可拖，命中区比视觉轨道高（4px 的轨道点不准） -->
              <view
                ref="audioTrack"
                class="audio-track"
                @mousedown="onSeekDown"
              >
                <view class="audio-track-rail"></view>
                <view class="audio-track-fill" :style="{ width: audioProgressPct + '%' }"></view>
                <view class="audio-track-knob" :style="{ left: audioProgressPct + '%' }"></view>
              </view>

              <view class="audio-times">
                <text class="audio-time">{{ formatClock(audioCurrent) }}</text>
                <text class="audio-time">{{ formatClock(audioDuration) }}</text>
              </view>

              <view class="audio-controls">
                <view class="audio-play" :title="audioPlaying ? $t('files.audioPause') : $t('files.audioPlay')" @tap="toggleAudioPlay">
                  <svg class="audio-play-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path
                      v-for="(d, gi) in (audioPlaying ? ICONS.pause : ICONS.play)"
                      :key="gi"
                      :d="d"
                      :fill="audioPlaying ? 'none' : 'currentColor'"
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </view>

                <view class="audio-side">
                  <!-- 倍速：Chromium 原生控件的溢出菜单里本来就有，换成自绘不能把它弄丢 -->
                  <view class="audio-rate" :title="$t('files.audioRate')" @tap="cycleAudioRate">
                    <text>{{ audioRate }}x</text>
                  </view>
                  <view class="audio-vol">
                    <view class="audio-vol-btn" :title="$t('files.audioMute')" @tap="toggleAudioMute">
                      <svg class="audio-vol-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path v-for="(d, gi) in (audioMuted ? ICONS.volumeMute : ICONS.volume)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </view>
                    <view ref="audioVolTrack" class="audio-vol-track" @mousedown="onVolumeDown">
                      <view class="audio-vol-rail"></view>
                      <view class="audio-vol-fill" :style="{ width: (audioMuted ? 0 : audioVolume * 100) + '%' }"></view>
                    </view>
                  </view>
                </view>
              </view>
            </template>
           </view>
        </view>

        <!-- 文本预览 -->
        <view v-else-if="isText" class="preview-text">
          <text class="text-content">{{ textContent }}</text>
        </view>

        <!-- 压缩包预览：条目列表 + 解压到当前目录 -->
        <view v-else-if="isArchive" class="preview-archive">
          <view class="archive-toolbar">
            <text class="archive-count">{{ archiveLoading || archiveError ? '' : $t('files.entriesCount', { count: archiveEntries.length }) }}</text>
            <button class="btn-extract" size="mini" :disabled="archiveLoading || extracting || !!archiveError" @tap="handleExtract">
              {{ extracting ? $t('files.extracting') : $t('files.extract') }}
            </button>
          </view>
          <view v-if="archiveLoading" class="archive-status"><text>{{ $t('files.readingArchive') }}</text></view>
          <view v-else-if="archiveError" class="archive-status archive-error"><text>{{ archiveError }}</text></view>
          <scroll-view v-else scroll-y class="archive-list">
            <view v-for="(entry, i) in archiveEntries" :key="i" class="archive-entry">
              <svg class="entry-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in (entry.dir ? ICONS.folder : ICONS.doc)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
              <text class="entry-path">{{ entry.path }}</text>
              <text class="entry-size" v-if="!entry.dir">{{ formatFileSize(entry.size) }}</text>
            </view>
          </scroll-view>
        </view>

        <!-- 不支持预览的文件类型 -->
        <view v-else class="preview-unsupported">
          <text>{{ $t('files.previewUnsupportedType') }}</text>
          <text class="preview-hint">{{ $t('files.fileTypeHint', { type: file.fileType || $t('files.unknown') }) }}</text>
          <text class="preview-hint">{{ $t('files.fileIdHint', { id: file.wpsFileId || file.id }) }}</text>
          <button class="btn-download" type="default" size="mini" @tap="handleDownload">
            {{ $t('files.downloadFile') }}
          </button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getFileDownloadUrl, getArchiveEntries, extractArchive } from '@/services/api.js'
import { getAuthHeaders, getSessionId } from '@/utils/auth.js'
import { ICONS } from '@/config/icons.js'
import { shouldAcceptResponse } from '@/utils/requestGeneration.js'

// docx-preview 依赖 Chromium DOM，仅 H5/桌面构建启用；其它平台落 Office 占位分支
// #ifdef H5
const IS_H5 = true
// #endif
// #ifndef H5
const IS_H5 = false
// #endif

// 图片查看器缩放范围：10% ~ 800%，到边界停住不继续缩
const IMAGE_MIN_SCALE = 0.1
const IMAGE_MAX_SCALE = 8

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
    },
    // EvidenceLink 定位符（spec §1.4）：pdf → #page；image → 画框；media → 起播时刻
    locator: {
      type: Object,
      default: null
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
      extracting: false,
      // 图片查看器：缩放平移状态。换文件时在 resetImageViewState 里整体清零，
      // 真正的「适应窗口」尺寸要等 handleImageLoad 拿到 naturalWidth/Height 才能算。
      imageScale: 1,
      imageTx: 0,
      imageTy: 0,
      imageFitScale: 1,
      imageNaturalWidth: 0,
      imageNaturalHeight: 0,
      imagePanning: false,
      imagePanStartX: 0,
      imagePanStartY: 0,
      imagePanStartTx: 0,
      imagePanStartTy: 0,
      // 自绘音频播放器。实例本身（window.Audio）不进 data——它不需要响应式，
      // 塞进 data 会被 Vue 代理一层，媒体元素被 Proxy 包住后行为不可预期。
      audioPlaying: false,
      audioCurrent: 0,
      audioDuration: 0,
      audioVolume: 1,
      audioMuted: false,
      audioRate: 1
    }
  },
  computed: {
    ICONS() { return ICONS },
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
    audioProgressPct() {
      if (!this.audioDuration) return 0
      return Math.min(100, Math.max(0, (this.audioCurrent / this.audioDuration) * 100))
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
    },
    // naturalWidth/Height 只有 img 解码完成后才有值，工具栏在此之前不该出现
    imageReady() {
      return this.imageNaturalWidth > 0 && this.imageNaturalHeight > 0
    },
    imageZoomPercentText() {
      return Math.round(this.imageScale * 100) + '%'
    },
    imageTransformStyle() {
      return {
        transform: `translate(${this.imageTx}px, ${this.imageTy}px) scale(${this.imageScale})`
      }
    },
    pdfSrc() {
      const loc = this.locator
      const page = loc && loc.type === 'pdf' ? Number(loc.page) : 0
      return this.blobUrl + (page > 0 ? '#page=' + page : '')
    },
    evidenceRectStyle() {
      const loc = this.locator
      const r = loc && loc.type === 'image' ? loc.rect : null
      if (!r || !this.imageReady) return null
      const s = this.imageScale
      const w = this.imageNaturalWidth * s
      const h = this.imageNaturalHeight * s
      return {
        left: (this.imageTx + Number(r.x || 0) * w) + 'px',
        top: (this.imageTy + Number(r.y || 0) * h) + 'px',
        width: Math.max(2, Number(r.w || 0) * w) + 'px',
        height: Math.max(2, Number(r.h || 0) * h) + 'px'
      }
    }
  },
  watch: {
    file: {
      immediate: true,
      handler(newFile) {
        console.log('FilePreview file 变化:', newFile)
        this.reloadPreview(newFile)
      }
    },
    // 音频的 blob 是异步拉下来的（要带 X-Session-Id，直链拿不到），
    // 播放器实例只能等 blobUrl 落地再建。换文件时 reloadPreview 会先清空它。
    blobUrl(url) {
      this.teardownAudio()
      if (url && this.isAudio) this.setupAudio(url)
    },
    // 同一文件再次被链接点中（换了时刻）：媒体元素还在，直接 seek
    locator() {
      this.seekToLocator()
    },
    // AI 修改文件后（pdf_highlight/pdf_redact 等）后端会更新 wpsFileId 并发 reload_file，
    // reload 处理是对既有 file 对象 Object.assign 原地更新——对象引用不变，上面的
    // file watch 不会触发。监听 wpsFileId 让预览重新拉取最新字节（编辑器同款语义）。
    'file.wpsFileId'(newVal, oldVal) {
      if (newVal && newVal !== oldVal) {
        console.log('FilePreview wpsFileId 变化，重新加载预览:', newVal)
        this.reloadPreview(this.file)
      }
    }
  },
  beforeUnmount() {
    this.teardownAudio()
    if (this.blobUrl) {
      URL.revokeObjectURL(this.blobUrl)
    }
    // 组件卸载时若正处于拖拽平移中，window 上的监听不会自己消失
    window.removeEventListener('mousemove', this.handleImagePanMove)
    window.removeEventListener('mouseup', this.handleImagePanEnd)
  },
  mounted() {
    console.log('FilePreview mounted, file:', this.file, 'fileUrl:', this.fileUrl)
  },
  methods: {
    // ==================== 自绘音频播放器 ====================
    setupAudio(url) {
      try {
        const a = new window.Audio(url)
        a.preload = 'metadata'
        a.volume = this.audioVolume
        a.playbackRate = this.audioRate
        a.addEventListener('loadedmetadata', () => { this.audioDuration = a.duration || 0; this.seekToLocator() })
        a.addEventListener('timeupdate', () => { this.audioCurrent = a.currentTime || 0 })
        a.addEventListener('play', () => { this.audioPlaying = true })
        a.addEventListener('pause', () => { this.audioPlaying = false })
        a.addEventListener('ended', () => { this.audioPlaying = false; this.audioCurrent = 0 })
        this._audio = a
      } catch (e) {
        console.warn('音频播放器创建失败:', e)
      }
    },
    teardownAudio() {
      this.detachAudioDrag()
      if (this._audio) {
        try { this._audio.pause() } catch (e) { /* ignore */ }
        this._audio.src = ''
        this._audio = null
      }
      this.audioPlaying = false
      this.audioCurrent = 0
      this.audioDuration = 0
    },
    toggleAudioPlay() {
      if (!this._audio) return
      if (this._audio.paused) this._audio.play().catch(() => {})
      else this._audio.pause()
    },
    toggleAudioMute() {
      if (!this._audio) return
      this.audioMuted = !this.audioMuted
      this._audio.muted = this.audioMuted
    },
    cycleAudioRate() {
      const steps = [1, 1.25, 1.5, 2, 0.75]
      this.audioRate = steps[(steps.indexOf(this.audioRate) + 1) % steps.length]
      if (this._audio) this._audio.playbackRate = this.audioRate
    },
    formatClock(sec) {
      const s = Math.max(0, Math.floor(sec || 0))
      const m = Math.floor(s / 60)
      return m + ':' + String(s % 60).padStart(2, '0')
    },
    // 进度条与音量条共用「按下即生效、按住可拖」的一套：监听挂 window，
    // 否则拖出轨道范围就收不到 mouseup，滑块会一直粘着鼠标
    ratioFromEvent(el, e) {
      const rect = el.getBoundingClientRect()
      if (!rect.width) return 0
      return Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
    },
    onSeekDown(e) {
      if (!this._audio || !this.audioDuration) return
      const el = this.$refs.audioTrack
      const apply = (ev) => {
        const t = this.ratioFromEvent(el, ev) * this.audioDuration
        this._audio.currentTime = t
        this.audioCurrent = t
      }
      apply(e)
      this.attachAudioDrag(apply)
    },
    onVolumeDown(e) {
      if (!this._audio) return
      const el = this.$refs.audioVolTrack
      const apply = (ev) => {
        const v = this.ratioFromEvent(el, ev)
        this.audioVolume = v
        this.audioMuted = v === 0
        this._audio.volume = v
        this._audio.muted = this.audioMuted
      }
      apply(e)
      this.attachAudioDrag(apply)
    },
    attachAudioDrag(apply) {
      this.detachAudioDrag()
      this._audioDragMove = (ev) => apply(ev)
      this._audioDragUp = () => this.detachAudioDrag()
      window.addEventListener('mousemove', this._audioDragMove)
      window.addEventListener('mouseup', this._audioDragUp)
    },
    detachAudioDrag() {
      if (this._audioDragMove) window.removeEventListener('mousemove', this._audioDragMove)
      if (this._audioDragUp) window.removeEventListener('mouseup', this._audioDragUp)
      this._audioDragMove = null
      this._audioDragUp = null
    },

    // file watch 与 wpsFileId watch 共用的加载分发（原 file watch handler 逻辑原样抽出）
    reloadPreview(newFile) {
      // 清理旧的 blobUrl
      if (this.blobUrl) {
        URL.revokeObjectURL(this.blobUrl)
        this.blobUrl = ''
      }

      this.docxRenderFailed = false
      this.pptxRenderFailed = false
      this.resetImageViewState()

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
    },
    // 换文件（或重新加载同一文件）时清空缩放平移状态。真正的「适应窗口」尺寸
    // 要等图片解码完成、handleImageLoad 拿到 naturalWidth/Height 后才能算，
    // 这里先归零占位，避免上一张图的缩放值窜到下一张图上。
    resetImageViewState() {
      this.imageScale = 1
      this.imageTx = 0
      this.imageTy = 0
      this.imageFitScale = 1
      this.imageNaturalWidth = 0
      this.imageNaturalHeight = 0
    },
    async loadTextContent() {
      if (!this.file || !this.fileUrl) return

      this.loading = true
      try {
        const response = await uni.request({
          url: this.fileUrl,
          method: 'GET',
          header: getAuthHeaders()
        })
        // uni.request 对 4xx/5xx 不会 reject，走的是 success 回调。不看 statusCode
        // 就把 response.data 当正文，用户会在「文本预览」里读到后端的错误信封
        // （{"code":4010,...} 之类），还以为那就是文件内容。
        const status = Number(response.statusCode || 0)
        if (status && (status < 200 || status >= 300)) {
          console.warn('[FilePreview] 文本预览请求失败 status=', status)
          this.textContent = this.$t('files.loadFailed')
          return
        }
        this.textContent = response.data || ''
      } catch (error) {
        console.error('加载文本内容失败:', error)
        this.textContent = this.$t('files.loadFailed')
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
              title: self.$t('files.resourceLoadFailedStatus', { status: xhr.status }),
              icon: 'none'
            })
          }
          self.loading = false
        }
        
        xhr.onerror = function() {
          console.error('loadMediaResource: 网络错误')
          uni.showToast({
            title: self.$t('files.networkErrorResource'),
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
      // 竞态防护：与 loadMediaResource 同一类毛病——快速切换 zip/rar/7z 文件时，
      // reloadPreview 会为新文件再调一次本方法，旧请求若后回来会用旧文件的条目
      // 覆盖新文件已经显示的列表。用请求代次判定"这份响应是否还对得上最新一次
      // 调用"；同时把 file 摘成局部变量，不在 await 之后再读 this.file（那时可能
      // 已经换成别的文件了）。
      const seq = (this._archiveReqSeq = (this._archiveReqSeq || 0) + 1)
      const file = this.file
      try {
        const res = await getArchiveEntries(file.projectId, file.id)
        if (!shouldAcceptResponse(seq, this._archiveReqSeq)) return
        this.archiveEntries = (res && res.entries) || []
      } catch (e) {
        if (!shouldAcceptResponse(seq, this._archiveReqSeq)) return
        this.archiveError = (e && e.message) || this.$t('files.archiveReadFailed')
      } finally {
        if (shouldAcceptResponse(seq, this._archiveReqSeq)) this.archiveLoading = false
      }
    },
    // 解压到压缩包所在目录下的新文件夹；成功后通知宿主刷新资源管理器
    async handleExtract() {
      if (this.extracting) return
      this.extracting = true
      try {
        const folder = await extractArchive(this.file.projectId, this.file.id)
        uni.showToast({ title: this.$t('files.extractedTo', { name: (folder && folder.name) || '' }), icon: 'success' })
        this.$emit('extracted', folder)
      } catch (e) {
        uni.showModal({ title: this.$t('files.extractFailed'), content: (e && e.message) || this.$t('files.extractFailed'), showCancel: false })
      } finally {
        this.extracting = false
      }
    },
    // EvidenceLink media 定位：startMs → currentTime。音频用自绘播放器实例，视频用 loadeddata 记下的元素。
    seekToLocator() {
      const loc = this.locator
      if (!loc || loc.type !== 'media') return
      const sec = Number(loc.startMs || 0) / 1000
      if (!(sec >= 0)) return
      const el = this.isAudio ? this._audio : this._videoEl
      if (!el) return
      try { el.currentTime = sec } catch (e) { /* metadata 未就绪时忽略，loadedmetadata 会再调一次 */ }
    },
    onVideoLoaded(e) {
      console.log('视频加载成功，可以播放')
      if (e.target) {
        this._videoEl = e.target
        this.seekToLocator()
        console.log('视频信息:', {
          duration: e.target.duration,
          videoWidth: e.target.videoWidth,
          videoHeight: e.target.videoHeight
        })
      }
    },
    // uni 的 <view> 在 H5 端 $refs 拿到的有时是组件实例（带 $el），有时已经是原生
    // DOM 节点，取决于具体编译产物——renderPptx/renderDocx 已经踩过这个坑，同款兜底。
    getImageViewportEl() {
      const ref = this.$refs.imageViewport
      return ref && (ref.$el || ref)
    },
    clampImageScale(scale) {
      return Math.min(IMAGE_MAX_SCALE, Math.max(IMAGE_MIN_SCALE, scale))
    },
    // 图片解码完成后才拿得到 naturalWidth/Height，第一时间按「适应窗口」摆好
    handleImageLoad(e) {
      const img = e.target
      this.imageNaturalWidth = img.naturalWidth || 0
      this.imageNaturalHeight = img.naturalHeight || 0
      this.applyImageView('fit')
    },
    // 摆到「适应窗口」或「100%」，两种都居中显示——工具栏点这两个按钮时不保留
    // 旧的平移量，语义上就是"重新摆一次"，而不是在当前位置基础上微调。
    applyImageView(mode) {
      const el = this.getImageViewportEl()
      if (!el || !this.imageNaturalWidth || !this.imageNaturalHeight) return
      const vw = el.clientWidth
      const vh = el.clientHeight
      this.imageFitScale = this.clampImageScale(
        Math.min(vw / this.imageNaturalWidth, vh / this.imageNaturalHeight)
      )
      const scale = mode === 'fit' ? this.imageFitScale : this.clampImageScale(1)
      this.imageScale = scale
      this.imageTx = (vw - this.imageNaturalWidth * scale) / 2
      this.imageTy = (vh - this.imageNaturalHeight * scale) / 2
    },
    // 以容器坐标 (anchorX, anchorY) 为锚点缩放到 targetScale：锚点在屏幕上的像素位置
    // 缩放前后保持不动。滚轮缩放的手感全靠这个——以中心缩放会让光标指的地方跑掉。
    zoomImageTo(targetScale, anchorX, anchorY) {
      const newScale = this.clampImageScale(targetScale)
      if (newScale === this.imageScale) return
      const ratio = newScale / this.imageScale
      this.imageTx = anchorX - (anchorX - this.imageTx) * ratio
      this.imageTy = anchorY - (anchorY - this.imageTy) * ratio
      this.imageScale = newScale
    },
    handleImageWheel(e) {
      e.preventDefault()
      if (!this.imageNaturalWidth) return
      const el = this.getImageViewportEl()
      if (!el) return
      const rect = el.getBoundingClientRect()
      const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15
      this.zoomImageTo(this.imageScale * factor, e.clientX - rect.left, e.clientY - rect.top)
    },
    handleImagePanStart(e) {
      if (!this.imageNaturalWidth) return
      e.preventDefault()
      this.imagePanning = true
      this.imagePanStartX = e.clientX
      this.imagePanStartY = e.clientY
      this.imagePanStartTx = this.imageTx
      this.imagePanStartTy = this.imageTy
      // 挂在 window 上而不是元素上：拖拽过程中鼠标很容易滑出图片区域甚至预览面板，
      // 挂在元素上会在那一刻丢事件，导致图片"粘"在鼠标上放不下来。
      window.addEventListener('mousemove', this.handleImagePanMove)
      window.addEventListener('mouseup', this.handleImagePanEnd)
    },
    handleImagePanMove(e) {
      if (!this.imagePanning) return
      this.imageTx = this.imagePanStartTx + (e.clientX - this.imagePanStartX)
      this.imageTy = this.imagePanStartTy + (e.clientY - this.imagePanStartY)
    },
    handleImagePanEnd() {
      this.imagePanning = false
      window.removeEventListener('mousemove', this.handleImagePanMove)
      window.removeEventListener('mouseup', this.handleImagePanEnd)
    },
    // 双击在「适应窗口」「100%」之间切换：已经在 100% 就回到适应窗口，
    // 其余任何状态（包括滚轮缩放到的任意值）一律跳到 100%。
    handleImageDblClick(e) {
      if (!this.imageNaturalWidth) return
      const el = this.getImageViewportEl()
      if (!el) return
      const rect = el.getBoundingClientRect()
      const target = Math.abs(this.imageScale - 1) < 0.001 ? this.imageFitScale : 1
      this.zoomImageTo(target, e.clientX - rect.left, e.clientY - rect.top)
    },
    imageZoomInBtn() {
      const el = this.getImageViewportEl()
      if (!el) return
      this.zoomImageTo(this.imageScale * 1.25, el.clientWidth / 2, el.clientHeight / 2)
    },
    imageZoomOutBtn() {
      const el = this.getImageViewportEl()
      if (!el) return
      this.zoomImageTo(this.imageScale / 1.25, el.clientWidth / 2, el.clientHeight / 2)
    },
    imageZoomActual() {
      this.applyImageView('actual')
    },
    imageZoomFit() {
      this.applyImageView('fit')
    },
    handleImageError(e) {
      console.error('图片加载失败:', e)
      uni.showToast({
        title: this.$t('files.imageLoadFailed'),
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
        title: this.$t('files.videoPlayFailed'),
        icon: 'none'
      })
    },
    handleAudioError(e) {
      console.error('音频加载失败:', e)
      uni.showToast({
        title: this.$t('files.audioPlayFailed'),
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
                    title: this.$t('files.openDocFailed'),
                    icon: 'none'
                  })
                }
              })
            }
          },
          fail: (err) => {
            console.error('下载文件失败:', err)
            uni.showToast({
              title: this.$t('files.downloadFailed'),
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
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  padding: 24rpx;
  background: #282828;
  cursor: grab;
}

.preview-image.is-panning {
  cursor: grabbing;
}

/* EvidenceLink 图片定位框：跟随 img 的平移缩放，不吃鼠标 */
.evidence-rect {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid #1A5336;
  background: rgba(26, 83, 54, 0.12);
  box-shadow: 0 0 0 9999px rgba(15, 23, 42, 0.18);
  pointer-events: none;
}

/* 缩放平移由 JS 算出的 transform 控制，图片本身按原始像素尺寸渲染 */
.preview-img {
  position: absolute;
  top: 0;
  left: 0;
  transform-origin: 0 0;
  user-select: none;
  -webkit-user-drag: none;
}

.image-toolbar {
  position: absolute;
  left: 50%;
  bottom: 24rpx;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 4rpx;
  padding: 8rpx 12rpx;
  background: rgba(255, 255, 255, 0.96);
  border: 1rpx solid #e5e7eb;
  border-radius: 10rpx;
  box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.18);
  /* 容器背景的 cursor:grab 会被子元素继承，工具栏不是可拖拽画布，这里截断 */
  cursor: default;
}

.img-tool-btn {
  min-width: 48rpx;
  height: 48rpx;
  line-height: 48rpx;
  padding: 0 8rpx;
  margin: 0;
  background: transparent;
  border: none;
  border-radius: 6rpx;
  font-size: 26rpx;
  color: #374151;
  cursor: pointer;
}

.img-tool-btn:hover {
  background: #f3f4f6;
}

.img-tool-btn-text {
  font-size: 22rpx;
  padding: 0 12rpx;
}

.img-zoom-pct {
  min-width: 76rpx;
  text-align: center;
  font-size: 22rpx;
  color: #6b7280;
}

.img-tool-sep {
  width: 1rpx;
  height: 28rpx;
  background: #e5e7eb;
  margin: 0 4rpx;
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

/* ---- 自绘音频播放器 ----
   尺寸一律用 px：这块是桌面端的固定形制，不该跟着 rpx 一起做视口缩放。 */
.preview-audio {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f8f9fa;
}

.audio-card {
  width: 100%;
  max-width: 460px;
  padding: 24px;
  box-sizing: border-box;
  background: #ffffff;
  border: 1px solid #E6EAE8;
  border-radius: 12px;
  box-shadow: 0 6px 24px rgba(18, 52, 77, 0.06);
}

.audio-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}

.audio-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  color: #1A5336;
}

.audio-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #334155;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audio-loading {
  padding: 12px 0;
  font-size: 12px;
  color: #9aa5a0;
  text-align: center;
}

/* 轨道：视觉 4px，命中区 16px。4px 高的东西点不准，也拖不住 */
.audio-track {
  position: relative;
  height: 16px;
  cursor: pointer;
}

.audio-track-rail,
.audio-track-fill {
  position: absolute;
  top: 6px;
  left: 0;
  height: 4px;
  border-radius: 2px;
}

.audio-track-rail {
  right: 0;
  background: #E6EAE8;
}

.audio-track-fill {
  background: #1A5336;
}

.audio-track-knob {
  position: absolute;
  top: 3px;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  border-radius: 50%;
  background: #1A5336;
  box-shadow: 0 1px 4px rgba(26, 83, 54, 0.4);
}

.audio-times {
  display: flex;
  justify-content: space-between;
  margin: 4px 0 14px;
}

.audio-time {
  font-size: 11px;
  color: #8b9691;
  font-variant-numeric: tabular-nums;
}

.audio-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.audio-play {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #1A5336;
  color: #ffffff;
  cursor: pointer;
  transition: background 0.15s ease;
}

.audio-play:hover {
  background: #22694A;
}

.audio-play-glyph {
  width: 18px;
  height: 18px;
}

.audio-side {
  display: flex;
  align-items: center;
  gap: 14px;
}

.audio-rate {
  min-width: 40px;
  padding: 4px 8px;
  border: 1px solid #E6EAE8;
  border-radius: 6px;
  font-size: 12px;
  color: #4a5751;
  text-align: center;
  cursor: pointer;
  font-variant-numeric: tabular-nums;
}

.audio-rate:hover {
  border-color: #5BD197;
  color: #1A5336;
}

.audio-vol {
  display: flex;
  align-items: center;
  gap: 8px;
}

.audio-vol-btn {
  width: 20px;
  height: 20px;
  color: #4a5751;
  cursor: pointer;
}

.audio-vol-glyph {
  width: 20px;
  height: 20px;
}

.audio-vol-track {
  position: relative;
  width: 72px;
  height: 16px;
  cursor: pointer;
}

.audio-vol-rail,
.audio-vol-fill {
  position: absolute;
  top: 6px;
  left: 0;
  height: 4px;
  border-radius: 2px;
}

.audio-vol-rail {
  right: 0;
  background: #E6EAE8;
}

.audio-vol-fill {
  background: #5BD197;
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

  width: 28rpx;
  height: 28rpx;
  flex-shrink: 0;
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

