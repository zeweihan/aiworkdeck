<template>
  <view class="qo-mask" @tap.self="$emit('close')">
    <view class="qo-panel">
      <input
        class="qo-input"
        type="text"
        :placeholder="$t('files.quickOpenPlaceholder')"
        :value="query"
        :focus="true"
        @input="onInput"
      />
      <scroll-view v-if="matches.length" class="qo-list" scroll-y :scroll-into-view="'qo-item-' + activeIndex">
        <view
          v-for="(item, idx) in matches"
          :id="'qo-item-' + idx"
          :key="item.id"
          class="qo-item"
          :class="{ 'is-active': idx === activeIndex }"
          @tap="$emit('open', item)"
          @mousemove="activeIndex = idx"
        >
          <text class="qo-item-name">{{ item.name }}</text>
          <text class="qo-item-path">{{ item.dirLabel }}</text>
        </view>
      </scroll-view>
      <view v-else class="qo-empty">
        <text>{{ loading ? $t('files.loadingFileList') : (query ? $t('files.noMatchingFiles') : $t('files.typeToSearch')) }}</text>
      </view>
    </view>
  </view>
</template>

<script>
// IDE 化 Cmd+P 快速打开：按名字模糊匹配项目文件，方向键 + 回车打开。
// 宿主（project-overview）负责挂载时机与全局快捷键；本组件只管列表与键盘交互。
import { getProjectFiles } from '@/services/api.js'

const MAX_RESULTS = 30

export default {
  name: 'QuickOpenPanel',
  props: {
    projectId: { type: [Number, String], required: true },
  },
  emits: ['open', 'close'],
  data() {
    return {
      query: '',
      files: [],
      loading: true,
      activeIndex: 0,
    }
  },
  computed: {
    matches() {
      const q = this.query.trim().toLowerCase()
      let list
      if (!q) {
        list = this.files.slice(0, MAX_RESULTS)
      } else {
        // 前缀命中优先于包含命中；同级按名字短的在前（更可能是想要的那个）
        const starts = []
        const includes = []
        for (const f of this.files) {
          const name = f.name.toLowerCase()
          if (name.startsWith(q)) starts.push(f)
          else if (name.includes(q)) includes.push(f)
          if (starts.length >= MAX_RESULTS) break
        }
        list = starts.concat(includes).slice(0, MAX_RESULTS)
      }
      return list
    },
  },
  watch: {
    matches() {
      this.activeIndex = 0
    },
  },
  async mounted() {
    // uni 的 input 不透传 keydown（Vue3 也无 .native）：面板存续期在 document 捕获段
    // 拦方向键/回车/Esc，卸载即摘（面板是模态的，捕获期间不影响底下页面）
    this._keydownHandler = (e) => this.onKeydown(e)
    document.addEventListener('keydown', this._keydownHandler, true)
    try {
      const resp = await getProjectFiles(this.projectId)
      const all = Array.isArray(resp) ? resp : (resp && resp.data) || []
      const byId = new Map(all.map((f) => [f.id, f]))
      this.files = all
        .filter((f) => !f.isFolder && !f.isDeleted)
        .map((f) => ({ ...f, dirLabel: this.dirLabelOf(f, byId) }))
        .sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh'))
    } catch (e) {
      console.warn('[QuickOpen] 加载文件列表失败', e)
    } finally {
      this.loading = false
    }
  },
  beforeUnmount() {
    if (this._keydownHandler) {
      document.removeEventListener('keydown', this._keydownHandler, true)
      this._keydownHandler = null
    }
  },
  methods: {
    dirLabelOf(f, byId) {
      const parts = []
      let pid = f.parentId
      let depth = 0
      while (pid && depth < 10) {
        const parent = byId.get(pid)
        if (!parent) break
        parts.unshift(parent.name)
        pid = parent.parentId
        depth++
      }
      return parts.join(' / ')
    },
    onInput(e) {
      this.query = (e.detail && e.detail.value) || ''
    },
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
        if (item) this.$emit('open', item)
      } else if (e.key === 'Escape') {
        e.preventDefault()
        this.$emit('close')
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.qo-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.15);
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 12vh;
}

.qo-panel {
  width: 560px;
  max-width: calc(100vw - 48px);
  background: var(--awd-surface);
  border-radius: 10px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.22);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.qo-input {
  height: 48px;
  padding: 0 16px;
  font-size: 15px;
  border-bottom: 1px solid var(--awd-border);
  box-sizing: border-box;
  width: 100%;
}

.qo-list {
  max-height: 46vh;
}

.qo-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 9px 16px;
  cursor: pointer;

  &.is-active {
    background: var(--awd-accent-soft);
  }
}

.qo-item-name {
  font-size: 14px;
  color: var(--awd-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qo-item-path {
  font-size: 12px;
  color: var(--awd-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 1;
}

.qo-empty {
  padding: 20px 16px;
  font-size: 13px;
  color: var(--awd-text-2);
  text-align: center;
}
</style>
