<template>
  <scroll-view scroll-y class="easy-voice-pane">
    <!-- Text Input Section -->
    <view class="section">
      <view class="section-header">
        <text class="section-title">{{ $t('panels.evTextContentTitle') }}</text>
        <view class="section-spacer"></view>
        <view class="section-actions">
           <view class="mini-btn" @tap="importFromDoc" :title="$t('panels.evImportTitle')">
             <text>{{ $t('panels.evImport') }}</text>
           </view>
           <view class="mini-btn icon" @tap="text = ''" :title="$t('panels.evClearTitle')">
             <svg class="btn-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
           </view>
        </view>
      </view>
      <textarea
        class="voice-textarea"
        v-model="text"
        :placeholder="$t('panels.evTextPlaceholder')"
        maxlength="-1"
      />
    </view>

    <!-- Settings Section -->
    <view class="section">
      <view class="section-header">
        <text class="section-title">{{ $t('panels.evVoiceSettingsTitle') }}</text>
      </view>

      <!-- 引擎未就绪时把话说在合成之前：音色列表拿不到就是引擎没起来，
           这时候让用户填完一整段文字再报错是没道理的。
           **不止是说，还要给出路**——照会议录音那条 ASR gate 的形制（#385）：
           模型没下就在这里下，下完就地把服务拉起来，不用去「组件管理」绕一圈。 -->
      <view class="ev-gate" v-if="engineGateVisible">
        <text class="ev-gate-msg">{{ gateMessage }}</text>
        <view class="ev-gate-actions">
          <template v-if="modelDownloading">
            <text class="ev-gate-hint">{{ $t('panels.evModelDownloading', { percent: modelPercent }) }}</text>
            <view class="ev-gate-btn secondary" @tap="onCancelModel">{{ $t('panels.evCancelDownload') }}</view>
          </template>
          <template v-else>
            <view class="ev-gate-btn primary" v-if="canDownloadModel" @tap="onDownloadModel">
              {{ $t('panels.evDownloadModel', { size: modelSizeHint }) }}
            </view>
            <view class="ev-gate-btn secondary" :class="{ disabled: rechecking }" @tap="onRecheck">
              {{ rechecking ? $t('panels.evRechecking') : $t('panels.evRecheck') }}
            </view>
          </template>
        </view>
      </view>

      <!-- Voice Selection (Custom Dropdown) -->
      <view class="form-item relative">
        <text class="label">{{ $t('panels.evSpeakerLabel') }}</text>

        <view class="voice-select-trigger" @tap="toggleVoiceDropdown">
          <view class="selected-text">
             <text v-if="selectedVoiceLabel">{{ selectedVoiceLabel }}</text>
             <text v-else class="placeholder">{{ $t('panels.evSelectVoicePlaceholder') }}</text>
          </view>
          <text class="select-arrow">▼</text>
        </view>

        <!-- Dropdown Drawer -->
        <view v-if="showVoiceDropdown" class="voice-dropdown" @tap.stop>
           <view class="voice-search-box">
              <input
                v-model="voiceSearch"
                class="voice-search-input"
                :placeholder="$t('panels.evSearchVoicePlaceholder')"
                :focus="true"
              />
           </view>
           <scroll-view scroll-y class="voice-list-scroll">
              <view
                 v-for="voice in filteredVoices"
                 :key="voice.voiceId"
                 class="voice-option"
                 :class="{ active: selectedVoiceId === voice.voiceId }"
                 @tap="selectVoice(voice)"
              >
                 <view class="voice-info-row">
                    <text class="voice-name-text">{{ voice.name }}</text>
                    <text class="voice-gender-tag">{{ voice.gender }}</text>
                 </view>
                 <text class="voice-locale-text">{{ voice.locale }}</text>
              </view>
              <view v-if="filteredVoices.length === 0" class="empty-tip">
                 {{ $t('panels.evNoMatchVoice') }}
              </view>
           </scroll-view>
        </view>
        <view v-if="showVoiceDropdown" class="dropdown-mask" @tap="showVoiceDropdown = false"></view>
      </view>

      <!-- 语速。
           这里以前有三个滑杆（语速/语调/音量），三个都是死的：前端把 '+0%' / '+0Hz'
           写死在 payload 里、根本没读滑杆的值；而后端三档里 ElevenLabs 与平台代采
           压根没有这几个参数，只有本地 Kokoro 吃一个倍率制的 speed——'+0%' 传过去
           解析失败恒回落 1.0。语调与音量已删（没有任何一档支持，留着就是骗人），
           语速改成倍率并真正下发。 -->
      <view class="form-item">
        <view class="slider-header">
           <text class="label">{{ $t('panels.evRateLabel') }}</text>
           <text class="value-text">{{ speedText }}</text>
        </view>
        <slider
          :value="rate"
          @change="onRateChange"
          min="50"
          max="150"
          step="5"
          block-size="12"
          activeColor="var(--awd-accent)"
          backgroundColor="var(--awd-border)"
          block-color="var(--awd-accent)"
        />
      </view>
    </view>

    <!-- Generate Action -->
    <view class="action-area">
      <button
        class="workdeck-btn workdeck-btn-primary full-width"
        @tap="handleGenerate"
        :disabled="generating || !text"
        :loading="generating"
      >
        {{ generating ? $t('panels.evGenerating') : $t('panels.evGenerate') }}
      </button>
    </view>

    <!-- Result Area -->
    <view v-if="audioUrl" class="section result-area">
      <view class="result-header">
         <text class="result-title">{{ $t('panels.evResultTitle') }}</text>
         <text class="download-link" @tap="downloadAudio">{{ $t('panels.evDownload') }}</text>
      </view>

      <!-- Custom Audio Player -->
      <view class="custom-player" :class="{ playing: isPlaying }">
          <view class="play-btn" @tap="togglePlay">
              <text class="play-icon">{{ isPlaying ? '⏸' : '▶' }}</text>
          </view>
          <view class="player-info">
              <text class="player-status">{{ isPlaying ? $t('panels.evPlaying') : $t('panels.evClickToPlay') }}</text>
          </view>
      </view>
    </view>
    
  </scroll-view>
