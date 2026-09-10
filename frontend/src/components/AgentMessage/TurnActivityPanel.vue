<!--
SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
SPDX-License-Identifier: AGPL-3.0-or-later
-->
<template>
  <section ref="panelRoot" class="turn-activity" :aria-label="$t('chat.activityPanelTitle')">
    <div class="activity-bar">
      <div
        class="turn-status"
        :class="[`status-${selectedStatus}`, { 'is-pulsing': isStreaming && selectedStatus === 'running' }]"
        role="status"
        :aria-live="isStreaming ? 'polite' : 'off'"
      >
        <span class="status-dot" aria-hidden="true"></span>
        <span class="status-text">{{ $t(statusKey) }}</span>
        <span v-if="selectedTurn" class="status-separator" aria-hidden="true">·</span>
        <span v-if="selectedTurn" class="turn-context">
          <span class="turn-summary">
            {{ $t('chat.activityTurnSummary', { current: selectedTurnIndex + 1, total: turns.length, label: selectedTurn.label }) }}
          </span>
          <span v-if="currentActivityHint" class="activity-hint">
            <span class="status-separator" aria-hidden="true">·</span>
            {{ currentActivityHint }}
          </span>
        </span>
      </div>

      <div class="activity-actions" :class="{ 'has-attention': selectedTurn?.attentionIndex >= 0 }">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          class="activity-trigger"
          :class="{ active: isOpen && activeTab === tab.id }"
          :disabled="!selectedTurn"
          :aria-expanded="isOpen && activeTab === tab.id"
          :aria-controls="panelId"
          @click="togglePanel(tab.id)"
        >{{ tab.label }}</button>
        <button
          v-if="selectedTurn?.attentionIndex >= 0"
          type="button"
          class="activity-trigger attention-trigger"
          @click="navigateTo(selectedTurn.attentionIndex, 'attention')"
        >
          {{ $t('chat.activityJumpToAttention') }}
        </button>
      </div>
    </div>

    <Transition name="activity-pop">
      <div
        v-if="isOpen"
        :id="panelId"
        class="activity-panel"
        role="dialog"
        aria-modal="false"
        :aria-labelledby="panelTitleId"
      >
        <div class="panel-heading">
          <div class="turn-picker">
            <button
              type="button"
              class="icon-button"
              :aria-label="$t('chat.activityPreviousTurn')"
              :disabled="selectedTurnIndex <= 0"
              @click="selectRelativeTurn(-1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <polyline points="15 18 9 12 15 6"></polyline>
              </svg>
            </button>
            <div class="turn-title-wrap">
              <strong :id="panelTitleId" class="panel-title">{{ selectedTurn?.label || $t('chat.activityEmpty') }}</strong>
              <span v-if="selectedTurn" class="turn-counter">
                {{ $t('chat.activityTurnCounter', { current: selectedTurnIndex + 1, total: turns.length }) }}
              </span>
            </div>
            <button
              type="button"
              class="icon-button"
              :aria-label="$t('chat.activityNextTurn')"
              :disabled="selectedTurnIndex < 0 || selectedTurnIndex >= turns.length - 1"
              @click="selectRelativeTurn(1)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <polyline points="9 18 15 12 9 6"></polyline>
              </svg>
            </button>
          </div>
          <button type="button" class="close-button" :aria-label="$t('chat.activityClose')" @click="closePanel">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>

        <div class="activity-tabs" role="tablist">
          <button
            v-for="tab in tabs"
            :id="`${panelId}-${tab.id}-tab`"
            :key="tab.id"
            type="button"
            role="tab"
            :aria-controls="`${panelId}-${tab.id}-panel`"
            :aria-selected="activeTab === tab.id"
            :data-activity-tab="tab.id"
            :tabindex="activeTab === tab.id ? 0 : -1"
            :class="{ active: activeTab === tab.id }"
            @click="activeTab = tab.id"
            @keydown.left.prevent="selectAdjacentTab(tab.id, -1, $event)"
            @keydown.right.prevent="selectAdjacentTab(tab.id, 1, $event)"
          >
            {{ tab.label }}
          </button>
        </div>

        <div
          :id="`${panelId}-${activeTab}-panel`"
          class="panel-body"
          role="tabpanel"
          :aria-labelledby="`${panelId}-${activeTab}-tab`"
          tabindex="0"
        >
          <template v-if="activeTab === 'todos'">
            <TodoProgressCard v-if="selectedTodos.length" :todos="selectedTodos" :live="selectedStatus === 'running'" />
            <p v-if="selectedStatus !== 'running' && selectedTodos.some(todo => todo.status === 'in_progress')" class="task-snapshot-note">{{ $t('chat.activityTaskSnapshot') }}</p>
            <div v-else class="empty-state">{{ $t('chat.activityEmpty') }}</div>
          </template>

          <template v-else-if="activeTab === 'thoughts'">
            <div v-if="visibleThoughts.length" class="thought-list">
              <article v-for="thought in visibleThoughts" :key="thought.key" class="thought-entry">
                <MarkdownPreview :content="thought.content" />
              </article>
            </div>
            <div v-else class="empty-state">{{ $t('chat.activityEmpty') }}</div>
          </template>

          <template v-else-if="activeTab === 'processes'">
            <div v-if="selectedProcesses.length" class="process-list">
              <section
                v-for="(process, index) in selectedProcesses"
                :key="process.key || process.id || index"
                class="process-entry"
              >
                <div class="process-number">{{ $t('chat.activityProcessNumber', { n: index + 1 }) }}</div>
                <ProcessCard :process="process" />
              </section>
            </div>
            <div v-else class="empty-state">{{ $t('chat.activityEmpty') }}</div>
          </template>

          <template v-else>
            <div v-if="turns.length" class="location-list">
              <div
                v-for="(turn, index) in turns"
                :key="turn.key"
                class="location-row"
                :class="{ selected: turn.key === selectedTurn?.key }"
              >
                <button type="button" class="turn-location" @click="navigateTo(turnAnchor(turn), 'turn')">
                  <span class="location-index">{{ index + 1 }}</span>
                  <span class="location-label">{{ turn.label }}</span>
                </button>
                <div class="location-actions">
                  <button
                    v-if="turn.answerIndex >= 0"
                    type="button"
                    @click="navigateTo(turn.answerIndex, 'answer')"
                  >
                    {{ $t('chat.activityJumpToAnswer') }}
                  </button>
                  <button
                    v-if="turn.attentionIndex >= 0"
                    type="button"
                    class="attention-link"
                    @click="navigateTo(turn.attentionIndex, 'attention')"
                  >
                    {{ $t('chat.activityJumpToAttention') }}
                  </button>
                </div>
              </div>
            </div>
            <div v-else class="empty-state">{{ $t('chat.activityEmpty') }}</div>
          </template>
        </div>
      </div>
    </Transition>
  </section>
