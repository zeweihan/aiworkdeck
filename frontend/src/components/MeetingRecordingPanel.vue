<template>
  <!-- Vue 3 多根节点：与 ShareholderMeetingPanel 同构 -->

  <!-- 面板标题由外壳的 sidebar-header 统一出，这里不再自画一份
       （标题文案因此走 config.sidebar.meetingRecorder，不在 meeting 命名空间里） -->

  <!-- 转写档位与就绪状态。**摆在录音开始之前**，不拖到转写那一刻才暴露：
       律师需要在按下录音键之前就知道这段录音会不会出本机（设计 §6.2.1）。 -->
  <view class="mr-tier" v-if="tierKnown">
    <view class="mr-tier-row">
      <text class="mr-tier-label">{{ $t('meeting.tierLabel') }}</text>
      <text class="mr-tier-value" :class="tierClass">{{ tierText }}</text>
    </view>
    <text class="mr-tier-desc">{{ tierDesc }}</text>
    <!-- 「录音不出本机」。切换时**就地探一次**，没就绪就不许留在打开态（设计 §6.2.1）：
         做成能打开、录完两小时才发现转不了的样子，用户只剩「放弃这份录音」或
         「关掉开关传上云」两条路，后者与他打开开关的目的正好相反。 -->
    <view class="mr-tier-row mr-tier-switch">
      <view class="mr-tier-switch-info">
        <text class="mr-tier-label">{{ $t('meeting.localSwitchLabel') }}</text>
        <text class="mr-tier-desc">{{ localSwitchNote }}</text>
      </view>
      <AwdSwitch
        :checked="asrProvider === 'local'"
        :disabled="tierBusy"
        @change="onToggleLocalAsr"
      />
    </view>

    <!-- 未就绪时的就地出路。**「服务没起」与「模型没下」必须分开**：
         前者重启应用，后者要下一个 GB 级模型，合并成一句「不可用」等于让用户猜。 -->
    <view class="mr-tier-gate" v-if="localGate">
      <text class="mr-tier-gate-msg">{{ localGate.message }}</text>
      <text class="mr-tier-desc">{{ localGate.nextStep }}</text>
      <view class="mr-tier-gate-actions">
        <template v-if="modelDownloading">
          <text class="mr-tier-desc">{{ $t('meeting.modelDownloading', { percent: modelPercent }) }}</text>
          <view class="mr-btn secondary" @tap="onCancelAsrModel">{{ $t('meeting.cancelDownload') }}</view>
        </template>
        <template v-else>
          <view class="mr-btn primary" v-if="canDownloadModel" @tap="onDownloadAsrModel">
            {{ $t('meeting.downloadModel', { size: modelSizeText }) }}
          </view>
          <view class="mr-btn secondary" @tap="onRecheckLocalAsr">{{ $t('meeting.recheck') }}</view>
        </template>
      </view>
    </view>
  </view>

  <!-- 未配置转写凭证的提示（录音仍可用）。下一步按档位分：
       平台代采档缺的是账户，自备 Key 档缺的才是那五个阿里云凭证。 -->
  <view class="mr-config-hint" v-if="configured === false">
    <text>{{ notConfiguredHint }}</text>
  </view>

  <!-- 录音区：开会点一下就开录，录前零表单 -->
  <view class="mr-record-zone">
    <template v-if="!recordingActive">
      <view class="mr-record-btn" @tap="onStartRecording">
        <view class="mr-record-dot"></view>
        <text class="mr-record-text">{{ $t('meeting.startRecording') }}</text>
      </view>
      <text class="mr-record-hint">{{ $t('meeting.startHint') }}</text>
    </template>
    <template v-else-if="recordingHere">
      <view class="mr-recording-live">
        <view class="mr-live-row">
          <view class="mr-live-dot" :class="{ paused: recState.status === 'paused' }"></view>
          <text class="mr-live-time">{{ formatSeconds(recState.seconds) }}</text>
          <text class="mr-live-label">{{ recState.status === 'paused' ? $t('meeting.paused') : $t('meeting.recording') }}</text>
        </view>
        <view class="mr-level-track">
          <view class="mr-level-bar" :style="{ width: Math.round(recState.level * 100) + '%' }"></view>
        </view>
        <view class="mr-live-actions">
          <view class="mr-btn secondary" @tap="onTogglePause">
            {{ recState.status === 'paused' ? $t('meeting.resume') : $t('meeting.pause') }}
          </view>
          <view class="mr-btn danger" :class="{ disabled: recState.status === 'stopping' }" @tap="onStopRecording">
            {{ recState.status === 'stopping' ? $t('meeting.saving') : $t('meeting.stopRecording') }}
          </view>
        </view>
        <text class="mr-record-hint">{{ $t('meeting.backgroundHint') }}</text>
        <text class="mr-record-error" v-if="recState.error">{{ recState.error }}</text>
      </view>
    </template>
    <template v-else>
      <text class="mr-record-hint">{{ $t('meeting.otherProjectRecording') }}</text>
    </template>
  </view>

  <!-- 会议列表 -->
  <view class="mr-sec-head">
    <text class="mr-sec-title">{{ $t('meeting.sectionTitle') }}</text>
    <text class="mr-sec-count">{{ meetings.length }}</text>
  </view>
  <view class="mr-list">
    <view v-if="!meetings.length" class="mr-list-empty">
      <text>{{ $t('meeting.empty') }}</text>
    </view>
    <view v-for="m in meetings" :key="m.id" class="mr-item">
      <view class="mr-item-head" @tap="toggleExpand(m)">
        <view class="mr-item-info">
          <text class="mr-item-title">{{ m.title }}</text>
          <text class="mr-item-meta">{{ metaLine(m) }}</text>
        </view>
        <text class="mr-status" :class="m.status.toLowerCase()">{{ statusText(m.status) }}</text>
      </view>

      <!-- 详情 -->
      <view class="mr-detail" v-if="expandedId === m.id">
        <!-- 标题改名 -->
        <view class="mr-title-edit" v-if="editingTitleId === m.id">
          <input class="mr-input" v-model="editingTitle" :placeholder="$t('meeting.titlePlaceholder')" />
          <view class="mr-btn secondary small" @tap="editingTitleId = null">{{ $t('meeting.cancel') }}</view>
          <view class="mr-btn primary small" @tap="saveTitle(m)">{{ $t('meeting.save') }}</view>
        </view>
        <view class="mr-detail-tools" v-else>
          <text class="mr-link" @tap="startEditTitle(m)">{{ $t('meeting.renameTitle') }}</text>
          <text class="mr-link" @tap="togglePlay(m)">{{ playingId === m.id ? $t('meeting.stopPlayback') : $t('meeting.playRecording') }}</text>
          <text class="mr-link danger" @tap="confirmDelete(m)">{{ $t('meeting.delete') }}</text>
        </view>

        <!-- 已录音未转写 -->
        <view v-if="m.status === 'RECORDED'" class="mr-section">
          <text class="mr-hint" v-if="configured === false">{{ $t('meeting.needCredentialsHint') }}</text>
          <view v-else class="mr-btn primary" @tap="onTranscribe(m)">{{ $t('meeting.transcribe') }}</view>
        </view>

        <!-- 转写中 -->
        <view v-if="m.status === 'TRANSCRIBING'" class="mr-section">
          <view class="mr-progress-row">
            <view class="mr-spinner"></view>
            <text class="mr-hint">{{ $t('meeting.transcribingHint') }}</text>
          </view>
        </view>

        <!-- 失败 -->
        <view v-if="m.status === 'FAILED'" class="mr-section">
          <text class="mr-error">{{ m.error || $t('meeting.transcribeFailed') }}</text>
          <view class="mr-btn secondary" @tap="onTranscribe(m)">{{ $t('meeting.retryTranscribe') }}</view>
        </view>

        <!-- 已转写：说话人 + 转写稿 + 摘要 + 动作 -->
        <template v-if="m.status === 'TRANSCRIBED'">
          <view class="mr-section">
            <text class="mr-section-title">{{ $t('meeting.speakersTitle') }}</text>
            <view class="mr-speakers">
              <view
                v-for="sp in speakerIds(m)"
                :key="sp"
                class="mr-speaker-chip"
                :class="'sp-' + (Number(sp) % 6)"
                @tap="startEditSpeaker(m, sp)"
              >
                <text>{{ speakerName(m, sp) }}</text>
              </view>
            </view>
            <view class="mr-title-edit" v-if="editingSpeakerId !== null">
              <input class="mr-input" v-model="editingSpeakerName" :placeholder="$t('meeting.speakerNamePlaceholder', { n: editingSpeakerId })" />
              <view class="mr-btn secondary small" @tap="editingSpeakerId = null">{{ $t('meeting.cancel') }}</view>
              <view class="mr-btn primary small" @tap="saveSpeakerName(m)">{{ $t('meeting.save') }}</view>
            </view>
          </view>

          <view class="mr-section">
            <view class="mr-actions">
              <view class="mr-btn primary" :class="{ disabled: generatingId === m.id }" @tap="onGenerateMinutes(m)">
                {{ generatingId === m.id ? $t('meeting.sendingToAi') : $t('meeting.generateMinutes') }}
              </view>
              <view class="mr-btn secondary" @tap="onExport(m)">{{ $t('meeting.exportTranscript') }}</view>
            </view>
          </view>

          <!-- 摘要素材（听悟章节/摘要/待办） -->
          <view class="mr-section" v-if="summaryOf(m)">
            <view class="mr-fold-head" @tap="summaryOpenId = summaryOpenId === m.id ? null : m.id">
              <text class="mr-section-title">{{ $t('meeting.autoSummary') }}</text>
              <text class="mr-link">{{ summaryOpenId === m.id ? $t('meeting.collapse') : $t('meeting.expand') }}</text>
            </view>
            <view v-if="summaryOpenId === m.id" class="mr-summary">
              <text class="mr-summary-text" v-if="summaryOf(m).summary">{{ summaryOf(m).summary }}</text>
              <view v-for="(c, i) in (summaryOf(m).chapters || [])" :key="'c' + i" class="mr-summary-block">
                <text class="mr-summary-strong">{{ c.title }}</text>
                <text class="mr-summary-text">{{ c.summary }}</text>
              </view>
              <view v-if="(summaryOf(m).todos || []).length" class="mr-summary-block">
                <text class="mr-summary-strong">{{ $t('meeting.todoLeads') }}</text>
                <text class="mr-summary-text" v-for="(t, i) in summaryOf(m).todos" :key="'t' + i">- {{ t }}</text>
              </view>
            </view>
          </view>

          <!-- 转写稿 -->
          <view class="mr-section">
            <text class="mr-section-title">{{ $t('meeting.transcript') }}</text>
            <view class="mr-transcript">
              <view v-for="(seg, i) in segmentsOf(m)" :key="i" class="mr-seg">
                <view class="mr-seg-head">
                  <text class="mr-seg-speaker" :class="'sp-' + (Number(seg.speaker) % 6)">{{ speakerName(m, seg.speaker) }}</text>
                  <text class="mr-seg-time">{{ formatMs(seg.start) }}</text>
                </view>
                <text class="mr-seg-text">{{ seg.text }}</text>
              </view>
            </view>
          </view>
        </template>
      </view>
    </view>
    <!-- 空态只在列表上方那一处（.mr-list-empty）。#389 加了新的那处但漏删了这里的旧的，
         两块的 v-if 条件相同，没有录音时同一句话会渲染两遍。 -->
  </view>

  <!-- 删除确认 -->
  <view class="mr-dialog-mask" v-if="showDeleteDialog" @tap="showDeleteDialog = false">
    <view class="mr-dialog-content" @tap.stop>
      <view class="mr-dialog-header"><text class="mr-dialog-title">{{ $t('meeting.deleteDialogTitle') }}</text></view>
      <view class="mr-dialog-body">
        <text>{{ $t('meeting.deleteDialogBody') }}</text>
      </view>
      <view class="mr-dialog-footer">
        <view class="mr-dialog-btn cancel" @tap="showDeleteDialog = false">{{ $t('meeting.cancel') }}</view>
        <view class="mr-dialog-btn confirm" @tap="handleDelete">{{ $t('meeting.confirmDelete') }}</view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  getMeetingRecordings, getMeetingRecording, transcribeMeetingRecording,
  updateMeetingRecording, exportMeetingTranscript, getMeetingMinutesPrompt,
  deleteMeetingRecording, getApiBaseUrl,
  getPlatformServices, setPlatformServiceProvider
} from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
import {
  recorderState, startRecording, stopRecording, pauseRecording, resumeRecording,
  isRecordingActive, formatSeconds
} from '@/utils/meetingRecorder.js'
import {
  localTierReady, localAsrProbeResult, refreshLocalAsrReadiness
} from '@/config/platformServices.js'
import { host } from '@/services/host.js'
import AwdSwitch from '@/components/AwdSwitch.vue'

