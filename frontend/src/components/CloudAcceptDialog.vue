<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog">
      <view class="awd-header"><text class="awd-title">{{ $t('version.pullFromLibraryTitle') }}</text></view>
      <view class="awd-body">
        <view v-if="loading" class="cloud-accept-hint">{{ $t('version.loadingGeneric') }}</view>
        <!-- 走到这里 = 本站没有官方案件库、本机也没有连接（国际站）。没有「去连一个」
             那条路了：手填地址的入口已撤，自建部署由 cloud.collab.base-url 指过来。 -->
        <view v-else-if="noConnection" class="cloud-accept-empty">
          <text class="cloud-accept-hint">{{ $t('version.noLibraryAvailableShort') }}</text>
        </view>
        <template v-else>
          <view v-if="!projects.length" class="cloud-accept-hint">{{ $t('version.noSharedProjects') }}</view>
          <view v-else class="cloud-project-list">
            <view v-for="p in projects" :key="p.id" class="cloud-project-row">
              <view class="cloud-project-info">
                <text class="cloud-project-name">{{ p.name }}</text>
                <!-- 被邀请进来的人在取之前就该看得见自己在这份案卷里是什么身份 -->
                <text v-if="p.myRole" class="cloud-project-role">{{ roleLabel(p.myRole) }}</text>
              </view>
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
import {
  listCloudConnections, listRemoteProjects, acceptCloudProject,
  getOfficialCloud, connectOfficialCloud,
} from '@/services/api.js'
import { roleLabel } from '@/config/memberRoles.js'

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
      // 本机只认一个案件库（官方，或 cloud.collab.base-url 指过来的自建库），
      // 界面上不再有「从哪个案件库取」的选择器
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
        let conns = await this.fetchConnections()
        // 一条连接都没有、但本站有官方案件库：用本机的 AI WorkDeck 账户当场连上再列。
        // 这个弹窗的全部用途就是「取一份案卷」，先弹一个「去连一个」再让人回来点第二次
        // 是白走一趟——连的还是他自己的账号，不是什么新授权。
        if (!conns.length && await this.officialAvailable()) {
          try {
            await connectOfficialCloud()
            conns = await this.fetchConnections()
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('version.connectFailed'), icon: 'none' })
          }
        }
        if (!conns.length) { this.noConnection = true; return }
        this.connectionId = conns[0].id
        await this.loadProjects()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.loadRemoteProjectsFailed'), icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    // Options API 模板拿不到裸导入函数，包一层 method 才能在模板里当 roleLabel(...) 调用
    roleLabel,
    async fetchConnections() {
      const res = await listCloudConnections()
      return (res && res.data && res.data.connections) || []
    },
    async officialAvailable() {
      try {
        const res = await getOfficialCloud()
        return !!(res && res.data && res.data.available)
      } catch (e) {
        return false
      }
    },
    async loadProjects() {
      const pres = await listRemoteProjects(this.connectionId)
      this.projects = (pres && pres.data && pres.data.projects) || []
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
.cloud-project-list {}
.cloud-project-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 16rpx 0; border-bottom: 1px solid var(--awd-border-subtle);
}
.cloud-project-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cloud-project-role { font-size: 12px; color: var(--awd-text-3); }
.cloud-project-name { font-size: 26rpx; color: var(--awd-text); word-break: break-all; }
</style>
