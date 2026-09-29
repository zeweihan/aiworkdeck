<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  播放器控制条（dev-board#1023，规格 3.1-3.3）。纯展示 + emit，不碰媒体元素。

  两套皮一个组件：根 class `mp--overlay`（视频，压在画面上，字面量，不随主题翻转）/
  `mp--card`（音频，普通内容面，全部 --awd-* 令牌）。内部只消费 --mp-* 局部变量，
  深浅色靠令牌自己成立——这里没有、也不许写 data-theme 分支。

  模板里一律用原生 div/span 而不是 uni 的 view/text：uni 会把 view 上的键盘/鼠标事件
  重建成普通对象（target 不是真节点、shiftKey 等丢失），滑轨拖拽与键盘操作都靠真事件。
-->
<template>
  <div
    class="mpc"
    :class="['mp--' + variant, 'mpc--' + kind, { 'is-dragging': dragging, 'has-pop': rateOpen }]"
  >
    <!-- 进度轨：视觉细、命中区高；已缓冲段逐段画；EvidenceLink 定位刻度 -->
    <div
      ref="rail"
      class="mpc-rail"
      role="slider"
      tabindex="0"
      :aria-label="$t('files.player.progress')"
      aria-valuemin="0"
      :aria-valuemax="ariaMax"
      :aria-valuenow="ariaNow"
      :aria-valuetext="ariaText"
      @mousedown="onRailDown"
      @mousemove="onRailHover"
      @mouseleave="hoverPct = null"
    >
      <div class="mpc-rail-line">
        <div v-for="(seg, i) in bufferedSegs" :key="'b' + i" class="mpc-rail-buffered" :style="seg"></div>
        <div class="mpc-rail-fill" :style="{ width: playedPct + '%' }"></div>
      </div>
      <div v-if="markPct !== null" class="mpc-rail-mark" :style="{ left: markPct + '%' }"></div>
      <div class="mpc-rail-knob" :style="{ left: playedPct + '%' }"></div>
      <div v-if="hoverPct !== null && durationSec > 0" class="mpc-rail-tip" :style="{ left: hoverPct + '%' }">{{ clock((hoverPct / 100) * durationSec) }}</div>
    </div>

    <div class="mpc-bar">
      <div
        class="mpc-btn mpc-play"
        role="button"
        tabindex="0"
        :aria-label="playing ? $t('files.player.pause') : $t('files.player.play')"
        :title="(playing ? $t('files.player.pause') : $t('files.player.play')) + ' (K)'"
        @click="$emit('toggle-play')"
        @keydown.enter.space.prevent.stop="$emit('toggle-play')"
      >
        <svg class="mpc-icon mpc-play-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            v-for="(d, gi) in (playing ? ICONS.pause : ICONS.play)"
            :key="gi"
            :d="d"
            :fill="playing ? 'none' : 'currentColor'"
            stroke="currentColor"
            stroke-width="2.2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </div>

      <div class="mpc-time">
        <span class="mpc-time-cur">{{ clock(displaySec) }}</span>
        <span class="mpc-time-dur"> / {{ durationText }}</span>
      </div>

      <div class="mpc-spacer"></div>

      <!-- CC：五态（规格 3.6）。点击动作由父组件按状态分派，这里只 emit -->
      <div
        v-if="captionState"
        class="mpc-btn mpc-cc"
        :class="['is-' + captionState, { 'is-on': ccOn }]"
        role="button"
        tabindex="0"
        :aria-label="ccLabel"
        :aria-pressed="captionState === 'ready' ? (captionsOn ? 'true' : 'false') : null"
        :title="ccTitle"
        @click="$emit('caption-click')"
        @keydown.enter.space.prevent.stop="$emit('caption-click')"
      >
        <svg class="mpc-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in (captionState === 'empty' ? ICONS.captionsOff : ICONS.captions)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span v-if="captionState === 'none' || captionState === 'pending'" class="mpc-cc-plus">
          <svg viewBox="0 0 10 10" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M5 2.6v4.8M2.6 5h4.8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" /></svg>
        </span>
        <template v-if="captionState === 'transcribing'">
          <svg class="mpc-cc-spin" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle class="mpc-cc-spin-track" cx="8" cy="8" r="6" stroke-width="2" />
            <path class="mpc-cc-spin-arc" d="M8 2a6 6 0 0 1 6 6" stroke-width="2" stroke-linecap="round" />
          </svg>
          <span class="mpc-cc-dots"><i></i><i></i><i></i></span>
        </template>
        <span v-if="captionState === 'failed'" class="mpc-cc-alert"></span>
      </div>

      <!-- 逐字稿开关：只在字幕就绪、且是视频时出现（音频的 CC 就是逐字稿开关） -->
      <div
        v-if="kind === 'video' && captionState === 'ready'"
        class="mpc-btn mpc-transcript"
        :class="{ 'is-active': transcriptOpen }"
        role="button"
        tabindex="0"
        :aria-label="transcriptOpen ? $t('files.player.hideTranscript') : $t('files.player.showTranscript')"
        :aria-pressed="transcriptOpen ? 'true' : 'false'"
        :title="transcriptOpen ? $t('files.player.hideTranscript') : $t('files.player.showTranscript')"
        @click="$emit('toggle-transcript')"
        @keydown.enter.space.prevent.stop="$emit('toggle-transcript')"
      >
        <svg class="mpc-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in ICONS.transcript" :key="gi" :d="d" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>

      <!-- 倍速：按钮显示当前值，向上弹菜单，当前项打勾 -->
      <div ref="rateWrap" class="mpc-rate">
        <div
          ref="rateBtn"
          class="mpc-btn mpc-rate-btn"
          :class="{ 'is-active': rateOpen }"
          role="button"
          tabindex="0"
          aria-haspopup="menu"
          :aria-expanded="rateOpen ? 'true' : 'false'"
          :aria-label="$t('files.player.speed') + ' ' + rateText(rate) + 'x'"
          :title="$t('files.player.speed')"
          @click="toggleRate(false)"
          @keydown.enter.space.prevent.stop="toggleRate(true)"
        >
          <span>{{ rateText(rate) }}x</span>
        </div>
        <div v-if="rateOpen" ref="rateMenu" class="mpc-pop mpc-rate-menu" role="menu" :aria-label="$t('files.player.speed')" @keydown="onMenuKey">
          <div class="mpc-pop-title">{{ $t('files.player.speed') }}</div>
          <div
            v-for="r in rateSteps"
            :key="r"
            class="mpc-pop-item"
            :class="{ 'is-current': isCurrentRate(r) }"
            role="menuitemradio"
            tabindex="-1"
            :aria-checked="isCurrentRate(r) ? 'true' : 'false'"
            @click="pickRate(r)"
          >
            <svg class="mpc-pop-check" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <template v-if="isCurrentRate(r)">
                <path v-for="(d, gi) in ICONS.check" :key="gi" :d="d" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
              </template>
            </svg>
            <span>{{ rateText(r) }}</span>
          </div>
        </div>
      </div>

      <!-- 音量：按钮切静音；滑条 overlay 皮悬停/聚焦时展开，card 皮常显 -->
      <div class="mpc-vol">
        <div
          class="mpc-btn mpc-vol-btn"
          role="button"
          tabindex="0"
          :aria-label="muted ? $t('files.player.unmute') : $t('files.player.mute')"
          :title="(muted ? $t('files.player.unmute') : $t('files.player.mute')) + ' (M)'"
          @click="$emit('toggle-mute')"
          @keydown.enter.space.prevent.stop="$emit('toggle-mute')"
        >
          <svg class="mpc-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path v-for="(d, gi) in volumeIcon" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <div class="mpc-vol-slot">
          <div
            ref="volTrack"
            class="mpc-vol-track"
            role="slider"
            tabindex="0"
            :aria-label="$t('files.player.volume')"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="volumePct"
            :aria-valuetext="volumePct + '%'"
            @mousedown="onVolDown"
            @keydown="onVolKey"
          >
            <div class="mpc-vol-line">
              <div class="mpc-vol-fill" :style="{ width: volumePct + '%' }"></div>
            </div>
            <div class="mpc-vol-knob" :style="{ left: volumePct + '%' }"></div>
          </div>
        </div>
      </div>

      <div
        v-if="kind === 'video' && pipAvailable"
        class="mpc-btn mpc-pip"
        :class="{ 'is-active': pipActive }"
        role="button"
        tabindex="0"
        :aria-label="pipActive ? $t('files.player.exitPip') : $t('files.player.pip')"
        :title="pipActive ? $t('files.player.exitPip') : $t('files.player.pip')"
        @click="$emit('toggle-pip')"
        @keydown.enter.space.prevent.stop="$emit('toggle-pip')"
      >
        <svg class="mpc-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in ICONS.pip" :key="gi" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>

      <div
        v-if="kind === 'video'"
        class="mpc-btn mpc-fullscreen"
        role="button"
        tabindex="0"
        :aria-label="fullscreen ? $t('files.player.exitFullscreen') : $t('files.player.fullscreen')"
        :title="(fullscreen ? $t('files.player.exitFullscreen') : $t('files.player.fullscreen')) + ' (F)'"
        @click="$emit('toggle-fullscreen')"
        @keydown.enter.space.prevent.stop="$emit('toggle-fullscreen')"
      >
        <svg class="mpc-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path v-for="(d, gi) in (fullscreen ? ICONS.fullscreenExit : ICONS.fullscreen)" :key="gi" :d="d" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>
    </div>
  </div>
