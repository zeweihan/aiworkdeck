<template>
  <view class="ptx-editor">
    <view v-if="phase === 'loading'" class="ptx-status">
      <text class="ptx-status-text">{{ $t('editor.plainText.opening') }}</text>
    </view>

    <view v-else-if="phase === 'error'" class="ptx-status">
      <text class="ptx-status-text">{{ errorText || $t('editor.plainText.loadFailed') }}</text>
      <view class="ptx-btn" role="button" @tap="boot">{{ $t('editor.plainText.retry') }}</view>
    </view>

    <template v-else>
      <view class="ptx-bar">
        <text class="ptx-name">{{ file && file.name }}</text>
        <view v-if="isMarkdown" class="ptx-toggle">
          <view class="ptx-toggle-btn" :class="{ active: !previewMode }" role="button" @tap="setPreview(false)">
            {{ $t('editor.plainText.edit') }}
          </view>
          <view class="ptx-toggle-btn" :class="{ active: previewMode }" role="button" @tap="setPreview(true)">
            {{ $t('editor.plainText.preview') }}
          </view>
        </view>
        <view class="ptx-bar-right">
          <!-- 保存状态：成功不出声（同 LOWA 口径，成功提示只会闪变打扰）；
               「未保存」小字常显、失败醒目并给当场重试。 -->
          <view v-if="saveFailed" class="ptx-save-failed" role="button" @tap="save">
            {{ $t('editor.plainText.saveFailedRetry') }}
          </view>
          <text v-else-if="dirty || saving" class="ptx-dirty">{{ $t('editor.plainText.unsaved') }}</text>
        </view>
      </view>
      <!-- CodeMirror 挂载点必须是真实 DOM（div 而非 uni view），预览时 v-show 藏住
           而不销毁——切回编辑要保留光标/滚动/撤销栈 -->
      <div v-show="!previewMode" ref="cmHost" class="ptx-cm-host"></div>
      <view v-if="previewMode" class="ptx-preview">
        <view class="markdown-body" v-html="previewHtml"></view>
      </view>
    </template>
  </view>
</template>

<script>
// 纯文本轻量编辑器（dev-board#37）：txt / md / markdown 不再进 LOWA WASM 引擎
// （150MB 引擎 boot 十几秒、Writer 语义对纯文本毫无意义），改走 CodeMirror 6。
//
// 契约与 LOWA 编辑器（LibreOfficeEditor.vue）对齐的三件事：
// 1. 存取走同一套通用字节接口（GET /download、POST /upload multipart）——上传成功
//    后端 signalChange 自动接上版本记录，这里不需要任何额外接线；
// 2. 下载失败/可疑空下载即封保存（朴素版 docLoadFailed 闸，PR#194 同款事故的预防）；
// 3. reloadFromBackend()：版本退回/AI text_* 直改后，宿主命令就地重载，丢弃本地
//    未保存态（版本操作以后端为准），否则下一次自动保存会把旧内容写回去。
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { markdown } from '@codemirror/lang-markdown'
import { javascript } from '@codemirror/lang-javascript'
import { json } from '@codemirror/lang-json'
import { html } from '@codemirror/lang-html'
import { css } from '@codemirror/lang-css'
import MarkdownIt from 'markdown-it'
import { getFileDownloadUrl, getFileUploadUrl } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'

const AUTOSAVE_DELAY = 3000
const RETRY_DELAY = 15000

