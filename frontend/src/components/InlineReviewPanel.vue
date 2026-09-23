<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="irp">
    <!-- 顶栏：这轮检查的状态 + AI 开关/重新检查/立即 AI 审校/浮球收起与展开。
         开关只管 AI 那一层：规则检查始终在跑，它不调模型也不花钱（dev-board#749）。 -->
    <view class="irp-top">
      <text class="irp-note">{{ noteText }}</text>
      <view class="irp-top-acts">
        <text class="irp-act sw" :class="{ off: !aiEnabled }" @tap="toggleAi">
          {{ aiEnabled ? $t('editor.inlineReview.aiDisable') : $t('editor.inlineReview.aiEnable') }}
        </text>
        <text class="irp-act" :class="{ disabled: checking }" @tap="refresh">{{ $t('editor.inlineReview.refresh') }}</text>
        <text v-if="writable" class="irp-act deep" :class="{ disabled: deepBusy }" @tap="runDeep">
          {{ deepBusy ? $t('editor.inlineReview.deepBusy') : $t('editor.inlineReview.deep') }}
        </text>
        <text class="irp-act" @tap="toggleBall">
          {{ ballCollapsed ? $t('editor.inlineReview.expandBall') : $t('editor.inlineReview.collapseBall') }}
        </text>
      </view>
    </view>

    <!-- 分类：计数恒按未筛选的全量算（切分类不变），与审阅标签上的数字同源。 -->
    <view class="irp-tabs">
      <text v-for="b in BUCKETS" :key="b" class="irp-tab" :class="{ on: bucket === b }" @tap="bucket = b">
        {{ $t('editor.inlineReview.bucket.' + b) }} {{ counts[b] }}
      </text>
    </view>

    <view v-if="notice" class="irp-flash">{{ notice }}</view>

    <scroll-view class="irp-list" scroll-y>
      <!-- AI 关着时「AI 审校」这一桶天然是空的，说清是为什么空——否则看起来像
           AI 看过一遍、什么都没发现。 -->
      <view v-if="!rows.length && !aiEnabled && bucket === 'ai'" class="irp-empty">
        <text class="irp-empty-t">{{ $t('editor.inlineReview.offTitle') }}</text>
        <text class="irp-empty-s">{{ $t('editor.inlineReview.offHint') }}</text>
      </view>
      <view v-else-if="!rows.length" class="irp-empty">
        <text class="irp-empty-t">{{ fresh ? $t('editor.inlineReview.empty') : statusText }}</text>
      </view>

      <view v-for="f in rows" :key="f.id" class="irp-item" :class="['sev-' + (f.severity || 'warn'), { stale: !fresh }]">
        <view class="irp-item-top">
          <text class="irp-item-t">{{ f.title || f.kind }}</text>
          <text class="irp-bucket">{{ $t('editor.inlineReview.bucket.' + bucketOf(f)) }}</text>
        </view>
        <text v-if="f.quote" class="irp-quote">{{ f.quote }}</text>
        <text v-if="f.message" class="irp-text">{{ f.message }}</text>

        <!-- 关联位置：同一条问题牵涉的另一处正文（前后数量对不上这类）。 -->
        <view v-for="(r, i) in f.related || []" :key="'r' + i" class="irp-related">
          <text class="irp-quote">{{ r.quote }}</text>
          <text v-if="fresh && locatable(r)" class="irp-act" @tap="locate(r)">{{ $t('editor.inlineReview.locate') }}</text>
        </view>

        <!-- 过期条目（正文已改、新一轮还没回来）：显式说明并禁用定位/采用——
             旧 revision 的段落偏移落在改过的正文上会改错地方。 -->
        <text v-if="!fresh" class="irp-stale">{{ $t('editor.inlineReview.staleItem') }}</text>
        <view class="irp-acts">
          <text v-if="locatable(f)" class="irp-act" :class="{ disabled: !fresh || busy }" @tap="locate(f)">{{ $t('editor.inlineReview.locate') }}</text>
          <text v-if="applicable(f)" class="irp-act ok" :class="{ disabled: !fresh || busy }" @tap="apply(f)">{{ $t('editor.inlineReview.apply') }}</text>
          <text class="irp-act" @tap="ignore(f)">{{ $t('editor.inlineReview.ignore') }}</text>
        </view>
      </view>

      <text class="irp-scope">{{ $t('editor.inlineReview.scope') }}</text>
      <text v-if="truncated" class="irp-scope warn">{{ $t('editor.inlineReview.truncated') }}</text>
    </scroll-view>
  </view>
