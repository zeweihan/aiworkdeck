<template>
  <scroll-view scroll-y class="desensitize-pane">
    <view class="section">
      <view class="section-title">{{ $t('panels.deSectionFileSelect') }}</view>
      <view class="file-input-wrapper">
         <view class="path-display" :class="{ empty: !filePath }" @tap="triggerFileSelect">
            {{ filePath ? filePath : $t('panels.deFilePlaceholder') }}
         </view>
         <view class="actions-row">
            <view class="mini-btn" @tap="importFromActiveTab" :title="$t('panels.deImportFromTabTitle')">
               <text>{{ $t('panels.deImportCurrent') }}</text>
            </view>
            <view class="mini-btn" @tap="triggerFileSelect" :title="$t('panels.deBrowseTitle')">
               <text>{{ $t('panels.deBrowse') }}</text>
            </view>
         </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title">{{ $t('panels.deStrategiesTitle') }}</view>
      <view class="strategies-list">
        <label
          v-for="s in availableStrategies"
          :key="s.value"
          class="strategy-item"
          @tap="toggleStrategy(s.value)"
        >
          <view class="checkbox" :class="{ checked: selectedStrategies.includes(s.value) }">
             <text v-if="selectedStrategies.includes(s.value)" class="check-mark">✓</text>
          </view>
          <text class="strategy-label">{{ s.label }}</text>
        </label>
      </view>
    </view>

    <view class="action-area">
      <button
        class="workdeck-btn workdeck-btn-primary full-width"
        @tap="handleGenerate"
        :disabled="processing || !filePath || selectedStrategies.length === 0"
        :loading="processing"
      >
        {{ processing ? $t('panels.deProcessing') : $t('panels.deGenerate') }}
      </button>
    </view>

    <view class="info-tip" v-if="filePath">
       <text>{{ $t('panels.deWillGenerate', { fileName }) }}</text>
    </view>
  </scroll-view>
</template>

<script>
import { desensitizeFile, getSensitiveOptions } from '@/services/api.js'

export default {
  name: 'DesensitizePane',
  props: {
    projectId: {
        type: [String, Number],
        required: true
    }
  },
  data() {
    return {
      filePath: '',
      fileName: '',
      fileId: null, // Add fileId
      availableStrategies: [], // Fetch from backend
      selectedStrategies: [],
      processing: false
    }
  },
  mounted() {
      this.fetchOptions()
  },
  methods: {
    async fetchOptions() {
        try {
            const res = await getSensitiveOptions()
             // API wrapper returns data directly (if code===0) or array directly depending on backend.
             // Controller returns ResponseEntity<List<Map>>, which usually results in just the list in JSON.
             // api.js request wrapper resolves with res.data.
             // Backend SensitiveController values:
             // return ResponseEntity.ok(options); -> This is a direct list.
             // api.js: if (res.data && typeof res.data.code !== 'undefined') ... else resolve(res.data)
             // So if the backend returns a raw list, it should be in res.data (the list).
             
             if (Array.isArray(res)) {
                 this.availableStrategies = res
             } else if (res && res.data && Array.isArray(res.data)) {
                 // in case it's wrapped
                 this.availableStrategies = res.data
             }
             
             // Select default ones
             this.selectedStrategies = this.availableStrategies
                 .filter(s => ['PHONE', 'ID_CARD'].includes(s.value))
                 .map(s => s.value)
        } catch (e) {
            console.error('Failed to fetch strategies', e)
            uni.showToast({ title: this.$t('panels.deFetchStrategiesFailed'), icon: 'none' })
        }
    },
    toggleStrategy(val) {
        const idx = this.selectedStrategies.indexOf(val)
        if (idx > -1) {
            this.selectedStrategies.splice(idx, 1)
        } else {
            this.selectedStrategies.push(val)
        }
    },
    triggerFileSelect() {
        // Request parent to pick file
        // Callback will be handled by listening to an event or prop update if implemented differently
        // Here we emit an event hoping parent handles it
        this.$emit('request-file-select', (file) => {
            if (file) {
               this.filePath = file.filePath || file.path // Adapt to file object structure
               this.fileName = file.name
               this.fileId = file.id // Store fileId
            }
        })
    },
    importFromActiveTab() {
        // Request parent for active file
        this.$emit('request-active-file', (file) => {
             if (file) {
                 if (!file.filePath) {
                     uni.showToast({ title: this.$t('panels.deNoPathCurrentFile'), icon: 'none' })
                     return
                 }
                 this.filePath = file.filePath
                 this.fileName = file.name
                 this.fileId = file.id // Store fileId
                 uni.showToast({ title: this.$t('panels.deSelectedCurrentFile'), icon: 'success' })
             } else {
                 uni.showToast({ title: this.$t('panels.deNoOpenFile'), icon: 'none' })
             }
        })
    },
    async handleGenerate() {
        if (!this.fileId || this.selectedStrategies.length === 0) {
             if (!this.fileId) uni.showToast({ title: this.$t('panels.deSelectValidFile'), icon: 'none' })
             return
        }
        this.processing = true
        try {
            const res = await desensitizeFile({
                fileId: this.fileId, // Send fileId
                strategies: this.selectedStrategies
            })

            // Backend now returns the full ProjectFile object
            if (res && res.id) {
                uni.showToast({ title: this.$t('panels.deSuccess'), icon: 'success' })
                // Open the new file (pass the full object)
                this.$emit('open-file', res)
            }
        } catch (e) {
            console.error('Desensitization failed', e)
            uni.showToast({ title: this.$t('panels.deProcessFailed', { msg: e.message }), icon: 'none' })
        } finally {
            this.processing = false
        }
    }
  }
}
</script>

