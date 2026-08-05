<template>
  <view v-if="!collapsed" class="awd-mask">
    <view class="awd-dialog adopt-dialog">
      <view class="awd-header">
        <text class="awd-title">{{ dialogTitle }}</text>
      </view>
      <view class="awd-body">
        <template v-if="hasTarget">
          <view class="adopt-hint">{{ hintText }}</view>
          <view v-for="row in rows" :key="row.path" class="adopt-row">
            <view class="adopt-row-main">
              <text class="adopt-row-name">{{ row.name }}</text>
              <text
                v-if="mainlineTip && draftTip"
                class="adopt-row-compare"
                @tap="compare(row)"
              >看看两边差在哪</text>
            </view>
            <view class="adopt-row-choices">
              <view
                v-for="opt in choiceOptions"
                :key="opt.value"
                class="radio-item"
                :class="{ checked: resolutions[row.path] === opt.value }"
                @tap="choose(row.path, opt.value)"
              >
                <view class="radio-head">
                  <view class="radio-dot" />
                  <text class="radio-label">{{ opt.label }}</text>
                </view>
                <text class="radio-desc">{{ opt.desc }}</text>
              </view>
            </view>
          </view>
          <view class="adopt-foot-note">{{ footNote }}</view>
        </template>
        <!-- 文案要跟下面那个按钮的字对上：这里唯一可点的出口就是「先不采纳」，
             说「撤销」会让律师在界面上找不到对应的按钮。 -->
        <view v-else class="adopt-orphan-hint">
          这次采纳的信息读不全了，请点「先不采纳」退出来，稍后再试一次
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="abort">{{ abortLabel }}</view>
        <view
          v-if="hasTarget"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': !allChosen || busy }"
          @tap="confirm"
        >{{ confirmLabel }}</view>
      </view>
    </view>
  </view>
  <view v-else class="adopt-collapsed-bar">
    <text class="adopt-collapsed-text">还有文件等你选留哪一份</text>
    <text class="adopt-collapsed-resume" @tap="collapsed = false">继续处理</text>
  </view>
</template>

<script>
import {
  resolveAdopt, abortAdopt,
  resolveCloudMerge, abortCloudMerge,
  resolveSessionEnd, abortSessionEnd,
} from '@/services/api.js'

