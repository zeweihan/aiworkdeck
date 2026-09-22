<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  钢琴键会话导航（dev-board#791 / 计划 K12）。

  数据一律来自 ChatInterface 已有的 `chatTurns`（chatTurns.mjs 已把每轮提问截成
  turn.label、算好 turn.status），本组件不重算任何一轮的状态，也不自己 scrollTo——
  点击只 emit('jump')，由宿主转调 useChatReadingPosition.navigateToMessage，
  「跳转」和「是否在屏」必须解析同一个元素。

  定位契约：展开层是 `position: absolute`，它的包含块是宿主的 `.message-area`
  （ChatInterface 给那一层加了 position: relative），**不是**本组件根节点——根节点
  只占 12px 宽的一列，百分比宽度要按消息区算才能在窄面板里自动收窄。换宿主时要么
  给新宿主同样加 position: relative，要么改这里的定位。
-->
<template>
  <nav
    v-if="turns.length > 1"
    ref="railRef"
    class="chat-turn-rail"
    :class="{ 'is-open': open }"
    :aria-label="$t('chat.railLabel')"
    tabindex="0"
    @mouseenter="hovering = true"
    @mouseleave="hovering = false"
    @focusin="focused = true"
    @focusout="onFocusOut"
    @keydown="onKeydown"
  >
    <span class="rail-ticks" aria-hidden="true">
      <span v-for="turn in turns" :key="turn.key" class="rail-tick" :class="tickClass(turn)"><i></i></span>
    </span>
    <div v-if="open" ref="panelRef" class="rail-panel">
      <button
        v-for="(turn, i) in turns"
        :key="turn.key"
        type="button"
        class="rail-item"
        :class="[statusClass(turn), { 'is-active': turn.key === activeKey, 'is-cursor': i === cursor }]"
        :data-rail-key="turn.key"
        :aria-current="turn.key === activeKey ? 'true' : undefined"
        :title="itemTitle(turn, i)"
        tabindex="-1"
        @mousedown.prevent
        @click="jump(i)"
      >
        <span class="rail-dot"></span>
        <span class="rail-index">{{ i + 1 }}</span>
        <span class="rail-label">{{ turn.label || $t('chat.railUntitled') }}</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { t } from '@/i18n'

const props = defineProps({
  turns: { type: Array, default: () => [] },
  activeKey: { type: String, default: '' }
})
const emit = defineEmits(['jump'])

const railRef = ref(null)
const panelRef = ref(null)
const hovering = ref(false)
const focused = ref(false)
const cursor = ref(-1)
const open = computed(() => hovering.value || focused.value)

// 一轮的「位置」永远是它第一条用户消息的全局下标——那正是 navigateToMessage 认的
// [data-message-index]。历史里偶尔有开头就是助手的一轮（旧会话），退到第一条助手消息。
const turnIndex = (turn) => (turn?.user ? turn.user.index : turn?.assistants?.[0]?.index ?? -1)

const statusClass = (turn) => {
  const status = turn?.status || 'idle'
  if (status === 'running') return 'is-running'
  if (status === 'queued') return 'is-queued'
  if (status === 'awaiting_input' || status === 'awaiting_approval') return 'is-awaiting'
  return 'is-idle'
}
const tickClass = (turn) => [statusClass(turn), { 'is-active': turn.key === props.activeKey }]
const itemTitle = (turn, i) => `${t('chat.railTurnIndex', { n: i + 1 })} ${turn.label || t('chat.railUntitled')}`

const activeIndex = () => props.turns.findIndex(turn => turn.key === props.activeKey)

const jump = (i) => {
  const turn = props.turns[i]
  const index = turnIndex(turn)
  if (!turn || index < 0) return
  cursor.value = i
  emit('jump', { key: turn.key, index })
}

const moveCursor = (delta) => {
  const from = cursor.value >= 0 ? cursor.value : activeIndex()
  const next = Math.min(props.turns.length - 1, Math.max(0, (from < 0 ? 0 : from + delta)))
  cursor.value = next
  scrollCursorIntoView()
}

// 刻意自己算 scrollTop 而不用 scrollIntoView：后者会把每一层可滚动祖先都带着滚，
// 于是「打开导航列」这个纯查看动作会把用户正在读的消息流一起挪走。
const scrollCursorIntoView = () => nextTick(() => {
  const panel = panelRef.value
  const row = panel?.children?.[cursor.value >= 0 ? cursor.value : 0]
  if (!panel || !row) return
  if (row.offsetTop < panel.scrollTop) panel.scrollTop = Math.max(0, row.offsetTop - 4)
  else if (row.offsetTop + row.offsetHeight > panel.scrollTop + panel.clientHeight) {
    panel.scrollTop = row.offsetTop + row.offsetHeight - panel.clientHeight + 4
  }
})