const POLL_INTERVAL_MS = 8000

export default {
  name: 'MeetingRecordingPanel',
  components: { AwdSwitch },
  props: {
    projectId: {
      type: [String, Number],
      required: true
    },
    currentUser: {
      type: Object,
      default: null
    }
  },
  emits: ['generate-minutes'],
  data() {
    return {
      meetings: [],
      configured: null,
      expandedId: null,
      recState: recorderState,
      editingTitleId: null,
      editingTitle: '',
      editingSpeakerId: null,
      editingSpeakerName: '',
      summaryOpenId: null,
      generatingId: null,
      showDeleteDialog: false,
      deletingMeeting: null,
      playingId: null,
      // 转写档位（GET /api/platform-services 的 asr 那一项）。
      // null = 还没读到 / 读不到（server 模式下非 admin 就会读不到），此时整块不渲染——
      // 摆一个「未知」比不摆更让人不安。
      asrProvider: null,
      asrPlatformAvailable: false,
      asrAccountConnected: false,
      tierBusy: false,
      // 本机转写模型的下载状态（组件管理里那套 absent/downloading/installed 的同一条链路，
      // 只是搬到用户真正需要它的位置——他刚点开「录音不出本机」的这一刻）
      modelState: null,
      modelPercent: 0,
      // 桌面壳报的体积说法（'约 1.5GB' 之类）；空 = 还没问到，界面回落到 meeting.modelSizeDefault
      modelSizeHint: '',
      // 用户点过「录音不出本机」但没成——下面那块引导只在这之后出现，
      // 平台档用户不该每次开面板都看见一块「模型没下载」
      localGateOpen: false,
      _modelProgressUnsub: null,
      _player: null,
      _audioUrl: null,
      _pollTimer: null,
      _onStopped: null
    }
  },
  computed: {
    recordingActive() {
      return isRecordingActive()
    },
    recordingHere() {
      return this.recordingActive && String(this.recState.projectId) === String(this.projectId)
    },
    tierKnown() {
      return this.asrProvider !== null
    },
    // 本地档能不能真正用起来。**读的是 platformServices 那个唯一出口**，
    // 与 admin 的档位下拉同源——只在一处接探测的话，用户从另一处照样能切进一个用不了的档。
    localAsrUsable() {
      return localTierReady('asr')
    },
    /**
     * 未就绪时就地摆出来的那块引导。两种情况出现：
     * 用户刚点了开关（`localGateOpen`），或者档位已经是 local 却探不通
     * （模型被删了之类的真故障，不说他会在转写那一刻才知道）。
     *
     * 平台档用户从没想用本地转写，不该每次打开面板都看见一块橙色的「模型没下载」。
     */
    localGate() {
      if (this.localAsrUsable) return null
      if (!this.localGateOpen && this.asrProvider !== 'local') return null
      const r = localAsrProbeResult()
      return r && r.status !== 'READY' ? r : null
    },
    canDownloadModel() {
      // 服务没起时给下载按钮是错的指路：模型下完了照样没人来跑它
      return !!host.model && this.localGate && this.localGate.status === 'MODEL_MISSING'
    },
    modelDownloading() {
      return this.modelState === 'downloading'
    },
    modelSizeText() {
      return this.modelSizeHint || this.$t('meeting.modelSizeDefault')
    },
    tierText() {
      if (this.asrProvider === 'local') return this.$t('meeting.tierLocal')
      if (this.asrProvider === 'byok') return this.$t('meeting.tierByok')
      return this.$t(this.asrAccountConnected ? 'meeting.tierPlatform' : 'meeting.tierNeedsAccount')
    },
    // 「platform 档但还没连账户」不给绿色：那一档现在还转不了，绿色会读成「已就绪」
    tierClass() {
      if (this.asrProvider === 'local') return 'tier-local'
      if (this.asrProvider === 'platform' && this.asrAccountConnected) return 'tier-platform'
      return 'tier-byok'
    },
    tierDesc() {
      if (this.asrProvider === 'local') return this.$t('meeting.tierDescLocal')
      if (this.asrProvider === 'byok') return this.$t('meeting.tierDescByok')
      if (!this.asrPlatformAvailable) return this.$t('meeting.tierDescNoPlatform')
      if (!this.asrAccountConnected) return this.$t('meeting.tierDescNotConnected')
      return this.$t('meeting.tierDescPlatform')
    },
    localSwitchNote() {
      if (this.asrProvider === 'local') return this.$t('meeting.localSwitchNoteOn')
      if (this.localAsrUsable) return this.$t('meeting.localSwitchNoteReady')
      return this.$t('meeting.localSwitchNoteNeedsModel')
    },
    notConfiguredHint() {
      return this.$t(this.asrProvider === 'platform'
        ? 'meeting.notConfiguredPlatform'
        : 'meeting.notConfiguredByok')
    }
  },
  mounted() {
    this.loadMeetings()
    this.loadAsrTier()
    // 装载时就探一次：面板要在**按下录音键之前**说清这段录音会不会出本机（设计 §6.2.1）
    refreshLocalAsrReadiness()
    this.loadModelState()
    this._pollTimer = setInterval(() => this.pollTranscribing(), POLL_INTERVAL_MS)
    // 从顶部胶囊停止录音时刷新列表
    this._onStopped = () => this.loadMeetings()
    try { uni.$on('awd:meeting-recording-stopped', this._onStopped) } catch (e) { /* ignore */ }
    // 模型下载进度直接订阅主进程，与组件管理页同一条事件流：
    // 用户在这里点的下载与在设置页点的是同一个下载，两处显示的进度必须一致
    if (host.model) {
      this._modelProgressUnsub = host.model.onProgress((evt) => {
        if (!evt || evt.id !== 'asr-models') return
        if (evt.phase === 'progress') {
          this.modelState = 'downloading'
          if (typeof evt.percent === 'number') this.modelPercent = evt.percent
        } else {
          // done / error：重新探一次真相，不拿事件本身当结论
          this.loadModelState()
          refreshLocalAsrReadiness()
        }
      })
    }
  },
  beforeUnmount() {
    if (this._pollTimer) clearInterval(this._pollTimer)
    try { if (this._onStopped) uni.$off('awd:meeting-recording-stopped', this._onStopped) } catch (e) { /* ignore */ }
    if (this._modelProgressUnsub) {
      this._modelProgressUnsub()
      this._modelProgressUnsub = null
    }
    this.stopPlay()
  },
  methods: {
    formatSeconds,
    // 转写档位。读不到就整块不渲染（asrProvider 保持 null）：
    // 这个端点是机器级的，server 模式下普通租户本来就无权读，那不是错误。
    async loadAsrTier() {
      try {
        const s = (await getPlatformServices()) || {}
        const asr = (s.services || []).find(x => x.service === 'asr')
        if (!asr) return
        this.asrProvider = asr.provider
        this.asrPlatformAvailable = !!s.platformAvailable
        this.asrAccountConnected = !!s.accountConnected
      } catch (e) {
        console.warn('读取转写档位失败', e)
      }
    },
    /**
     * 「录音不出本机」。
     *
     * 打开时**先就地探一次**，没就绪就不写档位——开关因此回到关闭态，
     * 下面同时展开「下载模型 / 重新检测」的出路（设计 §6.2.1）。
     * 让它留在打开态是本批要避免的那件事：律师录完两小时才发现转不了，
     * 只剩「放弃录音」或「关掉开关传上云」，后者与他打开开关的目的正好相反。
     *
     * 切档后重新拉一次档位与会议列表：isConfigured 的判据按档分，
     * 不刷新的话上面那条「未配置」提示会停在旧档的说法上。
     */
    async onToggleLocalAsr(on) {
      if (this.tierBusy) return
      this.tierBusy = true
      try {
        if (on) {
          await refreshLocalAsrReadiness()
          await this.loadModelState()
          if (!this.localAsrUsable) {
            this.localGateOpen = true // 未就绪：不写档位（开关自然回到关闭态），就地给出路
            return
          }
        }
        await setPlatformServiceProvider('asr', on ? 'local' : 'platform')
        this.localGateOpen = false
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('meeting.switchFailed'), icon: 'none' })
      } finally {
        this.tierBusy = false
        await this.loadAsrTier()
        await this.loadMeetings()
      }
    },
    async onRecheckLocalAsr() {
      await refreshLocalAsrReadiness()
      await this.loadModelState()
      if (this.localAsrUsable) {
        // 刚探到就绪：把用户点开关时想做的事补上，不让他再点一次
        await this.onToggleLocalAsr(true)
      }
    },
    // 模型组件的状态（桌面壳才有；浏览器里 host.model 为空，下载入口整块不出现）
    async loadModelState() {
      if (!host.model) return
      try {
        const res = await host.model.status()
        const comp = ((res && res.components) || []).find(c => c.id === 'asr-models')
        if (!comp) return
        this.modelState = comp.state
        if (comp.sizeHint) this.modelSizeHint = comp.sizeHint
      } catch (e) {
        console.warn('读取本机转写模型状态失败', e)
      }
    },
    async onDownloadAsrModel() {
      if (!host.model) return
      try {
        await host.model.download('asr-models')
        this.modelState = 'downloading'
        this.modelPercent = 0
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.downloadStartFailed'), icon: 'none' })
      }
    },
    async onCancelAsrModel() {
      if (!host.model) return
      try {
        await host.model.cancel('asr-models')
      } finally {
        this.modelState = 'absent'
        this.modelPercent = 0
        await this.loadModelState()
      }
    },
    async loadMeetings() {
      try {
        const res = await getMeetingRecordings(this.projectId)
        this.meetings = (res && res.meetings) || []
        if (res && res.configured !== undefined) this.configured = !!res.configured
      } catch (e) {
        console.error('加载会议列表失败', e)
      }
    },
    // 转写中的会议：定时取详情（后端 poll-on-read 顺手问听悟并推进状态）
    async pollTranscribing() {
      const inflight = this.meetings.filter(m => m.status === 'TRANSCRIBING')
      for (const m of inflight) {
        try {
          const fresh = await getMeetingRecording(m.id)
          if (fresh && fresh.status !== m.status) {
            await this.loadMeetings()
            return
          }
        } catch (e) { /* 下轮再试 */ }
      }
    },

    // ==================== 录音 ====================
    async onStartRecording() {
      try {
        await startRecording(this.projectId)
        if (recorderState.configured !== null) this.configured = recorderState.configured
        await this.loadMeetings()
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('meeting.cannotStartRecording'), icon: 'none' })
      }
    },
    onTogglePause() {
      if (this.recState.status === 'paused') resumeRecording()
      else pauseRecording()
    },
    async onStopRecording() {
      if (this.recState.status === 'stopping') return
      const meeting = await stopRecording()
      await this.loadMeetings()
      if (meeting && meeting.id) this.expandedId = meeting.id
    },

    // ==================== 详情 ====================
    toggleExpand(m) {
      this.expandedId = this.expandedId === m.id ? null : m.id
      if (this.expandedId !== null) this.refreshOne(m.id)
      else this.stopPlay()
    },
    async refreshOne(id) {
      try {
        const fresh = await getMeetingRecording(id)
        const idx = this.meetings.findIndex(x => x.id === id)
        if (idx >= 0 && fresh) this.meetings.splice(idx, 1, fresh)
      } catch (e) { /* ignore */ }
    },
    metaLine(m) {
      const date = (m.createdAt || '').replace('T', ' ').slice(5, 16)
      const dur = m.durationMs ? this.formatMs(m.durationMs) : ''
      return dur ? `${date} · ${dur}` : date
    },
    statusText(status) {
      return {
        RECORDING: this.$t('meeting.statusRecording'),
        RECORDED: this.$t('meeting.statusRecorded'),
        TRANSCRIBING: this.$t('meeting.statusTranscribing'),
        TRANSCRIBED: this.$t('meeting.statusTranscribed'),
        FAILED: this.$t('meeting.statusFailed')
      }[status] || status
    },
    formatMs(ms) {
      return formatSeconds(Math.floor((ms || 0) / 1000))
    },

    // ==================== 转写与纪要 ====================
    async onTranscribe(m) {
      try {
        await transcribeMeetingRecording(m.id)
        await this.loadMeetings()
        this.expandedId = m.id
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.submitTranscribeFailed', { message: (e && e.message) || e }), icon: 'none' })
      }
    },
    async onGenerateMinutes(m) {
      if (this.generatingId) return
      this.generatingId = m.id
      try {
        const res = await getMeetingMinutesPrompt(m.id)
        this.$emit('generate-minutes', { meeting: m, prompt: res.prompt })
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.generateMinutesFailed', { message: (e && e.message) || e }), icon: 'none' })
      } finally {
        this.generatingId = null
      }
    },
    async onExport(m) {
      try {
        const file = await exportMeetingTranscript(m.id)
        const name = (file && file.name) || this.$t('meeting.transcriptFallbackName')
        uni.showToast({ title: this.$t('meeting.exported', { name }), icon: 'none' })
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.exportFailed', { message: (e && e.message) || e }), icon: 'none' })
      }
    },

    // ==================== 标题与说话人 ====================
    startEditTitle(m) {
      this.editingTitleId = m.id
      this.editingTitle = m.title
    },
    async saveTitle(m) {
      const title = (this.editingTitle || '').trim()
      if (!title) return
      try {
        await updateMeetingRecording(m.id, { title })
        this.editingTitleId = null
        await this.loadMeetings()
        this.expandedId = m.id
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.saveFailed', { message: (e && e.message) || e }), icon: 'none' })
      }
    },
    segmentsOf(m) {
      if (!m.transcriptJson) return []
      try {
        const arr = JSON.parse(m.transcriptJson)
        return Array.isArray(arr) ? arr : []
      } catch (e) {
        return []
      }
    },
    summaryOf(m) {
      if (!m.summaryJson) return null
      try {
        return JSON.parse(m.summaryJson)
      } catch (e) {
        return null
      }
    },
    speakerIds(m) {
      const ids = []
      for (const seg of this.segmentsOf(m)) {
        if (!ids.includes(seg.speaker)) ids.push(seg.speaker)
      }
      return ids
    },
    speakerNamesOf(m) {
      if (!m.speakerNames) return {}
      try {
        return JSON.parse(m.speakerNames) || {}
      } catch (e) {
        return {}
      }
    },
    speakerName(m, sp) {
      const names = this.speakerNamesOf(m)
      return names[sp] && names[sp].trim() ? names[sp] : this.$t('meeting.speakerDefaultName', { n: sp })
    },
    startEditSpeaker(m, sp) {
      this.editingSpeakerId = sp
      const names = this.speakerNamesOf(m)
      this.editingSpeakerName = names[sp] || ''
    },
    async saveSpeakerName(m) {
      const sp = this.editingSpeakerId
      if (sp === null) return
      const names = this.speakerNamesOf(m)
      const v = (this.editingSpeakerName || '').trim()
      if (v) names[sp] = v
      else delete names[sp]
      try {
        await updateMeetingRecording(m.id, { speakerNames: names })
        this.editingSpeakerId = null
        await this.refreshOne(m.id)
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.saveFailed', { message: (e && e.message) || e }), icon: 'none' })
      }
    },

    // ==================== 播放 ====================
    // 下载端点要带认证头，window.Audio 设不了 header——fetch 成 blob 再播
    // （模板里不能写 <audio> 标签：uni-h5 会编译成不存在的组件，FeedbackWidget 已踩过）
    async togglePlay(m) {
      if (this.playingId === m.id) {
        this.stopPlay()
        return
      }
      this.stopPlay()
      if (!m.audioFileId) {
        uni.showToast({ title: this.$t('meeting.noAudio'), icon: 'none' })
        return
      }
      try {
        const resp = await fetch(`${getApiBaseUrl()}/api/files/${m.audioFileId}/download`, {
          headers: getAuthHeaders()
        })
        if (!resp.ok) throw new Error('HTTP ' + resp.status)
        const blob = await resp.blob()
        this._audioUrl = URL.createObjectURL(blob)
        this._player = new window.Audio(this._audioUrl)
        this._player.onended = () => { this.playingId = null }
        await this._player.play()
        this.playingId = m.id
      } catch (e) {
        this.stopPlay()
        uni.showToast({ title: this.$t('meeting.playFailed', { message: (e && e.message) || e }), icon: 'none' })
      }
    },
    stopPlay() {
      if (this._player) {
        try { this._player.pause() } catch (e) { /* ignore */ }
      }
      if (this._audioUrl) {
        try { URL.revokeObjectURL(this._audioUrl) } catch (e) { /* ignore */ }
      }
      this._player = null
      this._audioUrl = null
      this.playingId = null
    },

    // ==================== 删除 ====================
    confirmDelete(m) {
      this.deletingMeeting = m
      this.showDeleteDialog = true
    },
    async handleDelete() {
      this.showDeleteDialog = false
      if (!this.deletingMeeting) return
      try {
        await deleteMeetingRecording(this.deletingMeeting.id)
        if (this.expandedId === this.deletingMeeting.id) this.expandedId = null
        await this.loadMeetings()
      } catch (e) {
        uni.showToast({ title: this.$t('meeting.deleteFailed', { message: (e && e.message) || e }), icon: 'none' })
      } finally {
        this.deletingMeeting = null
      }
    }
  }
}
</script>

