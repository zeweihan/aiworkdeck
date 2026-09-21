<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="rp">
    <view class="rp-head">
      <!-- 收起（dev-board#753）：面板左上角一个向右的箭头，指向它收起的方向。
           原先是右端一行 12px 灰字「收起」，真机上没人看得见。 -->
      <view class="rp-collapse" :title="$t('editor.review.collapse')" :aria-label="$t('editor.review.collapse')" @tap="$emit('close')">
        <svg class="rp-collapse-ico" viewBox="0 0 24 24" fill="none"><path d="M9 5l7 7-7 7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </view>
      <!-- 标签行（dev-board#754）：五个标签排一行、谁都不许折行，计数是同一行里
           的小号数字（文案本身不带 {count}，否则会连着标签一起被折行）。 -->
      <view v-if="!isMerge" ref="tabs" class="rp-tabs awd-hairline-scroll">
        <text class="rp-tab" :class="{ on: tab === 'rev' }" @tap="tab = 'rev'">{{ $t('editor.review.revTab') }}<text class="rp-tab-n">{{ allGroups.length }}</text></text>
        <text class="rp-tab" :class="{ on: tab === 'cmt' }" @tap="tab = 'cmt'">{{ $t('editor.review.cmtTab') }}<text class="rp-tab-n">{{ comments.length }}</text></text>
        <text class="rp-tab" :class="{ on: tab === 'evd' }" @tap="tab = 'evd'">{{ $t('editor.review.evidenceTab') }}<text class="rp-tab-n">{{ evidenceCount }}</text></text>
        <!-- 「AI 审校」（dev-board#723/#724，改名见 #749）：规则检查 + AI 审校的
             同一张清单。inlineReview 为 null（非 Writer / 没有项目 / 引擎没起来）
             时整个标签不出现。 -->
        <text v-if="inlineReview" class="rp-tab" :class="{ on: tab === 'chk' }" @tap="tab = 'chk'">{{ $t('editor.review.checkTab') }}<text class="rp-tab-n">{{ inlineReviewCount }}</text></text>
        <!-- 「溯源」（dev-board#632）：这一段是谁、哪一版、什么时候改的。
             这份文件没有版本记录时 provenance 为 null，标签整个不出现。 -->
        <text v-if="provenance" class="rp-tab" :class="{ on: tab === 'prov' }" @tap="tab = 'prov'">{{ $t('version.provenanceTab') }}</text>
      </view>
      <text v-else class="rp-merge-title">{{ $t('version.mergePanelTitle') }}</text>
    </view>

    <view v-if="!isMerge && tab === 'rev' && revisions.length" class="rp-bulk">
      <text class="rp-bulk-btn" @tap="resolveAll('accept')">{{ $t('editor.review.acceptAll') }}</text>
      <text class="rp-bulk-btn" @tap="resolveAll('reject')">{{ $t('editor.review.rejectAll') }}</text>
    </view>

    <!-- 作者筛选：多方修订混在一份文档里时，先按「谁改的」收窄再逐条看。
         四个桶的数字恒按未筛选的全量算，切了筛选也不变（否则没法用它判断
         「还有几条别人的改动没看」）。 -->
    <scroll-view v-if="!isMerge && tab === 'rev' && revisions.length" class="rp-filter" scroll-x>
      <view class="rp-filter-row">
        <text v-for="f in authorFilters" :key="f.kind" class="rp-chip" :class="[f.kind, { on: authorFilter === f.kind }]"
              @tap="authorFilter = f.kind">{{ f.label }}</text>
      </view>
    </scroll-view>

    <view v-if="error && tab !== 'evd' && tab !== 'chk'" class="rp-error">{{ error }}</view>
    <view v-if="listLimitReached" class="rp-limit">{{ $t('editor.review.limitReached', { count: 500 }) }}</view>

    <!-- 底稿页：独立组件、v-show 常驻（tab 上要显示计数，且切页不丢筛选/折叠态） -->
    <EvidencePanel
      v-if="!isMerge"
      v-show="tab === 'evd'"
      :executor="executor"
      :project-id="projectId"
      :doc-file-id="docFileId"
      @count="evidenceCount = $event"
      @locate="$emit('locate', $event)"
      @changed="$emit('changed')"
    />

    <!-- 审校页：同样 v-show 常驻（标签上要显示计数，切页不丢忽略/分类） -->
    <InlineReviewPanel
      v-if="!isMerge && inlineReview"
      v-show="tab === 'chk'"
      :state="inlineReview"
      :executor="executor"
      @count="inlineReviewCount = $event"
      @changed="$emit('changed')"
      @action="$emit('inline-review', $event)"
    />

    <!-- 合并比对稿模式（dev-board#630，spec §5.4）：三块固定顺序，没有标签切换。
         ① 同一段两边都改了（置顶，未处理完挡住「完成裁决」）
         ② 修订按两侧作者分组
         ③ 另一侧只改了格式、没被自动带过来的段 -->
    <scroll-view v-if="isMerge" class="rp-list" scroll-y>
      <!-- ① 同一段两边都改了 -->
      <view v-if="mergeConflictRows.length" class="rp-sec">
        <text class="rp-sec-h">{{ $t('version.mergeBlockConflicts', { count: mergeConflictRows.length }) }}</text>
        <view v-for="c in mergeConflictRows" :key="'mc-' + c.key" class="rp-card rp-conflict" :class="{ done: !!c.choice }">
          <view class="rp-card-top">
            <text class="rp-who">{{ c.where }}</text>
            <text v-if="c.choice" class="rp-tag done">{{ c.choiceLabel }}</text>
          </view>
          <view class="rp-three">
            <view class="rp-three-col">
              <text class="rp-three-h">{{ $t('version.mergeBaseSideLabel') }}</text>
              <text class="rp-three-t">{{ c.baseText || $t('editor.review.emptyText') }}</text>
            </view>
            <view class="rp-three-col">
              <text class="rp-three-h">{{ mainSideLabel }}</text>
              <text class="rp-three-t">{{ c.mainText || $t('editor.review.emptyText') }}</text>
            </view>
            <view class="rp-three-col">
              <text class="rp-three-h">{{ otherSideLabel }}</text>
              <text class="rp-three-t">{{ c.otherText || $t('editor.review.emptyText') }}</text>
            </view>
          </view>
          <view class="rp-acts">
            <text class="rp-act ok" @tap.stop="chooseConflict(c, 'main')">{{ $t('version.mergeUseSide', { side: mainSideLabel }) }}</text>
            <text class="rp-act ok" @tap.stop="chooseConflict(c, 'other')">{{ $t('version.mergeUseSide', { side: otherSideLabel }) }}</text>
            <text class="rp-act" @tap.stop="chooseConflict(c, 'self')">{{ $t('version.mergeEditSelf') }}</text>
          </view>
        </view>
      </view>

      <!-- ② 修订按两侧作者分组 -->
      <view class="rp-sec">
        <text class="rp-sec-h">{{ $t('version.mergeBlockRevisions', { count: allGroups.length }) }}</text>
        <view v-if="!allGroups.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('version.mergeNoPendingRevisions') }}</text>
        </view>
        <view v-for="side in mergeSideGroups" :key="'ms-' + side.side" class="rp-sub">
          <text class="rp-sub-h">{{ $t('version.mergeSideGroup', { side: side.label, count: side.groups.length }) }}</text>
          <view v-if="side.groups.length" class="rp-bulk">
            <text class="rp-bulk-btn" @tap="resolveMergeSide(side, 'accept')">{{ $t('version.mergeAcceptSide', { side: side.label }) }}</text>
            <text class="rp-bulk-btn" @tap="resolveMergeSide(side, 'reject')">{{ $t('version.mergeRejectSide', { side: side.label }) }}</text>
          </view>
          <view v-for="g in side.groups" :key="g.key" class="rp-card" :class="'k-' + g.authorKind" @tap="goto(g)">
            <view class="rp-card-top">
              <text class="rp-tag" :class="typeClass(g)">{{ typeLabel(g) }}</text>
              <text v-if="g.inTable" class="rp-tag tbl">{{ $t('editor.review.table') }}</text>
              <text class="rp-date">{{ side.when }}</text>
            </view>
            <text class="rp-text" :class="{ del: g.typeKey === 'delete' }">{{ g.text || $t('editor.review.emptyText') }}</text>
            <text v-if="g.paragraph" class="rp-ctx">{{ g.paragraph }}</text>
            <view class="rp-acts">
              <text class="rp-act ok" @tap.stop="resolveMergeGroup(side, g, 'accept')">{{ $t('editor.review.accept') }}</text>
              <text class="rp-act no" @tap.stop="resolveMergeGroup(side, g, 'reject')">{{ $t('editor.review.reject') }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- ③ 另一侧只改了格式、没被自动带过来 -->
      <view v-if="mergeFormatOnly.length" class="rp-sec">
        <text class="rp-sec-h">{{ $t('version.mergeBlockFormatOnly', { side: otherSideLabel, count: mergeFormatOnly.length }) }}</text>
        <text class="rp-sec-s">{{ $t('version.mergeFormatOnlyHint', { side: otherSideLabel }) }}</text>
        <view v-for="f in mergeFormatRows" :key="'mf-' + f.key" class="rp-card" @tap="gotoParagraph(f.paraKey)">
          <text class="rp-ctx">{{ f.where }}</text>
          <text class="rp-text">{{ f.preview || $t('editor.review.emptyText') }}</text>
          <view class="rp-acts">
            <text class="rp-act" @tap.stop="$emit('open-other-version', f)">{{ $t('version.mergeViewOtherVersion', { side: otherSideLabel }) }}</text>
          </view>
        </view>
      </view>
    </scroll-view>

    <scroll-view v-show="!isMerge && tab !== 'evd' && tab !== 'chk'" class="rp-list" scroll-y :scroll-into-view="activeCardId" scroll-with-animation>
      <!-- 修订 -->
      <template v-if="tab === 'rev'">
        <view v-if="!revisions.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyRevTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyRevSub') }}</text>
        </view>
        <view v-else-if="!revisionGroups.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyFilteredTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyFilteredSub') }}</text>
        </view>
        <view v-for="g in revisionGroups" :key="g.key" :id="'rp-' + g.key" class="rp-card" :class="['k-' + g.authorKind, { active: activeCardId === 'rp-' + g.key }]" @tap="goto(g)">
          <view class="rp-card-top">
            <text class="rp-who" :class="g.authorKind">{{ authorLabel(g) }}</text>
            <text class="rp-tag" :class="typeClass(g)">{{ typeLabel(g) }}</text>
            <text v-if="g.inTable" class="rp-tag tbl">{{ $t('editor.review.table') }}</text>
            <text v-if="g.items.length > 1 && !g.operationId" class="rp-tag cnt">{{ $t('editor.review.contiguousCount', { count: g.items.length }) }}</text>
            <text class="rp-date">{{ g.date || '' }}</text>
          </view>
          <text class="rp-text" :class="{ del: g.typeKey === 'delete' }">{{ g.text || $t('editor.review.emptyText') }}</text>
          <!-- 引擎给的说明只对格式类（正文是空的、光看文字说不出改了什么）有信息量；
               插入/删除卡上文字本身已经说明一切，不再重复一行。 -->
          <text v-if="g.description && g.typeKey !== 'insert' && g.typeKey !== 'delete'" class="rp-desc">{{ g.description }}</text>
          <text v-if="g.paragraph" class="rp-ctx">{{ g.paragraph }}</text>
          <!-- 修订理由：位置上与本条修订重叠/相接的批注（AI 把改动理由挂成批注）。 -->
          <view v-for="c in g.reasons" :key="'r' + c.index" class="rp-reason">
            <text class="rp-reason-h">{{ $t('editor.review.reasonBy', { author: c.author || $t('editor.review.unknownAuthor') }) }}</text>
            <text class="rp-reason-t">{{ c.content }}</text>
          </view>
          <view class="rp-acts">
            <text class="rp-act ok" @tap.stop="resolveGroup(g, 'accept')">{{ $t('editor.review.accept') }}</text>
            <text class="rp-act no" @tap.stop="resolveGroup(g, 'reject')">{{ $t('editor.review.reject') }}</text>
          </view>
        </view>
      </template>

      <!-- 批注 -->
      <template v-else-if="tab === 'cmt'">
        <view v-if="!comments.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyCmtTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyCmtSub') }}</text>
        </view>
        <view v-for="c in commentRows" :key="'c' + c.index" :id="'rp-c' + c.index" class="rp-card" :class="{ done: c.resolved, active: activeCardId === 'rp-c' + c.index }" @tap="gotoComment(c)">
          <view class="rp-card-top">
            <text class="rp-who" :class="c.authorKind">{{ c.author || $t('editor.review.unknownAuthor') }}</text>
            <text v-if="c.linkedCount" class="rp-tag link">{{ $t('editor.review.linkedToRev', { count: c.linkedCount }) }}</text>
            <text v-if="c.resolved" class="rp-tag done">{{ $t('editor.review.resolved') }}</text>
            <text class="rp-date">{{ c.date || '' }}</text>
          </view>
          <text class="rp-text">{{ c.content }}</text>
          <text v-if="c.anchorText" class="rp-ctx">{{ $t('editor.review.anchor', { text: c.anchorText }) }}</text>
          <!-- 编辑和删除在随正文滚动的批注卡片中操作；此处保留汇总处置。 -->
          <view class="rp-acts">
            <text class="rp-act" @tap.stop="toggleResolved(c)">{{ c.resolved ? $t('editor.review.reopen') : $t('editor.review.resolve') }}</text>
          </view>
        </view>
      </template>

      <!-- 溯源：按段落序列出「首 40 字 · 谁 · 哪天 · 哪一版」。点行定位到那一段，
           点那一行的出处跳提交历史。 -->
      <template v-else-if="tab === 'prov'">
        <view v-if="provSummary" class="rp-prov-sum">{{ provSummary }}</view>
        <view v-if="provenance && provenance.truncated" class="rp-prov-note">{{ $t('version.provenanceTruncated') }}</view>
        <view v-if="!provRows.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('version.provenanceEmpty') }}</text>
        </view>
        <view v-for="row in provRows" :key="'p' + row.index" class="rp-card rp-prov" @tap="gotoParagraph(row)">
          <text class="rp-text">{{ row.snippet }}</text>
          <text
            class="rp-prov-from"
            :class="{ link: !!(row.unit && row.unit.sha) }"
            @tap.stop="openHistory(row)"
          >{{ row.label }}</text>
        </view>
      </template>
    </scroll-view>
  </view>
</template>

<script>
// ReviewPanel.vue — 修订与批注的审阅面板（编辑器右栏）。
//
// WHY: 页边显示（ShowChangesInMargin）把删除文本挪出正文解决了压字，但页边
// 小字读不到作者/时间，且同一表格行多格删除仍会在页边同高互叠（引擎按行绘制，
// 无跨格协调）。面板把修订的权威视图搬到右栏：看得全、点得到、能逐条处置。
//
// 数据全部来自 worker 原语（list_revisions / goto_revision / resolve_revision /
// resolve_revisions（批量） / resolve_all_revisions 与 list_comments 一族），
// executor 由宿主编辑器注入。
// 每次处置后重新拉清单——redline 的索引就是枚举序，处置一条后其余会前移。
//
// dev-board#377 补齐成 Word 式审阅窗格的三个维度（判定全在
// utils/reviewGrouping.js 的纯函数里，本组件只渲染与发命令）：
//   作者  卡片带色条 + 作者标识，顶部四桶筛选（全部/AI/我/其他人）；
//   类型  引擎 RedlineType 如实映射成 插入/删除/格式/段落格式，认不出的原样显示；
//   理由  位置上与修订重叠/相接的批注挂进卡片，处置后顺手标记为已解决。
import EvidencePanel from '@/components/EvidencePanel.vue'
import InlineReviewPanel from '@/components/InlineReviewPanel.vue'
import {
  groupRevisions, countByAuthorKind, filterByAuthorKind, linkCommentsToRevisions, authorKind,
} from '@/utils/reviewGrouping.js'
import { provenanceLabel } from '@/utils/provenanceAlign.js'

// RedlineType 归一后的显示键 → i18n 键。插入/删除沿用旧键（文案不变）。
const TYPE_I18N = {
  insert: 'editor.review.insertion',
  delete: 'editor.review.deletion',
  format: 'editor.review.typeFormat',
  paraFormat: 'editor.review.typeParaFormat',
}
const TYPE_CLASS = { insert: 'ins', delete: 'del', format: 'fmt', paraFormat: 'pfmt', other: 'oth' }

// 围栏快照：只带 worker 围栏（office_thread.js 的 matchRevisionSnapshot /
// matchCommentSnapshot）实际比对的字段，逐个取值拼成普通对象。
// WHY：g.items 取自响应式的 this.revisions，每一项都是 Vue Proxy，而命令要过
// 结构化克隆（Electron webview.send 的 IPC / iframe postMessage）——Proxy 过不去
// （DataCloneError）。webview 下 send() 返回的 Promise 被拒、无人接，relay 干等满
// resolve_revisions 的 120s 预算，其间 resolving 把面板按钮全部锁死。
const REVISION_FENCE_FIELDS = ['index', 'identifier', 'type', 'text', 'author', 'timestamp']
const COMMENT_FENCE_FIELDS = ['id', 'author', 'content', 'timestamp', 'anchorText', 'resolved']
function fenceSnapshot(src, fields) {
  const out = {}
  for (const k of fields) out[k] = src[k]
  return out
}

export default {
  name: 'ReviewPanel',
  components: { EvidencePanel, InlineReviewPanel },
  emits: ['close', 'changed', 'locate', 'merge-state', 'open-other-version', 'open-history', 'inline-review'],
  props: {
    documentLocation: { type: Object, default: () => ({}) },
    // LibreOffice executor（executeCommand(action, params)）。null 时面板静默。
    executor: { type: Object, default: null },
    // 宿主用它在文档改动后要求刷新（自增数字即可）。
    refreshKey: { type: Number, default: 0 },
    // 「底稿」页要的：项目与当前文档（ProjectFile.id）。缺省时底稿页为空。
    projectId: { type: [Number, String], default: null },
    docFileId: { type: [Number, String], default: null },
    // 当前登录用户名——「我」这一桶的判据。与宿主 load_document 传给引擎的
    // authorName 同源（LibreOfficeEditor.currentAuthorName），拿不到时任何
    // 非 AI 的作者都算「其他人」，不把未署名的修订算到自己头上。
    selfAuthor: { type: String, default: '' },
    // ---- 合并比对稿模式（dev-board#630，spec §5.4）。默认 'review' 时下面这些全不生效 ----
    mode: { type: String, default: 'review' },
    // 同一段两边都改了：后端 analysis.overlaps 原样传下来 [{key, baseText, mainText, otherText}]
    mergeConflicts: { type: Array, default: () => [] },
    // 另一侧只改了格式、没被自动带过来的段：引擎 build_merge_draft 的 formatOnly [{paraKey, preview}]
    mergeFormatOnly: { type: Array, default: () => [] },
    // 两侧修订的作者名（引擎在比较时署上的），用来把每条修订归到哪一边
    mainAuthor: { type: String, default: '' },
    otherAuthor: { type: String, default: '' },
    // 两侧在这个语境里怎么称呼（「你」/「律师乙」/「案件库那边」…）。界面只说这两个词，
    // 不说 MAIN/DRAFT，也不显示 username。
    mainLabel: { type: String, default: '' },
    otherLabel: { type: String, default: '' },
    // 每处改动的时间 = 那一侧版本的提交时间；引擎给的修订日期是比较时刻，界面不用它。
    mainWhen: { type: String, default: '' },
    otherWhen: { type: String, default: '' },
    // 逐段溯源（dev-board#632）：{rows:[{index, text, unit}], summary, truncated, loading}。
    // null = 这份文件没有溯源可看（没开版本记录 / 老服务端），「溯源」标签不出现。
    provenance: { type: Object, default: null },
    // 即时审校的状态快照（inlineReviewHost 的 publish）。null = 这份文档没有审校，
    // 「审校」标签整个不出现。
    inlineReview: { type: Object, default: null },
  },
  data() {
    return {
      tab: 'rev', revisions: [], comments: [], error: '', resolving: false, evidenceCount: 0, inlineReviewCount: 0,
      reviewRevision: null, reviewDocumentSeq: null,
      authorFilter: 'all',
      // 合并模式的两笔账：块 1 每处选了哪一边，块 2 每条修订怎么处置的。
      // 「完成裁决」时由宿主用 collectDecisions 合成尾注清单。
      mergeChoices: {}, mergeOutcomes: [],
    }
  },
  computed: {
    isMerge() { return this.mode === 'merge' },
    mainSideLabel() { return this.mainLabel || this.$t('version.mergeSideMainDefault') },
    otherSideLabel() { return this.otherLabel || this.$t('version.mergeSideOtherDefault') },
    // 块 1：同一段两边都改了。三栏文字来自后端 analysis.overlaps（引擎里这一段
    // 只有主线侧的修订——另一侧刻意没重放，见 spec §5.1），所以对方那一栏必须
    // 从后端拿，不能从文档里读。
    mergeConflictRows() {
      return (this.mergeConflicts || []).map((c) => {
        const choice = this.mergeChoices[c.key] || ''
        return Object.assign({}, c, {
          choice,
          choiceLabel: this.conflictChoiceLabel(choice),
          where: this.unitLabel(c.key),
          paraKey: this.paraIndexOf(c.key),
        })
      })
    },
    mergeFormatRows() {
      return (this.mergeFormatOnly || []).map((f) => ({
        key: 'p' + f.paraKey, paraKey: f.paraKey, preview: f.preview || '',
        where: this.unitLabel('p' + f.paraKey),
      }))
    },
    // 块 2：修订按两侧分组。作者名对不上任何一侧的（律师自己在正文里手改出来的）
    // 不进这两组——它们既不是「你改的」也不是「律师乙改的」，逐处裁决的账里没有位置。
    mergeSideGroups() {
      const buckets = { M: [], T: [] }
      for (const g of this.allGroups) {
        const side = this.mergeSideOf(g.author)
        if (side) buckets[side].push(g)
      }
      return [
        { side: 'M', label: this.mainSideLabel, when: this.mainWhen, groups: buckets.M },
        { side: 'T', label: this.otherSideLabel, when: this.otherWhen, groups: buckets.T },
      ]
    },
    listLimitReached() {
      return this.tab === 'rev' ? this.revisions.length >= 500 : this.tab === 'cmt' && this.comments.length >= 500
    },
    activeCardId() {
      const loc = this.documentLocation || {}
      if (this.tab === 'cmt') return loc.commentIndex == null ? '' : 'rp-c' + loc.commentIndex
      if (this.tab !== 'rev') return ''
      const group = this.revisionGroups.find(g => g.items.some(r => r.index === loc.revisionIndex))
      return group ? 'rp-' + group.key : ''
    },
    // 批注 ↔ 修订的双向关联（同段落 + 区间相交/相接）。坐标由 worker 回传，
    // 表格单元格等跨 story 的区间定位不到（paraKey -1）时一律不关联。
    links() { return linkCommentsToRevisions(this.revisions, this.comments) },
    // 未筛选的全量分组——筛选与计数都基于它，tab 上的数字也用它。
    allGroups() {
      return groupRevisions(this.revisions, { reasons: this.links.reasons, selfAuthor: this.selfAuthor })
        .map(g => ({ ...g, revision: this.reviewRevision, documentSeq: this.reviewDocumentSeq }))
    },
    authorCounts() { return countByAuthorKind(this.allGroups) },
    authorFilters() {
      const c = this.authorCounts
      return [
        { kind: 'all', label: this.$t('editor.review.filterAll', { count: c.all }) },
        { kind: 'ai', label: this.$t('editor.review.filterAi', { count: c.ai }) },
        { kind: 'me', label: this.$t('editor.review.filterMe', { count: c.me }) },
        { kind: 'other', label: this.$t('editor.review.filterOther', { count: c.other }) },
      ]
    },
    revisionGroups() { return filterByAuthorKind(this.allGroups, this.authorFilter) },
    provSummary() { return (this.provenance && this.provenance.summary) || '' },
    provRows() {
      const rows = (this.provenance && this.provenance.rows) || []
      const t = (k, p) => this.$t(k, p)
      return rows.map((row) => ({
        index: row.index,
        unit: row.unit || null,
        snippet: (String(row.text || '').trim() || this.$t('editor.review.emptyText')).slice(0, 40),
        label: provenanceLabel(t, row.unit),
      }))
    },
    // 批注清单：标出「这条批注已经挂到 N 条修订上」，并按同一口径给出作者归类。
    commentRows() {
      const linked = this.links.linked
      return this.comments.map((c) => Object.assign({}, c, {
        linkedCount: (linked.get(c.index) || []).length,
        authorKind: authorKind(c.author, this.selfAuthor),
      }))
    },
  },
  watch: {
    executor: { handler() { this.reload() }, immediate: true },
    refreshKey() { this.reload() },
    // 合并模式下宿主的「完成裁决」按钮靠这条消息算可点与文案，所以清单一变就要报一次。
    revisions() { if (this.isMerge) this.emitMergeState() },
    mergeConflicts: { handler() { if (this.isMerge) this.emitMergeState() }, immediate: true },
    // 英文标签比中文长得多，一行放不下时标签行横向滚（绝不折行）。宿主也会直接
    // 切标签（正文浮球点开就落在「AI 审校」），那一下选中的标签可能正躲在滚动
    // 区外——界面看着没变，实际内容已经换了。切完把它滚进视野。
    tab() { this.$nextTick(() => this.revealActiveTab()) },
  },
  methods: {
    revealActiveTab() {
      const box = this.$refs.tabs
      const el = box && box.querySelector && box.querySelector('.rp-tab.on')
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
    },
    /** 宿主切标签用（正文浮球点开时要直接落在「审校」页）。合并模式没有标签。 */
    openTab(key) {
      if (this.isMerge || !key) return
      if (key === 'chk' && !this.inlineReview) return
      this.tab = key
    },
    // ---- 合并比对稿模式（dev-board#630） ------------------------------------
    /** 'p12' → 12；'t1.2.3' → null（表格单元不在正文段落序里，定位不过去） */
    paraIndexOf(key) {
      const m = /^p(\d+)$/.exec(String(key || ''))
      return m ? Number(m[1]) : null
    },
    /** 单元键 → 律师读得懂的位置：「第 13 段」/「表格里的一格」 */
    unitLabel(key) {
      const i = this.paraIndexOf(key)
      if (i === null) return this.$t('version.mergeUnitInTable')
      return this.$t('version.mergeUnitParagraph', { n: i + 1 })
    },
    conflictChoiceLabel(choice) {
      if (choice === 'main') return this.$t('version.mergeChoseSide', { side: this.mainSideLabel })
      if (choice === 'other') return this.$t('version.mergeChoseSide', { side: this.otherSideLabel })
      if (choice === 'self') return this.$t('version.mergeChoseSelf')
      return ''
    },
    /** 一条修订属于哪一侧。作者名是引擎在同一条命令内署上的（spike A2）。 */
    mergeSideOf(author) {
      const a = String(author || '')
      if (a && a === this.mainAuthor) return 'M'
      if (a && a === this.otherAuthor) return 'T'
      return ''
    },
    emitMergeState() {
      const conflictChoices = (this.mergeConflicts || []).map((c) => ({ key: c.key, choice: this.mergeChoices[c.key] || '' }))
      this.$emit('merge-state', {
        conflictChoices,
        revisionOutcomes: this.mergeOutcomes.slice(),
        pendingConflicts: conflictChoices.filter((c) => !c.choice).length,
        pendingRevisions: this.mergeSideGroups.reduce((n, s) => n + s.groups.length, 0),
      })
    },
    recordOutcome(side, group, action) {
      for (const r of group.items || []) {
        this.mergeOutcomes.push({
          paraKey: r.paraKey, inTable: !!r.inTable, side, action: action === 'accept' ? 'A' : 'R',
        })
      }
    },
    gotoParagraph(paraKey) {
      if (paraKey === null || paraKey === undefined) return
      this.run('select_paragraph', { index: paraKey })
    },
    /**
     * 块 1 三选一。
     * 「用你的」= 接受这一段主线侧的修订（另一侧本来就没重放进来）；
     * 「用律师乙的」= 一条 worker 命令里拒掉这一段现有修订、切成对方作者写入对方
     *   文字再接受（作者跨命令设不住，spike A2）；
     * 「自己改」= 光标定位过去，律师手改，这一处只记一个 X。
     */
    async chooseConflict(row, choice) {
      if (this.resolving) return
      this.resolving = true
      try {
        if (choice === 'main') {
          const items = this.revisionsInUnit(row)
          if (items.length) {
            const indices = items.map((r) => r.index).sort((a, b) => b - a)
            await this.run('resolve_revisions', { indices, action: 'accept' })
          }
        } else if (choice === 'other') {
          const res = await this.run('merge_take_other', {
            paraKey: row.paraKey, text: row.otherText || '', author: this.otherAuthor,
          })
          if (!res) return // run() 已经把失败写进红条；这一处仍算未处理，别记账
        } else if (choice === 'self') {
          this.gotoParagraph(row.paraKey)
        } else {
          return
        }
        this.mergeChoices = Object.assign({}, this.mergeChoices, { [row.key]: choice })
        this.$emit('changed')
        await this.reload()
        this.emitMergeState()
      } finally {
        this.resolving = false
      }
    },
    revisionsInUnit(row) {
      if (row.paraKey === null || row.paraKey === undefined) return []
      return this.revisions.filter((r) => r.paraKey === row.paraKey)
    },
    async resolveMergeGroup(side, group, action) {
      if (this.resolving) return
      this.recordOutcome(side.side, group, action)
      await this.resolveGroup(group, action)
      this.emitMergeState()
    },
    async resolveMergeSide(side, action) {
      if (this.resolving) return
      const groups = side.groups.slice()
      if (!groups.length) return
      this.resolving = true
      try {
        // 一侧可能有几十条，逐条 resolve_revision 会把 office 线程排满；
        // 按降序一次交给批量原语（处置一条后比它大的枚举序会前移，同 resolveGroup）。
        const indices = groups.flatMap((g) => g.items.map((r) => r.index)).sort((a, b) => b - a)
        const res = await this.run('resolve_revisions', { indices, action })
        // 引擎没接住这一批时不记账——记了就等于在尾注里说「这一侧已经处置完」，
        // 而文档里那几十条还原样躺着。
        if (!res) return
        for (const g of groups) this.recordOutcome(side.side, g, action)
        this.$emit('changed')
        await this.reload()
      } finally {
        this.resolving = false
      }
      this.emitMergeState()
    },
    /**
     * 「完成裁决」时把剩下没处理的修订全部接受（未拒绝即保留，Word 的默认语义），
     * 并把它们逐条记进账。返回最终两笔账，宿主交给 collectDecisions 合成尾注清单。
     */
    async acceptRemainingRevisions() {
      const sides = this.mergeSideGroups
      for (const s of sides) for (const g of s.groups) this.recordOutcome(s.side, g, 'accept')
      if (sides.some((s) => s.groups.length)) {
        await this.run('resolve_all_revisions', { action: 'accept' })
        await this.reload()
      }
      this.emitMergeState()
      return {
        conflictChoices: (this.mergeConflicts || []).map((c) => ({ key: c.key, choice: this.mergeChoices[c.key] || '' })),
        revisionOutcomes: this.mergeOutcomes.slice(),
      }
    },
    typeLabel(g) {
      // 认不出的类型原样显示引擎给的字符串——不猜，也不硬塞进「插入」。
      return TYPE_I18N[g.typeKey] ? this.$t(TYPE_I18N[g.typeKey]) : (g.type || this.$t('editor.review.typeOther'))
    },
    typeClass(g) { return TYPE_CLASS[g.typeKey] || 'oth' },
    authorLabel(g) {
      if (g.authorKind === 'me') return this.$t('editor.review.selfAuthor', { name: g.author })
      return g.author || this.$t('editor.review.unknownAuthor')
    },
    async run(action, params) {
      if (!this.executor) return null
      try {
        const r = await this.executor.executeCommand(action, params || {})
        if (r && r.success === false) { this.error = r.message || this.$t('editor.review.opFailed'); return null }
        this.error = ''
        return r
      } catch (e) {
        this.error = (e && e.message) || String(e)
        return null
      }
    },
    // 重读清单。两条纪律，都是 dev-board#460 的病灶：
    // ① **读失败不许清零**。run() 在超时 / success:false / 抛错时返回 null，旧实现
    //    无条件写 `|| []`，一次读失败就把 tab 打成「修订 0 / 批注 0」，而文档里
    //    躺着 AI 刚做的几十条修订——律师据此以为 AI 什么都没改。保留上一次的清单
    //    （过期但真实），错误照常置位由红条说出来。同款口径见 EvidencePanel.load()。
    // ② **重入 defer 而不是并发**。AI 改稿期间 modified 一次接一次，旧实现每次都
    //    再发一轮两条读命令，全堆到单事件循环的 office 线程上排在写命令后面，
    //    越忙越读不回来。在飞时只记一笔 _again，收尾时补跑一次——最后一次触发
    //    一定被读到（照 useEvidenceAnchors 的 state.recheck 写法）。
    async reload() {
      if (!this.executor) { this.revisions = []; this.comments = []; return }
      if (this._loading) { this._again = true; return }
      this._loading = true
      try {
        do {
          this._again = false
          const [rv, cm] = await Promise.all([this.run('list_revisions', { limit: 500 }), this.run('list_comments', { limit: 500 })])
          if (rv) {
            this.revisions = rv.revisions || []
            this.reviewRevision = rv.revision ?? null
            this.reviewDocumentSeq = rv.documentSeq ?? null
          }
          if (cm) this.comments = (cm.comments || []).map(c => ({ ...c, revision: cm.revision, documentSeq: cm.documentSeq }))
        } while (this._again)
      } finally {
        this._loading = false
        this._again = false
      }
    },
    goto(g) {
      const target = g.items[0]
      this.run('goto_revision', { index: target.index, ...(g.documentSeq == null ? {} : {
        identifier: target.identifier, documentSeq: g.documentSeq, revision: g.revision,
      }) })
    },
    gotoParagraph(row) {
      this.run('select_paragraph', { index: row.index })
    },
    openHistory(row) {
      const sha = row && row.unit && row.unit.sha
      if (!sha) return
      this.$emit('open-history', { sha })
    },
    gotoComment(c) {
      this.run('goto_comment', { id: c.id, index: c.index, documentSeq: c.documentSeq, revision: c.revision })
    },
    // 整组处置（尽调模块 P3 稳定性余项 #1，dev-board#100）：一次性把组内全部 index
    // 打包发给 resolve_revisions 批量原语，worker 侧一次建索引再批处理，不再对每个
    // 条目单独调 resolve_revision——旧实现里 worker 的 redlineAt(index) 每次都从头
    // 整棵重新枚举 getRedlines()，K 个条目就是 K 次 O(N) 重扫（O(K·N)），大文档里一张
    // 连续删除合并出的大卡片点一次「接受」就能卡住。
    // **仍要求 indices 按降序传给 worker**：index 是枚举序，处置掉一条之后比它大的
    // 索引全部前移一位，而比它小的不受影响——worker 侧按这个顺序逐条处置，语义与
    // 旧实现完全一致，只是一次网络往返代替 K 次。
    // 连点两次会并发跑两轮：两轮手里是同一份索引，第一轮处置完引擎里的索引已经
    // 前移，第二轮那份索引会打到别的修订上（引擎照样返回 success）。加重入闸，
    // 处置期间的重复点击直接忽略。
    async resolveGroup(g, action) {
      if (this.resolving) return
      this.resolving = true
      try {
        const indices = g.items.map((r) => r.index).sort((a, b) => b - a)
        const snapshot = g.documentSeq == null ? {} : {
          revision: g.revision, documentSeq: g.documentSeq,
          expectedRevisions: g.items.map((r) => fenceSnapshot(r, REVISION_FENCE_FIELDS)),
        }
        const res = await this.run('resolve_revisions', { indices, action, ...snapshot })
        const results = (res && res.results) || []
        const done = results.filter((r) => r && r.success).length
        if (done) {
          // 处置联动（dev-board#377）：这条修订的理由批注已经没有待办意义了，
          // 标记为已解决——**不删除**（删除会让「当初为什么这么改」永久消失，
          // 而且 .uno:DeleteComment 在宿主上下文里本来也够不着）。
          // 一条都没命中时不动批注：修订还在文档里，理由也还得留着。
          // 按 id 定位，不按 index——修订处置完重拉清单前，批注 index 未必仍对得上。
          await this.resolveReasons(g.reasons)
          this.$emit('changed')
        }
        // 引擎没命中的如实说，别让用户以为整组都处理完了
        if (done < indices.length) this.error = this.$t('editor.review.groupPartialFail', { total: indices.length, failed: indices.length - done })
        await this.reload()
      } finally {
        this.resolving = false
      }
    },
    async resolveReasons(reasons) {
      for (const c of (reasons || [])) {
        if (c.resolved) continue
        await this.run('set_comment_resolved', { id: c.id, index: c.index, resolved: true, ...(c.documentSeq == null ? {} : { documentSeq: c.documentSeq }) })
      }
    },
    async resolveAll(action) {
      const res = await this.run('resolve_all_revisions', { action })
      if (res) this.$emit('changed')
      await this.reload()
    },
    async toggleResolved(c) {
      const snapshot = c.documentSeq == null ? {} : {
        documentSeq: c.documentSeq, revision: c.revision, expectedComment: fenceSnapshot(c, COMMENT_FENCE_FIELDS),
      }
      const res = await this.run('set_comment_resolved', { id: c.id, index: c.index, resolved: !c.resolved, ...snapshot })
      if (res) this.$emit('changed')
      await this.reload()
    },
  },
}
</script>

<style scoped>
/* 320px（原 288）：五个标签 + 收起箭头排一行要这么宽（dev-board#754）。
   这个数与 LibreOfficeEditor 里浮层的让位宽度是同一个，改这里要一起改那边。 */
.rp { display: flex; flex-direction: column; width: 320px; height: 100%; background: var(--awd-bg);
  border-left: 1px solid var(--awd-border); }
.rp-head { display: flex; align-items: center; gap: 4px; padding: 6px 8px 6px 6px;
  border-bottom: 1px solid var(--awd-border); }
.rp-collapse { flex: none; width: 24px; height: 24px; display: flex; align-items: center; justify-content: center;
  border-radius: 6px; color: var(--awd-text-2); cursor: pointer; }
.rp-collapse:hover { background: var(--awd-surface-2); color: var(--awd-text); }
.rp-collapse-ico { width: 16px; height: 16px; display: block; }
/* 一行放不下时（英文标签长得多）横向滚，绝不折行：折行会把每个标签挤成两行。 */
.rp-tabs { flex: 1; min-width: 0; display: flex; flex-wrap: nowrap; gap: 2px; overflow-x: auto; }
.rp-tab { flex: none; white-space: nowrap; padding: 3px 7px; border-radius: 6px;
  font-size: 12px; color: var(--awd-text-2); }
.rp-tab.on { background: var(--awd-accent-soft); color: var(--awd-accent-text); font-weight: 600; }
/* 计数与标签同一行、同一条基线，只是小一号淡一档。间距用 margin 不用 flex gap：
   uni-h5 下 <text> 的内容真正落在内层 span 里，外层的 gap 够不着它。 */
.rp-tab-n { margin-left: 3px; font-size: 10.5px; color: var(--awd-text-3); }
.rp-tab.on .rp-tab-n { color: var(--awd-accent-text); }
.rp-bulk { display: flex; gap: 6px; padding: 8px 10px 0; }
.rp-bulk-btn { flex: 1; text-align: center; padding: 4px 0; border: 1px solid var(--awd-border); border-radius: 6px;
  font-size: 12px; color: var(--awd-text-2); background: var(--awd-surface); }
.rp-filter { padding: 8px 10px 0; white-space: nowrap; }
.rp-filter-row { display: flex; gap: 5px; }
.rp-chip { flex: none; padding: 2px 8px; border: 1px solid var(--awd-border); border-radius: 999px;
  font-size: 11px; color: var(--awd-text-2); background: var(--awd-surface); }
.rp-chip.on { background: var(--awd-accent-soft); border-color: var(--awd-accent); color: var(--awd-accent-text); font-weight: 600; }
.rp-error { margin: 8px 10px 0; padding: 6px 8px; border-radius: 6px; background: var(--awd-danger-soft); color: var(--awd-danger-text); font-size: 12px; }
.rp-limit { margin: 8px 10px 0; font-size: 12px; color: var(--awd-text-2); }
.rp-list { flex: 1; min-height: 0; padding: 8px 10px; }
.rp-empty { padding: 28px 6px; display: flex; flex-direction: column; gap: 6px; }
.rp-empty-t { font-size: 13px; color: var(--awd-text-2); }
.rp-empty-s { font-size: 12px; color: var(--awd-text-3); line-height: 1.5; }
.rp-card { margin-bottom: 8px; padding: 8px 9px; background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 8px; }
/* 作者色条：一眼分出「AI 改的 / 我改的 / 别人改的」 */
.rp-card.k-ai { border-left: 3px solid var(--awd-accent); }
.rp-card.k-me { border-left: 3px solid var(--awd-info); }
.rp-card.k-other { border-left: 3px solid var(--awd-border-strong); }
.rp-card.active { border-color: var(--awd-accent-text); }
.rp-card.done { opacity: 0.6; }
.rp-card-top { display: flex; align-items: center; gap: 6px; margin-bottom: 5px; flex-wrap: wrap; }
.rp-who { padding: 1px 6px; border-radius: 4px; font-size: 11px; background: var(--awd-surface-2); color: var(--awd-text-2); }
.rp-who.ai { background: var(--awd-accent-soft); color: var(--awd-accent-text); font-weight: 600; }
.rp-who.me { background: var(--awd-info-soft); color: var(--awd-info-text); }
.rp-tag { padding: 1px 6px; border-radius: 4px; font-size: 11px; }
.rp-tag.ins { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.rp-tag.del { background: var(--awd-danger-soft); color: var(--awd-danger-text); }
.rp-tag.fmt, .rp-tag.pfmt { background: var(--awd-warning-soft); color: var(--awd-warning-text); }
.rp-tag.oth { background: var(--awd-surface-2); color: var(--awd-text-2); }
.rp-tag.tbl { background: var(--awd-info-soft); color: var(--awd-info-text); }
.rp-tag.cnt { background: var(--awd-surface-2); color: var(--awd-text-2); }
.rp-tag.link { background: var(--awd-accent-wash); color: var(--awd-accent-text); }
.rp-tag.done { background: var(--awd-surface-2); color: var(--awd-text-2); }
.rp-date { font-size: 11px; color: var(--awd-text-3); margin-left: auto; }
.rp-text { display: block; font-size: 13px; color: var(--awd-text); line-height: 1.5; word-break: break-all; }
.rp-text.del { text-decoration: line-through; color: var(--awd-danger-text); }
.rp-desc { display: block; margin-top: 3px; font-size: 11px; color: var(--awd-text-2); }
.rp-ctx { display: block; margin-top: 4px; font-size: 11px; color: var(--awd-text-3); line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.rp-reason { margin-top: 6px; padding: 5px 7px; border-radius: 6px; background: var(--awd-surface-2);
  border-left: 2px solid var(--awd-accent); }
.rp-reason-h { display: block; font-size: 10px; color: var(--awd-text-3); margin-bottom: 2px; }
.rp-reason-t { display: block; font-size: 12px; color: var(--awd-text-2); line-height: 1.45; }
.rp-acts { display: flex; gap: 6px; margin-top: 7px; }
.rp-act { padding: 2px 10px; border: 1px solid var(--awd-border); border-radius: 6px; font-size: 12px; color: var(--awd-text-2); }
.rp-act.ok { border-color: var(--awd-mint); color: var(--awd-accent-text); }
.rp-act.no { border-color: var(--awd-danger); color: var(--awd-danger-text); }

/* 合并比对稿模式的三块（dev-board#630） */
.rp-merge-title { font-size: 12px; font-weight: 600; color: var(--awd-text); }
.rp-sec { margin-bottom: 14px; }
.rp-sec-h { display: block; font-size: 12px; font-weight: 600; color: var(--awd-text); margin: 4px 0 6px; }
.rp-sec-s { display: block; font-size: 11px; color: var(--awd-text-3); line-height: 1.5; margin-bottom: 6px; }
.rp-sub { margin-bottom: 10px; }
.rp-sub-h { display: block; font-size: 11px; color: var(--awd-text-2); margin: 6px 0 4px; }
.rp-conflict { border-left: 3px solid var(--awd-warning); }
.rp-three { display: flex; flex-direction: column; gap: 5px; margin: 4px 0 2px; }
.rp-three-col { padding: 5px 7px; border-radius: 6px; background: var(--awd-surface-2); }
.rp-three-h { display: block; font-size: 10px; color: var(--awd-text-3); margin-bottom: 2px; }
.rp-three-t { display: block; font-size: 12px; color: var(--awd-text); line-height: 1.45; word-break: break-all; }
/* 溯源列表：一行一段，上面是段落首 40 字，下面是它的出处。 */
.rp-prov { cursor: pointer; }
.rp-prov-sum { padding: 8px 10px; font-size: 11.5px; color: var(--awd-text-2); }
.rp-prov-note { padding: 0 10px 8px; font-size: 11px; color: var(--awd-text-3); line-height: 1.6; }
.rp-prov-from { display: block; margin-top: 4px; font-size: 11px; color: var(--awd-text-3); }
.rp-prov-from.link { color: var(--awd-accent-text); text-decoration: underline; }
</style>
