<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  逐字稿面板（dev-board#1024，规格 3.4）。内容面，全部 --awd-* 令牌，深浅色自动成立。

  输入是 cue（长句已按 3.5 切成子 cue）；列表按原段（segIndex）合回一行，
  当前行 = 含当前 cue 的那一段。跟随由父组件持有：用户手动滚动时本组件 emit
  unfollow，父组件 4s 后自动恢复；「回到当前」药丸 emit follow 立即恢复。
  滚动只动列表自身的 scrollTop——scrollIntoView 会连带滚动所有可滚祖先
  （含 overflow:hidden 的工作台容器），整个界面会被推歪。
-->
<template>
  <div class="mpt">
    <div class="mpt-head">
      <span class="mpt-summary">{{ $t('files.player.summary', { count: rows.length, duration: clock(totalSec) }) }}</span>
      <div v-if="speakerCount >= 2" class="mpt-legend">
        <span v-for="sp in legend" :key="sp" class="mpt-legend-item">
          <i class="mpt-legend-dot" :style="{ background: speakerColor(sp) }"></i>
          <span class="mpt-legend-name">{{ labelOf(sp) }}</span>
        </span>
      </div>
    </div>

    <div
      ref="list"
      class="mpt-list"
      @wheel.passive="onUserScroll"
      @touchmove.passive="onUserScroll"
      @mousedown="onListMouseDown"
    >
      <div v-if="!rows.length" class="mpt-empty">{{ $t('files.captions.empty') }}</div>
      <div
        v-for="(row, ri) in rows"
        :key="row.segIndex + '-' + row.firstCue"
        class="mpt-row"
        :class="{ 'is-current': ri === currentRow }"
        role="button"
        :tabindex="ri === focusRow ? 0 : -1"
        :data-row="ri"
        :aria-current="ri === currentRow ? 'true' : null"
        @click="$emit('seek', row.start / 1000)"
        @keydown.enter.space.prevent.stop="$emit('seek', row.start / 1000)"
      >
        <div class="mpt-meta">
          <span class="mpt-time">{{ clock(row.start / 1000) }}</span>
          <span v-if="hasSpeaker(row.speaker)" class="mpt-speaker" :style="{ color: speakerColor(row.speaker) }">{{ labelOf(row.speaker) }}</span>
        </div>
        <div class="mpt-text">{{ row.text }}</div>
      </div>
    </div>

    <div
      v-if="!following && currentRow >= 0"
      class="mpt-back"
      role="button"
      tabindex="0"
      :aria-label="$t('files.player.backToCurrent')"
      @click="$emit('follow')"
      @keydown.enter.space.prevent.stop="$emit('follow')"
    >{{ $t('files.player.backToCurrent') }}</div>
  </div>
</template>

<script>
import { speakerColorVar } from '@/utils/media/speakerColors.js'

// 合并同一段切出来的子 cue：CJK 结尾直接接，否则补一个空格（英文切分时 trim 掉了）
function joinText(a, b) {
  if (!a) return b
  const last = a.charCodeAt(a.length - 1)
  return last >= 0x2E80 ? a + b : a + ' ' + b
}