<style scoped lang="scss">
$mr-primary: #1A5336;
$mr-danger: #E5484D;
$mr-border: #E5E7EB;
$mr-muted: #6B7280;

/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场）。此前这个面板在 260px
   宽的左栏里堆了三张 12px 外边距的卡片，一屏只装得下「提示 + 一个按钮」。 */

/* 分组头：与插件广场同形 */
.mr-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x);
}

.mr-sec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.mr-sec-count {
  font-size: 10px;
  color: var(--awd-panel-text-3);
  background: var(--awd-panel-hover);
  border-radius: 999px;
  padding: 0 6px;
  line-height: 14px;
}

.mr-list-empty {
  padding: 12px var(--awd-panel-pad-x);
  text-align: center;
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-4);
  line-height: 1.6;
}

.mr-config-hint {
  margin: var(--awd-panel-gap) var(--awd-panel-pad-x);
  padding: 6px 8px;
  background: #FFF8E6;
  border: 1px solid #F2E3B3;
  border-radius: var(--awd-panel-radius);
  font-size: var(--awd-panel-fs-meta);
  color: #8A6D1D;
  line-height: 1.55;
}

/* ---- 转写档位（录音开始前就摆出来）---- */
.mr-tier {
  margin: var(--awd-panel-gap) var(--awd-panel-pad-x) 0;
  padding: 8px;
  border: 1px solid $mr-border;
  border-radius: var(--awd-panel-radius);
  background: #FAFBFC;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mr-tier-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mr-tier-switch {
  align-items: flex-start;
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid $mr-border;
}

