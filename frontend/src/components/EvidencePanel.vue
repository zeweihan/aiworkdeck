<template>
  <view class="ep">
    <!-- 统计条：总数 + 各状态数，同时就是状态筛选（288px 宽放不下「统计 + 另一排筛选」两行，
         合成一排 chip；点哪枚就只看哪一类，计数永远按未筛选的全量算）。 -->
    <view class="ep-stats">
      <text class="ep-stat total" :class="{ on: status === 'all' }" @tap="setStatus('all')">{{ $t('evidence.stat.total', { count: counts.total }) }}</text>
      <text v-for="s in STATUS_KEYS" :key="s" class="ep-stat" :class="[s, { on: status === s, zero: !counts[s] }]" @tap="setStatus(s)">{{ $t('evidence.status.' + s) }} {{ counts[s] }}</text>
    </view>

    <view class="ep-bar">
      <view class="ep-seg">
        <text class="ep-seg-btn" :class="{ on: view === 'section' }" @tap="setView('section')">{{ $t('evidence.view.bySection') }}</text>
        <text class="ep-seg-btn" :class="{ on: view === 'party' }" @tap="setView('party')">{{ $t('evidence.view.byParty') }}</text>
      </view>
      <!-- 第二个筛选跟着当前视图的维度走：按章节视图里筛章节，按主体视图里筛主体。
           主体名要整棵文件树的标签（懒加载），不在主体视图时不拉。 -->
      <picker class="ep-filter" mode="selector" :range="dimLabels" :value="dimIndex" @change="onDimChange">
        <text class="ep-filter-btn" :class="{ on: dimKey !== 'all' }">{{ dimLabels[dimIndex] }}</text>
      </picker>
    </view>

    <view v-if="rebinding" class="ep-rebind">
      <text class="ep-rebind-hint">{{ $t('evidence.rebindHint') }}</text>
      <view class="ep-rebind-acts">
        <text class="ep-act ok" @tap="confirmRebind">{{ $t('evidence.action.confirmRebind') }}</text>
        <text class="ep-act" @tap="rebinding = null">{{ $t('evidence.action.cancelRebind') }}</text>
      </view>
    </view>

    <view v-if="error" class="ep-error">{{ error }}</view>

    <scroll-view class="ep-list" scroll-y>
      <view v-if="loading && !links.length" class="ep-loading">
        <text class="ep-loading-t">{{ $t('evidence.loading') }}</text>
      </view>
      <view v-else-if="!links.length" class="ep-empty">
        <text class="ep-empty-t">{{ $t('evidence.empty') }}</text>
        <text class="ep-empty-s">{{ $t('evidence.emptyHint') }}</text>
      </view>
      <view v-else-if="!rows.length" class="ep-empty">
        <text class="ep-empty-t">{{ $t('evidence.emptyFiltered') }}</text>
      </view>

      <!-- 三种行（一级组头 / 二级组头 / 卡片）拍平成一个列表：卡片的模板只写一份，
           章节树的两层与主体的一层共用它。 -->
      <template v-for="r in rows" :key="r.rowKey">
        <view v-if="r.kind === 'group'" class="ep-group-head" @tap="toggleGroup(r.key)">
          <text class="ep-group-caret">{{ collapsed[r.key] ? '+' : '-' }}</text>
          <text class="ep-group-title">{{ r.title }}</text>
          <text class="ep-group-count">{{ r.count }}</text>
        </view>
        <view v-else-if="r.kind === 'sub'" class="ep-group-head sub" @tap="toggleGroup(r.key)">
          <text class="ep-group-caret">{{ collapsed[r.key] ? '+' : '-' }}</text>
          <text class="ep-group-title">{{ r.title }}</text>
          <text class="ep-group-count">{{ r.count }}</text>
        </view>
        <view v-else class="ep-card" :class="['st-' + r.link.status, { indent: r.indent, rebinding: rebinding === r.link.linkKey }]" @tap="goto(r.link)">
          <view class="ep-card-top">
            <view class="ep-dot" :class="r.link.status"></view>
            <text class="ep-status-txt">{{ $t('evidence.status.' + (r.link.status || 'active')) }}</text>
            <text class="ep-kind" v-if="r.link.createdByKind === 'ai'">AI</text>
            <text class="ep-count">{{ $t('evidence.targetsCount', { count: (r.link.targets || []).length }) }}</text>
          </view>
          <text class="ep-anchor">{{ r.link.anchorText || '' }}</text>

          <view v-for="tg in r.link.targets || []" :key="tg.id" class="ep-target" :class="{ gone: !tg.file || tg.file.isDeleted }" @tap.stop="locate(r.link, tg)">
            <view class="ep-target-row">
              <text class="ep-target-name">{{ targetName(tg) }}</text>
              <text class="ep-target-loc">{{ summary(tg.locator) }}</text>
            </view>
            <!-- 引文摘要：底稿里被引的原文。定位符没带 quote 就整行不渲染（不拿别的顶替） -->
            <text v-if="quoteOf(tg)" class="ep-quote">{{ $t('evidence.loc.quote', { quote: quoteOf(tg) }) }}</text>
            <view class="ep-target-row">
              <text class="ep-chip" :class="{ none: !tg.method }" @tap.stop="toggleMethodPicker(tg)">{{ $t('evidence.method.' + (tg.method || 'none')) }}</text>
              <!-- 置信度只有核查跑过才有（人工建链是 null）：没有就不渲染，不显示 0 也不编造 -->
              <text v-if="tg.confidence != null" class="ep-chip conf">{{ $t('evidence.confidence', { value: tg.confidence }) }}</text>
              <text v-if="tg.relation && tg.relation !== 'supports'" class="ep-chip rel" :class="tg.relation">{{ $t('evidence.relation.' + tg.relation) }}</text>
              <text class="ep-target-rm" @tap.stop="removeTarget(r.link, tg)">{{ $t('evidence.action.removeTarget') }}</text>
            </view>
            <view v-if="methodPickerFor === tg.id" class="ep-method-picker">
              <text v-for="m in METHODS" :key="m" class="ep-chip pick" :class="{ on: tg.method === m }" @tap.stop="setMethod(r.link, tg, m)">{{ $t('evidence.method.' + m) }}</text>
            </view>
          </view>

          <view class="ep-acts">
            <text v-if="r.link.status === 'stale' || r.link.status === 'unverified'" class="ep-act ok" @tap.stop="keep(r.link)">{{ $t('evidence.action.keep') }}</text>
            <text v-if="r.link.status === 'orphan'" class="ep-act" @tap.stop="startRebind(r.link)">{{ $t('evidence.action.rebind') }}</text>
            <text class="ep-act no" @tap.stop="remove(r.link)">{{ $t('evidence.action.delete') }}</text>
          </view>
        </view>
      </template>
    </scroll-view>
  </view>
