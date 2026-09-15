<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  交稿引导（dev-board#645）。

  病灶：律师点「交稿」，后端回一句「案件库里有同事交的新稿，先取回再交」的 toast 就没了。
  那句话说不清下一步该点哪里，他只会反复点同一个按钮。这里把「能不能交」摊成三步、
  按顺序解锁、每一步给一个当场能点的按钮，做完一步就地重读状态刷新勾选。

  三处交稿入口（提交历史工具栏 / 协作抽屉 / 底部状态条那一格通往的抽屉）共用
  project-overview 上挂的这一个实例：三份各自判断必然走散，而判错的后果是律师看到
  一句他看不懂的后端错误。后端那两句（REMOTE_AHEAD / NOTHING_TO_SUBMIT）仍留着兜底
  ——前端的判据是现读一次 /version/status，但仍可能漏（读失败退回快照、或读完到推之间
  才开的工作段），漏了就靠后端那一句；正常路径不再走到它们。

  mode='help' 是同一个弹窗的说明模式（协作抽屉里那个「这套协作怎么用」链接）：
  只摊开折叠区，不摆步骤——他此刻并没有在交稿，摆一排按钮等于把说明变成了操作。
-->
<template>
  <view v-if="visible" class="awd-mask" @tap.self="close">
    <view class="awd-dialog submit-guide" @tap.stop>
      <view class="awd-header">
        <text class="awd-title">{{ titleText }}</text>
      </view>

      <view class="awd-body">
        <!-- 连不上案件库：三步一步都做不了（取回要联网、交稿也要），摆一排灰按钮
             只会让人以为软件卡住了。说清楚「改动都在本机」再放人走。 -->
        <view v-if="guide.offline && mode !== 'help'" class="sg-offline">
          {{ $t('version.submitGuideOfflineDesc') }}
        </view>

        <view v-else-if="mode !== 'help'" class="sg-steps">
          <view
            v-for="(step, i) in guide.steps"
            :key="step.id"
            class="sg-step"
            :class="['sg-step-' + step.id, 'sg-' + step.state]"
          >
            <view class="sg-step-head">
              <text class="sg-step-index">{{ i + 1 }}</text>
              <text class="sg-step-title">{{ $t(stepTitleKey(step)) }}</text>
              <text class="sg-step-state">{{ $t(stateLabelKey(step)) }}</text>
            </view>
            <text class="sg-step-desc">{{ stepDesc(step) }}</text>

            <!-- 结束工作那一步给个名字：工作段的名字就是时间线上那个节点的标题，
                 从这条路交稿也不该丢掉命名的机会（与版本面板的命名弹窗同一个占位文案）。 -->
            <input
              v-if="step.id === 'end-session' && step.state === 'active'"
              v-model="sessionName"
              class="awd-input sg-step-input"
              :placeholder="$t('version.sessionNamePlaceholder')"
            />

            <view
              class="awd-btn sg-step-btn"
              :class="[
                step.state === 'active' ? 'awd-btn-primary' : 'awd-btn-secondary',
                (step.state !== 'active' || busy) ? 'awd-btn-disabled' : '',
              ]"
              @tap="runStep(step)"
            >{{ $t(stepTitleKey(step)) }}</view>
          </view>
        </view>

        <!-- 折叠区：默认收起（正在交稿的人要的是按钮，不是课文）；说明模式下常开。 -->
        <view class="sg-how">
          <view v-if="mode !== 'help'" class="sg-how-toggle" @tap="expanded = !expanded">
            <text class="sg-how-arrow">{{ expanded ? '▼' : '▶' }}</text>
            <text>{{ $t('version.submitGuideHowTitle') }}</text>
          </view>
          <view v-if="expanded || mode === 'help'" class="sg-how-body">
            <text class="sg-how-line">{{ $t('version.submitGuideHow1') }}</text>
            <text class="sg-how-line">{{ $t('version.submitGuideHow2') }}</text>
            <text class="sg-how-line">{{ $t('version.submitGuideHow3') }}</text>
            <text class="sg-how-line">{{ $t('version.submitGuideHow4') }}</text>
            <text class="sg-how-example">{{ $t('version.submitGuideHowExample') }}</text>
            <text class="sg-how-mnemonic">{{ $t('version.submitGuideHowMnemonic') }}</text>
          </view>
        </view>
      </view>

      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary sg-later" @tap="close">
          {{ mode === 'help' ? $t('common.close') : $t('version.submitGuideLater') }}
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  endWorkSession, updateFromCloud, uploadToCloud,
  getVersionStatus, getCloudStatus, checkCloud,
} from '@/services/api.js'
import { submitGuideSteps } from '@/utils/submitGuide.js'
import { pullStepText } from '@/utils/collabWording.js'