</template>

<script>
import { getTtsVoices, generateTtsAudio, promptFeatureNotConfigured } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'
import { host } from '@/services/host.js'

// 本机语音引擎的模型组件 id（desktop/main/services/model-manager.js）。
// kokoro-service 的 descriptor 把 enabled 门在这个组件上——模型没下，服务根本不启动，
// 于是 /api/tts/voices 恒返回空数组。
const TTS_MODEL_ID = 'kokoro-models'

export default {
  name: 'EasyVoicePane',
  data() {
    return {
      text: '',
      voices: [],
      selectedVoiceId: '',  // 本机 Kokoro 的 voiceId
      selectedVoiceName: '',  // Display name
      voiceSearch: '',
      showVoiceDropdown: false,
      voicesLoaded: false,
      // 本机语音组件的状态：absent / downloading / installed（读自 host.model.status）
      modelState: '',
      modelPercent: 0,
      modelSizeHint: '300 MB',
      rechecking: false,
      // 语速以「百分之几倍」存（100 = 原速），下发时除以 100 变成 Kokoro 的 speed
      rate: 100,
      generating: false,
      audioUrl: '',
      audioInstance: null,
      isPlaying: false,
      // Karaoke highlighting
      sentences: [],
      currentSentenceIndex: -1,
      sentenceDurations: [],
      audioDuration: 0,
      _unmounted: false // 卸载判据：generateTtsAudio 的响应可能在切走面板之后才回来
    }
  },
  computed: {
    ICONS() { return ICONS },
    speedText() { return (this.rate / 100).toFixed(2).replace(/0$/, '') + 'x' },
    /** 拿不到音色 = 引擎没就绪。问过之后才判，免得加载中先闪一下红字。 */
    engineGateVisible() {
      return this.voicesLoaded && this.voices.length === 0
    },
    /** 浏览器态没有 host.model，下载入口整块不出现（那儿也没有本机引擎可言）。 */
    canDownloadModel() {
      return !!host.model && this.modelState !== 'installed'
    },
    modelDownloading() {
      return this.modelState === 'downloading'
    },
    /**
     * 「模型没下」与「模型下好了但服务没起」是两回事，下一步完全不同
     *（前者下 300MB，后者点一下重新检测就够），不能合并成一句「不可用」让用户猜。
     */
    gateMessage() {
      if (!host.model) return this.$t('panels.evNoVoicesNoticeWeb')
      if (this.modelState === 'installed') return this.$t('panels.evEngineNotRunning')
      return this.$t('panels.evModelMissing')
    },
    selectedVoiceLabel() {
        const v = this.voices.find(v => v.voiceId === this.selectedVoiceId)
        return v ? `${v.name} (${v.gender || 'voice'})` : ''
    },
    filteredVoices() {
       if (!this.voiceSearch) return this.voices
       const q = this.voiceSearch.toLowerCase()
       return this.voices.filter(v => 
          v.name.toLowerCase().includes(q) || 
          v.locale.toLowerCase().includes(q) ||
          v.gender.toLowerCase().includes(q)
       )
    }
  },
  mounted() {
    this.fetchVoices()
    this.loadModelState()
    // 下载进度直接订阅主进程，与「组件管理」页同一条事件流：
    // 用户在这里点的下载与在设置页点的是同一个下载，两处进度必须一致
    if (host.model) {
      this._modelProgressUnsub = host.model.onProgress((evt) => {
        if (!evt || evt.id !== TTS_MODEL_ID) return
        if (evt.phase === 'progress') {
          this.modelState = 'downloading'
          if (typeof evt.percent === 'number') this.modelPercent = evt.percent
        } else {
          // done / error：重新探一次真相，不拿事件本身当结论
          this.loadModelState()
          if (evt.phase === 'done') this.startEngineThenRefresh()
        }
      })
    }
  },
  beforeUnmount() {
    this._unmounted = true
    this.stopAudio()
    // 卸载时手上可能还攥着一个已经生成好、但还没播的 blob URL，必须一并释放，
    // 否则每次"生成完切走面板"都会泄漏一个 blob。
    if (this.audioUrl) {
      URL.revokeObjectURL(this.audioUrl)
      this.audioUrl = ''
    }
    if (this._modelProgressUnsub) {
      this._modelProgressUnsub()
      this._modelProgressUnsub = null
    }
  },
  methods: {
    // ==================== Karaoke Highlighting ====================
    /**
     * Split text into sentences by Chinese/English punctuation
     */
    splitTextToSentences(text) {
      if (!text) return []
      // Split by common sentence-ending punctuation
      const raw = text.split(/[。！？；.!?;]+/)
      // Filter empty and trim whitespace
      return raw.map(s => s.trim()).filter(s => s.length > 0)
    },

    /**
     * Estimate duration for each sentence based on character ratio
     */
    estimateSentenceDurations() {
      if (!this.sentences.length || !this.audioDuration) return []
      const totalChars = this.sentences.reduce((sum, s) => sum + s.length, 0)
      if (totalChars === 0) return []
      return this.sentences.map(s => (s.length / totalChars) * this.audioDuration * 1000) // ms
    },

    /**
     * Determine current sentence index based on playback time
     */
    getCurrentSentenceIndex(currentTimeMs) {
      let accumulated = 0
      for (let i = 0; i < this.sentenceDurations.length; i++) {
        accumulated += this.sentenceDurations[i]
        if (currentTimeMs < accumulated) {
          return i
        }
      }
      return this.sentences.length - 1
    },

    /**
     * Handle timeupdate event for karaoke sync
     */
    onAudioTimeUpdate() {
      if (!this.audioInstance || !this.sentences.length) return
      const currentTimeMs = this.audioInstance.currentTime * 1000
      const newIndex = this.getCurrentSentenceIndex(currentTimeMs)
      
      if (newIndex !== this.currentSentenceIndex) {
        this.currentSentenceIndex = newIndex
        const sentence = this.sentences[newIndex]
        if (sentence) {
          console.log('[EasyVoice] Highlighting sentence:', newIndex, sentence.substring(0, 30) + '...')
          this.$emit('highlight-sentence', sentence)
        }
      }
    },

    /**
     * Handle audio ended event
     */
    onAudioEnded() {
      this.isPlaying = false
      this.currentSentenceIndex = -1
      this.$emit('clear-highlight')
    },

    // ==================== Audio Control ====================
    stopAudio() {
        if (this.audioInstance) {
            this.audioInstance.pause()
            this.audioInstance = null
            this.isPlaying = false
            this.currentSentenceIndex = -1
            this.$emit('clear-highlight')
        }
    },
    togglePlay() {
        if (!this.audioUrl) return
        
        if (!this.audioInstance) {
            this.audioInstance = new Audio(this.audioUrl)
            
            // Get audio duration for sentence timing estimation
            this.audioInstance.onloadedmetadata = () => {
                this.audioDuration = this.audioInstance.duration
                console.log('[EasyVoice] Audio duration:', this.audioDuration, 's')
                this.sentenceDurations = this.estimateSentenceDurations()
                console.log('[EasyVoice] Sentence durations:', this.sentenceDurations)
            }
            
            // Karaoke sync via timeupdate
            this.audioInstance.ontimeupdate = () => {
                this.onAudioTimeUpdate()
            }
            
            this.audioInstance.onended = () => {
                this.onAudioEnded()
            }
            this.audioInstance.onpause = () => {
                this.isPlaying = false
            }
            this.audioInstance.onplay = () => {
                this.isPlaying = true
            }
             this.audioInstance.onerror = (e) => {
                console.error('Audio playback error', e)
                this.isPlaying = false
                uni.showToast({ title: this.$t('panels.evPlayFailed'), icon: 'none' })
            }
        }

        if (this.isPlaying) {
            this.audioInstance.pause()
        } else {
            this.audioInstance.play().catch(e => {
                console.error('Play failed', e)
                uni.showToast({ title: this.$t('panels.evCannotPlay'), icon: 'none' })
            })
        }
    },
    toggleVoiceDropdown() {
       this.showVoiceDropdown = !this.showVoiceDropdown
       if (this.showVoiceDropdown) {
          this.voiceSearch = ''
       }
    },
    selectVoice(voice) {
       this.selectedVoiceId = voice.voiceId
       this.selectedVoiceName = voice.name
       this.showVoiceDropdown = false
    },
    async fetchVoices() {
      try {
        console.log('[EasyVoicePane] Fetching voices...')
        const res = await getTtsVoices()
        console.log('[EasyVoicePane] Voices response:', res)
        
        if (res && Array.isArray(res)) {
            this.voices = res
            // Default to first available voice
            const defaultVoice = this.voices[0]
            if (defaultVoice) {
                this.selectedVoiceId = defaultVoice.voiceId
                this.selectedVoiceName = defaultVoice.name
            }
        } else {
            console.warn('[EasyVoicePane] Invalid voices response format', res)
        }
      } catch (e) {
        console.error('[EasyVoicePane] Failed to load voices', e)
        uni.showToast({ title: this.$t('panels.evLoadVoicesFailed'), icon: 'none' })
      } finally {
        // 与「还没问过」区分开：空列表 = 引擎没起来，要在合成之前就说出来
        this.voicesLoaded = true
      }
    },
    onRateChange(e) {
      this.rate = e.detail.value
    },

    // ==================== 本机语音组件 ====================
    async loadModelState() {
      if (!host.model) return
      try {
        const res = await host.model.status()
        const comp = ((res && res.components) || []).find(c => c.id === TTS_MODEL_ID)
        if (!comp) return
        this.modelState = comp.state
        if (comp.sizeHint) this.modelSizeHint = comp.sizeHint
      } catch (e) {
        console.warn('[EasyVoicePane] 读取语音模型状态失败', e)
      }
    },
    async onDownloadModel() {
      if (!host.model) return
      try {
        await host.model.download(TTS_MODEL_ID)
        this.modelState = 'downloading'
        this.modelPercent = 0
      } catch (e) {
        uni.showToast({ title: this.$t('panels.evDownloadStartFailed'), icon: 'none' })
      }
    },
    async onCancelModel() {
      if (!host.model) return
      try {
        await host.model.cancel(TTS_MODEL_ID)
      } finally {
        this.modelState = 'absent'
        this.modelPercent = 0
        await this.loadModelState()
      }
    },
    /**
     * kokoro-service 的 descriptor 把 enabled 门在模型上，所以**模型下完之后
     * 还要显式拉一次服务**——否则要等到下次启动应用才会起来，用户会以为下载没用。
     * service-manager 的 start() 每次都重新求值 enabled，此刻求值必然为真。
     */
    async startEngineThenRefresh() {
      try {
        if (host.services && host.services.ensure) await host.services.ensure('kokoro-service')
      } catch (e) {
        console.warn('[EasyVoicePane] 拉起本机语音服务失败', e)
      }
      await this.fetchVoices()
    },
    async onRecheck() {
      if (this.rechecking) return
      this.rechecking = true
      try {
        await this.loadModelState()
        await this.startEngineThenRefresh()
        if (this.voices.length) {
          uni.showToast({ title: this.$t('panels.evEngineReady'), icon: 'success' })
        }
      } finally {
        this.rechecking = false
      }
    },
    async importFromDoc() {
        const callback = (content) => {
            if (content) {
                this.text = content;
                // sentences 是「当前这段音频的时间轴」，不是「文本框里现在有什么」，
                // 所以导入时不能顺手把它换掉：旧音频还在播的话，ontimeupdate 会按旧音频
                // 的时长算出下标、去新文档的句子数组里取字符串 emit 出去——而导入路径
                // （project-overview 的 handleEasyVoiceDocRequest）随后就 openFile 打开了
                // 新文档，那些句子在新文档里真能被 find 到，选区于是跟着旧音频乱跳。
                // handleGenerate 在每次合成前都会按当前正文重算，这里不需要提前拆句。
                uni.showToast({ title: this.$t('panels.evImportedDocSuccess'), icon: 'success' })
            } else {
                 uni.showToast({ title: this.$t('panels.evCannotGetDocContent'), icon: 'none' })
            }
        };
        // Keep global emit for potential other listeners
        uni.$emit('easyvoice-request-doc-text', callback);
        this.$emit('request-doc-text', callback);
    },
    async handleGenerate() {
      if (!this.text) return
      this.generating = true
      this.stopAudio() 
      
      // Split text into sentences before generating
      this.sentences = this.splitTextToSentences(this.text)
      console.log('[EasyVoice] Prepared', this.sentences.length, 'sentences for karaoke')
      
      try {
        const payload = {
            text: this.text,
            voice: this.selectedVoiceId,
            // 倍率制字符串，后端 TtsService.parseSpeed 认这个格式（"1"/"1.2"/"1.2x"）。
            // 此前这里写死 '+0%'，解析必然失败、恒回落 1.0——滑杆等于没接。
            // #386 已把它接上（当时滑杆还是 -50..+50，换算成 1 + rate/100）；
            // 这里进一步把滑杆本身改成倍率制（50..150 = 0.5x..1.5x），
            // 界面上直接显示 "1.3x"，不再让用户在百分比与倍率之间脑内换算。
            // pitch / volume 不再随请求发出：本机引擎不支持、面板上那两个滑杆也已删，
            // 发一个恒定的假值只会让人以为它有用（DTO 侧的 setter 仍在，存量客户端不受影响）。
            rate: String(this.rate / 100)
        }
        
        console.log('[EasyVoicePane] Generating with payload:', payload)
        const audioBuffer = await generateTtsAudio(payload)
        console.log('[EasyVoicePane] Generated audio buffer size:', audioBuffer.byteLength)

        // 卸载判据：await 期间用户可能已经切走了这个面板（切左栏面板/切到会议录音
        // tab 都会销毁组件）。此时没有任何播放控件能停下接下来 togglePlay() 会起播
        // 的音频，必须在这里拦住，不落地播放。
        if (this._unmounted) return

        const blob = new Blob([audioBuffer], { type: 'audio/mpeg' })
        if (this.audioUrl) {
            URL.revokeObjectURL(this.audioUrl)
        }
        this.audioUrl = URL.createObjectURL(blob)

        this.$nextTick(() => {
             if (this._unmounted) return
             this.togglePlay()
        })

      } catch (e) {
        if (this._unmounted) return
        console.error('[EasyVoicePane] Generation failed', e)
        if (e && e.featureNotConfigured) {
          // TTS 未配置：引导去设置而非报"生成失败"（#18 T7）
          promptFeatureNotConfigured(e)
        } else {
          uni.showToast({ title: this.$t('panels.evGenerateFailed'), icon: 'none' })
        }
      } finally {
        this.generating = false
      }
    },
    downloadAudio() {
        if (!this.audioUrl) return
        const a = document.createElement('a')
        a.href = this.audioUrl
        a.download = `voice_${Date.now()}.mp3`
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
    }
  }
}
</script>

