<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  音视频播放器（dev-board#1023 控制条 / #1024 自动字幕 / #1025 流式播放，规格
  docs/superpowers/specs/2026-09-29-media-player-design.md）。

  拥有：媒体元素、播放状态、字幕状态机、快捷键、EvidenceLink 时间定位。
  MediaControls 与 MediaTranscript 是纯展示，只 emit。

  三条不能改回去的做法：
  1. 媒体元素命令式创建（document.createElement('video') / new Audio()），实例挂在
     this._media、不进 data。模板里写 <video>/<audio> 会被 uni-h5 编译器替换成它自己的
     组件（事件名、ref 都不可靠——FeedbackWidget、会议面板、旧 FilePreview 都踩过）；
     塞进 data 会被 Vue 代理一层。
  2. 模板用原生 div/span，不用 uni 的 view/text：uni 会把 view 上的键盘/鼠标事件重建成
     普通对象（target 不是真节点、shiftKey 丢失），快捷键与滑轨拖拽都要真事件。
  3. 快捷键只挂在根元素（tabindex=0）上，不挂 window：工作台有捕获阶段的 IDE 键位，
     AI 输入框与编辑器都不能被抢键。
-->
<template>
  <div
    ref="root"
    :class="rootClass"
    tabindex="0"
    :aria-label="file && file.name ? file.name : null"
    @keydown="onKeydown"
    @mousedown="onRootMouseDown"
    @mousemove="onPointerMove"
    @mouseleave="onPointerLeave"
  >
    <!-- ==================== 视频：overlay 皮 ==================== -->
    <div v-if="kind === 'video'" class="mp-main" :class="{ 'is-side': transcriptShown && wide, 'is-below': transcriptShown && !wide }">
      <div class="mp-stage">
        <!-- 媒体元素由 setupMedia() 挂进来；这个容器里不放任何 Vue 管理的子节点 -->
        <div ref="surface" class="mp-surface"></div>
        <div class="mp-hit" @click="onStageClick" @dblclick="onStageDblClick"></div>

        <div v-if="stageStatus === 'loading'" class="mp-spinner" aria-hidden="true"></div>
        <div v-else-if="stageStatus === 'error'" class="mp-stage-msg" role="alert">{{ $t('files.player.playFailed') }}</div>
        <div v-else-if="stageStatus === 'pip'" class="mp-stage-msg">
          <svg class="mp-stage-msg-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.pip" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>{{ $t('files.player.pipActive') }}</span>
        </div>
        <div v-else-if="stageStatus === 'paused'" class="mp-badge" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.play" :key="gi" :d="d" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
          </svg>
        </div>

        <!-- 字幕叠层：容器查询单位随舞台宽度缩放；控制条出现时抬高让位 -->
        <div v-if="captionText" class="mp-caption-layer" :class="{ 'is-raised': controlsShown }" aria-live="polite">
          <div class="mp-caption">
            <span v-if="captionSpeaker" class="mp-caption-speaker">{{ $t('files.player.speakerPrefix', { name: captionSpeaker }) }}</span>{{ captionText }}
          </div>
        </div>

        <!-- EvidenceLink 时间标记：定位到的时刻 + 一键继续播放（P3 契约） -->
        <div v-if="markShown" class="mp-mark">
          <span class="mp-mark-label">{{ $t('files.locate.mediaMark', { time: clock(locatorSec) }) }}</span>
          <div class="mp-mark-btn" role="button" tabindex="0" @click="playFromMark" @keydown.enter.space.prevent.stop="playFromMark">{{ $t('files.locate.playFromMark') }}</div>
          <div class="mp-mark-close" role="button" tabindex="0" :aria-label="$t('files.locate.close')" :title="$t('files.locate.close')" @click="markVisible = false" @keydown.enter.space.prevent.stop="markVisible = false">×</div>
        </div>

        <div class="mp-scrim" :class="{ 'is-hidden': !controlsShown }"></div>
        <div class="mp-controls" :class="{ 'is-hidden': !controlsShown }" @focusin="controlsFocused = true" @focusout="onControlsFocusOut">
          <MediaControls
            ref="controls"
            variant="overlay"
            kind="video"
            :playing="playing"
            :current-sec="currentSec"
            :duration-sec="durationSec"
            :buffered="buffered"
            :volume="volume"
            :muted="muted"
            :rate="rate"
            :caption-state="ccState"
            :captions-on="captionsOn"
            :caption-error="captionError"
            :transcript-open="transcriptOpen"
            :pip-available="pipAvailable"
            :pip-active="pipActive"
            :fullscreen="fullscreen"
            :mark-sec="markSec"
            @toggle-play="togglePlay"
            @seek="seek"
            @volume="setVolume"
            @toggle-mute="toggleMute"
            @rate="setRate"
            @caption-click="onCaptionClick"
            @toggle-transcript="toggleTranscript"
            @toggle-pip="togglePip"
            @toggle-fullscreen="toggleFullscreen"
            @popover="onPopover"
          />
        </div>
      </div>

      <MediaTranscript
        v-if="transcriptShown"
        class="mp-transcript"
        :cues="cues"
        :labels="labels"
        :speaker-count="speakerCount"
        :current-index="cueIndex"
        :following="following"
        @seek="onTranscriptSeek"
        @follow="onFollow"
        @unfollow="onUnfollow"
      />
    </div>

    <!-- ==================== 音频：card 皮 ==================== -->
    <template v-else>
      <div class="mp-card" :class="{ 'is-wide': transcriptShown }">
        <div class="mp-card-head">
          <svg class="mp-card-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in ICONS.audioLines" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span class="mp-card-name">{{ file && file.name }}</span>
        </div>
        <MediaControls
          ref="controls"
          variant="card"
          kind="audio"
          :playing="playing"
          :current-sec="currentSec"
          :duration-sec="durationSec"
          :buffered="buffered"
          :volume="volume"
          :muted="muted"
          :rate="rate"
          :caption-state="ccState"
          :captions-on="captionsOn"
          :caption-error="captionError"
          :transcript-open="transcriptShown"
          :pip-available="false"
          :fullscreen="false"
          :mark-sec="markSec"
          @toggle-play="togglePlay"
          @seek="seek"
          @volume="setVolume"
          @toggle-mute="toggleMute"
          @rate="setRate"
          @caption-click="onCaptionClick"
          @popover="onPopover"
        />
        <div v-if="markShown" class="mp-mark is-inline">
          <span class="mp-mark-label">{{ $t('files.locate.mediaMark', { time: clock(locatorSec) }) }}</span>
          <div class="mp-mark-btn" role="button" tabindex="0" @click="playFromMark" @keydown.enter.space.prevent.stop="playFromMark">{{ $t('files.locate.playFromMark') }}</div>
          <div class="mp-mark-close" role="button" tabindex="0" :aria-label="$t('files.locate.close')" :title="$t('files.locate.close')" @click="markVisible = false" @keydown.enter.space.prevent.stop="markVisible = false">×</div>
        </div>
        <div v-if="stageStatus === 'error'" class="mp-card-msg is-error" role="alert">{{ $t('files.player.playFailed') }}</div>
        <div v-else-if="ccState === 'empty'" class="mp-card-msg">{{ $t('files.captions.empty') }}</div>
      </div>
      <MediaTranscript
        v-if="transcriptShown"
        class="mp-transcript mp-transcript--audio"
        :cues="cues"
        :labels="labels"
        :speaker-count="speakerCount"
        :current-index="cueIndex"
        :following="following"
        @seek="onTranscriptSeek"
        @follow="onFollow"
        @unfollow="onUnfollow"
      />
    </template>
  </div>