</template>

<script>
import { ICONS } from '@/config/icons.js'
import { RATE_STEPS } from '@/utils/media/mediaShortcuts.js'

function clamp01(v) {
  return Math.min(1, Math.max(0, v))
}

export default {
  name: 'MediaControls',
  props: {
    variant: { type: String, default: 'overlay' }, // 'overlay' | 'card'
    kind: { type: String, default: 'video' }, // 'video' | 'audio'
    playing: { type: Boolean, default: false },
    currentSec: { type: Number, default: 0 },
    durationSec: { type: Number, default: 0 },
    buffered: { type: Array, default: () => [] }, // [[startSec, endSec], ...]
    volume: { type: Number, default: 1 },
    muted: { type: Boolean, default: false },
    rate: { type: Number, default: 1 },
    // null = 不渲染 CC（拿不到项目时无从登记转写）；否则 none/pending/transcribing/ready/empty/failed
    captionState: { type: String, default: null },
    captionsOn: { type: Boolean, default: false },
    // failed 态 tooltip 用的错误原文（规格 3.6：前 60 字 + 点击重试）
    captionError: { type: String, default: '' },
    transcriptOpen: { type: Boolean, default: false },
    pipAvailable: { type: Boolean, default: false },
    pipActive: { type: Boolean, default: false },
    fullscreen: { type: Boolean, default: false },
    markSec: { type: Number, default: null },
  },
  emits: [
    'toggle-play', 'seek', 'volume', 'toggle-mute', 'rate', 'caption-click',
    'toggle-transcript', 'toggle-pip', 'toggle-fullscreen', 'popover',
  ],
  data() {
    return {
      dragging: false,
      dragSec: 0,
      hoverPct: null,
      rateOpen: false,
    }
  },
  computed: {
    ICONS() { return ICONS },
    rateSteps() { return RATE_STEPS },
    displaySec() {
      return this.dragging ? this.dragSec : this.currentSec
    },
    playedPct() {
      if (!(this.durationSec > 0)) return 0
      return clamp01(this.displaySec / this.durationSec) * 100
    },
    durationText() {
      return this.durationSec > 0 ? this.clock(this.durationSec) : '--:--'
    },
    bufferedSegs() {
      const d = this.durationSec
      if (!(d > 0) || !Array.isArray(this.buffered)) return []
      const out = []
      for (const r of this.buffered) {
        if (!Array.isArray(r) || r.length < 2) continue
        const s = clamp01(r[0] / d)
        const e = clamp01(r[1] / d)
        if (e > s) out.push({ left: s * 100 + '%', width: (e - s) * 100 + '%' })
      }
      return out
    },
    markPct() {
      const sec = this.markSec
      if (sec === null || sec === undefined || !(this.durationSec > 0)) return null
      if (sec < 0 || sec > this.durationSec) return null
      return (sec / this.durationSec) * 100
    },
    ariaMax() { return Math.round(this.durationSec > 0 ? this.durationSec : 0) },
    ariaNow() { return Math.round(this.displaySec || 0) },
    ariaText() { return this.clock(this.displaySec) + ' / ' + this.durationText },
    volumePct() {
      return this.muted ? 0 : Math.round(clamp01(this.volume) * 100)
    },
    volumeIcon() {
      if (this.muted || this.volume <= 0) return ICONS.volumeMute
      return this.volume < 0.5 ? ICONS.volumeLow : ICONS.volumeHigh
    },
    ccOn() {
      return this.captionState === 'ready' && this.captionsOn
    },
    ccLabel() {
      switch (this.captionState) {
        case 'transcribing': return this.$t('files.captions.generating')
        case 'ready':
          if (this.kind === 'audio') {
            return this.captionsOn ? this.$t('files.player.hideTranscript') : this.$t('files.player.showTranscript')
          }
          return this.captionsOn ? this.$t('files.captions.turnOff') : this.$t('files.captions.turnOn')
        case 'empty': return this.$t('files.captions.emptyRetry')
        case 'failed': {
          const raw = String(this.captionError || '').trim() || this.$t('files.captions.failedDefault')
          const cut = raw.length > 60 ? raw.slice(0, 60) + '…' : raw
          return this.$t('files.captions.failedRetry', { error: cut })
        }
        default: return this.$t('files.captions.generate')
      }
    },
    ccTitle() {
      return this.captionState === 'ready' ? this.ccLabel + ' (C)' : this.ccLabel
    },
  },
  watch: {
    rateOpen(open) {
      if (open) {
        document.addEventListener('mousedown', this.onDocDown, true)
      } else {
        document.removeEventListener('mousedown', this.onDocDown, true)
      }
    },
  },
  beforeUnmount() {
    this.stopDrag()
    if (typeof document !== 'undefined') document.removeEventListener('mousedown', this.onDocDown, true)
  },
  methods: {
    clock(sec) {
      const s = Math.max(0, Math.floor(Number(sec) || 0))
      const h = Math.floor(s / 3600)
      const m = Math.floor((s % 3600) / 60)
      const ss = String(s % 60).padStart(2, '0')
      return h > 0 ? h + ':' + String(m).padStart(2, '0') + ':' + ss : m + ':' + ss
    },
    rateText(r) {
      const n = Number(r) || 1
      return Number.isInteger(n) ? n.toFixed(1) : String(n)
    },
    isCurrentRate(r) {
      return Math.abs(r - this.rate) < 1e-6
    },
    ratioAt(el, e) {
      const rect = el.getBoundingClientRect()
      if (!rect.width) return 0
      return clamp01((e.clientX - rect.left) / rect.width)
    },

    // ---- 进度轨：按下即跳、按住可拖。监听挂 window，拖出轨道也收得到 mouseup ----
    onRailHover(e) {
      if (!(this.durationSec > 0)) return
      this.hoverPct = this.ratioAt(this.$refs.rail, e) * 100
    },
    onRailDown(e) {
      if (e.button !== 0 || !(this.durationSec > 0)) return
      e.preventDefault()
      const el = this.$refs.rail
      const apply = (ev) => {
        const sec = this.ratioAt(el, ev) * this.durationSec
        this.dragSec = sec
        this.hoverPct = (sec / this.durationSec) * 100
        this.$emit('seek', sec)
      }
      this.dragging = true
      apply(e)
      this.startDrag(apply)
    },
    onVolDown(e) {
      if (e.button !== 0) return
      e.preventDefault()
      const el = this.$refs.volTrack
      const apply = (ev) => this.$emit('volume', this.ratioAt(el, ev))
      apply(e)
      this.startDrag(apply)
    },
    startDrag(apply) {
      this.stopDrag()
      this._dragMove = (ev) => apply(ev)
      this._dragUp = () => {
        this.stopDrag()
        this.dragging = false
      }
      window.addEventListener('mousemove', this._dragMove)
      window.addEventListener('mouseup', this._dragUp)
    },
    stopDrag() {
      if (typeof window === 'undefined') return
      if (this._dragMove) window.removeEventListener('mousemove', this._dragMove)
      if (this._dragUp) window.removeEventListener('mouseup', this._dragUp)
      this._dragMove = null
      this._dragUp = null
    },
    // 音量滑条自己吃方向键（根元素上方向键是 seek / 音量，这里拦下免得两边都动）
    onVolKey(e) {
      const step = { ArrowRight: 0.05, ArrowUp: 0.05, ArrowLeft: -0.05, ArrowDown: -0.05 }[e.key]
      if (step === undefined) return
      e.preventDefault()
      e.stopPropagation()
      const base = this.muted ? 0 : this.volume
      this.$emit('volume', clamp01(Math.round((base + step) * 100) / 100))
    },

    // ---- 倍速菜单 ----
    toggleRate(fromKeyboard) {
      if (this.rateOpen) {
        this.closePopover()
        return
      }
      this.rateOpen = true
      this.$emit('popover', true)
      if (fromKeyboard) {
        this.$nextTick(() => {
          const menu = this.$refs.rateMenu
          const cur = menu && menu.querySelector('.is-current')
          if (cur) cur.focus()
        })
      }
    },
    pickRate(r) {
      this.$emit('rate', r)
      this.closePopover()
    },
    /** 父组件在 Esc 快捷键时调用；自身点外部 / 选中后也走这里 */
    closePopover(returnFocus) {
      if (!this.rateOpen) return false
      this.rateOpen = false
      this.$emit('popover', false)
      if (returnFocus && this.$refs.rateBtn) this.$refs.rateBtn.focus()
      return true
    },
    onDocDown(e) {
      const wrap = this.$refs.rateWrap
      if (wrap && wrap.contains(e.target)) return
      this.closePopover()
    },
    onMenuKey(e) {
      const menu = this.$refs.rateMenu
      if (!menu) return
      const items = Array.from(menu.querySelectorAll('.mpc-pop-item'))
      const at = items.indexOf(document.activeElement)
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault()
        e.stopPropagation()
        const next = e.key === 'ArrowDown'
          ? (at < 0 ? 0 : Math.min(items.length - 1, at + 1))
          : (at < 0 ? items.length - 1 : Math.max(0, at - 1))
        if (items[next]) items[next].focus()
      } else if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        e.stopPropagation()
        if (at >= 0) this.pickRate(this.rateSteps[at])
        this.$nextTick(() => this.$refs.rateBtn && this.$refs.rateBtn.focus())
      } else if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        this.closePopover(true)
      } else if (e.key === 'Tab') {
        this.closePopover()
      }
    },
  },
}
</script>