export default {
  name: 'PlainTextEditor',
  props: {
    file: { type: Object, default: null },
    projectId: { type: [String, Number], default: null }
  },
  data() {
    return {
      phase: 'loading',      // loading | ready | error
      errorText: '',
      dirty: false,
      saving: false,
      saveFailed: false,
      previewMode: false,
      previewHtml: ''
    }
  },
  computed: {
    isMarkdown() {
      const t = this.file && this.file.fileType ? String(this.file.fileType).toLowerCase() : ''
      return t === 'md' || t === 'markdown'
    }
  },
  mounted() {
    // 非响应式内部状态（EditorView 进响应式代理会被 Proxy 拖垮且无意义）
    this._view = null
    this._seq = 0              // 每次用户编辑 +1；保存完成时对账判定期间有没有新输入
    this._saveTimer = null
    this._retryTimer = null
    this._applyingRemote = false
    this._loadOk = false
    this._md = null
    this.boot()
  },
  beforeUnmount() {
    clearTimeout(this._saveTimer)
    clearTimeout(this._retryTimer)
    // 标签切走/关闭时的最后防线：同步取走内容，异步发出去（组件销毁不影响 XHR）。
    // closeFile 的显式 flushSave 分支是主路径，这里兜住"切到别的标签"这种不经
    // closeFile 的卸载——v-if 单实例意味着切标签就是销毁。
    if (this.dirty && !this.saving && this._loadOk && this._view) {
      const content = this._view.state.doc.toString()
      this.uploadContent(content).catch((e) => {
        console.warn('[PlainTextEditor] unmount flush-save failed:', e)
      })
    }
    if (this._view) { this._view.destroy(); this._view = null }
  },
  methods: {
    // 按扩展名选语言包（dev-board#61 插件开发形态：js/json/html/css 补高亮）。
    // yml/yaml/txt 没有对应语言包，走纯文本，仍有行号/撤销/查找替换等通用能力。
    languageExtension() {
      const t = this.file && this.file.fileType ? String(this.file.fileType).toLowerCase() : ''
      if (t === 'md' || t === 'markdown') return markdown()
      if (t === 'js' || t === 'mjs') return javascript()
      if (t === 'json') return json()
      if (t === 'html' || t === 'htm') return html()
      if (t === 'css') return css()
      return null
    },

    fileRef() {
      const f = this.file
      if (!f) return null
      // 数字主键优先：wpsFileId 是自由字段，撞号时后端 findFirst 会取错文件（同 DrawioEditor）
      return f.id != null ? f.id : (f.wpsFileId || null)
    },

    async boot() {
      this.phase = 'loading'
      this.errorText = ''
      this.dirty = false
      this.saveFailed = false
      this._loadOk = false
      clearTimeout(this._saveTimer)
      clearTimeout(this._retryTimer)
      if (this._view) { this._view.destroy(); this._view = null }
      try {
        const text = await this.download()
        this._loadOk = true
        this.phase = 'ready'
        await this.$nextTick()
        this.mountEditor(text)
        if (this.previewMode) this.renderPreview()
      } catch (e) {
        this.errorText = (e && e.message) || this.$t('editor.plainText.loadFailed')
        this.phase = 'error'
      }
    },

    async download() {
      const id = this.fileRef()
      if (!id) throw new Error(this.$t('editor.plainText.fileMissing'))
      const res = await fetch(getFileDownloadUrl(id), { headers: getAuthHeaders() || {} })
      if (!res.ok) throw new Error(this.$t('editor.plainText.readFailed', { status: res.status }))
      const text = await res.text()
      // 空下载闸（PR#194 同款）：元数据说有内容、下载却是空的——多半是网络/存储
      // 异常，此时装载空白再自动保存等于把真文件清空。宁可报错也不冒这个险。
      const meta = this.file && this.file.fileSize
      if (meta > 0 && text.length === 0) {
        throw new Error(this.$t('editor.plainText.emptyDownload'))
      }
      return text
    },

    mountEditor(text) {
      const host = this.$refs.cmHost
      if (!host) return
      const extensions = [
        lineNumbers(),
        highlightActiveLine(),
        highlightActiveLineGutter(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        EditorView.lineWrapping,
        syntaxHighlighting(defaultHighlightStyle),
        EditorView.updateListener.of((update) => {
          if (update.docChanged && !this._applyingRemote) this.onUserEdit()
        }),
        EditorView.theme({
          '&': { height: '100%', fontSize: '13px', backgroundColor: '#ffffff' },
          '.cm-scroller': {
            fontFamily: "'SF Mono', Menlo, Consolas, 'PingFang SC', 'Microsoft YaHei', monospace",
            lineHeight: '1.7'
          },
          '.cm-content': { padding: '12px 0', caretColor: '#1a5336' },
          '.cm-gutters': { backgroundColor: '#fafbfc', color: '#9aa0a8', border: 'none', borderRight: '1px solid #f0f1f3' },
          '.cm-activeLine': { backgroundColor: 'rgba(26, 83, 54, 0.04)' },
          '.cm-activeLineGutter': { backgroundColor: 'rgba(26, 83, 54, 0.06)' },
          '&.cm-focused': { outline: 'none' }
        })
      ]
      const lang = this.languageExtension()
      if (lang) extensions.push(lang)
      this._view = new EditorView({
        state: EditorState.create({ doc: text, extensions }),
        parent: host
      })
    },

    onUserEdit() {
      this._seq++
      this.dirty = true
      this.saveFailed = false
      this.scheduleSave()
    },

    scheduleSave() {
      clearTimeout(this._saveTimer)
      this._saveTimer = setTimeout(() => { this._saveTimer = null; this.save() }, AUTOSAVE_DELAY)
    },

    getText() {
      return this._view ? this._view.state.doc.toString() : ''
    },

    getSelectionText() {
      if (!this._view) return ''
      const sel = this._view.state.selection.main
      return sel.empty ? '' : this._view.state.sliceDoc(sel.from, sel.to)
    },

    async save() {
      if (!this._loadOk || this.phase !== 'ready' || !this._view) return false
      if (this.saving) { this.scheduleSave(); return false }
      const seq = this._seq
      const content = this._view.state.doc.toString()
      this.saving = true
      try {
        await this.uploadContent(content)
        this.saveFailed = false
        // 保存期间又有输入的话保持脏、让防抖继续跑；没有才清脏
        if (this._seq === seq) this.dirty = false
        else this.scheduleSave()
        return true
      } catch (e) {
        console.warn('[PlainTextEditor] save failed:', e)
        this.saveFailed = true
        clearTimeout(this._retryTimer)
        this._retryTimer = setTimeout(() => { this._retryTimer = null; if (this.dirty) this.save() }, RETRY_DELAY)
        return false
      } finally {
        this.saving = false
      }
    },

    uploadContent(content) {
      const id = this.fileRef()
      if (!id) return Promise.reject(new Error('no file id'))
      const mime = this.isMarkdown ? 'text/markdown' : 'text/plain'
      const blob = new Blob([content], { type: mime + ';charset=utf-8' })
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const form = new FormData()
        form.append('file', blob, (this.file && this.file.name) || 'untitled.txt')
        const xhr = new XMLHttpRequest()
        xhr.open('POST', getFileUploadUrl(id), true)
        xhr.timeout = 60000
        Object.keys(headers).forEach((k) => { if (k.toLowerCase() !== 'content-type') xhr.setRequestHeader(k, headers[k]) })
        xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status)))
        xhr.onerror = () => reject(new Error('network error'))
        xhr.ontimeout = () => reject(new Error('timeout'))
        xhr.send(form)
      })
    },

    /** 关闭前落盘（closeFile 的文本分支调用）：取消防抖、等在途保存、脏则立即存。 */
    async flushSave() {
      clearTimeout(this._saveTimer)
      this._saveTimer = null
      for (let i = 0; i < 100 && this.saving; i++) {
        await new Promise((r) => setTimeout(r, 100))
      }
      if (this.dirty) await this.save()
    },

    /**
     * 后端就地改了文件之后的重载（版本退回 / 检查点恢复 / AI text_* 直改）。
     * 丢弃本地未保存态——这条链上的动作以后端为准，保留本地脏内容等于让下一次
     * 自动保存把操作结果冲掉（LOWA reloadFromBackend 的同款数据事故，PR#218）。
     * 失败则转入 error 相位封死保存（画布内容已不可信），用户可点重试。
     */
    async reloadFromBackend() {
      clearTimeout(this._saveTimer)
      this._saveTimer = null
      clearTimeout(this._retryTimer)
      this._retryTimer = null
      this.dirty = false
      this.saveFailed = false
      for (let i = 0; i < 100 && this.saving; i++) {
        await new Promise((r) => setTimeout(r, 100))
      }
      try {
        const text = await this.download()
        if (this._view) {
          this._applyingRemote = true
          try {
            this._view.dispatch({ changes: { from: 0, to: this._view.state.doc.length, insert: text } })
          } finally {
            this._applyingRemote = false
          }
        } else if (this.phase !== 'ready') {
          // 之前就没加载成功：这次全量重走 boot
          await this.boot()
          return this.phase === 'ready'
        }
        this._seq++
        this.dirty = false
        if (this.previewMode) this.renderPreview()
        return true
      } catch (e) {
        console.warn('[PlainTextEditor] reload failed:', e)
        this._loadOk = false
        this.errorText = (e && e.message) || this.$t('editor.plainText.loadFailed')
        this.phase = 'error'
        return false
      }
    },

    setPreview(on) {
      if (this.previewMode === !!on) return
      this.previewMode = !!on
      if (on) this.renderPreview()
    },

    renderPreview() {
      if (!this._md) {
        // html:false —— 渲染结果直接进 v-html，放行原始 HTML 等于存储型 XSS（同 MarkdownPreview）
        this._md = new MarkdownIt({ html: false, linkify: true, typographer: true })
      }
      this.previewHtml = this._md.render(this.getText())
    }
  }
}
</script>