</template>

<script>
import MediaControls from './MediaControls.vue'
import MediaTranscript from './MediaTranscript.vue'
import { ICONS } from '@/config/icons.js'
import {
  getFileDownloadUrl, getMeetingByFile, getMeetingRecording,
  registerMeetingFromFile, transcribeMeetingRecording,
} from '@/services/api.js'
import { getAuthHeaders, getSessionId } from '@/utils/auth.js'
import { buildStreamUrl, redactToken, shouldFallbackToBlob } from '@/utils/media/mediaSource.js'
import { mimeTypeFor } from '@/utils/media/mediaTypes.js'
import { parseSegments, buildSpeakerLabels, buildCues, cueIndexAt } from '@/utils/media/transcriptCues.js'
import { captionStateFrom, CAPTIONS_OFF_KEY } from '@/utils/media/captionState.js'
import { resolveShortcut, nextRate, isEditableTarget } from '@/utils/media/mediaShortcuts.js'
import { confirmPaidTranscription } from '@/utils/paidTranscribeGate.js'

const POLL_MS = 4000
const IDLE_MS = 2500
const SHORTCUT_REVEAL_MS = 1500
const FOLLOW_RESUME_MS = 4000
const WIDE_PX = 760

function readCaptionsOff() {
  try {
    return uni.getStorageSync(CAPTIONS_OFF_KEY) === '1'
  } catch (e) {
    return false
  }
}