<style scoped>
/* ---- 皮肤变量（规格 3.1）----
   overlay 压在视频画面上，底图不参与主题，所以是字面量；card 是普通内容面，全走令牌。
   在规格表之外补的几项（on-fill / focus / pop-hover / pop-border / pop-fg-2 / active-*）
   同样分两套，组件内部只消费 --mp-*。 */
.mp--overlay {
  --mp-fg: rgba(255, 255, 255, 0.92);
  --mp-fg-2: rgba(255, 255, 255, 0.64);
  --mp-hover-bg: rgba(255, 255, 255, 0.12);
  --mp-rail: rgba(255, 255, 255, 0.28);
  --mp-buffered: rgba(255, 255, 255, 0.45);
  --mp-fill: #89A8A0;
  --mp-knob: #FFFFFF;
  --mp-mark: #D7C5A1;
  --mp-pop-bg: rgba(28, 26, 22, 0.96);
  --mp-pop-fg: rgba(255, 255, 255, 0.92);
  /* 竹月青实底上压深墨字：白字只有 2.6:1，看不清「字幕已开」 */
  --mp-on-fill: #14211D;
  --mp-focus: #89A8A0;
  --mp-pop-hover: rgba(255, 255, 255, 0.10);
  --mp-pop-border: rgba(255, 255, 255, 0.08);
  --mp-pop-fg-2: rgba(255, 255, 255, 0.5);
  --mp-active-bg: rgba(137, 168, 160, 0.24);
  --mp-active-fg: #B9D0C9;
}

