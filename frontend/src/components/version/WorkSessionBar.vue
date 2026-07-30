<template>
  <view class="session-bar">
    <template v-if="onDraft">
      <view class="session-dot draft-dot" />
      <text class="session-text">正在稿《{{ onDraft.name }}》上修改</text>
      <view class="awd-btn awd-btn-secondary session-btn" @tap="returnToMainline">回到主线工作</view>
      <view class="awd-btn awd-btn-primary session-btn" @tap="adopt">采纳这一稿</view>
      <view class="awd-btn awd-btn-danger session-btn" @tap="confirmAbandon">放弃这一稿</view>
    </template>
    <template v-else-if="working">
      <view class="session-dot" />
      <text class="session-text">工作中{{ changedCount ? `（已改 ${changedCount} 份文件）` : '' }}</text>
      <view class="awd-btn awd-btn-primary session-btn" @tap="openNaming">结束本次工作</view>
      <view class="awd-btn awd-btn-danger session-btn" @tap="confirmDiscard">丢弃</view>
    </template>
    <template v-else>
      <text class="session-text session-idle">当前没有进行中的工作</text>
    </template>

    <view v-if="naming" class="awd-mask" @tap.self="naming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">给这次工作起个名字</text></view>
        <view class="awd-body">
          <input
            v-model="title"
            class="awd-input"
            placeholder="例如：发客户第一稿（不填也可以）"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="naming = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="end">完成</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  endWorkSession, discardWorkSession,
  switchToMainline, adoptDraft, abandonDraft,
} from '@/services/api.js'

export default {
  name: 'WorkSessionBar',
  props: {
    working: { type: Boolean, default: false },
    changedCount: { type: Number, default: 0 },
    onDraft: { type: Object, default: null },
  },
  emits: ['ended', 'discarded', 'mainline-resumed', 'draft-adopted', 'draft-abandoned'],
  data() {
    return { naming: false, title: '', busy: false }
  },
  inject: ['projectId'],
  methods: {
    openNaming() {
      this.title = ''
      this.naming = true
    },
    async returnToMainline() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await switchToMainline(this.projectId)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.$emit('mainline-resumed', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '回到主线工作失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 成功但没生成版本（稿 tip 是主线祖先，没有实质内容可采纳）时后端带 notice，
    // 冲突（success=false）时不算失败——仓库进入待裁决态，交给三选一弹窗接手，
    // 这里只把已经变化的 affectedFileIds 转发出去（见 AdoptOutcome 注释）。
    async adopt() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await adoptDraft(this.projectId, this.onDraft.id)
        const data = (res && res.data) || {}
        if (data.success && data.notice) {
          uni.showToast({ title: data.notice, icon: 'none' })
        }
        this.$emit('draft-adopted', data.affectedFileIds || [])
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '采纳失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    confirmAbandon() {
      uni.showModal({
        title: '放弃这一稿',
        content: '这一稿的所有改动都会被丢掉，确定吗？',
        success: async (r) => {
          if (!r.confirm) return
          if (this.busy) return
          this.busy = true
          try {
            const res = await abandonDraft(this.projectId, this.onDraft.id)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            this.$emit('draft-abandoned', affectedFileIds)
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '放弃失败，请稍后重试', icon: 'none' })
          } finally {
            this.busy = false
          }
        },
      })
    },
    async end() {
      if (this.busy) return
      this.busy = true
      try {
        const res = await endWorkSession(this.projectId, this.title)
        // notice = 结束成功但没生成版本（整段工作一个改动都没有）。后端刻意用返回值
        // 而不是异常表达：它已经把工作段收尾了，走 catch 分支会让弹窗卡开、状态条
        // 停在「工作中」，而后台其实早就结束了。
        const notice = (res && res.data && res.data.notice) || ''
        if (notice) uni.showToast({ title: notice, icon: 'none' })
        this.naming = false
        this.$emit('ended')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '本次工作还没能收尾，你的改动都还在', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    confirmDiscard() {
      uni.showModal({
        title: '丢弃本次工作',
        content: '本次工作的所有改动都会被撤销，回到开始工作之前的样子。确定吗？',
        success: async (r) => {
          if (!r.confirm) return
          if (this.busy) return
          this.busy = true
          try {
            await discardWorkSession(this.projectId)
            this.$emit('discarded')
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '丢弃失败，请稍后重试', icon: 'none' })
          } finally {
            this.busy = false
          }
        },
      })
    },
  },
}
</script>

<style lang="scss" scoped>
.session-bar {
  display: flex; align-items: center; gap: 12rpx;
  padding: 16rpx 20rpx; border-bottom: 1px solid #eee;
}
.session-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #C8A45D;
}
.draft-dot { background: #7A5FC0; }
.session-text { font-size: 26rpx; color: #333; flex: 1; }
.session-idle { color: #999; }
.session-btn { flex-shrink: 0; }

.awd-btn { padding: 10rpx 20rpx; border-radius: 6rpx; font-size: 24rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-danger { background: #fff; color: #b23; border: 1px solid #e0c0c0; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; }
.awd-input {
  width: 100%; padding: 16rpx; border: 1px solid #ddd;
  border-radius: 8rpx; font-size: 26rpx;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
</style>
