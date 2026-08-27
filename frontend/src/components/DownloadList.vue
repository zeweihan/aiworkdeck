<template>
  <div v-if="store.audioList && store.audioList.length > 0" class="download-area">
    <!-- 下载列表标题 -->
    <div class="download-header">
      <span class="header-title">{{ t('files.downloadListTitle', { count: store.audioList.length }) }}</span>
      <span class="header-title-tips">
        <el-tooltip
          class="box-item"
          effect="dark"
          :content="t('files.refreshWarning')"
          placement="top"
        >
          <el-icon><WarningFilled /></el-icon>
        </el-tooltip>
      </span>
    </div>

    <!-- 下载列表容器 -->
    <el-scrollbar class="download-list">
      <div
        v-for="(item, index) in store.audioList"
        :key="index"
        class="download-item"
        :class="{ downloading: item.isDownloading }"
      >
        <!-- 文件信息 -->
        <div class="file-info">
          <span class="filename">{{ item.file }}</span>
          <span class="file-size">{{ formatFileSize(item.size || 0) }}</span>
        </div>

        <!-- 操作区域 -->
        <div class="actions">
          <el-button
            type="success"
            size="small"
            round
            @click="playAudio(item, index)"
            :icon="item.isPlaying ? VideoPause : VideoPlay"
            class="play-button"
          >
            <transition name="text-fade" mode="out-in">
              <span :key="item.isPlaying ? 'playing' : 'play'">
                {{ item.isPlaying ? t('files.pause') : t('files.play') }}
              </span>
            </transition>
          </el-button>
          <el-button
            type="primary"
            size="small"
            round
            @click="downloadAudio(item, index)"
            :disabled="item.isDownloading"
            :loading="item.isDownloading"
            :icon="Service"
          >
            {{ item.isDownloading ? t('files.downloading') : t('files.download') }}
          </el-button>
          <el-button
            v-if="item.srt"
            type="primary"
            size="small"
            round
            @click="downloadSrt(item, index)"
            :disabled="item.isSrtLoading"
            :loading="item.isSrtLoading"
            :icon="ChatLineSquare"
          >
            {{ item.isSrtLoading ? t('files.downloading') : t('files.download') }}
          </el-button>
          <el-tooltip :content="t('common.delete')" placement="top" :disabled="item.isDownloading" effect="dark">
            <el-icon class="delete-icon" @click="removeDownloadItem(item)">
              <CircleCloseFilled />
            </el-icon>
          </el-tooltip>
        </div>
      </div>
    </el-scrollbar>

    <!-- 批量操作 -->
    <div class="batch-actions">
      <el-button type="primary" size="small" round @click="downloadAll">
        <el-icon><Download /></el-icon>
        {{ t('files.downloadAll') }}
      </el-button>
      <el-button type="danger" size="small" round @click="clearAll">
        <el-icon><Delete /></el-icon>
        {{ t('files.clearList') }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { downloadFile } from '@/api/tts'
import { useGenerationStore } from '@/stores/generation'
import { ElMessage, ElMessageBox } from 'element-plus'
import { watch } from 'vue'
import {
  Download,
  CircleCloseFilled,
  Delete,
  VideoPause,
  VideoPlay,
  ChatLineSquare,
  Service,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useAudio } from '@/utils/index'
import { t } from '@/i18n'
import type { Audio } from '../stores/generation'

const store = useGenerationStore()

const playAudio = async (item: Audio, _: number) => {
  const audio = useAudio(item.audio)
  if (audio.isPlaying.value) {
    audio.pause()
    item.isPlaying = false
  } else {
    try {
      await audio.play()
      item.isPlaying = true
    } catch (err) {
      if (err instanceof Error && err.name === 'NotSupportedError') {
        // 处理不支持的场景
        ElMessage.error(t('files.audioMissing'))
      }
      console.log(`audio.play error`, (err as Error).message)
    }
  }
  watch(audio.isPlaying, (isPlaying) => {
    if (isPlaying === false) {
      item.isPlaying = false
    }
  })
}
const commonDownload = (
  item: Audio,
  file: string,
  successMsg: string,
  loadingProp: keyof Pick<Audio, 'isSrtLoading' | 'isDownloading'>
) => {
  try {
    item[loadingProp] = true
    const url = file.startsWith('blob') ? file : downloadFile(file)
    const link = document.createElement('a')
    link.target = '_blank'
    link.href = url
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    ElMessage.success(successMsg)
  } catch (err) {
    console.log(`commonDownload error: ${file}`, (err as Error).message)
  } finally {
    setTimeout(() => {
      item[loadingProp] = false
    }, 200)
  }
}
function downloadByBlobs(blobs: Blob[], name: string) {
  const mimeType = 'audio/mpeg'
  const audioBlob = new Blob(blobs, { type: mimeType })
  const url = URL.createObjectURL(audioBlob)
  const a = document.createElement('a')
  a.href = url
  a.download = name?.endsWith('.mp3') ? name : `${name}.mp3`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
const downloadAudio = (item: Audio, _: number) => {
  if (item.blobs) return downloadByBlobs(item.blobs, item.name || 'audio')
  if (!item.file) return
  commonDownload(item, item.file, t('files.downloadAudioSuccess'), 'isDownloading')
}
const downloadSrt = (item: Audio, _: number) => {
  console.log('item.srt', item.srt)
  if (!item.srt) return
  commonDownload(item, item.srt, t('files.downloadSrtSuccess'), 'isSrtLoading')
}

const removeDownloadItem = (item: Audio) => {
  if (item.isDownloading) return
  // 确认删除操作
  ElMessageBox.confirm(t('files.confirmDeleteDownload'), t('files.tipTitle'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning',
  }).then(() => {
    const newList = store.audioList.filter((audio) => audio !== item)
    store.updateAudioList(newList)
    ElMessage.success(t('files.deleted'))
  })
}

const formatFileSize = (bytes: number) => {
  if (!bytes) return ''
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const downloadAll = () => {
  store.audioList.forEach((item, index) => {
    if (!item.isDownloading) {
      downloadAudio(item, index)
    }
  })
}

const clearAll = () => {
  ElMessageBox.confirm(t('files.confirmClearList'), t('files.tipTitle'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning',
  }).then(() => {
    store.updateAudioList([])
    ElMessage.success(t('files.cleared'))
  })
}
</script>

<style scoped>
.download-area {
  padding: 16px;
  background: var(--awd-surface);
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
  margin-top: 20px;
}

.download-header {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--awd-border-subtle);
  position: relative;
}

.header-title {
  font-size: 16px;
  font-weight: 500;
  color: var(--awd-text);
}
.header-title-tips {
  position: absolute;
  top: 0px;
  right: 20px;
}
.download-list {
  max-height: 320px;
  margin: 12px 0;
  overflow-y: auto;
  padding: 0px 10px;
}

.download-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 8px;
  border-radius: 6px;
  transition: all 0.3s;
}

.download-item:hover {
  background: var(--awd-surface-2);
}

.download-item.downloading {
  background: var(--awd-bg);
}

.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.filename {
  color: var(--awd-text-2);
  font-size: 14px;
  max-width: 400px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  color: var(--awd-text-3);
  font-size: 12px;
  flex-shrink: 0;
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.delete-icon {
  font-size: 18px;
  color: var(--awd-text-3);
  cursor: pointer;
  transition: color 0.3s;
}

.delete-icon:hover {
  color: var(--awd-danger-text);
}

.batch-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--awd-border-subtle);
}
/* 按钮样式优化 */
.play-button {
  transition: all 0.3s ease;
  padding: 6px 16px; /* 稍微增加内边距 */
}

.play-button:hover {
  transform: scale(1.05); /* 悬浮时轻微放大 */
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1); /* 添加阴影 */
}

.play-button:deep(.el-icon) {
  transition: transform 0.2s ease; /* 图标动画 */
}

.play-button:hover:deep(.el-icon) {
  transform: scale(1.1); /* 图标悬浮放大 */
}

/* 文字切换动画 */
.text-fade-enter-active,
.text-fade-leave-active {
  transition: all 0.2s ease;
}

.text-fade-enter-from,
.text-fade-leave-to {
  opacity: 0;
  transform: translateY(5px);
}
</style>
