<!--
  常驻反馈浮窗（右下角）。

  组件由 App.vue 单独 createApp 挂在 <body> 下（见 utils/feedbackWidget.js），
  不在 uni 的页面树里，因此全应用只有一个实例，天然绕开 project-overview 那条
  「navigateTo 页面栈多实例 → 一次事件 N 份副作用」的地雷。

  **标签选择不是风格问题**：uni-app 的 H5 编译器会把 button / input / textarea /
  audio 这些标签替换成 uni 内置组件（`<audio>` 更是直接编译失败——uni-h5 没有导出
  Audio），而 uni 的 Input 根本不支持 type="file"。所以这里一律用不会被改写的标签：
  按钮用 div、文件选择框用 JS 现建、多行输入用 `<component :is="'textarea'">`
  绕开标签名映射、语音试听用 new window.Audio()。改这个文件时别顺手换回原生标签。

  桌面端 BrowserView 是原生层、永远盖住 DOM，所以浮窗打开时要让主进程把 view 藏起来。
  这里不自己调 setViewsVisible，只置 utils/overlayState.js 的全局 ref，
  由 project-overview 既有的那一处 watcher 统一执行（理由见该文件注释）。
-->
<template>
  <div class="awdfb">
    <!-- 入口按钮可以在窗口里拖着走：钉死在右下角时它会挡住底部工具抽屉、
         状态栏和编辑器右下角的控件。按下-拖动-松开由 pointer 事件自己判，
         位移没超过阈值才算点击（所以这里不写 @click，否则拖完手一松还会开面板）。 -->
    <div
      v-if="!open"
      class="awdfb-launcher"
      :class="{ 'is-moving': moving }"
      :style="launcherStyle"
      role="button"
      tabindex="0"
      :title="$t('feedback.launcherTitle')"
      @pointerdown="onLauncherDown"
      @keydown.enter="openPanel"
    >
      <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
        <path
          d="M12 3.5c-3.3 0-6 2.4-6 5.4 0 1.6.8 3 2 4v1.6c0 .5.5.8.9.6l1.7-1c.4.1.9.1 1.4.1 3.3 0 6-2.4 6-5.3S15.3 3.5 12 3.5Z"
          fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"
        />
        <path d="M12 7.2v3" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        <circle cx="12" cy="12.4" r="0.9" fill="currentColor" />
      </svg>
      <span>{{ $t('feedback.launcherLabel') }}</span>
    </div>

    <div v-if="open" class="awdfb-mask">
      <div
        class="awdfb-panel"
        :class="{ 'is-dragging': dragging }"
        :style="panelStyle"
        @dragover.prevent="dragging = true"
        @dragleave="dragging = false"
        @drop.prevent="onDrop"
      >
        <div class="awdfb-head">
          <div class="awdfb-title">{{ headTitle }}</div>
          <div class="awdfb-head-right">
            <div v-if="view === 'form'" class="awdfb-link" role="button" @click="openMine">{{ $t('feedback.myFeedback') }}</div>
            <div v-else class="awdfb-link" role="button" @click="backToForm">{{ $t('feedback.back') }}</div>
            <div class="awdfb-x" role="button" :title="$t('feedback.close')" @click="closePanel">✕</div>
          </div>
        </div>

        <div class="awdfb-body">
          <template v-if="view === 'form'">
          <div class="awdfb-kinds">
            <div
              class="awdfb-kind"
              role="button"
              :class="{ active: kind === 'BUG' }"
              @click="kind = 'BUG'"
            >{{ $t('feedback.kindBug') }}</div>
            <div
              class="awdfb-kind"
              role="button"
              :class="{ active: kind === 'IDEA' }"
              @click="kind = 'IDEA'"
            >{{ $t('feedback.kindIdea') }}</div>
          </div>

          <component
            :is="'textarea'"
            ref="textarea"
            class="awdfb-text"
            :value="text"
            :placeholder="kind === 'BUG'
              ? $t('feedback.placeholderBug')
              : $t('feedback.placeholderIdea')"
            @input="text = $event.target.value"
            @paste="onPaste"
          />

          <div class="awdfb-tools">
            <div
              v-if="canScreenshot"
              class="awdfb-tool"
              role="button"
              :class="{ disabled: capturing }"
              @click="captureScreenshot"
            >{{ capturing ? $t('feedback.capturingScreenshot') : $t('feedback.captureScreenshotBtn') }}</div>
            <div class="awdfb-tool" role="button" @click="pickImages">{{ $t('feedback.pickImages') }}</div>
            <div
              class="awdfb-tool"
              role="button"
              :class="{ recording: recording }"
              @click="toggleRecording"
            >{{ recording ? $t('feedback.stopRecording', { seconds: recordSeconds }) : $t('feedback.recordVoice') }}</div>
          </div>

          <div v-if="images.length" class="awdfb-shots">
            <div v-for="(img, i) in images" :key="img.id" class="awdfb-shot">
              <img :src="img.url" alt="" />
              <div class="awdfb-shot-x" role="button" :title="$t('feedback.remove')" @click="removeImage(i)">✕</div>
            </div>
          </div>

          <div v-if="audio" class="awdfb-audio">
            <span class="awdfb-audio-label">{{ $t('feedback.audioLabel', { seconds: audio.seconds }) }}</span>
            <div class="awdfb-tool" role="button" @click="togglePlay">
              {{ playing ? $t('feedback.stopPlay') : $t('feedback.play') }}
            </div>
            <div class="awdfb-tool" role="button" @click="removeAudio">{{ $t('feedback.remove') }}</div>
          </div>
          <div v-if="audio" class="awdfb-hint">
            {{ $t('feedback.audioHint') }}
          </div>

          <div class="awdfb-ctx-toggle" role="button" @click="showContext = !showContext">
            {{ showContext ? $t('feedback.contextToggleCollapse', { summary: contextSummary }) : $t('feedback.contextToggleExpand', { summary: contextSummary }) }}
          </div>
          <div class="awdfb-hint">{{ $t('feedback.submitHint') }}</div>
          <pre v-if="showContext" class="awdfb-ctx">{{ contextPreview }}</pre>
          </template>

          <template v-else-if="view === 'result'">
            <div class="awdfb-result" :class="{ err: !resultOk }">
              <div class="awdfb-result-msg">{{ resultMessage }}</div>
              <div class="awdfb-result-actions">
                <div v-if="resultOk" class="awdfb-tool" role="button" @click="openMine">{{ $t('feedback.viewProgress') }}</div>
                <div v-else class="awdfb-tool" role="button" @click="backToForm">{{ $t('feedback.retry') }}</div>
              </div>
            </div>
          </template>

          <template v-else-if="view === 'mine'">
            <div v-if="mineLoading" class="awdfb-hint">{{ $t('feedback.loading') }}</div>
            <div v-else-if="mineError" class="awdfb-hint err">{{ mineError }}</div>
            <div v-else-if="!mineList.length" class="awdfb-hint">{{ $t('feedback.noFeedbackYet') }}</div>
            <div v-else class="awdfb-mine-list">
              <div v-for="item in mineList" :key="item.id" class="awdfb-mine-item">
                <div class="awdfb-mine-row">
                  <span class="awdfb-badge" :class="item.kind === 'IDEA' ? 'idea' : 'bug'">
                    {{ item.kind === 'IDEA' ? $t('feedback.kindIdea') : $t('feedback.kindBug') }}
                  </span>
                  <span class="awdfb-mine-time">{{ item.timeLabel }}</span>
                </div>
                <div class="awdfb-mine-text">{{ item.excerpt }}</div>
                <div class="awdfb-mine-status">
                  <span>{{ item.statusLabel }}</span>
                  <div v-if="item.prUrl" class="awdfb-mine-pr" role="button" @click="openPr(item.prUrl)">{{ $t('feedback.viewPr') }}</div>
                </div>
              </div>
            </div>
          </template>
        </div>

        <div v-if="view === 'form'" class="awdfb-foot">
          <div class="awdfb-status" :class="{ err: statusIsError }">{{ status }}</div>
          <div
            class="awdfb-submit"
            role="button"
            :class="{ disabled: submitting || !canSubmit }"
            @click="submit"
          >{{ submitting ? $t('feedback.submitting') : $t('feedback.submit') }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { host, isDesktopHost } from '@/services/host.js'
import { submitFeedback, getMyFeedback } from '@/services/api.js'
import { setGlobalOverlay } from '@/utils/overlayState.js'
import { getRecentErrors, recentErrorCount } from '@/utils/errorBuffer.js'
import { getLastProjectId } from '@/utils/recentProjects.js'
import { openExternalUrl } from '@/utils/externalLink.js'
import { t as t$ } from '@/i18n'
import { shouldAcceptResponse } from '@/utils/requestGeneration.js'

const MAX_IMAGES = 10
const MAX_RECORD_SECONDS = 120
// 入口按钮被拖到哪儿了。存起来是必须的：每次启动都弹回右下角的话，
// 「它挡住了我要点的东西」这个问题等于没解决。
const LAUNCHER_POS_KEY = 'awd_feedback_launcher_pos'

// 状态对用户的说法：不暴露 NEW/PR_OPENED/EMAILED/SKIPPED/FAILED 这些内部枚举。
const MINE_STATUS_LABELS = {
  PR_OPENED: 'feedback.statusPrOpened',
  EMAILED: 'feedback.statusEmailed',
  SKIPPED: 'feedback.statusSkipped',
  FAILED: 'feedback.statusFailed',
}

function mineStatusLabel(item) {
  if (item.status === 'NEW') return t$(item.uploaded ? 'feedback.statusUploaded' : 'feedback.statusPending')
  return t$(MINE_STATUS_LABELS[item.status] || 'feedback.statusProcessing')
}

function mineExcerpt(item) {
  const text = (item.text || '').trim() || (item.voiceTranscript || '').trim()
  return text ? (text.length > 60 ? text.slice(0, 60) + '…' : text) : t$('feedback.excerptOnlyMedia')
}

function mineTimeLabel(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
    + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}

function formatMineItem(item) {
  return {
    id: item.id,
    kind: item.kind,
    excerpt: mineExcerpt(item),
    timeLabel: mineTimeLabel(item.createdAt),
    statusLabel: mineStatusLabel(item),
    prUrl: item.prUrl || '',
  }
}

export default {
  name: 'FeedbackWidget',
  data() {
    return {
      open: false,
      view: 'form', // 'form' | 'result' | 'mine'
      kind: 'BUG',
      text: '',
      images: [],
      audio: null,
      recording: false,
      recordSeconds: 0,
      playing: false,
      capturing: false,
      submitting: false,
      // 注意：dragging 是「有文件被拖到面板上」，跟入口按钮的拖动无关，别混用
      dragging: false,
      // 入口按钮的位置。null = 没挪过，走 CSS 里的右下角默认值
      launcherPos: null,
      moving: false,
      showContext: false,
      status: '',
      statusIsError: false,
      seq: 0,
      resultOk: false,
      resultMessage: '',
      mineList: [],
      mineLoading: false,
      mineError: '',
      _mineRequestSeq: 0, // 请求代次：只接受"此刻最新一次" openMine 发出的响应
    }
  },
  computed: {
    canScreenshot() {
      return isDesktopHost() && !!(host.ocr && host.ocr.startSelection)
    },
    canSubmit() {
      // recording 也算「有内容」：录着音直接点提交是很自然的操作，
      // 此时 audio 还没落成（onstop 未到），submit 里会先等它
      return !!(this.text.trim() || this.images.length || this.audio || this.recording)
    },
    contextSummary() {
      const bits = [this.$t('feedback.contextCurrentPage'), this.$t('feedback.contextVersionSystem')]
      const n = recentErrorCount()
      if (n) bits.push(this.$t('feedback.contextFrontendErrors', { count: n }))
      bits.push(this.$t('feedback.contextBackendLogs'))
      return bits.join(' · ')
    },
    contextPreview() {
      return JSON.stringify(this.collectContext(), null, 2)
    },
    headTitle() {
      if (this.view === 'mine') return this.$t('feedback.myFeedback')
      if (this.view === 'result') return this.resultOk ? this.$t('feedback.headResultOk') : this.$t('feedback.submitFailed')
      return this.$t('feedback.headDefaultTitle')
    },
    launcherStyle() {
      if (!this.launcherPos) return {}
      // 挪过之后改成左上角定位，得把 CSS 里的 right/bottom 显式解掉
      return {
        left: this.launcherPos.left + 'px',
        top: this.launcherPos.top + 'px',
        right: 'auto',
        bottom: 'auto',
      }
    },
    panelStyle() {
      if (!this.launcherPos) return {}
      // 面板跟着入口所在的象限走：入口拖到左上角、面板还从右下角冒出来会很跳。
      // 只认象限不做精确贴附——面板 420px 宽、最高 78vh，精确贴附在小窗口下必然出界。
      const w = (typeof window !== 'undefined' && window.innerWidth) || 1280
      const h = (typeof window !== 'undefined' && window.innerHeight) || 800
      const atLeft = this.launcherPos.left + 60 < w / 2
      const atTop = this.launcherPos.top + 14 < h / 2
      return {
        left: atLeft ? '16px' : 'auto',
        right: atLeft ? 'auto' : '16px',
        top: atTop ? '16px' : 'auto',
        bottom: atTop ? 'auto' : '34px',
      }
    },
  },
  mounted() {
    // 菜单栏的「反馈…」「报告问题…」经这条事件打开浮窗。浮窗挂在页面树之外
    // （feedbackWidget.js 的 body 级单例），菜单派发器够不到组件实例，只能走事件。
    this._openFromMenu = () => { if (!this.open) this.openPanel() }
    try { uni.$on('awd:open-feedback', this._openFromMenu) } catch (e) { /* ignore */ }
    this.restoreLauncherPos()
    // 窗口缩小后旧坐标可能整个落到视口外，缩一次窗就再也点不到那个按钮了
    this._onWinResize = () => { if (this.launcherPos) this.launcherPos = this.clampPos(this.launcherPos) }
    try { window.addEventListener('resize', this._onWinResize) } catch (e) { /* ignore */ }
  },
  beforeUnmount() {
    try { uni.$off('awd:open-feedback', this._openFromMenu) } catch (e) { /* ignore */ }
    try { window.removeEventListener('resize', this._onWinResize) } catch (e) { /* ignore */ }
    this.detachLauncherDrag()
    this.stopRecording(true)
    this.stopPlay()
    this.revokeAll()
    setGlobalOverlay(false)
  },
  methods: {
    // ==================== 入口按钮拖动 ====================
    // 尺寸是量出来的（padding 会随文案长度变），别写死
    launcherSize() {
      const el = this.$el && this.$el.querySelector('.awdfb-launcher')
      return { w: (el && el.offsetWidth) || 96, h: (el && el.offsetHeight) || 28 }
    },
    clampPos(pos) {
      const { w, h } = this.launcherSize()
      const vw = (typeof window !== 'undefined' && window.innerWidth) || 1280
      const vh = (typeof window !== 'undefined' && window.innerHeight) || 800
      const M = 8
      return {
        left: Math.min(Math.max(pos.left, M), Math.max(M, vw - w - M)),
        top: Math.min(Math.max(pos.top, M), Math.max(M, vh - h - M)),
      }
    },
    restoreLauncherPos() {
      try {
        const saved = uni.getStorageSync(LAUNCHER_POS_KEY)
        if (saved && typeof saved.left === 'number' && typeof saved.top === 'number') {
          this.launcherPos = this.clampPos(saved)
        }
      } catch (e) { /* 存储读不出来就用默认角落 */ }
    },
    onLauncherDown(e) {
      if (e.button !== undefined && e.button !== 0) return
      const el = e.currentTarget
      const rect = el.getBoundingClientRect()
      this._drag = {
        dx: e.clientX - rect.left,
        dy: e.clientY - rect.top,
        x0: e.clientX,
        y0: e.clientY,
        moved: false,
      }
      // 监听挂在 window 上而不是按钮上：拖快了指针会跑出按钮范围，挂在按钮上
      // 就收不到 pointerup，按钮会永远卡在「拖动中」。（setPointerCapture 能解决
      // 这个问题，但它在 CDP 合成事件下不一定拿得到，e2e 会跟着一起坏。）
      this._onLauncherMove = (ev) => this.onLauncherMove(ev)
      this._onLauncherUp = (ev) => this.onLauncherUp(ev)
      window.addEventListener('pointermove', this._onLauncherMove)
      window.addEventListener('pointerup', this._onLauncherUp)
      window.addEventListener('pointercancel', this._onLauncherUp)
      void el
    },
    onLauncherMove(e) {
      if (!this._drag) return
      // 4px 阈值：手抖不该被当成拖动，否则想点开面板的人会拖出一点位移然后什么也没发生
      if (!this._drag.moved
        && Math.abs(e.clientX - this._drag.x0) < 4
        && Math.abs(e.clientY - this._drag.y0) < 4) return
      this._drag.moved = true
      this.moving = true
      this.launcherPos = this.clampPos({
        left: e.clientX - this._drag.dx,
        top: e.clientY - this._drag.dy,
      })
    },
    onLauncherUp() {
      const moved = !!(this._drag && this._drag.moved)
      this.detachLauncherDrag()
      this.moving = false
      if (!moved) {
        this.openPanel()
        return
      }
      try { uni.setStorageSync(LAUNCHER_POS_KEY, this.launcherPos) } catch (e) { /* ignore */ }
    },
    detachLauncherDrag() {
      if (this._onLauncherMove) window.removeEventListener('pointermove', this._onLauncherMove)
      if (this._onLauncherUp) {
        window.removeEventListener('pointerup', this._onLauncherUp)
        window.removeEventListener('pointercancel', this._onLauncherUp)
      }
      this._onLauncherMove = null
      this._onLauncherUp = null
      this._drag = null
    },
    openPanel() {
      this.open = true
      this.view = 'form'
      this.status = ''
      this.statusIsError = false
      setGlobalOverlay(true)
      this.$nextTick(() => {
        if (this.$refs.textarea && this.$refs.textarea.focus) this.$refs.textarea.focus()
      })
    },
    closePanel() {
      if (this.submitting) return
      this.stopRecording(true)
      this.stopPlay()
      this.open = false
      setGlobalOverlay(false)
    },
    backToForm() {
      this.view = 'form'
    },
    // 「我的反馈」视图：每次打开都重新拉一遍，状态会随后台优化者的处理进度变化，
    // 缓存旧列表只会让用户看到过期的「待发送」。back 不取消在途请求，快速
    // myFeedback->back->myFeedback 会并发出两个请求；用请求代次只认最后一次发出的响应，
    // 防止先发的（陈旧）响应后回来把已经渲染好的新列表盖掉。
    async openMine() {
      this.view = 'mine'
      this.mineLoading = true
      this.mineError = ''
      const seq = ++this._mineRequestSeq
      try {
        const res = await getMyFeedback()
        if (!shouldAcceptResponse(seq, this._mineRequestSeq)) return
        const items = (res && res.data && res.data.items) || []
        this.mineList = items.map(formatMineItem)
      } catch (e) {
        if (!shouldAcceptResponse(seq, this._mineRequestSeq)) return
        this.mineError = (e && e.message) || this.$t('feedback.loadFailed')
      } finally {
        if (shouldAcceptResponse(seq, this._mineRequestSeq)) this.mineLoading = false
      }
    },
    // PR 链接必须走系统浏览器/新标签页：桌面端主进程会拦截渲染层的 window.open，
    // 直接写 <a href> 在 Electron 里未必跳得出去，统一走这个既有出口（同 admin.vue 的 openPr）。
    openPr(url) {
      openExternalUrl(url)
    },
    reset() {
      this.stopPlay()
      this.revokeAll()
      this.text = ''
      this.images = []
      this.audio = null
      this.showContext = false
    },
    revokeAll() {
      for (const img of this.images) {
        try { URL.revokeObjectURL(img.url) } catch (e) { /* ignore */ }
      }
      if (this.audio) {
        try { URL.revokeObjectURL(this.audio.url) } catch (e) { /* ignore */ }
      }
    },

    // ---- 图片 ----
    addImageBlob(blob, name) {
      if (this.images.length >= MAX_IMAGES) {
        this.setStatus(this.$t('feedback.maxImages', { max: MAX_IMAGES }), true)
        return
      }
      const file = blob instanceof File
        ? blob
        : new File([blob], name || ('shot-' + (++this.seq) + '.png'), { type: blob.type || 'image/png' })
      this.images.push({ id: 'img-' + (++this.seq), file, url: URL.createObjectURL(file) })
    },
    removeImage(i) {
      const [gone] = this.images.splice(i, 1)
      if (gone) {
        try { URL.revokeObjectURL(gone.url) } catch (e) { /* ignore */ }
      }
    },
    // 文件选择框在这里现建：模板里写 <input type="file"> 会被 uni 编译成不支持
    // type="file" 的 uni Input，点了什么也不会发生
    pickImages() {
      const input = document.createElement('input')
      input.type = 'file'
      input.accept = 'image/*'
      input.multiple = true
      input.style.display = 'none'
      input.addEventListener('change', () => {
        for (const f of Array.from(input.files || [])) {
          if (f.type && f.type.startsWith('image/')) this.addImageBlob(f, f.name)
        }
        input.remove()
      })
      document.body.appendChild(input)
      input.click()
    },
    onPaste(e) {
      const items = (e.clipboardData && e.clipboardData.items) || []
      for (const item of items) {
        if (item.kind !== 'file') continue
        const f = item.getAsFile()
        if (f && f.type && f.type.startsWith('image/')) {
          e.preventDefault()
          this.addImageBlob(f, f.name)
        }
      }
    },
    onDrop(e) {
      this.dragging = false
      for (const f of Array.from((e.dataTransfer && e.dataTransfer.files) || [])) {
        if (f.type && f.type.startsWith('image/')) this.addImageBlob(f, f.name)
      }
    },

    // ---- 框选截图（走桌面壳既有的覆盖窗，与 OCR 摘录同一条 IPC）----
    async captureScreenshot() {
      if (!this.canScreenshot || this.capturing) return
      this.capturing = true
      // 覆盖窗是置顶的独立窗口，浮窗自己得先让开，否则截到的是浮窗本身
      this.open = false
      setGlobalOverlay(false)
      try {
        const resp = await host.ocr.startSelection({ mode: 'window' })
        if (!resp || resp.ok !== true) {
          if (!(resp && resp.cancelled)) {
            this.setStatus((resp && resp.message) || this.$t('feedback.screenshotFailed'), true)
          }
          return
        }
        const blob = await cropSelection(resp.payload)
        if (blob) this.addImageBlob(blob, 'screenshot.png')
      } catch (e) {
        this.setStatus((e && e.message) || this.$t('feedback.screenshotFailed'), true)
      } finally {
        this.capturing = false
        this.open = true
        setGlobalOverlay(true)
      }
    },

    // ---- 语音 ----
    async toggleRecording() {
      if (this.recording) {
        await this.stopRecording(false)
        return
      }
      if (this.audio) {
        this.setStatus(this.$t('feedback.onlyOneAudio'), true)
        return
      }
      if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices) {
        this.setStatus(this.$t('feedback.recordingUnsupported'), true)
        return
      }
      // getUserMedia 在飞（权限弹窗展示期间）this.recording 还是 false，二次点击会
      // 重入整段 try、开出第二路 getUserMedia/MediaRecorder，互相踩踏 this._recorder/
      // this._stream/this._recordTimer。用独立的启动中标志挡住重入，不能复用 recording
      // ——它要等 await 之后才置真。
      if (this._startingRecording) return
      this._startingRecording = true
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
        const mimeType = pickAudioMime()
        const recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream)
        const chunks = []
        recorder.ondataavailable = (ev) => { if (ev.data && ev.data.size) chunks.push(ev.data) }
        // onstop 是异步到达的：提交时如果还在录，必须等这个 promise 落地，
        // 否则那段刚录完的语音根本来不及进 files（用户会以为语音丢了）
        this._stopped = new Promise((resolve) => { this._resolveStopped = resolve })
        recorder.onstop = () => {
          const type = recorder.mimeType || mimeType || 'audio/webm'
          const blob = new Blob(chunks, { type })
          // 轨道不停的话 macOS 状态栏会一直亮着录音指示灯
          try { stream.getTracks().forEach((t) => t.stop()) } catch (e) { /* ignore */ }
          if (blob.size > 0) {
            const ext = type.indexOf('ogg') >= 0 ? '.ogg' : (type.indexOf('mp4') >= 0 ? '.m4a' : '.webm')
            this.audio = {
              file: new File([blob], 'voice' + ext, { type }),
              url: URL.createObjectURL(blob),
              seconds: this.recordSeconds,
            }
          }
          this.recording = false
          this.clearRecordTimer()
          if (this._resolveStopped) this._resolveStopped()
        }
        this._recorder = recorder
        this._stream = stream
        this.recordSeconds = 0
        this.recording = true
        recorder.start()
        this._recordTimer = setInterval(() => {
          this.recordSeconds += 1
          if (this.recordSeconds >= MAX_RECORD_SECONDS) this.stopRecording(false)
        }, 1000)
      } catch (e) {
        this.setStatus(this.$t('feedback.micPermissionDenied', { message: (e && e.message) || e }), true)
      } finally {
        this._startingRecording = false
      }
    },
    /** @returns {Promise<void>} 录音真正落成 File 之后才 resolve。 */
    stopRecording(discard) {
      if (!this._recorder) return Promise.resolve()
      if (discard) this._recorder.onstop = null
      try {
        if (this._recorder.state !== 'inactive') this._recorder.stop()
      } catch (e) { /* ignore */ }
      if (discard) {
        try { (this._stream || { getTracks: () => [] }).getTracks().forEach((t) => t.stop()) } catch (e) { /* ignore */ }
        this.recording = false
        this.clearRecordTimer()
        this._recorder = null
        this._stopped = null
        return Promise.resolve()
      }
      return this._stopped || Promise.resolve()
    },
    clearRecordTimer() {
      if (this._recordTimer) clearInterval(this._recordTimer)
      this._recordTimer = null
    },
    removeAudio() {
      this.stopPlay()
      if (this.audio) {
        try { URL.revokeObjectURL(this.audio.url) } catch (e) { /* ignore */ }
      }
      this.audio = null
    },
    // 试听用 JS 建播放器：模板里的 <audio> 会被 uni 编译成不存在的 Audio 组件（直接编译失败）
    togglePlay() {
      if (this.playing) {
        this.stopPlay()
        return
      }
      if (!this.audio) return
      try {
        this._player = new window.Audio(this.audio.url)
        this._player.onended = () => { this.playing = false }
        this._player.play()
        this.playing = true
      } catch (e) {
        this.setStatus(this.$t('feedback.playFailed', { message: (e && e.message) || e }), true)
      }
    },
    stopPlay() {
      if (this._player) {
        try { this._player.pause() } catch (e) { /* ignore */ }
      }
      this._player = null
      this.playing = false
    },

    // ---- 上下文与提交 ----
    currentPage() {
      try {
        const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
        const top = pages && pages.length ? pages[pages.length - 1] : null
        if (top && top.route) return String(top.route)
      } catch (e) { /* ignore */ }
      try {
        return String(window.location.hash || window.location.pathname || '')
      } catch (e) {
        return ''
      }
    },
    collectContext() {
      const ctx = {
        page: this.currentPage(),
        desktopShell: isDesktopHost(),
        userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : '',
        language: typeof navigator !== 'undefined' ? navigator.language : '',
        online: typeof navigator !== 'undefined' ? navigator.onLine : null,
        localTime: new Date().toString(),
      }
      try {
        ctx.window = { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio }
        ctx.screen = { w: window.screen.width, h: window.screen.height }
      } catch (e) { /* ignore */ }
      const errors = getRecentErrors()
      if (errors.length) ctx.recentErrors = errors
      return ctx
    },
    async submit() {
      if (this.submitting || !this.canSubmit) return
      if (this.recording) await this.stopRecording(false)
      this.submitting = true
      this.setStatus(this.$t('feedback.submitting'), false)
      try {
        const files = this.images.map((i) => i.file)
        if (this.audio) files.push(this.audio.file)
        const res = await submitFeedback({
          kind: this.kind,
          text: this.text,
          // getLastProjectId 无记录时返回 0，别把 0 当项目 id 传上去
          projectId: getLastProjectId() || null,
          page: this.currentPage(),
          clientContext: this.collectContext(),
        }, files)
        const data = (res && res.data) || {}
        this.reset()
        this.resultOk = true
        this.resultMessage = this.$t('feedback.receivedWithId', { id: data.id || '?' })
        this.view = 'result'
      } catch (e) {
        // 失败分支不 reset：文字/图片/语音原样留着，用户点「重试」能直接回到刚才那份草稿
        this.resultOk = false
        this.resultMessage = (e && e.message) || this.$t('feedback.submitFailed')
        this.view = 'result'
      } finally {
        this.submitting = false
      }
    },
    setStatus(msg, isError) {
      this.status = msg
      this.statusIsError = !!isError
    },
  },
}

