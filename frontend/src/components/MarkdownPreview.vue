<template>
  <view class="markdown-preview">
    <view v-if="loading" class="markdown-loading">
      <text>{{ $t('files.loadingDots') }}</text>
    </view>
    <view v-else class="markdown-body" v-html="displayedHtml"></view>
  </view>
</template>

<script>
import MarkdownIt from 'markdown-it'
import { getFileDownloadUrl } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'

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
    const md = new MarkdownIt({
      // 渲染结果直接进 v-html，而内容来自他人上传的 .md 与模型输出，
      // 放行原始 HTML 等于存储型 XSS，故禁用
      html: false,
      linkify: true,
      typographer: true
    })
    // 裸 <table> 没有滚动容器，宽表格会被上游面板的 overflow:hidden 直接裁掉且不出滚动条，
    // 这里包一层可横向滚动的 div（dev-board#467）
    md.renderer.rules.table_open = () => '<div class="md-table-scroll"><table>'
    md.renderer.rules.table_close = () => '</table></div>'
    return {
      md,
      loadedContent: '',
      loading: false
    }
  },
  computed: {
    displayedHtml() {
      // 优先使用直接传入的 content，其次使用从服务器加载的内容
      const text = this.content || this.loadedContent || ''
      return this.md.render(text)
    }
  },
  watch: {
    file: {
      immediate: true,
      handler(newFile) {
        if (newFile && !this.content) {
          this.loadFileContent()
        }
      }
    }
  },
  methods: {
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