</template>

<script setup>
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t } from '@/i18n'
import MarkdownPreview from '../MarkdownPreview.vue'
import ProcessCard from './ProcessCard.vue'
import TodoProgressCard from './TodoProgressCard.vue'

const props = defineProps({
  turns: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false }
})

const emit = defineEmits(['navigate'])

const panelId = `turn-activity-${getCurrentInstance()?.uid ?? 'panel'}`
const panelTitleId = `${panelId}-title`
const validTabs = ['todos', 'thoughts', 'processes', 'locate']
const isOpen = ref(false)
const panelRoot = ref(null)
let opener = null
const activeTab = ref('processes')
const selectedKey = ref(null)

const latestTurn = computed(() => props.turns[props.turns.length - 1] || null)
const selectedTurn = computed(() => {
  return props.turns.find(turn => turn.key === selectedKey.value) || latestTurn.value
})
const selectedTurnIndex = computed(() => {
  if (!selectedTurn.value) return -1
  return props.turns.findIndex(turn => turn.key === selectedTurn.value.key)
})
const selectedTodos = computed(() => selectedTurn.value?.todos || [])
const selectedProcesses = computed(() => selectedTurn.value?.processes || [])
const visibleThoughts = computed(() => {
  return (selectedTurn.value?.thoughts || []).filter(thought => String(thought?.content || '').trim())
})
const completedTodoCount = computed(() => {
  return selectedTodos.value.filter(todo => todo.status === 'completed').length
})
const currentActivityHint = computed(() => {
  if (selectedTurn.value?.status !== 'running') return ''
  const currentTodo = selectedTodos.value.find(todo => todo.status === 'in_progress')
  if (currentTodo) return currentTodo.activeForm || currentTodo.content || ''

  for (let processIndex = selectedProcesses.value.length - 1; processIndex >= 0; processIndex -= 1) {
    const process = selectedProcesses.value[processIndex]
    const items = process?.items || []
    for (let itemIndex = items.length - 1; itemIndex >= 0; itemIndex -= 1) {
      const item = items[itemIndex]
      if (item?.type === 'step' && item.text) return item.text
    }
    if (process?.title) return process.title
  }
  return ''
})
const selectedStatus = computed(() => selectedTurn.value?.status || 'idle')

