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
    <div
      v-if="!open"
      class="awdfb-launcher"
      role="button"
      tabindex="0"
      title="报告问题 / 提建议"
      @click="openPanel"
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
      <span>反馈</span>
    </div>

    <div v-if="open" class="awdfb-mask" @mousedown.self="closePanel">
      <div
        class="awdfb-panel"
        :class="{ 'is-dragging': dragging }"
        @dragover.prevent="dragging = true"
        @dragleave="dragging = false"
        @drop.prevent="onDrop"
      >
        <div class="awdfb-head">
          <div class="awdfb-title">告诉我们哪里不对</div>
          <div class="awdfb-x" role="button" title="关闭" @click="closePanel">✕</div>
        </div>

        <div class="awdfb-body">
          <div class="awdfb-kinds">
            <div
              class="awdfb-kind"
              role="button"
              :class="{ active: kind === 'BUG' }"
              @click="kind = 'BUG'"
            >报障</div>
            <div
              class="awdfb-kind"
              role="button"
              :class="{ active: kind === 'IDEA' }"
              @click="kind = 'IDEA'"
            >建议</div>
          </div>

          <component
            :is="'textarea'"
            ref="textarea"
            class="awdfb-text"
            :value="text"
            :placeholder="kind === 'BUG'
              ? '出了什么问题？刚才在做什么？（可直接粘贴截图）'
              : '你希望它变成什么样？（可直接粘贴截图）'"
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
            >{{ capturing ? '框选中…' : '框选截图' }}</div>
            <div class="awdfb-tool" role="button" @click="pickImages">选图片</div>
            <div
              class="awdfb-tool"
              role="button"
              :class="{ recording: recording }"
              @click="toggleRecording"
            >{{ recording ? '停止录音 ' + recordSeconds + 's' : '说一段' }}</div>
          </div>

          <div v-if="images.length" class="awdfb-shots">
            <div v-for="(img, i) in images" :key="img.id" class="awdfb-shot">
              <img :src="img.url" alt="" />
              <div class="awdfb-shot-x" role="button" title="移除" @click="removeImage(i)">✕</div>
            </div>
          </div>

          <div v-if="audio" class="awdfb-audio">
            <span class="awdfb-audio-label">语音 {{ audio.seconds }}s</span>
            <div class="awdfb-tool" role="button" @click="togglePlay">
              {{ playing ? '停止' : '试听' }}
            </div>
            <div class="awdfb-tool" role="button" @click="removeAudio">移除</div>
          </div>
          <div v-if="audio" class="awdfb-hint">
            没配转写服务时，这段录音会原样留给维护者听，不会被丢掉。
          </div>

          <div class="awdfb-ctx-toggle" role="button" @click="showContext = !showContext">
            {{ showContext ? '收起' : '查看' }}随反馈一起发送的现场信息（{{ contextSummary }}）
          </div>
          <pre v-if="showContext" class="awdfb-ctx">{{ contextPreview }}</pre>
        </div>

        <div class="awdfb-foot">
          <div class="awdfb-status" :class="{ err: statusIsError }">{{ status }}</div>
          <div
            class="awdfb-submit"
            role="button"
            :class="{ disabled: submitting || !canSubmit }"
            @click="submit"
          >{{ submitting ? '提交中…' : '提交' }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { host, isDesktopHost } from '@/services/host.js'
import { submitFeedback } from '@/services/api.js'
import { setGlobalOverlay } from '@/utils/overlayState.js'
import { getRecentErrors, recentErrorCount } from '@/utils/errorBuffer.js'
import { getLastProjectId } from '@/utils/recentProjects.js'

const MAX_IMAGES = 10
const MAX_RECORD_SECONDS = 120

export default {
  name: 'FeedbackWidget',
  data() {
    return {
      open: false,
      kind: 'BUG',
      text: '',
      images: [],
      audio: null,
      recording: false,
      recordSeconds: 0,
      playing: false,
      capturing: false,
      submitting: false,
      dragging: false,
      showContext: false,
      status: '',
      statusIsError: false,
      seq: 0,
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
      const bits = ['当前页面', '版本与系统']
      const n = recentErrorCount()
      if (n) bits.push(n + ' 条前端报错')
      bits.push('后端日志片段')
      return bits.join(' · ')
    },
    contextPreview() {
      return JSON.stringify(this.collectContext(), null, 2)
    },
  },
  beforeUnmount() {
    this.stopRecording(true)
    this.stopPlay()
    this.revokeAll()
    setGlobalOverlay(false)
  },
  methods: {
    openPanel() {
      this.open = true
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
        this.setStatus('最多 ' + MAX_IMAGES + ' 张图片', true)
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
            this.setStatus((resp && resp.message) || '截图失败', true)
          }
          return
        }
        const blob = await cropSelection(resp.payload)
        if (blob) this.addImageBlob(blob, 'screenshot.png')
      } catch (e) {
        this.setStatus((e && e.message) || '截图失败', true)
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
        this.setStatus('只能附一段语音，请先移除现有的', true)
        return
      }
      if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices) {
        this.setStatus('当前环境不支持录音', true)
        return
      }
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
        this.setStatus('拿不到麦克风权限：' + ((e && e.message) || e), true)
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
        this.setStatus('试听失败：' + ((e && e.message) || e), true)
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
      this.setStatus('提交中…', false)
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
        this.setStatus('收到了，谢谢。编号 #' + (data.id || '?'), false)
        setTimeout(() => {
          if (!this.submitting) this.closePanel()
        }, 1400)
      } catch (e) {
        this.setStatus((e && e.message) || '提交失败', true)
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
    im.onerror = () => reject(new Error('截图图片加载失败'))
    im.src = String(payload.dataUrl)
  })
  const vw = img.naturalWidth || img.width || 0
  const vh = img.naturalHeight || img.height || 0
  if (!vw || !vh) throw new Error('截图图片尺寸异常')

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
  bottom: 34px;
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
}

.awdfb-launcher:hover {
  border-color: #5BD197;
  box-shadow: 0 4px 16px rgba(26, 83, 54, 0.18);
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
