<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="account-catalog">
    <view class="catalog-heading">
      <text>{{ $t('projects.accountCatalog') }}</text>
      <button class="catalog-action" :disabled="busy" @tap="load">{{ $t('projects.catalogRefresh') }}</button>
    </view>
    <text v-if="error" class="catalog-note">{{ error }}</text>
    <text v-else-if="!remoteProjects.length" class="catalog-note">{{ $t('projects.catalogAllLocal') }}</text>
    <view v-for="project in remoteProjects" :key="project.projectUid" class="catalog-project">
      <button class="catalog-project-name" :disabled="busy" @tap="open(project)">{{ project.name }}</button>
      <text class="catalog-note">{{ locationsText(project) }}</text>
    </view>
    <view v-if="selected" class="catalog-files">
      <view class="catalog-heading"><text>{{ selected.name }}</text><button class="catalog-action" @tap="selected = null">{{ $t('common.close') }}</button></view>
      <text class="catalog-note">{{ $t('projects.catalogDestination') }}</text>
      <select v-model="targetId" class="catalog-destination" :disabled="busy">
        <option value="">{{ $t('projects.catalogChooseLocal') }}</option>
        <option v-for="p in localProjects" :value="String(p.id)" :key="p.id">{{ p.name }}</option>
      </select>
      <text v-if="incomplete" class="catalog-note">{{ $t('projects.catalogIncomplete') }}</text>
      <text v-if="busy" class="catalog-note">{{ $t('projects.catalogWorking') }}</text>
      <text v-if="!busy && !files.length" class="catalog-note">{{ $t('projects.catalogNoFiles') }}</text>
      <view v-for="file in files" :key="file.uid || [file.deviceId,file.projectKey,file.id].join(':')" class="catalog-file">
        <view><text>{{ file.path || file.name }}</text><text class="catalog-note">{{ file.source === 'cloud' ? $t('projects.catalogCloud') : file.deviceName || file.deviceId }}</text></view>
        <button class="catalog-action" :disabled="busy || !targetId || (file.source === 'desktop' && !file.online)" @tap="download(file)">{{ $t('projects.catalogImport') }}</button>
      </view>
    </view>
  </view>