const statusKeys = {
  running: 'chat.activityStatusRunning',
  queued: 'chat.activityStatusQueued',
  finished: 'chat.activityStatusFinished',
  awaiting_input: 'chat.activityStatusAwaitingInput',
  awaiting_approval: 'chat.activityStatusAwaitingApproval',
  error: 'chat.activityStatusError',
  cancelled: 'chat.activityStatusCancelled',
  paused: 'chat.activityStatusPaused',
  interrupted: 'chat.activityStatusInterrupted',
  idle: 'chat.activityStatusIdle'
}
const statusKey = computed(() => statusKeys[selectedStatus.value] || statusKeys.idle)

const tabs = computed(() => [
  { id: 'todos', label: t('chat.activityTasks', { done: completedTodoCount.value, total: selectedTodos.value.length }) },
  { id: 'thoughts', label: t('chat.activityThoughts', { count: visibleThoughts.value.length }) },
  { id: 'processes', label: t('chat.activityProcesses', { count: selectedProcesses.value.length }) },
  { id: 'locate', label: t('chat.activityLocate') }
])

watch(
  () => latestTurn.value?.key,
  latestKey => {
    selectedKey.value = latestKey ?? null
    isOpen.value = false
  },
  { immediate: true }
)

const closePanel = () => {
  if (panelRoot.value?.querySelector('.activity-panel')?.contains(document.activeElement)) opener?.focus?.()
  isOpen.value = false
}

const togglePanel = tab => {
  if (!selectedTurn.value) return
  if (isOpen.value && activeTab.value === tab) {
    closePanel()
    return
  }
  opener = document.activeElement
  activeTab.value = tab
  isOpen.value = true
}

const openTurn = (key, tab = 'processes') => {
  opener = document.activeElement
  selectedKey.value = key
  activeTab.value = validTabs.includes(tab) ? tab : 'processes'
  isOpen.value = !!selectedTurn.value
}

const selectRelativeTurn = offset => {
  const nextIndex = selectedTurnIndex.value + offset
  if (nextIndex < 0 || nextIndex >= props.turns.length) return
  selectedKey.value = props.turns[nextIndex].key
}

const selectAdjacentTab = (currentId, offset, event) => {
  const currentIndex = validTabs.indexOf(currentId)
  const nextIndex = (currentIndex + offset + validTabs.length) % validTabs.length
  const tabList = event.currentTarget?.parentElement
  activeTab.value = validTabs[nextIndex]
  nextTick(() => {
    tabList?.querySelector(`[data-activity-tab="${activeTab.value}"]`)?.focus()
  })
}