// 主进程返回的是「整窗截图 + 视口坐标系里的选区」，裁剪要把选区映射回图片像素。
// 与 project-overview 的 OCR 裁剪同一套算法（ocrActions.js 的 ocrSetFrameFromDataUrl
// + cropOcrSelection），这里按浮窗自己的需要写成一个无状态函数。
async function cropSelection(payload) {
  if (!payload || !payload.dataUrl || !payload.selection) return null
  const img = await new Promise((resolve, reject) => {
    const im = new Image()
    im.onload = () => resolve(im)
    im.onerror = () => reject(new Error(t$('feedback.screenshotImageLoadFailed')))
    im.src = String(payload.dataUrl)
  })
  const vw = img.naturalWidth || img.width || 0
  const vh = img.naturalHeight || img.height || 0
  if (!vw || !vh) throw new Error(t$('feedback.screenshotImageSizeInvalid'))

  const b = payload.bounds || null
  const cw = b && b.width ? Number(b.width) : window.innerWidth
  const ch = b && b.height ? Number(b.height) : window.innerHeight
  const ox = b && typeof b.x === 'number' ? Number(b.x) : 0
  const oy = b && typeof b.y === 'number' ? Number(b.y) : 0
  const scale = Math.min(cw / vw, ch / vh)
  const dx = ox + (cw - vw * scale) / 2
  const dy = oy + (ch - vh * scale) / 2

  const s = payload.selection
  const left = Math.min(s.x1, s.x2)
  const top = Math.min(s.y1, s.y2)
  const w = Math.abs(s.x2 - s.x1)
  const h = Math.abs(s.y2 - s.y1)

  const clamp = (v, min, max) => Math.max(min, Math.min(max, v))
  const sx = clamp((left - dx) / scale, 0, vw - 1)
  const sy = clamp((top - dy) / scale, 0, vh - 1)
  const sw = clamp(w / scale, 1, vw - sx)
  const sh = clamp(h / scale, 1, vh - sy)

  const out = document.createElement('canvas')
  out.width = Math.max(1, Math.floor(sw))
  out.height = Math.max(1, Math.floor(sh))
  out.getContext('2d').drawImage(
    img, Math.floor(sx), Math.floor(sy), Math.floor(sw), Math.floor(sh),
    0, 0, out.width, out.height,
  )
  return new Promise((resolve) => out.toBlob((blob) => resolve(blob), 'image/png'))
}