.mp--card {
  --mp-fg: var(--awd-text);
  /* 规格表写的是 text-3；时间读数要承载信息，text-3 在玉脂白上只有 2.4:1，取 text-2 */
  --mp-fg-2: var(--awd-text-2);
  --mp-hover-bg: var(--awd-surface-2);
  --mp-rail: var(--awd-surface-3);
  --mp-buffered: var(--awd-border-strong);
  --mp-fill: var(--awd-accent);
  --mp-knob: var(--awd-accent);
  --mp-mark: var(--awd-gold-line);
  --mp-pop-bg: var(--awd-surface);
  --mp-pop-fg: var(--awd-text);
  --mp-on-fill: var(--awd-text-on-accent);
  --mp-focus: var(--awd-bamboo);
  --mp-pop-hover: var(--awd-surface-2);
  --mp-pop-border: var(--awd-border);
  --mp-pop-fg-2: var(--awd-text-3);
  --mp-active-bg: var(--awd-accent-soft);
  --mp-active-fg: var(--awd-accent-text);
}

.mpc {
  position: relative;
  display: flex;
  flex-direction: column;
  color: var(--mp-fg);
  user-select: none;
  -webkit-user-select: none;
}

/* ---- 进度轨 ---- */
.mpc-rail {
  position: relative;
  height: 18px;
  cursor: pointer;
  outline: none;
}

