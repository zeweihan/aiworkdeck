<template>
  <view class="session-bar">
    <template v-if="onDraft">
      <view class="session-dot draft-dot" />
      <text class="session-text">{{ $t('version.workingOnDraft', { name: onDraft.name }) }}</text>
      <view class="awd-btn awd-btn-secondary session-btn" @tap="returnToMainline">{{ $t('version.returnToMainline') }}</view>
      <view class="awd-btn awd-btn-primary session-btn" @tap="adopt">{{ $t('version.adoptDraft') }}</view>
      <view class="awd-btn awd-btn-danger session-btn" @tap="confirmAbandon">{{ $t('version.abandonDraft') }}</view>
    </template>
    <template v-else-if="working">
      <view class="session-dot" />
      <text class="session-text">{{ changedCount ? $t('version.workingWithCount', { count: changedCount }) : $t('version.working') }}</text>
      <view class="awd-btn awd-btn-primary session-btn" @tap="openNaming">{{ $t('version.endSession') }}</view>
      <view class="awd-btn awd-btn-danger session-btn" @tap="confirmDiscard">{{ $t('version.discard') }}</view>
    </template>
    <template v-else>
      <text class="session-text session-idle">{{ $t('version.noActiveSession') }}</text>
    </template>

    <view v-if="naming" class="awd-mask" @tap.self="naming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">{{ $t('version.nameSessionTitle') }}</text></view>
        <view class="awd-body">
          <input
            v-model="title"
            class="awd-input"
            :placeholder="$t('version.sessionNamePlaceholder')"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="naming = false">{{ $t('common.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="end">{{ $t('version.finish') }}</view>
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
        uni.showToast({ title: (e && e.message) || this.$t('version.returnMainlineFailed'), icon: 'none' })
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
        uni.showToast({ title: (e && e.message) || this.$t('version.adoptFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    confirmAbandon() {
      uni.showModal({
        title: this.$t('version.abandonDraft'),
        content: this.$t('version.abandonDraftConfirmContent'),
        success: async (r) => {
          if (!r.confirm) return
          if (this.busy) return
          this.busy = true
          try {
            const res = await abandonDraft(this.projectId, this.onDraft.id)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            this.$emit('draft-abandoned', affectedFileIds)
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('version.abandonFailed'), icon: 'none' })
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
        uni.showToast({ title: (e && e.message) || this.$t('version.endFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    confirmDiscard() {
      uni.showModal({
        title: this.$t('version.discardSessionTitle'),
        content: this.$t('version.discardSessionContent'),
        success: async (r) => {
          if (!r.confirm) return
          if (this.busy) return
          this.busy = true
          try {
            // 丢弃改写了磁盘（切回主线内容），打开中的编辑器必须跟着重载，
            // 否则下一次 autosave 会把刚被丢弃的工作原样写回去（同退回/切线）。
            const res = await discardWorkSession(this.projectId)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            this.$emit('discarded', affectedFileIds)
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('version.discardFailed'), icon: 'none' })
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
  display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx;
  padding: 16rpx 20rpx; border-bottom: 1px solid #eee;
}
.session-dot {
  width: 14rpx; height: 14rpx; border-radius: 50%; background: #C8A45D;
}
.draft-dot { background: #7A5FC0; }
/* 稿态一行有 3 个操作按钮（回到主线工作/采纳这一稿/放弃这一稿），加上这段文字，
   窄侧栏下自然宽度之和远超容器宽度。flex 默认不换行时，session-text（flex:1，
   会被按钮的 flex-shrink:0 挤到只剩几像素）与溢出的按钮一起被侧栏容器的
   overflow 裁切掉——按钮仍在 DOM 里、也仍"可见"（不是 display:none），但视觉
   上被别的内容盖住，真实点击落在盖住它的那块内容上，跟"稿态选择器存在"这条
   断言无关，是另一种假阳性（e2e 实测：elementFromPoint 在按钮几何坐标处命中的
   是编辑区内容，不是按钮本身）。min-width 防止文字被挤到只剩几像素竖排；
   wrap 让按钮在窄侧栏下换行，而不是被裁切成不可点。 */
.session-text { font-size: 26rpx; color: #333; flex: 1; min-width: 200rpx; }
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
