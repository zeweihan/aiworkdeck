<template>
  <scroll-view scroll-y class="easy-voice-pane">
    <!-- Text Input Section -->
    <view class="section no-border">
      <view class="section-header">
        <text class="section-title">文本内容</text>
        <view class="section-actions">
           <view class="mini-btn" @tap="importFromDoc" title="从当前文档导入">
             <text>导入</text>
           </view>
           <view class="mini-btn" @tap="text = ''" title="清空">
             <svg class="btn-glyph" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.trash" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg>
           </view>
        </view>
      </view>
      <textarea
        class="voice-textarea"
        v-model="text"
        placeholder="在此输入要转换的文本..."
        maxlength="-1"
      />
    </view>

    <!-- Settings Section -->
    <view class="section no-border">
      <text class="section-title">语音设置</text>
      
      <!-- Voice Selection (Custom Dropdown) -->
      <view class="form-item relative">
        <text class="label">说话人</text>
        
        <view class="voice-select-trigger" @tap="toggleVoiceDropdown">
          <view class="selected-text">
             <text v-if="selectedVoiceLabel">{{ selectedVoiceLabel }}</text>
             <text v-else class="placeholder">选择语音...</text>
          </view>
          <text class="select-arrow">▼</text>
        </view>

        <!-- Dropdown Drawer -->
        <view v-if="showVoiceDropdown" class="voice-dropdown" @tap.stop>
           <view class="voice-search-box">
              <input 
                v-model="voiceSearch" 
                class="voice-search-input" 
                placeholder="搜索语音..." 
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
                 无匹配语音
              </view>
           </scroll-view>
        </view>
        <view v-if="showVoiceDropdown" class="dropdown-mask" @tap="showVoiceDropdown = false"></view>
      </view>

      <!-- Rate -->
      <view class="form-item">
        <view class="slider-header">
           <text class="label">语速</text>
           <text class="value-text">{{ rate }}%</text>
        </view>
        <slider 
          :value="rate" 
          @change="onRateChange" 
          min="-50" 
          max="50" 
          show-value 
          block-size="12"
          activeColor="#5BD197"
          backgroundColor="#2D5240"
          block-color="#5BD197"
        />
      </view>

      <!-- Pitch -->
      <view class="form-item">
        <view class="slider-header">
           <text class="label">语调</text>
           <text class="value-text">{{ pitch }}Hz</text>
        </view>
        <slider 
          :value="pitch" 
          @change="onPitchChange" 
          min="-50" 
          max="50" 
          show-value 
          block-size="12"
          activeColor="#5BD197"
          backgroundColor="#2D5240"
          block-color="#5BD197"
        />
      </view>
      
      <!-- Volume -->
       <view class="form-item">
        <view class="slider-header">
           <text class="label">音量</text>
           <text class="value-text">{{ volume }}%</text>
        </view>
         <slider 
          :value="volume" 
          @change="onVolumeChange" 
          min="-50" 
          max="50" 
          show-value 
          block-size="12"
          activeColor="#5BD197"
          backgroundColor="#2D5240"
          block-color="#5BD197"
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
        {{ generating ? '生成中...' : '开始生成' }}
      </button>
    </view>

    <!-- Result Area -->
    <view v-if="audioUrl" class="section no-border result-area">
      <view class="result-header">
         <text class="result-title">生成结果</text>
         <text class="download-link" @tap="downloadAudio">下载</text>
      </view>
      
      <!-- Custom Audio Player -->
      <view class="custom-player" :class="{ playing: isPlaying }">
          <view class="play-btn" @tap="togglePlay">
              <text class="play-icon">{{ isPlaying ? '⏸' : '▶' }}</text>
          </view>
          <view class="player-info">
              <text class="player-status">{{ isPlaying ? '正在播放...' : '点击播放试听' }}</text>
          </view>
      </view>
    </view>
    
  </scroll-view>
</template>

<script>
import { getTtsVoices, generateTtsAudio, promptFeatureNotConfigured } from '@/services/api.js'
import { ICONS } from '@/config/icons.js'

