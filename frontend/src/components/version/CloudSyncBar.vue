<template>
  <view class="cloud-bar">
    <template v-if="!cloud || !cloud.linked">
      <text class="cloud-text cloud-unlinked">这个项目还没有共享到云端</text>
      <button v-if="hasConnection" class="awd-btn awd-btn-secondary cloud-btn"
              :disabled="busy" @tap="onShare">共享到云端</button>
      <text v-else class="cloud-hint">先在设置里连接团队服务器</text>
    </template>
    <template v-else>
      <text class="cloud-dot" :class="stateClass"></text>
      <text class="cloud-text">{{ stateText }}</text>
      <button class="awd-btn awd-btn-secondary cloud-btn" :disabled="busy"
              @tap="onUpload">立即上传</button>
      <button class="awd-btn awd-btn-secondary cloud-btn" :disabled="busy"
              @tap="onUpdate">从云端更新</button>
      <text class="cloud-members-link" @tap="openMembers">成员</text>
    </template>

    <view v-if="membersOpen" class="awd-mask" @tap.self="membersOpen = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">云端项目成员</text></view>
        <view class="awd-body">
          <view v-if="membersLoading" class="cloud-members-loading">正在读取…</view>
          <view v-else-if="!membersList.length" class="cloud-members-empty">还没有其他成员</view>
          <view v-else class="cloud-members-list">
            <view v-for="m in membersList" :key="m.username || m.id" class="cloud-member-row">
              <text class="cloud-member-name">{{ m.displayName || m.username }}</text>
              <text class="cloud-member-role">{{ roleLabel(m.role) }}</text>
            </view>
          </view>
          <view class="cloud-member-add-row">
            <input v-model="addUsername" class="awd-input" placeholder="按用户名添加成员" />
            <view
              class="awd-btn awd-btn-primary"
              :class="{ 'awd-btn-disabled': memberBusy || !addUsername }"
              @tap="onAddMember"
            >添加</view>
          </view>
          <view class="cloud-members-hint">完整的成员管理请到网页端操作。</view>
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="membersOpen = false">关闭</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  listCloudConnections, shareProjectToCloud, uploadToCloud, updateFromCloud,
  getCloudMembers, addCloudMember,
} from '@/services/api.js'

export default {
  name: 'CloudSyncBar',
  props: {
    // VersionPanel 下发的 cloudStatus 对象：{linked, pendingUpload, remoteAhead, offline}。
    cloud: { type: Object, default: null },
    hasConnection: { type: Boolean, default: false },
  },
  emits: ['reload-files', 'shared'],
  inject: ['projectId'],
  data() {
    return {
      busy: false,
      membersOpen: false,
      membersLoading: false,
      membersList: [],
      addUsername: '',
      memberBusy: false,
    }
  },
  computed: {
    stateText() {
      if (!this.cloud) return ''
      if (this.cloud.offline) return '云端暂时连不上'
      if (this.cloud.pendingUpload) return '有改动待上传'
      if (this.cloud.remoteAhead) return '云端有新版本'
      return '已与云端同步'
    },
    stateClass() {
      if (!this.cloud) return ''
      if (this.cloud.offline) return 'cloud-dot-yellow'
      if (this.cloud.pendingUpload || this.cloud.remoteAhead) return 'cloud-dot-blue'
      return 'cloud-dot-green'
    },
  },
  methods: {
    async onShare() {
      this.busy = true
      try {
        const conns = await listCloudConnections()
        const list = (conns.data && conns.data.connections) || []
        if (!list.length) { uni.showToast({ title: '请先在设置里连接团队服务器', icon: 'none' }); return }
        await shareProjectToCloud(this.projectId, list[0].id)
        uni.showToast({ title: '已共享到云端', icon: 'none' })
        this.$emit('shared')
      } catch (e) { uni.showToast({ title: e.message || '共享失败', icon: 'none' }) }
      finally { this.busy = false }
    },
    async onUpload() {
      this.busy = true
      try {
        const res = await uploadToCloud(this.projectId)
        const st = res.data && res.data.status
        if (st === 'UPLOADED') uni.showToast({ title: '已上传到云端', icon: 'none' })
        else if (st === 'CONFLICT') this.$emit('shared') // 让面板 refresh 弹冲突弹窗
        else uni.showToast({ title: (res.data && res.data.message) || '暂时没能上传', icon: 'none' })
        this.$emit('shared')
      } catch (e) { uni.showToast({ title: e.message || '上传失败', icon: 'none' }) }
      finally { this.busy = false }
    },
    async onUpdate() {
      this.busy = true
      try {
        const res = await updateFromCloud(this.projectId)
        const d = res.data || {}
        if (d.status === 'UPDATED') {
          uni.showToast({ title: '已从云端更新', icon: 'none' })
          this.$emit('reload-files', d.affectedFileIds || [])
        } else if (d.status === 'CONFLICT') {
          this.$emit('shared') // refresh → /status 带 cloudConflict → 弹窗
        } else if (d.status === 'OFFLINE') {
          uni.showToast({ title: '云端暂时连不上', icon: 'none' })
        } else {
          uni.showToast({ title: '已经是最新内容', icon: 'none' })
        }
      } catch (e) { uni.showToast({ title: e.message || '更新失败', icon: 'none' }) }
      finally { this.busy = false }
    },
    async openMembers() {
      this.membersOpen = true
      this.membersLoading = true
      try {
        const res = await getCloudMembers(this.projectId)
        this.membersList = (res.data && res.data.members) || []
      } catch (e) {
        uni.showToast({ title: e.message || '读取成员失败', icon: 'none' })
      } finally {
        this.membersLoading = false
      }
    },
    async onAddMember() {
      if (this.memberBusy || !this.addUsername) return
      this.memberBusy = true
      try {
        await addCloudMember(this.projectId, this.addUsername, 'PARTICIPANT')
        this.addUsername = ''
        uni.showToast({ title: '已添加', icon: 'none' })
        await this.openMembers()
      } catch (e) {
        uni.showToast({ title: e.message || '添加失败', icon: 'none' })
      } finally {
        this.memberBusy = false
      }
    },
    roleLabel(role) {
      if (role === 'OWNER') return '负责人'
      if (role === 'PARTICIPANT') return '参与者'
      return role || ''
    },
  },
}
</script>