<style scoped>
/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场）。
   原来是「灰底 + 白卡片 + 16px 内边距 + 1px 描边」三层套娃，在 260px 宽里
   实际可用宽度只剩 196px。 */
.desensitize-pane {
  height: 100%;
  background-color: #fff;
  box-sizing: border-box;
}

.section {
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
  background: #fff;
}

.section-title {
  display: flex;
  align-items: center;
  height: var(--awd-panel-sec-h);
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.file-input-wrapper {
    display: flex;
    flex-direction: column;
    gap: var(--awd-panel-gap);
}

.path-display {
    padding: 6px 8px;
    background: #F8F9FA;
    border: 1px dashed #D1D5DB;
    border-radius: var(--awd-panel-radius);
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
    word-break: break-all;
    min-height: var(--awd-panel-row-h);
    box-sizing: border-box;
    display: flex;
    align-items: center;
    cursor: pointer;
}
.path-display.empty {
    color: #9ca3af;
    justify-content: center;
}

.actions-row {
    display: flex;
    gap: 6px;
}

.mini-btn {
    flex: 1;
    height: 24px;
    line-height: 22px;
    text-align: center;
    background: #fff;
    border: 1px solid var(--awd-panel-border);
    border-radius: 4px;
    font-size: var(--awd-panel-fs-meta);
    color: #4b5563;
    cursor: pointer;
    transition: all 0.2s;
}
.mini-btn:hover {
    background: #f9fafb;
    border-color: #d1d5db;
}

.strategies-list {
    display: flex;
    flex-direction: column;
    gap: 0;
}

.strategy-item {
    display: flex;
    align-items: center;
    cursor: pointer;
    height: 24px;
}
.strategy-item:hover { background: var(--awd-panel-accent-wash); }

.checkbox {
    width: 14px;
    height: 14px;
    flex-shrink: 0;
    border: 1px solid #d1d5db;
    border-radius: 3px;
    margin-right: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s;
}
.checkbox.checked {
    background-color: #1A5336;
    border-color: #1A5336;
}
.check-mark {
    color: #fff;
    font-size: 10px;
}

.strategy-label {
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
}

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
  background-color: #1A5336;
  color: #fff;
  transition: opacity 0.2s;
}
.workdeck-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
}

.info-tip {
    margin-top: 6px;
    font-size: 10px;
    line-height: 1.5;
    color: #6b7280;
    text-align: center;
}
</style>