export default {
  name: 'MediaTranscript',
  props: {
    cues: { type: Array, default: () => [] },
    labels: { type: Object, default: () => ({}) },
    speakerCount: { type: Number, default: 0 },
    currentIndex: { type: Number, default: -1 },
    following: { type: Boolean, default: true },
  },
  emits: ['seek', 'follow', 'unfollow'],
  computed: {
    rows() {
      const out = []
      const cues = Array.isArray(this.cues) ? this.cues : []
      for (let i = 0; i < cues.length; i++) {
        const c = cues[i]
        const last = out[out.length - 1]
        if (last && last.segIndex === c.segIndex) {
          last.text = joinText(last.text, c.text)
          last.end = c.end
          last.lastCue = i
        } else {
          out.push({ segIndex: c.segIndex, start: c.start, end: c.end, speaker: c.speaker, text: c.text, firstCue: i, lastCue: i })
        }
      }
      return out
    },
    // 含当前 cue 的那一行：rows 按 firstCue 升序，二分
    currentRow() {
      const idx = this.currentIndex
      const rows = this.rows
      if (!(idx >= 0) || !rows.length) return -1
      let lo = 0
      let hi = rows.length - 1
      while (lo <= hi) {
        const mid = (lo + hi) >> 1
        if (rows[mid].lastCue < idx) lo = mid + 1
        else if (rows[mid].firstCue > idx) hi = mid - 1
        else return mid
      }
      return -1
    },
    // 列表只放一个 Tab 停靠点（当前行，没有就第一行），其余行靠点击
    focusRow() {
      return this.currentRow >= 0 ? this.currentRow : 0
    },
    totalSec() {
      const rows = this.rows
      return rows.length ? rows[rows.length - 1].end / 1000 : 0
    },
    legend() {
      const seen = []
      for (const r of this.rows) {
        if (!this.hasSpeaker(r.speaker)) continue
        const id = String(r.speaker)
        if (!seen.includes(id)) seen.push(id)
      }
      return seen
    },
  },
  watch: {
    currentRow(ri) {
      if (this.following && ri >= 0) this.scrollToRow(ri, true)
    },
    following(on) {
      if (on && this.currentRow >= 0) this.scrollToRow(this.currentRow, true)
    },
  },
  mounted() {
    if (this.currentRow >= 0) this.$nextTick(() => this.scrollToRow(this.currentRow, false))
  },
  methods: {
    clock(sec) {
      const s = Math.max(0, Math.floor(Number(sec) || 0))
      const h = Math.floor(s / 3600)
      const m = Math.floor((s % 3600) / 60)
      const ss = String(s % 60).padStart(2, '0')
      return h > 0 ? h + ':' + String(m).padStart(2, '0') + ':' + ss : m + ':' + ss
    },
    hasSpeaker(sp) {
      return sp !== undefined && sp !== null && sp !== ''
    },
    labelOf(sp) {
      const l = this.labels && this.labels[String(sp)]
      return l || String(sp)
    },
    speakerColor(sp) {
      return speakerColorVar(sp)
    },
    scrollToRow(ri, smooth) {
      const list = this.$refs.list
      if (!list || typeof list.querySelector !== 'function') return
      const row = list.querySelector('[data-row="' + ri + '"]')
      if (!row) return
      const top = Math.max(0, row.offsetTop - (list.clientHeight - row.offsetHeight) / 2)
      let reduce = false
      try {
        reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
      } catch (e) { /* 读不到按不减少处理 */ }
      if (typeof list.scrollTo === 'function') {
        list.scrollTo({ top, behavior: smooth && !reduce ? 'smooth' : 'auto' })
      } else {
        list.scrollTop = top
      }
    },
    // 只认「人的动作」：程序滚动也会触发 scroll 事件，拿 scroll 判断会把跟随自己打断
    onUserScroll() {
      this.$emit('unfollow')
    },
    // 拖列表自身的滚动条：mousedown 落在列表容器本身（不是某一行）
    onListMouseDown(e) {
      if (e.target === this.$refs.list) this.$emit('unfollow')
    },
  },
}
</script>

<style scoped>
.mpt {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  box-sizing: border-box;
  background: var(--awd-surface);
  color: var(--awd-text);
}

.mpt-head {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--awd-border);
}

.mpt-summary {
  font-size: 12px;
  color: var(--awd-text-2);
  font-variant-numeric: tabular-nums;
}

.mpt-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}

.mpt-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--awd-text-2);
}

.mpt-legend-dot {
  display: block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.mpt-list {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 0 44px;
  overscroll-behavior: contain;
}

.mpt-empty {
  padding: 24px 14px;
  font-size: 13px;
  color: var(--awd-text-3);
  text-align: center;
}

.mpt-row {
  position: relative;
  padding: 8px 14px 8px 16px;
  cursor: pointer;
  outline: none;
  transition: background-color 0.12s ease;
}

.mpt-row:hover {
  background: var(--awd-surface-2);
}

.mpt-row:focus-visible {
  box-shadow: inset 0 0 0 2px var(--awd-bamboo);
}

.mpt-row.is-current {
  background: var(--awd-accent-soft);
}

.mpt-row.is-current::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 1px;
  background: var(--awd-accent);
}

.mpt-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 2px;
}

.mpt-time {
  font-size: 11px;
  color: var(--awd-text-3);
  font-variant-numeric: tabular-nums;
}

.mpt-speaker {
  font-size: 11px;
  font-weight: 600;
}

.mpt-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--awd-text);
  word-break: break-word;
  user-select: text;
}

.mpt-back {
  position: absolute;
  left: 50%;
  bottom: 12px;
  transform: translateX(-50%);
  height: 28px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  border-radius: 14px;
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  font-size: 12px;
  white-space: nowrap;
  box-shadow: var(--awd-shadow-sm);
  cursor: pointer;
  outline: none;
}

.mpt-back:hover {
  background: var(--awd-accent-hover);
}

.mpt-back:focus-visible {
  box-shadow: 0 0 0 2px var(--awd-bamboo);
}

@media (prefers-reduced-motion: reduce) {
  .mpt-row {
    transition: none;
  }
}
</style>