<style scoped>
/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场）。
   此前这个面板是 16px 页边距 + 24px 段间距 + 白卡片套白底，260px 宽的左栏里
   一屏只装得下文本框和半个音色选择器。 */
.easy-voice-pane {
  height: 100%;
  background-color: var(--awd-surface);
  box-sizing: border-box;
}

.section {
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
  background: var(--awd-surface);
}

/* 分组头：与插件广场同形（26px / 11px-700） */
.section-header {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--awd-panel-sec-h);
}

.section-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.section-spacer { flex: 1; }

.section-actions {
    display: flex;
    gap: 4px;
}

.mini-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 18px;
    padding: 0 6px;
    background: var(--awd-surface);
    border: 1px solid var(--awd-panel-border);
    border-radius: 4px;
    font-size: 10px;
    cursor: pointer;
    color: var(--awd-panel-text-2);
}
.mini-btn.icon { width: 20px; padding: 0; }
.mini-btn:hover {
    background: var(--awd-panel-hover);
    border-color: var(--awd-border-strong);
}
.btn-glyph { width: 11px; height: 11px; }

/* 引擎未就绪时的就地出路（形制与会议录音的 .mr-tier-gate 同源） */
.ev-gate {
    margin-bottom: var(--awd-panel-gap);
    padding: 8px;
    border-radius: var(--awd-panel-radius);
    background: var(--awd-warning-soft);
    display: flex;
    flex-direction: column;
    gap: 6px;
}