export default {
  name: 'SubmitDraftGuide',
  props: {
    visible: { type: Boolean, default: false },
    projectId: { type: [String, Number], required: true },
    // 打开这一刻页面手上的那份状态（/version/status 的 working、cloudStatus）。
    // 弹窗打开后由自己重读保鲜——律师在弹窗里做完一步，页面那份还是旧的。
    working: { type: Boolean, default: false },
    cloud: { type: Object, default: null },
    // 'guide' = 从「交稿」拦下来的；'help' = 抽屉里的「这套协作怎么用」，只看说明
    mode: { type: String, default: 'guide' },
  },
  // changed：状态变了，页面重拉。reload-files：磁盘被改写，重载打开中的编辑器。
  // conflict：撞上了要逐份选择的情况，页面把人送到裁决现场。
  emits: ['update:visible', 'changed', 'reload-files', 'conflict'],
  data() {
    return {
      localWorking: false,
      localCloud: null,
      sessionName: '',
      expanded: false,
      busy: false,
    }
  },
  computed: {
    guide() {
      const c = this.localCloud || {}
      return submitGuideSteps({
        working: this.localWorking,
        remoteAhead: c.remoteAhead,
        remoteAheadCount: c.remoteAheadCount,
        remoteAheadBySelf: c.remoteAheadBySelf,
        offline: c.offline,
      })
    },
    titleText() {
      if (this.mode === 'help') return this.$t('version.submitGuideHelpTitle')
      return this.$t(this.guide.title)
    },
  },
  watch: {
    visible(v) {
      if (!v) return
      this.localWorking = !!this.working
      this.localCloud = this.cloud || {}
      this.sessionName = ''
      this.expanded = this.mode === 'help'
      this.busy = false
      if (this.mode === 'help') return
      // 先用页面递过来的快照把清单画出来（便宜、立刻有结果），再走一次联网重读。
      // cloudStatus 是不联网的本地快照，同事可能几小时前就交了新稿而本机还显示
      // 「一致」——那会让这张清单把「先取回」那一步整个漏掉（口径见 fetchCollabState）。
      //
      // 重读落地之前先 busy 把三个按钮压住（dev-board 0.44.1 清单 B3）：页面那份 working 不会
      // 因为律师刚才的编辑而刷新，所以开窗第一帧很可能画成「可以交稿了」、第 ③ 步
      // 还是个可点的主按钮——那一下点下去就是一次什么都没交的空交稿。
      this.busy = true
      this.refresh({ online: true }).finally(() => { this.busy = false })
    },
  },
  methods: {
    close() {
      this.$emit('update:visible', false)
    },
    stepTitleKey(step) {
      return { 'end-session': 'version.endSession', 'pull-latest': 'version.pullLatestAction', submit: 'version.submitDraftAction' }[step.id]
    },
    stateLabelKey(step) {
      return { done: 'version.submitGuideStateDone', active: 'version.submitGuideStateActive', todo: 'version.submitGuideStateTodo' }[step.state]
    },
    stepDesc(step) {
      if (step.id === 'end-session') {
        return step.state === 'done'
          ? this.$t('version.submitGuideStepEndDone')
          : this.$t('version.submitGuideStepEndDesc')
      }
      if (step.id === 'pull-latest') {
        // 「取回同事交的 6 版」与顶栏那句「同事交了新稿 · 6 版」同源同规则
        return step.state === 'done'
          ? this.$t('version.submitGuidePullDone')
          : pullStepText((k, p) => this.$t(k, p), this.localCloud)
      }
      return this.$t('version.submitGuideStepSubmitDesc')
    },
    runStep(step) {
      if (this.busy || step.state !== 'active') return
      if (step.id === 'end-session') return this.doEndSession()
      if (step.id === 'pull-latest') return this.doPull()
      return this.doSubmit()
    },
    /**
     * 每步做完就地重读两份状态（版本状态 + 云端本地快照），律师不用关掉弹窗再点一次。
     * 顺带兜住裁决窗口：结束工作/取回都可能把仓库停在「等你逐份选择」，那时弹窗留在
     * 这里没有任何意义——关掉，把人送去裁决现场。
     * online=true 只用在刚打开的那一次（cloudStatus 是不联网的本地快照）；做完一步之后
     * 用本地快照就够——取回刚刚已经真 fetch 过，结束工作不改变案件库那一侧。
     *
     * @returns {Promise<boolean>} true = 撞上裁决窗口，调用方不要再往下走
     */
    async refresh({ online = false } = {}) {
      let conflicted = false
      try {
        const res = await getVersionStatus(this.projectId)
        const d = (res && res.data) || {}
        this.localWorking = !!d.working
        conflicted = !!(d.sessionEndConflict || d.cloudConflict || d.adoptConflict)
      } catch (e) {
        console.warn('[SubmitGuide] 读取版本状态失败', e)
      }
      try {
        const res = online ? await checkCloud(this.projectId) : await getCloudStatus(this.projectId)
        this.localCloud = (res && res.data) || this.localCloud
      } catch (e) {
        console.warn('[SubmitGuide] 读取协作状态失败', e)
      }
      this.$emit('changed')
      if (conflicted) {
        this.close()
        this.$emit('conflict')
      }
      return conflicted
    },
    // 与 WorkSessionBar.end 同一套结果处理：notice = 结束成功但没生成版本（这段工作
    // 一个改动都没有），后端刻意用返回值不用异常——走 catch 会让状态停在「工作中」。
    async doEndSession() {
      this.busy = true
      try {
        const res = await endWorkSession(this.projectId, this.sessionName)
        const notice = (res && res.data && res.data.notice) || ''
        if (notice) uni.showToast({ title: notice, icon: 'none' })
        this.sessionName = ''
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.endFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 与 CollabDialog.onUpdate / CommitHistoryTab.onPullLatest 同一套口径
    async doPull() {
      this.busy = true
      try {
        const res = await updateFromCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPDATED') {
          uni.showToast({ title: this.$t('version.pulledLatest'), icon: 'none' })
          this.$emit('reload-files', d.affectedFileIds || [])
        } else if (d.status === 'CONFLICT') {
          this.close()
          this.$emit('changed')
          this.$emit('conflict')
          return
        } else if (d.status === 'OFFLINE') {
          uni.showToast({ title: this.$t('version.libraryOffline'), icon: 'none' })
        }
        await this.refresh()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.pullFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    // 与 CollabDialog.onUpload / CommitHistoryTab.onSubmitDraft 同一套口径
    async doSubmit() {
      this.busy = true
      try {
        const res = await uploadToCloud(this.projectId)
        const d = (res && res.data) || {}
        if (d.status === 'UPLOADED') {
          uni.showToast({ title: this.$t('version.submitted'), icon: 'none' })
          const ids = d.affectedFileIds || []
          if (ids.length) this.$emit('reload-files', ids)
          this.close()
          this.$emit('changed')
        } else if (d.status === 'CONFLICT') {
          this.close()
          this.$emit('changed')
          this.$emit('conflict')
        } else {
          // 判据可能已经陈旧（比如刚刚同事又交了一版、或者这一步点下去之前又开了一段
          // 活）：后端那句 REMOTE_AHEAD / NOTHING_TO_SUBMIT 就是这条路的兜底，**原样**
          // 说给律师听，再重读一次状态把清单上的勾刷新。本档不许自己拼文案：后端那句
          // 才知道到底是「先取回」还是「先结束本次工作」。
          uni.showToast({ title: d.message || this.$t('version.submitFailedNotice'), icon: 'none' })
          await this.refresh()
        }
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.submitFailed'), icon: 'none' })
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
.submit-guide {
  width: 460px; max-width: 92vw; max-height: 84vh;
  display: flex; flex-direction: column; background: var(--awd-surface);
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-header { padding: 18px 24px; border-bottom: 1px solid var(--awd-border-subtle); }
.awd-title { font-size: 16px; font-weight: 600; color: var(--awd-text); }
.awd-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
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
.awd-btn-disabled { opacity: .45; pointer-events: none; }
.awd-input {
  width: 100%; height: 34px; padding: 0 10px; margin-bottom: 10px;
  border: 1px solid var(--awd-border-strong); border-radius: 6px;
  font-size: 13px; color: var(--awd-text); box-sizing: border-box;
}

.sg-offline { font-size: 13px; color: var(--awd-text-2); line-height: 1.7; }

.sg-steps { display: flex; flex-direction: column; gap: 10px; }
.sg-step {
  padding: 12px 14px; border: 1px solid var(--awd-border); border-radius: 8px;
  background: var(--awd-bg); display: flex; flex-direction: column; align-items: flex-start; gap: 8px;
}
.sg-step.sg-active { border-color: var(--awd-accent); background: var(--awd-surface); }
.sg-step.sg-done { opacity: .72; }
.sg-step-head { display: flex; align-items: center; gap: 8px; width: 100%; }
.sg-step-index {
  width: 20px; height: 20px; border-radius: 50%; flex-shrink: 0;
  background: var(--awd-surface-2); color: var(--awd-text-2);
  font-size: 12px; line-height: 20px; text-align: center;
}
.sg-step.sg-done .sg-step-index { background: var(--awd-accent); color: var(--awd-text-on-accent); }
.sg-step-title { font-size: 14px; font-weight: 600; color: var(--awd-text); flex: 1; }
.sg-step-state { font-size: 12px; color: var(--awd-text-3); flex-shrink: 0; }
.sg-step.sg-active .sg-step-state { color: var(--awd-accent); }
.sg-step-desc { font-size: 12.5px; color: var(--awd-text-2); line-height: 1.6; }
.sg-step-input { margin-bottom: 0; }

.sg-how { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--awd-border-subtle); }
.sg-how-toggle {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; color: var(--awd-accent); cursor: pointer;
}
.sg-how-arrow { font-size: 9px; }
.sg-how-body { display: flex; flex-direction: column; gap: 8px; margin-top: 10px; }
.sg-how-line { font-size: 12.5px; color: var(--awd-text-2); line-height: 1.7; }
.sg-how-example {
  font-size: 12.5px; color: var(--awd-text-2); line-height: 1.7;
  padding: 10px 12px; background: var(--awd-bg); border-radius: 6px;
}
.sg-how-mnemonic { font-size: 13px; color: var(--awd-text); font-weight: 600; }
</style>
