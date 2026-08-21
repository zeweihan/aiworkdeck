<template>
  <view class="doc-diff-viewer">
    <!-- 工具栏 -->
    <view class="diff-toolbar">
      <view class="toolbar-left">
        <text class="toolbar-title">{{ $t('editor.diff.title') }}</text>
        <text class="toolbar-subtitle">{{ displaySourceName }} vs {{ displayTargetName }}</text>
      </view>
      <view class="toolbar-right">
        <view class="diff-nav">
          <button class="nav-btn" @tap="goToPrevDiff" :disabled="currentDiffIndex <= 0">
            <text>↑</text>
            <text class="nav-text">{{ $t('editor.diff.prev') }}</text>
          </button>
          <text class="diff-count">{{ currentDiffIndex + 1 }} / {{ totalDiffs }}</text>
          <button class="nav-btn" @tap="goToNextDiff" :disabled="currentDiffIndex >= totalDiffs - 1">
            <text>↓</text>
            <text class="nav-text">{{ $t('editor.diff.next') }}</text>
          </button>
        </view>
        <view class="view-toggle">
          <button 
            class="toggle-btn" 
            :class="{ active: viewMode === 'side' }"
            @tap="viewMode = 'side'"
          >
            {{ $t('editor.diff.sideBySide') }}
          </button>
          <button 
            class="toggle-btn" 
            :class="{ active: viewMode === 'inline' }"
            @tap="viewMode = 'inline'"
          >
            {{ $t('editor.diff.inline') }}
          </button>
        </view>
      </view>
    </view>
    
    <!-- Monaco Diff Editor 容器 -->
    <!-- #ifdef H5 -->
    <view class="diff-container" ref="diffContainer">
      <!-- 容器只能用模板 ref 取，不能挂全局 id：分栏模式下左右两个窗格会同时挂载
           两个 DocDiffViewer，硬编码 id 会让两边都拿到 DOM 里靠前的那一个容器，
           后挂载的实例把 Monaco 建进了别人的窗格。 -->
      <div ref="monacoContainer" class="monaco-container"></div>
    </view>
    <!-- #endif -->
    
    <!-- 非 H5 环境的简单文本对比 -->
    <!-- #ifndef H5 -->
    <view class="fallback-diff">
      <scroll-view scroll-y class="fallback-scroll">
        <view class="fallback-content">
          <view class="fallback-header">
            <text class="fallback-label source-label">{{ $t('editor.diff.sourceLabel', { name: displaySourceName }) }}</text>
            <text class="fallback-label target-label">{{ $t('editor.diff.targetLabel', { name: displayTargetName }) }}</text>
          </view>
          <view v-for="(line, idx) in diffLines" :key="idx" class="diff-line" :class="line.type">
            <text class="line-prefix">{{ line.prefix }}</text>
            <text class="line-content">{{ line.content }}</text>
          </view>
        </view>
      </scroll-view>
    </view>
    <!-- #endif -->
    
    <!-- Loading 状态 -->
    <view v-if="loading" class="loading-overlay">
      <view class="loading-spinner"></view>
      <text class="loading-text">{{ $t('editor.diff.loadingContent') }}</text>
    </view>
    
    <!-- 错误状态 -->
    <view v-if="error" class="error-overlay">
      <svg class="error-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
        <path v-for="(d, gi) in ICONS.warning" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <text class="error-text">{{ error }}</text>
      <button class="retry-btn" @tap="loadDocuments">{{ $t('editor.diff.retry') }}</button>
    </view>
  </view>
</template>

<script>
import { markRaw } from 'vue'
import api, { getVersionFileText } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { t } from '@/i18n'

