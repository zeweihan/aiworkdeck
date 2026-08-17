<template>
  <!-- Vue 3 多根节点：与 ShareholderMeetingPanel 同构 -->

  <!-- Header -->
  <view class="mr-panel-header">
    <text class="mr-panel-title">会议录音</text>
  </view>

  <!-- 转写档位与就绪状态。**摆在录音开始之前**，不拖到转写那一刻才暴露：
       律师需要在按下录音键之前就知道这段录音会不会出本机（设计 §6.2.1）。 -->
  <view class="mr-tier" v-if="tierKnown">
    <view class="mr-tier-row">
      <text class="mr-tier-label">转写方式</text>
      <text class="mr-tier-value" :class="tierClass">{{ tierText }}</text>
    </view>
    <text class="mr-tier-desc">{{ tierDesc }}</text>
    <!-- 「录音不出本机」。本地转写引擎在后续版本才随包发出，在那之前这个开关是灰的：
         做成能打开、录完两小时才发现转不了的样子，用户只剩「放弃这份录音」或
         「关掉开关传上云」两条路，后者与他打开开关的目的正好相反。 -->
    <view class="mr-tier-row mr-tier-switch">
      <view class="mr-tier-switch-info">
        <text class="mr-tier-label">录音不出本机</text>
        <text class="mr-tier-desc">{{ localSwitchNote }}</text>
      </view>
      <AwdSwitch
        :checked="asrProvider === 'local'"
        :disabled="!localAsrUsable || tierBusy"
        @change="onToggleLocalAsr"
      />
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
        <text class="mr-record-text">开始录音</text>
      </view>
      <text class="mr-record-hint">点击即开始，说话人自动区分，结束后自动转写</text>
    </template>
    <template v-else-if="recordingHere">
      <view class="mr-recording-live">
        <view class="mr-live-row">
          <view class="mr-live-dot" :class="{ paused: recState.status === 'paused' }"></view>
          <text class="mr-live-time">{{ formatSeconds(recState.seconds) }}</text>
          <text class="mr-live-label">{{ recState.status === 'paused' ? '已暂停' : '录音中' }}</text>
        </view>
        <view class="mr-level-track">
          <view class="mr-level-bar" :style="{ width: Math.round(recState.level * 100) + '%' }"></view>
        </view>
        <view class="mr-live-actions">
          <view class="mr-btn secondary" @tap="onTogglePause">
            {{ recState.status === 'paused' ? '继续' : '暂停' }}
          </view>
          <view class="mr-btn danger" :class="{ disabled: recState.status === 'stopping' }" @tap="onStopRecording">
            {{ recState.status === 'stopping' ? '保存中...' : '结束录音' }}
          </view>
        </view>
        <text class="mr-record-hint">切到其他页面录音不会中断，可从顶部胶囊随时停止</text>
        <text class="mr-record-error" v-if="recState.error">{{ recState.error }}</text>
      </view>
    </template>
    <template v-else>
      <text class="mr-record-hint">另一个项目正在录音中，请先在顶部胶囊停止</text>
    </template>
  </view>

  <!-- 会议列表 -->
  <view class="mr-list">
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
          <input class="mr-input" v-model="editingTitle" placeholder="会议标题" />
          <view class="mr-btn secondary small" @tap="editingTitleId = null">取消</view>
          <view class="mr-btn primary small" @tap="saveTitle(m)">保存</view>
        </view>
        <view class="mr-detail-tools" v-else>
          <text class="mr-link" @tap="startEditTitle(m)">改标题</text>
          <text class="mr-link" @tap="togglePlay(m)">{{ playingId === m.id ? '停止播放' : '播放录音' }}</text>
          <text class="mr-link danger" @tap="confirmDelete(m)">删除</text>
        </view>

        <!-- 已录音未转写 -->
        <view v-if="m.status === 'RECORDED'" class="mr-section">
          <text class="mr-hint" v-if="configured === false">配置转写凭证后可转文字与生成纪要。</text>
          <view v-else class="mr-btn primary" @tap="onTranscribe(m)">开始转写</view>
        </view>

        <!-- 转写中 -->
        <view v-if="m.status === 'TRANSCRIBING'" class="mr-section">
          <view class="mr-progress-row">
            <view class="mr-spinner"></view>
            <text class="mr-hint">转写与说话人分离进行中，通常几分钟内完成，可离开此页</text>
          </view>
        </view>

        <!-- 失败 -->
        <view v-if="m.status === 'FAILED'" class="mr-section">
          <text class="mr-error">{{ m.error || '转写失败' }}</text>
          <view class="mr-btn secondary" @tap="onTranscribe(m)">重试转写</view>
        </view>

        <!-- 已转写：说话人 + 转写稿 + 摘要 + 动作 -->
        <template v-if="m.status === 'TRANSCRIBED'">
          <view class="mr-section">
            <text class="mr-section-title">说话人（点击改名）</text>
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
              <input class="mr-input" v-model="editingSpeakerName" :placeholder="'说话人' + editingSpeakerId + ' 的名字'" />
              <view class="mr-btn secondary small" @tap="editingSpeakerId = null">取消</view>
              <view class="mr-btn primary small" @tap="saveSpeakerName(m)">保存</view>
            </view>
          </view>

          <view class="mr-section">
            <view class="mr-actions">
              <view class="mr-btn primary" :class="{ disabled: generatingId === m.id }" @tap="onGenerateMinutes(m)">
                {{ generatingId === m.id ? '正在交给 AI...' : '生成会议纪要' }}
              </view>
              <view class="mr-btn secondary" @tap="onExport(m)">导出转写稿</view>
            </view>
          </view>

          <!-- 摘要素材（听悟章节/摘要/待办） -->
          <view class="mr-section" v-if="summaryOf(m)">
            <view class="mr-fold-head" @tap="summaryOpenId = summaryOpenId === m.id ? null : m.id">
              <text class="mr-section-title">机器速览</text>
              <text class="mr-link">{{ summaryOpenId === m.id ? '收起' : '展开' }}</text>
            </view>
            <view v-if="summaryOpenId === m.id" class="mr-summary">
              <text class="mr-summary-text" v-if="summaryOf(m).summary">{{ summaryOf(m).summary }}</text>
              <view v-for="(c, i) in (summaryOf(m).chapters || [])" :key="'c' + i" class="mr-summary-block">
                <text class="mr-summary-strong">{{ c.title }}</text>
                <text class="mr-summary-text">{{ c.summary }}</text>
              </view>
              <view v-if="(summaryOf(m).todos || []).length" class="mr-summary-block">
                <text class="mr-summary-strong">待办线索</text>
                <text class="mr-summary-text" v-for="(t, i) in summaryOf(m).todos" :key="'t' + i">- {{ t }}</text>
              </view>
            </view>
          </view>

          <!-- 转写稿 -->
          <view class="mr-section">
            <text class="mr-section-title">转写稿</text>
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

    <view v-if="meetings.length === 0" class="mr-empty">
      <text>暂无会议录音</text>
    </view>
  </view>

  <!-- 删除确认 -->
  <view class="mr-dialog-mask" v-if="showDeleteDialog" @tap="showDeleteDialog = false">
    <view class="mr-dialog-content" @tap.stop>
      <view class="mr-dialog-header"><text class="mr-dialog-title">删除会议</text></view>
      <view class="mr-dialog-body">
        <text>将删除该会议的转写记录与录音文件，不可恢复。确认删除？</text>
      </view>
      <view class="mr-dialog-footer">
        <view class="mr-dialog-btn cancel" @tap="showDeleteDialog = false">取消</view>
        <view class="mr-dialog-btn confirm" @tap="handleDelete">确认删除</view>
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
import { localTierReady } from '@/config/platformServices.js'
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
    // 本地转写引擎随后续版本发出；在那之前开关一律灰着（见模板处的注释）
    localAsrUsable() {
      return localTierReady('asr')
    },
    tierText() {
      if (this.asrProvider === 'local') return '本地转写'
      if (this.asrProvider === 'byok') return '自备 Key'
      return this.asrAccountConnected ? '平台代采' : '需要连接账户'
    },
    // 「platform 档但还没连账户」不给绿色：那一档现在还转不了，绿色会读成「已就绪」
    tierClass() {
      if (this.asrProvider === 'local') return 'tier-local'
      if (this.asrProvider === 'platform' && this.asrAccountConnected) return 'tier-platform'
      return 'tier-byok'
    },
    tierDesc() {
      if (this.asrProvider === 'local') return '录音与转写都在本机完成，音频不出本机。'
      if (this.asrProvider === 'byok') return '用你自己的阿里云听悟账号转写，音频经你自己的 OSS 中转。'
      if (!this.asrPlatformAvailable) return '本机形态使用自备 Key，在「系统管理 - 平台服务」里填听悟凭证。'
      if (!this.asrAccountConnected) return '连接官网账户后即可直接转写，不用自己开通听悟。'
      return '由 AI Workdeck 代为转写，按时长折算 Credits 从账户余额扣。音频经我们的对象存储中转，转写完成即删除，另有 24 小时兜底清理。'
    },
    localSwitchNote() {
      if (this.localAsrUsable) return '打开后音频不上传，转写在本机完成（本地档没有说话人分离）。'
      return '本地转写引擎将在后续版本提供，届时打开即可让录音完全不出本机。'
    },
    notConfiguredHint() {
      if (this.asrProvider === 'platform') {
        return '转写暂不可用：录音会保存到项目文件，但不能转文字。到「系统管理 - 账户与用量」连接官网账户即可开通。'
      }
      return '未配置转写服务：录音会保存到项目文件，但不能转文字。管理员可在「系统管理 - 平台服务 - 会议录音转写」里填阿里云听悟凭证，或改用平台代采。'
    }
  },
  mounted() {
    this.loadMeetings()
    this.loadAsrTier()
    this._pollTimer = setInterval(() => this.pollTranscribing(), POLL_INTERVAL_MS)
    // 从顶部胶囊停止录音时刷新列表
    this._onStopped = () => this.loadMeetings()
    try { uni.$on('awd:meeting-recording-stopped', this._onStopped) } catch (e) { /* ignore */ }
  },
  beforeUnmount() {
    if (this._pollTimer) clearInterval(this._pollTimer)
    try { if (this._onStopped) uni.$off('awd:meeting-recording-stopped', this._onStopped) } catch (e) { /* ignore */ }
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
    // 「录音不出本机」。切档后重新拉一次档位与会议列表：
    // isConfigured 的判据按档分（平台档看有没有连账户，自备 Key 档看那五个凭证），
    // 不刷新的话上面那条「未配置」提示会停在旧档的说法上。
    async onToggleLocalAsr(on) {
      if (this.tierBusy) return
      this.tierBusy = true
      try {
        await setPlatformServiceProvider('asr', on ? 'local' : 'platform')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '切换失败，稍后重试', icon: 'none' })
      } finally {
        this.tierBusy = false
        await this.loadAsrTier()
        await this.loadMeetings()
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
        uni.showToast({ title: (e && e.message) || '无法开始录音', icon: 'none' })
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
        RECORDING: '录音中',
        RECORDED: '未转写',
        TRANSCRIBING: '转写中',
        TRANSCRIBED: '已转写',
        FAILED: '转写失败'
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
        uni.showToast({ title: '提交转写失败：' + ((e && e.message) || e), icon: 'none' })
      }
    },
    async onGenerateMinutes(m) {
      if (this.generatingId) return
      this.generatingId = m.id
      try {
        const res = await getMeetingMinutesPrompt(m.id)
        this.$emit('generate-minutes', { meeting: m, prompt: res.prompt })
      } catch (e) {
        uni.showToast({ title: '生成纪要失败：' + ((e && e.message) || e), icon: 'none' })
      } finally {
        this.generatingId = null
      }
    },
    async onExport(m) {
      try {
        const file = await exportMeetingTranscript(m.id)
        uni.showToast({ title: `已导出：${(file && file.name) || '转写稿'}（见「会议录音」文件夹）`, icon: 'none' })
      } catch (e) {
        uni.showToast({ title: '导出失败：' + ((e && e.message) || e), icon: 'none' })
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
        uni.showToast({ title: '保存失败：' + ((e && e.message) || e), icon: 'none' })
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
      return names[sp] && names[sp].trim() ? names[sp] : '说话人' + sp
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
        uni.showToast({ title: '保存失败：' + ((e && e.message) || e), icon: 'none' })
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
        uni.showToast({ title: '没有可播放的录音', icon: 'none' })
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
        uni.showToast({ title: '播放失败：' + ((e && e.message) || e), icon: 'none' })
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
        uni.showToast({ title: '删除失败：' + ((e && e.message) || e), icon: 'none' })
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

.mr-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 6px;
}

.mr-panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #1F2328;
}