const turnAnchor = turn => {
  if (typeof turn?.user?.index === 'number') return turn.user.index
  const firstAssistant = turn?.assistants?.[0]
  return typeof firstAssistant?.index === 'number' ? firstAssistant.index : -1
}

const navigateTo = (index, target) => {
  if (typeof index !== 'number' || index < 0) return
  closePanel()
  emit('navigate', { index, target })
}

const handleEscape = event => {
  if (event.key === 'Escape' && isOpen.value) closePanel()
}

const handleOutsideClick = event => {
  if (isOpen.value && !panelRoot.value?.contains(event.target)) closePanel()
}
onMounted(() => {
  document.addEventListener('keydown', handleEscape)
  document.addEventListener('pointerdown', handleOutsideClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', handleEscape)
  document.removeEventListener('pointerdown', handleOutsideClick)
})

defineExpose({ openTurn })
</script>

<style scoped>
button { margin: 0; }
button::after { border: 0; }
.task-snapshot-note { color: var(--awd-text-2); font-size: 12px; line-height: 1.6; margin: 10px 2px 0; }

.turn-activity {
  position: relative;
  z-index: 12;
  flex-shrink: 0;
  width: 100%;
  min-width: 0;
  container-type: inline-size;
  color: var(--awd-text);
}

.activity-bar {
  min-height: 36px;
  padding: 4px 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--awd-surface);
  border-bottom: 1px solid var(--awd-border);
}

.turn-status {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--awd-text-2);
  font-size: 12px;
  font-weight: 600;
}

.status-dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-text {
  flex: 0 0 auto;
  white-space: nowrap;
}

.status-separator {
  flex: 0 0 auto;
  color: var(--awd-text-3);
}

.turn-context {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.turn-summary,
.activity-hint {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.turn-summary {
  flex: 0 1 auto;
  color: var(--awd-text);
}

.activity-hint {
  flex: 1 1 auto;
  color: var(--awd-text-2);
}

.status-running,
.status-finished {
  color: var(--awd-accent-text);
}

.status-awaiting_input,
.status-awaiting_approval,
.status-paused {
  color: var(--awd-warning-text);
}

.status-error {
  color: var(--awd-danger-text);
}

.turn-status.is-pulsing .status-dot {
  animation: activity-pulse 1.5s ease-in-out infinite;
}

.activity-actions {
  min-width: 0;
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
}

.activity-trigger,
.activity-tabs button,
.location-actions button {
  border: 1px solid var(--awd-border);
  background: var(--awd-bg);
  color: var(--awd-text-2);
  border-radius: 999px;
  font: inherit;
  cursor: pointer;
}

.activity-trigger {
  height: 26px;
  padding: 0 8px;
  font-size: 12px;
  line-height: 24px;
  white-space: nowrap;
}

.activity-trigger:hover:not(:disabled),
.activity-trigger.active,
.activity-tabs button:hover,
.activity-tabs button.active {
  border-color: var(--awd-border-strong);
  background: var(--awd-accent-soft);
  color: var(--awd-accent-text);
}

.activity-trigger.attention-trigger {
  border-color: var(--awd-warning);
  background: var(--awd-warning-soft);
  color: var(--awd-warning-text);
}

button:focus-visible {
  outline: 2px solid var(--awd-mint);
  outline-offset: 2px;
}

button:disabled {
  cursor: default;
  opacity: 0.45;
}

.activity-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 8px;
  right: 8px;
  max-height: min(50vh, 420px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--awd-surface);
  border: 1px solid var(--awd-border-strong);
  border-radius: 10px;
  box-shadow: var(--awd-shadow-lg);
}

.panel-heading {
  min-height: 42px;
  padding: 6px 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--awd-border);
}

.turn-picker {
  min-width: 0;
  flex: 1;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 4px;
}

.icon-button,
.close-button {
  width: 28px;
  height: 28px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--awd-text-2);
  cursor: pointer;
}

.icon-button:hover:not(:disabled),
.close-button:hover {
  background: var(--awd-surface-2);
  color: var(--awd-text);
}

