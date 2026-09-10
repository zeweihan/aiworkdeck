<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view v-if="open" class="memory-mask" @tap="$emit('close')">
    <view class="memory-dialog" @tap.stop>
      <view class="memory-header">
        <view>
          <text class="memory-title">{{ $t('chat.memoryTitle') }}</text>
          <text class="memory-subtitle">{{ $t('chat.memorySubtitle') }}</text>
        </view>
        <text class="memory-close" @tap="$emit('close')">×</text>
      </view>

      <view class="memory-spaces">
        <view
          v-for="space in spaces"
          :key="space.id || space.scope"
          class="memory-space"
          :class="{ active: activeSpace && activeSpace.id === space.id, unavailable: !space.available }"
          @tap="space.available && selectSpace(space)"
        >
          <text>{{ space.label || scopeLabel(space.scope) }}</text>
          <text v-if="!space.available" class="space-reason">{{ space.reason || $t('chat.memoryUnavailable') }}</text>
        </view>
      </view>

      <view v-if="loading" class="memory-loading">{{ $t('chat.memoryLoading') }}</view>
      <view v-else-if="loadError" class="memory-error">
        <text>{{ loadError }}</text>
        <text class="memory-link" @tap="loadSpaces">{{ $t('chat.memoryRetry') }}</text>
      </view>
      <view v-else class="memory-body">
        <view class="memory-files">
          <view class="memory-files-head">
            <text>{{ $t('chat.memoryFiles') }}</text>
            <text v-if="activeSpace && activeSpace.writable" class="memory-link" @tap="showCreate = true">{{ $t('chat.memoryNewTopic') }}</text>
          </view>
          <view v-if="showCreate" class="memory-create">
            <input v-model="newTopic" class="memory-create-input" :placeholder="$t('chat.memoryTopicPlaceholder')" @confirm="createTopic" />
            <text class="memory-link" @tap="createTopic">{{ $t('chat.memoryCreate') }}</text>
          </view>
          <view
            v-for="file in files"
            :key="file.path"
            class="memory-file"
            :class="{ active: current && current.path === file.path }"
            @tap="openFile(file.path)"
          >
            <text class="memory-file-title">{{ file.title || file.path }}</text>
            <text class="memory-file-path">{{ file.path }}</text>
          </view>
        </view>

        <view class="memory-editor">
          <template v-if="current">
            <view class="memory-meta">
              <text>{{ activeSpace.label || scopeLabel(activeSpace.scope) }} / {{ current.path }}</text>
              <text>{{ $t('chat.memoryRevision', { revision: current.revision }) }} · {{ formatUpdated(current.updatedAt) }}</text>
            </view>
            <view v-if="!canWrite" class="memory-readonly">
              {{ activeSpace.reason || $t('chat.memoryReadOnly') }}
            </view>
            <view v-if="conflict" class="memory-conflict">
              <text>{{ $t('chat.memoryConflict') }}</text>
              <view class="memory-conflict-actions">
                <text class="memory-link" @tap="useServerVersion">{{ $t('chat.memoryUseServer') }}</text>
                <text class="memory-link" @tap="saveCurrent">{{ $t('chat.memorySaveMine') }}</text>
              </view>
            </view>
            <textarea v-model="draft" class="memory-textarea" :disabled="!canWrite"></textarea>
            <view v-if="relativeLinks.length" class="memory-related">
              <text class="memory-related-label">{{ $t('chat.memoryLinkedFiles') }}</text>
              <text v-for="link in relativeLinks" :key="link.path" class="memory-link" @tap="openFile(link.path)">{{ link.label }}</text>
            </view>
            <view class="memory-actions">
              <text class="memory-button" @tap="downloadCurrent">{{ $t('chat.memoryDownload') }}</text>
              <text v-if="current.path !== 'remember.md'" class="memory-button danger" :class="{ disabled: !canWrite }" @tap="canWrite && confirmDelete()">{{ $t('chat.memoryDelete') }}</text>
              <text class="memory-button primary" :class="{ disabled: !canWrite || saving }" @tap="canWrite && !saving && saveCurrent()">{{ saving ? $t('chat.memorySaving') : $t('chat.save') }}</text>
            </view>
          </template>
          <text v-else class="memory-empty">{{ $t('chat.memorySelectFile') }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import {
  deleteMemoryFile,
  downloadMemoryFile,
  getMemoryFile,
  getMemoryFiles,
  getMemorySpaces,
  saveMemoryFile,
} from '@/services/api.js'
import { markdownFileLinks } from '@/composables/memoryBrowserState.mjs'

export default {
  name: 'MemoryBrowser',
  emits: ['close'],
  props: {
    open: { type: Boolean, default: false },
    projectId: { type: [String, Number], default: null },
  },
  data() {
    return {
      spaces: [], activeSpace: null, files: [], current: null, draft: '', serverDraft: '',
      loading: false, saving: false, loadError: '', conflict: false,
      showCreate: false, newTopic: '',
      viewGeneration: 0, currentSpaceId: null,
    }
  },
  computed: {
    canWrite() {
      return !!(this.activeSpace && this.activeSpace.writable && this.current
        && this.currentSpaceId === this.activeSpace.id && this.current.writable !== false)
    },
    relativeLinks() {
      return this.current
        ? markdownFileLinks(this.draft, this.current.path, this.files.map((file) => file.path))
        : []
    },
  },
  watch: {
    open(value) {
      if (value) this.loadSpaces()
      else this.viewGeneration += 1
    },
  },
  mounted() {
    if (this.open) this.loadSpaces()
  },
  methods: {
    scopeLabel(scope) {
      return this.$t(`chat.memoryScope${String(scope || '').replace(/^./, (c) => c.toUpperCase())}`)
    },
    formatUpdated(value) {
      if (!value) return this.$t('chat.memoryUnknownTime')
      try { return new Date(value).toLocaleString() } catch (e) { return String(value) }
    },
    async loadSpaces() {
      const generation = ++this.viewGeneration
      this.loading = true
      this.loadError = ''
      try {
        const data = await getMemorySpaces(this.projectId || undefined)
        if (!this.isActiveRequest(null, generation)) return
        this.spaces = Array.isArray(data) ? data : []
        const preferred = this.spaces.find((space) => space.available && space.id === this.activeSpace?.id)
          || this.spaces.find((space) => space.available)
        if (preferred) await this.selectSpace(preferred, generation)
      } catch (e) {
        if (this.isActiveRequest(null, generation)) {
          this.loadError = e.message || this.$t('chat.memoryLoadFailed')
        }
      } finally {
        if (this.viewGeneration === generation) this.loading = false
      }
    },
    isActiveRequest(spaceId, generation) {
      return this.open && this.viewGeneration === generation
        && (spaceId == null || this.activeSpace?.id === spaceId)
    },
    isCurrentFile(spaceId, path, generation) {
      return this.isActiveRequest(spaceId, generation)
        && this.currentSpaceId === spaceId && this.current?.path === path
    },
    currentTarget() {
      if (!this.activeSpace || !this.current || this.currentSpaceId !== this.activeSpace.id) return null
      return {
        spaceId: this.activeSpace.id,
        path: this.current.path,
        revision: this.current.revision,
        generation: this.viewGeneration,
      }
    },
    async selectSpace(space, inheritedGeneration = null) {
      const generation = inheritedGeneration == null ? ++this.viewGeneration : inheritedGeneration
      if (generation !== this.viewGeneration) return
      this.activeSpace = space
      this.files = []
      this.current = null
      this.currentSpaceId = null
      this.draft = ''
      this.conflict = false
      try {
        const data = await getMemoryFiles(space.id)
        if (!this.isActiveRequest(space.id, generation)) return
        this.files = Array.isArray(data) ? data : []
        const first = this.files.find((file) => file.path === 'remember.md') || this.files[0]
        if (first) await this.openFile(first.path, generation)
      } catch (e) {
        if (this.isActiveRequest(space.id, generation)) {
          this.loadError = e.message || this.$t('chat.memoryLoadFailed')
        }
      }
    },
    async openFile(path, inheritedGeneration = null) {
      if (!this.activeSpace || !path) return
      const spaceId = this.activeSpace.id
      const generation = inheritedGeneration == null ? ++this.viewGeneration : inheritedGeneration
      try {
        const file = await getMemoryFile(spaceId, path)
        if (!this.isActiveRequest(spaceId, generation)) return
        this.current = file
        this.currentSpaceId = spaceId
        this.draft = file.content || ''
        this.serverDraft = this.draft
        this.conflict = false
      } catch (e) {
        if (this.isActiveRequest(spaceId, generation)) {
          uni.showToast({ title: e.message || this.$t('chat.memoryLoadFailed'), icon: 'none' })
        }
      }
    },
    async createTopic() {
      if (!this.activeSpace || !this.activeSpace.writable) return
      let path = this.newTopic.trim().replace(/\\/g, '/')
      if (!path) return
      if (!path.toLowerCase().endsWith('.md')) path += '.md'
      if (path.startsWith('/') || path.split('/').includes('..')) {
        uni.showToast({ title: this.$t('chat.memoryInvalidPath'), icon: 'none' })
        return
      }
      const spaceId = this.activeSpace.id
      const generation = this.viewGeneration
      try {
        const file = await saveMemoryFile({ spaceId, path, content: `# ${path.replace(/\.md$/i, '')}\n`, expectedRevision: 0 })
        if (!this.isActiveRequest(spaceId, generation)) return
        this.showCreate = false
        this.newTopic = ''
        await this.refreshFiles(spaceId, generation)
        if (this.isActiveRequest(spaceId, generation)) await this.openFile(file.path, generation)
      } catch (e) {
        if (this.isActiveRequest(spaceId, generation)) {
          uni.showToast({ title: e.message || this.$t('chat.memorySaveFailed'), icon: 'none' })
        }
      }
    },
    async refreshFiles(spaceId = this.activeSpace?.id, generation = this.viewGeneration) {
      if (!spaceId) return
      const data = await getMemoryFiles(spaceId)
      if (!this.isActiveRequest(spaceId, generation)) return
      this.files = Array.isArray(data) ? data : []
    },
    async saveCurrent() {
      if (!this.canWrite || this.saving) return
      const target = this.currentTarget()
      if (!target) return
      const content = this.draft
      this.saving = true
      try {
        const file = await saveMemoryFile({
          spaceId: target.spaceId,
          path: target.path,
          content,
          expectedRevision: target.revision,
        })
        if (!this.isCurrentFile(target.spaceId, target.path, target.generation)) return
        const hasNewerDraft = this.draft !== content
        this.current = file
        this.currentSpaceId = target.spaceId
        this.serverDraft = file.content || ''
        if (!hasNewerDraft) this.draft = this.serverDraft
        this.conflict = false
        await this.refreshFiles(target.spaceId, target.generation)
        uni.showToast({ title: this.$t('chat.memorySaved'), icon: 'success' })
      } catch (e) {
        if (e && e.status === 409 && this.isCurrentFile(target.spaceId, target.path, target.generation)) {
          const latest = await getMemoryFile(target.spaceId, target.path)
          if (!this.isCurrentFile(target.spaceId, target.path, target.generation)) return
          const mine = this.draft
          this.current = latest
          this.currentSpaceId = target.spaceId
          this.serverDraft = latest.content || ''
          this.draft = mine
          this.conflict = true
        } else if (this.isCurrentFile(target.spaceId, target.path, target.generation)) {
          uni.showToast({ title: e.message || this.$t('chat.memorySaveFailed'), icon: 'none' })
        }
      } finally {
        this.saving = false
      }
    },
    useServerVersion() {
      this.draft = this.serverDraft
      this.conflict = false
    },
    confirmDelete() {
      const target = this.currentTarget()
      if (!target) return
      uni.showModal({
        title: this.$t('chat.memoryDeleteTitle'),
        content: this.$t('chat.memoryDeleteConfirm', { path: target.path }),
        success: async (result) => { if (result.confirm) await this.deleteCurrent(target) },
      })
    },
    async deleteCurrent(target = this.currentTarget()) {
      if (!target) return
      try {
        await deleteMemoryFile(target.spaceId, target.path, target.revision)
        if (!this.isCurrentFile(target.spaceId, target.path, target.generation)) return
        const generation = ++this.viewGeneration
        this.current = null
        this.currentSpaceId = null
        this.draft = ''
        await this.refreshFiles(target.spaceId, generation)
        const next = this.files.find((file) => file.path === 'remember.md') || this.files[0]
        if (next) await this.openFile(next.path, generation)
      } catch (e) {
        if (!this.isCurrentFile(target.spaceId, target.path, target.generation)) return
        if (e && e.status === 409) await this.openFile(target.path)
        uni.showToast({ title: e.message || this.$t('chat.memoryDeleteFailed'), icon: 'none' })
      }
    },
    async downloadCurrent() {
      const target = this.currentTarget()
      if (!target) return
      try {
        const bytes = await downloadMemoryFile(target.spaceId, target.path)
        const blob = new Blob([bytes], { type: 'text/markdown;charset=utf-8' })
        const url = URL.createObjectURL(blob)
        const anchor = document.createElement('a')
        anchor.href = url
        anchor.download = target.path.split('/').pop()
        anchor.click()
        setTimeout(() => URL.revokeObjectURL(url), 0)
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('chat.memoryDownloadFailed'), icon: 'none' })
      }
    },
  },
}
</script>

