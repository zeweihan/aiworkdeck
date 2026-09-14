<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  提交历史标签页（dev-board#624）——程序员在 IDE 里看 `git log --graph --all` 能做的，
  律师在这里都能做：泳道图、按日分组的版本行与事件行、筛选、领先/落后计数、
  任选两版对比、逐份文件对比、退回/另起一稿/标记重要版本。

  界面延续「零 Git 术语」纪律：分支叫「稿」，提交叫「版」，push/pull 叫「交稿/取回最新稿」。

  三个数据源：
   · `/version/history`  本机主线 + 各进行中稿 + origin/master 的统一视图（游标分页）
   · `/version/compare`  任选两版之间的文件增删改
   · `/cloud/.../events` 案件库那边的协作事件（谁交了稿 / 谁签出 / 谁取回 / 谁加了人）
  后两者任一不可得都只降级掉自己那一块，不拖累主列表——没放进案件库的案卷（绝大多数）
  本来就只有本机历史。

  排版口径：行高固定（版本行 46px / 事件行 30px / 日期头 28px），泳道 SVG 按这个高度
  画上半段（进线）与下半段（出线）。行高一变就要同步改 ROW_H 那三个常量，否则线接不上。
-->
<template>
  <view class="commit-history" :class="{ 'is-focused': focused }" @tap="focused = true">
    <!-- ==================== 工具栏 ==================== -->
    <view class="ch-toolbar">
      <view class="ch-filters">
        <view class="ch-filter">
          <text class="ch-filter-label">{{ $t('version.filterAuthor') }}</text>
          <AwdSelect
            :range="authorLabels"
            :value="authorIndex"
            @change="onPickAuthor"
          />
        </view>
        <view class="ch-filter">
          <text class="ch-filter-label">{{ $t('version.filterFile') }}</text>
          <AwdSelect
            :range="fileLabels"
            :value="fileIndex"
            @change="onPickFile"
          />
        </view>
        <view class="ch-filter ch-filter-kw">
          <input
            v-model="keyword"
            class="ch-input"
            :placeholder="$t('version.filterKeywordPlaceholder')"
            @confirm="reload"
          />
        </view>
        <view class="ch-filter ch-filter-date">
          <AwdDatePicker v-model="fromDate" :placeholder="$t('version.filterFrom')" />
          <text class="ch-date-sep">—</text>
          <AwdDatePicker v-model="toDate" :placeholder="$t('version.filterTo')" />
        </view>
        <text v-if="hasFilter" class="ch-link" @tap="clearFilters">{{ $t('version.filterClear') }}</text>
      </view>

      <view class="ch-toolbar-right">
        <text class="ch-counts">{{ countsText }}</text>
        <view v-if="selectedKeys.length === 2" class="ch-btn ch-btn-secondary" @tap="loadCompare">
          {{ $t('version.compareTwo') }}
        </view>
        <template v-if="cloudLinked">
          <view class="ch-btn ch-btn-secondary" :class="{ 'is-disabled': busy }" @tap="onPullLatest">
            {{ $t('version.pullLatestAction') }}
          </view>
          <view class="ch-btn ch-btn-primary" :class="{ 'is-disabled': busy }" @tap="onSubmitDraft">
            {{ $t('version.submitDraftAction') }}
          </view>
        </template>
        <view class="ch-btn ch-btn-secondary" @tap="reload">{{ $t('version.refreshList') }}</view>
      </view>
    </view>

    <view class="ch-body">
      <!-- ==================== 列表（泳道图与行同格，一起滚） ==================== -->
      <scroll-view class="ch-list" scroll-y :scroll-into-view="scrollIntoView" @scrolltolower="loadMore">
        <view v-if="loading" class="ch-empty">{{ $t('version.loadingHistory') }}</view>

        <view v-else-if="loadError" class="ch-empty">
          <text class="ch-empty-desc">{{ $t('version.loadFailedDesc') }}</text>
          <text class="ch-link" @tap="reload">{{ $t('common.retry') }}</text>
        </view>

        <!-- 空态一：这个项目还没开版本记录。只说一句「没有历史」等于把人挂在这里，
             开启按钮就在旁边（与左栏版本面板的引导页同一个 enableVersionControl）。 -->
        <view v-else-if="notEnabled" class="ch-empty">
          <text class="ch-empty-desc">{{ $t('version.historyNeedsVersioning') }}</text>
          <view class="ch-btn ch-btn-primary ch-empty-btn" :class="{ 'is-disabled': busy }" @tap="enableVersioning">
            {{ $t('version.enable') }}
          </view>
        </view>

        <view v-else-if="!displayRows.length" class="ch-empty">
          <text class="ch-empty-desc">{{ $t('version.timelineEmpty') }}</text>
        </view>

        <template v-else>
          <template v-for="group in groups" :key="group.day">
            <view class="ch-day">{{ dayLabel(group.at) }}</view>
            <view
              v-for="row in group.rows"
              :key="row.key"
              :id="rowDomId(row)"
              class="ch-row"
              :class="{
                'is-event': row.kind === 'event',
                'is-selected': selectedKeys.indexOf(row.key) >= 0,
                'is-remote': row.kind === 'version' && row.entry.remote,
              }"
              @tap="onRowTap(row, $event)"
            >
              <!-- 泳道图：每行一张，上半段进线、下半段出线，行高固定才接得上 -->
              <view class="ch-graph" :style="graphStyle">
                <svg
                  class="ch-graph-svg"
                  :viewBox="'0 0 ' + graphWidth + ' ' + rowHeight(row)"
                  :width="graphWidth"
                  :height="rowHeight(row)"
                  fill="none"
                >
                  <path
                    v-for="(seg, i) in graphSegments(row)"
                    :key="i"
                    :d="seg.d"
                    :class="'ch-lane-' + seg.colorKey"
                    stroke-width="1.6"
                    stroke-linecap="round"
                  />
                  <circle
                    v-if="row.graph && row.graph.node"
                    :cx="laneX(row.graph.lane)"
                    :cy="rowHeight(row) / 2"
                    :r="row.entry && row.entry.milestone ? 5 : 3.6"
                    :class="'ch-node ch-node-' + row.graph.colorKey"
                  />
                </svg>
              </view>

              <!-- 事件行：浅底一句话，没有泳道节点 -->
              <view v-if="row.kind === 'event'" class="ch-event-text">{{ eventText(row.event) }}</view>

              <!-- 版本行 -->
              <view v-else class="ch-entry">
                <view class="ch-entry-line1">
                  <text
                    v-for="(r, i) in visibleRefs(row.entry)"
                    :key="i"
                    class="ch-ref"
                    :class="'ch-ref-' + r.type"
                  >{{ refLabel(r) }}</text>
                  <text v-if="row.entry.milestone" class="ch-milestone">{{ row.entry.milestone }}</text>
                  <text class="ch-title">{{ titleOf(row.entry) }}</text>
                </view>
                <view class="ch-entry-line2">
                  <text class="ch-author">{{ authorText(row.entry) }}</text>
                  <text class="ch-sep">·</text>
                  <text class="ch-time">{{ timeOf(row.entry.when) }}</text>
                  <text class="ch-sep">·</text>
                  <text class="ch-shortid">{{ row.entry.shortId || row.entry.sha.slice(0, 7) }}</text>
                  <text
                    v-if="row.entry.autoCount"
                    class="ch-autos"
                    @tap.stop="showAutoSaves"
                  >{{ autoFoldedText(row.entry.autoCount) }}</text>
                </view>
              </view>
            </view>
          </template>
          <view v-if="loadingMore" class="ch-more">{{ $t('version.loadingHistory') }}</view>
        </template>
      </scroll-view>

      <!-- ==================== 右侧详情 ==================== -->
      <view class="ch-detail">
        <template v-if="selectedKeys.length === 2">
          <view class="ch-detail-title">{{ $t('version.compareBetween') }}</view>
          <view class="ch-detail-meta">{{ compareRangeText }}</view>
          <view v-if="comparePane === 'loading'" class="ch-detail-note">{{ $t('version.comparingLoading') }}</view>
          <view v-else-if="comparePane === 'empty'" class="ch-detail-note">{{ $t('version.noChangesThisVersion') }}</view>
          <view v-for="c in compareChanges" :key="c.path" class="ch-change">
            <text class="ch-change-type" :class="'type-' + c.type">{{ changeTypeLabel(c.type) }}</text>
            <text class="ch-change-path">{{ c.path }}</text>
            <text
              v-if="c.type === 'MODIFY'"
              class="ch-link"
              @tap="compareRange(c.path)"
            >{{ $t('version.compareLabel') }}</text>
          </view>
        </template>

        <template v-else-if="selectedEntry">
          <view class="ch-detail-title">{{ titleOf(selectedEntry) }}</view>
          <view class="ch-detail-meta">
            {{ authorText(selectedEntry) }} · {{ timeOf(selectedEntry.when) }} ·
            {{ selectedEntry.shortId || selectedEntry.sha.slice(0, 7) }}
          </view>
          <view v-if="selectedEntry.refs && selectedEntry.refs.length" class="ch-detail-row">
            <text class="ch-detail-key">{{ $t('version.detailTags') }}</text>
            <text class="ch-detail-val">{{ refsText(selectedEntry) }}</text>
          </view>
          <view v-if="typeLabel(selectedEntry)" class="ch-detail-row">
            <text class="ch-detail-key">{{ $t('version.detailType') }}</text>
            <text class="ch-detail-val">{{ typeLabel(selectedEntry) }}</text>
          </view>
          <view v-if="resolutionLines.length" class="ch-detail-row">
            <text class="ch-detail-key">{{ $t('version.detailResolutions') }}</text>
            <view class="ch-detail-val">
              <view v-for="(line, i) in resolutionLines" :key="i" class="ch-resolution">{{ line }}</view>
            </view>
          </view>

          <view class="ch-detail-sub">{{ $t('version.detailFiles') }}</view>
          <view v-if="changesLoading" class="ch-detail-note">{{ $t('version.loadingGeneric') }}</view>
          <view v-else-if="changesError" class="ch-detail-note">
            <text class="ch-detail-err">{{ $t('version.changesLoadFailedDesc') }}</text>
            <text class="ch-link" @tap="loadChanges">{{ $t('common.retry') }}</text>
          </view>
          <view v-else-if="!changes.length" class="ch-detail-note">{{ $t('version.noChangesThisVersion') }}</view>
          <view v-for="c in changes" :key="c.path" class="ch-change">
            <text class="ch-change-type" :class="'type-' + c.type">{{ changeTypeLabel(c.type) }}</text>
            <text class="ch-change-path">{{ c.path }}</text>
            <text
              v-if="c.type === 'MODIFY' && selectedEntry.parents && selectedEntry.parents.length"
              class="ch-link"
              @tap="compareWithPrevious(c.path)"
            >{{ $t('version.compareLabel') }}</text>
          </view>

          <!-- 远端独有的版本（本机还没取回）改不了本机磁盘，四个操作一个都不给：
               对一条本机根本没有的提交点「退回」只会得到一个看不懂的后端错误。 -->
          <view v-if="selectedEntry.remote" class="ch-detail-note">{{ $t('version.remoteOnlyActionsNote') }}</view>
          <view v-else class="ch-detail-actions">
            <view class="ch-btn ch-btn-secondary" @tap="compareWithPrevious('')">{{ $t('version.compareWithPrevious') }}</view>
            <view class="ch-btn ch-btn-secondary" @tap="openMilestoneNaming">
              {{ selectedEntry.milestone ? $t('version.renameMilestone') : $t('version.markMilestone') }}
            </view>
            <view class="ch-btn ch-btn-secondary" @tap="openDraftNaming">{{ $t('version.newDraftFromVersion') }}</view>
            <view class="ch-btn ch-btn-primary" @tap="confirmRevert">{{ $t('version.revertToVersion') }}</view>
          </view>
        </template>

        <template v-else-if="selectedEvent">
          <view class="ch-detail-title">{{ eventText(selectedEvent) }}</view>
          <view class="ch-detail-meta">{{ timeOf(selectedEvent.createdAt) }}</view>
          <view v-if="selectedEvent.device && selectedEvent.device.name" class="ch-detail-row">
            <text class="ch-detail-key">{{ $t('version.detailDevice') }}</text>
            <text class="ch-detail-val">{{ selectedEvent.device.name }}</text>
          </view>
        </template>

        <view v-else class="ch-detail-note">
          <text>{{ $t('version.detailSelectPrompt') }}</text>
          <text v-if="!cloudLinked" class="ch-detail-hint">{{ $t('version.historyNotLinkedNote') }}</text>
        </view>
      </view>
    </view>

    <!-- 命名弹窗：与 VersionNodeDetail 同一套文案与形制 -->
    <view v-if="milestoneNaming" class="ch-mask" @tap.self="milestoneNaming = false">
      <view class="ch-dialog">
        <view class="ch-dialog-head">{{ $t('version.nameMilestoneTitle') }}</view>
        <input v-model="milestoneName" class="ch-input ch-dialog-input" :placeholder="$t('version.milestoneNamePlaceholder')" />
        <view class="ch-dialog-foot">
          <view class="ch-btn ch-btn-secondary" @tap="milestoneNaming = false">{{ $t('common.cancel') }}</view>
          <view class="ch-btn ch-btn-primary" @tap="submitMilestone">{{ $t('common.confirm') }}</view>
        </view>
      </view>
    </view>
    <view v-if="draftNaming" class="ch-mask" @tap.self="draftNaming = false">
      <view class="ch-dialog">
        <view class="ch-dialog-head">{{ $t('version.nameDraftTitle') }}</view>
        <input v-model="draftName" class="ch-input ch-dialog-input" :placeholder="$t('version.draftNamePlaceholder')" />
        <view class="ch-dialog-foot">
          <view class="ch-btn ch-btn-secondary" @tap="draftNaming = false">{{ $t('common.cancel') }}</view>
          <view class="ch-btn ch-btn-primary" @tap="submitDraftCreate">{{ $t('version.start') }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getVersionHistory, getVersionCompare, getVersionChanges, getCloudEvents,
  getProjectFiles, uploadToCloud, updateFromCloud, enableVersionControl,
} from '@/services/api.js'
import { layoutGraph, laneCountOf } from '@/utils/historyGraph.js'
import { mergeHistoryRows, groupRowsByDay, eventRowText, comparePaneState } from '@/utils/historyRows.js'
import { createVersionActions } from '@/composables/useVersionActions.js'
import { roleLabel } from '@/config/memberRoles.js'
import AwdSelect from '@/components/AwdSelect.vue'
import AwdDatePicker from '@/components/AwdDatePicker.vue'

