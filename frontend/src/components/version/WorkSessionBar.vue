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

.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: #1A5336; color: #fff; }
.awd-btn-primary:hover { background: #14422b; }
.awd-btn-secondary { background: #fff; color: #475569; border: 1px solid #cbd5e1; }
.awd-btn-secondary:hover { background: #f1f5f9; }
.awd-btn-danger { background: #fff; color: #b23; border: 1px solid #f0c4c4; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.awd-dialog {
  width: 380px; max-width: 90vw; background: #fff; border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-header { padding: 18px 24px; border-bottom: 1px solid #f1f5f9; }
.awd-title { font-size: 16px; font-weight: 600; color: #0f172a; }
.awd-body { padding: 20px 24px; }
.awd-input {
  width: 100%; height: 38px; padding: 0 12px; border: 1px solid #cbd5e1;
  border-radius: 6px; font-size: 14px; color: #0f172a; box-sizing: border-box;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid #f1f5f9; background: #f8f9fa;
}
</style>