export default {
  name: 'MediaPlayer',
  components: { MediaControls, MediaTranscript },
  props: {
    kind: { type: String, default: 'video' }, // 'video' | 'audio'
    // 需要 id / wpsFileId / name / fileType / projectId
    file: { type: Object, default: null },
    // file 上没有 projectId 时的兜底
    projectId: { type: [String, Number], default: null },
    // EvidenceLink 起播时刻（秒），来自 parseMediaStartSec；null = 没有定位
    locatorSec: { type: Number, default: null },
  },
  emits: ['locator-consumed', 'error'],
  data() {
    return {
      playing: false,
      currentSec: 0,
      durationSec: 0,
      buffered: [],
      volume: 1,
      muted: false,
      rate: 1,
      loading: true,
      buffering: false,
      failed: false,
      fullscreen: false,
      pipActive: false,
      pipAvailable: false,
      // 视频控制条自动隐藏
      idle: false,
      popoverOpen: false,
      controlsFocused: false,
      wide: false,
      // EvidenceLink 时间标记条（用户可关）
      markVisible: this.locatorSec !== null && this.locatorSec !== undefined,
      // 字幕（规格 3.6）：captionState 为 null 表示拿不到项目，CC 不渲染
      meeting: null,
      captionState: null,
      captionsOn: true,
      captionBusy: false,
      // 冻结数组：上千段的会议不需要逐个对象做响应式代理
      cues: Object.freeze([]),
      labels: {},
      speakerCount: 0,
      transcriptOpen: false,
      following: true,
    }
  },
  computed: {
    ICONS() { return ICONS },
    rootClass() {
      return [
        'mp',
        this.kind === 'video' ? 'mp--overlay mp--video' : 'mp--card mp--audio',
        {
          'is-idle': this.kind === 'video' && !this.controlsShown,
          'is-fullscreen': this.fullscreen,
          'has-transcript': this.transcriptShown,
        },
      ]
    },
    effectiveProjectId() {
      const f = this.file
      if (f && f.projectId !== undefined && f.projectId !== null && f.projectId !== '') return f.projectId
      return this.projectId
    },
    fileId() {
      return this.file && this.file.id !== undefined ? this.file.id : null
    },
    // 登记转写要 projectId + 项目文件 id，缺一个 CC 就不出现（无从登记）
    captionsSupported() {
      return this.effectiveProjectId !== null && this.effectiveProjectId !== undefined && this.effectiveProjectId !== ''
        && this.fileId !== null
    },
    ccState() {
      return this.captionsSupported ? this.captionState : null
    },
    captionError() {
      return (this.meeting && this.meeting.error) || ''
    },
    controlsShown() {
      if (this.kind !== 'video') return true
      return !this.playing || !this.idle || this.popoverOpen || this.controlsFocused
    },
    stageStatus() {
      if (this.failed) return 'error'
      if (this.pipActive) return 'pip'
      if (this.loading || this.buffering) return 'loading'
      if (!this.playing) return 'paused'
      return ''
    },
    transcriptShown() {
      if (this.captionState !== 'ready') return false
      return this.kind === 'video' ? this.transcriptOpen : this.captionsOn
    },
    cueIndex() {
      if (this.captionState !== 'ready' || !this.cues.length) return -1
      return cueIndexAt(this.cues, this.currentSec * 1000)
    },
    currentCue() {
      return this.cueIndex >= 0 ? this.cues[this.cueIndex] : null
    },
    captionText() {
      if (this.kind !== 'video' || this.captionState !== 'ready' || !this.captionsOn) return ''
      return this.currentCue ? this.currentCue.text : ''
    },
    // 叠层字幕只在说话人 ≥ 2 时带前缀（规格 3.5）
    captionSpeaker() {
      const c = this.currentCue
      if (!c || this.speakerCount < 2) return ''
      if (c.speaker === undefined || c.speaker === null || c.speaker === '') return ''
      return this.labels[String(c.speaker)] || ''
    },
    markShown() {
      return this.locatorSec !== null && this.locatorSec !== undefined && this.markVisible
    },
    markSec() {
      return this.markShown ? this.locatorSec : null
    },
  },
  watch: {
    // 同一文件被另一个 EvidenceLink 点中（换了时刻）：重新落定位
    locatorSec(sec) {
      if (sec === null || sec === undefined) return
      this.relocate()
    },
  },
  mounted() {
    this._destroyed = false
    this._off = []
    this._meetingSeq = 0
    this.pipAvailable = this.kind === 'video' && typeof document !== 'undefined' && !!document.pictureInPictureEnabled
    this.captionsOn = !readCaptionsOff()
    this._locatorPending = this.locatorSec !== null && this.locatorSec !== undefined
    this.setupMedia()
    this.loadMeeting()
    document.addEventListener('fullscreenchange', this.onFullscreenChange)
    const root = this.$refs.root
    if (root && typeof ResizeObserver === 'function') {
      this._ro = new ResizeObserver((entries) => {
        const w = entries[0] && entries[0].contentRect ? entries[0].contentRect.width : 0
        this.wide = w >= WIDE_PX
      })
      this._ro.observe(root)
    }
  },
  beforeUnmount() {
    this._destroyed = true
    this.stopPolling()
    clearTimeout(this._idleTimer)
    clearTimeout(this._followTimer)
    if (this._ro) { this._ro.disconnect(); this._ro = null }
    document.removeEventListener('fullscreenchange', this.onFullscreenChange)
    try {
      if (document.fullscreenElement && document.fullscreenElement === this.$refs.root) document.exitFullscreen().catch(() => {})
    } catch (e) { /* 不在全屏 */ }
    this.teardownMedia()
  },
  methods: {
    clock(sec) {
      const s = Math.max(0, Math.floor(Number(sec) || 0))
      const h = Math.floor(s / 3600)
      const m = Math.floor((s % 3600) / 60)
      const ss = String(s % 60).padStart(2, '0')
      return h > 0 ? h + ':' + String(m).padStart(2, '0') + ':' + ss : m + ':' + ss
    },
    toast(title) {
      try {
        uni.showToast({ title, icon: 'none', duration: 3000 })
      } catch (e) { /* 非 uni 环境 */ }
    },
    /** FilePreview 渲染出媒体分支、且焦点无主（body）时调用，让快捷键立刻可用 */
    focusRoot() {
      const r = this.$refs.root
      if (r && typeof r.focus === 'function') r.focus({ preventScroll: true })
    },

    // ==================== 媒体元素 ====================
    setupMedia() {
      const f = this.file
      if (!f) return
      let el
      try {
        el = this.kind === 'video' ? document.createElement('video') : new window.Audio()
      } catch (e) {
        console.warn('[MediaPlayer] 媒体元素创建失败', e)
        this.markFailed({ code: 0 })
        return
      }
      el.preload = 'metadata'
      // 自动播放由 afterMetadata 决定（带定位时绝不自动播，见 EvidenceLink 契约）
      el.autoplay = false
      el.controls = false
      el.volume = this.volume
      el.muted = this.muted
      el.playbackRate = this.rate
      if (this.kind === 'video') {
        el.setAttribute('playsinline', '')
        el.playsInline = true
        el.className = 'mp-video'
        const surface = this.$refs.surface
        if (surface) surface.appendChild(el)
      }
      const on = (name, fn) => {
        el.addEventListener(name, fn)
        this._off.push(() => el.removeEventListener(name, fn))
      }
      on('loadedmetadata', () => this.onMetadata())
      on('durationchange', () => this.onDurationChange())
      on('timeupdate', () => this.scheduleTick())
      on('seeked', () => { this.buffering = false; this.scheduleTick(); this.readBuffered() })
      on('progress', () => this.readBuffered())
      on('play', () => { this.playing = true; this.armIdle(IDLE_MS) })
      on('playing', () => { this.buffering = false; this.loading = false })
      on('pause', () => { this.playing = false; this.idle = false })
      on('ended', () => { this.playing = false; this.idle = false })
      on('waiting', () => { this.buffering = true })
      on('canplay', () => { this.buffering = false; this.loading = false })
      on('volumechange', () => { this.volume = el.volume; this.muted = el.muted })
      on('ratechange', () => { this.rate = el.playbackRate })
      on('error', () => this.onMediaError())
      if (this.kind === 'video') {
        on('enterpictureinpicture', () => { this.pipActive = true })
        on('leavepictureinpicture', () => { this.pipActive = false })
      }
      this._media = el
      this._fellBack = false
      this._autoplayed = false
      // 直链流式播放（#1025）：媒体元素设不了请求头，鉴权走 ?token=
      this._streamUrl = buildStreamUrl(getFileDownloadUrl(f.wpsFileId || f.id), getSessionId())
      el.src = this._streamUrl
    },
    teardownMedia() {
      if (this._raf && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(this._raf)
      this._raf = 0
      // 丢弃在途的 blob 回退请求
      this._mediaReqId = (this._mediaReqId || 0) + 1
      if (this._xhr) {
        try { this._xhr.abort() } catch (e) { /* ignore */ }
        this._xhr = null
      }
      const el = this._media
      this._media = null
      if (el) {
        // 先摘监听再清 src：清 src 本身会触发一次 error（code 4），别让它走回退
        (this._off || []).forEach((fn) => fn())
        this._off = []
        try {
          if (document.pictureInPictureElement === el) document.exitPictureInPicture().catch(() => {})
        } catch (e) { /* ignore */ }
        try { el.pause() } catch (e) { /* ignore */ }
        el.removeAttribute('src')
        try { el.load() } catch (e) { /* ignore */ }
        if (el.parentNode) el.parentNode.removeChild(el)
      }
      this.revokeBlob()
    },
    revokeBlob() {
      if (this._blobUrl) {
        URL.revokeObjectURL(this._blobUrl)
        this._blobUrl = ''
      }
    },
    onMetadata() {
      const el = this._media
      if (!el) return
      this.loading = false
      const d = el.duration
      // MediaRecorder 录的 webm 头里没有时长（Infinity）：跳到极远处逼浏览器算出来，
      // durationchange 里拿到真值后跳回开头再走定位/自动播放
      if (d === Infinity) {
        this._fixingDuration = true
        try {
          el.currentTime = 1e101
          return
        } catch (e) {
          this._fixingDuration = false
        }
      }
      this.durationSec = Number.isFinite(d) ? d : 0
      this.afterMetadata()
    },
    onDurationChange() {
      const el = this._media
      if (!el) return
      const d = el.duration
      if (!Number.isFinite(d) || d <= 0) return
      this.durationSec = d
      if (this._fixingDuration) {
        this._fixingDuration = false
        try { el.currentTime = 0 } catch (e) { /* ignore */ }
        this.currentSec = 0
        this.afterMetadata()
      }
    },
    // 元数据就绪后：有定位 → seek 并停在那一帧；没有定位 → 视频自动播放、音频不动
    afterMetadata() {
      this.readBuffered()
      if (this.locatorSec !== null && this.locatorSec !== undefined) {
        this.seekToLocator()
        return
      }
      if (this.kind === 'video' && !this._autoplayed) {
        this._autoplayed = true
        this.playMedia()
      }
    },
    // media 定位：seek 到 locatorSec 并**停在那一帧**——自动播下去等于当场把定位冲掉。
    // 元数据没就绪时不写（loadedmetadata 会再调一次）。落地后 emit locator-consumed 一次。
    seekToLocator() {
      const sec = this.locatorSec
      const el = this._media
      if (sec === null || sec === undefined || !el) return false
      if (!(el.readyState >= 1) || this._fixingDuration) return false
      try {
        el.currentTime = sec
        if (typeof el.pause === 'function') el.pause()
      } catch (e) {
        return false
      }
      this.currentSec = sec
      if (this._locatorPending) {
        this._locatorPending = false
        this.$emit('locator-consumed')
      }
      return true
    },
    // 同一文件再次被链接点中：重新亮出时间标记并落定位
    relocate() {
      if (this.locatorSec === null || this.locatorSec === undefined) return
      this.markVisible = true
      this._locatorPending = true
      this.seekToLocator()
    },
    // 时间标记上的「从这里播放」
    playFromMark() {
      const sec = this.locatorSec
      const el = this._media
      if (!el) return
      try {
        if (sec !== null && sec !== undefined && Math.abs((el.currentTime || 0) - sec) > 0.5) el.currentTime = sec
      } catch (e) { /* metadata 未就绪 */ }
      this.playMedia()
    },
    playMedia() {
      const el = this._media
      if (!el) return
      try {
        const p = el.play()
        if (p && typeof p.catch === 'function') p.catch(() => {})
      } catch (e) { /* 自动播放被策略拦下时停在暂停态，中央徽标会提示可点 */ }
    },
    // timeupdate 一秒四次，rAF 合并到帧上再写响应式数据
    scheduleTick() {
      if (this._raf || this._fixingDuration) return
      const run = () => {
        this._raf = 0
        const el = this._media
        if (el && !this._fixingDuration) this.currentSec = el.currentTime || 0
      }
      if (typeof requestAnimationFrame === 'function') this._raf = requestAnimationFrame(run)
      else run()
    },
    readBuffered() {
      const el = this._media
      if (!el || !el.buffered) return
      const out = []
      try {
        for (let i = 0; i < el.buffered.length; i++) out.push([el.buffered.start(i), el.buffered.end(i)])
      } catch (e) { /* ignore */ }
      this.buffered = out
    },
    onMediaError() {
      const el = this._media
      if (!el) return
      const code = el.error ? el.error.code : 0
      if (shouldFallbackToBlob(code, this._fellBack)) {
        this._fellBack = true
        console.warn('[MediaPlayer] 直链播放失败，回退到 blob：', redactToken(this._streamUrl), 'code=', code)
        this.loadBlob()
        return
      }
      console.error('[MediaPlayer] 媒体播放失败 code=', code, el.error && el.error.message)
      this.markFailed({ code })
    },
    markFailed(detail) {
      this.failed = true
      this.loading = false
      this.buffering = false
      this.playing = false
      this.$emit('error', detail || {})
    },
    // 直链不通时的回退（原 FilePreview.loadMediaResource 的 XHR blob 逻辑，连竞态防护一起搬来）：
    // 带 X-Session-Id 整个拉成 blob 再播。只回退一次，blob 也失败就是真的放不了。
    loadBlob() {
      const f = this.file
      if (!f) return
      const url = getFileDownloadUrl(f.wpsFileId || f.id)
      const reqId = (this._mediaReqId = (this._mediaReqId || 0) + 1)
      this.loading = true
      const headers = getAuthHeaders() || {}
      const mimeType = mimeTypeFor(f.fileType)
      const xhr = new XMLHttpRequest()
      xhr.open('GET', url, true)
      xhr.responseType = 'blob' // 直接要 blob，避开 arraybuffer 的大小限制
      Object.keys(headers).forEach((key) => xhr.setRequestHeader(key, headers[key]))
      xhr.onload = () => {
        if (this._destroyed || this._mediaReqId !== reqId) return // 已换文件或卸载，丢弃陈旧响应
        this._xhr = null
        if (xhr.status === 200) {
          let blob = xhr.response
          // blob 没带对的 MIME 时重新包一层，否则部分容器解不开
          if (mimeType && blob.type !== mimeType) blob = new Blob([blob], { type: mimeType })
          this.revokeBlob()
          this._blobUrl = URL.createObjectURL(blob)
          if (this._media) this._media.src = this._blobUrl
        } else {
          console.error('[MediaPlayer] blob 回退请求失败 status=', xhr.status)
          this.markFailed({ code: 0, status: xhr.status })
        }
      }
      xhr.onerror = () => {
        if (this._destroyed || this._mediaReqId !== reqId) return
        this._xhr = null
        console.error('[MediaPlayer] blob 回退网络错误')
        this.markFailed({ code: 2 })
      }
      this._xhr = xhr
      xhr.send()
    },

    // ==================== 播放控制 ====================
    togglePlay() {
      const el = this._media
      if (!el || this.failed) return
      if (el.paused || el.ended) this.playMedia()
      else el.pause()
    },
    currentTimeNow() {
      const el = this._media
      return el ? el.currentTime || 0 : this.currentSec
    },
    seek(sec) {
      const el = this._media
      if (!el) return
      const max = this.durationSec > 0 ? this.durationSec : Infinity
      const t = Math.min(max, Math.max(0, Number(sec) || 0))
      try {
        el.currentTime = t
      } catch (e) {
        return
      }
      this.currentSec = t
      if (this.kind === 'video') this.armIdle(IDLE_MS)
    },
    setVolume(v) {
      const el = this._media
      const vol = Math.min(1, Math.max(0, Number(v) || 0))
      this.volume = vol
      this.muted = vol === 0
      if (el) {
        el.volume = vol
        el.muted = this.muted
      }
    },
    volumeBy(delta) {
      const base = this.muted ? 0 : this.volume
      const vol = Math.min(1, Math.max(0, Math.round((base + delta) * 100) / 100))
      this.setVolume(vol)
    },
    toggleMute() {
      const el = this._media
      if (this.muted || this.volume <= 0) {
        if (this.volume <= 0) this.volume = 0.5
        this.muted = false
      } else {
        this.muted = true
      }
      if (el) {
        el.volume = this.volume
        el.muted = this.muted
      }
    },
    setRate(r) {
      const el = this._media
      this.rate = r
      if (el) el.playbackRate = r
    },
    toggleFullscreen() {
      if (this.kind !== 'video') return
      const root = this.$refs.root
      try {
        if (document.fullscreenElement === root) {
          document.exitFullscreen().catch(() => {})
        } else if (root && typeof root.requestFullscreen === 'function') {
          // 全屏的是播放器根元素而不是 video：自绘控制条与字幕要跟着进全屏
          root.requestFullscreen().catch(() => {})
        }
      } catch (e) { /* 宿主不给全屏 */ }
    },
    onFullscreenChange() {
      this.fullscreen = !!document.fullscreenElement && document.fullscreenElement === this.$refs.root
    },
    togglePip() {
      const el = this._media
      if (!el || this.kind !== 'video') return
      try {
        if (document.pictureInPictureElement === el) {
          document.exitPictureInPicture().catch(() => {})
        } else if (typeof el.requestPictureInPicture === 'function') {
          el.requestPictureInPicture().catch(() => {})
        }
      } catch (e) { /* 不支持 */ }
    },

    // ==================== 控制条显隐（视频） ====================
    armIdle(ms) {
      clearTimeout(this._idleTimer)
      this.idle = false
      this._idleTimer = setTimeout(() => {
        if (this.playing) this.idle = true
      }, ms)
    },
    onPointerMove() {
      if (this.kind !== 'video') return
      // mousemove 很密，重置计时器节流到 150ms 一次
      const now = Date.now()
      if (!this.idle && this._lastMove && now - this._lastMove < 150) return
      this._lastMove = now
      this.armIdle(IDLE_MS)
    },
    onPointerLeave() {
      if (this.kind !== 'video' || !this.playing) return
      this.armIdle(600)
    },
    onControlsFocusOut(e) {
      const wrap = e.currentTarget
      if (!wrap || !e.relatedTarget || !wrap.contains(e.relatedTarget)) this.controlsFocused = false
    },
    onPopover(open) {
      this.popoverOpen = !!open
      if (!open) this._popoverClosedAt = Date.now()
    },
    onStageClick() {
      // 刚点外部关掉倍速菜单的那一下不算「切换播放」
      if (this._popoverClosedAt && Date.now() - this._popoverClosedAt < 300) return
      this.togglePlay()
    },
    onStageDblClick() {
      this.toggleFullscreen()
    },
    // 按钮与滑轨上按下不抢焦点（焦点统一留在根元素，空格才始终是播放/暂停）；
    // 逐字稿区域不拦，保留选中文字复制的能力
    onRootMouseDown(e) {
      const t = e.target
      if (isEditableTarget(t)) return
      const inControls = !!(t && typeof t.closest === 'function' && t.closest('.mpc, .mp-mark'))
      const inTranscript = !!(t && typeof t.closest === 'function' && t.closest('.mpt'))
      if (inControls) e.preventDefault()
      if (!inTranscript) this.focusRoot()
    },

    // ==================== 快捷键（规格 3.7） ====================
    onKeydown(e) {
      const action = resolveShortcut(e, { kind: this.kind, captionsAvailable: this.captionState === 'ready' })
      if (!action) {
        // Tab 走进控制条时先把它亮出来
        if (e.key === 'Tab' && this.kind === 'video') this.armIdle(IDLE_MS)
        return
      }
      if (action.type === 'close-popover') {
        // 焦点在根元素上时关菜单不回焦到倍速按钮（否则下一下空格会重新打开菜单而不是播放）；
        // 焦点在菜单里的 Esc 由 MediaControls 自己处理并回焦
        const closed = this.$refs.controls && this.$refs.controls.closePopover(false)
        // 没有弹层可关就放行（全屏的 Esc 浏览器自己处理，工作台的 Esc 也不抢）
        if (!closed) return
        e.preventDefault()
        e.stopPropagation()
        return
      }
      e.preventDefault()
      e.stopPropagation()
      this.runShortcut(action)
      if (this.kind === 'video') this.armIdle(SHORTCUT_REVEAL_MS)
    },
    runShortcut(a) {
      switch (a.type) {
        case 'toggle-play': this.togglePlay(); break
        case 'seek-by': this.seek(this.currentTimeNow() + a.value); break
        case 'seek-to-ratio': if (this.durationSec > 0) this.seek(a.value * this.durationSec); break
        case 'volume-by': this.volumeBy(a.value); break
        case 'toggle-mute': this.toggleMute(); break
        case 'toggle-fullscreen': this.toggleFullscreen(); break
        case 'toggle-captions': this.toggleCaptions(); break
        case 'rate-step': this.setRate(nextRate(this.rate, a.value)); break
        default: break
      }
    },

    // ==================== 字幕（规格 3.6） ====================
    async loadMeeting() {
      if (!this.captionsSupported) {
        this.captionState = null
        return
      }
      const seq = ++this._meetingSeq
      try {
        const res = await getMeetingByFile(this.effectiveProjectId, this.fileId)
        if (this._destroyed || seq !== this._meetingSeq) return
        this.applyMeeting(res ? res.meeting : null)
      } catch (e) {
        if (this._destroyed || seq !== this._meetingSeq) return
        // 读不到按「没有记录」处理：CC 仍能点「生成字幕」，不把播放器本身搞坏
        console.warn('[MediaPlayer] 读取字幕记录失败：', e && e.message)
        this.applyMeeting(null)
      }
    },
    applyMeeting(m) {
      const prev = this.captionState
      this.meeting = m || null
      const state = captionStateFrom(this.meeting)
      this.captionState = state
      if (state === 'ready') {
        const key = m.id + ':' + (m.updatedAt || '') + ':' + String(m.transcriptJson || '').length + ':' + (m.speakerNames || '')
        if (key !== this._cueKey) {
          this._cueKey = key
          const segs = parseSegments(m.transcriptJson)
          const locale = (this.$i18n && this.$i18n.locale) || 'zh-CN'
          const { labels, count } = buildSpeakerLabels(segs, m.speakerNames, locale)
          this.cues = Object.freeze(buildCues(segs))
          this.labels = labels
          this.speakerCount = count
        }
      } else {
        this._cueKey = ''
        this.cues = Object.freeze([])
        this.labels = {}
        this.speakerCount = 0
        this.transcriptOpen = false
      }
      if (state === 'transcribing') this.schedulePoll()
      else this.stopPolling()
      // 本次会话里看着它从「生成中」走到终态：给一句话（CC 按钮本身也会变）
      if (prev === 'transcribing' && state !== 'transcribing') {
        if (state === 'ready') this.toast(this.$t('files.captions.ready'))
        else if (state === 'failed') this.toast(this.$t('files.captions.failed'))
        else if (state === 'empty') this.toast(this.$t('files.captions.empty'))
      }
    },
    schedulePoll() {
      if (this._pollTimer || this._destroyed) return
      this._pollTimer = setTimeout(async () => {
        this._pollTimer = null
        const m = this.meeting
        if (!m || this._destroyed) return
        const seq = ++this._meetingSeq
        try {
          const fresh = await getMeetingRecording(m.id)
          if (this._destroyed || seq !== this._meetingSeq) return
          this.applyMeeting(fresh)
        } catch (e) {
          if (this._destroyed || seq !== this._meetingSeq) return
          // 一次没问到（网络抖动）不算终态，接着问
          this.schedulePoll()
        }
      }, POLL_MS)
    },
    stopPolling() {
      if (this._pollTimer) clearTimeout(this._pollTimer)
      this._pollTimer = null
    },
    toggleCaptions() {
      if (this.captionState !== 'ready') return
      this.captionsOn = !this.captionsOn
      // 只存「关」，默认开
      try {
        if (this.captionsOn) uni.removeStorageSync(CAPTIONS_OFF_KEY)
        else uni.setStorageSync(CAPTIONS_OFF_KEY, '1')
      } catch (e) { /* 存不下就只在本次生效 */ }
    },
    toggleTranscript() {
      this.transcriptOpen = !this.transcriptOpen
      if (this.transcriptOpen) this.onFollow()
    },
    // CC 点击按五态分派（规格 3.6）。提交转写会在平台档预扣 Credits，
    // 与资源管理器右键、会议面板同走付费确认（dev-board#968）。
    async onCaptionClick() {
      const st = this.captionState
      if (this.captionBusy || st === null || st === 'transcribing') return
      if (st === 'ready') {
        this.toggleCaptions()
        return
      }
      this.captionBusy = true
      try {
        // 确认框挂在工作台层，播放器全屏时会被全屏元素整个盖住、等不到回答
        if (this.fullscreen) {
          try { await document.exitFullscreen() } catch (e) { /* 已不在全屏 */ }
        }
        const ok = await confirmPaidTranscription({
          projectId: this.effectiveProjectId,
          audioFileId: this.fileId,
          durationMs: this.durationSec > 0 ? Math.round(this.durationSec * 1000) : undefined,
        })
        if (!ok || this._destroyed) return
        if (st === 'none' || !this.meeting) {
          const res = await registerMeetingFromFile(this.effectiveProjectId, this.fileId)
          if (this._destroyed) return
          this._meetingSeq++
          this.applyMeeting(res && res.meeting)
          if (res && res.configured === false) this.toast(this.$t('files.captions.notConfigured'))
          else if (res && res.submitted) this.toast(this.$t('files.captions.submitted'))
          else if (this.captionState === 'pending') this.toast(this.$t('files.captions.noticePending'))
        } else {
          const m = await transcribeMeetingRecording(this.meeting.id)
          if (this._destroyed) return
          this._meetingSeq++
          this.applyMeeting(m)
          if (this.captionState === 'transcribing') this.toast(this.$t('files.captions.submitted'))
        }
      } catch (e) {
        if (this._destroyed) return
        console.warn('[MediaPlayer] 字幕生成发起失败：', e && e.message)
        // 后端 4xx 的文案（含录音告知未确认、凭证未配）原样给用户
        this.toast((e && e.message) || this.$t('files.captions.submitFailed'))
      } finally {
        this.captionBusy = false
      }
    },

    // ==================== 逐字稿 ====================
    // 点行只跳时间，不改变播放/暂停（规格 3.4）
    onTranscriptSeek(sec) {
      this.seek(sec)
      this.onFollow()
    },
    onUnfollow() {
      this.following = false
      clearTimeout(this._followTimer)
      this._followTimer = setTimeout(() => { this.following = true }, FOLLOW_RESUME_MS)
    },
    onFollow() {
      clearTimeout(this._followTimer)
      this.following = true
    },
  },
}
</script>

<style scoped>
.mp {
  position: relative;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  outline: none;
}

/* 焦点环画在最上层：视频画面与控制条都是定位元素，普通 outline 会被它们盖住 */
.mp::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 10;
  pointer-events: none;
}