function pickAudioMime() {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']
  for (const c of candidates) {
    try {
      if (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(c)) return c
    } catch (e) { /* ignore */ }
  }
  return ''
}
</script>

<style scoped>
.awdfb {
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.awdfb-launcher {
  position: fixed;
  right: 16px;
  /* 默认停在右缘约 60% 高度处：右下角（bottom:34px）正好压在 AI 面板 composer
     的模型选择器/发送键上（dev-board#213）。挪过的用户走 launcherStyle 的
     left/top 持久化坐标，不受这个默认值影响。 */
  bottom: 40vh;
  z-index: 99998;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 11px;
  border: 1px solid #DDE3E0;
  border-radius: 14px;
  background: #FFFFFF;
  color: #1A5336;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  user-select: none;
  box-shadow: 0 2px 10px rgba(18, 52, 77, 0.12);
  transition: box-shadow 0.15s ease, border-color 0.15s ease;
  /* 拖到顶部那条 38px 拖拽条上时，别让它被当成「拖窗口」 */
  -webkit-app-region: no-drag;
  /* 触摸/触控板上不写这条，pointermove 会被浏览器的滚动手势抢走 */
  touch-action: none;
}

.awdfb-launcher:hover {
  border-color: #5BD197;
  box-shadow: 0 4px 16px rgba(26, 83, 54, 0.18);
}

/* 拖动中：抬起来一点，并且关掉 transition——否则每一帧都在补间，跟手感全无 */
.awdfb-launcher.is-moving {
  cursor: grabbing;
  transition: none;
  border-color: #5BD197;
  box-shadow: 0 8px 22px rgba(26, 83, 54, 0.26);
}

.awdfb-mask {
  position: fixed;
  inset: 0;
  z-index: 99999;
  background: rgba(18, 52, 77, 0.14);
}

.awdfb-panel {
  position: fixed;
  right: 16px;
  bottom: 34px;
  width: 420px;
  max-width: calc(100vw - 32px);
  max-height: 78vh;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
  border: 1px solid #E3E8E5;
  border-radius: 12px;
  box-shadow: 0 16px 44px rgba(18, 52, 77, 0.2);
  overflow: hidden;
}

.awdfb-panel.is-dragging {
  border-color: #5BD197;
}

.awdfb-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #EEF1EF;
}