const LANE_W = 16
const ROW_H_VERSION = 46
const ROW_H_EVENT = 30

export default {
  name: 'CommitHistoryTab',
  components: { AwdSelect, AwdDatePicker },
  props: {
    projectId: { type: [String, Number], required: true },
    // 'remote' = 从顶栏「同事交了新稿」进来的，首屏定位到第一条还没取回的版本
    focus: { type: String, default: '' },
    // 标签是单例：已经开着时再点一次入口，focus 值可能没变（props 不变 = 不重新定位，
    // 用户会以为按钮坏了）。宿主每次点都自增这个 token，本页据此重新定位一次。
    focusToken: { type: Number, default: 0 },
    // 案卷放进过团队案件库才拉事件、才给交稿/取回两个按钮
    cloudLinked: { type: Boolean, default: false },
    // 页面上的协作动作/120 秒轮询完成后自增一次，本页据此重拉
    refreshToken: { type: Number, default: 0 },
  },
  emits: ['compare-file', 'reload-files', 'changed', 'conflict'],
  data() {
    return {
      loading: true, loadingMore: false, loadError: false, notEnabled: false,
      entries: [], events: [], nextCursor: null,
      head: null, ahead: 0, behind: 0,
      selectedKeys: [],
      changes: [], changesLoading: false, changesError: false,
      compareChanges: [], compareLoading: false, compareLoaded: false,
      selfUserId: null, selfTokenId: null,
      // 筛选
      authors: [], authorIndex: 0,
      files: [], fileIndex: 0,
      keyword: '', fromDate: '', toDate: '', includeAuto: false,
      busy: false, focused: true,
      milestoneNaming: false, milestoneName: '',
      draftNaming: false, draftName: '',
      scrollIntoView: '',
      loadSeq: 0,
    }
  },
  computed: {
    authorLabels() {
      return [this.$t('version.filterAll')].concat(this.authors.map((a) => a.label))
    },
    fileLabels() {
      return [this.$t('version.filterAll')].concat(this.files.map((f) => f.name))
    },
    hasFilter() {
      return !!(this.authorIndex || this.fileIndex || this.keyword || this.fromDate || this.toDate || this.includeAuto)
    },
    countsText() {
      const a = Number(this.ahead) || 0
      const b = Number(this.behind) || 0
      if (!this.cloudLinked) return ''
      if (a && b) return this.$t('version.aheadBehind', { ahead: a, behind: b })
      if (a) return this.$t('version.aheadOnly', { ahead: a })
      if (b) return this.$t('version.behindOnly', { behind: b })
      return this.$t('version.inSyncCounts')
    },
    /** 泳道排版只对版本行算（事件行没有节点），事件行借上一条版本行的过路线。 */
    graphRows() {
      return layoutGraph(this.entries)
    },
    laneCount() {
      return Math.max(1, laneCountOf(this.graphRows))
    },
    graphWidth() {
      return this.laneCount * LANE_W + 6
    },
    graphStyle() {
      return `width:${this.graphWidth}px`
    },
    /**
     * 版本行 + 事件行的显示序，每行挂上自己那格泳道图。
     *
     * 泳道排版只对版本行算；事件行夹在两条版本行之间，画的是那段带子里的过路线
     * （= 上一条版本行的出线落点）。band 就是「当前这段带子里有哪几条线」，
     * 顺着显示序往下推一次即可。
     */
    displayRows() {
      const rows = mergeHistoryRows(this.entries, this.events)
      const bySha = new Map()
      for (const g of this.graphRows) bySha.set(g.sha, g)
      let band = []
      return rows.map((row) => {
        if (row.kind === 'event') {
          return {
            ...row,
            graph: {
              node: false,
              incoming: band.slice(),
              outgoing: band.map((b) => ({ fromLane: b.lane, toLane: b.lane, kind: 'straight', colorKey: b.colorKey })),
            },
          }
        }
        const g = bySha.get(row.entry.sha)
        if (!g) return { ...row, graph: null }
        const incoming = band.slice()
        const seen = new Set()
        band = []
        for (const c of g.connections || []) {
          if (seen.has(c.toLane)) continue
          seen.add(c.toLane)
          band.push({ lane: c.toLane, colorKey: c.colorKey || g.colorKey })
        }
        return {
          ...row,
          graph: { node: true, lane: g.lane, colorKey: g.colorKey, incoming, outgoing: g.connections || [] },
        }
      })
    },
    groups() {
      return groupRowsByDay(this.displayRows)
    },
    selectedEntry() {
      if (this.selectedKeys.length !== 1) return null
      return this.entries.find((e) => e.sha === this.selectedKeys[0]) || null
    },
    selectedEvent() {
      if (this.selectedKeys.length !== 1) return null
      const key = this.selectedKeys[0]
      if (key.indexOf('ev-') !== 0) return null
      return this.events.find((e) => `ev-${e.id}` === key) || null
    },
    comparePane() {
      return comparePaneState({
        selectedCount: this.selectedKeys.length,
        loading: this.compareLoading,
        loaded: this.compareLoaded,
        changes: this.compareChanges,
      })
    },
    compareRangeText() {
      if (this.selectedKeys.length !== 2) return ''
      const [a, b] = this.orderedSelection()
      return `${this.shortOf(a)} → ${this.shortOf(b)}`
    },
    resolutionLines() {
      const list = (this.selectedEntry && this.selectedEntry.resolutions) || []
      return list.map((r) => {
        const key = {
          MAIN: 'version.resolutionKeptMain',
          DRAFT: 'version.resolutionKeptDraft',
          BOTH: 'version.resolutionKeptBoth',
        }[r.kept] || 'version.resolutionKeptBoth'
        return this.$t(key, { path: r.path })
      })
    },
  },
  watch: {
    refreshToken() { this.reload({ keepSelection: true }) },
    focusToken() {
      if (this.focus === 'remote') this.focusFirstRemote()
    },
    keyword() { this.debouncedReload() },
    fromDate() { this.reload() },
    toDate() { this.reload() },
    selectedKeys() {
      this.changes = []
      this.compareChanges = []
      this.compareLoaded = false
      if (this.selectedEntry) this.loadChanges()
      // 选够两版就直接去拉清单：标题已经写着「这两版之间的改动」，
      // 还要再点一次工具栏按钮才出内容，中间那段只会被读成「没有改动」。
      // 工具栏那个按钮留着当重新加载。
      if (this.selectedKeys.length === 2) this.loadCompare()
    },
  },
  created() {
    this._compareSeq = 0
    this._actions = createVersionActions({
      projectId: () => this.projectId,
      t: (k, p) => this.$t(k, p),
      isBusy: () => this.busy,
      setBusy: (v) => { this.busy = v },
      emit: (name, payload) => this.onAction(name, payload),
    })
  },
  mounted() {
    this.reload({ focusRemote: this.focus === 'remote' })
    this.loadFileOptions()
    // 键盘导航与窗口回到前台时刷新：都挂在 document/window 上——uni 会把 <view> 上的
    // 原生事件重建成普通对象，keydown 的 currentTarget 不是 DOM，拿不到容器做焦点判定。
    this._onKey = (e) => this.onKeyDown(e)
    this._onFocus = () => this.reload({ keepSelection: true })
    this._onDocTap = (e) => {
      this.focused = !!(this.$el && e.target && this.$el.contains(e.target))
    }
    if (typeof document !== 'undefined') {
      document.addEventListener('keydown', this._onKey, true)
      document.addEventListener('mousedown', this._onDocTap, true)
    }
    if (typeof window !== 'undefined') window.addEventListener('focus', this._onFocus)
  },
  beforeUnmount() {
    if (typeof document !== 'undefined') {
      document.removeEventListener('keydown', this._onKey, true)
      document.removeEventListener('mousedown', this._onDocTap, true)
    }
    if (typeof window !== 'undefined') window.removeEventListener('focus', this._onFocus)
    if (this._kwTimer) clearTimeout(this._kwTimer)
  },
  methods: {
    // ---------- 取数 ----------
    queryOptions(cursor) {
      const author = this.authorIndex ? this.authors[this.authorIndex - 1] : null
      const file = this.fileIndex ? this.files[this.fileIndex - 1] : null
      return {
        limit: 100,
        cursor: cursor || undefined,
        author: author ? (author.value || author.label) : undefined,
        fileId: file ? file.id : undefined,
        q: this.keyword || undefined,
        from: this.fromDate || undefined,
        to: this.toDate || undefined,
        includeAuto: this.includeAuto,
      }
    },
    /**
     * 重拉。请求代次守卫与 VersionTimeline 同一条理由：筛选条件连着改时，先发的那次
     * 若后回，会把已经渲染好的结果覆盖成旧的过滤结果，而界面上的筛选器显示的是新条件。
     */
    async reload({ keepSelection = false, focusRemote = false } = {}) {
      const seq = ++this.loadSeq
      this.loading = true
      this.loadError = false
      try {
        const res = await getVersionHistory(this.projectId, this.queryOptions())
        if (seq !== this.loadSeq) return
        const d = (res && res.data) || {}
        this.entries = Array.isArray(d.entries) ? d.entries : []
        this.nextCursor = d.nextCursor || null
        this.head = d.head || null
        this.ahead = d.ahead || 0
        this.behind = d.behind || 0
        this.notEnabled = d.enabled === false
        this.collectAuthors()
        if (!keepSelection) this.selectedKeys = []
        this.loading = false
        if (focusRemote) this.$nextTick(() => this.focusFirstRemote())
      } catch (e) {
        if (seq !== this.loadSeq) return
        console.warn('[History] 读取历史失败', e)
        this.loadError = true
        this.loading = false
      }
      await this.loadEvents()
    },
    async loadMore() {
      if (!this.nextCursor || this.loadingMore || this.loading) return
      this.loadingMore = true
      try {
        const res = await getVersionHistory(this.projectId, this.queryOptions(this.nextCursor))
        const d = (res && res.data) || {}
        const more = Array.isArray(d.entries) ? d.entries : []
        const seen = new Set(this.entries.map((e) => e.sha))
        this.entries = this.entries.concat(more.filter((e) => e && !seen.has(e.sha)))
        this.nextCursor = d.nextCursor || null
        this.collectAuthors()
      } catch (e) {
        console.warn('[History] 翻页失败', e)
      } finally {
        this.loadingMore = false
      }
    },
    // 事件表是案件库那边的东西：没放进库、库连不上、老服务端没这个端点，
    // 三种情况都只是「没有事件行」，本机历史照常看。
    async loadEvents() {
      if (!this.cloudLinked) { this.events = []; return }
      try {
        const res = await getCloudEvents(this.projectId, { limit: 100 })
        const d = (res && res.data) || {}
        this.events = Array.isArray(d.events) ? d.events : []
        this.selfUserId = d.selfUserId == null ? null : d.selfUserId
        this.selfTokenId = d.selfTokenId == null ? null : d.selfTokenId
      } catch (e) {
        console.warn('[History] 读取协作事件失败，只显示本机历史', e)
        this.events = []
      }
    },
    async loadFileOptions() {
      try {
        const res = await getProjectFiles(this.projectId)
        const all = Array.isArray(res) ? res : ((res && res.data) || [])
        this.files = all
          .filter((f) => f && !f.isFolder && f.name)
          .map((f) => ({ id: f.id, name: f.name }))
          .slice(0, 300)
      } catch (e) {
        this.files = []
      }
    },
    // 参与人下拉从已拉到的版本里归纳（后端没有单独的作者名单端点，也不该为一个
    // 下拉再加一个端点）。展示名为准，**永远不显示 username**。
    collectAuthors() {
      const seen = new Map()
      for (const e of this.entries) {
        const label = (e.authorName || '').trim()
        if (!label) continue
        if (!seen.has(label)) seen.set(label, { label, value: e.authorEmail || label })
      }
      const list = Array.from(seen.values())
      // 当前选中的那个人翻页后可能不在新名单里，保留下标对应关系
      const current = this.authorIndex ? this.authors[this.authorIndex - 1] : null
      this.authors = list
      if (current) {
        const i = list.findIndex((a) => a.label === current.label)
        this.authorIndex = i >= 0 ? i + 1 : 0
      }
    },
    async loadChanges() {
      const entry = this.selectedEntry
      if (!entry) return
      this.changesLoading = true
      this.changesError = false
      try {
        const res = await getVersionChanges(this.projectId, entry.sha)
        this.changes = (res && res.data && res.data.changes) || []
      } catch (e) {
        this.changesError = true
        this.changes = []
      } finally {
        this.changesLoading = false
      }
    },
    async loadCompare() {
      if (this.selectedKeys.length !== 2) return
      const [a, b] = this.orderedSelection()
      // 连着改选时会并发飞出去好几趟，只认最后一趟的回包
      const seq = ++this._compareSeq
      this.compareLoading = true
      try {
        const res = await getVersionCompare(this.projectId, a, b)
        if (seq !== this._compareSeq) return
        const list = (res && res.data && res.data.changes) || (res && res.data) || []
        this.compareChanges = Array.isArray(list) ? list : []
      } catch (e) {
        if (seq !== this._compareSeq) return
        uni.showToast({ title: this.$t('version.loadFailedToast'), icon: 'none' })
        this.compareChanges = []
      } finally {
        if (seq === this._compareSeq) {
          this.compareLoading = false
          this.compareLoaded = true
        }
      }
    },

    async enableVersioning() {
      if (this.busy) return
      this.busy = true
      try {
        await enableVersionControl(this.projectId)
        this.notEnabled = false
        await this.reload()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.enableFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },

    // ---------- 筛选 ----------
    onPickAuthor(i) { this.authorIndex = i; this.reload() },
    onPickFile(i) { this.fileIndex = i; this.reload() },
    debouncedReload() {
      if (this._kwTimer) clearTimeout(this._kwTimer)
      this._kwTimer = setTimeout(() => this.reload(), 400)
    },
    clearFilters() {
      this.authorIndex = 0
      this.fileIndex = 0
      this.keyword = ''
      this.fromDate = ''
      this.toDate = ''
      this.includeAuto = false
      this.reload()
    },
    // 「自动存档 N 次」点开 = 把自动存档也拉进来（后端是 includeAuto 这一个开关，
    // 没有按行展开的端点；做成全局开关是这个契约的诚实映射）。
    showAutoSaves() {
      if (this.includeAuto) return
      this.includeAuto = true
      this.reload({ keepSelection: true })
    },

    // ---------- 选中与键盘 ----------
    onRowTap(row, evt) {
      this.focused = true
      const native = (evt && (evt.metaKey || evt.ctrlKey)) ? evt
        : (typeof window !== 'undefined' ? window.event : null)
      const multi = !!(native && (native.metaKey || native.ctrlKey))
      if (!multi) { this.selectedKeys = [row.key]; return }
      // 只有版本行能参与「对比这两版」：事件行没有 sha，凑进去只会打出一条
      // 查不到的 compare 请求，用户看到的是一个没有原因的空清单。
      if (row.kind !== 'version') { this.selectedKeys = [row.key]; return }
      const onlyVersions = this.selectedKeys.filter((k) => k.indexOf('ev-') !== 0)
      if (onlyVersions.length !== this.selectedKeys.length) this.selectedKeys = onlyVersions
      const idx = this.selectedKeys.indexOf(row.key)
      if (idx >= 0) { this.selectedKeys = this.selectedKeys.filter((k) => k !== row.key); return }
      // 只比两版：再点第三行时顶掉最早选的那个
      this.selectedKeys = this.selectedKeys.concat(row.key).slice(-2)
    },
    onKeyDown(e) {
      if (!this.focused) return
      const tag = (e.target && e.target.tagName) || ''
      if (/^(INPUT|TEXTAREA|SELECT)$/.test(tag)) return
      const rows = this.displayRows
      if (!rows.length) return
      if (e.key === 'Escape') { this.selectedKeys = []; return }
      if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Enter') return
      e.preventDefault()
      const cur = this.selectedKeys.length === 1 ? rows.findIndex((r) => r.key === this.selectedKeys[0]) : -1
      if (e.key === 'Enter') {
        if (cur >= 0 && rows[cur].kind === 'version') this.loadChanges()
        return
      }
      const next = e.key === 'ArrowDown'
        ? Math.min(rows.length - 1, cur + 1)
        : Math.max(0, cur <= 0 ? 0 : cur - 1)
      this.selectedKeys = [rows[next].key]
      this.scrollIntoView = ''
      this.$nextTick(() => { this.scrollIntoView = this.rowDomId(rows[next]) })
    },
    focusFirstRemote() {
      const row = this.displayRows.find((r) => r.kind === 'version' && r.entry.remote)
      if (!row) return
      this.selectedKeys = [row.key]
      this.scrollIntoView = ''
      this.$nextTick(() => { this.scrollIntoView = this.rowDomId(row) })
    },
    // uni <scroll-view> 的 scroll-into-view 对 id 形状有硬要求（不合法只 console.error），
    // sha 与事件 id 里可能有非法字符，统一换下划线（同 fileOpenTabs.tabDomId）。
    rowDomId(row) {
      return 'chrow-' + String(row.key).replace(/[^A-Za-z0-9_-]/g, '_')
    },

    // ---------- 泳道图 ----------
    laneX(lane) { return lane * LANE_W + LANE_W / 2 },
    rowHeight(row) { return row.kind === 'event' ? ROW_H_EVENT : ROW_H_VERSION },
    /** 上半段=进线（上一条版本行的出线落点），下半段=本行出线；事件行两段都是过路线。 */
    graphSegments(row) {
      const g = row.graph
      if (!g) return []
      const h = this.rowHeight(row)
      const segs = []
      for (const lane of g.incoming || []) {
        segs.push({ d: `M ${this.laneX(lane.lane)} 0 L ${this.laneX(lane.lane)} ${h / 2}`, colorKey: lane.colorKey })
      }
      for (const c of g.outgoing || []) {
        const x1 = this.laneX(c.fromLane)
        const x2 = this.laneX(c.toLane)
        const y0 = h / 2
        segs.push({
          d: x1 === x2
            ? `M ${x1} ${y0} L ${x1} ${h}`
            : `M ${x1} ${y0} C ${x1} ${y0 + h * 0.3}, ${x2} ${y0 + h * 0.2}, ${x2} ${h}`,
          colorKey: c.colorKey || 'mainline',
        })
      }
      return segs
    },

    // ---------- 行文案 ----------
    titleOf(e) { return e.title || e.message || '' },
    authorText(e) {
      const name = (e.authorName || '').trim() || this.$t('version.unnamedColleague')
      return e.self ? this.$t('version.authorYou', { name }) : name
    },
    timeOf(when) {
      const d = new Date(when)
      if (isNaN(d.getTime())) return ''
      const pad = (n) => String(n).padStart(2, '0')
      return `${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
    dayLabel(at) {
      const d = new Date(at)
      if (isNaN(d.getTime())) return ''
      return this.$t('version.dayHeader', { month: d.getMonth() + 1, day: d.getDate() })
    },
    // en 下 1 不能说成「1 auto-saves」。仓里还没有用过 vue-i18n 的复数管道语法，
    // 这里按数量在两个键之间选（zh 两条文案一样，显示不变）。
    autoFoldedText(count) {
      const n = Number(count) || 0
      return this.$t(n === 1 ? 'version.autoFoldedCountOne' : 'version.autoFoldedCount', { count: n })
    },
    shortOf(sha) {
      const e = this.entries.find((x) => x.sha === sha)
      return (e && e.shortId) || String(sha).slice(0, 7)
    },
    visibleRefs(e) {
      return (e.refs || []).slice(0, 3)
    },
    refLabel(r) {
      if (r.type === 'mainline') return this.$t('version.refMainline')
      if (r.type === 'remote') return this.$t('version.refRemote')
      if (r.type === 'local') return this.$t('version.refLocal')
      return r.name || this.$t('version.refDraft')
    },
    refsText(e) {
      return (e.refs || []).map((r) => this.refLabel(r)).join(' · ')
    },
    typeLabel(e) {
      return {
        initial: this.$t('version.typeInitial'),
        session: this.$t('version.typeSession'),
        pull: this.$t('version.typePull'),
        adopt: this.$t('version.typeAdopt'),
        revert: this.$t('version.typeRevert'),
        auto: this.$t('version.typeAuto'),
        upgrade: this.$t('version.typeUpgrade'),
      }[e && e.type] || ''
    },
    changeTypeLabel(t) {
      return {
        ADD: this.$t('version.changeTypeAdd'),
        MODIFY: this.$t('version.changeTypeModify'),
        DELETE: this.$t('version.changeTypeDelete'),
        RENAME: this.$t('version.changeTypeRename'),
      }[t] || t
    },
    eventText(ev) {
      return eventRowText((k, p) => this.$t(k, p), ev, {
        selfUserId: this.selfUserId,
        selfTokenId: this.selfTokenId,
        roleLabel,
      })
    },

    // ---------- 动作 ----------
    orderedSelection() {
      // 按列表顺序（新→旧）取，compare 的 from=旧、to=新
      const order = this.displayRows.filter((r) => r.kind === 'version').map((r) => r.key)
      const picked = this.selectedKeys.slice().sort((a, b) => order.indexOf(a) - order.indexOf(b))
      return [picked[1], picked[0]]
    },
    compareWithPrevious(path) {
      const e = this.selectedEntry
      if (!e) return
      const p = path || (this.changes.find((c) => c.type === 'MODIFY') || {}).path
      if (!p) { uni.showToast({ title: this.$t('version.noChangesThisVersion'), icon: 'none' }); return }
      this.$emit('compare-file', { path: p, sha: e.sha })
    },
    compareRange(path) {
      const [from, to] = this.orderedSelection()
      this.$emit('compare-file', {
        path, newRef: to, oldRef: from,
        oldLabel: this.shortOf(from), newLabel: this.shortOf(to),
      })
    },
    openMilestoneNaming() {
      this.milestoneName = (this.selectedEntry && this.selectedEntry.milestone) || ''
      this.milestoneNaming = true
    },
    async submitMilestone() {
      const ok = await this._actions.markMilestone(this.selectedEntry.sha, this.milestoneName)
      if (ok) { this.milestoneNaming = false; this.reload({ keepSelection: true }) }
    },
    openDraftNaming() {
      this.draftName = ''
      this.draftNaming = true
    },
    async submitDraftCreate() {
      const ok = await this._actions.createDraftFrom(this.selectedEntry.sha, this.draftName)
      if (ok) this.draftNaming = false
    },
    confirmRevert() {
      this._actions.confirmRevert(this.selectedEntry.sha)
    },
    // useVersionActions 的出口收在这里：磁盘被改写的两件事都要上抛重载链 + 重拉列表。
    onAction(name, payload) {
      if (name === 'compare-file') { this.$emit('compare-file', payload); return }
      if (name === 'milestoned') { this.reload({ keepSelection: true }); return }
      this.$emit('reload-files', payload || [])
      this.$emit('changed')
      this.reload()
    },
    // 交稿 / 取回：与 CollabDialog 的 onUpload / onUpdate 同一套结果处理口径
    // （CONFLICT 送去裁决现场、被拒后自动整合过的文件要走重载链）。
    async onSubmitDraft() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await uploadToCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPLOADED') {
          uni.showToast({ title: this.$t('version.submitted'), icon: 'none' })
          const ids = d.affectedFileIds || []
          if (ids.length) this.$emit('reload-files', ids)
        } else if (d.status === 'CONFLICT') {
          this.$emit('conflict')
        } else {
          uni.showToast({ title: d.message || this.$t('version.submitFailedNotice'), icon: 'none' })
        }
        this.$emit('changed')
        await this.reload()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.submitFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async onPullLatest() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await updateFromCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPDATED') {
          uni.showToast({ title: this.$t('version.pulledLatest'), icon: 'none' })
          this.$emit('reload-files', d.affectedFileIds || [])
        } else if (d.status === 'CONFLICT') {
          this.$emit('conflict')
        } else if (d.status === 'OFFLINE') {
          uni.showToast({ title: this.$t('version.libraryOffline'), icon: 'none' })
        } else {
          uni.showToast({ title: this.$t('version.alreadyLatest'), icon: 'none' })
        }
        this.$emit('changed')
        await this.reload()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.pullFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.commit-history {
  display: flex; flex-direction: column; height: 100%;
  background: var(--awd-surface); color: var(--awd-text);
}

/* ---- 工具栏 ---- */
.ch-toolbar {
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;
  padding: 8px 14px; border-bottom: 1px solid var(--awd-border); background: var(--awd-bg);
}
.ch-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.ch-filter { display: flex; align-items: center; gap: 6px; }
.ch-filter-label { font-size: 12px; color: var(--awd-text-3); }
.ch-filter-kw { min-width: 150px; }
.ch-filter-date { gap: 4px; }
.ch-date-sep { font-size: 12px; color: var(--awd-text-3); }
.ch-input {
  height: 26px; box-sizing: border-box; padding: 0 8px; font-size: 12px;
  border: 1px solid var(--awd-border-strong); border-radius: 4px;
  background: var(--awd-surface); color: var(--awd-text);
}
.ch-toolbar-right { display: flex; align-items: center; gap: 8px; }
.ch-counts { font-size: 12px; color: var(--awd-text-2); }
.ch-link { font-size: 12px; color: var(--awd-accent-text); text-decoration: underline; cursor: pointer; flex-shrink: 0; }

.ch-btn {
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
  height: 26px; box-sizing: border-box; padding: 0 12px; border-radius: 4px;
  font-size: 12px; cursor: pointer;
}
.ch-btn-secondary { background: var(--awd-surface); color: var(--awd-text-2); border: 1px solid var(--awd-border-strong); }
.ch-btn-secondary:hover { background: var(--awd-surface-2); }
.ch-btn-primary { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.ch-btn-primary:hover { background: var(--awd-accent-hover); }
.ch-btn.is-disabled { opacity: .5; pointer-events: none; }

/* ---- 列表 + 详情 ---- */
.ch-body { flex: 1; display: flex; min-height: 0; }
.ch-list { flex: 1; min-width: 0; height: 100%; }
.ch-empty { padding: 24px 18px; display: flex; flex-direction: column; gap: 8px; }
.ch-empty-desc { font-size: 13px; color: var(--awd-text-3); line-height: 1.6; }
.ch-more { padding: 10px 18px; font-size: 12px; color: var(--awd-text-3); }

.ch-day {
  padding: 6px 14px; height: 28px; box-sizing: border-box;
  font-size: 11px; font-weight: 700; color: var(--awd-text-3);
  background: var(--awd-bg); border-bottom: 1px solid var(--awd-border-subtle);
}
.ch-row {
  display: flex; align-items: stretch; height: 46px; box-sizing: border-box;
  padding-right: 14px; cursor: pointer;
}
.ch-row.is-event { height: 30px; background: var(--awd-bg); }
.ch-row:hover { background: var(--awd-surface-2); }
.ch-row.is-selected { background: var(--awd-accent-wash); }
.ch-row.is-remote .ch-title { font-style: italic; }

.ch-graph { flex-shrink: 0; position: relative; }
.ch-graph-svg { display: block; }
.ch-graph-svg path { fill: none; }
.ch-lane-mainline { stroke: var(--awd-accent); }
.ch-lane-draft { stroke: var(--awd-mint); }
.ch-lane-remote { stroke: var(--awd-info); }
.ch-lane-local { stroke: var(--awd-accent); }
.ch-node { stroke: var(--awd-surface); stroke-width: 1.5; }
.ch-node-mainline { fill: var(--awd-accent); }
.ch-node-draft { fill: var(--awd-mint); }
.ch-node-remote { fill: var(--awd-info); }
.ch-node-local { fill: var(--awd-accent); }

.ch-event-text {
  flex: 1; min-width: 0; display: flex; align-items: center;
  font-size: 12px; color: var(--awd-text-3);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ch-entry { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 2px; }
.ch-entry-line1 { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ch-entry-line2 { display: flex; align-items: center; gap: 6px; min-width: 0; }
.ch-title {
  font-size: 13px; color: var(--awd-text); flex: 1; min-width: 0;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.ch-ref {
  flex-shrink: 0; font-size: 10px; line-height: 15px; padding: 0 6px; border-radius: 3px;
  background: var(--awd-surface-2); color: var(--awd-text-2); border: 1px solid var(--awd-border);
}
.ch-ref-mainline { color: var(--awd-accent-text); border-color: var(--awd-accent); }
.ch-ref-remote { color: var(--awd-info-text); border-color: var(--awd-info); }
.ch-milestone {
  flex-shrink: 0; font-size: 10px; line-height: 15px; padding: 0 6px; border-radius: 3px;
  color: var(--awd-warning-text); border: 1px solid var(--awd-warning);
}
.ch-author { font-size: 11px; color: var(--awd-text-2); flex-shrink: 0; }
.ch-sep { font-size: 11px; color: var(--awd-text-3); flex-shrink: 0; }
.ch-time, .ch-shortid { font-size: 11px; color: var(--awd-text-3); flex-shrink: 0; }
.ch-shortid { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.ch-autos { font-size: 11px; color: var(--awd-accent-text); text-decoration: underline; flex-shrink: 0; }

/* ---- 详情 ---- */
.ch-empty-btn { align-self: flex-start; }
/* 右栏定宽会在窄窗格里把列表挤没（工作台两栏都能拖，编辑区下限只有 200px），
   所以给一个百分比上限，让它跟着窗格一起收。 */
.ch-detail {
  width: 300px; max-width: 45%; flex-shrink: 0; overflow-y: auto; padding: 14px;
  border-left: 1px solid var(--awd-border); background: var(--awd-bg);
}
.ch-detail-title { font-size: 14px; font-weight: 600; color: var(--awd-text); line-height: 1.5; }
.ch-detail-meta { font-size: 11.5px; color: var(--awd-text-3); margin-top: 6px; }
.ch-detail-row { display: flex; gap: 8px; margin-top: 10px; }
.ch-detail-key { font-size: 11.5px; color: var(--awd-text-3); flex-shrink: 0; width: 48px; }
.ch-detail-val { font-size: 11.5px; color: var(--awd-text-2); flex: 1; min-width: 0; word-break: break-all; }
.ch-resolution { font-size: 11.5px; color: var(--awd-text-2); line-height: 1.7; }
.ch-detail-sub {
  margin-top: 14px; padding-bottom: 6px; font-size: 11px; font-weight: 700; color: var(--awd-text-3);
  border-bottom: 1px solid var(--awd-border-subtle);
}
.ch-detail-note { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; font-size: 12px; color: var(--awd-text-3); line-height: 1.6; }
.ch-detail-hint { font-size: 11.5px; color: var(--awd-text-3); }
.ch-detail-err { font-size: 12px; color: var(--awd-danger-text); }
.ch-change { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--awd-border-subtle); }
.ch-change-type { font-size: 11px; flex-shrink: 0; }
.ch-change-type.type-ADD { color: var(--awd-accent-text); }
.ch-change-type.type-MODIFY { color: var(--awd-warning-text); }
.ch-change-type.type-DELETE { color: var(--awd-danger-text); }
.ch-change-type.type-RENAME { color: var(--awd-text-2); }
.ch-change-path { font-size: 11.5px; color: var(--awd-text); flex: 1; min-width: 0; word-break: break-all; }
.ch-detail-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }

/* ---- 命名弹窗 ---- */
.ch-mask {
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.ch-dialog {
  width: 360px; max-width: 90vw; background: var(--awd-surface); border-radius: 10px;
  padding: 18px; display: flex; flex-direction: column; gap: 14px;
}
.ch-dialog-head { font-size: 15px; font-weight: 600; color: var(--awd-text); }
.ch-dialog-input { height: 34px; font-size: 13px; }
.ch-dialog-foot { display: flex; justify-content: flex-end; gap: 10px; }
</style>
