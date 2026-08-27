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
              >{{ $t('version.compareViewDiff') }}</text>
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
          {{ $t('version.conflictOrphanHint') }}
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="abort">{{ abortLabel }}</view>
        <view
          v-if="hasTarget"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': !allChosen || busy }"
          @tap="confirm"
        >{{ $t('version.confirmChoice') }}</view>
      </view>
    </view>
  </view>
  <view v-else class="adopt-collapsed-bar">
    <text class="adopt-collapsed-text">{{ $t('version.pendingChoiceBar') }}</text>
    <text class="adopt-collapsed-resume" @tap="collapsed = false">{{ $t('version.resumeProcessing') }}</text>
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
      if (this.mode === 'cloud') return this.$t('version.conflictTitleCloud')
      if (this.mode === 'session-end') return this.$t('version.conflictTitleSessionEnd')
      return this.$t('version.conflictTitleAdopt', { name: this.draftName || this.$t('version.thisDraftFallback') })
    },
    /*
     * 措辞不能说「改的是同一处」。Word/PDF 这些文档在版本记录里是整份字节，两边只要
     * 都动过就整份进这张清单，改的是不是同一条条款根本无从判断（JGit 合并器对二进制
     * blob 直接判冲突）；律师照「同一处」去理解，会以为选「留我这份」只丢那一处重叠，
     * 实际丢的是对方对这份文档的全部改动。所以只讲事实：两边都改过，整份二选一。
     */
    hintText() {
      return this.$t('version.conflictHint')
    },
    // 三个选项的后果说明必须短到各占一行——弹窗高度受 max-height 限制，说明一长
    // 第三个选项就被挤到可视区外，而它在 DOM 里仍"可见"、点击坐标却落在别处
    // （v1 地雷 #24 的同款失败形态，本 PR 编写时现场踩到）。共性的兜底说明统一
    // 收到这条脚注里，不在每个选项里重复。
    footNote() {
      return this.$t('version.conflictFootNote', { side: this.bothCopySide })
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
     * 每条都以「整份」起头：落地口径是整份字节覆盖，不是挑着合，理由见 hintText。
     */
    choiceOptions() {
      if (this.mode === 'cloud') {
        return [
          { value: 'MAIN', label: this.$t('version.keepMineLabel'), desc: this.$t('version.cloudKeepMineDesc') },
          { value: 'DRAFT', label: this.$t('version.useColleagueLabel'), desc: this.$t('version.cloudUseColleagueDesc') },
          { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.cloudBothDesc') },
        ]
      }
      if (this.mode === 'session-end') {
        return [
          { value: 'MAIN', label: this.$t('version.useColleagueLabel'), desc: this.$t('version.sessionUseColleagueDesc') },
          { value: 'DRAFT', label: this.$t('version.keepMineLabel'), desc: this.$t('version.sessionKeepMineDesc') },
          { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.sessionBothDesc') },
        ]
      }
      return [
        { value: 'MAIN', label: this.$t('version.adoptUseOriginalLabel'), desc: this.$t('version.adoptUseOriginalDesc') },
        { value: 'DRAFT', label: this.$t('version.adoptUseDraftLabel'), desc: this.$t('version.adoptUseDraftDesc') },
        { value: 'BOTH', label: this.$t('version.bothLabel'), desc: this.$t('version.adoptBothDesc') },
      ]
    },
    // 「两份都留着」另存出来的副本名里那个「来自」是谁，与后端 sideBySideRelPath 的
    // 「原名（来自：{增量侧名字}）扩展名」一致：cloud 传常量、其余传稿名/工作标题。
    bothCopySide() {
      if (this.mode === 'cloud') return this.$t('version.teamCaseLibrary')
      return this.draftName || this.$t('version.anotherCopy')
    },
    compareLabels() {
      if (this.mode === 'cloud') return { oldLabel: this.$t('version.myShareLabel'), newLabel: this.$t('version.colleagueShareLabel') }
      if (this.mode === 'session-end') return { oldLabel: this.$t('version.colleagueShareLabel'), newLabel: this.$t('version.myShareLabel') }
      return { oldLabel: this.$t('version.originalShareLabel'), newLabel: this.$t('version.thisDraftShareLabel') }
    },
    abortLabel() {
      if (this.mode === 'cloud') return this.$t('version.abortCloud')
      if (this.mode === 'session-end') return this.$t('version.abortSessionEnd')
      return this.$t('version.abortAdopt')
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
        const fallback = this.mode === 'cloud' ? this.$t('version.resolveFailedCloud')
          : this.mode === 'session-end' ? this.$t('version.resolveFailedSessionEnd')
          : this.$t('version.resolveFailedAdopt')
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
          fallbackNotice = this.$t('version.abortNoticeCloud')
        } else if (this.mode === 'session-end') {
          res = await abortSessionEnd(this.projectId)
          fallbackNotice = this.$t('version.abortNoticeSessionEnd')
        } else {
          // abort-adopt 的路径参数在后端不参与判断（只按 projectId 找当前合并中的仓库），
          // draftId 反查落空（残局）时也用得到这条逃生门，占位传 0。
          res = await abortAdopt(this.projectId, this.draftId || 0)
          // 与后端 WorkSessionService.adoptAbortedNotice() 中英两句逐字一致：正常路径显示的
          // 是后端那句，两处措辞不同的话只有在后端没带 message 时才会露馅，很难被发现。
          fallbackNotice = this.$t('version.abortNoticeAdopt')
        }
        uni.showToast({ title: (res && res.message) || fallbackNotice, icon: 'none' })
        this.$emit('aborted')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.abortFailedGeneric'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: var(--awd-overlay);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.adopt-dialog {
  width: 460px; max-width: 92vw; max-height: 84vh;
  display: flex; flex-direction: column; background: var(--awd-surface);
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-header { padding: 18px 24px; border-bottom: 1px solid var(--awd-border-subtle); }
.awd-title { font-size: 16px; font-weight: 600; color: var(--awd-text); }
.awd-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
.adopt-hint { font-size: 13px; color: var(--awd-text-2); margin-bottom: 14px; line-height: 1.6; }
.adopt-foot-note { font-size: 12px; color: var(--awd-text-3); line-height: 1.6; margin-top: 14px; }
.adopt-orphan-hint { font-size: 13.5px; color: var(--awd-danger-text); line-height: 1.6; }
.adopt-row { padding: 14px 0; border-bottom: 1px solid var(--awd-border-subtle); }
.adopt-row-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.adopt-row-name { font-size: 13.5px; color: var(--awd-text); word-break: break-all; }
.adopt-row-compare { font-size: 12px; color: var(--awd-accent-text); text-decoration: underline; cursor: pointer; flex-shrink: 0; margin-left: 12px; }
.adopt-row-choices { display: flex; flex-direction: column; gap: 6px; }
.radio-item {
  display: flex; flex-direction: column; gap: 2px; cursor: pointer;
  padding: 8px 10px; border: 1px solid var(--awd-border); border-radius: 6px;
}
.radio-item.checked { border-color: var(--awd-accent); background: var(--awd-accent-soft); }
.radio-head { display: flex; align-items: center; gap: 8px; }
.radio-dot {
  width: 12px; height: 12px; border-radius: 50%; border: 1px solid var(--awd-border-strong);
  background: var(--awd-surface); box-sizing: border-box; flex-shrink: 0;
}
.radio-item.checked .radio-dot { border-color: var(--awd-accent); background: var(--awd-accent); }
.radio-label { font-size: 13px; color: var(--awd-text); }
.radio-item.checked .radio-label { color: var(--awd-accent-text); font-weight: 600; }
/* 后果说明：律师是靠这行判断「选了会发生什么」，不是靠上面那四个字。
   必须能在一行里放下，理由见 footNote 的注释。 */
.radio-desc { font-size: 12px; color: var(--awd-text-3); line-height: 1.5; padding-left: 20px; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid var(--awd-border-subtle); background: var(--awd-bg);
}
.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.awd-btn-primary:hover { background: var(--awd-accent-hover); }
.awd-btn-secondary { background: var(--awd-surface); color: var(--awd-text-2); border: 1px solid var(--awd-border-strong); }
.awd-btn-secondary:hover { background: var(--awd-surface-2); }
.awd-btn-disabled { opacity: .45; pointer-events: none; }

.adopt-collapsed-bar {
  position: fixed; left: 50%; bottom: 24px; transform: translateX(-50%);
  display: flex; align-items: center; gap: 12px;
  background: var(--awd-accent); color: var(--awd-text-on-accent); padding: 10px 20px; border-radius: 999px;
  font-size: 13px; z-index: 9999; box-shadow: 0 10px 25px -5px rgba(0,0,0,.25);
}
.adopt-collapsed-resume { text-decoration: underline; flex-shrink: 0; cursor: pointer; }
</style>
