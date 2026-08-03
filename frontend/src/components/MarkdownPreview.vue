<template>
  <view class="markdown-preview">
    <view v-if="loading" class="markdown-loading">
      <text>正在加载...</text>
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
    return {
      md: new MarkdownIt({
        // 渲染结果直接进 v-html，而内容来自他人上传的 .md 与模型输出，
        // 放行原始 HTML 等于存储型 XSS，故禁用
        html: false,
        linkify: true,
        typographer: true
      }),
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
        this.loadedContent = `加载失败: ${e.message}`
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
  background: #fff;
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
  color: #666;
  font-size: 14px;
}

.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: #2c2c2c;
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
  color: #1a5336;
}

.markdown-body :deep(h1) {
  font-size: 20px;
  border-bottom: 1px solid #e9ecef;
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
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 13px;
}

.markdown-body :deep(pre) {
  background: #f9f9f9;
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
  border-left: 3px solid #1a5336;
  padding-left: 12px;
  margin: 12px 0;
  color: #666;
  font-style: italic;
}

.markdown-body :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #e9ecef;
  padding: 8px 12px;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #f5f5f5;
  font-weight: 600;
}


</style>