</template>

<script>
// InlineReviewPanel.vue — AI 审校清单（审阅面板第五个标签「AI 审校」，
// dev-board#723/#724，命名与开关归属见 #749）。
//
// WHY：清单原来是正文上一个 380px 的浮窗，压着字、关不掉，而律师读的是正文。
// 搬进右栏之后正文只剩一颗浮球；这里是唯一的清单视图。
//
// 两层一张清单：规则检查（结构、编号、交叉引用、算式、证件号码）始终在跑，
// 顶栏那个开关只管 AI 那一层——它要扣 Credits，所以必须能关。
//
// 数据来自宿主的 inlineReviewHost（worker 快照 + /insight/review 的规则结果），
// 由 LibreOfficeEditor 经 ReviewPanel 以 state 下传；本组件不自己发请求、不自己
// 读正文。定位走 goto_review_range、采用走 apply_review_edit，两者都带
// revision + 段落原文 + 引文三重围栏，**不许**改用 find_text_locations
// （那个会把书签写进 docx）。
import {
  REVIEW_BUCKETS, bucketOf, filterByBucket, countByBucket, visibleFindings,
  isFresh, isLocatable, isApplicable,
} from '@/utils/inlineReviewGrouping.js'

// 后端 DocInsightService.DEEP_REASON_* 下发的码。表里没有的只显示基础那句，
// 绝不把码本身露给用户。
const DEEP_REASONS = new Set([
  'DEEP_TIMEOUT', 'DEEP_BUDGET', 'DEEP_UNPARSEABLE', 'DEEP_UPSTREAM', 'DEEP_NETWORK',
  'DEEP_RATE_LIMITED', 'DEEP_QUOTA', 'DEEP_TOO_LONG', 'DEEP_MODEL_UNAVAILABLE', 'DEEP_REGION', 'DEEP_FAILED',
])
const ERRORS = new Set(['REVIEW_INLINE_REVISIONS', 'REVIEW_SNAPSHOT_FAILED', 'REVIEW_FAILED', 'REVIEW_DEEP_INCOMPLETE'])