</template>
<script>
import { getAccountProjectCatalog, requestAccountCatalog, importAccountCatalogFile } from '@/services/api.js'
const pause = (ms) => new Promise(resolve => setTimeout(resolve, ms))
export default {
  name: 'AccountProjectCatalog',
  props: { localProjects: { type: Array, default: () => [] }, currentProjectId: { type: [String, Number], default: null } },
  emits: ['imported'],
  data() { return { catalog: [], selected: null, files: [], busy: false, error: '', incomplete: false, targetId: '' } },
  computed: { remoteProjects() { return this.catalog } },
  mounted() { this.targetId = this.currentProjectId ? String(this.currentProjectId) : ''; this.load() },
  methods: {
    async load() {
      this.busy = true; this.error = ''
      try { this.catalog = await getAccountProjectCatalog() } catch (e) { this.error = e.message }
      finally { this.busy = false }
    },
    locationsText(p) { return (p.locations || []).map(l => l.kind === 'cloud' ? this.$t('projects.catalogCloud') : `${l.deviceName || l.deviceId} · ${this.$t(l.online ? 'projects.catalogOnline' : 'projects.catalogOffline')}`).join(' / ') },
    request(method, path, body) {
      return requestAccountCatalog(method, path, body, this.selected?.accountScope)
    },
    async poll(id, wanted) {
      for (let i = 0; i < 90; i++) {
        const response = await this.request('GET', `/api/mobile/transfer/${id}`)
        const transfer = response.transfer
        if (transfer.status === wanted || (wanted === 'STAGED' && transfer.status === 'DELIVERED')) return transfer
        if (['FAILED', 'EXPIRED'].includes(transfer.status)) throw new Error(transfer.error || this.$t('projects.catalogFailed'))
        await pause(1500)
      }
      throw new Error(this.$t('projects.catalogPending'))
    },
    async open(project) {
      this.selected = project; this.files = []; this.busy = true; this.error = ''
      try {
        const snapshot = await this.request('GET', `/api/mobile/catalog/${project.projectUid}/files`)
        const entries = new Map()
        for (const file of snapshot.files || []) entries.set(file.uid || `${file.deviceId}:${file.projectKey}:${file.id}`, file)
        const desktopLocations = (project.locations || []).filter(l => l.kind === 'desktop')
        const incomplete = new Map(desktopLocations.map(l => [`${l.deviceId}:${l.key}`, snapshot.truncated]))
        this.files = [...entries.values()]
        for (const location of desktopLocations.filter(l => l.online)) {
          const route = `${location.deviceId}:${location.key}`
          try {
            const created = await this.request('POST', '/api/mobile/transfer/list', { deviceId: location.deviceId, projectKey: location.key, requestId: crypto.randomUUID() })
            const listing = await this.poll(created.id, 'DONE')
            incomplete.set(route, !!listing.truncated)
            for (const [key, entry] of entries) if (entry.source === 'desktop' && `${entry.deviceId}:${entry.projectKey}` === route) entries.delete(key)
            for (const file of listing.files || []) {
              const key = file.uid || `${route}:${file.id}`
              if (entries.get(key)?.source === 'cloud') continue
              entries.set(key, { ...file, source: 'desktop', deviceId: location.deviceId, deviceName: location.deviceName, projectKey: location.key, online: true })
            }
          } catch (e) { incomplete.set(route, true); this.error = e.message }
          this.files = [...entries.values()]
        }
        this.incomplete = [...incomplete.values()].some(Boolean)
        this.files = [...entries.values()]
      } catch (e) { this.error = e.message }
      finally { this.busy = false }
    },
    async download(file) {
      this.busy = true; this.error = ''
      try {
        const body = { accountScope: this.selected.accountScope, targetProjectId: Number(this.targetId), projectUid: this.selected.projectUid, fileUid: file.uid }
        if (file.source === 'desktop') {
          const key = `awd-catalog-pull:${this.selected.accountScope}:${this.selected.projectUid}:${file.deviceId}:${file.projectKey}:${file.id}`
          let transfer = uni.getStorageSync(key)
          if (!transfer) {
            const quote = await this.request('GET', `/api/mobile/transfer/quote?bytes=${file.size || 0}`)
            const confirmed = await new Promise(resolve => uni.showModal({ title: this.$t('projects.catalogImport'), content: this.$t('projects.catalogQuote', { credits: quote.credits }), success: result => resolve(result.confirm), fail: () => resolve(false) }))
            if (!confirmed) return
            transfer = { requestId: crypto.randomUUID() }; uni.setStorageSync(key, transfer)
          }
          if (!transfer.id) {
            const created = await this.request('POST', '/api/mobile/transfer/pull', { deviceId: file.deviceId, projectKey: file.projectKey, remoteFileId: file.id, fileName: file.name, fileSize: file.size, requestId: transfer.requestId })
            transfer.id = created.id; uni.setStorageSync(key, transfer)
          }
          await this.poll(transfer.id, 'STAGED'); body.transferId = transfer.id
          const result = await importAccountCatalogFile(body)
          uni.removeStorageSync(key); this.$emit('imported', result)
        } else {
          this.$emit('imported', await importAccountCatalogFile(body))
        }
        uni.showToast({ title: this.$t('projects.catalogSaved'), icon: 'success' })
      } catch (e) { this.error = e.message }
      finally { this.busy = false }
    },
  },
}
</script>
<style scoped>
.account-catalog { margin: 12px 0; padding: 12px; border: 1px solid var(--awd-border); border-radius: 8px; }
.catalog-heading,.catalog-file { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.catalog-note { display: block; font-size: 12px; color: var(--awd-text-secondary); line-height: 1.6; overflow-wrap: anywhere; }
.catalog-project { padding: 8px 0; }
.catalog-project-name,.catalog-action { margin: 0; background: transparent; border: 0; font-size: 12px; color: var(--awd-text-primary); text-align: left; padding: 3px 6px; }
.catalog-project-name { font-weight: 600; font-size: 13px; }
.catalog-files { border-top: 1px solid var(--awd-border); padding-top: 10px; }
.catalog-file { padding: 8px 0; font-size: 12px; overflow-wrap: anywhere; }
.catalog-destination { width: 100%; margin: 8px 0; }
</style>
