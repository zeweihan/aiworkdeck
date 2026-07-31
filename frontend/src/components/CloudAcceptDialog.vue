<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog">
      <view class="awd-header"><text class="awd-title">从云端接一个项目</text></view>
      <view class="awd-body">
        <view v-if="loading" class="cloud-accept-hint">正在读取…</view>
        <view v-else-if="noConnection" class="cloud-accept-empty">
          <text class="cloud-accept-hint">还没有连接团队服务器</text>
          <text class="cloud-accept-goto-settings" @tap="gotoSettings">去设置连接</text>
        </view>
        <view v-else-if="!projects.length" class="cloud-accept-hint">云端还没有可接入的项目</view>
        <view v-else class="cloud-project-list">
          <view v-for="p in projects" :key="p.id" class="cloud-project-row">
            <text class="cloud-project-name">{{ p.name }}</text>
            <view
              class="awd-btn awd-btn-secondary"
              :class="{ 'awd-btn-disabled': busy }"
              @tap="onAccept(p)"
            >接到本地</view>
          </view>
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="close">关闭</view>
      </view>
    </view>
  </view>
</template>

<script>
import { listCloudConnections, listRemoteProjects, acceptCloudProject } from '@/services/api.js'

export default {
  name: 'CloudAcceptDialog',
  props: {
    visible: { type: Boolean, default: false },
  },
  emits: ['accepted', 'update:visible'],
  data() {
    return {
      loading: false,
      noConnection: false,
      connectionId: null,
      projects: [],
      busy: false,
    }
  },
  watch: {
    visible(v) {
      if (v) this.load()
    },
  },
  methods: {
    async load() {
      this.loading = true
      this.noConnection = false
      this.projects = []
      try {
        const res = await listCloudConnections()
        const conns = (res && res.data && res.data.connections) || []
        if (!conns.length) { this.noConnection = true; return }
        this.connectionId = conns[0].id
        const pres = await listRemoteProjects(this.connectionId)
        this.projects = (pres && pres.data && pres.data.projects) || []
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '读取云端项目失败', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    async onAccept(project) {
      if (this.busy) return
      this.busy = true
      try {
        const res = await acceptCloudProject(this.connectionId, project.id)
        const localProjectId = res && res.data && res.data.localProjectId
        this.close()
        this.$emit('accepted', localProjectId)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '接入失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    gotoSettings() {
      this.close()
      uni.navigateTo({ url: '/pages/admin/admin' })
    },
    close() {
      this.$emit('update:visible', false)
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; max-height: 74vh; display: flex; flex-direction: column; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.cloud-accept-hint { font-size: 26rpx; color: #666; line-height: 1.6; }
.cloud-accept-empty { display: flex; flex-direction: column; gap: 12rpx; align-items: flex-start; }
.cloud-accept-goto-settings { font-size: 25rpx; color: #12344D; text-decoration: underline; }
.cloud-project-list {}
.cloud-project-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16rpx 0; border-bottom: 1px solid #f0f0f0;
}
.cloud-project-name { font-size: 26rpx; color: #222; word-break: break-all; }
</style>
