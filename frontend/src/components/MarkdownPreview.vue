<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="markdown-preview">
    <view v-if="loading" class="markdown-loading">
      <text>{{ $t('files.loadingDots') }}</text>
    </view>
    <!-- 代码块复制用事件委托：正文每帧整段重写 innerHTML，给每颗按钮单独绑监听
         会在流式过程中反复建了又丢（dev-board#790）。 -->
    <view v-else class="markdown-body" v-html="renderedHtml" @click="handleBodyClick"></view>
  </view>
</template>

<script>
import { renderMarkdown } from '@/utils/markdownRenderer.js'
import { copyToClipboard } from '@/utils/chatClipboard.js'
import { t } from '@/i18n'
import { getFileDownloadUrl } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'

// 每帧最多渲染一次（dev-board#750）。流式回答每个 token 都会换一次 content，而这里的渲染
// 是「整篇重新解析 + 整段 innerHTML 重写」——不是追加。不合帧的话，一篇 N 个 token 的回答
// 要把全文重建 N 遍（O(n²)），还会在每次重写时清掉用户在正文里选中的文字。
// 合帧不会推迟首字：第一次渲染走同步路径，之后才开始攒帧。
const scheduleFrame = typeof requestAnimationFrame === 'function'
  ? (cb) => requestAnimationFrame(cb)
  : (cb) => setTimeout(cb, 16)
const cancelFrame = typeof requestAnimationFrame === 'function'
  ? (h) => cancelAnimationFrame(h)
  : (h) => clearTimeout(h)