/* 焦点环只给视频（overlay 皮）画在整个播放器外框上；音频（card 皮）的根元素是整片预览区，
   外框一圈描边会把大片空白框起来，改落在卡片本身（见 .mp--card:focus-visible .mp-card） */
.mp--video:focus-visible::after {
  box-shadow: inset 0 0 0 2px var(--awd-bamboo);
}

.mp--card:focus-visible {
  outline: none;
}

/* ==================== 视频 ==================== */
.mp--video {
  background: #0E0D0B;
}

.mp-main {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
}

.mp-main.is-side {
  flex-direction: row;
}

.mp-stage {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  /* 暖近黑，与深色模式暖墨调同源，不用纯黑 */
  background: #0E0D0B;
  container-type: inline-size;
}

.mp-surface {
  position: absolute;
  inset: 0;
}

.mp-surface :deep(.mp-video) {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: transparent;
}

.mp-hit {
  position: absolute;
  inset: 0;
  z-index: 1;
  cursor: pointer;
}

.mp.is-idle .mp-hit {
  cursor: none;
}

.mp-badge {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  width: 56px;
  height: 56px;
  margin: -28px 0 0 -28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(10, 9, 7, 0.55);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.12);
  color: #FFFFFF;
  pointer-events: none;
}

.mp-badge svg {
  width: 24px;
  height: 24px;
  /* 三角形的视觉重心偏左，往右挪一点才像居中 */
  margin-left: 3px;
}

