<template>
  <view class="ep">
    <view class="ep-bar">
      <view class="ep-seg">
        <text class="ep-seg-btn" :class="{ on: view === 'section' }" @tap="setView('section')">{{ $t('evidence.view.bySection') }}</text>
        <text class="ep-seg-btn" :class="{ on: view === 'party' }" @tap="setView('party')">{{ $t('evidence.view.byParty') }}</text>
      </view>
      <picker class="ep-status" mode="selector" :range="statusLabels" :value="statusIndex" @change="onStatusChange">
        <text class="ep-status-btn">{{ statusLabels[statusIndex] }}</text>
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
      <view v-if="!links.length && !loading" class="ep-empty">
        <text class="ep-empty-t">{{ $t('evidence.empty') }}</text>
        <text class="ep-empty-s">{{ $t('evidence.emptyHint') }}</text>
      </view>
      <view v-else-if="!groups.length && !loading" class="ep-empty">
        <text class="ep-empty-t">{{ $t('evidence.emptyFiltered') }}</text>
      </view>

      <view v-for="g in groups" :key="g.key" class="ep-group">
        <view class="ep-group-head" @tap="toggleGroup(g.key)">
          <text class="ep-group-caret">{{ collapsed[g.key] ? '+' : '-' }}</text>
          <text class="ep-group-title">{{ groupTitle(g) }}</text>
          <text class="ep-group-count">{{ g.items.length }}</text>
        </view>
        <template v-if="!collapsed[g.key]">
          <view v-for="l in g.items" :key="g.key + ':' + l.linkKey" class="ep-card" :class="['st-' + l.status, { rebinding: rebinding === l.linkKey }]" @tap="goto(l)">
            <view class="ep-card-top">
              <view class="ep-dot" :class="l.status"></view>
              <text class="ep-status-txt">{{ $t('evidence.status.' + (l.status || 'active')) }}</text>
              <text class="ep-kind" v-if="l.createdByKind === 'ai'">AI</text>
              <text class="ep-count">{{ $t('evidence.targetsCount', { count: (l.targets || []).length }) }}</text>
            </view>
            <text class="ep-anchor">{{ l.anchorText || '' }}</text>

            <view v-for="tg in l.targets || []" :key="tg.id" class="ep-target" :class="{ gone: !tg.file || tg.file.isDeleted }" @tap.stop="locate(l, tg)">
              <view class="ep-target-row">
                <text class="ep-target-name">{{ targetName(tg) }}</text>
                <text class="ep-target-loc">{{ summary(tg.locator) }}</text>
              </view>
              <view class="ep-target-row">
                <text class="ep-chip" :class="{ none: !tg.method }" @tap.stop="toggleMethodPicker(tg)">{{ $t('evidence.method.' + (tg.method || 'none')) }}</text>
                <text v-if="tg.relation && tg.relation !== 'supports'" class="ep-chip rel" :class="tg.relation">{{ $t('evidence.relation.' + tg.relation) }}</text>
                <text class="ep-target-rm" @tap.stop="removeTarget(l, tg)">{{ $t('evidence.action.removeTarget') }}</text>
              </view>
              <view v-if="methodPickerFor === tg.id" class="ep-method-picker">
                <text v-for="m in METHODS" :key="m" class="ep-chip pick" :class="{ on: tg.method === m }" @tap.stop="setMethod(l, tg, m)">{{ $t('evidence.method.' + m) }}</text>
              </view>
            </view>

            <view class="ep-acts">
              <text v-if="l.status === 'stale' || l.status === 'unverified'" class="ep-act ok" @tap.stop="keep(l)">{{ $t('evidence.action.keep') }}</text>
              <text v-if="l.status === 'orphan'" class="ep-act" @tap.stop="startRebind(l)">{{ $t('evidence.action.rebind') }}</text>
              <text class="ep-act no" @tap.stop="remove(l)">{{ $t('evidence.action.delete') }}</text>
            </view>
          </view>
        </template>
      </view>
    </scroll-view>
  </view>
</template>

<script>
// EvidencePanel.vue — 审阅面板第三页「证据」：当前文档的 EvidenceLink 清单
// （spec §4.3，dev-board#105）。ReviewPanel 只做 tab 壳，数据与动作全在这里。
//
// 数据：mounted 与 `awd:evidence-changed`（宿主编辑器 stale 核对 / 本面板自身动作
// 后广播）时 listEvidenceLinks({docFileId}) 重拉；主体视图要 PARTY 标签，
// 从文件树 getProjectFiles(pid, null, true) 一次取齐（只在切到该视图时拉）。
// 文档内动作（跳转/套书签）经 executor 走 worker 原语，定位底稿经 `locate` 事件
// 交宿主打开文件。
import {
  listEvidenceLinks, keepEvidenceAnchor, rebindEvidenceLink, deleteEvidenceLink,
  updateEvidenceTarget, removeEvidenceTarget, getProjectFiles,
} from '@/services/api.js'
import { groupBySection, groupByParty, filterByStatus, collectFileTags, GROUP_NONE } from '@/utils/evidenceGrouping.js'
import { locatorSummary, buildFileLinkUrl } from '@/utils/evidenceLocator.js'
import { ulid } from '@/utils/ulid.js'
import { WPS_INTERNAL_HTTP_LINK_BASE } from '@/config/workbenchActions.js'
import { EVIDENCE_CHANGED_EVENT } from '@/utils/evidenceEvents.js'