export default {
  name: 'MarkdownPreview',
  props: {
    // AI artifact 的内容（直接传入）
    content: {
      type: String,
      default: ''
    },
    // 真正的 .md 文件对象（从服务器加载）
    file: {
      type: Object,
      default: null
    }
  },
  data() {
    // md 实例刻意不放在这里：放进 data() 会被 Vue 做成响应式代理，解析慢 3.7 倍以上
    // （详见 utils/markdownRenderer.js 的注释）
    return {
      // 首屏同步渲染一次：静态预览（文件、历史消息、计划卡）挂载后立刻就要有内容，
      // 之后的变更才开始合帧
      // 代码块复制键的文字随渲染一起生成（见 utils/markdownRenderer.js 为什么不在那边 import i18n）
      renderedHtml: renderMarkdown(this.content || '', { copyLabel: t('chat.copyCode') }),
      loadedContent: '',
      loading: false
    }
  },
  computed: {
    sourceText() {
      // 优先使用直接传入的 content，其次使用从服务器加载的内容
      return this.content || this.loadedContent || ''
    }
  },
  watch: {
    // 刻意不 immediate：首屏那一次已经在 data() 里同步渲染过了
    sourceText() {
      this.scheduleRender()
    },
    file: {
      immediate: true,
      handler(newFile) {
        if (newFile && !this.content) {
          this.loadFileContent()
        }
      }
    }
  },
  beforeUnmount() {
    if (this.renderFrame != null) {
      cancelFrame(this.renderFrame)
      this.renderFrame = null
    }
  },
  methods: {
    /**
     * 代码块右上角那颗复制键（按钮由 markdownRenderer 的 fence/code_block 规则渲染）。
     * 取的是同一个 .md-code-block 里 <pre> 的 textContent——渲染出来的转义实体
     * （&lt; &amp;）在 textContent 里已经还原成原字符，复制走的就是代码原文。
     */
    handleBodyClick(event) {
      const button = event.target && event.target.closest && event.target.closest('[data-md-copy]')
      if (!button) return
      event.preventDefault()
      event.stopPropagation()
      const block = button.closest('.md-code-block')
      const pre = block && block.querySelector('pre')
      copyToClipboard(pre ? pre.textContent : '')
    },
    scheduleRender() {
      // 已有待执行的帧时不重复排：回调里读的是当时最新的 sourceText，
      // 所以最后一次变更一定会被渲染出来（不会丢尾巴）。
      // renderFrame 未初始化时是 undefined，`!= null` 同样为 false——刻意不进 data()，
      // 它不参与渲染，放进去只会让每一帧多两次无谓的响应式写入。
      if (this.renderFrame != null) return
      this.renderFrame = scheduleFrame(() => {
        this.renderFrame = null
        this.renderNow()
      })
    },
    renderNow() {
      this.renderedHtml = renderMarkdown(this.sourceText, { copyLabel: t('chat.copyCode') })
    },
    async loadFileContent() {
      if (!this.file) return

      const fileId = this.file.wpsFileId || this.file.id
      if (!fileId) return

      this.loading = true
      try {
        const url = getFileDownloadUrl(fileId)
        const headers = getAuthHeaders()
        const response = await fetch(url, { headers })

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`)
        }

        this.loadedContent = await response.text()
      } catch (e) {
        console.error('加载 Markdown 文件失败:', e)
        this.loadedContent = this.$t('files.loadFailedWithReason', { reason: e.message })
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.markdown-preview {
  padding: 16px;
  background: var(--awd-surface);
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden; /* Prevent horizontal overflow */
  box-sizing: border-box;
  min-width: 0; /* Allow flex shrinking */
}

.markdown-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--awd-text-2);
  font-size: 14px;
}

.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: var(--awd-text);
  word-wrap: break-word;
  overflow-wrap: break-word;
  user-select: text; /* Allow text selection for copying */
  -webkit-user-select: text;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin-top: 16px;
  margin-bottom: 8px;
  font-weight: 600;
  color: var(--awd-accent-text);
}

.markdown-body :deep(h1) {
  font-size: 20px;
  border-bottom: 1px solid var(--awd-border);
  padding-bottom: 8px;
}

.markdown-body :deep(h2) {
  font-size: 17px;
}

.markdown-body :deep(h3) {
  font-size: 15px;
}

.markdown-body :deep(p) {
  margin: 8px 0;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 20px;
  /* margin: 8px 0; */
}

.markdown-body :deep(li) {
  margin: 4px 0;
}

.markdown-body :deep(code) {
  background: var(--awd-surface-2);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 13px;
}

.markdown-body :deep(pre) {
  background: var(--awd-surface);
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  margin: 12px 0;
}

.markdown-body :deep(pre code) {
  background: none;
  padding: 0;
}

.markdown-body :deep(blockquote) {
  border-left: 3px solid var(--awd-accent);
  padding-left: 12px;
  margin: 12px 0;
  color: var(--awd-text-2);
  font-style: italic;
}

/* 代码块 + 右上角复制键。按钮浮在块内右上角，不占正文宽度；
   代码块自身可能横向滚动，所以定位挂在外层 wrapper 上而不是 <pre> 上。 */
.markdown-body :deep(.md-code-block) {
  position: relative;
}

.markdown-body :deep(.md-copy-btn) {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  padding: 2px 8px;
  font-size: 11px;
  font-family: inherit;
  line-height: 1.6;
  color: var(--awd-text-2);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 5px;
  cursor: pointer;
  opacity: 0.75;
  transition: opacity 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.markdown-body :deep(.md-code-block:hover .md-copy-btn) {
  opacity: 1;
}

.markdown-body :deep(.md-copy-btn:hover) {
  color: var(--awd-accent-text);
  border-color: var(--awd-mint);
}

.markdown-body :deep(.md-table-scroll) {
  max-width: 100%;
  overflow-x: auto;
}

.markdown-body :deep(.md-table-scroll)::-webkit-scrollbar {
  height: 8px;
}

.markdown-body :deep(.md-table-scroll)::-webkit-scrollbar-thumb {
  background: var(--awd-border);
  border-radius: 4px;
}

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid var(--awd-border);
  padding: 8px 12px;
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--awd-surface-2);
  font-weight: 600;
}


</style>
