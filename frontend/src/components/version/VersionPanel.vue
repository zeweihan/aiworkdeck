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
        @discarded="refresh"
        @mainline-resumed="onReload"
        @draft-adopted="onReload"
        @draft-abandoned="onReload"
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
      <!-- 采纳冲突三选一弹窗：/status 带 adoptConflict 时自动弹出，含崩溃后重开面板的场景。 -->
      <AdoptConflictDialog
        v-if="adoptConflict"
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
import { getVersionStatus, enableVersionControl, listDrafts } from '@/services/api.js'
import WorkSessionBar from './WorkSessionBar.vue'
import VersionTimeline from './VersionTimeline.vue'
import DraftList from './DraftList.vue'
import AdoptConflictDialog from './AdoptConflictDialog.vue'

export default {
  name: 'VersionPanel',
  components: { WorkSessionBar, VersionTimeline, DraftList, AdoptConflictDialog },
  props: {
    projectId: { type: [String, Number], required: true },
    fileFilter: { type: Object, default: null },
  },
  emits: ['compare-file', 'clear-file-filter', 'reload-files'],
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
      timelineKey: 0,
      busy: false,
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
        this.onDraft = d.onDraft || null
        this.adoptConflict = d.adoptConflict || null
        this.timelineKey += 1
        this.loadError = false
        if (this.enabled) await this.fetchDrafts()
        else this.drafts = []
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