const onFocusOut = (event) => {
  if (railRef.value && event?.relatedTarget && railRef.value.contains(event.relatedTarget)) return
  focused.value = false
}

const onKeydown = (event) => {
  const key = event.key
  if (key === 'ArrowDown' || key === 'ArrowUp') {
    event.preventDefault()
    focused.value = true
    moveCursor(key === 'ArrowDown' ? 1 : -1)
    return
  }
  if (key === 'Enter' || key === ' ') {
    event.preventDefault()
    jump(cursor.value >= 0 ? cursor.value : Math.max(0, activeIndex()))
    return
  }
  if (key === 'Escape') {
    event.preventDefault()
    hovering.value = false
    focused.value = false
    railRef.value?.blur?.()
  }
}

// 展开时把当前那一轮带到眼前：几十轮以上时列表比面板长，不定位等于每次都要自己找。
watch(open, (isOpen) => {
  if (!isOpen) { cursor.value = -1; return }
  cursor.value = activeIndex()
  scrollCursorIntoView()
})
</script>

<style scoped>
.chat-turn-rail {
  position: static; /* 展开层要按 .message-area 定位，见文件头的定位契约 */
  flex-shrink: 0;
  width: 12px;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  outline: none;
  cursor: pointer;
}
.chat-turn-rail:focus-visible { outline: 1px solid var(--awd-accent); outline-offset: -2px; border-radius: 3px; }

.rail-ticks {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 0;
  /* 刻度按可用高度收缩（200 轮时每格压到 2px）；仍装不下才裁尾，长会话的真正导航
     surface 是展开层，它自己可以滚。 */
  overflow: hidden;
}
.rail-tick {
  flex: 0 1 7px;
  min-height: 2px;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.rail-tick > i {
  display: block;
  width: 8px;
  height: 3px;
  max-height: 70%;
  border-radius: 2px;
  background: var(--awd-text-3);
  transition: background .15s ease, width .15s ease, height .15s ease;
}
/* 这三格不参与压缩：200 轮时其余格被压到 2.7px，「我在哪儿」和「哪一格在等我」就糊成
   一条灰线（实测当前格跟着压时，强调色被反锯齿冲淡近一半）。 */
.rail-tick.is-active,
.rail-tick.is-running,
.rail-tick.is-awaiting { flex: 0 0 auto; height: 6px; min-height: 6px; }
/* 颜色按 turn.status；顺序即优先级：当前轮换成强调色，但「待你回答 / 待审批」压在
   最后——那一格必须一直显眼，哪怕它正好就是当前轮。 */
.rail-tick.is-queued > i { background: var(--awd-gold-line); }
.rail-tick.is-running > i { background: var(--awd-gold); }
.rail-tick.is-active > i { width: 12px; height: 4px; max-height: 100%; background: var(--awd-accent); }
.rail-tick.is-awaiting > i { background: var(--awd-danger); }

.rail-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 200px;
  max-width: calc(100% - 12px); /* 窄面板（<300px）里自动收窄，始终覆盖在消息区上而不挤压它 */
  max-height: 100%;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px;
  box-sizing: border-box;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border-subtle);
  border-radius: 6px;
  box-shadow: var(--awd-shadow-md);
  z-index: 6; /* 盖住 .return-to-latest 的 5 */
}
.rail-panel button::after { border: 0; }
.rail-item {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 5px 7px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  font-size: 12px;
  line-height: 1.5;
  color: var(--awd-text-2);
  cursor: pointer;
}
.rail-item:hover { background: var(--awd-bg); }
.rail-item.is-active { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.rail-item.is-cursor { outline: 1px solid var(--awd-accent); outline-offset: -1px; }
.rail-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--awd-text-3);
}
.rail-item.is-queued .rail-dot { background: var(--awd-gold-line); }
.rail-item.is-running .rail-dot { background: var(--awd-gold); }
.rail-item.is-awaiting .rail-dot { background: var(--awd-danger); }
.rail-index {
  flex-shrink: 0;
  min-width: 16px;
  font-size: 10px;
  color: var(--awd-text-3);
}
.rail-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
