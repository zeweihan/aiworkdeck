<template>
  <view class="libre-editor-wrapper" :class="{ 'evidence-drop-armed': evidenceDropArmed }">
    <!-- NO full-width bar — it read as alien chrome on top of the document
         (user feedback). Status floats over the editor's top-right corner;
         the pill only appears while something is happening (saving/failure)
         and vanishes when ready. -->
    <!-- 加载进度面板：引擎启动 + 文档下载/打开是感知最慢的一段（尤其大文档），
         把过程阶段化展示出来（用户反馈：不能更快，也要看得见进展）。 -->
    <view v-if="loadingOverlayVisible" class="libre-loading">
      <!-- 只读预览接力：文档字节一到就先用 docx-preview 本地渲出可读内容，
           引擎继续后台 boot；就绪后 overlay 整体消失、无缝换入可编辑视图。
           渲染失败/非 Word/空文档保持原进度卡片。 -->
      <view v-show="previewReady" class="libre-preview-strip">
        <view class="libre-strip-track"><view class="libre-strip-fill" :style="{ width: bootPct + '%' }"></view></view>
        <text class="libre-strip-text">{{ bootStageText }} {{ Math.round(bootPct) }}% — {{ $t('editor.previewReadableHint') }}</text>
      </view>
      <view v-show="previewReady" ref="docxPreviewHost" class="libre-preview-host"></view>
      <view v-if="!previewReady" class="libre-loading-card">
        <view class="libre-doc-icon">
          <view class="doc-fold"></view>
          <view class="doc-line l1"></view>
          <view class="doc-line l2"></view>
          <view class="doc-line l3"></view>
        </view>
        <text class="libre-loading-name">{{ loadingTitle }}</text>
        <view class="libre-progress-track">
          <view class="libre-progress-fill" :style="{ width: bootPct + '%' }">
            <view class="libre-progress-shimmer"></view>
          </view>
        </view>
        <view class="libre-loading-meta">
          <text class="libre-loading-stage">{{ bootStageText }}</text>
          <text class="libre-loading-pct">{{ Math.round(bootPct) }}%</text>
        </view>
        <text v-if="dlText" class="libre-loading-dl">{{ dlText }}</text>
        <text v-if="!stuck" class="libre-loading-hint">{{ $t('editor.firstOpenHint') }}</text>
        <!-- 同一阶段长时间无进展（大概率是下载/装载卡住）：给出可点的出路，
             而不是让用户对着一根不动的进度条干等。 -->
        <view v-if="stuck" class="libre-loading-retry" @click="retryLoad">
          <text>{{ $t('editor.retryLoad') }}</text>
        </view>
      </view>
    </view>
    <!-- The Electron <webview> is created imperatively (uni-app's template
         compiler does not know the <webview> tag); it mounts into this host.
         审阅面板与画布并排（挤宽而非浮层）——webview 是独立合成层，浮层压在
         上面的行为不可靠。 -->
    <!-- 自建工具栏（P2a）：只加不减——LO 自己的菜单栏/工具栏这一期原样保留，
         等自建这套功能覆盖到位再谈隐藏（体验不能退步是硬约束）。
         Calc/Impress 的命令面完全不同，先只给 Writer。 -->
    <!-- file 判空不能省：预热备胎（file=null）也会走到 ready，工具栏挂上去就会
         对着一个隐藏的空白实例白跑 get_ui_state/list_styles/list_fonts。 -->
    <EditorToolbar
      v-if="ready && file && !loadingOverlayVisible && docKind === 'writer'"
      ref="toolbar"
      :executor="executor"
      :refresh-key="uiRefreshKey"
      :review-open="reviewOpen"
      @toggle-review="reviewOpen = !reviewOpen"
      @changed="onDocModified"
      @ui-state="$emit('menu-state')"
    />
    <view class="libre-body">
      <!-- 浮层必须钉在**画布**上而不是整个编辑器上：审阅面板是并排挤宽的，钉在
           外层右上角会正好压住面板的「修订/批注」标题行（真机截图实证）。 -->
      <view class="libre-canvas-wrap">
        <view :id="hostId" class="libre-host"></view>
        <!-- 证据关联投放层（EvidenceLink）：画布是 webview/iframe，HTML5 拖放事件落在它
             的文档里而不是宿主，外层容器直接绑 @drop 收不到。所以文件树/暂存区一开始
             拖（uni 事件 file-drag-start）就在画布上铺一层透明接收层，drop 落在这层上。
             选区在拖之前已经选好，接收层挡住画布不影响建链。 -->
        <view
          v-if="evidenceDropArmed && ready && file"
          class="libre-evidence-drop"
          :class="{ over: evidenceDropOver }"
          @dragenter.prevent="onEvidenceDragOver"
          @dragover.prevent="onEvidenceDragOver"
          @dragleave="onEvidenceDragLeave"
          @drop.prevent="onEvidenceDrop"
        >
          <text class="libre-evidence-hint">{{ $t('workbench.evidence.dropHint') }}</text>
        </view>
        <view class="libre-float">
          <!-- No manual save button: edits auto-save (modify listener → debounced
               saveDocument). 保存状态只在「慢」和「失败」时出声——见 saveDocument。 -->
          <view v-if="displayStatus && !loadingOverlayVisible" class="libre-pill" :class="{ error: isError }">
            <view v-if="!isError && !ready" class="libre-spin"></view>
            <text>{{ displayStatus }}</text>
          </view>
          <!-- 审阅面板开关：页边小字读不到作者/时间，面板才是修订的权威视图。
               Calc/Impress 都没有修订（redline）机制，按 docKind 隐藏——不能只是点了没反应。
               Writer 上这个开关已经在自建工具栏里，浮层不再重复一个。 -->
          <text v-if="ready && !loadingOverlayVisible && showsReview && docKind !== 'writer'"
                class="libre-review-btn" :class="{ on: reviewOpen }"
                @tap="reviewOpen = !reviewOpen">{{ $t('editor.reviewBtn') }}</text>
        </view>
      </view>
      <ReviewPanel
        v-if="reviewOpen && ready && showsReview"
        ref="review"
        :executor="executor"
        :refresh-key="reviewRefreshKey"
        @close="reviewOpen = false"
        @changed="onReviewChanged"
      />
    </view>
  </view>
</template>

<script>
// LibreOfficeEditor.vue — HOST-side embed of the LibreOffice editor as an
// Electron <webview partition="persist:zetaoffice"> inside the MAIN renderer.
// Epic #43.
//
// The webview loads dist/zetaoffice/editor.html over the shared same-origin
// server, gets COOP/COEP isolation from desktop/main/zetaoffice-session.js (so
// LOWA's SharedArrayBuffer works), and is wrapped by createWebviewEditorExecutor
// (#52) into the standard executeCommand(action, params) contract. Mounted only
// by the project-overview keep-alive pool as the product inline document editor
// （原 ⌘⇧O 实验覆盖层与探针工具栏已移除）.

import { webviewTransport, iframeTransport } from '@/composables/useZetaOfficeWebview.js'
import { createRelayExecutor } from '@/composables/zetaOfficeRelay.js'
import ReviewPanel from '@/components/ReviewPanel.vue'
import EditorToolbar from '@/components/EditorToolbar.vue'
import { getFileDownloadUrl, getFileUploadUrl } from '@/services/api.js'
import { getAuthHeaders, getCurrentUser } from '@/utils/auth.js'
import { host } from '@/services/host.js'