.ev-gate-msg {
    font-size: var(--awd-panel-fs-meta);
    color: var(--awd-danger-text);
    line-height: 1.55;
}

.ev-gate-hint {
    font-size: var(--awd-panel-fs-meta);
    color: var(--awd-warning-text);
    font-variant-numeric: tabular-nums;
}

.ev-gate-actions {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
}

.ev-gate-btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 24px;
    padding: 0 10px;
    border-radius: 4px;
    font-size: var(--awd-panel-fs-meta);
    cursor: pointer;
    user-select: none;
}

.ev-gate-btn.primary {
    background: var(--awd-panel-accent);
    color: var(--awd-text-on-accent);
    font-weight: 500;
}
.ev-gate-btn.primary:hover { background: var(--awd-accent-hover); }

.ev-gate-btn.secondary {
    background: var(--awd-surface);
    border: 1px solid var(--awd-border-strong);
    color: var(--awd-panel-text-2);
}
.ev-gate-btn.secondary:hover { background: var(--awd-panel-hover); }

.ev-gate-btn.disabled { opacity: .55; pointer-events: none; }

.voice-textarea {
  width: 100%;
  height: 110px;
  border: 1px solid var(--awd-panel-border);
  border-radius: var(--awd-panel-radius);
  padding: 8px;
  font-size: var(--awd-panel-fs);
  line-height: 1.6;
  box-sizing: border-box;
  background: var(--awd-surface);
  resize: none;
  transition: border-color 0.2s;
}
.voice-textarea:focus {
    border-color: var(--awd-panel-accent-2);
    outline: none;
}