.awdfb-title {
  font-size: 14px;
  font-weight: 600;
  color: #1A5336;
}

.awdfb-head-right {
  display: inline-flex;
  align-items: center;
  gap: 12px;
}

.awdfb-link {
  color: #6C757D;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
}

.awdfb-link:hover {
  color: #1A5336;
}

.awdfb-x {
  color: #8A9691;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 4px;
  user-select: none;
}

.awdfb-x:hover {
  color: #12344D;
}

.awdfb-body {
  padding: 12px 14px;
  overflow-y: auto;
  flex: 1;
}

.awdfb-kinds {
  display: inline-flex;
  border: 1px solid #E3E8E5;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 10px;
}

.awdfb-kind {
  background: #FFFFFF;
  color: #6C757D;
  font-size: 12px;
  padding: 5px 16px;
  cursor: pointer;
  user-select: none;
}

.awdfb-kind.active {
  background: #1A5336;
  color: #FFFFFF;
}

.awdfb-text {
  width: 100%;
  min-height: 92px;
  box-sizing: border-box;
  resize: vertical;
  padding: 9px 10px;
  border: 1px solid #E3E8E5;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  color: #12344D;
  outline: none;
  font-family: inherit;
  background: #FFFFFF;
}

.awdfb-text:focus {
  border-color: #5BD197;
}

