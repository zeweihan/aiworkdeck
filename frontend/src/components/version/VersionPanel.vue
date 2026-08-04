<template>
  <view class="version-panel">
    <view v-if="loading" class="version-empty">正在读取版本记录…</view>

    <view v-else-if="loadError" class="version-error">
      <view class="version-error-desc">版本记录读取失败，请稍后重试。</view>
      <view class="awd-btn awd-btn-primary" @tap="refresh">重试</view>
    </view>

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
        :on-draft="onDraft"
        @ended="refresh"
        @discarded="onReload"
        @mainline-resumed="onReload"
        @draft-adopted="onReload"
        @draft-abandoned="onReload"
      />
      <CloudSyncBar
        :cloud="cloud"
        :has-connection="hasConnection"
        @shared="refresh"
        @reload-files="onReload"
      />
      <view v-if="fileFilter" class="version-file-filter">
        <text class="version-file-filter-text">只看《{{ fileFilter.name }}》的历史</text>
        <text class="version-file-filter-clear" @tap="$emit('clear-file-filter')">显示全部</text>
      </view>
      <DraftList
        v-if="drafts.length"
        :project-id="projectId"
        :drafts="drafts"
        @created="onReload"
        @switched="onReload"
      />
      <VersionTimeline
        :project-id="projectId"
        :file-filter="fileFilter"
        :key="timelineKey"
        @reload-files="onReload"
        @compare-file="$emit('compare-file', $event)"
        @draft-created="onReload"
      />
      <!-- 三语境冲突弹窗：/status 判定链 sessionEndConflict > cloudConflict > adoptConflict，
           互斥挂载（后端保证命中前两者中任一个时第三个必为 null，前端按同序取。含崩溃后
           重开面板的场景。 -->
      <AdoptConflictDialog
        v-if="sessionEndConflict"
        mode="session-end"
        :project-id="projectId"
        :session-id="sessionEndConflict.sessionId"
        :draft-name="sessionEndConflict.title"
        :conflicting-paths="sessionEndConflict.conflictingPaths"
        :mainline-tip="sessionEndConflict.mainlineTip"
        :draft-tip="sessionEndConflict.sessionTip"
        @resolved="onReload"
        @aborted="refresh"
        @compare-file="$emit('compare-file', $event)"
      />
      <AdoptConflictDialog
        v-else-if="cloudConflict"
        mode="cloud"
        :project-id="projectId"
        :conflicting-paths="cloudConflict.conflictingPaths"
        :mainline-tip="cloudConflict.mainlineTip"
        :draft-tip="cloudConflict.cloudTip"
        @resolved="onReload"
        @aborted="refresh"
        @compare-file="$emit('compare-file', $event)"
      />
      <AdoptConflictDialog
        v-else-if="adoptConflict"
        :project-id="projectId"
        :draft-id="adoptConflict.draftId"
        :draft-name="adoptConflict.draftName"
        :conflicting-paths="adoptConflict.conflictingPaths"
        :mainline-tip="adoptConflict.mainlineTip"
        :draft-tip="adoptConflict.draftTip"
        @resolved="onReload"
        @aborted="onReload"
        @compare-file="$emit('compare-file', $event)"
      />
    </template>
  </view>
</template>

<script>
import {
  getVersionStatus, enableVersionControl, listDrafts,
  getCloudStatus, checkCloud, listCloudConnections,
} from '@/services/api.js'
import WorkSessionBar from './WorkSessionBar.vue'
import VersionTimeline from './VersionTimeline.vue'
import DraftList from './DraftList.vue'
import AdoptConflictDialog from './AdoptConflictDialog.vue'
import CloudSyncBar from './CloudSyncBar.vue'

