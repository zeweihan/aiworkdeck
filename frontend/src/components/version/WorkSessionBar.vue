<template>
  <view class="session-bar">
    <template v-if="working">
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
import { endWorkSession, discardWorkSession } from '@/services/api.js'

export default {
  name: 'WorkSessionBar',
  props: {
    working: { type: Boolean, default: false },
    changedCount: { type: Number, default: 0 },
  },
  emits: ['ended', 'discarded'],
  data() {
    return { naming: false, title: '', busy: false }
  },
  inject: ['projectId'],
  methods: {
    openNaming() {
      this.title = ''
      this.naming = true
    },
    async end() {
      if (this.busy) return
      this.busy = true
      try {
        await endWorkSession(this.projectId, this.title)
        this.naming = false
        this.$emit('ended')
      } catch (e) {
        uni.showToast({ title: '本次工作还没能收尾，你的改动都还在', icon: 'none' })
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
            uni.showToast({ title: '丢弃失败，请稍后重试', icon: 'none' })
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
