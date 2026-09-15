<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="!collapsed" class="awd-mask">
    <view class="awd-dialog adopt-dialog">
      <view class="awd-header">
        <text class="awd-title">{{ dialogTitle }}</text>
      </view>
      <view class="awd-body">
        <template v-if="hasTarget">
          <view class="adopt-hint">{{ hintText }}</view>
          <view v-for="row in rows" :key="row.path" class="adopt-row">
            <view class="adopt-row-main">
              <text class="adopt-row-name">{{ row.name }}</text>
              <text
                v-if="mainlineTip && draftTip && showsWholeChoice(row)"
                class="adopt-row-compare"
                @tap="compare(row)"
              >{{ $t('version.compareViewDiff') }}</text>
            </view>
            <!-- 逐份说明：这份是已经替你合好了、还在合、要你逐处裁决，还是只能整份选。
                 legacy = 后端还没给 documentMerges（老版本），保持原来的纯三选一形态。 -->
            <text v-if="row.state !== 'legacy'" class="adopt-row-note">{{ row.text }}</text>
            <view v-if="rowActions(row).length" class="adopt-row-actions">
              <text
                v-for="a in rowActions(row)"
                :key="a.key"
                class="adopt-row-action"
                @tap="onRowAction(row, a.key)"
              >{{ a.label }}</text>
            </view>
            <!-- 表格逐格 / 演示逐页：两边都改过的那些格/页，每行选一边。
                 展开就在这张清单里做，不另开标签页（只有 docx 要引擎渲染）。 -->
            <view v-if="isStructuredRow(row)" class="merge-cells">
              <text v-if="analysisState[row.path] === 'loading'" class="merge-cell-hint">{{ $t('version.mergeOverlapLoading') }}</text>
              <text v-else-if="analysisState[row.path] === 'error'" class="merge-cell-hint">{{ $t('version.mergeOverlapFailed') }}</text>
              <template v-else>
                <view v-for="ov in overlapsOf(row)" :key="ov.key" class="merge-cell-row">
                  <text class="merge-cell-key">{{ cellKeyLabel(row, ov) }}</text>
                  <text class="merge-cell-base">{{ $t('version.mergeColBase') }}：{{ ov.baseText || $t('version.mergeEmptyCell') }}</text>
                  <view class="merge-cell-picks">
                    <view
                      class="merge-cell-pick"
                      :class="{ checked: pickOf(row.path, ov.key) === 'M' }"
                      @tap="pickCell(row.path, ov.key, 'M')"
                    >
                      <text class="merge-cell-side">{{ sideLabel('main') }}</text>
                      <text class="merge-cell-text">{{ ov.mainText || $t('version.mergeEmptyCell') }}</text>
                    </view>
                    <view
                      class="merge-cell-pick"
                      :class="{ checked: pickOf(row.path, ov.key) === 'T' }"
                      @tap="pickCell(row.path, ov.key, 'T')"
                    >
                      <text class="merge-cell-side">{{ sideLabel('other') }}</text>
                      <text class="merge-cell-text">{{ ov.otherText || $t('version.mergeEmptyCell') }}</text>
                    </view>
                  </view>
                </view>
                <view
                  class="awd-btn awd-btn-primary merge-cell-confirm"
                  :class="{ 'awd-btn-disabled': !allCellsPicked(row) || busy }"
                  @tap="confirmStructured(row)"
                >{{ $t('version.mergeRowConfirm') }}</view>
              </template>
            </view>
            <view v-if="showsWholeChoice(row)" class="adopt-row-choices">
              <view
                v-for="opt in choiceOptions"
                :key="opt.value"
                class="radio-item"
                :class="{ checked: resolutions[row.path] === opt.value }"
                @tap="choose(row.path, opt.value)"
              >
                <view class="radio-head">
                  <view class="radio-dot" />
                  <text class="radio-label">{{ opt.label }}</text>
                </view>
                <text class="radio-desc">{{ opt.desc }}</text>
              </view>
            </view>
          </view>
          <!-- 脚注讲的是「两份都留着」那个选项的后果——一份整份三选一都没有时，
               这句话没有任何对应的按钮，留着只会让律师去找一个不存在的选项。 -->
          <view v-if="hasWholeChoiceRow" class="adopt-foot-note">{{ footNote }}</view>
        </template>
        <!-- 文案要跟下面那个按钮的字对上：这里唯一可点的出口就是「先不采纳」，
             说「撤销」会让律师在界面上找不到对应的按钮。 -->
        <view v-else class="adopt-orphan-hint">
          {{ $t('version.conflictOrphanHint') }}
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="abort">{{ abortLabel }}</view>
        <view
          v-if="hasTarget"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': !allChosen || busy }"
          @tap="confirm"
        >{{ $t('version.confirmChoice') }}</view>
      </view>
    </view>
  </view>
  <view v-else class="adopt-collapsed-bar">
    <text class="adopt-collapsed-text">{{ $t('version.pendingChoiceBar') }}</text>
    <text class="adopt-collapsed-resume" @tap="collapsed = false">{{ $t('version.resumeProcessing') }}</text>
  </view>