.mr-tier-switch-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.mr-tier-label {
  font-size: 12px;
  font-weight: 600;
  color: #1F2328;
}

.mr-tier-value {
  flex: none;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 999px;
  color: #4B5563;
  background: #EEF1F0;
}

.mr-tier-value.tier-platform {
  color: #1A5336;
  background: #DEF3E7;
}

.mr-tier-value.tier-local {
  color: #1D4ED8;
  background: #DBEAFE;
}

.mr-tier-desc {
  font-size: 11px;
  color: #6B7280;
  line-height: 1.5;
}

/* 未就绪时的就地出路：下载模型 / 重新检测 */
.mr-tier-gate {
  margin-top: 4px;
  padding: 8px;
  border-radius: var(--awd-panel-radius);
  background: #FFF7ED;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.mr-tier-gate-msg {
  font-size: var(--awd-panel-fs-meta);
  color: #9A3412;
  line-height: 1.5;
}

.mr-tier-gate-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---- 录音区 ----
   这里刻意不跟着整体收紧：「开始录音」是这个面板唯一的主动作，把它压成
   一行 28px 的普通按钮会让面板失去焦点。收的是它周围的边距，不是按钮本身。 */
.mr-record-zone {
  margin: var(--awd-panel-gap) var(--awd-panel-pad-x);
  padding: 10px;
  border: 1px solid $mr-border;
  border-radius: var(--awd-panel-radius);
  background: #FAFBFC;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.mr-record-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 34px;
  border-radius: var(--awd-panel-radius);
  background: $mr-primary;
  cursor: pointer;

  &:hover { background: #17452E; }
}

.mr-record-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #FFFFFF;
  border: 2px solid rgba(255, 255, 255, 0.45);
  box-sizing: content-box;
}