export default {
  name: 'InlineReviewPanel',
  emits: ['count', 'changed', 'action'],
  props: {
    // 宿主 inlineReviewHost 最近一次 publish 的状态快照。null = 这份文档没有审校
    // （非 Writer / 未就绪 / 没有项目），此时标签与面板都不该出现。
    state: { type: Object, default: null },
    // LibreOffice executor（executeCommand(action, params)）。
    executor: { type: Object, default: null },
  },
  data() {
    // lastFresh：上一轮跑完的清单。正文一改宿主就把 findings 清空（旧坐标不可信），
    // 但清单整片消失再冒出来会让人以为「问题自己没了」——留着显示、标注过期、禁用动作。
    return { bucket: 'all', ignored: [], busy: false, notice: '', lastFresh: [], lastTruncated: false }
  },
  computed: {
    BUCKETS: () => REVIEW_BUCKETS,
    aiEnabled() { return !this.state || this.state.ai !== false },
    writable() { return !this.state || this.state.writable !== false },
    // 协议字段仍叫 hidden（持久化偏好沿用），语义自 dev-board#866 起是「贴边收起」：
    // 正文里只剩一截把手，点把手或点这里都能展开，两处读的是同一份状态。
    ballCollapsed() { return !!(this.state && this.state.hidden) },
    checking() { return !!(this.state && this.state.status === 'checking') },
    deepBusy() { return !!(this.state && this.state.deepStatus === 'checking') },
    fresh() { return isFresh(this.state) },
    truncated() { return this.fresh ? !!(this.state && this.state.truncated) : this.lastTruncated },
    // 忽略过的条目不进任何计数：标签上的数字与列表必须是同一批。
    all() {
      const source = this.fresh ? (this.state && this.state.findings) : this.lastFresh
      return visibleFindings(source, this.ignored)
    },
    counts() { return countByBucket(this.all) },
    rows() { return filterByBucket(this.all, this.bucket) },
    statusText() {
      const s = (this.state && this.state.status) || 'stale'
      if (s === 'checking') return this.$t('editor.inlineReview.checking')
      if (s === 'error') return this.$t('editor.inlineReview.error')
      return this.$t('editor.inlineReview.stale')
    },
    // 自动 AI 审校被本会话停掉的原因（额度、限流、模型不可用、地域）。手动按钮还在。
    autoPausedText() {
      const code = (this.state && this.state.autoBlocked) || ''
      if (!DEEP_REASONS.has(code)) return ''
      return this.$t('editor.inlineReview.autoPaused') + ' ' + this.$t('editor.inlineReview.deepReason.' + code)
    },
    noteText() {
      const msg = (this.state && this.state.message) || ''
      if (!msg) return this.autoPausedText || (this.fresh ? this.$t('editor.inlineReview.local') : this.statusText)
      const base = ERRORS.has(msg) ? this.$t('editor.inlineReview.err.' + msg) : this.$t('editor.inlineReview.error')
      if (msg !== 'REVIEW_DEEP_INCOMPLETE') return base
      const code = (this.state && this.state.deepReason) || ''
      const reason = DEEP_REASONS.has(code) ? ' ' + this.$t('editor.inlineReview.deepReason.' + code) : ''
      const retried = this.state && this.state.deepRetried ? ' ' + this.$t('editor.inlineReview.deepRetried') : ''
      return base + reason + retried
    },
  },
  watch: {
    state: {
      immediate: true,
      deep: true,
      handler(next, prev) {
        // 换文档 / 换会话：忽略过的条目、选中的分类、上一轮的清单都不跟过去。
        if (!next || !prev || next.session !== prev.session) {
          this.ignored = []; this.bucket = 'all'; this.notice = ''; this.lastFresh = []; this.lastTruncated = false
        }
        if (isFresh(next)) {
          this.lastFresh = Array.isArray(next.findings) ? next.findings : []
          this.lastTruncated = !!next.truncated
        }
        this.$emit('count', this.all.length)
      },
    },
    ignored() { this.$emit('count', this.all.length) },
  },
  methods: {
    bucketOf,
    locatable(f) { return isLocatable(f) },
    applicable(f) { return isApplicable(f, this.state) },
    async act(action, params) {
      if (!this.executor || this.busy) return null
      this.busy = true
      try {
        return await this.executor.executeCommand(action, params)
      } catch (e) {
        return { success: false, message: (e && e.message) || '' }
      } finally { this.busy = false }
    },
    rangeOf(f) {
      return {
        revision: this.state && this.state.revision,
        paragraphIndex: f.paragraphIndex, start: f.start, end: f.end,
        expectedParagraph: f.expectedParagraph, quote: f.quote,
      }
    },
    async locate(f) {
      if (!this.fresh || !isLocatable(f)) return
      const r = await this.act('goto_review_range', this.rangeOf(f))
      this.notice = r && r.success ? '' : this.$t('editor.inlineReview.failed')
    },
    async apply(f) {
      if (!this.fresh || !isApplicable(f, this.state)) return
      const r = await this.act('apply_review_edit', Object.assign(this.rangeOf(f), { replacement: f.replacement }))
      if (r && r.success) {
        this.notice = this.$t('editor.inlineReview.applied')
        this.$emit('changed')
      } else {
        this.notice = this.$t('editor.inlineReview.failed')
      }
    },
    ignore(f) {
      const id = String(f && f.id)
      if (!this.ignored.includes(id)) this.ignored = this.ignored.concat(id)
    },
    refresh() { if (!this.checking) this.$emit('action', { action: 'refresh' }) },
    runDeep() { if (!this.deepBusy) this.$emit('action', { action: 'deep' }) },
    toggleAi() { this.$emit('action', { action: 'ai', value: !this.aiEnabled }) },
    toggleBall() { this.$emit('action', { action: 'hidden', value: !this.ballCollapsed }) },
  },
}
</script>

<style lang="scss" scoped src="./inline-review-panel.scss"></style>