.mr-config-hint {
  margin: 0 12px 8px;
  padding: 8px 10px;
  background: #FFF8E6;
  border: 1px solid #F2E3B3;
  border-radius: 6px;
  font-size: 12px;
  color: #8A6D1D;
  line-height: 1.5;
}

/* ---- 转写档位（录音开始前就摆出来）---- */
.mr-tier {
  margin: 0 12px 8px;
  padding: 10px;
  border: 1px solid $mr-border;
  border-radius: 8px;
  background: #FAFBFC;
  display: flex;
  flex-direction: column;
  gap: 6px;
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

/* ---- 录音区 ---- */
.mr-record-zone {
  margin: 0 12px 10px;
  padding: 14px 12px;
  border: 1px solid $mr-border;
  border-radius: 8px;
  background: #FAFBFC;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.mr-record-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 22px;
  border-radius: 24px;
  background: $mr-primary;
  cursor: pointer;

  &:hover { background: #17452E; }
}

.mr-record-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #FFFFFF;
  border: 3px solid rgba(255, 255, 255, 0.45);
  box-sizing: content-box;
}

.mr-record-text {
  color: #FFFFFF;
  font-size: 14px;
  font-weight: 600;
}

.mr-record-hint {
  font-size: 11px;
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
  padding: 0 12px 16px;
}

.mr-item {
  border: 1px solid $mr-border;
  border-radius: 8px;
  margin-bottom: 8px;
  background: #FFFFFF;
  overflow: hidden;
}

.mr-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 10px;
  cursor: pointer;

  &:hover { background: #F8F9FA; }
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
  font-size: 11px;
  color: $mr-muted;
}

.mr-status {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
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
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
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

.mr-empty {
  padding: 20px 0;
  text-align: center;
  font-size: 12px;
  color: #9CA3AF;
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