.awdfb-tools {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.awdfb-tool {
  border: 1px solid #E3E8E5;
  background: #F8F9FA;
  border-radius: 7px;
  padding: 5px 11px;
  font-size: 12px;
  color: #12344D;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.awdfb-tool:hover {
  border-color: #5BD197;
}

.awdfb-tool.disabled {
  opacity: 0.6;
  cursor: default;
}

.awdfb-tool.recording {
  border-color: #C0392B;
  color: #C0392B;
}

.awdfb-shots {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.awdfb-shot {
  position: relative;
  width: 76px;
  height: 56px;
  border: 1px solid #E3E8E5;
  border-radius: 6px;
  overflow: hidden;
  background: #F8F9FA;
}

.awdfb-shot img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.awdfb-shot-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 16px;
  height: 16px;
  border-radius: 8px;
  background: rgba(18, 52, 77, 0.65);
  color: #FFFFFF;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  cursor: pointer;
  user-select: none;
}

.awdfb-audio {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.awdfb-audio-label {
  font-size: 12px;
  color: #6C757D;
  white-space: nowrap;
}

.awdfb-hint {
  margin-top: 6px;
  font-size: 11px;
  color: #8A9691;
}

.awdfb-hint.err {
  color: #C0392B;
}

.awdfb-ctx-toggle {
  margin-top: 12px;
  font-size: 11px;
  color: #6C757D;
  cursor: pointer;
  user-select: none;
}

.awdfb-ctx-toggle:hover {
  color: #1A5336;
}

.awdfb-ctx {
  margin: 6px 0 0;
  max-height: 160px;
  overflow: auto;
  background: #F8F9FA;
  border: 1px solid #EEF1EF;
  border-radius: 6px;
  padding: 8px;
  font-size: 10px;
  line-height: 1.5;
  color: #6C757D;
  white-space: pre-wrap;
  word-break: break-all;
}

.awdfb-result {
  padding: 20px 6px 8px;
  text-align: center;
}

.awdfb-result-msg {
  font-size: 14px;
  color: #12344D;
  line-height: 1.6;
}

.awdfb-result.err .awdfb-result-msg {
  color: #C0392B;
}

.awdfb-result-actions {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.awdfb-mine-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.awdfb-mine-item {
  border: 1px solid #E3E8E5;
  border-radius: 8px;
  padding: 9px 11px;
}

.awdfb-mine-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.awdfb-mine-time {
  font-size: 11px;
  color: #8A9691;
}

.awdfb-mine-text {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #12344D;
  word-break: break-all;
}

.awdfb-mine-status {
  margin-top: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #6C757D;
}

.awdfb-mine-pr {
  color: #1A5336;
  cursor: pointer;
  user-select: none;
}

.awdfb-mine-pr:hover {
  text-decoration: underline;
}

.awdfb-badge {
  display: inline-block;
  border-radius: 4px;
  padding: 2px 7px;
  font-size: 11px;
  line-height: 1.5;
}

.awdfb-badge.bug {
  background: #FDEDEC;
  color: #C0392B;
}

.awdfb-badge.idea {
  background: #EAF6EF;
  color: #1A5336;
}

.awdfb-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid #EEF1EF;
  background: #FCFDFC;
}

.awdfb-status {
  font-size: 11px;
  color: #6C757D;
  flex: 1;
  min-width: 0;
  word-break: break-all;
}

.awdfb-status.err {
  color: #C0392B;
}

.awdfb-submit {
  border-radius: 8px;
  background: #1A5336;
  color: #FFFFFF;
  font-size: 13px;
  padding: 7px 20px;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.awdfb-submit.disabled {
  background: #B9C6C0;
  cursor: default;
}
</style>