export default {

  computed: {

    ICONS() { return ICONS },

    // versionSpec 模式下标题用「上一版/这一版」这类批准用语，忽略 sourceName/targetName
    displaySourceName() {
      return this.versionSpec ? (this.versionSpec.oldLabel || this.$t('editor.diff.prevVersion')) : this.sourceName
    },
    displayTargetName() {
      return this.versionSpec ? (this.versionSpec.newLabel || this.$t('editor.diff.thisVersion')) : this.targetName
    }

  },
  name: 'DocDiffViewer',
  props: {
    sourceId: {
      type: [Number, String],
      required: false
    },
    targetId: {
      type: [Number, String],
      required: false
    },
    sourceName: {
      type: String,
      default: () => t('editor.diff.sourceDocDefault')
    },
    targetName: {
      type: String,
      default: () => t('editor.diff.newDocDefault')
    },
    // 版本对比降级模式：设了它就走版本文本源（file-text 端点），忽略 sourceId/targetId
    versionSpec: {
      type: Object,
      default: null
    }
  },
  data() {
    return {
      loading: false,
      error: null,
      sourceText: '',
      targetText: '',
      viewMode: 'side', // 'side' | 'inline'
      monacoEditor: null,
      diffEditor: null,
      currentDiffIndex: 0,
      totalDiffs: 0,
      diffLines: [] // 用于非 H5 环境的简单 diff
    }
  },
  watch: {
    viewMode() {
      this.updateDiffEditor()
    }
  },
  mounted() {
    this.loadDocuments()
  },
  beforeUnmount() {
    this.disposeDiffEditor()
  },
  methods: {
    async loadDocuments() {
      this.loading = true
      this.error = null
      
      try {
        if (this.versionSpec) {
          // 版本对比降级：并行取上一版/这一版的抽取文本，不走 compareDocuments
          const { projectId, path, oldRef, newRef } = this.versionSpec
          const [oldRes, newRes] = await Promise.all([
            getVersionFileText(projectId, oldRef, path),
            getVersionFileText(projectId, newRef, path)
          ])

          if (oldRes.code !== 0 || newRes.code !== 0) {
            throw new Error(oldRes.message || newRes.message || this.$t('editor.diff.fetchVersionFailed'))
          }

          this.sourceText = (oldRes.data && oldRes.data.text) || ''
          this.targetText = (newRes.data && newRes.data.text) || ''
        } else {
          const res = await api.compareDocuments(this.sourceId, this.targetId)

          if (res.code !== 0) {
            throw new Error(res.message || this.$t('editor.diff.fetchDocFailed'))
          }

          this.sourceText = res.data.source.text || ''
          this.targetText = res.data.target.text || ''
        }

        // #ifdef H5
        await this.initMonacoDiffEditor()
        // #endif
        
        // #ifndef H5
        this.computeSimpleDiff()
        // #endif
        
      } catch (e) {
        console.error('加载文档失败:', e)
        this.error = e.message || this.$t('editor.diff.loadFailed')
      } finally {
        this.loading = false
      }
    },
    
    // #ifdef H5
    async initMonacoDiffEditor() {
      try {
        // 动态加载 Monaco Editor
        const monaco = await this.loadMonaco()
        
        const container = this.$refs.monacoContainer
        if (!container) {
          throw new Error(this.$t('editor.diff.containerMissing'))
        }
        
        // 创建 Diff Editor。markRaw 不能省：diffEditor 是 data 字段，直接赋值会让
        // Vue 把整个 Monaco 编辑器对象图递归包成 reactive proxy，Monaco 内部再去
        // 遍历自己的结构就爆 "Maximum call stack size exceeded"（真机 app-e2e 里
        // 抓到过 4 条 pageerror）。编辑器实例不需要响应式——模板一次都没用到它。
        this.diffEditor = markRaw(monaco.editor.createDiffEditor(container, {
          automaticLayout: true,
          readOnly: true,
          renderSideBySide: this.viewMode === 'side',
          originalEditable: false,
          enableSplitViewResizing: true,
          scrollBeyondLastLine: false,
          minimap: { enabled: false },
          lineNumbers: 'on',
          renderWhitespace: 'none',
          fontSize: 13,
          fontFamily: '"PingFang SC", "Microsoft YaHei", monospace',
          wordWrap: 'on'
        }))

        // 设置模型（存到 this 以便 dispose，否则反复开合对比会累积泄漏 Monaco text model）
        this._originalModel = monaco.editor.createModel(this.sourceText, 'plaintext')
        this._modifiedModel = monaco.editor.createModel(this.targetText, 'plaintext')

        this.diffEditor.setModel({
          original: this._originalModel,
          modified: this._modifiedModel
        })
        
        // 计算差异数量
        this.$nextTick(() => {
          this.computeDiffCount()
        })
        
      } catch (e) {
        console.error('初始化 Monaco 编辑器失败:', e)
        this.error = this.$t('editor.diff.initFailed', { msg: e.message })
      }
    },
    
    async loadMonaco() {
      // 检查是否已加载
      if (window.monaco) {
        return window.monaco
      }
      
      // 动态加载 Monaco
      return new Promise((resolve, reject) => {
        const script = document.createElement('script')
        script.src = 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs/loader.js'
        script.onload = () => {
          window.require.config({
            paths: { 'vs': 'https://cdn.jsdelivr.net/npm/monaco-editor@0.45.0/min/vs' }
          })
          window.require(['vs/editor/editor.main'], () => {
            resolve(window.monaco)
          })
        }
        script.onerror = () => reject(new Error(this.$t('editor.diff.monacoLoadFailed')))
        document.head.appendChild(script)
      })
    },
    
    updateDiffEditor() {
      if (!this.diffEditor) return
      
      this.diffEditor.updateOptions({
        renderSideBySide: this.viewMode === 'side'
      })
    },
    
    computeDiffCount() {
      if (!this.diffEditor) return
      
      try {
        const lineChanges = this.diffEditor.getLineChanges()
        this.totalDiffs = lineChanges ? lineChanges.length : 0
        this.currentDiffIndex = 0
      } catch (e) {
        this.totalDiffs = 0
      }
    },
    
    goToPrevDiff() {
      if (this.currentDiffIndex > 0) {
        this.currentDiffIndex--
        this.scrollToDiff(this.currentDiffIndex)
      }
    },
    
    goToNextDiff() {
      if (this.currentDiffIndex < this.totalDiffs - 1) {
        this.currentDiffIndex++
        this.scrollToDiff(this.currentDiffIndex)
      }
    },
    
    scrollToDiff(index) {
      if (!this.diffEditor) return
      
      try {
        const lineChanges = this.diffEditor.getLineChanges()
        if (lineChanges && lineChanges[index]) {
          const change = lineChanges[index]
          const line = change.modifiedStartLineNumber || change.originalStartLineNumber || 1
          this.diffEditor.revealLineInCenter(line)
        }
      } catch (e) {
        console.warn('滚动到差异位置失败:', e)
      }
    },
    
    disposeDiffEditor() {
      if (this.diffEditor) {
        this.diffEditor.dispose()
        this.diffEditor = null
      }
      if (this._originalModel) { this._originalModel.dispose(); this._originalModel = null }
      if (this._modifiedModel) { this._modifiedModel.dispose(); this._modifiedModel = null }
    },
    // #endif
    
    // 非 H5 环境的简单 diff 计算
    computeSimpleDiff() {
      const sourceLines = this.sourceText.split('\n')
      const targetLines = this.targetText.split('\n')
      const result = []
      
      // 简单的逐行对比
      const maxLen = Math.max(sourceLines.length, targetLines.length)
      let diffCount = 0
      
      for (let i = 0; i < maxLen; i++) {
        const sourceLine = sourceLines[i] || ''
        const targetLine = targetLines[i] || ''
        
        if (sourceLine === targetLine) {
          result.push({ type: 'unchanged', prefix: ' ', content: sourceLine })
        } else {
          if (sourceLine) {
            result.push({ type: 'removed', prefix: '-', content: sourceLine })
            diffCount++
          }
          if (targetLine) {
            result.push({ type: 'added', prefix: '+', content: targetLine })
            if (!sourceLine) diffCount++
          }
        }
      }
      
      this.diffLines = result
      this.totalDiffs = diffCount
    }
  }
}
</script>