.mp--overlay .mpc-rail {
  margin: 0 12px;
}

.mpc-rail-line {
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  height: 3px;
  transform: translateY(-50%);
  border-radius: 999px;
  background: var(--mp-rail);
  overflow: hidden;
  transition: height 0.12s ease;
}

.mpc-rail-buffered,
.mpc-rail-fill {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
}

.mpc-rail-buffered {
  background: var(--mp-buffered);
}

.mpc-rail-fill {
  background: var(--mp-fill);
}

.mpc-rail-knob {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  margin-left: -6px;
  border-radius: 50%;
  background: var(--mp-knob);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  transform: translateY(-50%) scale(0);
  transition: transform 0.12s ease;
  pointer-events: none;
}

.mpc-rail:hover .mpc-rail-line,
.is-dragging .mpc-rail-line,
.mpc-rail:focus-visible .mpc-rail-line {
  height: 5px;
}

.mpc-rail:hover .mpc-rail-knob,
.is-dragging .mpc-rail-knob,
.mpc-rail:focus-visible .mpc-rail-knob {
  transform: translateY(-50%) scale(1);
}

.mpc-rail:focus-visible {
  box-shadow: 0 0 0 2px var(--mp-focus);
  border-radius: 4px;
}

/* EvidenceLink 定位刻度：竖线 + 顶端菱形，不吃鼠标（点它要落到轨道上 seek） */
.mpc-rail-mark {
  position: absolute;
  top: 3px;
  bottom: 3px;
  width: 2px;
  margin-left: -1px;
  background: var(--mp-mark);
  pointer-events: none;
}