</template>

<script>
import {
  resolveAdopt, abortAdopt,
  resolveCloudMerge, abortCloudMerge,
  resolveSessionEnd, abortSessionEnd,
  getMergeAnalysis, postMergeResolveStructured,
} from '@/services/api.js'
import { isDesktopHost } from '@/services/host.js'
import { mergeRowState, mergeRowText } from '@/utils/mergeRows.js'
import { resolveMergeSideNames } from '@/utils/mergeSideNames.js'

export default {
  name: 'AdoptConflictDialog',
  props: {
    projectId: { type: [String, Number], required: true },
    // 三语境标签映射（cloud/session-end 两处方向相反，改代码前先重读 Task 13 brief 那张表）：
    // adopt（现状）：MAIN=用主线的/DRAFT=用这一稿的，对比基线=主线(mainlineTip)、增量=这一稿(draftTip)。
    // cloud（更新冲突）：MAIN=用我这边的/DRAFT=用云端的，对比基线=我这边(mainlineTip)、增量=云端(draftTip=cloudTip)。
    // session-end（结束工作撞车）：MAIN=用同事的/DRAFT=用我这边的，对比基线=同事(mainlineTip)、增量=我这边(draftTip=sessionTip)。
    mode: { type: String, default: 'adopt' },
    // session-end 裁决需要工作段 id（resolveSessionEnd 的必填参数）。
    sessionId: { type: [String, Number], default: null },
    draftId: { type: [String, Number], default: null },
    draftName: { type: String, default: '' },
    conflictingPaths: { type: Array, default: () => [] },
    // 「对比」按钮要用的两个 ref：基线侧 tip / 增量侧 tip，来自 /status 对应的冲突字段。
    mainlineTip: { type: String, default: null },
    draftTip: { type: String, default: null },
    // ---- 三方合并（spec §5.3）。后端还没给这三个字段时全部为空，本组件退回
    // 原来的「整份三选一」形态，一行代码都不走新分支。 ----
    // [{path, kind, decision, reason, mainChanges, otherChanges, overlapCount, state}]
    // 加上 useDocumentMerge 挂上去的前端字段（failed/failReason/formatOnlyCount）。
    // 「改了几处」一律用后端的 mainChanges/otherChanges（自动合过的那一份另有同源的
    // mainCount/otherCount）——引擎按修订作者分桶的那两个数不进这张清单。
    documentMerges: { type: Array, default: () => [] },
    // {main: {sha, authorName, when, title, self}, other: {...}}——两侧尖端那一版的信息。
    // 注意 main/other 是**物理侧**，与语境无关（方向表见 version-control.md）。
    sides: { type: Object, default: () => ({}) },
    // 两边分头改之前的那一版，打开合并比对稿要用
    mergeBase: { type: String, default: null },
  },
  emits: ['resolved', 'aborted', 'compare-file', 'open-merge-review', 'retry-merge'],
  data() {
    return {
      resolutions: {},
      collapsed: false,
      busy: false,
      // 逐格/逐页裁决：path -> 'loading'|'ready'|'error'
      analysisState: {},
      // path -> Analysis（只用 overlaps）
      analysisByPath: {},
      // path -> {key: 'M'|'T'}
      picks: {},
      // 本组件自己知道、但 /status 还没回来的「这份已经合好了」：
      // 合并比对稿完成裁决、逐格裁决确定之后立刻生效，不等下一轮轮询。
      localMerged: {},
    }
  },
  computed: {
    // adopt 语境下 draftId 反查失败（异常残局）时只给逃生门；cloud/session-end 不依赖
    // draftId 这个概念，只要 /status 给出了冲突态就一定能展示选择区。
    hasTarget() {
      return this.mode === 'adopt' ? !!this.draftId : true
    },
    dialogTitle() {
      if (this.mode === 'cloud') return this.$t('version.conflictTitleCloud')
      if (this.mode === 'session-end') return this.$t('version.conflictTitleSessionEnd')
      return this.$t('version.conflictTitleAdopt', { name: this.draftName || this.$t('version.thisDraftFallback') })
    },
    /*
     * 措辞不能说「改的是同一处」。Word/PDF 这些文档在版本记录里是整份字节，两边只要
     * 都动过就整份进这张清单，改的是不是同一条条款根本无从判断（JGit 合并器对二进制
     * blob 直接判冲突）；律师照「同一处」去理解，会以为选「留我这份」只丢那一处重叠，
     * 实际丢的是对方对这份文档的全部改动。所以只讲事实：两边都改过，整份二选一。
     */
    hintText() {
      // 后端给得出逐份分析时，「没法自动合到一起」这句就不再成立——能合的已经合好了。
      if ((this.documentMerges || []).length) return this.$t('version.conflictHintMerge')
      return this.$t('version.conflictHint')
    },
    // 三个选项的后果说明必须短到各占一行——弹窗高度受 max-height 限制，说明一长
    // 第三个选项就被挤到可视区外，而它在 DOM 里仍"可见"、点击坐标却落在别处
    // （v1 地雷 #24 的同款失败形态，本 PR 编写时现场踩到）。共性的兜底说明统一
    // 收到这条脚注里，不在每个选项里重复。
    footNote() {
      return this.$t('version.conflictFootNote', { side: this.bothCopySide })
    },
    /*
     * 三语境的 MAIN / DRAFT 指向的物理侧不是同一件事（方向表见
     * .claude/agents/version-control.md 的「三语境冲突判定链」与地雷 #26）：
     *   adopt        MAIN=主线      DRAFT=这一稿
     *   cloud        MAIN=本机(我)  DRAFT=案件库里同事那份
     *   session-end  MAIN=同事      DRAFT=本机(我这段工作)
     * 装反的后果是律师选了「留我这份」、落盘的却是对方内容，而且不会有任何报错。
     * 下面只改 label / desc 两个字符串字段，绝不能调整 value 的归属或顺序。
     *
     * desc 是「选了会怎样」的实际后果，按后端 WorkSessionService.applyResolution 的
     * 真实行为写：MAIN 用 MAIN 侧字节覆盖这个文件；DRAFT 用 DRAFT 侧字节覆盖；
     * BOTH 是**原文件保留 MAIN 侧内容**，DRAFT 侧另存成同目录下的
     * 《原名（来自：{增量侧名字}）.扩展名》——不是把两边内容拼在一起。
     * 每条都以「整份」起头：落地口径是整份字节覆盖，不是挑着合，理由见 hintText。
     */
    choiceOptions() {
      if (this.mode === 'cloud') {
        return [
          { value: 'MAIN', label: this.$t('version.keepMineLabel'), desc: this.$t('version.cloudKeepMineDesc') },
          { value: 'DRAFT', label: this.$t('version.useColleagueLabel'), desc: this.$t('version.cloudUseColleagueDesc') },
          { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.cloudBothDesc') },
        ]
      }
      if (this.mode === 'session-end') {
        return [
          { value: 'MAIN', label: this.$t('version.useColleagueLabel'), desc: this.$t('version.sessionUseColleagueDesc') },
          { value: 'DRAFT', label: this.$t('version.keepMineLabel'), desc: this.$t('version.sessionKeepMineDesc') },
          { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.sessionBothDesc') },
        ]
      }
      return [
        { value: 'MAIN', label: this.$t('version.adoptUseOriginalLabel'), desc: this.$t('version.adoptUseOriginalDesc') },
        { value: 'DRAFT', label: this.$t('version.adoptUseDraftLabel'), desc: this.$t('version.adoptUseDraftDesc') },
        { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.adoptBothDesc') },
      ]
    },
    // 「两份都留着」另存出来的副本名里那个「来自」是谁，与后端 sideBySideRelPath 的
    // 「原名（来自：{增量侧名字}）扩展名」一致：cloud 传常量、其余传稿名/工作标题。
    bothCopySide() {
      if (this.mode === 'cloud') return this.$t('version.teamCaseLibrary')
      return this.draftName || this.$t('version.anotherCopy')
    },
    compareLabels() {
      if (this.mode === 'cloud') return { oldLabel: this.$t('version.myShareLabel'), newLabel: this.$t('version.colleagueShareLabel') }
      if (this.mode === 'session-end') return { oldLabel: this.$t('version.colleagueShareLabel'), newLabel: this.$t('version.myShareLabel') }
      return { oldLabel: this.$t('version.originalShareLabel'), newLabel: this.$t('version.thisDraftShareLabel') }
    },
    abortLabel() {
      if (this.mode === 'cloud') return this.$t('version.abortCloud')
      if (this.mode === 'session-end') return this.$t('version.abortSessionEnd')
      return this.$t('version.abortAdopt')
    },
    /*
     * 两侧的称呼。两边是同一个人时（律师用「稿」管对方回稿，真机 A3）「你 / 你」
     * 读不出哪边是哪边，这时退到线上（主线 / 稿《对方第三版回稿》）。合并比对稿
     * 标签页用的是同一个函数，两处说法必须一致。
     */
    sideNames() {
      return resolveMergeSideNames(this.$t.bind(this), this.sides,
        { mode: this.mode, draftName: this.draftName })
    },
    mergeByPath() {
      const map = {}
      for (const m of this.documentMerges || []) {
        if (m && m.path) map[m.path] = this.localMerged[m.path] ? { ...m, ...this.localMerged[m.path] } : m
      }
      // 老后端没给 documentMerges，但本组件自己合好过某份（逐格裁决）：也要记住
      for (const p of Object.keys(this.localMerged)) {
        if (!map[p]) map[p] = { path: p, ...this.localMerged[p] }
      }
      return map
    },
    rows() {
      const isDesktop = isDesktopHost()
      return this.conflictingPaths.map((path) => {
        const merge = this.mergeByPath[path] || null
        const state = merge ? mergeRowState(merge, { isDesktop }) : 'legacy'
        return {
          path,
          name: path.split('/').pop() || path,
          merge,
          state,
          text: merge ? mergeRowText(this.$t.bind(this), merge, this.sides || {},
            { isDesktop, sideNames: this.sideNames }) : '',
        }
      })
    },
    hasWholeChoiceRow() {
      return this.rows.some((r) => this.showsWholeChoice(r))
    },
    // 「确认选择」的闸：每一行都得有结论。已经合好的（merged）算有结论——提交 MERGED；
    // 还在自动合、或者律师还没做逐处裁决的，一律挡住。挡不住的后果是律师在
    // 「同一段两边都改了」还没处理时就按整份覆盖收尾，对方那段改动静默丢掉。
    allChosen() {
      if (!this.rows.length) return false
      return this.rows.every((r) => {
        if (r.state === 'merged') return true
        if (this.showsWholeChoice(r)) return !!this.resolutions[r.path]
        return false
      })
    },
  },
  watch: {
    // 逐格/逐页那两种行一出现就去拉正文（清单里直接展开，不需要律师再点一下展开）
    rows: {
      immediate: true,
      handler(rows) {
        for (const r of rows || []) {
          if (this.isStructuredRow(r) && !this.analysisState[r.path]) this.loadAnalysis(r.path)
        }
      },
    },
  },
  mounted() {
    // 合并比对稿标签页完成裁决后发这条；总览据此把行态刷成「已合并」。
    this._onFileResolved = (payload) => {
      const path = payload && payload.path
      if (!path) return
      this.localMerged = { ...this.localMerged, [path]: { state: 'MERGED' } }
    }
    uni.$on('awd:merge-file-resolved', this._onFileResolved)
  },
  beforeUnmount() {
    if (this._onFileResolved) uni.$off('awd:merge-file-resolved', this._onFileResolved)
  },
  methods: {
    choose(path, value) {
      this.resolutions = { ...this.resolutions, [path]: value }
    },
    // 哪几种行态还要律师做整份三选一：只能整份选的（pdf/图片/太大/解析不了）、
    // 没有引擎的、自动合并失败的，以及老后端的 legacy 行。已合好 / 正在合 / 逐处裁决
    // 这三种绝不能再给三选一——那是"整份覆盖"，会把已经合进去的对方改动一把抹掉。
    showsWholeChoice(row) {
      return row.state === 'legacy' || row.state === 'whole'
        || row.state === 'whole-nondesktop' || row.state === 'auto-failed'
    },
    isStructuredRow(row) {
      return row.state === 'manual-xlsx' || row.state === 'manual-pptx'
    },
    rowActions(row) {
      if (row.state === 'merged') return [{ key: 'view', label: this.$t('version.mergeViewMerged') }]
      if (row.state === 'manual-docx') return [{ key: 'review', label: this.$t('version.mergeOpenReview') }]
      if (row.state === 'auto-failed') return [{ key: 'retry', label: this.$t('version.mergeRetryAuto') }]
      return []
    },
    onRowAction(row, key) {
      if (key === 'retry') { this.$emit('retry-merge', { path: row.path }); return }
      this.openMergeReview(row, key === 'view')
    },
    // 合并比对稿标签页（F3 的 MergeReviewTab）。弹窗是全屏遮罩，和编辑区标签页没法
    // 同屏共存，先收起——与「对比」同一套处置，裁决态本身留在后端，收起不丢东西。
    openMergeReview(row, readonly) {
      this.collapsed = true
      this.$emit('open-merge-review', {
        projectId: this.projectId,
        path: row.path,
        name: row.name,
        ctx: this.mode,
        mergeBase: this.mergeBase,
        mainRef: this.mainlineTip,
        otherRef: this.draftTip,
        sides: this.sides || {},
        // 两侧是同一个人时，稿这一侧的称呼要带上稿名（「稿《对方第三版回稿》」），
        // 而稿名只有本组件知道——标签页那边只有 sides 与语境。
        draftName: this.draftName,
        readonly: !!readonly,
      })
    },
    // 两边那两栏的抬头。两侧是两个分得开的人时照旧说「你的 / 某某 的」；分不开时
    // （同一个账号两边都是自己）用线的称呼，不再两栏都写「你的」。
    sideLabel(which) {
      const names = this.sideNames
      if (!names.byPerson) return names[which]
      const side = (this.sides || {})[which]
      if (side && side.self) return this.$t('version.mergeSideMine')
      return this.$t('version.mergeSideOther', { name: names[which] })
    },
    cellKeyLabel(row, ov) {
      if (row.state !== 'manual-pptx') return ov.key
      const m = /^s(\d+)$/.exec(String(ov.key || ''))
      return m ? this.$t('version.mergeColSlide', { n: m[1] }) : this.$t('version.mergeSlideOrder')
    },
    async loadAnalysis(path) {
      this.analysisState = { ...this.analysisState, [path]: 'loading' }
      try {
        const res = await getMergeAnalysis(this.projectId, path)
        const data = (res && res.data) || {}
        this.analysisByPath = { ...this.analysisByPath, [path]: data }
        this.analysisState = { ...this.analysisState, [path]: 'ready' }
      } catch (e) {
        console.warn('[Merge] 读取逐处分析失败', path, e)
        this.analysisState = { ...this.analysisState, [path]: 'error' }
      }
    },
    overlapsOf(row) {
      const a = this.analysisByPath[row.path]
      return (a && a.overlaps) || []
    },
    pickOf(path, key) {
      return (this.picks[path] || {})[key] || ''
    },
    pickCell(path, key, side) {
      const cur = { ...(this.picks[path] || {}) }
      cur[key] = side
      this.picks = { ...this.picks, [path]: cur }
    },
    allCellsPicked(row) {
      const list = this.overlapsOf(row)
      if (!list.length) return false
      return list.every((ov) => !!this.pickOf(row.path, ov.key))
    },
    // 逐格/逐页确定：合并文件由后端按这份清单用 POI 拼，前端不送字节。
    async confirmStructured(row) {
      if (this.busy || !this.allCellsPicked(row)) return
      this.busy = true
      try {
        const decisions = this.overlapsOf(row).map((ov) => ({
          key: ov.key, side: this.pickOf(row.path, ov.key), action: 'A',
        }))
        await postMergeResolveStructured(this.projectId, { path: row.path, decisions })
        this.localMerged = { ...this.localMerged, [row.path]: { state: 'MERGED' } }
        uni.$emit('awd:merge-file-resolved', { path: row.path })
        uni.showToast({ title: this.$t('version.mergeStructuredSaved'), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.mergeStructuredFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 弹窗的 .awd-mask 是全屏遮罩，和「对比」打开的编辑区标签页没法同屏共存；
    // 先收起弹窗（不销毁，已选的三选一保留在内存里），对比看完点「继续处理」再展开——
    // 裁决态本身留在后端（/status 的对应冲突字段），收起不会丢任何东西。
    compare(row) {
      this.collapsed = true
      this.$emit('compare-file', {
        path: row.path,
        name: row.name,
        newRef: this.draftTip,
        oldRef: this.mainlineTip,
        newLabel: this.compareLabels.newLabel,
        oldLabel: this.compareLabels.oldLabel,
      })
    },
    // 提交给后端的裁决清单：已经逐处合好的那几份报 MERGED（字节早已落在工作区、
    // 待决记录也在后端手里，这里只是告诉它"这份按合并结果收尾"），其余报三选一的值。
    resolutionPayload() {
      const out = {}
      for (const r of this.rows) {
        if (r.state === 'merged') out[r.path] = 'MERGED'
        else if (this.resolutions[r.path]) out[r.path] = this.resolutions[r.path]
      }
      return out
    },
    async confirm() {
      if (!this.allChosen || this.busy) return
      this.busy = true
      try {
        const payload = this.resolutionPayload()
        let res
        if (this.mode === 'cloud') {
          res = await resolveCloudMerge(this.projectId, payload)
        } else if (this.mode === 'session-end') {
          res = await resolveSessionEnd(this.projectId, this.sessionId, payload)
        } else {
          res = await resolveAdopt(this.projectId, this.draftId, payload)
        }
        const data = (res && res.data) || {}
        if (data.notice) uni.showToast({ title: data.notice, icon: 'none' })
        this.$emit('resolved', data.affectedFileIds || [])
      } catch (e) {
        const fallback = this.mode === 'cloud' ? this.$t('version.resolveFailedCloud')
          : this.mode === 'session-end' ? this.$t('version.resolveFailedSessionEnd')
          : this.$t('version.resolveFailedAdopt')
        uni.showToast({ title: (e && e.message) || fallback, icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async abort() {
      if (this.busy) return
      this.busy = true
      try {
        let res
        let fallbackNotice
        if (this.mode === 'cloud') {
          res = await abortCloudMerge(this.projectId)
          fallbackNotice = this.$t('version.abortNoticeCloud')
        } else if (this.mode === 'session-end') {
          res = await abortSessionEnd(this.projectId)
          fallbackNotice = this.$t('version.abortNoticeSessionEnd')
        } else {
          // abort-adopt 的路径参数在后端不参与判断（只按 projectId 找当前合并中的仓库），
          // draftId 反查落空（残局）时也用得到这条逃生门，占位传 0。
          res = await abortAdopt(this.projectId, this.draftId || 0)
          // 与后端 WorkSessionService.adoptAbortedNotice() 中英两句逐字一致：正常路径显示的
          // 是后端那句，两处措辞不同的话只有在后端没带 message 时才会露馅，很难被发现。
          fallbackNotice = this.$t('version.abortNoticeAdopt')
        }
        uni.showToast({ title: (res && res.message) || fallbackNotice, icon: 'none' })
        this.$emit('aborted')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.abortFailedGeneric'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.adopt-dialog {
  width: 460px; max-width: 92vw; max-height: 84vh;
  display: flex; flex-direction: column; background: var(--awd-surface);
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-header { padding: 18px 24px; border-bottom: 1px solid var(--awd-border-subtle); }
.awd-title { font-size: 16px; font-weight: 600; color: var(--awd-text); }
.awd-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
.adopt-hint { font-size: 13px; color: var(--awd-text-2); margin-bottom: 14px; line-height: 1.6; }
.adopt-foot-note { font-size: 12px; color: var(--awd-text-3); line-height: 1.6; margin-top: 14px; }
.adopt-orphan-hint { font-size: 13.5px; color: var(--awd-danger-text); line-height: 1.6; }
.adopt-row { padding: 14px 0; border-bottom: 1px solid var(--awd-border-subtle); }
.adopt-row-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.adopt-row-name { font-size: 13.5px; color: var(--awd-text); word-break: break-all; }
.adopt-row-compare { font-size: 12px; color: var(--awd-accent-text); text-decoration: underline; cursor: pointer; flex-shrink: 0; margin-left: 12px; }
.adopt-row-choices { display: flex; flex-direction: column; gap: 6px; }
/* 逐份说明（「已合并：…」「同一段两边都改了 · 2 处」）：这一行是律师判断
   "这份还要不要我动手"的唯一依据，字号比选项说明大半级。 */
.adopt-row-note { display: block; font-size: 12.5px; color: var(--awd-text-2); line-height: 1.6; margin-bottom: 8px; }
.adopt-row-actions { display: flex; gap: 14px; margin-bottom: 8px; }
.adopt-row-action { font-size: 12px; color: var(--awd-accent-text); text-decoration: underline; cursor: pointer; }
.merge-cells { display: flex; flex-direction: column; gap: 10px; margin-bottom: 8px; }
.merge-cell-hint { font-size: 12px; color: var(--awd-text-3); }
.merge-cell-row { border: 1px solid var(--awd-border-subtle); border-radius: 6px; padding: 8px 10px; }
.merge-cell-key { display: block; font-size: 12.5px; color: var(--awd-text); font-weight: 600; }
.merge-cell-base { display: block; font-size: 12px; color: var(--awd-text-3); line-height: 1.5; margin: 4px 0 6px; }
.merge-cell-picks { display: flex; flex-direction: column; gap: 6px; }
.merge-cell-pick {
  display: flex; flex-direction: column; gap: 2px; cursor: pointer;
  padding: 6px 8px; border: 1px solid var(--awd-border); border-radius: 6px;
}
.merge-cell-pick.checked { border-color: var(--awd-accent); background: var(--awd-accent-soft); }
.merge-cell-side { font-size: 12px; color: var(--awd-text-2); }
.merge-cell-pick.checked .merge-cell-side { color: var(--awd-accent-text); font-weight: 600; }
.merge-cell-text { font-size: 12.5px; color: var(--awd-text); line-height: 1.5; word-break: break-all; }
.merge-cell-confirm { align-self: flex-start; }
.radio-item {
  display: flex; flex-direction: column; gap: 2px; cursor: pointer;
  padding: 8px 10px; border: 1px solid var(--awd-border); border-radius: 6px;
}
.radio-item.checked { border-color: var(--awd-accent); background: var(--awd-accent-soft); }
.radio-head { display: flex; align-items: center; gap: 8px; }
.radio-dot {
  width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--awd-border-strong);
  background: var(--awd-surface); box-sizing: border-box; flex-shrink: 0;
}
.radio-item.checked .radio-dot { border-color: var(--awd-accent); background: var(--awd-accent); }
.radio-label { font-size: 13px; color: var(--awd-text); }
.radio-item.checked .radio-label { color: var(--awd-accent-text); font-weight: 600; }
/* 后果说明：律师是靠这行判断「选了会发生什么」，不是靠上面那四个字。
   必须能在一行里放下，理由见 footNote 的注释。 */
.radio-desc { font-size: 12px; color: var(--awd-text-3); line-height: 1.5; padding-left: 20px; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid var(--awd-border-subtle); background: var(--awd-bg);
}
.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.awd-btn-primary:hover { background: var(--awd-accent-hover); }
.awd-btn-secondary { background: var(--awd-surface); color: var(--awd-text-2); border: 1px solid var(--awd-border-strong); }
.awd-btn-secondary:hover { background: var(--awd-surface-2); }
.awd-btn-disabled { opacity: .45; pointer-events: none; }

.adopt-collapsed-bar {
  position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%);
  display: flex; align-items: center; gap: 12px;
  background: var(--awd-accent); color: var(--awd-text-on-accent); padding: 10px 20px; border-radius: 999px;
  font-size: 13px; z-index: 9999; box-shadow: 0 10px 25px -5px rgba(0,0,0,.25);
}
.adopt-collapsed-resume { text-decoration: underline; flex-shrink: 0; cursor: pointer; }
</style>