</template>

<script>
// EvidencePanel.vue — 审阅面板第三页「底稿」：当前文档的 EvidenceLink 清单
// （spec §4.3 与总方案 §4「审阅面板『底稿』页」，dev-board#105 / #117）。
// ReviewPanel 只做 tab 壳，数据与动作全在这里。
//
// 数据：mounted 与 `awd:evidence-changed`（宿主编辑器 stale 核对 / 本面板自身动作
// 后广播）时 listEvidenceLinks({docFileId}) 重拉；主体视图要 PARTY 标签，
// 从文件树 getProjectFiles(pid, null, true) 一次取齐（只在切到该视图时拉——整棵树
// 对大项目不便宜，PR#550 才把文件树自己改成分层懒加载，这里不要把它请回来）。
// 筛选与统计都在前端算：统计要覆盖未筛选的全量，服务端再筛一遍等于多一次往返。
// 文档内动作（跳转/套书签）经 executor 走 worker 原语，定位底稿经 `locate` 事件
// 交宿主 openFileLinkTarget 打开（与点文档里的 filelink 超链接同一条链路）。
import {
  listEvidenceLinks, keepEvidenceAnchor, rebindEvidenceLink, deleteEvidenceLink,
  updateEvidenceTarget, removeEvidenceTarget, getProjectFiles,
} from '@/services/api.js'
import {
  groupBySectionTree, groupByParty, filterByStatus, filterBySection, filterByParty,
  sectionOptions, partyOptions, statusCounts, collectFileTags, GROUP_NONE, STATUS_KEYS,
} from '@/utils/evidenceGrouping.js'
import { locatorSummary, locatorQuote, buildFileLinkUrl } from '@/utils/evidenceLocator.js'
import { ulid } from '@/utils/ulid.js'
import { WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'
import { EVIDENCE_CHANGED_EVENT } from '@/utils/evidenceEvents.js'
import { resolveKeepText } from '@/composables/useEvidenceAnchors.js'

const STATUSES = ['all', ...STATUS_KEYS]
const METHODS = ['written_review', 'written_statement', 'web_check', 'third_party', 'interview']

export default {
  name: 'EvidencePanel',
  emits: ['locate', 'count', 'changed'],
  props: {
    executor: { type: Object, default: null },
    projectId: { type: [Number, String], default: null },
    docFileId: { type: [Number, String], default: null },
  },
  data() {
    return {
      links: [], loading: false, error: '',
      view: 'section', status: 'all',
      sectionKey: 'all', partyKey: 'all',
      fileTags: null, // Map<fileId, tags[]>，切到主体视图时懒加载
      collapsed: {},
      rebinding: null, // 正在重新指定的 linkKey
      methodPickerFor: null, // 展开方法选择的 targetId
      busy: false,
      METHODS,
      STATUS_KEYS,
    }
  },
  computed: {
    pid() { return Number(this.projectId) || null },
    did() { return Number(this.docFileId) || null },
    counts() { return statusCounts(this.links) },
    // 当前视图维度的筛选选项（第一项恒为「全部」）
    dimOptions() {
      const all = { key: 'all', label: this.$t(this.view === 'party' ? 'evidence.filter.allParties' : 'evidence.filter.allSections'), depth: 0 }
      const rest = this.view === 'party' ? partyOptions(this.links, this.fileTags) : sectionOptions(this.links)
      return [all, ...rest]
    },
    dimLabels() {
      return this.dimOptions.map((o) => {
        const text = o.label || (this.view === 'party' ? this.$t('evidence.group.none') : this.$t('evidence.group.untitled'))
        return o.depth ? '　' + text : text
      })
    },
    // 换文档后旧的筛选键可能在新文档里根本不存在——不落回「全部」的话列表会莫名全空
    dimKey() {
      const want = this.view === 'party' ? this.partyKey : this.sectionKey
      return this.dimOptions.some((o) => o.key === want) ? want : 'all'
    },
    dimIndex() { return Math.max(0, this.dimOptions.findIndex((o) => o.key === this.dimKey)) },
    visibleLinks() {
      const byStatus = filterByStatus(this.links, this.status)
      return this.view === 'party'
        ? filterByParty(byStatus, this.fileTags, this.dimKey)
        : filterBySection(byStatus, this.dimKey)
    },
    // 组头与卡片拍平成一个列表：章节树两级、主体一级，卡片模板只有一份
    rows() {
      const out = []
      const pushCard = (l, groupKey, indent) => out.push({ kind: 'link', rowKey: 'l:' + groupKey + ':' + l.linkKey, link: l, indent })
      if (this.view === 'party') {
        for (const g of groupByParty(this.visibleLinks, this.fileTags)) {
          out.push({ kind: 'group', rowKey: 'g:' + g.key, key: g.key, title: this.groupTitle(g), count: g.items.length })
          if (this.collapsed[g.key]) continue
          for (const l of g.items) pushCard(l, g.key, false)
        }
        return out
      }
      for (const g of groupBySectionTree(this.visibleLinks)) {
        out.push({ kind: 'group', rowKey: 'g:' + g.key, key: g.key, title: this.groupTitle(g), count: g.count })
        if (this.collapsed[g.key]) continue
        for (const l of g.items) pushCard(l, g.key, false)
        for (const c of g.children) {
          out.push({ kind: 'sub', rowKey: 's:' + c.key, key: c.key, title: this.groupTitle(c), count: c.items.length })
          if (this.collapsed[c.key]) continue
          for (const l of c.items) pushCard(l, c.key, true)
        }
      }
      return out
    },
  },
  watch: {
    docFileId() { this.load() },
    links(v) { this.$emit('count', (v || []).length) },
  },
  mounted() {
    this.restoreViewState()
    this._onChanged = (p) => { if (!p || !p.docFileId || Number(p.docFileId) === this.did) this.load() }
    try { uni.$on(EVIDENCE_CHANGED_EVENT, this._onChanged) } catch (e) { /* ignore */ }
    this.load()
    if (this.view === 'party') this.ensureFileTags()
  },
  beforeUnmount() {
    try { uni.$off(EVIDENCE_CHANGED_EVENT, this._onChanged) } catch (e) { /* ignore */ }
  },
  methods: {
    // 视图/筛选按项目记住（与工作台左栏面板同一套 uni.setStorageSync 约定，
    // 见 pages/project-overview/panelSwitching.js）。面板是 v-if 挂载的，关一次就重建，
    // 不落盘的话每次打开都退回「按章节 / 全部」。
    storageKey() { return this.pid ? `project_${this.pid}_evidencePanelView` : '' },
    restoreViewState() {
      const key = this.storageKey()
      if (!key) return
      try {
        const raw = uni.getStorageSync(key)
        const s = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : null
        if (!s) return
        if (s.view === 'party' || s.view === 'section') this.view = s.view
        if (STATUSES.includes(s.status)) this.status = s.status
        if (typeof s.sectionKey === 'string') this.sectionKey = s.sectionKey
        if (typeof s.partyKey === 'string') this.partyKey = s.partyKey
      } catch (e) { /* 存储不可用（隐私模式等）就用默认值，不报错 */ }
    },
    saveViewState() {
      const key = this.storageKey()
      if (!key) return
      try {
        uni.setStorageSync(key, JSON.stringify({ view: this.view, status: this.status, sectionKey: this.sectionKey, partyKey: this.partyKey }))
      } catch (e) { /* ignore */ }
    },
    async load() {
      if (!this.pid || !this.did) { this.links = []; return }
      this.loading = true
      try {
        const r = await listEvidenceLinks(this.pid, { docFileId: this.did })
        this.links = Array.isArray(r) ? r : (r && Array.isArray(r.data) ? r.data : [])
        this.error = ''
      } catch (e) {
        this.error = (e && e.message) || this.$t('evidence.loadFailed')
      } finally {
        this.loading = false
      }
    },
    async ensureFileTags() {
      if (this.fileTags || !this.pid) return
      try {
        const r = await getProjectFiles(this.pid, null, true)
        const tree = Array.isArray(r) ? r : (r && Array.isArray(r.data) ? r.data : [])
        this.fileTags = collectFileTags(tree)
      } catch (e) {
        this.fileTags = new Map()
      }
    },
    async setView(v) {
      this.view = v
      this.saveViewState()
      if (v === 'party') await this.ensureFileTags()
    },
    setStatus(s) {
      this.status = STATUSES.includes(s) ? s : 'all'
      this.saveViewState()
    },
    onDimChange(e) {
      const i = Number(e && e.detail && e.detail.value) || 0
      const key = (this.dimOptions[i] || {}).key || 'all'
      if (this.view === 'party') this.partyKey = key
      else this.sectionKey = key
      this.saveViewState()
    },
    toggleGroup(key) { this.collapsed = { ...this.collapsed, [key]: !this.collapsed[key] } },
    groupTitle(g) {
      if (g.key === GROUP_NONE) return this.view === 'party' ? this.$t('evidence.group.none') : this.$t('evidence.group.untitled')
      return g.title || g.key
    },
    targetName(tg) {
      if (!tg.file) return this.$t('evidence.fileMissing')
      return tg.file.name + (tg.file.isDeleted ? ' ' + this.$t('evidence.fileDeleted') : '')
    },
    summary(loc) { return locatorSummary(loc, (k, p) => this.$t(k, p)) },
    quoteOf(tg) { return locatorQuote(tg && tg.locator) },
    async run(action, params) {
      if (!this.executor) return null
      try {
        const r = await this.executor.executeCommand(action, params || {})
        if (r && r.success === false) { this.error = r.message || r.error || this.$t('evidence.opFailed'); return null }
        return r
      } catch (e) {
        this.error = (e && e.message) || String(e)
        return null
      }
    },
    broadcast() {
      try { uni.$emit(EVIDENCE_CHANGED_EVENT, { docFileId: this.did, source: 'panel' }) } catch (e) { /* ignore */ }
    },
    goto(l) {
      if (this.rebinding) return
      this.error = ''
      // orphan = 书签已不在文档里，goto 只会报「bookmark not found」；直接说清楚让用户重新指定
      if (l.status === 'orphan') { this.error = this.$t('evidence.orphanGoto'); return }
      this.run('goto_bookmark', { name: l.linkKey })
    },
    locate(l, tg) {
      if (!tg || !tg.fileId || (tg.file && tg.file.isDeleted)) return
      this.$emit('locate', { fileId: tg.fileId, locator: tg.locator || null, linkKey: l.linkKey, targetId: tg.id })
    },
    // 「保留关联」：用文档里现在的文字刷新 anchorText/anchorHash → active
    async keep(l) {
      if (this.busy) return
      this.busy = true
      this.error = ''
      try {
        const cur = this.executor
          ? await resolveKeepText((a, p) => this.executor.executeCommand(a, p), l.linkKey, l.anchorText || '')
          : { text: l.anchorText || '', gone: false }
        if (cur.gone) { this.error = this.$t('evidence.keepGone'); return }
        const updated = await keepEvidenceAnchor(this.pid, l.linkKey, cur.text)
        this.replaceLink(updated)
        this.broadcast()
      } catch (e) {
        this.error = (e && e.message) || this.$t('evidence.opFailed')
      } finally {
        this.busy = false
      }
    },
    startRebind(l) {
      this.rebinding = l.linkKey
      this._rebindKey = null // 本轮还没在文档里建过新书签
      this.error = ''
    },
    // 「重新指定」：当前选区套新书签 + 写超链接 → 后端换 linkKey → active。
    // 书签一旦套上就记在 _rebindKey：后端失败后用户重试时复用它，不再往文档里多塞一个书签。
    async confirmRebind() {
      const oldKey = this.rebinding
      const link = this.links.find((x) => x.linkKey === oldKey)
      if (!link || this.busy) return
      this.busy = true
      this.error = ''
      try {
        let newKey = this._rebindKey
        let anchorText = ''
        if (newKey) {
          // 重试：书签还在就跳过去重写一遍超链接（幂等），再调后端；不在就重来
          const ctx0 = await this.run('get_bookmark_context', { name: newKey })
          if (ctx0 && ctx0.exists && ctx0.text) {
            anchorText = ctx0.text
            const gt = await this.run('goto_bookmark', { name: newKey })
            const hl0 = gt && gt.success ? await this.run('set_selection_hyperlink', { url: buildFileLinkUrl(WPS_INTERNAL_HTTP_LINK_BASE, newKey, this.pid, null) }) : null
            if (!hl0 || !hl0.success) { if (!this.error) this.error = this.$t('evidence.rebindFailed'); return }
          } else {
            newKey = null
            this._rebindKey = null
          }
        }
        if (!newKey) {
          const sel = await this.run('get_selection_hyperlink', {})
          const text = sel && sel.success ? String(sel.text || '').trim() : ''
          if (!text) { this.error = this.$t('evidence.rebindNoSelection'); return }
          newKey = 'EVID_' + ulid()
          const bm = await this.run('bookmark_selection', { name: newKey })
          if (!bm || !bm.success) return
          anchorText = bm.text || text
          const url = buildFileLinkUrl(WPS_INTERNAL_HTTP_LINK_BASE, newKey, this.pid, null)
          const hl = await this.run('set_selection_hyperlink', { url })
          // 超链接没写上就不算建好：_rebindKey 不记，下次重试从选区重新来（书签+链接必须成对）
          if (!hl || !hl.success) { if (!this.error) this.error = this.$t('evidence.rebindFailed'); return }
          this._rebindKey = newKey
        }
        const ctx = await this.run('get_bookmark_context', { name: newKey })
        const updated = await rebindEvidenceLink(this.pid, oldKey, {
          newLinkKey: newKey,
          anchorText,
          sectionPath: (ctx && ctx.sectionPath) || null,
          sectionTitle: (ctx && ctx.sectionTitle) || null,
        })
        this.links = this.links.map((x) => (x.linkKey === oldKey ? updated : x))
        this.rebinding = null
        this._rebindKey = null
        this.$emit('changed') // 文档真的改了（书签+超链接），让宿主走自动保存
        this.broadcast()
      } catch (e) {
        // 后端没接受：书签已在文档里，留着 _rebindKey 等用户重试，提示里说明
        this.error = ((e && e.message) || this.$t('evidence.rebindFailed')) + (this._rebindKey ? ' ' + this.$t('evidence.rebindRetryHint') : '')
      } finally {
        this.busy = false
      }
    },
    remove(l) {
      this.error = ''
      uni.showModal({
        title: this.$t('evidence.action.delete'),
        content: this.$t('evidence.deleteConfirm'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await deleteEvidenceLink(this.pid, l.linkKey)
            this.links = this.links.filter((x) => x.linkKey !== l.linkKey)
            this.broadcast()
          } catch (e) {
            this.error = (e && e.message) || this.$t('evidence.opFailed')
          }
        },
      })
    },
    toggleMethodPicker(tg) { this.methodPickerFor = this.methodPickerFor === tg.id ? null : tg.id },
    async setMethod(l, tg, method) {
      this.methodPickerFor = null
      if (tg.method === method) return
      this.error = ''
      try {
        const updated = await updateEvidenceTarget(this.pid, tg.id, { method })
        const targets = (l.targets || []).map((x) => (x.id === tg.id ? { ...x, ...(updated || {}), method } : x))
        this.replaceLink({ ...l, targets })
      } catch (e) {
        this.error = (e && e.message) || this.$t('evidence.opFailed')
      }
    },
    removeTarget(l, tg) {
      this.error = ''
      uni.showModal({
        title: this.$t('evidence.action.removeTarget'),
        content: this.$t('evidence.deleteTargetConfirm'),
        success: async (res) => {
          if (!res.confirm) return
          try {
            await removeEvidenceTarget(this.pid, tg.id)
            this.replaceLink({ ...l, targets: (l.targets || []).filter((x) => x.id !== tg.id) })
            this.broadcast()
          } catch (e) {
            this.error = (e && e.message) || this.$t('evidence.opFailed')
          }
        },
      })
    },
    replaceLink(updated) {
      if (!updated || !updated.linkKey) return
      this.links = this.links.map((x) => (x.linkKey === updated.linkKey ? updated : x))
    },
  },
}
</script>

<style lang="scss" scoped src="./evidence-panel.scss"></style>
