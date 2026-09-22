<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="markdown-preview">
    <view v-if="loading" class="markdown-loading">
      <text>{{ $t('files.loadingDots') }}</text>
    </view>
    <!-- 代码块复制用事件委托：正文每帧整段重写 innerHTML，给每颗按钮单独绑监听
         会在流式过程中反复建了又丢（dev-board#790）。 -->
    <!-- 两段 v-html（dev-board#811 K31）：前一段是已经定稿的前缀，字符串不变 Vue 就不碰它的
         DOM——用户在那里选中的文字不会被下一个 token 清掉，也不用重新解析。切点规则见
         utils/markdownStableSplit.js。正文短的时候 stableHtml 恒为空串，形态与改造前一致。 -->
    <view v-else class="markdown-body" @click="handleBodyClick">
      <view v-if="stableHtml" class="markdown-stable" v-html="stableHtml"></view>
      <view v-if="tailHtml" class="markdown-tail" v-html="tailHtml"></view>
    </view>
  </view>
</template>

<script>
import { renderMarkdown } from '@/utils/markdownRenderer.js'
import { nextStableLength } from '@/utils/markdownStableSplit.js'
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
      // 已定稿前缀的 HTML（流式期间不重算、不重写 DOM）与还在长的尾巴
      stableHtml: '',
      tailHtml: renderMarkdown(this.content || '', { copyLabel: t('chat.copyCode') }),
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
    if (this.settleTimer != null) {
      clearTimeout(this.settleTimer)
      this.settleTimer = null
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
    /**
     * 流式渲染的分段口径（dev-board#811 K31）。
     * 前缀只在它真的前进时重新解析一段新的，尾巴每帧重解析——尾巴由 STABLE_STEP 封顶，
     * 所以单帧成本不再随正文长度上涨。
     *
     * 非单调的正文变化（重新生成把正文清空、回灌换了一条消息）一律推倒重来：判据是
     * 「长度变短」或「前缀末尾那 32 个字符对不上」，两条都是 O(1)。
     */
    renderNow() {
      const env = { copyLabel: t('chat.copyCode') }
      const text = this.sourceText
      if (!this.stableLength) this.stableLength = 0
      if (text.length < this.stableLength ||
          (this.stableLength > 0 && text.slice(this.stableLength - 32, this.stableLength) !== this.stableMark)) {
        this.stableLength = 0
        this.stableParts = []
        this.stableMark = ''
        this.stableHtml = ''
      }
      const advanced = nextStableLength(text, this.stableLength)
      if (advanced > this.stableLength) {
        if (!this.stableParts) this.stableParts = []
        this.stableParts.push(renderMarkdown(text.slice(this.stableLength, advanced), env))
        this.stableLength = advanced
        this.stableMark = text.slice(advanced - 32, advanced)
        this.stableHtml = this.stableParts.join('')
      }
      this.tailHtml = renderMarkdown(text.slice(this.stableLength), env)
      this.scheduleSettle()
    },
    /**
     * 定稿校正：正文不再变化之后整篇重渲一次，只有与分段结果不同才写回 DOM。
     * 切点规则挡住了绝大多数分段差异，挡不住的（尾巴里才出现的链接引用定义之类）
     * 在这里被改正；结果相同就一个字节都不动，用户刚选中的文字也就不会被清掉。
     */
    scheduleSettle() {
      if (!this.stableLength) return
      if (this.settleTimer != null) clearTimeout(this.settleTimer)
      this.settleTimer = setTimeout(() => {
        this.settleTimer = null
        const whole = renderMarkdown(this.sourceText, { copyLabel: t('chat.copyCode') })
        if (whole === this.stableHtml + this.tailHtml) return
        this.stableParts = [whole]
        this.stableLength = this.sourceText.length
        this.stableMark = this.sourceText.slice(-32)
        this.stableHtml = whole
        this.tailHtml = ''
      }, 400)
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

/* 两段容器不生成盒子：正文被切成「定稿前缀 + 尾巴」两个 v-html 之后，
   段落外边距要能照常穿过它们合并，否则切点处会多出一截空白（dev-board#811 K31）。 */
.markdown-body > .markdown-stable,
.markdown-body > .markdown-tail {
  display: contents;
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
