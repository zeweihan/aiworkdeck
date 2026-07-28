<template>
  <scroll-view class="timeline" scroll-y>
    <view v-if="!versions.length" class="timeline-empty">还没有任何版本记录</view>

    <view
      v-for="group in grouped"
      :key="group.head.sha"
      class="timeline-node"
      :class="{ 'is-session': group.head.kind === 'session' }"
    >
      <view class="node-line" />
      <view class="node-main" @tap="select(group.head)">
        <view class="node-title">{{ titleOf(group.head) }}</view>
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
          <text class="auto-msg">{{ a.message }}</text>
        </view>
      </view>
    </view>

    <VersionNodeDetail
      v-if="selected"
      :project-id="projectId"
      :version="selected"
      @close="selected = null"
      @reverted="onReverted"
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
  },
  emits: ['reverted'],
  data() {
    return { versions: [], expanded: {}, selected: null }
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
        const res = await getVersionTimeline(this.projectId)
        this.versions = ((res && res.data && res.data.versions) || [])
      } catch (e) {
        console.warn('[Version] 读取时间线失败', e)
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
</style>