.form-item {
  margin-bottom: var(--awd-panel-gap);
}
.form-item.relative {
    position: relative;
}

.label {
  font-size: var(--awd-panel-fs-meta);
  color: var(--awd-panel-text-3);
  margin-bottom: 4px;
  display: block;
  font-weight: 500;
}

/* Custom Select Trigger */
.voice-select-trigger {
    width: 100%;
    height: var(--awd-panel-row-h);
    border: 1px solid var(--awd-panel-border);
    border-radius: var(--awd-panel-radius);
    background: var(--awd-surface);
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 8px;
    box-sizing: border-box;
    cursor: pointer;
    transition: all 0.2s;
}
.voice-select-trigger:active {
    border-color: var(--awd-accent);
}
.selected-text {
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.selected-text .placeholder {
    color: var(--awd-text-3);
}
.select-arrow {
    font-size: 10px;
    color: var(--awd-text-2);
}

/* Dropdown Drawer */
.voice-dropdown {
    position: absolute;
    top: 100%;
    left: 0;
    width: 100%;
    background: var(--awd-surface);
    border: 1px solid var(--awd-border);
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.1);
    z-index: 100;
    margin-top: 4px;
    overflow: hidden;
    display: flex;
    flex-direction: column;
}
.dropdown-mask {
    position: fixed;
    top: 0;
    left: 0;
    width: 100vw;
    height: 100vh;
    z-index: 90;
    background: transparent;
}