.mp-spinner {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  width: 36px;
  height: 36px;
  margin: -18px 0 0 -18px;
  box-sizing: border-box;
  border-radius: 50%;
  border: 3px solid rgba(255, 255, 255, 0.18);
  border-top-color: rgba(255, 255, 255, 0.85);
  animation: mp-spin 0.9s linear infinite;
  pointer-events: none;
}

.mp-stage-msg {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 2;
  max-width: 80%;
  transform: translate(-50%, -50%);
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  line-height: 1.5;
  text-align: center;
  pointer-events: none;
}

.mp-stage-msg-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.mp-caption-layer {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 16px;
  z-index: 2;
  display: flex;
  justify-content: center;
  pointer-events: none;
  transition: bottom 0.2s ease;
}

.mp-caption-layer.is-raised {
  bottom: 60px;
}

.mp-caption {
  max-width: 80%;
  padding: 4px 10px;
  border-radius: 4px;
  background: rgba(10, 9, 7, 0.72);
  color: #FFFFFF;
  font-size: clamp(13px, 2.2cqw, 22px);
  line-height: 1.4;
  text-align: center;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.mp-caption-speaker {
  color: #89A8A0;
  font-weight: 600;
}

.mp-scrim {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  height: 96px;
  background: linear-gradient(to top, rgba(10, 9, 7, 0.78), rgba(10, 9, 7, 0));
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.mp-controls {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 4;
  transition: opacity 0.2s ease;
}

.mp-scrim.is-hidden,
.mp-controls.is-hidden {
  opacity: 0;
}

.mp-controls.is-hidden {
  pointer-events: none;
}

.mp-main.is-side .mp-transcript {
  width: 300px;
  flex-shrink: 0;
  height: auto;
  border-left: 1px solid var(--awd-border);
}

.mp-main.is-below .mp-transcript {
  height: 40%;
  flex-shrink: 0;
  border-top: 1px solid var(--awd-border);
}

/* ==================== EvidenceLink 时间标记 ==================== */
.mp-mark {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px 6px 10px;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-left: 3px solid var(--awd-gold-line);
  border-radius: 6px;
  box-shadow: var(--awd-shadow-md);
  font-size: 12px;
}

.mp-mark.is-inline {
  position: static;
  margin-top: 14px;
  box-shadow: none;
  align-self: flex-start;
}

.mp-mark-label {
  color: var(--awd-gold-text);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.mp-mark-btn {
  padding: 3px 10px;
  border: 1px solid var(--awd-border-strong);
  border-radius: 5px;
  color: var(--awd-text);
  white-space: nowrap;
  cursor: pointer;
  outline: none;
}

.mp-mark-btn:hover {
  background: var(--awd-surface-2);
}

.mp-mark-close {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  font-size: 16px;
  line-height: 1;
  color: var(--awd-text-3);
  cursor: pointer;
  outline: none;
}

.mp-mark-close:hover {
  color: var(--awd-text);
  background: var(--awd-surface-2);
}

.mp-mark-btn:focus-visible,
.mp-mark-close:focus-visible {
  box-shadow: 0 0 0 2px var(--awd-bamboo);
}

/* ==================== 音频卡片 ==================== */
.mp--audio {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--awd-bg);
}

.mp--audio.has-transcript {
  justify-content: flex-start;
}

.mp-card {
  width: 100%;
  max-width: 460px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding: 22px 24px 20px;
  box-sizing: border-box;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 12px;
  box-shadow: var(--awd-shadow-md);
}

/* 键盘焦点在播放器根元素上时，环画在卡片上（跟卡片圆角走，保留原阴影） */
.mp--card:focus-visible .mp-card {
  box-shadow: 0 0 0 2px var(--awd-bamboo), var(--awd-shadow-md);
}

.mp-card.is-wide {
  max-width: 640px;
}

.mp-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.mp-card-icon {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  color: var(--awd-accent-text);
}

.mp-card-name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mp-card-msg {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--awd-text-2);
}

.mp-card-msg.is-error {
  color: var(--awd-danger-text);
}

.mp-transcript--audio {
  width: 100%;
  max-width: 640px;
  flex: 1;
  min-height: 0;
  height: auto;
  margin-top: 12px;
  border: 1px solid var(--awd-border);
  border-radius: 12px;
  overflow: hidden;
}

@keyframes mp-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .mp-caption-layer,
  .mp-scrim,
  .mp-controls {
    transition: none;
  }
  .mp-spinner {
    animation-duration: 2.4s;
  }
}
</style>