let seq = 0

export default {
  name: 'LibreOfficeEditor',
  components: { ReviewPanel, EditorToolbar },
  emits: ['close', 'ready', 'open-url', 'menu-state', 'evidence-drop', 'locator-consumed'],
  props: {
    // Track D: the Office file to load into the editor ({ id, name, fileType,
    // wpsFileId }). When set, the editor fetches its bytes (authed) and loads the
    // REAL document once the office endpoint is ready.
    // （原 'experimental' ⌘⇧O 探针工具栏变体已移除：产品只有内联编辑器一种形态。）
    file: { type: Object, default: null },
  },
  data() {
    return {
      hostId: 'libre-host-' + (++seq),
      ready: false,
      // EvidenceLink 拖放：armed = 项目内有文件正在被拖（uni file-drag-start/end），
      // over = 指针正悬在本画布的接收层上（描边高亮）。
      evidenceDropArmed: false,
      evidenceDropOver: false,
      // 当前文档内核类型（writer/calc/impress/unknown），worker load_document 返回值
      // 里带回；boot 出来的空白文档恒为 writer，装真文档前保持这个默认值不算错。
      // 审阅按钮/ReviewPanel 按它隐藏——Calc/Impress 都没有修订机制。
      docKind: 'writer',
      // 审阅面板（修订/批注）开关与刷新信号
      reviewOpen: false,
      reviewRefreshKey: 0,
      // 自建工具栏的激活态刷新信号。由「选区/光标动了」和「文档改了」驱动——
      // 没有轮询：编辑器页把 worker 的选区监听与 IME 覆盖层的光标移动合流成
      // 一个 selection 事件送过来，覆盖了用户能让光标动起来的所有途径。
      uiRefreshKey: 0,
      // 状态用 key 存（editor.status.* 命名空间），显示时经 $t 取文案；
      // isError/displayStatus 与「安静保存」的自比较全部按 key 判断，
      // zh 渲染结果与判定行为都与迁移前逐字节一致。
      statusKey: 'booting',
      log: '',
      webviewEl: null,
      executor: null,
      saving: false,
      // Autosave: set by the worker's modify signal, cleared when a save starts;
      // read by the host (closeFile / evict) to know if a flush is needed.
      dirty: false,
      // 数据安全闸：load_document 失败（或非空文件下载到 0 字节）后置位。此时
      // worker 里还是空白 boot 文档，任何保存都会用空文档覆盖后端真文件——
      // autosave/flushSave 一律拒绝。不能复用 isError（'保存失败' 也含'失败'，
      // 会误杀保存重试）。
      docLoadFailed: false,
      // 加载进度面板状态：bootPct 按里程碑推进（其间缓慢滴答，避免看起来卡死），
      // bootCap 是当前阶段允许滴到的上限；dl* 是文档字节下载进度。
      bootPct: 3,
      bootCap: 12,
      bootStageKey: 'engineStarting',
      dlLoaded: 0,
      dlTotal: 0,
      // 只读预览接力：字节预取完成后 docx-preview 渲染成功置 previewReady，
      // overlay 从进度卡片切换为可滚动阅读的文档 + 顶部细进度条。
      previewReady: false,
      previewFailed: false,
      // 同一 bootStageKey 停留超过约 30s（下载挂起等场景）时置位，露出重试按钮。
      stuck: false,
    }
  },
  computed: {
    isError() {
      return this.statusKey.endsWith('Failed')
    },
    // e2e 契约：tests/desktop-e2e/run.mjs 从组件实例读 ed.statusText 并按中文
    // 状态串（就绪/加载文档中/失败）判定——statusKey 化后保留这个只读
    // 别名，zh 语言下取值与迁移前逐字节一致。改名/删除前先改该套件。
    statusText() {
      return this.$t('editor.status.' + this.statusKey)
    },
    // Calc/Impress 都没有修订（redline）机制——审阅面板对它们没有数据可展示。
    showsReview() {
      return this.docKind !== 'calc' && this.docKind !== 'impress'
    },
    // Stays quiet once ready — no permanent "就绪" badge.
    displayStatus() {
      return this.statusKey === 'ready' ? '' : this.$t('editor.status.' + this.statusKey)
    },
    bootStageText() {
      return this.$t('editor.boot.' + this.bootStageKey)
    },
    loadingOverlayVisible() {
      // 「仅桌面版可用」是终态（h5 预览等场景），不是加载中——不展示进度面板
      return !this.ready && !this.isError && this.statusKey !== 'desktopOnly'
    },
    loadingTitle() {
      return (this.file && this.file.name) ? this.file.name : this.$t('editor.preparingEditor')
    },
    dlText() {
      if (!this.dlLoaded) return ''
      const fmt = (n) => n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'
      return this.dlTotal > 0
        ? this.$t('editor.dlProgress', { loaded: fmt(this.dlLoaded), total: fmt(this.dlTotal) })
        : this.$t('editor.dlLoadedOnly', { size: fmt(this.dlLoaded) })
    },
  },
  watch: {
    // 预热备胎过继（librePool.js）：宿主把 file 从 null 换成真实文档。引擎
    // 可能已空白就绪（_endpointUp），也可能仍在 boot——后者只预取字节，
    // onEndpointReady 会照常装载。file→file 换文档不支持（池按实例=文档）。
    file(newFile, oldFile) {
      if (!newFile || oldFile) return
      this.prefetchBytes()
      if (!this._endpointUp) return
      this.ready = false
      this.docLoadFailed = false
      this.statusKey = 'loadingDoc'
      this.bootPct = 75
      this.bootCap = 95
      this.bootStageKey = 'openingDoc'
      this._stageChangedAt = Date.now()
      this.stuck = false
      this.startBootTrickle()
      this.finishDocLoad()
    },
    // 菜单栏读勾选/置灰的三个信号。合并在这个 watch 里而不是另起一块——
    // 选项对象里两个同名 key，后写的会把先写的整个覆盖掉。
    reviewOpen() { this.$emit('menu-state') },
    ready(v) { this.$emit('menu-state'); if (v) this.consumeLocator() },
    docKind() { this.$emit('menu-state') },
    // 宿主 openFile(file, {locator}) 把定位符挂在 tab 对象上；已打开的标签再次被
    // 链接点中时是原地换对象，靠这个路径 watch 触发。
    'file.pendingLocator'(loc) { if (loc) this.consumeLocator() },
  },
  async mounted() {
    this._onEvidenceDragStart = () => { this.evidenceDropArmed = true }
    this._onEvidenceDragEnd = () => { this.evidenceDropArmed = false; this.evidenceDropOver = false }
    uni.$on('file-drag-start', this._onEvidenceDragStart)
    uni.$on('file-drag-end', this._onEvidenceDragEnd)
    try {
      const api = host.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusKey = 'unsupported'
        return
      }
      // Prefetch the document bytes IN PARALLEL with the LOWA boot — the fetch
      // (backend download) and the engine boot are independent, so serializing
      // them (the old flow: boot → endpoint ready → fetch → load) just added the
      // whole download to the perceived open time.
      if (this.file) this.prefetchBytes()
      this.startBootTrickle()
      // { kind:'webview', url, preload, partition } | { kind:'iframe', url }
      const info = await api.getEditor()
      this.mountEditor(info)
    } catch (e) {
      this.statusKey = 'initFailed'
      this.appendLog('init failed: ' + (e && e.message ? e.message : e))
    }
  },
  beforeUnmount() {
    uni.$off('file-drag-start', this._onEvidenceDragStart)
    uni.$off('file-drag-end', this._onEvidenceDragEnd)
    // Autosave timers die with the instance. Any still-dirty edits were flushed
    // by the closer (closeFile / evictLibreInstance await flushSave first) —
    // export needs the live webview, so saving from here is already too late.
    clearTimeout(this._saveTimer)
    clearTimeout(this._slowSaveTimer)
    clearInterval(this._bootTimer)
    // Tell the host this editor (and its executor) is going away — so it can
    // stop routing AI commands to a disposed executor when the document tab is
    // closed or switched. The executor ref lets the host ignore stale closes.
    try { this.$emit('close', this.executor) } catch (e) { /* ignore */ }
    try { if (this.executor && typeof this.executor.dispose === 'function') this.executor.dispose() } catch (e) { /* ignore */ }
    // iframe 传输的订阅挂在 window 上，不随元素移除而消失——必须显式退订，
    // 否则关一个文档漏一个监听器，且已销毁实例的 onDocModified 还会被触发。
    try { if (this._eventUnsub) this._eventUnsub() } catch (e) { /* ignore */ }
    this._eventUnsub = null
    try { if (this.webviewEl && this.webviewEl.remove) this.webviewEl.remove() } catch (e) { /* ignore */ }
    this.webviewEl = null
    this.executor = null
  },
  methods: {
    // ---- EvidenceLink：拖放接收层 ----
    onEvidenceDragOver(e) {
      this.evidenceDropOver = true
      try { if (e && e.dataTransfer) e.dataTransfer.dropEffect = 'link' } catch (err) { /* ignore */ }
    },
    onEvidenceDragLeave() {
      this.evidenceDropOver = false
    },
    onEvidenceDrop(e) {
      this.evidenceDropOver = false
      this.evidenceDropArmed = false
      let file = null
      try {
        const raw = e && e.dataTransfer ? e.dataTransfer.getData('application/x-checkba-file') : ''
        if (raw) file = JSON.parse(raw)
      } catch (err) { file = null }
      if (!file) {
        try {
          const raw2 = e && e.dataTransfer ? e.dataTransfer.getData('text/checkba-file-json') : ''
          if (raw2) file = JSON.parse(raw2)
        } catch (err) { file = null }
      }
      // webview 环境下 dataTransfer 可能被清空：退回 FileTree/暂存区留在 document 上的全局兜底
      if (!file && typeof document !== 'undefined' && document.__checkbaDraggedFile) {
        file = { ...document.__checkbaDraggedFile }
        document.__checkbaDraggedFile = null // 消费即清，免得下一次落空的 drop 捡到陈文件
      }
      if (!file || file.fileType === 'folder' || file.isFolder) return
      const id = file.id != null ? file.id : file.fileId
      if (!id) return
      this.$emit('evidence-drop', { file: { ...file, id } })
    },
    // ---- EvidenceLink：消费 pendingLocator（docx：书签优先，其次 quote 查找）----
    async consumeLocator() {
      const f = this.file
      const loc = f && f.pendingLocator
      if (!loc || !this.ready || !this.executor || this._consumingLocator) return
      this._consumingLocator = true
      const exec = (action, params) => this.executor.executeCommand(action, params)
      try {
        let done = false
        if (loc.bookmark) {
          try {
            const r = await exec('goto_bookmark', { name: String(loc.bookmark) })
            done = !!(r && r.success)
          } catch (e) { done = false }
        }
        if (!done && loc.quote) {
          const found = await exec('find_text_locations', { keyword: String(loc.quote).slice(0, 200) })
          const first = found && Array.isArray(found.matches) && found.matches[0]
          if (first && first.anchorId) await exec('set_selection', { anchor: first.anchorId })
        }
      } catch (e) {
        this.appendLog('locator failed: ' + (e && e.message ? e.message : e))
      } finally {
        this._consumingLocator = false
        this.$emit('locator-consumed', f.id)
      }
    },
    // ---- 菜单栏命令入口 ----------------------------------------------------
    // 「文档」菜单经 appMenuBridge → project-overview 的活跃编辑器 → 这里。
    // 一律薄转发到工具栏/审阅面板已有的方法，不在这层复制业务逻辑——
    // 菜单和工具栏点同一个按钮必须是同一条代码路径，否则两边会各自漂。

    /** 这个实例现在能接命令吗（引擎起来了、装着真文档、是 Writer）。 */
    menuReady() {
      return !!(this.ready && this.file && this.docKind === 'writer')
    },
    /** 供菜单读勾选态。工具栏没挂（非 Writer / 未就绪）时全 false。 */
    menuState() {
      const tb = this.$refs.toolbar
      const rec = tb && tb.state && tb.state.view ? tb.state.view.recordChanges : false
      return { trackChanges: !!rec, reviewOpen: !!this.reviewOpen }
    },
    menuToggleTrackChanges() {
      const tb = this.$refs.toolbar
      return tb ? tb.toggleTrack() : null
    },
    menuToggleReviewPanel() {
      this.reviewOpen = !this.reviewOpen
    },
    menuOpenFind() {
      const tb = this.$refs.toolbar
      if (tb && !tb.findOpen) return tb.toggleFind()
    },
    /**
     * 插入批注。批注表单长在工具栏「插入」下拉里，所以要先把下拉打开再进表单，
     * 否则 startComment() 只是改了个不可见的状态 = 点了没反应。
     * 没选中文字时引擎不接受批注，这里把原因回给调用方去提示，别静默吞掉。
     */
    menuInsertComment() {
      const tb = this.$refs.toolbar
      if (!tb) return { ok: false, reason: 'not-ready' }
      if (tb.noSelection) return { ok: false, reason: 'no-selection' }
      tb.menu = 'insert'
      tb.startComment()
      return { ok: true }
    },
    menuClearFormatting() {
      const tb = this.$refs.toolbar
      return tb ? tb.ui('clear_formatting') : null
    },
    /**
     * 接受/拒绝全部修订。ReviewPanel 是 v-if 挂载的（面板关着就不存在），
     * 所以先把面板打开再调——顺带让用户看见改动落在哪，比默默改完更好。
     */
    async menuResolveAllRevisions(action) {
      if (!this.showsReview) return null
      if (!this.reviewOpen) {
        this.reviewOpen = true
        await this.$nextTick()
      }
      const rp = this.$refs.review
      return rp ? rp.resolveAll(action) : null
    },

    // ---- 加载进度面板 ----
    // 里程碑之间用慢速滴答填充（封顶 bootCap），避免长阶段（WASM 编译/大文档
    // 排版）看起来像卡死；真正的阶段跳变由 boot-log 里程碑驱动。
    startBootTrickle() {
      clearInterval(this._bootTimer)
      if (!this._stageChangedAt) this._stageChangedAt = Date.now()
      this._bootTimer = setInterval(() => {
        if (this.ready || this.isError) { clearInterval(this._bootTimer); return }
        if (this.bootPct < this.bootCap) this.bootPct = Math.min(this.bootCap, this.bootPct + 0.6)
        // 同一阶段停了太久（典型是文档下载挂起）：亮出重试按钮，别让用户对着
        // 一根不动的进度条干等——超时/下载失败已有 fetchArrayBuffer 的 reject
        // 路径兜底，这里是给"没有报错、就是卡住"的情形一个出路。
        if (!this.stuck && Date.now() - this._stageChangedAt > 30000) this.stuck = true
      }, 400)
    },
    bootMilestone(base, cap, stageKey) {
      if (this.ready) return
      this.bootPct = Math.max(this.bootPct, base)
      this.bootCap = Math.max(this.bootCap, cap)
      if (stageKey && stageKey !== this.bootStageKey) {
        this.bootStageKey = stageKey
        this._stageChangedAt = Date.now()
        this.stuck = false
      }
    },
    // 手动重试：引擎已就绪的话就是文档下载/装载卡住了，重新走一次装载路径
    // （与备胎过继 watch:file 那条路同形制）；引擎自己还没起来则没有可重放的
    // 下载动作，只重置计时器继续等待，30s 后按钮会再次出现。
    retryLoad() {
      this.stuck = false
      this._stageChangedAt = Date.now()
      if (this._endpointUp) {
        this.appendLog('用户点击重试 / retry requested')
        this._bytesPromise = null
        this.dlLoaded = 0
        this.dlTotal = 0
        this.statusKey = this.file ? 'loadingDoc' : 'booting'
        this.bootStageKey = this.file ? 'openingDoc' : 'almostReady'
        this.bootCap = Math.max(this.bootCap, 95)
        this.startBootTrickle()
        this.finishDocLoad()
      } else {
        this.startBootTrickle()
      }
    },
    // 注意：这里匹配的是引擎 boot-log 的原始消息（含中文），是引擎侧判据，
    // 不随界面语言变化——匹配串一个字都不能动。
    onBootLog(m) {
      if (!m) return
      if (m.indexOf('CJK font fetched') !== -1) this.bootMilestone(15, 30, 'cjkFonts')
      else if (m.indexOf('soffice.js loaded') !== -1) this.bootMilestone(32, 55, 'layoutEngine')
      else if (m.indexOf('thread port ready') !== -1) this.bootMilestone(58, 70, 'docService')
      else if (m.indexOf('空白文档就绪') !== -1 || m.indexOf('UI ready') !== -1) {
        this.bootMilestone(72, 85, this.file ? 'openingDoc' : 'almostReady')
      } else if (m.indexOf('load_document: 已加载真实文档') !== -1) {
        this.bootMilestone(96, 99, 'rendering')
      }
    },
    appendLog(m) {
      // Mirror to devtools so the product variant (overlay hidden) stays diagnosable.
      console.log('[libre-editor]', m)
      this.log = (this.log + m + '\n').split('\n').slice(-200).join('\n')
    },
    // 按宿主给出的容器类型挂编辑器：桌面壳是 Electron <webview>（隔离分区 +
    // preload），Web 服务器版是同源 <iframe>。两者之外的一切——relay 协议、
    // executor 契约、worker、自动保存链路——完全共用。
    mountEditor(info) {
      const mountEl = document.getElementById(this.hostId)
      if (!mountEl) { this.appendLog('host element missing'); return }
      const el = info.kind === 'iframe' ? this.createIframe(info) : this.createWebview(info)
      el.style.width = '100%'
      el.style.height = '100%'
      el.style.border = '0'
      mountEl.appendChild(el)
      this.webviewEl = el
    },
    createWebview(info) {
      const wv = document.createElement('webview')
      wv.setAttribute('partition', info.partition)
      if (info.preload) wv.setAttribute('preload', info.preload)
      // contextIsolation ON (the preload uses contextBridge), nodeIntegration OFF.
      wv.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no')
      const transport = webviewTransport(wv)
      // 事件订阅必须在建元素时就挂上，绝不能推迟到 dom-ready：boot-log 里程碑
      // 从引擎启动第一刻就在发，modified 是自动保存的唯一触发信号——晚挂一步
      // 就会丢掉开头那些消息（丢 modified = 用户的编辑不落盘）。
      this.subscribeHostEvents(transport)
      // dom-ready 只说明 webview 渲染进程起来了；命令通道在此接（此前 send 无处可去）。
      wv.addEventListener('dom-ready', () => {
        if (this.executor) return // dom-ready 可能因页内导航重复触发
        this.wireExecutor(transport, 'webview dom-ready')
      })
      wv.addEventListener('did-fail-load', (e) => this.appendLog('did-fail-load: ' + (e.errorDescription || e.errorCode)))
      wv.addEventListener('console-message', (e) => { if (e.level >= 2) this.appendLog('[webview] ' + e.message) })
      wv.setAttribute('src', info.url)
      return wv
    },
    createIframe(info) {
      const fr = document.createElement('iframe')
      // 同源 iframe：LOWA 要 SharedArrayBuffer，跨源隔离由站点级 COOP/COEP 提供
      // （deploy/web/nginx.conf.example），不是靠 iframe 属性。这里不能加 sandbox
      // ——沙箱会掐掉 SharedArrayBuffer 与 Worker，引擎起不来。
      fr.setAttribute('allow', 'clipboard-read; clipboard-write')
      // <iframe> 没有 dom-ready；订阅的是 window 的 message 事件，建元素时就能
      // 全部挂上（比 webview 更早更稳），命令通道也不必等。
      const transport = iframeTransport(fr)
      this.subscribeHostEvents(transport)
      this.wireExecutor(transport, 'iframe mounted')
      fr.addEventListener('error', () => this.appendLog('iframe load error: ' + info.url))
      fr.src = info.url
      return fr
    },
    // 事件通道：与命令共用同一个传输，另挂一个订阅者。
    // (#79) 文档超链接点击由页面侧转成 {type:'open-url'}；modified 驱动自动保存；
    // boot-log 推进加载进度面板。
    subscribeHostEvents(transport) {
      this._eventUnsub = transport.subscribe((msg) => {
        if (!msg || msg.__lo !== 'lo-relay') return
        if (msg.type === 'open-url' && msg.url) {
          this.$emit('open-url', String(msg.url))
        } else if (msg.type === 'modified') {
          this.onDocModified()
        } else if (msg.type === 'selection') {
          // 光标/选区动了：工具栏重读激活态。不标脏——移动光标不是修改文档。
          if (this.ready) this.uiRefreshKey++
        } else if (msg.type === 'boot-log') {
          this.onBootLog(String(msg.msg || ''))
        }
      })
    },
    // 命令通道：把 {send, subscribe} 传输接成 executor。两种容器共用。
    wireExecutor(transport, whence) {
      try {
        // onReady 握手到达前不推 load_document，否则会被丢（页面侧 serveExecutor
        // 还没订阅、office 也没 boot）。
        this.executor = createRelayExecutor({
          send: transport.send,
          subscribe: transport.subscribe,
          onReady: () => this.onEndpointReady(),
          onLateResult: (action, result) => this.onLateLoadResult(action, result),
        })
        // 命令繁忙跟踪：AI 命令与用户输入都走这同一个 executor。autoSave 据此
        // 避开活跃期（export_document 会冻结 office 线程上的 Qt 事件循环）。
        this._cmdBusy = 0
        this._lastCmdAt = 0
        const innerExec = this.executor.executeCommand.bind(this.executor)
        this.executor.executeCommand = async (action, params) => {
          if (action === 'export_document') return innerExec(action, params) // 保存自身不算「活跃编辑」
          this._cmdBusy++
          this._lastCmdAt = Date.now()
          try { return await innerExec(action, params) }
          finally { this._cmdBusy--; this._lastCmdAt = Date.now() }
        }
        this.statusKey = this.file ? 'loadingDoc' : 'booting'
        this.appendLog(whence + ' — executor wired, awaiting office endpoint')
      } catch (e) {
        this.appendLog('executor wiring failed: ' + (e && e.message ? e.message : e))
      }
    },
    // The office endpoint inside the webview is booted and serving. Load the real
    // document (Track D) if we have one, then publish readiness so the host
    // starts routing AI commands to the (now correctly-targeted) editor.
    async onEndpointReady() {
      if (!this.executor) return // unmounted during boot
      this._endpointUp = true
      await this.finishDocLoad()
    },
    // 迟到结果：load_document 的 180s relay 超时只是「host 端不再等」，worker 里
    // 的 loadComponentFromURL 打不断，常常在超时之后仍然真的装载成功。旧行为
    // 是静默丢弃这条迟到的成功消息——用户看到「文档加载失败」的红胶囊，画布上
    // 其实已经换成了真文档；更严重的是 docLoadFailed 同时是自动保存闸，误判
    // 期间的编辑会静默不落盘。
    // 只在「当前仍显示着这次失败」且「没有更晚的装载尝试发生过」（世代号相符）
    // 时才撤回失败态——世代号不符说明文档已切换/已重试/组件已卸载后订阅已断开
    // （dispose() 会取消订阅，届时这个回调根本不会再被触发），这些情形一律
    // 按兵不动，不能让一个作废的迟到结果去污染当前状态。
    onLateLoadResult(action, result) {
      if (action !== 'load_document') return
      if (!this.docLoadFailed) return
      if (this._loadGen !== this._loadGenAtFailure) return
      if (result && result.success) {
        this.docLoadFailed = false
        this.statusKey = 'ready'
        this.appendLog('迟到的 load_document 结果实际成功，撤回失败态 / late load_document result arrived successful, reverting loadFailed')
      }
    },
    // 装载 + 发布就绪。两个入口：onEndpointReady（常规：mount 时就有 file，或
    // 备胎空白 boot 完成），以及 file watcher（备胎在引擎就绪后被过继）。
    async finishDocLoad() {
      // 重入闸：卡住 30s 露出的「重试」按钮会在原来那次装载**仍在途**时（弱网/挂起
      // 代理下 XHR 的 60s 超时还没到）再起一条链路，两条各自 dispatch 一次
      // load_document，且后完成的那条按最后写者赢覆盖 ready/statusKey/docKind——
      // 迟到的失败能把重试的成功盖成 loadFailed（连带关掉保存闸），迟到的成功能把
      // 重试装好的文档连同其间的编辑整个换掉。世代号让被取代的那条在每个 await
      // 之后自行退场：只有最新一次尝试有权推命令、改状态、发 ready。
      const seq = this._docLoadSeq = (this._docLoadSeq || 0) + 1
      if (this.file) {
        try {
          await this.loadDocument()
          if (seq !== this._docLoadSeq) return
        } catch (e) {
          if (seq !== this._docLoadSeq) return
          // Load failed → the seeded prototype is still showing. Surface it; the
          // editor stays usable (AI/IME act on whatever is shown) but the content
          // is wrong, so this is loud, not silent. docLoadFailed 关保存闸——
          // 空白画布上的任何编辑都不得回传覆盖后端真文件。
          this.docLoadFailed = true
          this.statusKey = 'loadFailed'
          // 记下这次失败时的世代号——迟到的 load_document 结果（见
          // onLateLoadResult）只在世代仍相符（没有更晚的装载尝试发生过）时
          // 才允许撤回这个失败态，防止串到后来的重试/换文档头上。
          this._loadGenAtFailure = this._loadGen
          this.appendLog('load_document failed: ' + (e && e.message ? e.message : e))
        }
      }
      this.bootPct = 100
      clearInterval(this._bootTimer)
      this.ready = true
      if (!this.statusKey.endsWith('Failed')) this.statusKey = 'ready'
      this.$emit('ready', this.executor)
      // 装载期间后端把这份文件改掉了（版本退回 / 检查点恢复），刚装进来的是
      // 预取到的旧字节——不 await，让宿主先拿到 ready 再补一次真重载。
      if (this._reloadPending) {
        this._reloadPending = false
        this.reloadFromBackend()
      }
    },
    // Kick off the (authed) document download without waiting for the engine.
    // loadDocument() awaits this promise; on failure it falls back to a fresh
    // fetch there so a transient prefetch error can't kill the load path.
    prefetchBytes() {
      const f = this.file
      const fileId = f && (f.wpsFileId || f.id)
      if (!fileId) return
      const t0 = Date.now()
      this._bytesPromise = this.fetchArrayBuffer(getFileDownloadUrl(fileId), (loaded, total) => {
        this.dlLoaded = loaded
        this.dlTotal = total
      })
        .then((buf) => { this.appendLog('文档字节预取完成 / prefetched ' + (buf ? buf.byteLength : 0) + ' bytes in ' + (Date.now() - t0) + 'ms'); return buf })
        .catch((e) => { this.appendLog('预取失败（加载时重试）/ prefetch failed: ' + (e && e.message ? e.message : e)); return null })
      // 只读预览接力：不影响 _bytesPromise 本身（loadDocument 仍 await 它）
      this._bytesPromise.then((buf) => this.tryDocxPreview(buf))
    },
    // 引擎 boot 期间先把预取到的 docx 渲成只读预览（docx-preview 本地解析，
    // 同 FilePreview.renderDocx 的配置）。失败静默回落进度卡片。
    async tryDocxPreview(buf) {
      if (!buf || buf.byteLength === 0 || this.ready || this.previewReady || this.previewFailed) return
      const t = String((this.file && this.file.fileType) || '').toLowerCase()
      if (t !== 'docx' && t !== 'doc') return
      try {
        const { renderAsync } = await import('docx-preview')
        await this.$nextTick() // 过继路径上 overlay 可能刚重新显示，等 ref 挂上
        const ref = this.$refs.docxPreviewHost
        const container = ref && (ref.$el || ref)
        if (!container || this.ready) return
        container.innerHTML = ''
        await renderAsync(buf, container, null, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true,
          experimental: true,
        })
        if (this.ready) return
        this.previewReady = true
        this.appendLog('只读预览就绪（引擎继续后台启动）')
      } catch (e) {
        this.previewFailed = true
        this.appendLog('docx 预览渲染失败（保留进度面板）: ' + (e && e.message ? e.message : e))
      }
    },
    // 返回 true 表示 worker 里的文档已被换成后端字节；false 表示后端是空内容
    // （新建/未保存文件），保留 boot 出来的空白文档。reloadFromBackend 靠这个
    // 区分「换成功了」和「什么都没换」——后者在重载语境下必须当失败处理。
    async loadDocument() {
      const f = this.file
      const fileId = f.wpsFileId || f.id
      if (!fileId) throw new Error('file has no id/wpsFileId')
      // 每次真正尝试装载都记一个新世代号——onLateLoadResult 靠它辨认一个迟到的
      // load_document 结果是否还对着「当前显示着的那次失败」，而不是被后来的
      // 重试/换文档盖过之后依然生效。
      this._loadGen = (this._loadGen || 0) + 1
      // 本次装载所属的 finishDocLoad 世代（见那里的重入闸）；下载回来后若已被
      // 更晚的一次尝试取代，就不能再把命令推给 worker。
      const seq = this._docLoadSeq || 0
      const url = getFileDownloadUrl(fileId)
      let buf = this._bytesPromise ? await this._bytesPromise : null
      if (!buf) {
        try {
          buf = await this.fetchArrayBuffer(url)
        } catch (e) {
          // 下载失败自动重试一次（弱网/瞬时超时很常见），仍失败就让异常照常
          // 抛出——finishDocLoad 的 catch 会置 docLoadFailed + statusKey='loadFailed'，
          // 走既有的失败可见路径，不静默卡住。
          this.appendLog('下载失败，重试一次 / download failed, retrying once: ' + (e && e.message ? e.message : e))
          buf = await this.fetchArrayBuffer(url)
        }
      }
      const bytes = new Uint8Array(buf || new ArrayBuffer(0))
      const name = f.name || (String(fileId) + '.' + String(f.fileType || 'docx'))
      // Empty body = a brand-new / unsaved document — the backend streams HTTP
      // 200 with 0 bytes for it. That's NOT a load failure: keep the clean blank
      // editor the worker booted (the user edits + saves into it). Only a real
      // fetch error (non-200, handled in fetchArrayBuffer) surfaces as failed.
      if (bytes.length === 0) {
        // 元数据说文件非空却下载到 0 字节 = 后端/存储瞬时异常，绝不能当
        // "新建空白文档"——那会让后续编辑以空文档覆盖真文件。按加载失败走。
        if (f.fileSize > 0) throw new Error('文件非空（' + f.fileSize + ' bytes）但下载到 0 字节，拒绝按空白文档打开')
        this.appendLog('文档为空（新建/未保存）→ 显示空白文档 / empty doc → blank editor: ' + name)
        return false
      }
      // office 是单线程消息循环，两条 load_document 会按到达顺序依次执行，后到的
      // 那条把先装好的文档整个换掉——被取代的这次到此为止，不再发命令。
      if (seq !== (this._docLoadSeq || 0)) throw new Error('装载已被更晚的一次尝试取代 / load superseded')
      this.appendLog('▶ load_document「' + name + '」(' + bytes.length + ' bytes) …')
      this.bootMilestone(86, 95, 'openingDoc')
      // 当前登录用户名随文档传给 worker：用户本人编辑的修订以用户名署名，
      // AI 命令产生的修订署名 AI WorkDeck（worker execCommand 按 __agent 切换）。
      const u = getCurrentUser() || {}
      const authorName = String(u.name || u.nickname || u.username || '')
      const t0 = Date.now()
      const res = await this.executor.executeCommand('load_document', { bytes, name, authorName })
      this.appendLog('  ← ' + (Date.now() - t0) + 'ms ' + JSON.stringify(res))
      if (!res || !res.success) throw new Error((res && res.message) || 'load_document returned no success')
      if (res.kind) this.docKind = res.kind
      return true
    },
    // 后端就地覆盖了本文件的内容（版本退回 / 检查点恢复 / AI 直接改文件），而
    // worker 里端着的还是改前的文档：律师继续编辑，autosave 就会把「旧内容 +
    // 新编辑」写回去，把后端的改动冲掉。这里就地换文档——不重挂载（不重启
    // WASM 引擎，也不动保活池状态机），更不经 closeFile（那会先 flushSave 把
    // 旧字节落盘，正是要防的事故本身）。
    //
    // 换文档前必须先停掉自动保存：取消定时器 + 清脏 + 等在途保存结束。否则旧
    // 内容的 export 还排在队里，换完文档照样把旧字节传上去。重载语义就是丢弃
    // 编辑器内的本地改动（后端内容是权威），所以清脏不需要征询。
    async reloadFromBackend() {
      if (!this.file || !this.executor || !this.ready) {
        // 引擎还在启动（含备胎刚过继、只读预览接力正显示着 docx-preview 的那段）：
        // worker 里根本还没有文档可换，但 _bytesPromise 里预取的正是退回前的旧字节，
        // finishDocLoad 到点就会把它装进引擎，随后 autosave 把旧内容写回后端——
        // 退回被悄悄撤销。所以挂个待办，装载完成后立刻真重载一次。
        // 只读预览本身无害（它没有保存路径），有害的是它背后那份陈字节。
        if (this.file && this.loadingOverlayVisible) {
          this._reloadPending = true
          this.appendLog('reload deferred: 引擎仍在启动，就绪后立即重载')
          return true
        }
        this.appendLog('reload skipped: 编辑器未就绪')
        return false
      }
      // 清脏 + 等在途保存都拦不住「已经启动、正在 export/upload 路上」的那一笔：
      // 它读的是换文档之前的 worker 内容，等它 upload 完成就把退回结果覆盖回旧字节。
      // saving 标志只在 saveDocument 内部为真，await 它结束之后新的 modified 又能
      // 再排一次 autosave。所以整个重载窗口期立一个闸：置位期间 onDocModified 不标脏、
      // saveDocument 在 upload 前直接放弃。重载语义本来就是丢弃编辑器里的本地改动
      // （后端内容是权威），丢掉这一笔是语义本身，不是数据损失。
      this._reloading = true
      const cancelAutoSave = () => {
        clearTimeout(this._saveTimer)
        this._saveTimer = null
        this.dirty = false
        this._dirtySince = 0
      }
      cancelAutoSave()
      // 在途 export/upload 期间不能换文档（export 读的是 worker 当前文档）——
      // 等它结束，其间新来的 modify 同样丢弃。
      while (this.saving) await new Promise((r) => setTimeout(r, 100))
      cancelAutoSave()
      // 预取到的是改前的字节，必须重新下载。
      this._bytesPromise = null
      this.dlLoaded = 0
      this.dlTotal = 0
      const prevStatusKey = this.statusKey
      this.statusKey = 'reloading'
      try {
        const loaded = await this.loadDocument()
        if (!loaded) throw new Error('后端返回 0 字节，未替换编辑器内文档')
        // load_document 的 retarget 重装了 modify listener 并重置 RecordChanges，
        // 换完再清一次脏（retarget 里设 RecordChanges 会触发一次 modified）。
        this.docLoadFailed = false
        cancelAutoSave()
        this.statusKey = prevStatusKey.endsWith('Failed') ? 'ready' : prevStatusKey
        this.appendLog('reload: 已就地换成后端最新内容')
        return true
      } catch (e) {
        // 换文档失败 = 画布上仍是改前内容。保存闸必须落下，否则下一次 autosave
        // 会用旧内容覆盖后端刚改好的文件。
        this.docLoadFailed = true
        this.statusKey = 'reloadFailed'
        this._loadGenAtFailure = this._loadGen
        this.appendLog('reload failed: ' + (e && e.message ? e.message : e))
        return false
      } finally {
        this._reloading = false
      }
    },
    // Authed binary fetch — same XHR auth pattern as FilePreview.fetchAuthedBlob,
    // but ArrayBuffer (the bytes we relay into the worker).
    fetchArrayBuffer(url, onProgress) {
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const xhr = new XMLHttpRequest()
        xhr.open('GET', url, true)
        xhr.responseType = 'arraybuffer'
        // 无超时的话，请求挂起（代理/网络中间层吞掉响应，不触发 onerror）会让
        // loadDocument 里的 await 永远不返回——加载面板卡在某个百分比，既不报
        // 错也不重试。60s 覆盖正常大文档下载，挂起的连接会被主动打断。
        xhr.timeout = 60000
        Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]))
        if (onProgress) {
          xhr.onprogress = (ev) => {
            try { onProgress(ev.loaded || 0, ev.lengthComputable ? ev.total : 0) } catch (e) { /* ignore */ }
          }
        }
        xhr.onload = () => (xhr.status === 200 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status)))
        xhr.onerror = () => reject(new Error('网络错误 / network error'))
        xhr.ontimeout = () => reject(new Error('下载超时 / download timed out'))
        xhr.send()
      })
    },
    // Autosave: the worker's modify listener reports every document change
    // (typed / IME / AI command) — debounce-save on it so the user never has to
    // press anything. Idle window 2.5s; continuous typing still hits the backend
    // at least every 15s (max-wait), so a crash can't eat a long burst.
    // 面板处置了修订/批注：文档确实改了，走与打字同一条自动保存链路
    onReviewChanged() {
      this.onDocModified()
    },
    onDocModified() {
      // docLoadFailed：画布上是空白 boot 文档，标脏会引发空文档覆盖真文件
      if (!this.ready || !this.file || this.docLoadFailed) return
      // 重载窗口期（版本退回 / 检查点恢复正在换文档）里的 modified 一律丢弃：
      // 它描述的是即将被替换掉的旧文档，标脏只会让 autosave 把旧内容传回去。
      if (this._reloading) return
      this.dirty = true
      if (!this._dirtySince) this._dirtySince = Date.now()
      this.scheduleAutoSave()
      // 文档变了（打字 / AI 改动）——面板开着就刷新，别让它显示过期清单
      if (this.reviewOpen) this.reviewRefreshKey++
      // 工具栏激活态也可能变了（AI 改了格式、用户敲了字）
      this.uiRefreshKey++
    },
    scheduleAutoSave() {
      clearTimeout(this._saveTimer)
      const elapsed = Date.now() - this._dirtySince
      const delay = Math.max(200, Math.min(2500, 15000 - elapsed))
      this._saveTimer = setTimeout(() => this.autoSave(), delay)
    },
    async autoSave() {
      if (this.saving) { this.scheduleAutoSave(); return } // a save is in flight — retry after it
      // 假死根因修复：export_document 是全文档同步序列化，跑在 office 线程上会把
      // Qt 事件循环（滚动/输入/重绘）整段冻住。AI 修订风暴期间 modify 不断，旧的
      // 15s max-wait 会把导出硬塞进风暴中间 → 用户「完全无法滚动的假死」。
      // 改为：命令在飞或刚结束（<1.5s）时让路、等空闲窗口再导出；只有脏数据
      // 超过 60s 才强制保存（崩溃丢失上限从 15s 放宽到 60s，换取修订期不冻结）。
      const dirtyAge = this._dirtySince ? Date.now() - this._dirtySince : 0
      const sinceCmd = Date.now() - (this._lastCmdAt || 0)
      if ((this._cmdBusy > 0 || sinceCmd < 1500) && dirtyAge < 60000) {
        clearTimeout(this._saveTimer)
        this._saveTimer = setTimeout(() => this.autoSave(), 2000)
        return
      }
      this.dirty = false
      this._dirtySince = 0
      const ok = await this.saveDocument()
      if (this.dirty) { this.scheduleAutoSave(); return } // edits arrived mid-save
      if (!ok) {
        // Transient failure (offline / backend hiccup): the edits are still
        // unsaved — keep them marked dirty and retry on a slow cadence.
        this.dirty = true
        this._dirtySince = Date.now()
        clearTimeout(this._saveTimer)
        this._saveTimer = setTimeout(() => this.autoSave(), 15000)
      }
    },
    // Flush before unmount (tab close / LRU evict): export needs the live
    // webview, so the closer awaits this BEFORE removing the instance.
    async flushSave() {
      clearTimeout(this._saveTimer)
      while (this.saving) await new Promise((r) => setTimeout(r, 100))
      if (this.dirty) {
        this.dirty = false
        this._dirtySince = 0
        await this.saveDocument()
      }
    },
    // Track E: save — export the edited document from the worker (storeToURL →
    // bytes) and persist via the backend upload endpoint (same fileId contract
    // as the download the document was loaded from). Autosave calls this;
    // returns true on success so autoSave can schedule failure retries.
    async saveDocument() {
      const f = this.file
      if (!f || !this.executor || this.saving) return false
      // 最后一道闸（onDocModified 之外的调用方也拦住）：文档没成功加载，
      // 导出的只会是空白 boot 文档——拒绝覆盖后端真文件。
      if (this.docLoadFailed) { this.appendLog('save blocked: 文档未成功加载，拒绝用空白文档覆盖后端文件'); return false }
      // 重载窗口期一笔都别起：这时候导出的是即将被替换掉的旧文档，而且 export 会
      // 把 office 线程占住、拖慢紧跟着的 load_document。
      if (this._reloading) { this.appendLog('save blocked: 正在重载后端最新内容'); return false }
      const fileId = f.wpsFileId || f.id
      if (!fileId) { this.appendLog('save: file has no id/wpsFileId'); return false }
      this.saving = true
      // 保存状态别抢戏：绝大多数保存几百毫秒就完了，闪一下「保存中…→已保存」
      // 纯粹是干扰（用户反馈：经常有变化，不好看且会打扰）。规则改成——慢到 2s
      // 以上才提示「保存中…」，成功后安静收回、不报「已保存」，失败才常驻显示。
      // 上一次的「保存失败」不能当成要恢复的状态，这次成功了就该回到就绪。
      // （i18n：判据全部按 statusKey 走，不比较 $t 渲染出的显示串。）
      const prevStatusKey = this.statusKey.endsWith('Failed') ? 'ready' : this.statusKey
      clearTimeout(this._slowSaveTimer)
      this._slowSaveTimer = setTimeout(() => { if (this.saving) this.statusKey = 'saving' }, 2000)
      try {
        const name = f.name || (String(fileId) + '.' + String(f.fileType || 'docx'))
        this.appendLog('▶ export_document「' + name + '」…')
        const tExp = Date.now()
        const res = await this.executor.executeCommand('export_document', { name })
        this.appendLog('  ← export ' + (Date.now() - tExp) + 'ms')
        if (!res || !res.success) throw new Error((res && res.message) || 'export_document returned no success')
        // Structured clone across the relay hops may surface bytes as
        // Uint8Array / ArrayBuffer / plain array — normalize (mirror of the
        // worker's load_document normalization).
        const raw = res.bytes
        let u8 = null
        if (raw instanceof Uint8Array) u8 = raw
        else if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw)
        else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength)
        else if (Array.isArray(raw)) u8 = new Uint8Array(raw)
        if (!u8 || u8.length === 0) throw new Error('export produced no bytes')
        // 重载闸（版本退回 / 检查点恢复）：export 是在换文档之前启动的，导出的这份
        // 字节就是「后端已被改写掉的那个旧版本 + 用户的在途编辑」。上传出去就等于
        // 把律师刚做的退回覆盖回去——这是数据事故，不是体验问题。丢弃这一笔。
        if (this._reloading) {
          this.appendLog('save aborted: 正在重载后端最新内容，丢弃这一笔在途导出')
          return false
        }
        this.appendLog('  ← exported ' + u8.length + ' bytes, uploading…')
        await this.uploadBytes(getFileUploadUrl(fileId), u8, name)
        this.appendLog('  ← saved to backend (fileId=' + fileId + ')')
        return true
      } catch (e) {
        this.statusKey = 'saveFailed'
        this.appendLog('save failed: ' + (e && e.message ? e.message : e))
        return false
      } finally {
        this.saving = false
        clearTimeout(this._slowSaveTimer)
        // 只收回自己挂上去的「保存中…」；失败态是 catch 里刚设的，必须留着
        if (this.statusKey === 'saving') this.statusKey = prevStatusKey
      }
    },
    // Authed multipart POST — the upload twin of fetchArrayBuffer (backend
    // contract: POST /api/files/{fileId}/upload, part name "file").
    uploadBytes(url, u8, filename) {
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const form = new FormData()
        form.append('file', new Blob([u8]), filename)
        const xhr = new XMLHttpRequest()
        xhr.open('POST', url, true)
        Object.keys(headers).forEach((k) => { if (k.toLowerCase() !== 'content-type') xhr.setRequestHeader(k, headers[k]) })
        xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status)))
        xhr.onerror = () => reject(new Error('网络错误 / network error'))
        xhr.send(form)
      })
    },
  },
}
</script>