export default {
  name: 'VersionPanel',
  components: { WorkSessionBar, VersionTimeline, DraftList, AdoptConflictDialog, CloudSyncBar },
  props: {
    projectId: { type: [String, Number], required: true },
    fileFilter: { type: Object, default: null },
  },
  // adopt-conflict：把「有没有采纳等待处理」同步给页面。本面板一关（切去资源管理器
  // 等），三选一弹窗随组件卸载消失，而后端仍停在待裁决状态、版本捕获整体关闭——
  // 页面据此在面板之外挂一条固定提示条（project-overview.vue 的 .adopt-pending-bar）。
  emits: ['compare-file', 'clear-file-filter', 'reload-files', 'adopt-conflict'],
  provide() {
    return { projectId: this.projectId }
  },
  data() {
    return {
      loading: true,
      loadError: false,
      enabled: false,
      working: false,
      changedCount: 0,
      onDraft: null,
      drafts: [],
      adoptConflict: null,
      cloudConflict: null,
      sessionEndConflict: null,
      cloud: null,
      hasConnection: false,
      timelineKey: 0,
      busy: false,
    }
  },
  mounted() {
    this.refresh()
    // 静默探活一次云端连通性（会真的 fetch 远端，不同于 refresh() 里 getCloudStatus
    // 读的本地缓存状态）；失败不打断——三态里的「云端暂时连不上」已经覆盖了这种情况。
    checkCloud(this.projectId).then((res) => {
      this.cloud = (res && res.data) || null
    }).catch(() => {})
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
        this.onDraft = d.onDraft || null
        this.adoptConflict = d.adoptConflict || null
        this.cloudConflict = d.cloudConflict || null
        this.sessionEndConflict = d.sessionEndConflict || null
        this.$emit('adopt-conflict', !!(this.adoptConflict || this.cloudConflict || this.sessionEndConflict))
        this.timelineKey += 1
        this.loadError = false
        if (this.enabled) {
          await this.fetchDrafts()
          await this.fetchCloudState()
        } else {
          this.drafts = []
          this.cloud = null
          this.hasConnection = false
        }
      } catch (e) {
        // 读取失败绝不能落到"未开启"引导页——那会让律师误以为从没开过版本记录，
        // 去重复点开启。宁可显示可区分的错误态，保留 enabled 的上一次已知值。
        console.warn('[Version] 读取状态失败', e)
        this.loadError = true
        uni.showToast({ title: '读取失败，请稍后重试', icon: 'none' })
      } finally {
        this.loading = false
      }
    },
    async fetchDrafts() {
      try {
        const res = await listDrafts(this.projectId)
        this.drafts = (res && res.data && res.data.drafts) || []
      } catch (e) {
        console.warn('[Version] 读取稿列表失败', e)
        this.drafts = []
      }
    },
    // 云端状态条要用的两样东西：linked/pendingUpload/remoteAhead（本地缓存，不联网）
    // 与「设不设得出共享按钮」。两者互相独立失败，各自吞错误置默认，不拖累 refresh()
    // 主流程（离线时版本记录本地功能照常可用）。
    async fetchCloudState() {
      try {
        const res = await getCloudStatus(this.projectId)
        this.cloud = (res && res.data) || null
      } catch (e) {
        console.warn('[Version] 读取云端状态失败', e)
        this.cloud = null
      }
      try {
        const res = await listCloudConnections()
        const list = (res && res.data && res.data.connections) || []
        this.hasConnection = list.length > 0
      } catch (e) {
        console.warn('[Version] 读取云端连接失败', e)
        this.hasConnection = false
      }
    },
    // 退回/开稿/切线/采纳/放弃：都可能改变磁盘上打开中的文件，统一走这一条重载链
    // （见 fileOpenTabs.js 的 onVersionReloadFiles）；也都要重拉一次状态，
    // 工作段/稿态/稿列表/采纳冲突态全部以 /status 为准。
    onReload(affectedFileIds) {
      this.refresh()
      this.$emit('reload-files', affectedFileIds || [])
    },
    async enable() {
      if (this.busy) return
      this.busy = true
      try {
        await enableVersionControl(this.projectId)
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '开启失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
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
.version-error { padding: 32rpx 24rpx; }
.version-error-desc { font-size: 26rpx; color: #b23; line-height: 1.6; margin-bottom: 24rpx; }
.version-file-filter {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12rpx 24rpx; background: #F3F6F5; border-bottom: 1px solid #E9ECEF;
  font-size: 24rpx; color: #666;
}
.version-file-filter-clear { color: #12344D; text-decoration: underline; }

/* awd-* 没有集中定义，各组件 scoped 内各自定义 */
.awd-btn {
  display: inline-block; padding: 14rpx 28rpx; border-radius: 8rpx;
  font-size: 26rpx; text-align: center;
}
.awd-btn-primary { background: #12344D; color: #fff; }
</style>
