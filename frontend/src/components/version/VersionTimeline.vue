<template>
  <scroll-view class="timeline" scroll-y>
    <view v-if="loadError" class="timeline-error">
      <text class="timeline-error-desc">版本记录读取失败，请稍后重试。</text>
      <text class="timeline-error-retry" @tap="load">重试</text>
    </view>
    <view v-else-if="!versions.length" class="timeline-empty">还没有任何版本记录</view>

    <view
      v-for="group in grouped"
      :key="group.head.sha"
      class="timeline-node"
      :class="{ 'is-session': group.head.kind === 'session' }"
    >
      <view class="node-line" />
      <view class="node-main" @tap="select(group.head)">
        <view class="node-title" :class="{ 'has-milestone': group.head.milestone }">
          <text v-if="group.head.milestone" class="milestone-flag">重要版本</text>
          {{ group.head.milestone || titleOf(group.head) }}
        </view>
        <view class="node-meta">{{ group.head.authorName }} · {{ timeOf(group.head) }}</view>
      </view>

      <view
        v-if="group.autos.length"
        class="node-autos-toggle"
        @tap="toggle(group.head.sha)"
      >
        {{ expanded[group.head.sha] ? '收起' : `这段工作里还有 ${group.autos.length} 次自动存档` }}
      </view>
      <view v-if="expanded[group.head.sha]" class="node-autos">
        <view
          v-for="a in group.autos"
          :key="a.sha"
          class="node-auto"
          @tap="select(a)"
        >
          <text class="auto-time">{{ timeOf(a) }}</text>
          <text class="auto-msg" :class="{ 'has-milestone': a.milestone }">
            <text v-if="a.milestone" class="milestone-flag">重要版本</text>
            {{ a.milestone || a.message }}
          </text>
        </view>
      </view>
    </view>

    <VersionNodeDetail
      v-if="selected"
      :project-id="projectId"
      :version="selected"
      @close="selected = null"
      @reload-files="onReload"
      @compare-file="$emit('compare-file', $event)"
      @milestoned="onMilestoned"
      @draft-created="onDraftCreated"
    />
  </scroll-view>
</template>

<script>
import { getVersionTimeline } from '@/services/api.js'
import VersionNodeDetail from './VersionNodeDetail.vue'

export default {
  name: 'VersionTimeline',
  components: { VersionNodeDetail },
  props: {
    projectId: { type: [String, Number], required: true },
    fileFilter: { type: Object, default: null },
  },
  emits: ['reload-files', 'compare-file', 'draft-created'],
  data() {
    return { versions: [], expanded: {}, selected: null, loadError: false }
  },
  watch: {
    fileFilter() {
      this.load()
    },
  },
  computed: {
    // 工作段是主线节点，自动存档折进它下面。
    grouped() {
      const out = []
      let current = null
      for (const v of this.versions) {
        if (v.kind === 'session' || !current) {
          current = { head: v, autos: [] }
          out.push(current)
        } else {
          current.autos.push(v)
        }
      }
      return out
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const res = await getVersionTimeline(this.projectId, 50, this.fileFilter && this.fileFilter.fileId)
        this.versions = ((res && res.data && res.data.versions) || [])
        this.loadError = false
      } catch (e) {
        console.warn('[Version] 读取时间线失败', e)
        this.loadError = true
        uni.showToast({ title: '读取失败，请稍后重试', icon: 'none' })
      }
    },
    titleOf(v) {
      return v.note || v.message
    },
    timeOf(v) {
      const d = new Date(v.when)
      const pad = (n) => String(n).padStart(2, '0')
      return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
    toggle(sha) {
      this.expanded = { ...this.expanded, [sha]: !this.expanded[sha] }
    },
    select(v) {
      this.selected = v
    },
    // 标记重要版本之后：load() 会把 versions 整个换成新对象，而 selected 还指着
    // 旧对象——弹窗上的按钮仍写着「标为重要版本」，看起来像没生效。重拉之后把
    // selected 重新指到同一个 sha 的新条目上（拿到的 milestone 字段是服务端权威值）。
    async onMilestoned() {
      const sha = this.selected && this.selected.sha
      await this.load()
      if (!sha) return
      const fresh = this.versions.find(v => v.sha === sha)
      if (fresh) this.selected = fresh
    },
    onReload(affectedFileIds) {
      this.selected = null
      this.load()
      this.$emit('reload-files', affectedFileIds || [])
    },
    // 从某个历史版本另起一稿：HEAD 切到了新稿分支，本页时间线（按 HEAD 取历史）
    // 要重新拉取，否则还停在切线前那条线的历史上。
    onDraftCreated(affectedFileIds) {
      this.selected = null
      this.load()
      this.$emit('draft-created', affectedFileIds || [])
    },
  },
}
</script>

<style lang="scss" scoped>
.timeline { flex: 1; padding: 12rpx 0; }
.timeline-empty { padding: 24rpx; color: $awd-text-on-dark-3; font-size: 26rpx; }
.timeline-error { padding: 24rpx; display: flex; align-items: center; gap: 16rpx; }
.timeline-error-desc { font-size: 26rpx; color: $awd-brick-on-dark; }
.timeline-error-retry { font-size: 26rpx; color: $awd-mint; text-decoration: underline; }
.timeline-node { position: relative; padding: 16rpx 20rpx 16rpx 40rpx; }
.node-line {
  position: absolute; left: 20rpx; top: 0; bottom: 0; width: 2rpx; background: $awd-chrome-active;
}
.timeline-node.is-session .node-title { font-weight: 600; }
.node-title { font-size: 27rpx; color: $awd-text-on-dark; }
.node-meta { font-size: 23rpx; color: $awd-text-on-dark-3; margin-top: 6rpx; }
.node-autos-toggle { font-size: 23rpx; color: $awd-mint; margin-top: 10rpx; }
.node-autos { margin-top: 10rpx; padding-left: 12rpx; border-left: 2rpx dashed $awd-chrome-active; }
.node-auto { display: flex; gap: 12rpx; padding: 8rpx 0; }
.auto-time { font-size: 23rpx; color: $awd-text-on-dark-3; flex-shrink: 0; }
.auto-msg { font-size: 23rpx; color: $awd-text-on-dark-2; }
.milestone-flag { font-size: 20rpx; color: #C8A45D; border: 1px solid #C8A45D; border-radius: 4rpx; padding: 2rpx 8rpx; margin-right: 8rpx; }
.has-milestone { font-weight: 600; }
</style>