const STATUSES = ['all', 'active', 'unverified', 'stale', 'orphan']
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
      fileTags: null, // Map<fileId, tags[]>，切到主体视图时懒加载
      collapsed: {},
      rebinding: null, // 正在重新指定的 linkKey
      methodPickerFor: null, // 展开方法选择的 targetId
      busy: false,
      METHODS,
    }
  },
  computed: {
    pid() { return Number(this.projectId) || null },
    did() { return Number(this.docFileId) || null },
    statusLabels() { return STATUSES.map((s) => this.$t('evidence.status.' + s)) },
    statusIndex() { return Math.max(0, STATUSES.indexOf(this.status)) },
    groups() {
      const filtered = filterByStatus(this.links, this.status)
      return this.view === 'party' ? groupByParty(filtered, this.fileTags) : groupBySection(filtered)
    },
  },
  watch: {
    docFileId() { this.load() },
    links(v) { this.$emit('count', (v || []).length) },
  },
  mounted() {
    this._onChanged = (p) => { if (!p || !p.docFileId || Number(p.docFileId) === this.did) this.load() }
    try { uni.$on(EVIDENCE_CHANGED_EVENT, this._onChanged) } catch (e) { /* ignore */ }
    this.load()
  },
  beforeUnmount() {
    try { uni.$off(EVIDENCE_CHANGED_EVENT, this._onChanged) } catch (e) { /* ignore */ }
  },
  methods: {
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
    async setView(v) {
      this.view = v
      if (v === 'party' && !this.fileTags && this.pid) {
        try {
          const r = await getProjectFiles(this.pid, null, true)
          const tree = Array.isArray(r) ? r : (r && Array.isArray(r.data) ? r.data : [])
          this.fileTags = collectFileTags(tree)
        } catch (e) {
          this.fileTags = new Map()
        }
      }
    },
    onStatusChange(e) {
      const i = Number(e && e.detail && e.detail.value) || 0
      this.status = STATUSES[i] || 'all'
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
    goto(l) { if (!this.rebinding) this.run('goto_bookmark', { name: l.linkKey }) },
    locate(l, tg) {
      if (!tg || !tg.fileId || (tg.file && tg.file.isDeleted)) return
      this.$emit('locate', { fileId: tg.fileId, locator: tg.locator || null, linkKey: l.linkKey, targetId: tg.id })
    },
    // 「保留关联」：用文档里现在的文字刷新 anchorText/anchorHash → active
    async keep(l) {
      if (this.busy) return
      this.busy = true
      try {
        const r = await this.run('check_link_anchors', { names: [l.linkKey] })
        const item = r && Array.isArray(r.items) ? r.items[0] : null
        const text = item && item.exists ? item.text : (l.anchorText || '')
        const updated = await keepEvidenceAnchor(this.pid, l.linkKey, text)
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
      this.error = ''
    },
    // 「重新指定」：当前选区套新书签 + 写超链接 → 后端换 linkKey → active
    async confirmRebind() {
      const oldKey = this.rebinding
      const link = this.links.find((x) => x.linkKey === oldKey)
      if (!link || this.busy) return
      this.busy = true
      try {
        const sel = await this.run('get_selection_hyperlink', {})
        const text = sel && sel.success ? String(sel.text || '').trim() : ''
        if (!text) { this.error = this.$t('evidence.rebindNoSelection'); return }
        const newKey = 'EVID_' + ulid()
        const bm = await this.run('bookmark_selection', { name: newKey })
        if (!bm || !bm.success) return
        const url = buildFileLinkUrl(WPS_INTERNAL_HTTP_LINK_BASE, newKey, this.pid, null)
        await this.run('set_selection_hyperlink', { url })
        const ctx = await this.run('get_bookmark_context', { name: newKey })
        const updated = await rebindEvidenceLink(this.pid, oldKey, {
          newLinkKey: newKey,
          anchorText: bm.text || text,
          sectionPath: (ctx && ctx.sectionPath) || null,
          sectionTitle: (ctx && ctx.sectionTitle) || null,
        })
        this.links = this.links.map((x) => (x.linkKey === oldKey ? updated : x))
        this.rebinding = null
        this.$emit('changed') // 文档真的改了（书签+超链接），让宿主走自动保存
        this.broadcast()
      } catch (e) {
        this.error = (e && e.message) || this.$t('evidence.rebindFailed')
      } finally {
        this.busy = false
      }
    },
    remove(l) {
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
      try {
        const updated = await updateEvidenceTarget(this.pid, tg.id, { method })
        const targets = (l.targets || []).map((x) => (x.id === tg.id ? { ...x, ...(updated || {}), method } : x))
        this.replaceLink({ ...l, targets })
      } catch (e) {
        this.error = (e && e.message) || this.$t('evidence.opFailed')
      }
    },
    removeTarget(l, tg) {
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

<style scoped>
.ep { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.ep-bar { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px 0; gap: 6px; }
.ep-seg { display: flex; border: 1px solid #DEE2E6; border-radius: 6px; overflow: hidden; background: #fff; }
.ep-seg-btn { padding: 3px 9px; font-size: 12px; color: #495057; }
.ep-seg-btn.on { background: #E6F9F0; color: #1A5336; font-weight: 600; }
.ep-status-btn { padding: 3px 9px; border: 1px solid #DEE2E6; border-radius: 6px; font-size: 12px; color: #495057; background: #fff; }
.ep-rebind { margin: 8px 10px 0; padding: 7px 9px; border-radius: 6px; background: #FFF7E6; border: 1px solid #FFD591; }
.ep-rebind-hint { display: block; font-size: 12px; color: #8A5A00; line-height: 1.5; }
.ep-rebind-acts { display: flex; gap: 6px; margin-top: 6px; }
.ep-error { margin: 8px 10px 0; padding: 6px 8px; border-radius: 6px; background: #FEF2F2; color: #991B1B; font-size: 12px; }
.ep-list { flex: 1; min-height: 0; padding: 8px 10px; }
.ep-empty { padding: 28px 6px; display: flex; flex-direction: column; gap: 6px; }
.ep-empty-t { font-size: 13px; color: #495057; }
.ep-empty-s { font-size: 12px; color: #ADB5BD; line-height: 1.5; }
.ep-group { margin-bottom: 6px; }
.ep-group-head { display: flex; align-items: center; gap: 6px; padding: 4px 2px; }
.ep-group-caret { width: 12px; font-size: 12px; color: #868E96; font-family: monospace; }
.ep-group-title { flex: 1; font-size: 12px; color: #495057; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ep-group-count { font-size: 11px; color: #ADB5BD; }
.ep-card { margin: 4px 0 8px; padding: 8px 9px; background: #fff; border: 1px solid #E9ECEF; border-radius: 8px; border-left-width: 3px; }
.ep-card.st-active { border-left-color: #5BD197; }
.ep-card.st-unverified { border-left-color: #CED4DA; }
.ep-card.st-stale { border-left-color: #F5B30E; background: #FFFBEB; }
.ep-card.st-orphan { border-left-color: #EF4444; background: #FEF2F2; }
.ep-card.rebinding { outline: 2px solid #F5B30E; }
.ep-card-top { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
.ep-dot { width: 8px; height: 8px; border-radius: 50%; background: #CED4DA; }
.ep-dot.active { background: #22C55E; }
.ep-dot.stale { background: #F5B30E; }
.ep-dot.orphan { background: #EF4444; }
.ep-status-txt { font-size: 11px; color: #495057; }
.ep-kind { padding: 0 5px; border-radius: 4px; font-size: 10px; background: #EEF2FF; color: #3730A3; }
.ep-count { margin-left: auto; font-size: 11px; color: #ADB5BD; }
.ep-anchor { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  font-size: 13px; color: #2C3338; line-height: 1.5; word-break: break-all; }
.ep-target { margin-top: 6px; padding: 5px 7px; border-radius: 6px; background: #F8F9FA; }
.ep-target.gone { opacity: 0.55; }
.ep-target-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ep-target-row + .ep-target-row { margin-top: 3px; }
.ep-target-name { flex: 1; min-width: 0; font-size: 12px; color: #1A5336; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ep-target-loc { font-size: 11px; color: #868E96; }
.ep-chip { padding: 0 6px; border-radius: 4px; font-size: 11px; background: #E6F9F0; color: #1A5336; }
.ep-chip.none { background: #F1F3F5; color: #868E96; }
.ep-chip.rel.contradicts { background: #FEF2F2; color: #991B1B; }
.ep-chip.rel.partial { background: #FFF7E6; color: #8A5A00; }
.ep-chip.pick { background: #fff; border: 1px solid #DEE2E6; color: #495057; }
.ep-chip.pick.on { border-color: #5BD197; color: #1A5336; }
.ep-target-rm { margin-left: auto; font-size: 11px; color: #ADB5BD; }
.ep-method-picker { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 5px; }
.ep-acts { display: flex; gap: 6px; margin-top: 7px; }
.ep-act { padding: 2px 10px; border: 1px solid #DEE2E6; border-radius: 6px; font-size: 12px; color: #495057; background: #fff; }
.ep-act.ok { border-color: #5BD197; color: #1A5336; }
.ep-act.no { border-color: #FCA5A5; color: #991B1B; }
</style>
