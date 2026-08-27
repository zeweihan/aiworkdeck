<template>
  <view class="cp-mask" @tap.self="$emit('close')">
    <view class="cp-panel">
      <input
        class="cp-input"
        type="text"
        :placeholder="$t('workbench.commandPalettePlaceholder')"
        :value="query"
        :focus="true"
        @input="onInput"
      />
      <scroll-view v-if="matches.length" class="cp-list" scroll-y :scroll-into-view="'cp-item-' + activeIndex">
        <view
          v-for="(item, idx) in matches"
          :id="'cp-item-' + idx"
          :key="item.id"
          class="cp-item"
          :class="{ 'is-active': idx === activeIndex }"
          @tap="$emit('run', item)"
          @mousemove="activeIndex = idx"
        >
          <text class="cp-item-menu">{{ item.menuLabel }}</text>
          <text class="cp-item-name">{{ item.label }}</text>
          <text v-if="item.accelText" class="cp-item-accel">{{ item.accelText }}</text>
        </view>
      </scroll-view>
      <view v-else class="cp-empty">
        <text>{{ $t('workbench.commandPaletteEmpty') }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// 命令面板：命令注册表的第三个消费者（另两个是原生菜单栏与加速键）。
// 它读的就是菜单读的那张表，所以菜单里有的这里必然有，不会漂。
//
// 只列**当前语境下可执行**的命令——listAvailableCommands 已按 when 过滤过，
// 面板里不出现点了没反应的条目。
//
// 键盘交互与 QuickOpenPanel 一致（uni 的 input 不透传 keydown，面板存续期在
// document 捕获段拦方向键/回车/Esc，卸载即摘）。

import { listAvailableCommands } from '@/utils/appMenuBridge.js'
import { MENU_ORDER, labelOf } from '@/config/commands/index.js'
import { getAppLanguage } from '@/utils/appLanguage.js'

const MAX_RESULTS = 40

/** Electron accelerator 写法 → mac 键帽符号。 */
function prettyAccel(a) {
  if (!a) return ''
  return String(a)
    .replace(/CmdOrCtrl|Command|Cmd/g, '⌘')
    .replace(/Alt|Option/g, '⌥')
    .replace(/Shift/g, '⇧')
    .replace(/Control|Ctrl/g, '⌃')
    .replace(/Right/g, '→').replace(/Left/g, '←')
    .replace(/\+/g, '')
}

export default {
  name: 'CommandPalette',
  emits: ['run', 'close'],
  data() {
    return { query: '', activeIndex: 0, items: [] }
  },
  computed: {
    matches() {
      const q = this.query.trim().toLowerCase()
      if (!q) return this.items.slice(0, MAX_RESULTS)
      const starts = []
      const includes = []
      for (const it of this.items) {
        const hay = (it.menuLabel + ' ' + it.label).toLowerCase()
        if (it.label.toLowerCase().startsWith(q)) starts.push(it)
        else if (hay.includes(q)) includes.push(it)
      }
      return starts.concat(includes).slice(0, MAX_RESULTS)
    },
  },
  watch: {
    matches() { this.activeIndex = 0 },
  },
  mounted() {
    const lang = getAppLanguage()
    const menuLabel = new Map(MENU_ORDER.map((m) => [m.id, m.label ? labelOf(m, lang) : 'App']))
    this.items = listAvailableCommands().map((c) => ({
      ...c,
      menuLabel: menuLabel.get(c.menu) || c.menu,
      accelText: prettyAccel(c.accel),
    }))
    this._keydownHandler = (e) => this.onKeydown(e)
    document.addEventListener('keydown', this._keydownHandler, true)
  },
  beforeUnmount() {
    if (this._keydownHandler) {
      document.removeEventListener('keydown', this._keydownHandler, true)
      this._keydownHandler = null
    }
  },
  methods: {
    onInput(e) { this.query = (e.detail && e.detail.value) || '' },
    onKeydown(e) {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        if (this.matches.length) this.activeIndex = (this.activeIndex + 1) % this.matches.length
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        if (this.matches.length) this.activeIndex = (this.activeIndex - 1 + this.matches.length) % this.matches.length
      } else if (e.key === 'Enter') {
        e.preventDefault()
        const item = this.matches[this.activeIndex]
        if (item) this.$emit('run', item)
      } else if (e.key === 'Escape') {
        e.preventDefault()
        this.$emit('close')
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.cp-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.15);
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 12vh;
}

.cp-panel {
  width: 600px;
  max-width: calc(100vw - 48px);
  background: var(--awd-surface);
  border-radius: 10px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.22);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.cp-input {
  height: 48px;
  padding: 0 16px;
  font-size: 15px;
  border-bottom: 1px solid var(--awd-border);
  box-sizing: border-box;
  width: 100%;
}

.cp-list {
  max-height: 46vh;
}

.cp-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 9px 16px;
  cursor: pointer;

  &.is-active {
    background: var(--awd-accent-soft);
  }
}

.cp-item-menu {
  font-size: 12px;
  color: var(--awd-text-3);
  flex-shrink: 0;
  min-width: 48px;
}

.cp-item-name {
  font-size: 14px;
  color: var(--awd-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.cp-item-accel {
  font-size: 12px;
  color: var(--awd-text-2);
  flex-shrink: 0;
}

.cp-empty {
  padding: 20px 16px;
  font-size: 13px;
  color: var(--awd-text-2);
  text-align: center;
}
</style>