<style scoped>
.ptx-editor {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #ffffff;
}

.ptx-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 12px;
  border-bottom: 1px solid #ebedf0;
  flex-shrink: 0;
}
.ptx-name {
  font-size: 12px;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ptx-bar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.ptx-dirty { font-size: 11px; color: #8a5a2b; }
.ptx-save-failed {
  font-size: 11px;
  color: #b42318;
  border: 1px solid #f0c4bf;
  background: #fdf3f2;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
  user-select: none;
}

.ptx-toggle { display: flex; border: 1px solid #e3e6ea; border-radius: 5px; overflow: hidden; }
.ptx-toggle-btn {
  font-size: 11px;
  padding: 3px 10px;
  color: #4a5058;
  background: #fff;
  cursor: pointer;
  user-select: none;
}
.ptx-toggle-btn + .ptx-toggle-btn { border-left: 1px solid #e3e6ea; }
.ptx-toggle-btn.active { background: #e6f9f0; color: #1a5336; }

.ptx-cm-host {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* CodeMirror 根元素在 scoped 之外（运行时插入），穿透设置高度 */
.ptx-cm-host :deep(.cm-editor) { height: 100%; }

.ptx-preview {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px;
}

.ptx-status {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px;
}
.ptx-status-text { font-size: 13px; color: #4a5058; text-align: center; }
.ptx-btn {
  padding: 6px 16px;
  font-size: 12px;
  border-radius: 5px;
  border: 1px solid #e3e6ea;
  color: #4a5058;
  background: #fff;
  cursor: pointer;
  user-select: none;
}
.ptx-btn:hover { border-color: #c9ced6; background: #fafbfc; }

/* Markdown 预览排版：与 MarkdownPreview.vue 同一视觉词汇 */
.markdown-body {
  font-size: 14px;
  line-height: 1.7;
  color: #2c2c2c;
  word-wrap: break-word;
  overflow-wrap: break-word;
  user-select: text;
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
.markdown-body :deep(h1) { font-size: 20px; border-bottom: 1px solid #e9ecef; padding-bottom: 8px; }
.markdown-body :deep(h2) { font-size: 17px; }
.markdown-body :deep(h3) { font-size: 15px; }
.markdown-body :deep(p) { margin: 8px 0; }
.markdown-body :deep(ul),
.markdown-body :deep(ol) { padding-left: 20px; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(code) {
  background: #f5f5f5;
  padding: 2px 6px;
  border-radius: 4px;
  font-family: 'Menlo', 'Monaco', monospace;
  font-size: 13px;
}
.markdown-body :deep(pre) { background: #f9f9f9; padding: 12px; border-radius: 6px; overflow-x: auto; margin: 12px 0; }
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid #1a5336;
  padding-left: 12px;
  margin: 12px 0;
  color: #666;
  font-style: italic;
}
.markdown-body :deep(table) { width: 100%; border-collapse: collapse; margin: 12px 0; }
.markdown-body :deep(th),
.markdown-body :deep(td) { border: 1px solid #e9ecef; padding: 8px 12px; text-align: left; }
.markdown-body :deep(th) { background: #f5f5f5; font-weight: 600; }
</style>