<style scoped>
.memory-mask { position: fixed; inset: 0; z-index: 3200; display: flex; align-items: center; justify-content: center; padding: 24px; background: var(--awd-overlay); backdrop-filter: blur(2px); }
.memory-dialog { width: min(900px, 92vw); height: min(680px, 88vh); display: flex; flex-direction: column; overflow: hidden; border-radius: 12px; background: var(--awd-surface); box-shadow: 0 20px 50px rgba(0,0,0,.18); }
.memory-header { display: flex; justify-content: space-between; align-items: flex-start; padding: 18px 20px 12px; border-bottom: 1px solid var(--awd-border); }
.memory-title { display: block; color: var(--awd-text); font-size: 18px; font-weight: 600; }
.memory-subtitle { display: block; margin-top: 3px; color: var(--awd-text-3); font-size: 12px; }
.memory-close { padding: 0 4px; color: var(--awd-text-2); font-size: 24px; cursor: pointer; }
.memory-spaces { display: flex; gap: 8px; padding: 10px 20px; border-bottom: 1px solid var(--awd-border-subtle); }
.memory-space { display: flex; align-items: center; gap: 5px; padding: 6px 10px; border-radius: 7px; background: var(--awd-bg); color: var(--awd-text-2); font-size: 12px; cursor: pointer; }
.memory-space.active { background: var(--awd-accent-soft); color: var(--awd-accent-text); }
.memory-space.unavailable { opacity: .5; cursor: default; }
.space-reason { font-size: 10px; }
.memory-loading,.memory-error,.memory-empty { margin: auto; color: var(--awd-text-3); font-size: 13px; }
.memory-error { display: flex; gap: 10px; }
.memory-body { min-height: 0; flex: 1; display: flex; }
.memory-files { width: 220px; flex-shrink: 0; overflow-y: auto; padding: 10px; border-right: 1px solid var(--awd-border); background: var(--awd-bg); }
.memory-files-head { display: flex; justify-content: space-between; padding: 4px 6px 9px; color: var(--awd-text-2); font-size: 12px; font-weight: 600; }
.memory-file { padding: 8px; border-radius: 6px; cursor: pointer; }
.memory-file:hover,.memory-file.active { background: var(--awd-accent-soft); }
.memory-file-title,.memory-file-path { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.memory-file-title { color: var(--awd-text); font-size: 12px; }
.memory-file-path { margin-top: 2px; color: var(--awd-text-3); font-size: 10px; }
.memory-create { display: flex; gap: 5px; align-items: center; margin-bottom: 6px; }
.memory-create-input { min-width: 0; flex: 1; height: 26px; padding: 0 6px; border: 1px solid var(--awd-border); border-radius: 5px; background: var(--awd-surface); font-size: 11px; }
.memory-editor { min-width: 0; flex: 1; display: flex; flex-direction: column; padding: 14px; }
.memory-meta { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 8px; color: var(--awd-text-3); font-size: 11px; }
.memory-readonly,.memory-conflict { margin-bottom: 8px; padding: 7px 9px; border-radius: 6px; background: var(--awd-warning-soft, #fff6df); color: var(--awd-text-2); font-size: 11px; }
.memory-conflict { display: flex; justify-content: space-between; gap: 10px; }
.memory-conflict-actions { display: flex; gap: 10px; }
.memory-textarea { width: 100%; min-height: 0; flex: 1; box-sizing: border-box; padding: 12px; border: 1px solid var(--awd-border); border-radius: 8px; background: var(--awd-bg); color: var(--awd-text); font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
.memory-related { display: flex; gap: 8px; align-items: center; padding-top: 8px; font-size: 11px; }
.memory-related-label { color: var(--awd-text-3); }
.memory-link { color: var(--awd-accent-text); cursor: pointer; }
.memory-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 10px; }
.memory-button { padding: 6px 11px; border: 1px solid var(--awd-border); border-radius: 6px; color: var(--awd-text-2); font-size: 12px; cursor: pointer; }
.memory-button.primary { border-color: var(--awd-accent); background: var(--awd-accent); color: var(--awd-text-on-accent); }
.memory-button.danger { color: var(--awd-danger); }
.memory-button.disabled { opacity: .4; cursor: default; }

@media (max-width: 620px) {
  .memory-mask { padding: 8px; }
  .memory-dialog { width: 100%; height: 94vh; }
  .memory-header { padding: 12px; }
  .memory-spaces { padding: 8px 12px; overflow-x: auto; }
  .memory-space { flex-shrink: 0; }
  .memory-body { flex-direction: column; }
  .memory-files { width: auto; max-height: 150px; border-right: 0; border-bottom: 1px solid var(--awd-border); }
  .memory-editor { padding: 10px; }
  .memory-meta { flex-direction: column; gap: 3px; }
  .memory-related,.memory-actions { flex-wrap: wrap; }
}
</style>