.voice-search-box {
    padding: 8px;
    border-bottom: 1px solid var(--awd-border-subtle);
}
.voice-search-input {
    width: 100%;
    height: 32px;
    background: var(--awd-bg);
    border: 1px solid var(--awd-border);
    border-radius: 4px;
    padding: 0 8px;
    font-size: 13px;
    box-sizing: border-box;
}

.voice-list-scroll {
    max-height: 240px;
}

.voice-option {
    padding: 5px 8px;
    border-bottom: 1px solid var(--awd-border-subtle);
    cursor: pointer;
    transition: background 0.15s;
}
.voice-option:last-child {
    border-bottom: none;
}
.voice-option:hover {
    background: var(--awd-surface-2);
}
.voice-option.active {
    background: var(--awd-bg);
}
.voice-option.active .voice-name-text {
    color: var(--awd-accent-text);
    font-weight: 600;
}

.voice-info-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 2px;
}
.voice-name-text {
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
}
.voice-gender-tag {
    font-size: 10px;
    background: var(--awd-surface-2);
    padding: 1px 4px;
    border-radius: 4px;
    color: var(--awd-text-2);
}
.voice-locale-text {
    font-size: 11px;
    color: var(--awd-text-3);
}
.empty-tip {
    padding: 16px;
    text-align: center;
    font-size: 12px;
    color: var(--awd-text-3);
}