.mr-record-text {
  color: #FFFFFF;
  font-size: 13px;
  font-weight: 600;
}

.mr-record-hint {
  font-size: 10px;
  color: $mr-muted;
  text-align: center;
  line-height: 1.5;
}

.mr-record-error {
  font-size: 11px;
  color: $mr-danger;
}

.mr-recording-live {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.mr-live-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mr-live-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: $mr-danger;
  animation: mr-pulse 1.2s ease-in-out infinite;

  &.paused {
    animation: none;
    background: #B0B4BA;
  }
}

@keyframes mr-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}

.mr-live-time {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: #1F2328;
}

.mr-live-label {
  font-size: 12px;
  color: $mr-muted;
}

.mr-level-track {
  width: 80%;
  height: 4px;
  border-radius: 2px;
  background: #E9ECEF;
  overflow: hidden;
}

.mr-level-bar {
  height: 100%;
  border-radius: 2px;
  background: #5BD197;
  transition: width 0.15s linear;
}

.mr-live-actions {
  display: flex;
  gap: 8px;
}

/* ---- 通用按钮 ---- */
.mr-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px 14px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  user-select: none;

  &.primary {
    background: $mr-primary;
    color: #FFFFFF;
    &:hover { background: #17452E; }
  }

  &.secondary {
    background: #FFFFFF;
    border: 1px solid #D1D5DB;
    color: #374151;
    &:hover { background: #F3F4F6; }
  }

  &.danger {
    background: #FFFFFF;
    border: 1px solid $mr-danger;
    color: $mr-danger;
    &:hover { background: #FDF2F2; }
  }

  &.small {
    padding: 3px 10px;
    font-size: 11px;
  }

  &.disabled {
    opacity: 0.55;
    pointer-events: none;
  }
}

/* ---- 列表 ---- */
.mr-list {
  padding: 0 0 var(--awd-panel-gap-lg);
}

/* 一条录音一行，不再一条一张带描边的卡片：卡片在 260px 宽里只会制造
   「边框套边框」，展开的详情才是需要视觉分区的那一层。 */
.mr-item {
  border-bottom: 1px solid #F0F1F3;
  background: #FFFFFF;
}

.mr-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 5px var(--awd-panel-pad-x);
  cursor: pointer;

  &:hover { background: var(--awd-panel-accent-wash); }
}

.mr-item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.mr-item-title {
  font-size: 12px;
  font-weight: 600;
  color: #1F2328;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mr-item-meta {
  font-size: 10px;
  color: $mr-muted;
}

.mr-status {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 6px;
  line-height: 15px;
  border-radius: 999px;
  background: #F3F4F6;
  color: $mr-muted;

  &.recording { background: #FDF2F2; color: $mr-danger; }
  &.transcribing { background: #EBF5FF; color: #1D6FC2; }
  &.transcribed { background: #EAF7F0; color: $mr-primary; }
  &.failed { background: #FDF2F2; color: $mr-danger; }
}

/* ---- 详情 ---- */
.mr-detail {
  border-top: 1px solid #F0F1F3;
  padding: var(--awd-panel-gap) var(--awd-panel-pad-x);
  background: #FCFCFD;
  display: flex;
  flex-direction: column;
  gap: var(--awd-panel-gap);
}

.mr-detail-tools {
  display: flex;
  gap: 14px;
}

.mr-link {
  font-size: 11px;
  color: #1D6FC2;
  cursor: pointer;

  &.danger { color: $mr-danger; }
  &:hover { text-decoration: underline; }
}

.mr-title-edit {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mr-input {
  flex: 1;
  min-width: 0;
  border: 1px solid #D1D5DB;
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 12px;
  background: #FFFFFF;
}

.mr-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mr-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #374151;
}

.mr-hint {
  font-size: 11px;
  color: $mr-muted;
  line-height: 1.5;
}

.mr-error {
  font-size: 11px;
  color: $mr-danger;
  line-height: 1.5;
}

.mr-progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.mr-spinner {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border: 2px solid #D1D5DB;
  border-top-color: $mr-primary;
  border-radius: 50%;
  animation: mr-spin 0.9s linear infinite;
}

@keyframes mr-spin {
  to { transform: rotate(360deg); }
}

.mr-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* ---- 说话人 ---- */
.mr-speakers {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.mr-speaker-chip {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  cursor: pointer;
  border: 1px solid transparent;

  &:hover { border-color: #B7BCC3; }
}

/* 六色循环：同一说话人在图例与转写稿里颜色一致 */
.sp-0 { background: #EAF7F0; color: #1A5336; }
.sp-1 { background: #EBF5FF; color: #1D6FC2; }
.sp-2 { background: #FFF4E6; color: #B25E09; }
.sp-3 { background: #F5EBFF; color: #7A3FBF; }
.sp-4 { background: #FDF0F4; color: #C13A6B; }
.sp-5 { background: #EDF6F7; color: #0F7A8A; }

/* ---- 摘要 ---- */
.mr-fold-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mr-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mr-summary-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mr-summary-strong {
  font-size: 11px;
  font-weight: 600;
  color: #374151;
}

.mr-summary-text {
  font-size: 11px;
  color: #4B5563;
  line-height: 1.6;
}

/* ---- 转写稿 ---- */
.mr-transcript {
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid #F0F1F3;
  border-radius: 6px;
  padding: 8px;
  background: #FAFBFC;
}

.mr-seg {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mr-seg-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.mr-seg-speaker {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 8px;
}

.mr-seg-time {
  font-size: 10px;
  color: #9CA3AF;
  font-variant-numeric: tabular-nums;
}

.mr-seg-text {
  font-size: 12px;
  color: #1F2328;
  line-height: 1.6;
  word-break: break-word;
}

/* ---- 删除对话框（自带 scoped 副本，awd-* 无集中定义） ---- */
.mr-dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mr-dialog-content {
  width: 300px;
  background: #FFFFFF;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.18);
}

.mr-dialog-header {
  padding: 14px 16px 0;
}

.mr-dialog-title {
  font-size: 14px;
  font-weight: 600;
  color: #1F2328;
}

.mr-dialog-body {
  padding: 10px 16px 16px;
  font-size: 12px;
  color: #4B5563;
  line-height: 1.6;
}

.mr-dialog-footer {
  display: flex;
  border-top: 1px solid #F0F1F3;
}

.mr-dialog-btn {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  font-size: 13px;
  cursor: pointer;

  &.cancel { color: $mr-muted; }
  &.confirm { color: $mr-danger; font-weight: 600; }
  &:hover { background: #F8F9FA; }
}
</style>