.mpc-rail-mark::before {
  content: '';
  position: absolute;
  top: -3px;
  left: -2px;
  width: 6px;
  height: 6px;
  background: var(--mp-mark);
  transform: rotate(45deg);
}

.mpc-rail-tip {
  position: absolute;
  bottom: 100%;
  margin-bottom: 4px;
  padding: 3px 6px;
  transform: translateX(-50%);
  border-radius: 4px;
  background: var(--mp-pop-bg);
  color: var(--mp-pop-fg);
  border: 1px solid var(--mp-pop-border);
  font-size: 12px;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: var(--awd-shadow-md);
}

/* ---- 控制行 ---- */
.mpc-bar {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 44px;
  padding: 0 12px;
  box-sizing: border-box;
}

.mpc-spacer {
  flex: 1;
  min-width: 8px;
}

.mpc-btn {
  position: relative;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: var(--mp-fg);
  cursor: pointer;
  outline: none;
  transition: background-color 0.12s ease, color 0.12s ease;
}

.mpc-btn:hover {
  background: var(--mp-hover-bg);
}

.mpc-btn:focus-visible {
  box-shadow: 0 0 0 2px var(--mp-focus);
}

.mpc-btn.is-active {
  background: var(--mp-active-bg);
  color: var(--mp-active-fg);
}

.mpc-icon {
  width: 20px;
  height: 20px;
  display: block;
}

.mpc-time {
  margin-left: 6px;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.mpc-time-cur {
  color: var(--mp-fg);
}

.mpc-time-dur {
  color: var(--mp-fg-2);
}

/* ---- CC 五态 ---- */
.mpc-cc.is-on {
  background: var(--mp-fill);
  color: var(--mp-on-fill);
}

.mpc-cc.is-on:hover {
  background: var(--mp-fill);
  filter: brightness(1.08);
}

.mpc-cc-plus {
  position: absolute;
  top: 3px;
  right: 2px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--mp-fill);
  color: var(--mp-on-fill);
  display: flex;
  align-items: center;
  justify-content: center;
}

.mpc-cc-plus svg {
  width: 9px;
  height: 9px;
  display: block;
}

.mpc-cc-spin {
  position: absolute;
  right: 1px;
  bottom: 1px;
  width: 13px;
  height: 13px;
  animation: mpc-spin 1.2s linear infinite;
}

.mpc-cc-spin-track {
  stroke: var(--mp-rail);
}

.mpc-cc-spin-arc {
  stroke: var(--mp-fill);
}

/* 三个点只在「减少动态效果」下替代转圈 */
.mpc-cc-dots {
  display: none;
  position: absolute;
  right: 3px;
  bottom: 3px;
  gap: 2px;
}

.mpc-cc-dots i {
  display: block;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--mp-fill);
}

