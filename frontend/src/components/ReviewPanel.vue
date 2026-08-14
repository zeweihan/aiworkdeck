<template>
  <view class="rp">
    <view class="rp-head">
      <view class="rp-tabs">
        <text class="rp-tab" :class="{ on: tab === 'rev' }" @tap="tab = 'rev'">{{ $t('editor.review.revTab', { count: revisions.length }) }}</text>
        <text class="rp-tab" :class="{ on: tab === 'cmt' }" @tap="tab = 'cmt'">{{ $t('editor.review.cmtTab', { count: comments.length }) }}</text>
      </view>
      <text class="rp-close" @tap="$emit('close')">{{ $t('editor.review.collapse') }}</text>
    </view>

    <view v-if="tab === 'rev' && revisions.length" class="rp-bulk">
      <text class="rp-bulk-btn" @tap="resolveAll('accept')">{{ $t('editor.review.acceptAll') }}</text>
      <text class="rp-bulk-btn" @tap="resolveAll('reject')">{{ $t('editor.review.rejectAll') }}</text>
    </view>

    <view v-if="error" class="rp-error">{{ error }}</view>

    <scroll-view class="rp-list" scroll-y>
      <!-- 修订 -->
      <template v-if="tab === 'rev'">
        <view v-if="!revisions.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyRevTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyRevSub') }}</text>
        </view>
        <view v-for="r in revisions" :key="'r' + r.index" class="rp-card" @tap="goto(r)">
          <view class="rp-card-top">
            <text class="rp-tag" :class="r.type === 'Delete' ? 'del' : 'ins'">{{ r.type === 'Delete' ? $t('editor.review.deletion') : $t('editor.review.insertion') }}</text>
            <text v-if="r.inTable" class="rp-tag tbl">{{ $t('editor.review.table') }}</text>
            <text class="rp-author">{{ r.author || $t('editor.review.unknownAuthor') }}</text>
            <text class="rp-date">{{ r.date || '' }}</text>
          </view>
          <text class="rp-text" :class="{ del: r.type === 'Delete' }">{{ r.text || $t('editor.review.emptyText') }}</text>
          <text v-if="r.paragraph" class="rp-ctx">{{ r.paragraph }}</text>
          <view class="rp-acts">
            <text class="rp-act ok" @tap.stop="resolve(r, 'accept')">{{ $t('editor.review.accept') }}</text>
            <text class="rp-act no" @tap.stop="resolve(r, 'reject')">{{ $t('editor.review.reject') }}</text>
          </view>
        </view>
      </template>

      <!-- 批注 -->
      <template v-else>
        <view v-if="!comments.length" class="rp-empty">
          <text class="rp-empty-t">{{ $t('editor.review.emptyCmtTitle') }}</text>
          <text class="rp-empty-s">{{ $t('editor.review.emptyCmtSub') }}</text>
        </view>
        <view v-for="c in comments" :key="'c' + c.index" class="rp-card" :class="{ done: c.resolved }" @tap="gotoComment(c)">
          <view class="rp-card-top">
            <text class="rp-author">{{ c.author || $t('editor.review.unknownAuthor') }}</text>
            <text class="rp-date">{{ c.date || '' }}</text>
            <text v-if="c.resolved" class="rp-tag done">{{ $t('editor.review.resolved') }}</text>
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
// resolve_all_revisions 与 list_comments 一族），executor 由宿主编辑器注入。
// 每次处置后重新拉清单——redline 的索引就是枚举序，处置一条后其余会前移。
export default {
  name: 'ReviewPanel',
  emits: ['close', 'changed'],
  props: {
    // LibreOffice executor（executeCommand(action, params)）。null 时面板静默。
    executor: { type: Object, default: null },
    // 宿主用它在文档改动后要求刷新（自增数字即可）。
    refreshKey: { type: Number, default: 0 },
  },
  data() {
    return { tab: 'rev', revisions: [], comments: [], error: '' }
  },
  watch: {
    executor: { handler() { this.reload() }, immediate: true },
    refreshKey() { this.reload() },
  },
  methods: {
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
    goto(r) { this.run('goto_revision', { index: r.index }) },
    gotoComment(c) { this.run('goto_comment', { index: c.index }) },
    async resolve(r, action) {
      const res = await this.run('resolve_revision', { index: r.index, action })
      if (res) this.$emit('changed')
      await this.reload()
    },
    async resolveAll(action) {
      const res = await this.run('resolve_all_revisions', { action })
      if (res) this.$emit('changed')
      await this.reload()
    },
    async toggleResolved(c) {
      const res = await this.run('set_comment_resolved', { index: c.index, resolved: !c.resolved })
      if (res) this.$emit('changed')
      await this.reload()
    },
  },
}
</script>

<style scoped>
.rp { display: flex; flex-direction: column; width: 288px; height: 100%; background: #FBFCFD;
  border-left: 1px solid #E9ECEF; }
.rp-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px;
  border-bottom: 1px solid #E9ECEF; }
.rp-tabs { display: flex; gap: 4px; }
.rp-tab { padding: 3px 9px; border-radius: 6px; font-size: 12px; color: #495057; }
.rp-tab.on { background: #E6F9F0; color: #1A5336; font-weight: 600; }
.rp-close { font-size: 12px; color: #868E96; }
.rp-bulk { display: flex; gap: 6px; padding: 8px 10px 0; }
.rp-bulk-btn { flex: 1; text-align: center; padding: 4px 0; border: 1px solid #DEE2E6; border-radius: 6px;
  font-size: 12px; color: #495057; background: #fff; }
.rp-error { margin: 8px 10px 0; padding: 6px 8px; border-radius: 6px; background: #FEF2F2; color: #991B1B; font-size: 12px; }
.rp-list { flex: 1; min-height: 0; padding: 8px 10px; }
.rp-empty { padding: 28px 6px; display: flex; flex-direction: column; gap: 6px; }
.rp-empty-t { font-size: 13px; color: #495057; }
.rp-empty-s { font-size: 12px; color: #ADB5BD; line-height: 1.5; }
.rp-card { margin-bottom: 8px; padding: 8px 9px; background: #fff; border: 1px solid #E9ECEF; border-radius: 8px; }
.rp-card.done { opacity: 0.6; }
.rp-card-top { display: flex; align-items: center; gap: 6px; margin-bottom: 5px; flex-wrap: wrap; }
.rp-tag { padding: 1px 6px; border-radius: 4px; font-size: 11px; }
.rp-tag.ins { background: #E6F9F0; color: #1A5336; }
.rp-tag.del { background: #FEF2F2; color: #991B1B; }
.rp-tag.tbl { background: #EEF2FF; color: #3730A3; }
.rp-tag.done { background: #F1F3F5; color: #868E96; }
.rp-author { font-size: 12px; color: #495057; }
.rp-date { font-size: 11px; color: #ADB5BD; margin-left: auto; }
.rp-text { display: block; font-size: 13px; color: #2C3338; line-height: 1.5; word-break: break-all; }
.rp-text.del { text-decoration: line-through; color: #991B1B; }
.rp-ctx { display: block; margin-top: 4px; font-size: 11px; color: #ADB5BD; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.rp-acts { display: flex; gap: 6px; margin-top: 7px; }
.rp-act { padding: 2px 10px; border: 1px solid #DEE2E6; border-radius: 6px; font-size: 12px; color: #495057; }
.rp-act.ok { border-color: #5BD197; color: #1A5336; }
.rp-act.no { border-color: #FCA5A5; color: #991B1B; }
</style>