/* Slider Section */
.slider-header {
    display: flex;
    justify-content: space-between;
    align-items: baseline;
    margin-bottom: 0;
}
.value-text {
    font-size: var(--awd-panel-fs-meta);
    color: var(--awd-panel-accent);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
}

/* Action Area */
.action-area {
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
}

.workdeck-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 32px;
  border-radius: var(--awd-panel-radius);
  font-size: var(--awd-panel-fs);
  font-weight: 600;
  cursor: pointer;
  border: none;
  outline: none;
  box-shadow: 0 2px 4px rgba(26, 83, 54, 0.1);
  transition: all 0.2s;
}

.workdeck-btn-primary {
  background-color: var(--awd-accent);
  color: var(--awd-text-on-accent);
}
.workdeck-btn-primary:active {
  background-color: var(--awd-accent-hover);
  transform: translateY(1px);
}
.workdeck-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    background-color: #9ca3af;
}

.full-width {
  width: 100%;
}

/* Result Area */
.result-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    height: var(--awd-panel-sec-h);
}

.result-title {
    font-size: var(--awd-panel-fs-sec);
    font-weight: 700;
    letter-spacing: 0.04em;
    color: var(--awd-panel-text-2);
}

.download-link {
    font-size: 10px;
    color: var(--awd-panel-accent);
    cursor: pointer;
    font-weight: 500;
}

.custom-player {
    display: flex;
    align-items: center;
    gap: 8px;
    background: var(--awd-accent-wash);
    padding: 6px 8px;
    border-radius: var(--awd-panel-radius);
    border: 1px solid var(--awd-mint);
}
.play-btn {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    background: var(--awd-accent);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--awd-text-on-accent);
    cursor: pointer;
    transition: transform 0.2s;
    font-size: 14px;
}
.play-btn:active {
    transform: scale(0.95);
}
.player-info {
    flex: 1;
}
.player-status {
    font-size: 13px;
    color: var(--awd-text);
}

.btn-glyph {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
</style>
