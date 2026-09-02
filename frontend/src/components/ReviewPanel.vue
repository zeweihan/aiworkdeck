<template>
  <view class="rp">
    <view class="rp-head">
      <view class="rp-tabs">
        <text class="rp-tab" :class="{ on: tab === 'rev' }" @tap="tab = 'rev'">{{ $t('editor.review.revTab', { count: allGroups.length }) }}</text>
        <text class="rp-tab" :class="{ on: tab === 'cmt' }" @tap="tab = 'cmt'">{{ $t('editor.review.cmtTab', { count: comments.length }) }}</text>
        <text class="rp-tab" :class="{ on: tab === 'evd' }" @tap="tab = 'evd'">{{ $t('editor.review.evidenceTab', { count: evidenceCount }) }}</text>
      </view>
      <text class="rp-close" @tap="$emit('close')">{{ $t('editor.review.collapse') }}</text>
    </view>

    <view v-if="tab === 'rev' && revisions.length" class="rp-bulk">
      <text class="rp-bulk-btn" @tap="resolveAll('accept')">{{ $t('editor.review.acceptAll') }}</text>
      <text class="rp-bulk-btn" @tap="resolveAll('reject')">{{ $t('editor.review.rejectAll') }}</text>
    </view>

    <!-- 作者筛选：多方修订混在一份文档里时，先按「谁改的」收窄再逐条看。
         四个桶的数字恒按未筛选的全量算，切了筛选也不变（否则没法用它判断
         「还有几条别人的改动没看」）。 -->
    <scroll-view v-if="tab === 'rev' && revisions.length" class="rp-filter" scroll-x>
      <view class="rp-filter-row">
        <text v-for="f in authorFilters" :key="f.kind" class="rp-chip" :class="[f.kind, { on: authorFilter === f.kind }]"
              @tap="authorFilter = f.kind">{{ f.label }}</text>
      </view>
    </scroll-view>

    <view v-if="error && tab !== 'evd'" class="rp-error">{{ error }}</view>

    <!-- 底稿页：独立组件、v-show 常驻（tab 上要显示计数，且切页不丢筛选/折叠态） -->
    <EvidencePanel
      v-show="tab === 'evd'"
      :executor="executor"
      :project-id="projectId"
      :doc-file-id="docFileId"
      @count="evidenceCount = $event"
      @locate="$emit('locate', $event)"
      @changed="$emit('changed')"
    />

    <scroll-view v-show="tab !== 'evd'" class="rp-list" scroll-y>
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
        <view v-for="g in revisionGroups" :key="g.key" class="rp-card" :class="'k-' + g.authorKind" @tap="goto(g)">
          <view class="rp-card-top">
            <text class="rp-who" :class="g.authorKind">{{ authorLabel(g) }}</text>
            <text class="rp-tag" :class="typeClass(g)">{{ typeLabel(g) }}</text>
            <text v-if="g.inTable" class="rp-tag tbl">{{ $t('editor.review.table') }}</text>
            <text v-if="g.items.length > 1" class="rp-tag cnt">{{ $t('editor.review.contiguousCount', { count: g.items.length }) }}</text>
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
      <template v-else>
        <view v-if="!comments.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyCmtTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyCmtSub') }}</text>
        </view>
        <view v-for="c in commentRows" :key="'c' + c.index" class="rp-card" :class="{ done: c.resolved }" @tap="gotoComment(c)">
          <view class="rp-card-top">
            <text class="rp-who" :class="c.authorKind">{{ c.author || $t('editor.review.unknownAuthor') }}</text>
            <text v-if="c.linkedCount" class="rp-tag link">{{ $t('editor.review.linkedToRev', { count: c.linkedCount }) }}</text>
            <text v-if="c.resolved" class="rp-tag done">{{ $t('editor.review.resolved') }}</text>
            <text class="rp-date">{{ c.date || '' }}</text>
          </view>
          <text class="rp-text">{{ c.content }}</text>
          <text v-if="c.anchorText" class="rp-ctx">{{ $t('editor.review.anchor', { text: c.anchorText }) }}</text>
          <!-- 没有「删除」按钮：引擎的 .uno:DeleteComment 按活动批注窗口找 Id，
               在宿主加载出来的文档上下文里够不着（真机四轮验证），做不到就不放
               按钮——删除批注请用编辑器自身批注栏的右键菜单。 -->
          <view class="rp-acts">
            <text class="rp-act" @tap.stop="toggleResolved(c)">{{ c.resolved ? $t('editor.review.reopen') : $t('editor.review.resolve') }}</text>
          </view>
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
import {
  groupRevisions, countByAuthorKind, filterByAuthorKind, linkCommentsToRevisions, authorKind,
} from '@/utils/reviewGrouping.js'