.icon-button svg,
.close-button svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.turn-title-wrap {
  min-width: 0;
  text-align: center;
}

.panel-title {
  display: block;
  overflow: hidden;
  color: var(--awd-text);
  font-size: 12px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.turn-counter {
  display: block;
  margin-top: 1px;
  color: var(--awd-text-3);
  font-size: 12px;
}

.activity-tabs {
  padding: 6px 8px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 4px;
  border-bottom: 1px solid var(--awd-border);
  background: var(--awd-bg);
}

.activity-tabs button {
  min-width: 0;
  height: 26px;
  padding: 0 5px;
  overflow: hidden;
  font-size: 12px;
  line-height: 24px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.panel-body {
  min-height: 72px;
  padding: 8px;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
}

.empty-state {
  min-height: 56px;
  display: grid;
  place-items: center;
  color: var(--awd-text-3);
  font-size: 12px;
}

.thought-list,
.process-list,
.location-list {
  display: grid;
  gap: 7px;
}

.thought-entry {
  padding: 7px 9px;
  overflow: hidden;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 7px;
}

.thought-entry :deep(.markdown-preview),
.thought-entry :deep(.markdown-body) {
  min-height: 0;
  margin: 0;
  padding: 0 !important;
  background: transparent !important;
  color: var(--awd-text-2);
  font-size: 12px;
  line-height: 1.5;
}

.thought-entry :deep(.markdown-body > :first-child) {
  margin-top: 0 !important;
}

.thought-entry :deep(.markdown-body > :last-child) {
  margin-bottom: 0 !important;
}

.process-entry {
  min-width: 0;
}

.process-number {
  margin: 0 2px 4px;
  color: var(--awd-text-3);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.location-row {
  min-width: 0;
  padding: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--awd-border);
  border-radius: 7px;
  background: var(--awd-bg);
}

.location-row.selected {
  border-color: var(--awd-border-strong);
  background: var(--awd-accent-wash);
}

.turn-location {
  min-width: 0;
  flex: 1;
  padding: 2px;
  display: flex;
  align-items: center;
  gap: 7px;
  border: 0;
  background: transparent;
  color: var(--awd-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.location-index {
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  display: inline-grid;
  place-items: center;
  border-radius: 50%;
  background: var(--awd-surface-2);
  color: var(--awd-text-2);
  font-size: 12px;
  font-weight: 700;
}

.location-label {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.location-actions {
  flex: 0 0 auto;
  display: flex;
  gap: 4px;
}

.location-actions button {
  min-height: 24px;
  padding: 3px 7px;
  font-size: 12px;
}

.location-actions .attention-link {
  border-color: var(--awd-warning);
  background: var(--awd-warning-soft);
  color: var(--awd-warning-text);
}

.activity-pop-enter-active,
.activity-pop-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
  transform-origin: top center;
}

.activity-pop-enter-from,
.activity-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.99);
}

@keyframes activity-pulse {
  0%, 100% { opacity: 1; box-shadow: 0 0 0 0 var(--awd-accent-soft); }
  50% { opacity: 0.7; box-shadow: 0 0 0 4px transparent; }
}

@container (max-width: 560px) {
  .activity-bar {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 6px;
    min-height: 72px;
    padding-block: 6px;
  }

  .turn-status {
    min-height: 28px;
  }

  .activity-actions {
    width: 100%;
    margin-left: 0;
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .activity-actions.has-attention {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }

  .activity-trigger {
    min-width: 0;
    padding: 0 3px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

@container (max-width: 360px) {
  .turn-context {
    display: block;
  }

  .activity-hint {
    display: block;
  }

  .location-row {
    align-items: stretch;
    flex-direction: column;
  }

  .location-actions {
    justify-content: flex-end;
  }
}

@media (prefers-reduced-motion: reduce) {
  .activity-pop-enter-active,
  .activity-pop-leave-active,
  .turn-status.is-pulsing .status-dot {
    animation: none;
    transition: none;
  }
}
</style>
