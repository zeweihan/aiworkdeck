<template>
  <view class="ip">
    <!-- 头部：当前文档 + 解析按钮 + run 状态。面板**不自画标题**（左栏由外壳的
         .sidebar-header 出，右栏由 dock tab 出）——这里只画「对哪份文档、跑到哪一步」。 -->
    <view class="ip-head">
      <text class="ip-doc" :title="docName || ''">{{ docName || $t('insight.noDoc') }}</text>
      <text
        class="ip-parse"
        :class="{ disabled: !canParse }"
        @tap="onParseTap"
      >{{ run ? $t('insight.reparse') : $t('insight.parse') }}</text>
    </view>

    <view v-if="runLine" class="ip-run" :class="runClass">
      <view v-if="isRunning" class="ip-spin"></view>
      <text class="ip-run-t">{{ runLine }}</text>
    </view>
    <view v-if="error" class="ip-error">{{ error }}</view>

    <view class="ip-tabs">
      <text class="ip-tab" :class="{ on: tab === 'retrieval' }" @tap="tab = 'retrieval'">
        {{ $t('insight.tab.retrieval') }}<text v-if="entities.length" class="ip-tab-n">{{ entities.length }}</text>
      </text>
      <text class="ip-tab" :class="{ on: tab === 'checks' }" @tap="tab = 'checks'">
        {{ $t('insight.tab.checks') }}<text v-if="findings.length" class="ip-tab-n">{{ findings.length }}</text>
      </text>
    </view>

    <!-- ————————————————— 外部检索 ————————————————— -->
    <scroll-view v-show="tab === 'retrieval'" class="ip-body" scroll-y>
      <view v-if="!run && !loading" class="ip-empty">
        <text class="ip-empty-t">{{ $t('insight.empty.noRun') }}</text>
        <text class="ip-empty-s">{{ $t('insight.empty.noRunHint') }}</text>
      </view>
      <view v-else-if="loading && !entities.length" class="ip-empty">
        <text class="ip-empty-s">{{ $t('insight.loading') }}</text>
      </view>
      <view v-else-if="!entities.length" class="ip-empty">
        <text class="ip-empty-t">{{ $t('insight.empty.noEntity') }}</text>
        <text class="ip-empty-s">{{ $t('insight.empty.noEntityHint') }}</text>
      </view>

      <template v-for="g in entityGroups" :key="g.kind">
        <view class="ip-sec">
          <text class="ip-sec-t">{{ $t('insight.kind.' + g.kind) }}</text>
          <text class="ip-sec-n">{{ g.items.length }}</text>
        </view>
        <view
          v-for="e in g.items"
          :key="e.id"
          class="ip-row"
          :class="{ on: expandedId === e.id, hl: highlightId === e.id }"
          :id="'ip-ent-' + e.id"
        >
          <view class="ip-row-top" @tap="toggleEntity(e)">
            <view class="ip-dot" :class="'st-' + (e.retrievalStatus || 'PENDING')"></view>
            <text class="ip-name">{{ e.name }}</text>
            <text v-if="mentionCount(e)" class="ip-mentions">{{ $t('insight.mentions', { count: mentionCount(e) }) }}</text>
            <text class="ip-caret">{{ expandedId === e.id ? '−' : '+' }}</text>
          </view>

          <!-- 通道不可用/查无：note 必须显示出来（「法宝检索本次不可用：账号点数耗尽」
               远好过一个空白格子），并给一个重试。 -->
          <view v-if="e.retrievalNote" class="ip-note" :class="'st-' + (e.retrievalStatus || 'PENDING')">
            <text class="ip-note-t">{{ e.retrievalNote }}</text>
            <text v-if="canParse" class="ip-act" @tap.stop="refreshEntity(e)">{{ $t('insight.retry') }}</text>
          </view>

          <view v-if="expandedId === e.id" class="ip-detail">
            <text v-if="detailErr[e.id]" class="ip-detail-err">{{ detailErr[e.id] }}</text>
            <text v-else-if="detailLoading[e.id]" class="ip-detail-hint">{{ $t('insight.loadingDetail') }}</text>
            <template v-else-if="details[e.id]">
              <!-- 企业：工商基本情况表（键值两列） -->
              <template v-if="e.kind === 'COMPANY'">
                <view v-for="r in companyRows(details[e.id])" :key="r.label" class="ip-kv">
                  <text class="ip-k">{{ r.label }}</text>
                  <text class="ip-v">{{ r.value }}</text>
                </view>
                <template v-if="companyShareholders(details[e.id]).length">
                  <text class="ip-sub">{{ $t('insight.shareholders') }}</text>
                  <view v-for="(s, si) in companyShareholders(details[e.id])" :key="'sh' + si" class="ip-kv">
                    <text class="ip-k">{{ s.name }}</text>
                    <text class="ip-v">{{ [s.percent, s.capital].filter(Boolean).join(' / ') }}</text>
                  </view>
                </template>
              </template>
              <!-- 法规：条文原文 -->
              <template v-else-if="e.kind === 'LAW'">
                <text v-if="lawOf(e).title" class="ip-title">{{ lawOf(e).title }}{{ lawOf(e).article }}</text>
                <text v-if="lawOf(e).timeliness" class="ip-meta">{{ lawOf(e).timeliness }}</text>
                <text v-if="lawOf(e).content" class="ip-para">{{ lawOf(e).content }}</text>
                <template v-if="lawOf(e).more.length">
                  <text class="ip-sub">{{ $t('insight.moreCandidates') }}</text>
                  <text v-for="(m, mi) in lawOf(e).more" :key="'lm' + mi" class="ip-cand">{{ m }}</text>
                </template>
              </template>
              <!-- 案例：判决书 -->
              <template v-else>
                <text v-if="caseOf(e).title" class="ip-title">{{ caseOf(e).title }}</text>
                <text v-if="caseMeta(e)" class="ip-meta">{{ caseMeta(e) }}</text>
                <view v-for="s in caseOf(e).sections" :key="s.key" class="ip-block">
                  <text class="ip-sub">{{ $t('insight.caseSection.' + s.key) }}</text>
                  <text class="ip-para">{{ s.text }}</text>
                </view>
                <template v-if="caseOf(e).more.length">
                  <text class="ip-sub">{{ $t('insight.moreCandidates') }}</text>
                  <text v-for="(m, mi) in caseOf(e).more" :key="'cm' + mi" class="ip-cand">{{ m }}</text>
                </template>
              </template>
              <!-- 一个字段都认不出来时的原文兜底：宁可显示原始 JSON，也不给一片空白 -->
              <text v-if="showRaw(e)" class="ip-raw">{{ rawFallback(details[e.id]) }}</text>
            </template>
            <text v-else class="ip-detail-hint">{{ $t('insight.noDetail') }}</text>

            <!-- 出处：点 quote 在文档里定位过去（find_navigate，只动视图光标） -->
            <template v-if="(e.mentions || []).length">
              <text class="ip-sub">{{ $t('insight.mentionsTitle') }}</text>
              <text
                v-for="(m, mi) in e.mentions"
                :key="'mn' + mi"
                class="ip-quote"
                @tap.stop="locate(m.quote)"
              >{{ m.quote }}</text>
            </template>
          </view>
        </view>
      </template>
    </scroll-view>

    <!-- ————————————————— 一致性校验 ————————————————— -->
    <scroll-view v-show="tab === 'checks'" class="ip-body" scroll-y>
      <view v-if="!run && !loading" class="ip-empty">
        <text class="ip-empty-t">{{ $t('insight.empty.noRun') }}</text>
        <text class="ip-empty-s">{{ $t('insight.empty.noRunHint') }}</text>
      </view>
      <view v-else-if="!findings.length" class="ip-empty">
        <text class="ip-empty-t">{{ $t('insight.empty.noFinding') }}</text>
        <text class="ip-empty-s">{{ $t('insight.empty.noFindingHint') }}</text>
      </view>

      <view
        v-for="f in findings"
        :key="f.id"
        class="ip-find"
        :class="['sv-' + (f.severity || 'warn'), { done: !!fixed[f.id] }]"
      >
        <view class="ip-find-top" @tap="onFindingTap(f)">
          <text class="ip-sv">{{ $t('insight.severity.' + (f.severity || 'warn')) }}</text>
          <text class="ip-find-t">{{ f.title }}</text>
        </view>

        <view v-for="(c, ci) in claimsOf(f)" :key="'c' + ci" class="ip-claim" @tap="locate(c.quote)">
          <text class="ip-claim-q">{{ c.quote }}</text>
          <text v-if="c.value != null" class="ip-claim-v">{{ c.value }}{{ c.unit || '' }}</text>
        </view>
        <!-- USCC_INVALID 没有 claims，形状不同 -->
        <view v-if="uscOf(f)" class="ip-claim" @tap="locate(uscOf(f).quote)">
          <text class="ip-claim-q">{{ uscOf(f).quote }}</text>
          <text class="ip-claim-v">{{ uscOf(f).code }}</text>
        </view>

        <view v-if="fixed[f.id]" class="ip-fixed">
          <text class="ip-fixed-t">{{ $t('insight.fixed') }}</text>
          <text class="ip-fixed-s">{{ $t('insight.fixedHint') }}</text>
        </view>
        <template v-else>
          <view v-if="suggestionsOf(f).length" class="ip-fixes">
            <text
              v-for="s in suggestionsOf(f)"
              :key="s.numberText"
              class="ip-fix"
              :class="{ busy: fixBusy === f.id }"
              @tap.stop="applyFix(f, s)"
            >{{ $t('insight.unifyTo', { value: s.numberText + (s.unit || '') }) }}</text>
          </view>
          <text v-else-if="blockReason(f)" class="ip-blocked">{{ $t('insight.cannotFix', { reason: blockReason(f) }) }}</text>
        </template>
        <!-- 失败提示走面板内联小条：编辑器场景下 toast 会被 webview 遮挡（dev-board#133） -->
        <text v-if="fixNotice[f.id]" class="ip-notice">{{ fixNotice[f.id] }}</text>
      </view>
    </scroll-view>
  </view>