<style lang="scss" scoped>
/* 一行里塞了状态点 + 文字 + 两个按钮 + 「成员」链接，窄侧栏下自然宽度之和会超过
   容器宽度。跟 WorkSessionBar 的稿态三按钮行是同一个地雷（v1 地雷 #24）：flex 默认
   不换行时，溢出的元素仍在 DOM 里、也仍"可见"，但视觉上被别的内容盖住，真实点击
   落空。flex-wrap: wrap 让按钮在窄侧栏下换行，而不是被裁切成不可点；cloud-text
   给 min-width 防止被压缩到只剩几像素。 */
.cloud-bar {
  display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx;
  padding: 16rpx 20rpx; border-bottom: 1px solid #eee;
}
.cloud-text { font-size: 26rpx; color: #333; flex: 1; min-width: 200rpx; }
.cloud-unlinked { color: #666; }
.cloud-hint { font-size: 23rpx; color: #999; }
.cloud-btn { flex-shrink: 0; }
.cloud-members-link {
  font-size: 23rpx; color: #12344D; text-decoration: underline; flex-shrink: 0;
}

.cloud-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #C8A45D; flex-shrink: 0;
}
.cloud-dot-yellow { background: #C8A45D; }
.cloud-dot-blue { background: #3E7CB1; }
.cloud-dot-green { background: #4C9A6A; }

.awd-btn { padding: 10rpx 20rpx; border-radius: 6rpx; font-size: 24rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; max-height: 74vh; display: flex; flex-direction: column; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.awd-input {
  width: 100%; padding: 16rpx; border: 1px solid #ddd;
  border-radius: 8rpx; font-size: 26rpx; box-sizing: border-box;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}

.cloud-members-loading, .cloud-members-empty { font-size: 25rpx; color: #888; padding: 12rpx 0; }
.cloud-members-list { margin-bottom: 20rpx; }
.cloud-member-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12rpx 0; border-bottom: 1px solid #f0f0f0; font-size: 25rpx;
}
.cloud-member-name { color: #222; }
.cloud-member-role { color: #888; font-size: 22rpx; }
.cloud-member-add-row { display: flex; gap: 12rpx; align-items: center; margin-top: 12rpx; }
.cloud-member-add-row .awd-input { flex: 1; }
.cloud-members-hint { font-size: 22rpx; color: #999; margin-top: 16rpx; }
</style>