<style scoped>
.libre-editor-wrapper { position: relative; display: flex; flex-direction: column; width: 100%; height: 100%; background: #fff; }
/* Floating status pill, pinned over the CANVAS's top-right corner (not the
   wrapper's — the review panel sits to the right and would be covered). No
   layout height is reserved — the document canvas gets the full pane. */
.libre-float { position: absolute; top: 6px; right: 16px; z-index: 20; display: flex; align-items: center; gap: 8px; }
.libre-pill { display: flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px;
  background: rgba(31, 41, 55, 0.78); color: #e5e7eb; font-size: 12px; backdrop-filter: blur(4px); }
.libre-pill.error { background: rgba(153, 27, 27, 0.9); color: #fecaca; }
.libre-spin { width: 10px; height: 10px; border: 2px solid rgba(229, 231, 235, 0.35); border-top-color: #e5e7eb;
  border-radius: 50%; animation: libre-rot 0.8s linear infinite; }
@keyframes libre-rot { to { transform: rotate(360deg); } }
.libre-body { flex: 1; min-height: 0; width: 100%; display: flex; flex-direction: row; }
.libre-canvas-wrap { position: relative; flex: 1; min-width: 0; min-height: 0; height: 100%; }
.libre-host { width: 100%; height: 100%; }
/* EvidenceLink 拖放：整个编辑器描一圈边，画布上铺透明接收层；悬停时加深 */
.libre-editor-wrapper.evidence-drop-armed { box-shadow: inset 0 0 0 2px #1A5336; }
.libre-evidence-drop { position: absolute; inset: 0; z-index: 25; display: flex; align-items: flex-end; justify-content: center;
  padding-bottom: 28px; background: rgba(230, 249, 240, 0.25); border: 2px dashed rgba(26, 83, 54, 0.45); box-sizing: border-box; }
.libre-evidence-drop.over { background: rgba(230, 249, 240, 0.55); border-color: #1A5336; border-style: solid; }
.libre-evidence-hint { padding: 6px 14px; border-radius: 999px; background: #fff; border: 1px solid #1A5336; color: #1A5336;
  font-size: 12px; pointer-events: none; }
.libre-review-btn { padding: 3px 10px; border-radius: 999px; background: rgba(31, 41, 55, 0.78);
  color: #e5e7eb; font-size: 12px; backdrop-filter: blur(4px); }
.libre-review-btn.on { background: #E6F9F0; color: #1A5336; }
/* ---- 加载进度面板 ---- */
.libre-loading { position: absolute; inset: 0; z-index: 15; display: flex; align-items: center; justify-content: center;
  background: #F8F9FA; }
.libre-loading-card { display: flex; flex-direction: column; align-items: center; gap: 10px; width: 320px; max-width: 80%; }
.libre-doc-icon { position: relative; width: 44px; height: 56px; background: #fff; border: 1.5px solid #DEE2E6;
  border-radius: 5px; margin-bottom: 4px; overflow: hidden; }
.doc-fold { position: absolute; top: -1px; right: -1px; width: 14px; height: 14px;
  background: #F8F9FA; border-left: 1.5px solid #DEE2E6; border-bottom: 1.5px solid #DEE2E6; border-radius: 0 0 0 5px; }
.doc-line { position: absolute; left: 8px; height: 4px; border-radius: 2px; background: #E6F9F0;
  animation: doc-line-pulse 1.6s ease-in-out infinite; }
.doc-line.l1 { top: 20px; width: 26px; animation-delay: 0s; }
.doc-line.l2 { top: 30px; width: 20px; animation-delay: 0.25s; }
.doc-line.l3 { top: 40px; width: 24px; animation-delay: 0.5s; }
@keyframes doc-line-pulse { 0%, 100% { background: #E9ECEF; } 50% { background: #5BD197; } }
.libre-loading-name { font-size: 14px; font-weight: 600; color: #2C3338; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.libre-progress-track { width: 100%; height: 6px; background: #E9ECEF; border-radius: 999px; overflow: hidden; }
.libre-progress-fill { position: relative; height: 100%; background: #5BD197; border-radius: 999px;
  transition: width 0.5s ease; overflow: hidden; }
.libre-progress-shimmer { position: absolute; inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  animation: libre-shimmer 1.4s linear infinite; }
@keyframes libre-shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
.libre-loading-meta { display: flex; justify-content: space-between; width: 100%; }
.libre-loading-stage { font-size: 12px; color: #495057; }
.libre-loading-pct { font-size: 12px; color: #1A5336; font-weight: 600; }
.libre-loading-dl { font-size: 11px; color: #868E96; }
.libre-loading-hint { font-size: 11px; color: #ADB5BD; margin-top: 6px; }
.libre-loading-retry { margin-top: 8px; padding: 6px 16px; border-radius: 999px; background: #1A5336;
  color: #fff; font-size: 12px; cursor: pointer; }
.libre-loading-retry:hover { background: #16452C; }
/* ---- 只读预览接力 ---- */
.libre-preview-strip { position: absolute; top: 0; left: 0; right: 0; z-index: 2; display: flex; flex-direction: column;
  gap: 4px; padding: 6px 14px 8px; background: rgba(248, 249, 250, 0.95); border-bottom: 1px solid #E9ECEF;
  backdrop-filter: blur(4px); }
.libre-strip-track { width: 100%; height: 3px; background: #E9ECEF; border-radius: 999px; overflow: hidden; }
.libre-strip-fill { height: 100%; background: #5BD197; border-radius: 999px; transition: width 0.5s ease; }
.libre-strip-text { font-size: 11px; color: #868E96; }
.libre-preview-host { position: absolute; inset: 0; top: 34px; overflow-y: auto; background: #F1F3F5; }
/* docx-preview 生成的页面居中呈现（deep：内容是运行时注入的非 scoped DOM） */
.libre-preview-host :deep(.docx-wrapper) { background: transparent; padding: 16px 0; }
</style>
