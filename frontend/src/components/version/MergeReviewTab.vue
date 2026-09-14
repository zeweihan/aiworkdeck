<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  合并比对稿标签页（dev-board#630，spec §5.4）。

  律师看到的是一份可编辑的稿：主线侧的改动是一批带作者的修订，另一侧不重叠的
  改动被逐段重放成另一位作者的修订，同一段两边都改了的那几处**刻意没重放**，
  交给右栏三选一。保存即裁决——「完成裁决」把这份稿导出交给后端落盘。

  与 VersionCompareTab 的区别（两者都不是 LibreOfficeEditor，都不碰自动保存链）：
  - 那个是只读的两版对照，本组件是**可编辑**的合并稿，但同样**没有 upload 路径**：
    导出的字节只走 `POST /version/merge/resolve-file` 进合并待决记录，绝不写回
    ProjectFile——写回去就等于在裁决还没收尾时把冲突态的文件覆盖掉。
  - 不派发 `.uno:EditDoc`（spike A5 实测该命令在 r5 上根本不生效，只读靠的是
    「没有保存路径」）。
  - 不进保活池，随标签页关闭即销毁。

  布局照 VersionCompareTab / LibreOfficeEditor 的 overlay 形制：<webview> 宿主容器
  **始终**渲染，状态卡片盖在它上面——放进 v-show 里 Electron 的 <webview> 在隐藏
  子树中常常不 attach，dom-ready 永远不来。
-->
<template>
  <view class="mrt-root">
    <!-- 顶部说明条：这份稿是什么、两边各改了多少、两侧版本是谁什么时候的 -->
    <view class="mrt-bar">
      <view class="mrt-bar-main">
        <text class="mrt-title">{{ headline }}</text>
        <text class="mrt-sides">{{ sidesLine }}</text>
      </view>
      <view class="mrt-bar-acts">
        <text v-if="!finished" class="mrt-btn primary" :class="{ off: !canFinish }" @tap="finish">{{ finishLabel }}</text>
        <text class="mrt-btn" @tap="$emit('close-tab')">{{ finished ? $t('version.mergeCloseTab') : $t('version.mergeLater') }}</text>
      </view>
    </view>

    <view v-if="finished" class="mrt-done">{{ $t('version.mergeResolvedGoBack') }}</view>
    <view v-if="error" class="mrt-error">{{ error }}</view>

    <view class="mrt-body">
      <view class="mrt-canvas">
        <view :id="hostId" class="mrt-host"></view>
        <view v-show="!ready" class="mrt-status">
          <text>{{ statusText }}</text>
        </view>
      </view>
      <ReviewPanel
        v-if="ready && !readonly"
        ref="panel"
        mode="merge"
        :executor="executor"
        :refresh-key="refreshKey"
        :merge-conflicts="conflicts"
        :merge-format-only="formatOnly"
        :main-author="mainAuthor"
        :other-author="otherAuthor"
        :main-label="mainLabel"
        :other-label="otherLabel"
        :main-when="mainWhen"
        :other-when="otherWhen"
        @close="$emit('close-tab')"
        @merge-state="onMergeState"
        @open-other-version="onOpenOtherVersion"
      />
    </view>
  </view>
</template>

<script>
import ReviewPanel from '@/components/ReviewPanel.vue'
import { createWebviewEditorExecutor, createIframeEditorExecutor } from '@/composables/useZetaOfficeWebview.js'
import { fetchVersionFileBytes, postMergeResolveFile, getFileDownloadUrl } from '@/services/api.js'
import { fetchMergeInputs, buildMergeDraft } from '@/services/mergeDraft.js'
import { collectDecisions } from '@/utils/mergeReviewDecisions.js'
import { getAuthHeaders } from '@/utils/auth.js'
import { host } from '@/services/host.js'
import { formatDateTime } from '@/utils/projectHomeFormat.js'

export const MERGE_FILE_RESOLVED_EVENT = 'awd:merge-file-resolved'

let seq = 0