// RedlineType 归一后的显示键 → i18n 键。插入/删除沿用旧键（文案不变）。
const TYPE_I18N = {
  insert: 'editor.review.insertion',
  delete: 'editor.review.deletion',
  format: 'editor.review.typeFormat',
  paraFormat: 'editor.review.typeParaFormat',
}
const TYPE_CLASS = { insert: 'ins', delete: 'del', format: 'fmt', paraFormat: 'pfmt', other: 'oth' }

export default {
  name: 'ReviewPanel',
  components: { EvidencePanel },
  emits: ['close', 'changed', 'locate'],
  props: {
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
  },
  data() {
    return {
      tab: 'rev', revisions: [], comments: [], error: '', resolving: false, evidenceCount: 0,
      authorFilter: 'all',
    }
  },
  computed: {
    // 批注 ↔ 修订的双向关联（同段落 + 区间相交/相接）。坐标由 worker 回传，
    // 表格单元格等跨 story 的区间定位不到（paraKey -1）时一律不关联。
    links() { return linkCommentsToRevisions(this.revisions, this.comments) },
    // 未筛选的全量分组——筛选与计数都基于它，tab 上的数字也用它。
    allGroups() {
      return groupRevisions(this.revisions, { reasons: this.links.reasons, selfAuthor: this.selfAuthor })
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
  },
  methods: {
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
    async reload() {
      if (!this.executor) { this.revisions = []; this.comments = []; return }
      const [rv, cm] = await Promise.all([this.run('list_revisions', {}), this.run('list_comments', {})])
      this.revisions = (rv && rv.revisions) || []
      this.comments = (cm && cm.comments) || []
    },
    goto(g) { this.run('goto_revision', { index: g.items[0].index }) },
    gotoComment(c) { this.run('goto_comment', { index: c.index }) },
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
        const res = await this.run('resolve_revisions', { indices, action })
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
        await this.run('set_comment_resolved', { id: c.id, index: c.index, resolved: true })
      }
    },
    async resolveAll(action) {
      const res = await this.run('resolve_all_revisions', { action })
      if (res) this.$emit('changed')
      await this.reload()
    },
    async toggleResolved(c) {
      const res = await this.run('set_comment_resolved', { id: c.id, index: c.index, resolved: !c.resolved })
      if (res) this.$emit('changed')
      await this.reload()
    },
  },
}
</script>

<style scoped>
.rp { display: flex; flex-direction: column; width: 288px; height: 100%; background: var(--awd-bg);
  border-left: 1px solid var(--awd-border); }
.rp-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px;
  border-bottom: 1px solid var(--awd-border); }
.rp-tabs { display: flex; gap: 4px; }
.rp-tab { padding: 3px 9px; border-radius: 6px; font-size: 12px; color: var(--awd-text-2); }
.rp-tab.on { background: var(--awd-accent-soft); color: var(--awd-accent-text); font-weight: 600; }
.rp-close { font-size: 12px; color: var(--awd-text-2); }
.rp-bulk { display: flex; gap: 6px; padding: 8px 10px 0; }
.rp-bulk-btn { flex: 1; text-align: center; padding: 4px 0; border: 1px solid var(--awd-border); border-radius: 6px;
  font-size: 12px; color: var(--awd-text-2); background: var(--awd-surface); }
.rp-filter { padding: 8px 10px 0; white-space: nowrap; }
.rp-filter-row { display: flex; gap: 5px; }
.rp-chip { flex: none; padding: 2px 8px; border: 1px solid var(--awd-border); border-radius: 999px;
  font-size: 11px; color: var(--awd-text-2); background: var(--awd-surface); }
.rp-chip.on { background: var(--awd-accent-soft); border-color: var(--awd-accent); color: var(--awd-accent-text); font-weight: 600; }
.rp-error { margin: 8px 10px 0; padding: 6px 8px; border-radius: 6px; background: var(--awd-danger-soft); color: var(--awd-danger-text); font-size: 12px; }
.rp-list { flex: 1; min-height: 0; padding: 8px 10px; }
.rp-empty { padding: 28px 6px; display: flex; flex-direction: column; gap: 6px; }
.rp-empty-t { font-size: 13px; color: var(--awd-text-2); }
.rp-empty-s { font-size: 12px; color: var(--awd-text-3); line-height: 1.5; }
.rp-card { margin-bottom: 8px; padding: 8px 9px; background: var(--awd-surface); border: 1px solid var(--awd-border); border-radius: 8px; }
/* 作者色条：一眼分出「AI 改的 / 我改的 / 别人改的」 */
.rp-card.k-ai { border-left: 3px solid var(--awd-accent); }
.rp-card.k-me { border-left: 3px solid var(--awd-info); }
.rp-card.k-other { border-left: 3px solid var(--awd-border-strong); }
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
</style>