export default {
  name: 'AdoptConflictDialog',
  props: {
    projectId: { type: [String, Number], required: true },
    // 三语境标签映射（cloud/session-end 两处方向相反，改代码前先重读 Task 13 brief 那张表）：
    // adopt（现状）：MAIN=用主线的/DRAFT=用这一稿的，对比基线=主线(mainlineTip)、增量=这一稿(draftTip)。
    // cloud（更新冲突）：MAIN=用我这边的/DRAFT=用云端的，对比基线=我这边(mainlineTip)、增量=云端(draftTip=cloudTip)。
    // session-end（结束工作撞车）：MAIN=用同事的/DRAFT=用我这边的，对比基线=同事(mainlineTip)、增量=我这边(draftTip=sessionTip)。
    mode: { type: String, default: 'adopt' },
    // session-end 裁决需要工作段 id（resolveSessionEnd 的必填参数）。
    sessionId: { type: [String, Number], default: null },
    draftId: { type: [String, Number], default: null },
    draftName: { type: String, default: '' },
    conflictingPaths: { type: Array, default: () => [] },
    // 「对比」按钮要用的两个 ref：基线侧 tip / 增量侧 tip，来自 /status 对应的冲突字段。
    mainlineTip: { type: String, default: null },
    draftTip: { type: String, default: null },
  },
  emits: ['resolved', 'aborted', 'compare-file'],
  data() {
    return {
      resolutions: {},
      collapsed: false,
      busy: false,
    }
  },
  computed: {
    // adopt 语境下 draftId 反查失败（异常残局）时只给逃生门；cloud/session-end 不依赖
    // draftId 这个概念，只要 /status 给出了冲突态就一定能展示选择区。
    hasTarget() {
      return this.mode === 'adopt' ? !!this.draftId : true
    },
    dialogTitle() {
      if (this.mode === 'cloud') return '同一处，你和同事都改过'
      if (this.mode === 'session-end') return '收尾时发现同事已经交了新稿'
      return `采纳《${this.draftName || '这一稿'}》`
    },
    hintText() {
      return '下面这些文件，两边改的是同一处，只能留一份：'
    },
    // 三个选项的后果说明必须短到各占一行——弹窗高度受 max-height 限制，说明一长
    // 第三个选项就被挤到可视区外，而它在 DOM 里仍"可见"、点击坐标却落在别处
    // （v1 地雷 #24 的同款失败形态，本 PR 编写时现场踩到）。共性的兜底说明统一
    // 收到这条脚注里，不在每个选项里重复。
    footNote() {
      return `「两份都留着」的副本叫《原名（来自：${this.bothCopySide}）》。`
        + '不管怎么选，两边的内容都留在版本记录里，事后还能翻出来对比、退回。'
    },
    /*
     * 三语境的 MAIN / DRAFT 指向的物理侧不是同一件事（方向表见
     * .claude/agents/version-control.md 的「三语境冲突判定链」与地雷 #26）：
     *   adopt        MAIN=主线      DRAFT=这一稿
     *   cloud        MAIN=本机(我)  DRAFT=案件库里同事那份
     *   session-end  MAIN=同事      DRAFT=本机(我这段工作)
     * 装反的后果是律师选了「留我这份」、落盘的却是对方内容，而且不会有任何报错。
     * 下面只改 label / desc 两个字符串字段，绝不能调整 value 的归属或顺序。
     *
     * desc 是「选了会怎样」的实际后果，按后端 WorkSessionService.applyResolution 的
     * 真实行为写：MAIN 用 MAIN 侧字节覆盖这个文件；DRAFT 用 DRAFT 侧字节覆盖；
     * BOTH 是**原文件保留 MAIN 侧内容**，DRAFT 侧另存成同目录下的
     * 《原名（来自：{增量侧名字}）.扩展名》——不是把两边内容拼在一起。
     */
    choiceOptions() {
      if (this.mode === 'cloud') {
        return [
          { value: 'MAIN', label: '留我这份', desc: '这份保持你的内容，同事的改法不进来' },
          { value: 'DRAFT', label: '用同事那份', desc: '这份换成同事的内容，你的改动不进最终稿' },
          { value: 'BOTH', label: '两份都留着', desc: '你的留在原文件，同事那份另存一个副本' },
        ]
      }
      if (this.mode === 'session-end') {
        return [
          { value: 'MAIN', label: '用同事那份', desc: '这份按同事的内容收尾，你的改动不进最终稿' },
          { value: 'DRAFT', label: '留我这份', desc: '这份按你的内容收尾，同事那版不进来' },
          { value: 'BOTH', label: '两份都留着', desc: '同事的留在原文件，你这份另存一个副本' },
        ]
      }
      return [
        { value: 'MAIN', label: '用原来那份', desc: '这份保持采纳前的内容，这一稿的改动不进来' },
        { value: 'DRAFT', label: '用这一稿的', desc: '这份换成这一稿的内容，原来那版不再是当前内容' },
        { value: 'BOTH', label: '两份都留着', desc: '原来的留在原文件，这一稿那份另存一个副本' },
      ]
    },
    // 「两份都留着」另存出来的副本名里那个「来自」是谁，与后端 sideBySideRelPath 的
    // 「原名（来自：{增量侧名字}）扩展名」一致：cloud 传常量、其余传稿名/工作标题。
    bothCopySide() {
      if (this.mode === 'cloud') return '团队案件库'
      return this.draftName || '另一份'
    },
    compareLabels() {
      if (this.mode === 'cloud') return { oldLabel: '我这份', newLabel: '同事那份' }
      if (this.mode === 'session-end') return { oldLabel: '同事那份', newLabel: '我这份' }
      return { oldLabel: '原来那份', newLabel: '这一稿的' }
    },
    abortLabel() {
      if (this.mode === 'cloud') return '先不取回'
      if (this.mode === 'session-end') return '先不收尾'
      return '先不采纳'
    },
    confirmLabel() {
      if (this.mode === 'cloud') return '就按我选的来'
      if (this.mode === 'session-end') return '就按我选的来'
      return '就按我选的来'
    },
    rows() {
      return this.conflictingPaths.map((path) => ({
        path,
        name: path.split('/').pop() || path,
      }))
    },
    allChosen() {
      return this.rows.length > 0 && this.rows.every((r) => !!this.resolutions[r.path])
    },
  },
  methods: {
    choose(path, value) {
      this.resolutions = { ...this.resolutions, [path]: value }
    },
    // 弹窗的 .awd-mask 是全屏遮罩，和「对比」打开的编辑区标签页没法同屏共存；
    // 先收起弹窗（不销毁，已选的三选一保留在内存里），对比看完点「继续处理」再展开——
    // 裁决态本身留在后端（/status 的对应冲突字段），收起不会丢任何东西。
    compare(row) {
      this.collapsed = true
      this.$emit('compare-file', {
        path: row.path,
        name: row.name,
        newRef: this.draftTip,
        oldRef: this.mainlineTip,
        newLabel: this.compareLabels.newLabel,
        oldLabel: this.compareLabels.oldLabel,
      })
    },
    async confirm() {
      if (!this.allChosen || this.busy) return
      this.busy = true
      try {
        let res
        if (this.mode === 'cloud') {
          res = await resolveCloudMerge(this.projectId, this.resolutions)
        } else if (this.mode === 'session-end') {
          res = await resolveSessionEnd(this.projectId, this.sessionId, this.resolutions)
        } else {
          res = await resolveAdopt(this.projectId, this.draftId, this.resolutions)
        }
        const data = (res && res.data) || {}
        if (data.notice) uni.showToast({ title: data.notice, icon: 'none' })
        this.$emit('resolved', data.affectedFileIds || [])
      } catch (e) {
        const fallback = this.mode === 'cloud' ? '没能按你的选择处理，请稍后重试'
          : this.mode === 'session-end' ? '收尾没能完成，请稍后重试'
          : '采纳没能完成，请稍后重试'
        uni.showToast({ title: (e && e.message) || fallback, icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async abort() {
      if (this.busy) return
      this.busy = true
      try {
        let res
        let fallbackNotice
        if (this.mode === 'cloud') {
          res = await abortCloudMerge(this.projectId)
          fallbackNotice = '这次没有取回，你和同事的内容都还在'
        } else if (this.mode === 'session-end') {
          res = await abortSessionEnd(this.projectId)
          fallbackNotice = '这次没有收尾，你的改动都还在'
        } else {
          // abort-adopt 的路径参数在后端不参与判断（只按 projectId 找当前合并中的仓库），
          // draftId 反查落空（残局）时也用得到这条逃生门，占位传 0。
          res = await abortAdopt(this.projectId, this.draftId || 0)
          fallbackNotice = '这次没有采纳，两份稿都还在'
        }
        uni.showToast({ title: (res && res.message) || fallbackNotice, icon: 'none' })
        this.$emit('aborted')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '没能退出来，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.adopt-dialog { width: 680rpx; max-height: 84vh; display: flex; flex-direction: column; background: #fff; border-radius: 12rpx; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.adopt-hint { font-size: 24rpx; color: #666; margin-bottom: 14rpx; }
.adopt-foot-note { font-size: 21rpx; color: #999; line-height: 1.6; margin-top: 16rpx; }
.adopt-orphan-hint { font-size: 26rpx; color: #b23; line-height: 1.6; }
.adopt-row { padding: 16rpx 0; border-bottom: 1px solid #f0f0f0; }
.adopt-row-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10rpx; }
.adopt-row-name { font-size: 26rpx; color: #222; word-break: break-all; }
.adopt-row-compare { font-size: 23rpx; color: #12344D; text-decoration: underline; flex-shrink: 0; margin-left: 16rpx; }
.adopt-row-choices { display: flex; flex-direction: column; gap: 8rpx; }
.radio-item {
  display: flex; flex-direction: column; gap: 2rpx; cursor: pointer;
  padding: 8rpx 12rpx; border: 1px solid #eee; border-radius: 8rpx;
}
.radio-item.checked { border-color: #12344D; background: #F4F7F9; }
.radio-head { display: flex; align-items: center; gap: 8rpx; }
.radio-dot {
  width: 20rpx; height: 20rpx; border-radius: 50%; border: 1px solid #ccc;
  box-sizing: border-box; flex-shrink: 0;
}
.radio-item.checked .radio-dot { border-color: #12344D; background: #12344D; }
.radio-label { font-size: 23rpx; color: #444; }
.radio-item.checked .radio-label { color: #12344D; font-weight: 600; }
/* 后果说明：律师是靠这行判断「选了会发生什么」，不是靠上面那四个字。
   必须能在一行里放下，理由见 footNote 的注释。 */
.radio-desc { font-size: 21rpx; color: #888; line-height: 1.5; padding-left: 28rpx; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.adopt-collapsed-bar {
  position: fixed; left: 50%; bottom: 40rpx; transform: translateX(-50%);
  display: flex; align-items: center; gap: 16rpx;
  background: #12344D; color: #fff; padding: 14rpx 24rpx; border-radius: 999rpx;
  font-size: 24rpx; z-index: 999; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.2);
}
.adopt-collapsed-resume { text-decoration: underline; flex-shrink: 0; }
</style>