export default {
  name: 'MergeReviewTab',
  components: { ReviewPanel },
  emits: ['close-tab', 'open-version-compare'],
  props: {
    // {projectId, path, name, ctx, mergeBase, mainRef, otherRef,
    //  sides: {main: {authorName, when, title, self}, other: {…}}, readonly, fileId}
    mergeSpec: { type: Object, required: true },
  },
  data() {
    return {
      hostId: 'mrt-host-' + (++seq),
      ready: false,
      statusText: '',
      error: '',
      executor: null,
      webviewEl: null,
      refreshKey: 0,
      conflicts: [],
      formatOnly: [],
      mainCount: 0,
      otherCount: 0,
      pendingConflicts: 0,
      pendingRevisions: 0,
      mergeState: { conflictChoices: [], revisionOutcomes: [] },
      finished: false,
      finishing: false,
    }
  },
  computed: {
    readonly() { return !!this.mergeSpec.readonly },
    sides() { return this.mergeSpec.sides || {} },
    mainAuthor() { return (this.sides.main && this.sides.main.authorName) || '' },
    otherAuthor() { return (this.sides.other && this.sides.other.authorName) || '' },
    // 界面只说展示名，永远不显示 username；本人那一侧说「你」。
    mainLabel() {
      const s = this.sides.main || {}
      return s.self ? this.$t('version.actorYou') : (s.authorName || this.$t('version.mergeSideMainDefault'))
    },
    otherLabel() {
      const s = this.sides.other || {}
      return s.self ? this.$t('version.actorYou') : (s.authorName || this.$t('version.mergeSideOtherDefault'))
    },
    // 每处改动的时间 = 那一侧版本的提交时间（引擎给的修订日期是比较时刻，不能用）。
    mainWhen() { return this.whenLine(this.sides.main) },
    otherWhen() { return this.whenLine(this.sides.other) },
    headline() {
      if (this.readonly) return this.$t('version.mergeHeadlineReadonly', { name: this.mergeSpec.name })
      return this.$t('version.mergeHeadline', {
        name: this.mergeSpec.name,
        main: this.mainLabel, mainCount: this.mainCount,
        other: this.otherLabel, otherCount: this.otherCount,
        conflicts: this.conflicts.length,
      })
    },
    sidesLine() {
      const parts = []
      if (this.sides.main) parts.push(this.$t('version.mergeSideInfo', { side: this.mainLabel, when: this.mainWhen, title: this.sides.main.title || '' }))
      if (this.sides.other) parts.push(this.$t('version.mergeSideInfo', { side: this.otherLabel, when: this.otherWhen, title: this.sides.other.title || '' }))
      return parts.join('　')
    },
    // 块 1（同一段两边都改了）全部处理完才可点——那几处是律师唯一必须亲自决定的。
    canFinish() { return this.ready && !this.finishing && !this.finished && this.pendingConflicts === 0 },
    finishLabel() {
      if (this.finishing) return this.$t('version.mergeFinishing')
      if (this.pendingConflicts > 0) return this.$t('version.mergeFinishBlocked', { count: this.pendingConflicts })
      if (this.pendingRevisions > 0) return this.$t('version.mergeFinishAcceptRest', { count: this.pendingRevisions })
      return this.$t('version.mergeFinish')
    },
  },
  async mounted() {
    this.statusText = this.$t('version.mergePreparing')
    try {
      const api = host.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusText = this.$t('version.compareTabUnsupportedEnv')
        return
      }
      const info = await api.getEditor()
      await this.mountEditor(info)
      if (this.readonly) await this.loadMergedForReview()
      else await this.loadDraft()
      this.ready = true
    } catch (e) {
      console.warn('[MergeReview] 失败', e)
      this.statusText = (e && e.message) || this.$t('version.mergeBuildFailed')
    }
  },
  beforeUnmount() {
    if (this._bootTimeout) clearTimeout(this._bootTimeout)
    try { if (this.executor && typeof this.executor.dispose === 'function') this.executor.dispose() } catch (e) { /* ignore */ }
    try { if (this.webviewEl && this.webviewEl.remove) this.webviewEl.remove() } catch (e) { /* ignore */ }
    this.webviewEl = null
    this.executor = null
  },
  methods: {
    run(action, payload) { return this.executor.executeCommand(action, payload || {}) },
    whenLine(side) {
      if (!side) return ''
      // 后端给的是 ISO 时间戳（Instant，带 Z），律师读不懂——换成与左栏时间线 /
      // 提交历史标签页同一个格式化函数（src/utils/projectHomeFormat.js），
      // 不要在这里再拼一份新的日期措辞。
      return formatDateTime(side.when)
    },
    // 造合并比对稿：三份字节 + 后端算好的重放计划 → 一条 build_merge_draft 命令。
    async loadDraft() {
      const spec = this.mergeSpec
      this.statusText = this.$t('version.mergeFetchingVersions')
      const inputs = await fetchMergeInputs(spec.projectId, spec.path, {
        mergeBase: spec.mergeBase, mainRef: spec.mainRef, otherRef: spec.otherRef,
      })
      this.statusText = this.$t('version.mergeBuilding')
      const res = await buildMergeDraft((a, p) => this.run(a, p), inputs, {
        mainAuthor: this.mainAuthor, otherAuthor: this.otherAuthor, name: spec.name,
      })
      if (!res || res.success !== true) {
        // stage:'align' 等失败不在这里兜底重来——退回整份三选一是裁决总览的事，
        // 这里只如实说原因，别让律师对着一份对不齐的稿逐处裁决。
        const stage = res && res.stage ? res.stage : ''
        throw new Error((res && res.message) || this.$t('version.mergeBuildFailedStage', { stage: stage || '-' }))
      }
      this.mainCount = res.mainCount || 0
      this.otherCount = res.otherCount || 0
      this.formatOnly = res.formatOnly || []
      // 三栏文字（共同的上一版 / 你的 / 律师乙的）只有后端有——引擎里这几段刻意
      // 没重放另一侧，从文档里读不出对方那一栏。
      const overlaps = (inputs.analysis && inputs.analysis.overlaps) || []
      const conflictKeys = new Set(res.conflicts || [])
      this.conflicts = overlaps.filter((o) => !conflictKeys.size || conflictKeys.has(o.key))
      // 修订视图默认「全部」：律师要同时看见两边改了什么。
      await this.run('set_revision_view', { mode: 'all' }).catch(() => {})
      this.refreshKey++
    },
    // 「查看合并稿」：已经裁决完的那份，和共同的上一版比一次，只读看。
    async loadMergedForReview() {
      const spec = this.mergeSpec
      this.statusText = this.$t('version.mergeFetchingVersions')
      const baseBytes = await fetchVersionFileBytes(spec.projectId, spec.mergeBase, spec.path)
      const mergedBytes = await this.fetchMergedBytes()
      const loaded = await this.run('load_document', {
        bytes: mergedBytes, name: spec.name, authorName: this.$t('version.compareAuthorName'),
      })
      if (!loaded || loaded.success === false) throw new Error(this.$t('version.compareLoadNewFailed'))
      const cmp = await this.run('compare_document', { baseBytes })
      if (!cmp || cmp.success !== true) throw new Error(this.$t('version.compareGenerateFailed'))
    },
    async fetchMergedBytes() {
      const spec = this.mergeSpec
      // 合并稿此刻只在工作区里（还没落成版本），所以按 ProjectFile 取当前字节。
      if (!spec.fileId) throw new Error(this.$t('version.mergeReadonlyNeedsFile'))
      const resp = await fetch(getFileDownloadUrl(spec.fileId), { headers: getAuthHeaders() })
      if (!resp.ok) throw new Error(this.$t('version.compareFetchBytesFailed'))
      return new Uint8Array(await resp.arrayBuffer())
    },
    onMergeState(state) {
      this.mergeState = { conflictChoices: state.conflictChoices, revisionOutcomes: state.revisionOutcomes }
      this.pendingConflicts = state.pendingConflicts
      this.pendingRevisions = state.pendingRevisions
    },
    // 块 3 的「查看律师乙那一版」：开既有的版本对比标签（共同的上一版 → 另一侧），
    // 律师照着手动套格式——文字重放带不过来格式，这是已知限制。
    onOpenOtherVersion() {
      const spec = this.mergeSpec
      this.$emit('open-version-compare', {
        projectId: spec.projectId, path: spec.path, name: spec.name,
        newRef: spec.otherRef, oldRef: spec.mergeBase,
        oldLabel: this.$t('version.mergeBaseSideLabel'), newLabel: this.otherLabel,
      })
    },
    /**
     * 完成裁决：剩下没处理的修订全部接受（未拒绝即保留，Word 的默认语义）→ 导出 →
     * 交给后端落盘 + 记待决记录。**不上传到 ProjectFile**，收尾仍由裁决总览做。
     */
    async finish() {
      if (!this.canFinish || this.finishing) return
      this.finishing = true
      this.error = ''
      try {
        const panel = this.$refs.panel
        const state = panel ? await panel.acceptRemainingRevisions() : this.mergeState
        const decisions = collectDecisions({
          conflictChoices: state.conflictChoices,
          revisionOutcomes: state.revisionOutcomes,
          formatOnly: this.formatOnly,
        })
        const exported = await this.run('export_document', { name: this.mergeSpec.name })
        if (!exported || exported.success !== true) throw new Error(this.$t('version.mergeExportFailed'))
        const bytes = this.normalizeBytes(exported.bytes)
        if (!bytes || !bytes.length) throw new Error(this.$t('version.mergeExportFailed'))
        await postMergeResolveFile(this.mergeSpec.projectId, {
          path: this.mergeSpec.path, mode: 'manual', decisions, bytes, name: this.mergeSpec.name,
        })
        this.finished = true
        uni.$emit(MERGE_FILE_RESOLVED_EVENT, { path: this.mergeSpec.path })
      } catch (e) {
        this.error = (e && e.message) || this.$t('version.mergeResolveFailed')
      } finally {
        this.finishing = false
      }
    },
    // 结构化克隆过中继后字节可能是 Uint8Array / ArrayBuffer / 普通数组
    // （与 LibreOfficeEditor.autoSave 同一套归一）。
    normalizeBytes(raw) {
      if (raw instanceof Uint8Array) return raw
      if (raw instanceof ArrayBuffer) return new Uint8Array(raw)
      if (raw && raw.buffer instanceof ArrayBuffer) return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength)
      if (Array.isArray(raw)) return new Uint8Array(raw)
      return null
    },
    // 与 VersionCompareTab.mountEditor 同一套两段式握手：dom-ready 只说明 webview
    // 渲染进程起来了，onReady 才说明 office 端点起来、命令不会被丢。
    mountEditor(info) {
      return new Promise((resolve, reject) => {
        const mountEl = document.getElementById(this.hostId)
        if (!mountEl) { reject(new Error('host missing')); return }
        this._bootTimeout = setTimeout(() => {
          reject(new Error(this.$t('version.compareTimeout')))
        }, 150000)
        const onReady = () => { clearTimeout(this._bootTimeout); resolve() }
        let el
        if (info.kind === 'iframe') {
          el = document.createElement('iframe')
          try {
            this.executor = createIframeEditorExecutor(el, { onReady })
          } catch (e) { clearTimeout(this._bootTimeout); reject(e); return }
          el.addEventListener('error', () => {
            clearTimeout(this._bootTimeout)
            reject(new Error(this.$t('version.compareIframeLoadFailed')))
          })
          el.src = info.url
        } else {
          el = document.createElement('webview')
          el.setAttribute('partition', info.partition)
          if (info.preload) el.setAttribute('preload', info.preload)
          el.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no')
          el.addEventListener('dom-ready', () => {
            if (this.executor) return // 页内导航会让 dom-ready 重复触发
            try {
              this.executor = createWebviewEditorExecutor(el, { onReady })
            } catch (e) { clearTimeout(this._bootTimeout); reject(e) }
          })
          el.addEventListener('did-fail-load', (e) => {
            clearTimeout(this._bootTimeout)
            reject(new Error('did-fail-load: ' + (e.errorDescription || e.errorCode)))
          })
          el.src = info.url
        }
        el.style.width = '100%'; el.style.height = '100%'; el.style.border = '0'
        mountEl.appendChild(el)
        this.webviewEl = el
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.mrt-root { display: flex; flex-direction: column; height: 100%; background: var(--awd-bg); }
.mrt-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 8px 12px; border-bottom: 1px solid var(--awd-border); background: var(--awd-surface); }
.mrt-bar-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.mrt-title { font-size: 13px; color: var(--awd-text); font-weight: 600; }
.mrt-sides { font-size: 11px; color: var(--awd-text-3); }
.mrt-bar-acts { display: flex; gap: 8px; flex: none; }
.mrt-btn { padding: 4px 12px; border: 1px solid var(--awd-border); border-radius: 6px;
  font-size: 12px; color: var(--awd-text-2); background: var(--awd-bg); }
.mrt-btn.primary { border-color: var(--awd-accent); color: var(--awd-accent-text); background: var(--awd-accent-soft); }
.mrt-btn.off { opacity: 0.5; }
.mrt-done { padding: 6px 12px; background: var(--awd-accent-wash); color: var(--awd-accent-text); font-size: 12px; }
.mrt-error { padding: 6px 12px; background: var(--awd-danger-soft); color: var(--awd-danger-text); font-size: 12px; }
.mrt-body { flex: 1; min-height: 0; display: flex; }
.mrt-canvas { position: relative; flex: 1; min-width: 0; }
.mrt-host { position: absolute; inset: 0; }
/* 状态卡片是叠在宿主上的覆盖层，不是它的兄弟占位块——宿主必须一直有真实尺寸 */
.mrt-status { position: absolute; inset: 0; z-index: 2;
  display: flex; align-items: center; justify-content: center;
  background: var(--awd-surface); color: var(--awd-text-3); font-size: 13px; }
</style>
