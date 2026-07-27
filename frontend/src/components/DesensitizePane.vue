<template>
  <scroll-view scroll-y class="desensitize-pane">
    <view class="section">
      <view class="section-title">文件选择</view>
      <view class="file-input-wrapper">
         <view class="path-display" :class="{ empty: !filePath }" @tap="triggerFileSelect">
            {{ filePath ? filePath : '点击选择或拖入文件' }}
         </view>
         <view class="actions-row">
            <view class="mini-btn" @tap="importFromActiveTab" title="从当前打开文件导入">
               <text>导入当前</text>
            </view>
            <view class="mini-btn" @tap="triggerFileSelect" title="从项目浏览">
               <text>浏览</text>
            </view>
         </view>
      </view>
    </view>

    <view class="section">
      <view class="section-title">脱敏策略</view>
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
        {{ processing ? '处理中...' : '生成脱敏文件' }}
      </button>
    </view>

    <view class="info-tip" v-if="filePath">
       <text>将生成: [已脱敏]{{ fileName }}</text>
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
            uni.showToast({ title: '获取策略失败', icon: 'none' })
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
                     uni.showToast({ title: '当前文件未保存或无路径', icon: 'none' })
                     return
                 }
                 this.filePath = file.filePath
                 this.fileName = file.name
                 this.fileId = file.id // Store fileId
                 uni.showToast({ title: '已选择当前文件', icon: 'success' })
             } else {
                 uni.showToast({ title: '无打开的文件', icon: 'none' })
             }
        })
    },
    async handleGenerate() {
        if (!this.fileId || this.selectedStrategies.length === 0) {
             if (!this.fileId) uni.showToast({ title: '请选择有效文件', icon: 'none' })
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
                uni.showToast({ title: '脱敏成功', icon: 'success' })
                // Open the new file (pass the full object)
                this.$emit('open-file', res)
            }
        } catch (e) {
            console.error('Desensitization failed', e)
            uni.showToast({ title: '处理失败: ' + e.message, icon: 'none' })
        } finally {
            this.processing = false
        }
    }
  }
}
</script>

<style scoped>
.desensitize-pane {
  height: 100%;
  background-color: #f9fafb;
  padding: 16px;
  box-sizing: border-box;
}

.section {
  margin-bottom: 24px;
  background: #fff;
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
}

.section-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  margin-bottom: 12px;
}

.file-input-wrapper {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.path-display {
    padding: 10px;
    background: #f3f4f6;
    border: 1px dashed #d1d5db;
    border-radius: 6px;
    font-size: 12px;
    color: #374151;
    word-break: break-all;
    min-height: 40px;
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
    gap: 10px;
}

.mini-btn {
    flex: 1;
    padding: 6px 0;
    text-align: center;
    background: #fff;
    border: 1px solid #e5e7eb;
    border-radius: 4px;
    font-size: 12px;
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
    gap: 10px;
}

.strategy-item {
    display: flex;
    align-items: center;
    cursor: pointer;
    padding: 4px 0;
}

.checkbox {
    width: 16px;
    height: 16px;
    border: 1px solid #d1d5db;
    border-radius: 4px;
    margin-right: 8px;
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
    font-size: 13px;
    color: #1f2937;
}

.action-area {
  margin-top: 24px;
}

.workdeck-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 40px;
  border-radius: 6px;
  font-size: 14px;
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
    margin-top: 12px;
    font-size: 11px;
    color: #6b7280;
    text-align: center;
}
</style>