export default {
  name: 'EasyVoicePane',
  data() {
    return {
      text: '',
      voices: [],
      selectedVoiceId: '',  // ElevenLabs voice ID
      selectedVoiceName: '',  // Display name
      voiceSearch: '',
      showVoiceDropdown: false,
      rate: 0,
      pitch: 0,
      volume: 0,
      generating: false,
      audioUrl: '',
      audioInstance: null,
      isPlaying: false,
      // Karaoke highlighting
      sentences: [],
      currentSentenceIndex: -1,
      sentenceDurations: [],
      audioDuration: 0
    }
  },
  computed: {
    ICONS() { return ICONS },
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
  },
  beforeUnmount() {
    this.stopAudio()
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
                uni.showToast({ title: '播放失败', icon: 'none' })
            }
        }
        
        if (this.isPlaying) {
            this.audioInstance.pause()
        } else {
            this.audioInstance.play().catch(e => {
                console.error('Play failed', e)
                uni.showToast({ title: '无法播放', icon: 'none' })
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
            // Default to first available voice (ElevenLabs voices)
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
        uni.showToast({ title: '加载语音列表失败', icon: 'none' })
      }
    },
    onRateChange(e) {
      this.rate = e.detail.value
    },
    onPitchChange(e) {
      this.pitch = e.detail.value
    },
    onVolumeChange(e) {
        this.volume = e.detail.value
    },
    async importFromDoc() {
        const callback = (content) => {
            if (content) {
                this.text = content;
                // Split into sentences for karaoke highlighting
                this.sentences = this.splitTextToSentences(content)
                console.log('[EasyVoice] Split into', this.sentences.length, 'sentences')
                uni.showToast({ title: '已导入文档内容', icon: 'success' })
            } else {
                 uni.showToast({ title: '无法获取文档内容', icon: 'none' })
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
            voice: this.selectedVoiceId,  // Use voiceId for ElevenLabs API
            rate: '+0%',  // ElevenLabs uses different settings, kept for backward compatibility
            pitch: '+0Hz',
            volume: '+0%'
        }
        
        console.log('[EasyVoicePane] Generating with payload:', payload)
        const audioBuffer = await generateTtsAudio(payload)
        console.log('[EasyVoicePane] Generated audio buffer size:', audioBuffer.byteLength)
        
        const blob = new Blob([audioBuffer], { type: 'audio/mpeg' })
        if (this.audioUrl) {
            URL.revokeObjectURL(this.audioUrl)
        }
        this.audioUrl = URL.createObjectURL(blob)
        
        this.$nextTick(() => {
             this.togglePlay()
        })

      } catch (e) {
        console.error('[EasyVoicePane] Generation failed', e)
        if (e && e.featureNotConfigured) {
          // TTS 未配置：引导去设置而非报"生成失败"（#18 T7）
          promptFeatureNotConfigured(e)
        } else {
          uni.showToast({ title: '生成失败', icon: 'none' })
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

<style scoped lang="scss">
.easy-voice-pane {
  height: 100%;
  background-color: transparent; /* 继承左栏 $awd-chrome-panel */
  padding: 16px;
  box-sizing: border-box;
}

.section {
  margin-bottom: 24px;
  background: $awd-chrome-hover;
  padding: 12px;
  border-radius: 8px;
}

.section.no-border {
    border: none;
    box-shadow: none;
    background: $awd-chrome-hover;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: $awd-text-on-dark-2;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.section-actions {
    display: flex;
    gap: 8px;
}

.mini-btn {
    padding: 3px 8px;
    background: transparent;
    border: 1px solid $awd-chrome-active;
    border-radius: 4px;
    font-size: 11px;
    cursor: pointer;
    color: $awd-text-on-dark-2;
    transition: all 0.2s;
}
.mini-btn:hover {
    background: $awd-chrome-active;
    border-color: $awd-chrome-active;
    color: $awd-text-on-dark;
}

.voice-textarea {
  width: 100%;
  height: 140px;
  border: 1px solid $awd-chrome-active;
  border-radius: 6px;
  padding: 12px;
  font-size: 14px;
  line-height: 1.6;
  box-sizing: border-box;
  background: $awd-chrome-hover;
  color: $awd-text-on-dark;
  resize: none;
  transition: border-color 0.2s;
}
.voice-textarea:focus {
    border-color: $awd-mint;
    outline: none;
}

.form-item {
  margin-bottom: 20px;
}
.form-item.relative {
    position: relative;
}

.label {
  font-size: 12px;
  color: $awd-text-on-dark-3;
  margin-bottom: 6px;
  display: block;
  font-weight: 500;
}

/* Custom Select Trigger */
.voice-select-trigger {
    width: 100%;
    height: 40px;
    border: 1px solid $awd-chrome-active;
    border-radius: 6px;
    background: $awd-chrome-hover;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 0 12px;
    box-sizing: border-box;
    cursor: pointer;
    transition: all 0.2s;
}
.voice-select-trigger:active {
    border-color: $awd-mint;
}
.selected-text {
    font-size: 14px;
    color: $awd-text-on-dark;
}
.selected-text .placeholder {
    color: $awd-text-on-dark-3;
}
.select-arrow {
    font-size: 10px;
    color: $awd-text-on-dark-3;
}

/* Dropdown Drawer */
.voice-dropdown {
    position: absolute;
    top: 100%;
    left: 0;
    width: 100%;
    background: $awd-chrome-hover;
    border: 1px solid $awd-chrome-active;
    border-radius: 8px;
    box-shadow: 0 4px 20px rgba(0,0,0,0.4);
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
    border-bottom: 1px solid $awd-chrome-line;
}
.voice-search-input {
    width: 100%;
    height: 32px;
    background: $awd-chrome-panel;
    border: 1px solid $awd-chrome-active;
    border-radius: 4px;
    padding: 0 8px;
    font-size: 13px;
    box-sizing: border-box;
    color: $awd-text-on-dark;
}

.voice-list-scroll {
    max-height: 240px;
}

.voice-option {
    padding: 10px 12px;
    border-bottom: 1px solid $awd-chrome-line;
    cursor: pointer;
    transition: background 0.15s;
}
.voice-option:last-child {
    border-bottom: none;
}
.voice-option:hover {
    background: $awd-chrome-active;
}
.voice-option.active {
    background: rgba($awd-mint, 0.12);
}
.voice-option.active .voice-name-text {
    color: $awd-mint;
    font-weight: 600;
}

.voice-info-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 2px;
}
.voice-name-text {
    font-size: 14px;
    color: $awd-text-on-dark;
}
.voice-gender-tag {
    font-size: 10px;
    background: $awd-chrome-active;
    padding: 1px 4px;
    border-radius: 4px;
    color: $awd-text-on-dark-2;
}
.voice-locale-text {
    font-size: 11px;
    color: $awd-text-on-dark-3;
}
.empty-tip {
    padding: 16px;
    text-align: center;
    font-size: 12px;
    color: $awd-text-on-dark-3;
}


/* Slider Section */
.slider-header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 4px;
}
.value-text {
    font-size: 12px;
    color: $awd-mint;
    font-weight: 500;
}

/* Action Area */
.action-area {
  margin-top: 8px;
  margin-bottom: 24px;
}

.workdeck-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 44px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border: none;
  outline: none;
  transition: all 0.2s;
}

.workdeck-btn-primary {
  background-color: rgba($awd-mint, 0.12);
  border: 1px solid $awd-mint;
  color: $awd-mint;
}
.workdeck-btn-primary:active {
  background-color: rgba($awd-mint, 0.2);
  transform: translateY(1px);
}
.workdeck-btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
    background-color: $awd-chrome-active;
    border-color: transparent;
    color: $awd-text-on-dark-3;
}

.full-width {
  width: 100%;
}

/* Result Area */
.result-area {
    background: rgba($awd-mint, 0.08) !important;
    border: 1px solid rgba($awd-mint, 0.35) !important;
}

.result-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
}

.result-title {
    font-size: 13px;
    font-weight: 600;
    color: $awd-text-on-dark;
}

.download-link {
    font-size: 12px;
    color: $awd-mint;
    cursor: pointer;
    font-weight: 500;
}

.custom-player {
    display: flex;
    align-items: center;
    gap: 12px;
    background: $awd-chrome-hover;
    padding: 10px;
    border-radius: 8px;
    border: 1px solid $awd-chrome-active;
}
.play-btn {
    width: 36px;
    height: 36px;
    border-radius: 50%;
    background: rgba($awd-mint, 0.15);
    border: 1px solid $awd-mint;
    box-sizing: border-box;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $awd-mint;
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
    color: $awd-text-on-dark-2;
}

.btn-glyph {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
</style>
