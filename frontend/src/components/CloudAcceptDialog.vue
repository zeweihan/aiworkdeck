<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog">
      <view class="awd-header"><text class="awd-title">{{ $t('version.pullFromLibraryTitle') }}</text></view>
      <view class="awd-body">
        <view v-if="loading" class="cloud-accept-hint">{{ $t('version.loadingGeneric') }}</view>
        <view v-else-if="noConnection" class="cloud-accept-empty">
          <text class="cloud-accept-hint">{{ $t('version.noLibraryConnectedShort') }}</text>
          <text class="cloud-accept-goto-settings" @tap="gotoSettings">{{ $t('version.goConnectOne') }}</text>
        </view>
        <template v-else>
          <!-- 连了多个案件库时必须由律师指名去哪一个取：拿列表第一条会在存量死连接
               排在前面时对着一个早已不在的服务器发请求。 -->
          <view v-if="connections.length > 1" class="cloud-accept-picker">
            <text class="cloud-accept-picker-label">{{ $t('version.chooseLibrarySourceShortLabel') }}</text>
            <view
              v-for="c in connections"
              :key="c.id"
              class="cloud-accept-picker-item"
              :class="{ checked: connectionId === c.id }"
              @tap="selectConnection(c.id)"
            >
              <view class="cloud-accept-radio"></view>
              <text class="cloud-accept-picker-text">{{ c.serverUrl }}</text>
            </view>
          </view>
          <view v-if="!projects.length" class="cloud-accept-hint">{{ $t('version.noSharedProjects') }}</view>
          <view v-else class="cloud-project-list">
            <view v-for="p in projects" :key="p.id" class="cloud-project-row">
              <text class="cloud-project-name">{{ p.name }}</text>
              <view
                class="awd-btn awd-btn-secondary"
                :class="{ 'awd-btn-disabled': busy }"
                @tap="onAccept(p)"
              >{{ $t('version.pullToDevice') }}</view>
            </view>
          </view>
        </template>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="close">{{ $t('common.close') }}</view>
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
      connections: [],
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
        this.connections = conns
        if (!conns.length) { this.noConnection = true; return }
        this.connectionId = conns[0].id
        await this.loadProjects()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.loadRemoteProjectsFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    async loadProjects() {
      const pres = await listRemoteProjects(this.connectionId)
      this.projects = (pres && pres.data && pres.data.projects) || []
    },
    async selectConnection(id) {
      if (this.connectionId === id) return
      this.connectionId = id
      this.projects = []
      this.loading = true
      try {
        await this.loadProjects()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.loadRemoteProjectsFailed'), icon: 'none' })
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
        uni.showToast({ title: (e && e.message) || this.$t('version.pullToDeviceFailed'), icon: 'none' })
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
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; max-height: 74vh; display: flex; flex-direction: column; background: var(--awd-surface); border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid var(--awd-border); }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid var(--awd-border);
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: var(--awd-info); color: var(--awd-text-on-accent); }
.awd-btn-secondary { background: var(--awd-bg); color: var(--awd-text); }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.cloud-accept-hint { font-size: 26rpx; color: var(--awd-text-2); line-height: 1.6; }
.cloud-accept-empty { display: flex; flex-direction: column; gap: 12rpx; align-items: flex-start; }
.cloud-accept-goto-settings { font-size: 25rpx; color: var(--awd-text); text-decoration: underline; }
.cloud-accept-picker { display: flex; flex-direction: column; gap: 8rpx; margin-bottom: 20rpx; }
.cloud-accept-picker-label { font-size: 24rpx; color: var(--awd-text-2); }
.cloud-accept-picker-item {
  display: flex; align-items: center; gap: 10rpx;
  padding: 10rpx 12rpx; border: 1px solid var(--awd-border); border-radius: 8rpx;
}
.cloud-accept-picker-item.checked { border-color: var(--awd-info); background: var(--awd-info-soft); }
.cloud-accept-radio {
  width: 18rpx; height: 18rpx; border-radius: 50%; border: 1px solid var(--awd-border-strong);
  box-sizing: border-box; flex-shrink: 0;
}
.cloud-accept-picker-item.checked .cloud-accept-radio { border-color: var(--awd-info); background: var(--awd-info); }
.cloud-accept-picker-text { font-size: 24rpx; color: var(--awd-text); word-break: break-all; }
.cloud-project-list {}
.cloud-project-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16rpx 0; border-bottom: 1px solid var(--awd-border-subtle);
}
.cloud-project-name { font-size: 26rpx; color: var(--awd-text); word-break: break-all; }
</style>
