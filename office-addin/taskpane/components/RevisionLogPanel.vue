<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="overlay" @click.self="$emit('close')">
    <div class="panel glass">
      <div class="panel-head">
        <span>{{ t('revLogTitle') }}</span>
        <button class="panel-close" @click="$emit('close')">x</button>
      </div>

      <p v-if="!entries.length" class="empty">{{ t('revLogEmpty') }}</p>

      <div v-else class="entry-list">
        <div v-for="e in entries" :key="e.id" class="entry">
          <div class="entry-head">
            <span class="entry-time">{{ formatEntryTime(e.time) }}</span>
            <span class="entry-from">{{ t('revLogFrom', { name: e.originDocName || t('revLogUnknownDoc') }) }}</span>
          </div>
          <div class="entry-summary">{{ entrySummary(e) }}</div>
          <div class="entry-actions">
            <!-- 定位：Word 按改后的文字选中，表格/演示按目标区域或页跳过去。
                 条目里没有可定位的信息（结构类命令、参数里没有表名）时不给按钮 -->
            <button v-if="canLocate(e)" class="act" @click="locate(e)">{{ t('revLogLocate') }}</button>
            <button
              v-if="undoState(e) === 'undoable'"
              class="act primary"
              :disabled="busyId === e.id"
              @click="undo(e)"
            >{{ t('revLogUndo') }}</button>
            <!-- 撤不了的三种情形各有各的出路，不给一个按下去必然失败的按钮 -->
            <span v-else class="act-note">{{ undoNote(e) }}</span>
          </div>
          <p v-if="feedback[e.id]" class="entry-feedback" :class="{ warn: feedback[e.id].warn }">
            {{ feedback[e.id].text }}
          </p>
        </div>
      </div>

      <button v-if="entries.length" class="clear-btn" @click="clearAll">{{ t('revLogClear') }}</button>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { t } from '../lib/i18n.js'
import { entries, formatEntryTime, markUndone, clear } from '../lib/revisionLog.js'
import { entrySummary, canLocate, locateEntry, entryUndoState, undoRevisionEntry } from '../lib/crossDocWrite.js'

/**
 * 修订记录面板（dev-board#717）：别的窗格的 AI 改了本文档，痕迹留在这里——谁改的、
 * 改了什么、跳过去看看、把它撤回来。overlay 面板模式（与 TransferPanel 同款，挂在
 * App.vue 层，样式自含），z-index 高于账户菜单/新建项目弹层（40）。
 *
 * 面板只做接线：条目状态、定位、撤销的判断全在 lib/crossDocWrite.js（可在 node 里测）。
 * 撤销成功后不额外给提示——条目自己会翻成「已撤销」，重复说一遍反而像是两件事。
 */
defineEmits(['close'])

/** 每条的一次性反馈（定位没找到、撤销冲突或失败）；撤销成功时清掉，让条目状态说话 */
const feedback = reactive({})
const busyId = ref('')

function undoState(entry) {
  return entryUndoState(entry)
}

function undoNote(entry) {
  const state = entryUndoState(entry)
  if (state === 'undone') return t('revLogUndone')
  if (state === 'noBefore') return t('revLogNoBefore')
  return t('revLogNotUndoable')
}

async function locate(entry) {
  const r = await locateEntry(entry)
  // 定位不到只是轻提示：文字被再改过、表或页被删掉都很正常，不是错误
  feedback[entry.id] = r.found ? null : { text: t('revLogLocateMiss'), warn: true }
}

async function undo(entry) {
  busyId.value = entry.id
  try {
    const r = await undoRevisionEntry(entry, { onUndone: markUndone })
    if (r.status === 'undone') feedback[entry.id] = null
    else if (r.status === 'conflict') feedback[entry.id] = { text: t('revLogConflict'), warn: true }
    else feedback[entry.id] = { text: t('revLogUndoFailed', { message: r.error || '' }), warn: true }
  } finally {
    busyId.value = ''
  }
}

function clearAll() {
  clear()
  for (const k of Object.keys(feedback)) delete feedback[k]
}
</script>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgba(14, 33, 23, 0.32);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.panel {
  width: 100%;
  max-width: 360px;
  max-height: 92%;
  overflow-y: auto;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-md, 10px);
  box-shadow: var(--awd-shadow-float, 0 8px 32px rgba(18, 58, 38, 0.16));
  padding: 12px 14px 14px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.panel-close {
  border: none;
  background: none;
  color: var(--awd-text-secondary);
  font-family: var(--awd-font-mono, monospace);
  cursor: pointer;
  padding: 0 4px;
}

.empty {
  margin: 10px 0;
  padding: 10px;
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-mint-pale);
  color: var(--awd-text-secondary);
  font-size: 11px;
  line-height: 1.5;
  text-align: center;
}

.entry-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.entry {
  padding: 8px 10px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
}

.entry-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 11px;
  color: var(--awd-text-secondary);
}

.entry-time {
  font-family: var(--awd-font-mono, monospace);
  flex-shrink: 0;
}

.entry-from {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.entry-summary {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--awd-text);
  word-break: break-word;
}

.entry-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.act {
  padding: 3px 10px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
  transition: color 0.15s ease, border-color 0.15s ease, transform 0.1s ease;
}

.act:hover:not(:disabled) {
  color: var(--awd-accent);
  border-color: var(--awd-accent);
}

.act:active:not(:disabled) { transform: translateY(1px); }
.act:disabled { opacity: 0.5; cursor: not-allowed; }

.act.primary {
  color: var(--awd-primary);
  border-color: var(--awd-border-strong);
}

.act-note {
  font-size: 11px;
  line-height: 1.4;
  color: var(--awd-text-secondary);
}

.entry-feedback {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--awd-text-secondary);
}

.entry-feedback.warn { color: var(--awd-danger); }

.clear-btn {
  margin-top: 10px;
  width: 100%;
  padding: 6px 0;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 12px;
}

.clear-btn:hover {
  color: var(--awd-danger);
  border-color: var(--awd-danger);
}
</style>
