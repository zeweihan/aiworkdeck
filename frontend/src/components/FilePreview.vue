<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
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
            v-if="canEdit && showEditBtn"
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

          <!-- EvidenceLink 引文定位卡（P3）：跳页由 pdfSrc 的 #page= 完成，这张卡负责
               「引文在本页哪儿」。有 rects 就按归一化坐标画在页位图上；没有 rects 就
               如实说未能定位，只给引文原文与复制按钮（让用户在阅读器里自己 Ctrl+F）。 -->
          <view v-if="pdfLocate && pdfLocateVisible" class="evidence-locate-card">
            <view class="elc-head">
              <text class="elc-title">{{ pdfLocate.page ? $t('files.locate.pdfPage', { page: pdfLocate.page }) : $t('files.locate.pdfNoPage') }}</text>
              <view class="elc-close" :title="$t('files.locate.close')" @tap="closePdfLocate"><text>×</text></view>
            </view>
            <template v-if="pdfLocate.rects.length">
              <text class="elc-sub">{{ $t('files.locate.rectsTitle') }}</text>
              <view class="elc-map">
                <view v-for="(r, i) in pdfLocate.rects" :key="i" class="elc-map-rect" :style="pdfMapRectStyle(r)"></view>
              </view>
            </template>
            <text v-else-if="pdfLocate.quote" class="elc-miss">{{ $t('files.locate.quoteNotFound') }}</text>
            <text v-if="pdfLocate.quote" class="elc-quote">{{ pdfLocate.quote }}</text>
            <view v-if="pdfLocate.quote" class="elc-copy" @tap="copyPdfQuote"><text>{{ $t('files.locate.copyQuote') }}</text></view>
          </view>
        </view>

        <!-- 图片/SVG 预览：缩放平移查看器。用原生 img 配 CSS transform 而不是 uni 的
             image 组件——transform 不好控。换文件时的状态重置见 resetImageViewState。
             滚轮/拖拽/双击绑在容器而不是 img 本身：图片小于容器时四周还有留白，
             绑在 img 上会让留白区域变成"死区"，滚轮/拖拽在那里没反应。
             滚轮与双击不写模板 @wheel/@dblclick：uni 会把事件重建成没有 deltaY/clientX 的
             普通对象（只能缩小、不能放大，dev-board#1014），改由 syncImageViewportListeners
             用原生 addEventListener 挂到真实元素上。 -->
        <view
          v-else-if="isImage"
          class="preview-image"
          :class="{ 'is-panning': imagePanning }"
          ref="imageViewport"
          @mousedown="handleImagePanStart"
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
          <!-- EvidenceLink 图片定位框（P3）：locator.rect 是 0..1 归一化坐标，按当前
               缩放/平移/旋转换算（imageRectBox）。框本身常驻——用户要缩放、旋转之后
               核对它还罩不罩得住那块内容；只有压暗周边的遮罩 3s 后淡掉。 -->
          <view
            v-if="evidenceRectStyle"
            class="evidence-rect"
            :class="{ 'is-undimmed': evidenceRectUndimmed }"
            :style="evidenceRectStyle"
            @click.stop="hideEvidenceRect"
          ></view>
          <view v-if="imageReady" class="image-toolbar" @mousedown.stop>
            <button class="img-tool-btn" size="mini" @tap="imageZoomOutBtn">−</button>
            <text class="img-zoom-pct">{{ imageZoomPercentText }}</text>
            <button class="img-tool-btn" size="mini" @tap="imageZoomInBtn">＋</button>
            <view class="img-tool-sep"></view>
            <button class="img-tool-btn img-tool-btn-text" size="mini" @tap="imageZoomActual">1:1</button>
            <button class="img-tool-btn img-tool-btn-text" size="mini" @tap="imageZoomFit">{{ $t('files.fitWindow') }}</button>
            <button class="img-tool-btn img-tool-btn-text" size="mini" @tap="imageRotate">{{ $t('files.rotate') }}</button>
            <template v-if="hasImageLocatorRect">
              <view class="img-tool-sep"></view>
              <button
                class="img-tool-btn img-tool-btn-text"
                :class="{ 'is-on': evidenceRectVisible }"
                size="mini"
                @tap="toggleEvidenceRect"
              >{{ $t('files.locate.imageRect') }}</button>
            </template>
          </view>
        </view>

        <!-- 视频 / 音频：统一自绘播放器（dev-board#1023/#1024/#1025）。媒体元素、取源（直链
             Range 流式，失败回退 blob）、控制条、字幕、快捷键与 EvidenceLink 时间定位都在
             components/media/MediaPlayer.vue；key 绑文件，换文件或文件内容更新即整份重建。 -->
        <view v-else-if="isVideo || isAudio" class="preview-media">
          <MediaPlayer
            ref="mediaPlayer"
            :key="mediaKey"
            :kind="isVideo ? 'video' : 'audio'"
            :file="file"
            :project-id="projectId"
            :locator-sec="mediaLocatorSec"
            @locator-consumed="onMediaLocatorConsumed"
            @error="onMediaError"
          />
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
import { previewKindOf } from '@/utils/media/mediaTypes.js'
import { shouldGrabMediaFocus } from '@/utils/media/mediaShortcuts.js'
import MediaPlayer from '@/components/media/MediaPlayer.vue'
import {
  parsePdfLocator, parseImageRect, parseMediaStartSec,
  imageTransform, imageRectBox, rotatedDisplaySize, normalizeRotation,
} from '@/utils/evidenceLocator.js'
import { imageWheelZoomFactor, imageEventAnchor } from '@/utils/imageWheelZoom.js'

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
  components: { MediaPlayer },
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
    },
    // 调用点若不接 @edit 就传 false 藏掉编辑按钮，否则会渲染一个点了没反应的死按钮
    showEditBtn: {
      type: Boolean,
      default: true
    },
    // 播放器登记转写（生成字幕）要项目 id；file 上带 projectId 时以 file 为准，这里是兜底
    projectId: {
      type: [String, Number],
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
      // 旋转（0/90/180/270，顺时针）：扫描件、手机拍的现场照常常是躺着的，
      // 定位框要跟着一起转（换算在 utils/evidenceLocator.js 的 imageRectBox）
      imageRotation: 0,
      // EvidenceLink 定位：locator prop 的本地副本 + 可见态。
      // 图片画框常驻（点框或工具栏按钮收起），只有压暗周边的遮罩 3s 后淡掉；
      // pdf 引文卡由用户显式关闭；音视频时间标记归 MediaPlayer 管。
      appliedLocator: null,
      evidenceRectVisible: false,
      evidenceRectUndimmed: false,
      pdfLocateVisible: false
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
    // 扩展名表单一来源：utils/media/mediaTypes.js（ogg 按音频，只有 ogv 当视频）
    isVideo() {
      return !!this.file && previewKindOf(this.file.fileType) === 'video'
    },
    isAudio() {
      return !!this.file && previewKindOf(this.file.fileType) === 'audio'
    },
    // 换文件或 AI 改完文件（wpsFileId 变）时播放器整份重建
    mediaKey() {
      const f = this.file
      return f ? 'media-' + f.id + '-' + (f.wpsFileId || '') : 'media-none'
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
    // 缩放平移旋转共用一套口径：imageTx/imageTy 是「旋转后外接框」的左上角，
    // 换算全在 utils/evidenceLocator.js（画框要跟它逐像素对齐，别在这儿另写一份）
    imageView() {
      return {
        natW: this.imageNaturalWidth,
        natH: this.imageNaturalHeight,
        scale: this.imageScale,
        tx: this.imageTx,
        ty: this.imageTy,
        rotation: this.imageRotation,
      }
    },
    imageTransformStyle() {
      return { transform: imageTransform(this.imageView) }
    },
    // 定位用的是 appliedLocator（prop 的本地副本）：宿主收到 locator-consumed 会把 prop 清空，
    // 直接读 prop 的话 pdf 的 #page= 会跟着掉、iframe 重载回第 1 页。
    pdfSrc() {
      const loc = this.pdfLocate
      return this.blobUrl + (loc && loc.page ? '#page=' + loc.page : '')
    },
    // {page, quote, rects}；缺字段的 locator（OCR 常见）在这里就退化成 null
    pdfLocate() {
      return parsePdfLocator(this.appliedLocator)
    },
    imageLocatorRect() {
      return parseImageRect(this.appliedLocator)
    },
    hasImageLocatorRect() {
      return !!this.imageLocatorRect
    },
    mediaLocatorSec() {
      return parseMediaStartSec(this.appliedLocator)
    },
    evidenceRectStyle() {
      if (!this.evidenceRectVisible || !this.imageReady) return null
      const box = imageRectBox(this.imageLocatorRect, this.imageView)
      if (!box) return null
      return {
        left: box.left + 'px',
        top: box.top + 'px',
        width: box.width + 'px',
        height: box.height + 'px'
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
    // 宿主 openFile(file, {locator}) 落到 tab.pendingLocator → 这里的 prop。收到即拷贝成
    // appliedLocator（pdf/image/media 三类都按它渲染），然后通知宿主 locator-consumed 清空
    // pendingLocator，避免切回标签重复跳转。同一文件再次被链接点中（换了时刻/页）走同一条路。
    locator: {
      immediate: true,
      handler(loc) {
        if (!loc) return
        this.applyLocator(loc)
      }
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
    this.clearEvidenceRectTimers()
    if (this.blobUrl) {
      URL.revokeObjectURL(this.blobUrl)
    }
    this.unbindImageViewportListeners()
    // 组件卸载时若正处于拖拽平移中，window 上的监听不会自己消失
    window.removeEventListener('mousemove', this.handleImagePanMove)
    window.removeEventListener('mouseup', this.handleImagePanEnd)
  },
  mounted() {
    console.log('FilePreview mounted, file:', this.file, 'fileUrl:', this.fileUrl)
    this.syncImageViewportListeners()
  },
  // .preview-image 是 v-else-if 分支：切文件（图片 → docx → 图片）、加载态切换都会重建它。
  // 每次重渲染后比对一次元素身份，换了就把原生监听挪过去（同一元素不重复挂）。
  updated() {
    this.syncImageViewportListeners()
  },
  methods: {
    // file watch 与 wpsFileId watch 共用的加载分发（原 file watch handler 逻辑原样抽出）
    reloadPreview(newFile) {
      // 换文件：定位副本随之作废（新文件的 locator 会经 prop watch 重新 apply）
      this.clearEvidenceRectTimers()
      this.appliedLocator = null
      this.evidenceRectVisible = false
      this.evidenceRectUndimmed = false
      this.pdfLocateVisible = false
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
      } else if (this.isImage || this.isPdf) {
        this.loadMediaResource()
      } else if (this.isArchive) {
        this.loadArchiveEntries()
      } else if (this.isVideo || this.isAudio) {
        // 音视频由 MediaPlayer 自己取源；这里只负责焦点无主时把快捷键接过来
        this.$nextTick(() => this.focusMediaPlayer())
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
      this.imageRotation = 0
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
    // EvidenceLink 定位入口（P3）：pdf 跳页 + 引文卡、image 画框、media seek 并停在那一帧。
    // 三类都可能缺字段（OCR 出来的坐标尤其），缺就什么都不做——退化成「只打开文件」。
    applyLocator(loc) {
      this.appliedLocator = loc
      this.clearEvidenceRectTimers()
      this.evidenceRectVisible = false
      this.evidenceRectUndimmed = false
      this.pdfLocateVisible = !!this.pdfLocate
      if (this.imageLocatorRect) {
        // 框常驻（用户要缩放、旋转之后核对它还罩不罩得住那块内容），
        // 只有压暗周边的遮罩 3s 后淡掉
        this.evidenceRectVisible = true
        this._rectFadeTimer = setTimeout(() => { this.evidenceRectUndimmed = true }, 3000)
      }
      // 音视频：同一文件再次被点中时播放器已在，要它重新落一次定位（时刻相同时
      // locatorSec 不变、播放器的 watch 不会触发）。等一帧让新的 locatorSec 先传下去。
      this.$nextTick(() => this.relocateMedia())
      const f = this.file
      if (f && f.id != null) this.$nextTick(() => this.$emit('locator-consumed', f.id))
    },
    hideEvidenceRect() {
      this.clearEvidenceRectTimers()
      this.evidenceRectVisible = false
    },
    toggleEvidenceRect() {
      if (this.evidenceRectVisible) { this.hideEvidenceRect(); return }
      // 重新亮出来时不再压暗周边：用户是主动要看框，不需要再引一次注意力
      this.clearEvidenceRectTimers()
      this.evidenceRectUndimmed = true
      this.evidenceRectVisible = true
    },
    clearEvidenceRectTimers() {
      if (this._rectFadeTimer) { clearTimeout(this._rectFadeTimer); this._rectFadeTimer = null }
    },
    closePdfLocate() {
      this.pdfLocateVisible = false
    },
    // 引文在页位图上的位置：归一化坐标直接落成百分比，不掺任何猜测
    pdfMapRectStyle(r) {
      return {
        left: (r.x * 100) + '%',
        top: (r.y * 100) + '%',
        width: Math.max(1.5, r.w * 100) + '%',
        height: Math.max(1.5, r.h * 100) + '%'
      }
    },
    // 复制引文：内置 PDF 引擎没有可编程的查找接口，复制出去让用户自己在阅读器里查找
    copyPdfQuote() {
      const q = this.pdfLocate && this.pdfLocate.quote
      if (!q) return
      uni.setClipboardData({
        data: q,
        success: () => { uni.showToast({ title: this.$t('files.locate.quoteCopied'), icon: 'none' }) },
        fail: () => { uni.showToast({ title: this.$t('files.locate.quoteCopyFailed'), icon: 'none' }) }
      })
    },
    relocateMedia() {
      const p = this.$refs.mediaPlayer
      if (p && typeof p.relocate === 'function') p.relocate()
    },
    // 播放器 seek 落地。宿主在 applyLocator 时已被通知过一次，这里再报一次是幂等的
    // （onLocatorConsumed 只清 pendingLocator），保证「定位真正落下」之后宿主状态一定干净。
    onMediaLocatorConsumed() {
      const f = this.file
      if (f && f.id != null) this.$emit('locator-consumed', f.id)
    },
    onMediaError(detail) {
      const status = detail && detail.status
      uni.showToast({
        title: status
          ? this.$t('files.resourceLoadFailedStatus', { status })
          : (this.isVideo ? this.$t('files.videoPlayFailed') : this.$t('files.audioPlayFailed')),
        icon: 'none'
      })
    },
    // 打开 / 切换音视频文件时把焦点交给播放器根元素，空格/方向键立刻可用。
    // 焦点在资源管理器（带 tabindex 的文件树）上也要接过来——否则按键全打到文件树，
    // ↑↓ 会切走选中文件把播放器卸掉；只有用户正在输入（输入框、可编辑区、编辑器 iframe）时不抢。
    focusMediaPlayer() {
      if (typeof document === 'undefined') return
      if (!shouldGrabMediaFocus(document.activeElement)) return
      const p = this.$refs.mediaPlayer
      if (p && typeof p.focusRoot === 'function') p.focusRoot()
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
    // 旋转后按「外接框」算适配与居中（横过来的扫描件宽高要对调）。
    applyImageView(mode) {
      const el = this.getImageViewportEl()
      if (!el || !this.imageNaturalWidth || !this.imageNaturalHeight) return
      const vw = el.clientWidth
      const vh = el.clientHeight
      const unit = rotatedDisplaySize(this.imageNaturalWidth, this.imageNaturalHeight, 1, this.imageRotation)
      if (!unit) return
      this.imageFitScale = this.clampImageScale(Math.min(vw / unit.w, vh / unit.h))
      const scale = mode === 'fit' ? this.imageFitScale : this.clampImageScale(1)
      this.imageScale = scale
      this.imageTx = (vw - unit.w * scale) / 2
      this.imageTy = (vh - unit.h * scale) / 2
    },
    // 顺时针 90°，转完重新「适应窗口」——转过之后原来的缩放平移已经没有参照意义了
    imageRotate() {
      if (!this.imageNaturalWidth) return
      this.imageRotation = normalizeRotation(this.imageRotation + 90)
      this.applyImageView('fit')
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
    // 滚轮与双击用原生监听挂到真实元素上（理由见模板注释与 utils/imageWheelZoom.js）。
    // wheel 必须 passive:false，否则 Chromium 按 passive 处理、preventDefault 被忽略。
    syncImageViewportListeners() {
      const el = this.getImageViewportEl()
      const target = el && typeof el.addEventListener === 'function' ? el : null
      if (target === this._imageListenEl) return
      this.unbindImageViewportListeners()
      if (!target) return
      this._imageWheelHandler = (e) => this.handleImageWheel(e)
      this._imageDblHandler = (e) => this.handleImageDblClick(e)
      target.addEventListener('wheel', this._imageWheelHandler, { passive: false })
      target.addEventListener('dblclick', this._imageDblHandler)
      this._imageListenEl = target
    },
    unbindImageViewportListeners() {
      const el = this._imageListenEl
      if (el) {
        el.removeEventListener('wheel', this._imageWheelHandler)
        el.removeEventListener('dblclick', this._imageDblHandler)
      }
      this._imageListenEl = null
    },
    handleImageWheel(e) {
      e.preventDefault()
      if (!this.imageNaturalWidth) return
      const el = this.getImageViewportEl()
      if (!el) return
      const factor = imageWheelZoomFactor(e)
      if (factor === 1) return
      const a = imageEventAnchor(e, el.getBoundingClientRect())
      this.zoomImageTo(this.imageScale * factor, a.x, a.y)
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
      const target = Math.abs(this.imageScale - 1) < 0.001 ? this.imageFitScale : 1
      const a = imageEventAnchor(e, el.getBoundingClientRect())
      this.zoomImageTo(target, a.x, a.y)
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
  background-color: var(--awd-surface);
}

.preview-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--awd-text-3);
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
  border-bottom: 1rpx solid var(--awd-border);
  background-color: var(--awd-surface);
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
  color: var(--awd-text);
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
  color: var(--awd-text-2);
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

.preview-pdf {
  position: relative;
}

/* EvidenceLink 引文定位卡（P3）：浮在原生 PDF 视图右上角。
   刻意不去猜内置 PDF 引擎的排版几何——它是不透明插件，页面的实际像素位置读不到，
   照着猜画出来的高亮会偏到别的行上，那是假高亮。这里只画能算准的两样：
   跳到了第几页（阅读器自己完成）、引文在页面上的归一化位置（页位图）。 */
.evidence-locate-card {
  position: absolute;
  top: 16rpx;
  right: 16rpx;
  z-index: 5;
  width: 380rpx;
  box-sizing: border-box;
  padding: 16rpx;
  background: var(--awd-surface);
  border: 1rpx solid var(--awd-border);
  border-left: 6rpx solid var(--awd-accent);
  border-radius: 10rpx;
  box-shadow: 0 6rpx 20rpx rgba(15, 23, 42, 0.16);
}

.elc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
}

.elc-title {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--awd-accent-text);
}

.elc-close {
  padding: 0 8rpx;
  font-size: 28rpx;
  line-height: 1;
  color: var(--awd-text-3);
  cursor: pointer;
}

.elc-close:hover {
  color: var(--awd-text);
}

.elc-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 20rpx;
  color: var(--awd-text-2);
}

/* 页位图：A4 竖版比例的纸面示意，框按归一化坐标落百分比 */
.elc-map {
  position: relative;
  width: 180rpx;
  height: 254rpx;
  margin: 8rpx 0;
  background: var(--awd-surface);
  border: 1rpx solid var(--awd-border-strong);
}

.elc-map-rect {
  position: absolute;
  box-sizing: border-box;
  background: var(--awd-accent);
  border: 1rpx solid var(--awd-accent);
}

.elc-miss {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: var(--awd-danger-text);
}

.elc-quote {
  display: block;
  margin-top: 8rpx;
  padding: 8rpx 10rpx;
  max-height: 160rpx;
  overflow: hidden;
  background: var(--awd-bg);
  border-radius: 6rpx;
  font-size: 22rpx;
  line-height: 1.5;
  color: var(--awd-text);
  word-break: break-all;
}

.elc-copy {
  display: inline-block;
  margin-top: 10rpx;
  padding: 6rpx 16rpx;
  border: 1rpx solid var(--awd-border-strong);
  border-radius: 6rpx;
  font-size: 22rpx;
  color: var(--awd-text);
  cursor: pointer;
}

.elc-copy:hover {
  background: var(--awd-surface-2);
}

/* Word 文档零配置只读渲染容器（docx-preview） */
.preview-docx {
  width: 100%;
  height: 100%;
  overflow: auto;
  background-color: var(--awd-surface-2);
}

/* PPTX 零配置只读渲染容器（pptx-preview） */
.preview-pptx {
  width: 100%;
  height: 100%;
  overflow: auto;
  background-color: var(--awd-surface-2);
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
  color: var(--awd-text-2);
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
  color: var(--awd-text-3);
  margin-top: 8rpx;
}

.preview-image {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  padding: 24rpx;
  /* uni 的 <view> 是 content-box：不收进来的话 100% + 上下 padding 会撑出 .preview-body，
     贴底的缩放工具条被推到状态栏下面（dev-board#972） */
  box-sizing: border-box;
  background: #282828;
  cursor: grab;
}

.preview-image.is-panning {
  cursor: grabbing;
}

/* EvidenceLink 图片定位框：跟随 img 的平移缩放旋转。框常驻，只有压暗周边的
   遮罩（那圈超大 box-shadow）3s 后撤掉——长时间压暗会让整张底稿没法看。 */
.evidence-rect {
  position: absolute;
  box-sizing: border-box;
  border: 2px solid var(--awd-accent);
  background: var(--awd-surface-2);
  box-shadow: 0 0 0 9999px rgba(15, 23, 42, 0.18);
  cursor: pointer;
  transition: box-shadow 0.4s ease;
}
.evidence-rect.is-undimmed {
  box-shadow: none;
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
  /* 窄窗格（分屏 / 开着 AI 面板）下 left:50% 只剩一半可用宽度，不加这行会把「适应窗口」挤成「适」 */
  width: max-content;
  display: flex;
  align-items: center;
  gap: 4rpx;
  padding: 8rpx 12rpx;
  background: var(--awd-surface);
  border: 1rpx solid var(--awd-border);
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
  color: var(--awd-text);
  cursor: pointer;
}

.img-tool-btn:hover {
  background: var(--awd-surface-2);
}

.img-tool-btn-text {
  font-size: 22rpx;
  padding: 0 12rpx;
}

/* 定位框开关的按下态 */
.img-tool-btn.is-on {
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
}

.img-zoom-pct {
  min-width: 76rpx;
  text-align: center;
  font-size: 22rpx;
  color: var(--awd-text-2);
}

.img-tool-sep {
  width: 1rpx;
  height: 28rpx;
  background: var(--awd-surface-3);
  margin: 0 4rpx;
}

.preview-text {
  padding: 24rpx;
  overflow-y: auto;
  height: 100%;
}

.text-content {
  font-size: 28rpx;
  color: var(--awd-text);
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
  color: var(--awd-text-3);
  font-size: 28rpx;
}

/* 音视频：MediaPlayer 占满预览区（播放器内部尺寸一律 px，见 components/media/） */
.preview-media {
  width: 100%;
  height: 100%;
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
  border-bottom: 1rpx solid var(--awd-border);
}

.archive-count {
  font-size: 24rpx;
  color: var(--awd-text-2);
}

.archive-status {
  padding: 32rpx;
  text-align: center;
  color: var(--awd-text-2);
  font-size: 28rpx;
}

.archive-error {
  color: var(--awd-danger-text);
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
  border-bottom: 1rpx solid var(--awd-border-subtle);
}

.entry-icon {

  width: 28rpx;
  height: 28rpx;
  flex-shrink: 0;
}

.entry-path {
  flex: 1;
  font-size: 26rpx;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-size {
  font-size: 24rpx;
  color: var(--awd-text-3);
}
</style>