<style scoped lang="scss">
.doc-diff-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
  position: relative;
}

.diff-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.toolbar-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.toolbar-subtitle {
  font-size: 12px;
  color: #64748b;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.diff-nav {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nav-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  font-size: 12px;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s;
}

.nav-btn:hover:not(:disabled) {
  background: #f1f5f9;
  border-color: #cbd5e1;
}

.nav-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.nav-text {
  font-size: 11px;
}

.diff-count {
  font-size: 12px;
  color: #64748b;
  min-width: 60px;
  text-align: center;
}

.view-toggle {
  display: flex;
  border: 1px solid #e2e8f0;
  border-radius: 4px;
  overflow: hidden;
}

.toggle-btn {
  padding: 4px 12px;
  background: #fff;
  border: none;
  font-size: 12px;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}

.toggle-btn:first-child {
  border-right: 1px solid #e2e8f0;
}

.toggle-btn.active {
  background: #3b82f6;
  color: #fff;
}

.toggle-btn:hover:not(.active) {
  background: #f1f5f9;
}

.diff-container {
  flex: 1;
  overflow: hidden;
}

.monaco-container {
  width: 100%;
  height: 100%;
  min-height: 400px;
}

/* 非 H5 的简单 diff 样式 */
.fallback-diff {
  flex: 1;
  overflow: hidden;
}

.fallback-scroll {
  height: 100%;
}

.fallback-content {
  padding: 12px;
}

.fallback-header {
  display: flex;
  gap: 20px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.fallback-label {
  font-size: 12px;
  font-weight: 500;
}

.source-label {
  color: #dc2626;
}

.target-label {
  color: #16a34a;
}

.diff-line {
  display: flex;
  font-family: monospace;
  font-size: 13px;
  line-height: 1.6;
  padding: 2px 8px;
}

.diff-line.unchanged {
  background: transparent;
  color: #374151;
}

.diff-line.removed {
  background: #fef2f2;
  color: #dc2626;
}

.diff-line.added {
  background: #f0fdf4;
  color: #16a34a;
}

.line-prefix {
  width: 20px;
  flex-shrink: 0;
  font-weight: 600;
}

.line-content {
  flex: 1;
  word-break: break-all;
}

/* Loading & Error */
.loading-overlay,
.error-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.95);
  z-index: 100;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #e2e8f0;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.loading-text {
  margin-top: 16px;
  font-size: 14px;
  color: #64748b;
}

.error-icon {

  width: 44px;
  height: 44px;
  flex-shrink: 0;
  margin-bottom: 12px;
}

.error-text {
  font-size: 14px;
  color: #dc2626;
  margin-bottom: 16px;
}

.retry-btn {
  padding: 8px 20px;
  background: #3b82f6;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.retry-btn:hover {
  background: #2563eb;
}
</style>




