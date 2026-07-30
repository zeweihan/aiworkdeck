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
      @reverted="onReverted"
      @compare-file="$emit('compare-file', $event)"
      @milestoned="load"
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
  emits: ['reverted', 'compare-file'],
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
    onReverted() {
      this.selected = null
      this.load()
      this.$emit('reverted')
    },
  },
}
</script>

<style lang="scss" scoped>
.timeline { flex: 1; padding: 12rpx 0; }
.timeline-empty { padding: 24rpx; color: #999; font-size: 26rpx; }
.timeline-error { padding: 24rpx; display: flex; align-items: center; gap: 16rpx; }
.timeline-error-desc { font-size: 26rpx; color: #b23; }
.timeline-error-retry { font-size: 26rpx; color: #12344D; text-decoration: underline; }
.timeline-node { position: relative; padding: 16rpx 20rpx 16rpx 40rpx; }
.node-line {
  position: absolute; left: 20rpx; top: 0; bottom: 0; width: 2rpx; background: #e4e4e4;
}
.timeline-node.is-session .node-title { font-weight: 600; }
.node-title { font-size: 27rpx; color: #222; }
.node-meta { font-size: 23rpx; color: #999; margin-top: 6rpx; }
.node-autos-toggle { font-size: 23rpx; color: #12344D; margin-top: 10rpx; }
.node-autos { margin-top: 10rpx; padding-left: 12rpx; border-left: 2rpx dashed #ddd; }
.node-auto { display: flex; gap: 12rpx; padding: 8rpx 0; }
.auto-time { font-size: 23rpx; color: #aaa; flex-shrink: 0; }
.auto-msg { font-size: 23rpx; color: #666; }
.milestone-flag { font-size: 20rpx; color: #C8A45D; border: 1px solid #C8A45D; border-radius: 4rpx; padding: 2rpx 8rpx; margin-right: 8rpx; }
.has-milestone { font-weight: 600; }
</style>