</template>

<script>
// InsightPane.vue — 工作台「依据」面板（dev-board#181/#182）。
//
// 两个 tab：
//   外部检索  —— 解析抽出的公司/法规/案例实体 + 各自的外部库检索结果（详情懒加载）；
//   一致性校验 —— 文档内部前后矛盾的发现，点条目定位、点建议一键改好。
//
// 后端契约见 .claude/agents/doc-insight.md（列表瘦身/发现不瘦身、UNAVAILABLE≠查无此项）。
// 文档内的两个动作都走 worker 只读/替换原语，**定位一律 find_navigate**——
// find_text_locations 会往文档里写 __ai_anchor_* 书签并随 docx 落盘（doc-editor.md 明令禁止）。
//
// 停靠：这个面板注册在 config/panelRegistry.js（right 默认、left 也允许），
// 宿主 project-overview.vue 在两个 dock 分支里各显式渲染一份。面板不自画标题。

import {
  parseDocInsight, getDocInsight, getDocInsightEntity, refreshDocInsightEntity,
} from '@/services/api.js'
import { matchEntityAt, fixSuggestions, fixBlockReason, findingLocateQuote } from '@/utils/insightMatch.js'
import {
  companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
} from '@/utils/insightDetail.js'

const POLL_MS = 2000
const KIND_ORDER = ['COMPANY', 'LAW', 'CASE']