.mpc-cc-alert {
  position: absolute;
  top: 5px;
  right: 4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--awd-danger);
  box-shadow: 0 0 0 1.5px var(--mp-pop-bg);
}

@keyframes mpc-spin {
  to { transform: rotate(360deg); }
}

/* ---- 倍速 ---- */
.mpc-rate {
  position: relative;
}

.mpc-rate-btn {
  width: auto;
  min-width: 40px;
  padding: 0 6px;
  box-sizing: border-box;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.mpc-pop {
  position: absolute;
  right: 0;
  bottom: calc(100% + 8px);
  z-index: 3;
  width: 120px;
  padding: 4px 0;
  border-radius: 8px;
  background: var(--mp-pop-bg);
  color: var(--mp-pop-fg);
  border: 1px solid var(--mp-pop-border);
  box-shadow: var(--awd-shadow-md);
}

.mpc-pop-title {
  padding: 4px 12px 6px;
  font-size: 11px;
  color: var(--mp-pop-fg-2);
}

.mpc-pop-item {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px 0 8px;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  outline: none;
}

.mpc-pop-item:hover,
.mpc-pop-item:focus-visible {
  background: var(--mp-pop-hover);
}

.mpc-pop-item.is-current {
  font-weight: 600;
}

.mpc-pop-check {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  color: var(--mp-fill);
}

.mp--card .mpc-pop-check {
  color: var(--awd-accent-text);
}

/* ---- 音量 ---- */
.mpc-vol {
  display: flex;
  align-items: center;
}

.mpc-vol-slot {
  width: 0;
  overflow: hidden;
  transition: width 0.15s ease;
}

.mpc-vol:hover .mpc-vol-slot,
.mpc-vol:focus-within .mpc-vol-slot {
  width: 84px;
}

.mp--card .mpc-vol-slot {
  width: 84px;
}

.mpc-vol-track {
  position: relative;
  width: 72px;
  height: 18px;
  margin: 0 6px;
  cursor: pointer;
  outline: none;
}

.mpc-vol-line {
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  height: 3px;
  transform: translateY(-50%);
  border-radius: 999px;
  background: var(--mp-rail);
  overflow: hidden;
}

.mpc-vol-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: var(--mp-fill);
}

.mpc-vol-knob {
  position: absolute;
  top: 50%;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  border-radius: 50%;
  background: var(--mp-knob);
  transform: translateY(-50%);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  pointer-events: none;
}

.mpc-vol-track:focus-visible {
  box-shadow: 0 0 0 2px var(--mp-focus);
  border-radius: 4px;
}

/* ---- card 皮（音频卡片）的差异：轨道 4px 常显旋钮，播放键是 40px 实心圆钮 ---- */
.mp--card .mpc-rail {
  height: 16px;
}

.mp--card .mpc-rail-line {
  height: 4px;
}

.mp--card .mpc-rail:hover .mpc-rail-line,
.mp--card.is-dragging .mpc-rail-line {
  height: 4px;
}

.mp--card .mpc-rail-knob {
  width: 10px;
  height: 10px;
  margin-left: -5px;
  transform: translateY(-50%) scale(1);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
}

.mp--card .mpc-rail-mark {
  top: 2px;
  bottom: 2px;
}

.mp--card .mpc-bar {
  height: auto;
  margin-top: 12px;
  padding: 0;
  gap: 4px;
}

.mp--card .mpc-play {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
}

.mp--card .mpc-play:hover {
  background: var(--awd-accent-hover);
}

.mp--card .mpc-play-glyph {
  width: 18px;
  height: 18px;
}

.mp--card .mpc-time {
  margin-left: 10px;
}

/* 音频卡片上方常常没有空间（有逐字稿时卡片贴顶，上方是播放器边界会被裁掉），菜单向下弹 */
.mp--card .mpc-pop {
  top: calc(100% + 6px);
  bottom: auto;
}

.mp--card .mpc-rate-btn {
  border: 1px solid var(--awd-border);
  height: 28px;
}

.mp--card .mpc-rate-btn:hover {
  border-color: var(--awd-border-strong);
}

@media (prefers-reduced-motion: reduce) {
  .mpc-rail-line,
  .mpc-rail-knob,
  .mpc-btn,
  .mpc-vol-slot {
    transition: none;
  }
  .mpc-cc-spin {
    display: none;
  }
  .mpc-cc-dots {
    display: flex;
  }
}
</style>
