<template>
  <view class="version-panel">
    <view v-if="loading" class="version-empty">正在读取版本记录…</view>

    <view v-else-if="!enabled" class="version-intro">
      <view class="version-intro-title">本项目还没有开启版本记录</view>
      <view class="version-intro-desc">
        开启后，你每次改动都会自动留底，随时可以看到项目改了什么、退回到以前的样子。
      </view>
      <view class="awd-btn awd-btn-primary" @tap="enable">开启版本记录</view>
    </view>

    <template v-else>
      <WorkSessionBar
        :working="working"
        :changed-count="changedCount"
        @ended="refresh"
        @discarded="refresh"
      />
      <VersionTimeline :project-id="projectId" :key="timelineKey" />
    </template>
  </view>
</template>

<script>
import { getVersionStatus, enableVersionControl } from '@/services/api.js'
import WorkSessionBar from './WorkSessionBar.vue'
import VersionTimeline from './VersionTimeline.vue'

export default {
  name: 'VersionPanel',
  components: { WorkSessionBar, VersionTimeline },
  props: {
    projectId: { type: [String, Number], required: true },
  },
  data() {
    return {
      loading: true,
      enabled: false,
      working: false,
      changedCount: 0,
      timelineKey: 0,
    }
  },
  mounted() {
    this.refresh()
  },
  methods: {
    async refresh() {
      this.loading = true
      try {
        const res = await getVersionStatus(this.projectId)
        const d = (res && res.data) || {}
        this.enabled = !!d.enabled
        this.working = !!d.working
        this.changedCount = d.changedCount || 0
        this.timelineKey += 1
      } catch (e) {
        console.warn('[Version] 读取状态失败', e)
      } finally {
        this.loading = false
      }
    },
    async enable() {
      try {
        await enableVersionControl(this.projectId)
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: '开启失败，请稍后重试', icon: 'none' })
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.version-panel { display: flex; flex-direction: column; height: 100%; }
.version-empty { padding: 24rpx; color: #888; font-size: 26rpx; }
.version-intro { padding: 32rpx 24rpx; }
.version-intro-title { font-size: 30rpx; font-weight: 600; margin-bottom: 12rpx; }
.version-intro-desc { font-size: 26rpx; color: #666; line-height: 1.6; margin-bottom: 24rpx; }

/* awd-* 没有集中定义，各组件 scoped 内各自定义 */
.awd-btn {
  display: inline-block; padding: 14rpx 28rpx; border-radius: 8rpx;
  font-size: 26rpx; text-align: center;
}
.awd-btn-primary { background: #12344D; color: #fff; }
</style>