// request() 已把 {code:0,data} 整体 resolve 出来，这里统一剥一层（同 evidenceLinkActions）。
function unwrap(resp) {
  if (resp && typeof resp === 'object' && 'code' in resp && 'data' in resp) return resp.data
  return resp
}

export default {
  name: 'InsightPane',
  // entities：实体清单同步给宿主，宿主据此在正文点击时做 matchEntityAt（面板自己
  // 不知道用户点了画布哪里，宿主不知道有哪些实体——索引必须交给宿主一份）。
  emits: ['entities'],
  props: {
    projectId: { type: [Number, String], default: null },
    // 当前活跃的 writer 文档；换文档时面板整体重载（跟随，不留旧结论）
    docFileId: { type: [Number, String], default: null },
    docName: { type: String, default: '' },
    // () => (action, params) => Promise —— 拿当前文档的 LibreOffice executor。
    // 传函数不传实例（同 VariablePanel 的 :get-editor 先例）：实例会随保活池换。
    getExecutor: { type: Function, default: null },
    // 只读成员/客户没有写权限：解析与重新检索都花外部库额度，按钮置灰。
    canWrite: { type: Boolean, default: true },
    // 宿主「解析」按钮的请求：{fileId, token}，每点一次换一个新对象。
    // **必须带 fileId**：面板是 v-if 挂载的，点完解析才把面板开出来——挂载时
    // 请求已经在 props 里了（watch 看不到变化），所以 mounted 也要认一次。
    // 不带 fileId 的话，「在 A 文档点过解析、随后切到 B 文档开面板」会把 B 也解析掉。
    parseRequest: { type: Object, default: null },
    // 正文点击/光标移动时宿主推下来的光标邻域：{before, after, paragraph, meta:{metaKey,ctrlKey}, token}
    cursorContext: { type: Object, default: null },
  },
  data() {
    return {
      tab: 'retrieval',
      run: null, entities: [], findings: [],
      loading: false, error: '',
      expandedId: null, highlightId: null,
      details: {}, detailLoading: {}, detailErr: {},
      fixed: {}, fixNotice: {}, fixBusy: null,
      parsing: false,
    }
  },
  computed: {
    pid() { return Number(this.projectId) || null },
    did() { return Number(this.docFileId) || null },
    isRunning() { return !!this.run && this.run.status === 'RUNNING' },
    canParse() { return !!this.pid && !!this.did && this.canWrite && !this.parsing && !this.isRunning },
    runClass() {
      if (!this.run) return ''
      if (this.run.status === 'FAILED') return 'failed'
      if (this.run.status === 'RUNNING') return 'running'
      return 'done'
    },
    runLine() {
      if (!this.run) return ''
      if (this.run.status === 'FAILED') return this.run.error || this.$t('insight.failed')
      return this.run.phase || this.$t('insight.' + (this.run.status === 'RUNNING' ? 'running' : 'done'))
    },
    entityGroups() {
      const by = {}
      for (const e of this.entities) {
        const k = KIND_ORDER.indexOf(e.kind) === -1 ? 'COMPANY' : e.kind
        ;(by[k] || (by[k] = [])).push(e)
      }
      return KIND_ORDER.filter((k) => by[k] && by[k].length).map((k) => ({ kind: k, items: by[k] }))
    },
  },
  watch: {
    docFileId() { this.resetAndLoad() },
    parseRequest() { this.consumeParseRequest() },
    cursorContext(v) { this.onCursorContext(v) },
  },
  mounted() {
    this.resetAndLoad()
    this.consumeParseRequest()
  },
  beforeUnmount() {
    // 轮询定时器必须在这里清掉：面板是 v-if 挂载的，切走 tab / 收起 dock 就销毁，
    // 留着的 setTimeout 会对着一个已销毁的实例继续 setData（设置面板倒计时同款地雷）。
    this.clearPoll()
  },
  methods: {
    companyRows, companyShareholders, rawFallback,

    // ————————————————— 数据 —————————————————
    resetAndLoad() {
      this.clearPoll()
      this.run = null
      this.entities = []
      this.findings = []
      this.error = ''
      this.expandedId = null
      this.highlightId = null
      this.details = {}
      this.detailLoading = {}
      this.detailErr = {}
      this.fixed = {}
      this.fixNotice = {}
      this.fixBusy = null
      this._loading = null
      this._seenParse = null
      this.$emit('entities', { docFileId: this.did, entities: [] })
      if (this.did) this.load()
    },
    load() {
      // _loading 是给 requestParse 等的：面板刚开出来时首拉还在飞，此刻 run 恒为 null，
      // 不等就会把「已经解析过」的文档再解析一遍（白花一次 LLM + 外部库额度）。
      this._loading = this.doLoad()
      return this._loading
    },
    async doLoad() {
      if (!this.pid || !this.did) return
      const forDoc = this.did
      this.loading = true
      try {
        const v = unwrap(await getDocInsight(this.pid, this.did)) || {}
        if (forDoc !== this.did) return // 加载途中换了文档，这份结果作废
        this.run = v.run || null
        this.entities = Array.isArray(v.entities) ? v.entities : []
        this.findings = Array.isArray(v.findings) ? v.findings : []
        this.error = ''
        this.$emit('entities', {
          docFileId: forDoc,
          entities: this.entities.map((e) => ({ id: e.id, kind: e.kind, name: e.name, normKey: e.normKey })),
        })
        if (this.isRunning) this.schedulePoll()
      } catch (e) {
        if (forDoc !== this.did) return
        this.error = (e && e.message) || this.$t('insight.loadFailed')
      } finally {
        if (forDoc === this.did) this.loading = false
      }
    },
    schedulePoll() {
      this.clearPoll()
      this._poll = setTimeout(() => { this._poll = null; this.load() }, POLL_MS)
    },
    clearPoll() {
      if (this._poll) { clearTimeout(this._poll); this._poll = null }
    },
    onParseTap() {
      if (!this.canParse) return
      // 面板里这个按钮是用户明示的，force：已有结论也重跑一遍
      this.requestParse(true)
    },
    /** 宿主的「解析」请求：只认打给本文档的那一条（见 parseRequest 的注释）。 */
    consumeParseRequest() {
      const req = this.parseRequest
      if (!req || !this.did) return
      if (Number(req.fileId) !== this.did) return
      if (this._seenParse === req) return
      this._seenParse = req
      this.requestParse(false)
    },
    /**
     * 发起解析。force=false（工具栏按钮）时：已经有结论的文档只是把面板开出来，
     * 不再白花一次 LLM + 外部库额度；失败的那次不算结论，照样重跑。
     */
    async requestParse(force) {
      if (!this.pid || !this.did || !this.canWrite) return
      if (this.parsing) return
      if (this._loading) { try { await this._loading } catch (e) { /* 首拉失败也照常往下走 */ } }
      if (!this.did) return                       // 等首拉的过程中换了文档
      if (this.isRunning) return                  // 已经在跑了
      if (!force && this.run && this.run.status !== 'FAILED') return
      this.parsing = true
      this.error = ''
      try {
        await parseDocInsight(this.pid, this.did)
        // 立刻拉一次：后端返回时 run 已落库为 RUNNING，画面马上有进度而不是空等 2 秒
        await this.load()
      } catch (e) {
        this.error = (e && e.message) || this.$t('insight.parseFailed')
      } finally {
        this.parsing = false
      }
    },

    // ————————————————— 实体详情 —————————————————
    mentionCount(e) { return (e && e.mentions ? e.mentions.length : 0) },
    toggleEntity(e) {
      if (this.expandedId === e.id) { this.expandedId = null; return }
      this.expandedId = e.id
      this.highlightId = e.id
      this.loadDetail(e)
    },
    async loadDetail(e) {
      if (!this.pid || !e || !e.id) return
      if (this.details[e.id] !== undefined) return  // 已缓存（含「没有详情」的 null）
      if (!e.hasDetail) { this.details = { ...this.details, [e.id]: null }; return }
      this.detailLoading = { ...this.detailLoading, [e.id]: true }
      try {
        const v = unwrap(await getDocInsightEntity(this.pid, e.id)) || {}
        this.details = { ...this.details, [e.id]: v.detail || null }
        this.detailErr = { ...this.detailErr, [e.id]: '' }
      } catch (err) {
        this.detailErr = { ...this.detailErr, [e.id]: (err && err.message) || this.$t('insight.detailFailed') }
      } finally {
        this.detailLoading = { ...this.detailLoading, [e.id]: false }
      }
    },
    async refreshEntity(e) {
      if (!this.pid || !e || !e.id || !this.canWrite) return
      this.detailLoading = { ...this.detailLoading, [e.id]: true }
      this.detailErr = { ...this.detailErr, [e.id]: '' }
      try {
        const v = unwrap(await refreshDocInsightEntity(this.pid, e.id)) || {}
        const next = this.entities.map((x) => (x.id === e.id
          ? { ...x, retrievalStatus: v.retrievalStatus, retrievalSource: v.retrievalSource, retrievalNote: v.retrievalNote, hasDetail: v.hasDetail, fetchedAt: v.fetchedAt }
          : x))
        this.entities = next
        this.details = { ...this.details, [e.id]: v.detail || null }
        this.expandedId = e.id
      } catch (err) {
        this.detailErr = { ...this.detailErr, [e.id]: (err && err.message) || this.$t('insight.refreshFailed') }
      } finally {
        this.detailLoading = { ...this.detailLoading, [e.id]: false }
      }
    },
    lawOf(e) { return lawArticle(this.details[e.id]) },
    caseOf(e) { return caseRecord(this.details[e.id]) },
    caseMeta(e) {
      const c = this.caseOf(e)
      return [c.caseNumber, c.court, c.date, c.caseType].filter(Boolean).join(' · ')
    },
    // 认得的字段一个都没渲染出来时才亮原文兜底（不是每次都把 JSON 铺一遍）
    showRaw(e) {
      const d = this.details[e.id]
      if (!d) return false
      if (e.kind === 'COMPANY') return !companyRows(d).length
      if (e.kind === 'LAW') { const a = lawArticle(d); return !a.title && !a.content }
      const c = caseRecord(d)
      return !c.title && !c.sections.length
    },

    // ————————————————— 一致性发现 —————————————————
    claimsOf(f) {
      const d = f && f.detail
      return d && Array.isArray(d.claims) ? d.claims : []
    },
    uscOf(f) {
      const d = f && f.detail
      return d && !Array.isArray(d.claims) && d.code ? d : null
    },
    suggestionsOf(f) { return fixSuggestions(f && f.detail) },
    blockReason(f) { return fixBlockReason(f && f.detail) },
    onFindingTap(f) { this.locate(findingLocateQuote(f && f.detail)) },

    /**
     * 在文档里定位一句原文。**只用 find_navigate**：它全程 findFirst/findNext 枚举、
     * 只动视图光标，不像 find_text_locations 那样插锚点书签（书签会随 docx 落盘）。
     */
    async locate(quote) {
      const q = quote == null ? '' : String(quote)
      if (!q) return
      const exec = this.getExecutor ? this.getExecutor() : null
      if (!exec) return
      try { await exec('find_navigate', { keyword: q, direction: 'next' }) } catch (e) { /* 定位失败不打扰 */ }
    },

    /**
     * 一键修改：把这条发现里「其余」claim 的数字统一成候选值。
     *
     * 每一处都先用 find_navigate 数一遍——**必须恰好唯一命中**才替换。
     * 非唯一/未命中一律不动文档，把该条列进面板内联提示（不弹 toast：编辑器场景下
     * toast 被 webview 遮挡，等于静默失败，dev-board#133 的定性）。
     */
    async applyFix(f, suggestion) {
      if (this.fixBusy) return
      const exec = this.getExecutor ? this.getExecutor() : null
      if (!exec) { this.setNotice(f, this.$t('insight.noEditor')); return }
      this.fixBusy = f.id
      this.setNotice(f, '')
      const failed = []
      let done = 0
      try {
        for (const edit of suggestion.edits) {
          let nav = null
          try { nav = await exec('find_navigate', { keyword: edit.quote, direction: 'next' }) } catch (e) { nav = null }
          if (!nav || !nav.success || !nav.found || Number(nav.total) !== 1) { failed.push(edit.quote); continue }
          let res = null
          try {
            res = await exec('find_replace', { findText: edit.quote, replaceText: edit.replacement, replaceAll: true })
          } catch (e) { res = null }
          if (!res || !res.success || Number(res.replaced) !== 1) { failed.push(edit.quote); continue }
          done++
        }
      } finally {
        this.fixBusy = null
      }
      if (done && !failed.length) {
        this.fixed = { ...this.fixed, [f.id]: true }
      } else if (done) {
        this.fixed = { ...this.fixed, [f.id]: true }
        this.setNotice(f, this.$t('insight.fixPartial', { done, failed: failed.length }))
      } else {
        this.setNotice(f, this.$t('insight.fixNotUnique'))
      }
    },
    setNotice(f, msg) { this.fixNotice = { ...this.fixNotice, [f.id]: msg } },

    // ————————————————— 光标联动 —————————————————
    /**
     * 宿主推下来的光标邻域。命中实体时：
     *   带 Cmd/Ctrl（meta.metaKey || meta.ctrlKey）→ 选中并展开详情（切到检索 tab）；
     *   普通点击 / 光标移动         → 只被动高亮，不展开也不抢滚动。
     * 未命中不清高亮——光标走出实体名就把高亮抹掉会让面板一直在闪。
     */
    onCursorContext(ctx) {
      if (!ctx) return
      const hit = matchEntityAt(ctx, this.entities)
      if (!hit) return
      const meta = ctx.meta || {}
      this.highlightId = hit.id
      if (meta.metaKey || meta.ctrlKey) {
        this.tab = 'retrieval'
        this.expandedId = hit.id
        this.loadDetail(hit)
        this.scrollToEntity(hit.id)
      }
    },
    scrollToEntity(id) {
      this.$nextTick(() => {
        try {
          const el = typeof document !== 'undefined' ? document.getElementById('ip-ent-' + id) : null
          if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' })
        } catch (e) { /* 非 h5 端没有 document：滚不过去也不影响展开 */ }
      })
    },
  },
}
</script>

<style lang="scss" scoped src="./insight-pane.scss"></style>
