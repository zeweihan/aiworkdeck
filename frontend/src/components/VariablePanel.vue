<template>
  <div class="variable-panel">
    <div class="variable-layout">
      <div class="variable-main">


        <div class="variable-list">
          <div v-if="loading" class="loading">{{ $t('editor.vars.loading') }}</div>
          <div v-else-if="displayItems.length === 0" class="empty">{{ $t('editor.vars.empty') }}</div>
          <div v-else-if="!filteredItems.length" class="empty">{{ $t('editor.vars.noMatch') }}</div>

          <div v-else class="list-scroll">
            <div class="list-grid">
              <div v-for="it in filteredItems" :key="it.key" class="var-card">
                <div class="var-card-header">
                  <div class="var-info">
                    <div class="var-name" :title="it.name">{{ it.name }}</div>
                    <div class="var-creator">{{ it.creatorName || (it.scope === 'U' ? 'User' : 'Project') }}</div>
                    <span class="var-time-top">{{ formatUpdateTime(it.updatedAt) }}</span>
                  </div>
                  <div class="var-actions-top">
                    <!-- Vertical Stack -->
                    <button class="var-act-btn" @click.stop="insertVariable(it)" :title="$t('editor.vars.insert')"><svg class="var-act-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path v-for="(d, gi) in ICONS.bolt" :key="gi" :d="d" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" /></svg></button>
                    <button class="var-act-btn" @click.stop="updateValueFromSelection(it)" :title="$t('editor.vars.updateValue')">↻</button>
                    <div class="del-wrapper" style="position: relative;">
                      <button v-if="it.canDelete" class="var-act-btn danger" @click.stop="requestDelete(it)" :title="$t('editor.vars.delete')">×</button>
                      <!-- Inline Confirm Popup -->
                      <div v-if="confirmDeleteKey === it.key" class="delete-popover" @click.stop>
                        <div class="pop-arrow"></div>
                        <div class="pop-text">{{ $t('editor.vars.confirmDelete') }}</div>
                        <div class="pop-row">
                          <span class="pop-btn" @click.stop="cancelDelete">{{ $t('editor.vars.cancel') }}</span>
                          <span class="pop-btn danger" @click.stop="confirmDelete(it)">{{ $t('editor.vars.confirm') }}</span>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                
                
                <div class="var-value" :title="it.value">{{ it.value || $t('editor.vars.emptyValue') }}</div>
                
                <!-- Footer removed to maximize content space -->
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 变量类型：右侧纵向排列（IDE 终端风格） -->
      <div class="scope-rail">
        <div class="scope-item" :class="{ active: activeScope === 'doc' }" @click="switchScope('doc')">{{ $t('editor.vars.docScope') }}</div>
        <div class="scope-item" :class="{ active: activeScope === 'project' }" @click="switchScope('project')">{{ $t('editor.vars.projectScope') }}</div>
        <div class="scope-item" :class="{ active: activeScope === 'user' }" @click="switchScope('user')">{{ $t('editor.vars.userScope') }}</div>
      </div>
    </div>








    <div v-if="showCreateModal" class="modal-mask" @click="closeCreateModal">
      <div class="modal" @click.stop>
        <div class="modal-title">{{ $t('editor.vars.modalTitle') }}</div>
        <div class="modal-subtitle">{{ $t('editor.vars.modalSubtitle') }}</div>
        <input class="modal-input" v-model="createForm.name" :placeholder="$t('editor.vars.namePlaceholder')" />
        <div class="modal-actions">
          <button class="modal-btn" @click="closeCreateModal">{{ $t('editor.vars.cancel') }}</button>
          <button class="modal-btn primary" :disabled="!createForm.name.trim()" @click="confirmCreate">{{ $t('editor.vars.create') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ICONS } from '@/config/icons.js'
import {
  getProjectVariables,
  saveProjectVariable,
  deleteProjectVariable,
  getUserVariables,
  saveUserVariable,
  deleteUserVariable
} from '@/services/api.js'

export default {
  props: {
    projectId: {
      type: [String, Number],
      required: true
    },
    getEditor: {
      type: Function,
      default: null
    },
    // 由父面板统一提供搜索关键字
    searchKeyword: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      activeScope: 'doc',
      loading: false,
      projectVars: [],
      userVars: [],
      docFields: [],
      showCreateModal: false,
      createForm: { name: '' },
      confirmDeleteKey: null
    }
  },
  computed: {
    ICONS() { return ICONS },
    displayItems() {
      if (this.activeScope === 'doc') {
        const groups = new Map()
        for (const f of (this.docFields || [])) {
          const key = `${f.scope}:${f.varName}`
          if (!groups.has(key)) groups.set(key, [])
          groups.get(key).push(f)
        }
        const items = []
        groups.forEach((list, key) => {
          const first = list[0] || {}
          const scope = first.scope || 'D'
          const varName = first.varName || key
          const count = list.length
          const value = this._resolveValue(scope, varName, first.text || '')
          
          let backendId = null
          let canDelete = false
          if (scope === 'P') {
             const found = (this.projectVars || []).find(v => v.name === varName)
             if (found) { backendId = found.id; canDelete = true; }
          } else if (scope === 'U') {
             const found = (this.userVars || []).find(v => v.name === varName)
             if (found) { backendId = found.id; canDelete = true; }
          }

          items.push(this._toCardItem({
            key,
            scope,
            name: varName,
            value,
            occurrences: count,
            fieldIds: list.map(x => x.id).filter(Boolean),
            backendId,
            canDelete
          }))
        })
        return items.sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-Hans-CN'))
      }

      if (this.activeScope === 'project') {
        return (this.projectVars || []).map(v => this._toCardItem({
          key: `P:${v.id || v.name}`,
          scope: 'P',
          name: v.name,
          value: v.resolvedValue || v.value || '',
          updatedAt: v.updatedAt || v.createdAt,
          backendId: v.id,
          canDelete: true
        }))
      }

      return (this.userVars || []).map(v => this._toCardItem({
        key: `U:${v.id || v.name}`,
        scope: 'U',
        name: v.name,
        value: v.resolvedValue || v.value || '',
        updatedAt: v.updatedAt || v.createdAt,
        backendId: v.id,
        canDelete: true
      }))
    },
    filteredItems() {
      const keyword = (this.searchKeyword || '').trim().toLowerCase()
      if (!keyword) return this.displayItems
      return this.displayItems.filter(item => {
        const textTargets = [
          item.name,
          item.value,
          item.meta
        ]
        return textTargets.some(val => typeof val === 'string' && val.toLowerCase().includes(keyword))
      })
    }
  },
  mounted() {
    this.refresh()
  },
  methods: {
    switchScope(scope) {
      this.activeScope = scope
      this.refresh()
    },

    async refresh() {
      this.loading = true
      try {
        await Promise.all([this.fetchDocFields(), this.fetchProjectVars(), this.fetchUserVars()])
      } catch (e) {
        console.error('刷新变量失败', e)
      } finally {
        this.loading = false
      }
    },

    async fetchProjectVars() {
      if (!this.projectId) {
        this.projectVars = []
        return
      }
      try {
        const res = await getProjectVariables(this.projectId)
        this.projectVars = Array.isArray(res) ? res : (res.data || [])
      } catch (e) {
        this.projectVars = []
      }
    },

    async fetchUserVars() {
      try {
        const res = await getUserVariables()
        this.userVars = Array.isArray(res) ? res : (res.data || [])
      } catch (e) {
        this.userVars = []
      }
    },

    async fetchDocFields() {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.listVariableFields !== 'function') {
        this.docFields = []
        return
      }
      try {
        this.docFields = await editor.listVariableFields()
      } catch (e) {
        this.docFields = []
      }
    },

    _resolveValue(scope, varName, currentText) {
      if (scope === 'P') {
        const v = (this.projectVars || []).find(x => x.name === varName)
        return (v && (v.resolvedValue || v.value)) || ''
      }
      if (scope === 'U') {
        const v = (this.userVars || []).find(x => x.name === varName)
        return (v && (v.resolvedValue || v.value)) || ''
      }
      return currentText || ''
    },

    _toCardItem(raw) {
      const scope = raw.scope || 'D'
      const badgeMap = {
        D: { text: '文\n本', tone: 'neutral', label: '文本' },
        P: { text: '项\n目', tone: 'info', label: '项目' },
        U: { text: '用\n户', tone: 'info', label: '用户' }
      }
      const badge = badgeMap[scope] || badgeMap.D
      const occurrences = raw.occurrences || 0
      const meta = occurrences ? `${badge.label} · ${occurrences}处` : badge.label
      return {
        key: raw.key,
        scope,
        tone: badge.tone,
        badgeText: badge.text,
        name: raw.name || '',
        value: raw.value || '',
        meta,
        updatedAt: raw.updatedAt || null,
        occurrences,
        fieldIds: raw.fieldIds || [],
        backendId: raw.backendId,
        canDelete: !!raw.canDelete
      }
    },

    formatUpdateTime(v) {
      if (!v) return '—'
      try {
        const d = new Date(v)
        if (Number.isNaN(d.getTime())) return '—'
        const Y = d.getFullYear()
        const M = String(d.getMonth() + 1).padStart(2, '0')
        const D = String(d.getDate()).padStart(2, '0')
        const h = String(d.getHours()).padStart(2, '0')
        const m = String(d.getMinutes()).padStart(2, '0')
        return `${Y}-${M}-${D} ${h}:${m}`
      } catch (e) {
        return '—'
      }
    },

    openCreateModal() {
      this.createForm.name = ''
      this.showCreateModal = true
    },

    closeCreateModal() {
      this.showCreateModal = false
    },

    async confirmCreate() {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.getSelectionText !== 'function') {
        uni.showToast({ title: this.$t('editor.vars.activateEditorFirst'), icon: 'none' })
        return
      }
      const selected = await editor.getSelectionText()
      const text = (selected || '').trim()
      if (!text) {
        uni.showToast({ title: this.$t('editor.vars.selectInDocFirst'), icon: 'none' })
        return
      }
      const name = (this.createForm.name || '').trim()
      if (!name) return

      const scope = this.activeScope === 'project' ? 'P' : (this.activeScope === 'user' ? 'U' : 'D')
      try {
        if (scope === 'P') {
          await saveProjectVariable({ projectId: Number(this.projectId), name, value: text, type: 'TEXT' })
        } else if (scope === 'U') {
          await saveUserVariable({ name, value: text, type: 'TEXT' })
        }

        if (typeof editor.insertTextWithDocumentField !== 'function') {
          throw new Error(this.$t('editor.vars.noFieldInsert'))
        }
        await editor.insertTextWithDocumentField(text, scope, name)

        this.closeCreateModal()
        await this.refresh()
        uni.showToast({ title: this.$t('editor.vars.created'), icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('editor.vars.createFailed'), icon: 'none' })
      }
    },

    async insertVariable(it) {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.insertTextWithDocumentField !== 'function') {
        uni.showToast({ title: this.$t('editor.vars.activateEditorFirst'), icon: 'none' })
        return
      }
      try {
        const value = this._resolveValue(it.scope, it.name, it.value)
        await editor.insertTextWithDocumentField(value, it.scope, it.name)
        uni.showToast({ title: this.$t('editor.vars.inserted'), icon: 'success' })
        await this.fetchDocFields()
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('editor.vars.insertFailed'), icon: 'none' })
      }
    },

    async updateValueFromSelection(it) {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.getSelectionText !== 'function') {
        uni.showToast({ title: this.$t('editor.vars.activateEditorFirst'), icon: 'none' })
        return
      }
      const selected = await editor.getSelectionText()
      const text = (selected || '').trim()
      if (!text) {
        uni.showToast({ title: this.$t('editor.vars.selectContentFirst'), icon: 'none' })
        return
      }

      uni.showModal({
        title: this.$t('editor.vars.confirmUpdateTitle'),
        content: this.$t('editor.vars.confirmUpdateContent', { name: it.name }),
        success: async (res) => {
          if (!res.confirm) return
          try {
            if (it.scope === 'P') {
              await saveProjectVariable({ projectId: Number(this.projectId), name: it.name, value: text, type: 'TEXT' })
              await this._updateAllFieldInstances('P', it.name, text)
              await this.fetchProjectVars()
            } else if (it.scope === 'U') {
              await saveUserVariable({ name: it.name, value: text, type: 'TEXT' })
              await this._updateAllFieldInstances('U', it.name, text)
              await this.fetchUserVars()
            } else {
              await this._updateAllFieldInstances('D', it.name, text)
            }
            await this.fetchDocFields()
            uni.showToast({ title: this.$t('editor.vars.updated'), icon: 'success' })
          } catch (e) {
            uni.showToast({ title: e.message || this.$t('editor.vars.updateFailed'), icon: 'none' })
          }
        }
      })
    },

    async _updateAllFieldInstances(scope, varName, nextText) {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.updateDocumentField !== 'function') return
      const fields = (this.docFields || []).filter(f => f.scope === scope && f.varName === varName)
      for (const f of fields) {
        try {
          await editor.updateDocumentField(f.id, nextText)
        } catch (e) {
          // ignore
        }
      }
    },

    async syncDocument() {
      const editor = this.getEditor ? this.getEditor() : null
      if (!editor || typeof editor.syncAllDocumentFields !== 'function') {
        uni.showToast({ title: this.$t('editor.vars.activateEditorFirst'), icon: 'none' })
        return
      }
      uni.showLoading({ title: this.$t('editor.vars.syncing') })
      try {
        await this.fetchProjectVars()
        await this.fetchUserVars()
        const res = await editor.syncAllDocumentFields((scope, varName, currentText) => {
          if (scope === 'P' || scope === 'U') return this._resolveValue(scope, varName, currentText) || ''
          return currentText
        })
        uni.hideLoading()
        await this.fetchDocFields()
        uni.showToast({ title: this.$t('editor.vars.syncDone', { count: res.updated || 0 }), icon: 'none' })
      } catch (e) {
        uni.hideLoading()
        uni.showToast({ title: e.message || this.$t('editor.vars.syncFailed'), icon: 'none' })
      }
    },

    requestDelete(it) {
      if (this.confirmDeleteKey === it.key) {
        this.confirmDeleteKey = null // toggle off
        return
      }
      this.confirmDeleteKey = it.key
      // Auto-hide after 3 seconds if not confirmed
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
      this._deleteTimer = setTimeout(() => {
        if (this.confirmDeleteKey === it.key) {
          this.confirmDeleteKey = null
        }
      }, 5000)
    },

    cancelDelete() {
      this.confirmDeleteKey = null
      if (this._deleteTimer) clearTimeout(this._deleteTimer)
    },

    async confirmDelete(it) {
      this.cancelDelete()
      try {
        if (it.scope === 'P') {
          if (it.backendId) await deleteProjectVariable(it.backendId)
          await this.fetchProjectVars()
        } else if (it.scope === 'U') {
          if (it.backendId) await deleteUserVariable(it.backendId)
          await this.fetchUserVars()
        }
        uni.showToast({ title: this.$t('editor.vars.deleted'), icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || this.$t('editor.vars.deleteFailed'), icon: 'none' })
      }
    }
  }
}
</script>

<style lang="scss" scoped>
/* Color Config - AI Workdeck Palette */
$color-primary: #1A5336; // Forest Green
$color-accent: #5BD197; // Mint Green
$color-accent-pale: #E6F9F0;
$color-text-main: #2C3338;
$color-text-light: #6C757D;
$color-border: #E9ECEF;
$bg-pale: #F8F9FA;
$bg-white: #FFFFFF;

.variable-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: $bg-pale;
}

.variable-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
}

.variable-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* Sidebar / Scope Rail */
.scope-rail {
  width: 100px;
  flex-shrink: 0;
  border-left: 1px solid $color-border;
  background: $bg-white;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 12px 8px;
  gap: 4px;
}

.scope-item {
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  color: $color-text-light;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s;
  font-weight: 500;
  
  &:hover {
    background: $bg-pale;
    color: $color-text-main;
  }
  
  &.active {
    background: $color-accent-pale; // Mint Lightest
    color: $color-primary; // Forest Green
    font-weight: 600;
  }
}

.panel-topbar {
  height: 48px; /* Slightly taller for better spacing */
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 16px;
  border-bottom: 1px solid $color-border;
  background: $bg-white;
  flex-shrink: 0;
  gap: 12px;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
}

.top-btn {
  height: 32px;
  line-height: 30px; /* Center vertical alignment */
  padding: 0 12px;
  border-radius: 6px;
  border: 1px solid $color-border;
  background: $bg-white;
  font-size: 13px;
  color: $color-text-main;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.2s;
  font-weight: 500;

  &:hover {
    border-color: $color-accent;
    color: $color-primary;
    background: $color-accent-pale;
  }
  
  &.ghost {
    border-color: transparent;
    background: transparent;
    color: $color-text-light;
    
    &:hover {
      background: $bg-pale;
      color: $color-text-main;
    }
  }
}

.variable-list {
  flex: 1;
  overflow: hidden;
  padding: 0;
  background: $bg-pale; 
}

.loading, .empty {
  text-align: center;
  color: $color-text-light;
  padding: 48px 20px;
  font-size: 13px;
}

/* Horizontal Scroll Layout */
.list-scroll {
  flex: 1;
  width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 16px;
  /* Use flex row for horizontal scrolling container */
  white-space: nowrap;
}

.list-grid {
  display: inline-flex;
  gap: 16px;
  height: 100%;
  align-items: stretch;
}

.var-card {
  background: $bg-white;
  border: 1px solid $color-border;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 260px; /* Fixed width for horizontal items */
  flex-shrink: 0;
  transition: all 0.2s cubic-bezier(0.25, 0.8, 0.25, 1);
  position: relative;
  box-shadow: 0 1px 3px rgba(0,0,0,0.04);
  /* Fixed Height independent of content */
  height: 140px; 
  overflow: hidden;
}

.var-card:hover {
  border-color: $color-accent;
  box-shadow: 0 8px 24px rgba(91, 209, 151, 0.15); /* Mint shadow */
  transform: translateY(-2px);
}

.var-card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  flex-shrink: 0;
}

.var-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.var-name {
  font-size: 14px;
  font-weight: 600;
  color: $color-text-main;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.var-creator {
  font-size: 12px;
  color: $color-text-light;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.var-time-top {
  font-size: 11px;
  color: #9aa5b1; 
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Vertical Actions Stack -> Horizontal */
.var-actions-top {
  display: flex;
  flex-direction: row; 
  gap: 4px;
  flex-shrink: 0;
  align-items: flex-start; /* Align top */
}

.var-act-btn {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  background: transparent;
  border: 1px solid transparent; 
  color: $color-text-light;
  cursor: pointer;
  font-size: 13px;
  padding: 0;
  transition: all 0.2s;
  
  &:hover {
    background: $color-accent-pale;
    color: $color-primary;
  }
  
  &.danger:hover {
    background: #FEF2F2;
    color: #DC2626;
  }
}

.var-value {
  font-size: 13px;
  color: $color-text-main;
  background: #f1f5f9;
  padding: 10px;
  border-radius: 6px;
  flex: 1;
  overflow: hidden;
  word-break: break-all;
  line-height: 1.6;
  margin-bottom: 0; /* Remove bottom margin if any */
}

/* Footer removed as requested to maximize content area */
.var-card-footer {
  display: none;
}


/* Removed old var-actions styles */

/* Modal Styles */
.modal-mask {
  position: fixed;
  left: 0; top: 0; right: 0; bottom: 0;
  background: rgba(0, 0, 0, 0.5); /* Darker mask */
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.modal {
  width: 360px;
  background: #fff;
  border-radius: 12px; /* More rounded */
  padding: 24px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
}

.modal-title { 
  font-weight: 600; 
  font-size: 16px; 
  color: $color-text-main;
  margin-bottom: 8px; 
}

.modal-subtitle { 
  font-size: 13px; 
  color: $color-text-light; 
  margin-bottom: 16px; 
}

.modal-input {
  width: 100%; 
  height: 40px;
  border: 1px solid $color-border;
  border-radius: 6px;
  padding: 0 12px; 
  font-size: 14px;
  box-sizing: border-box;
  transition: border-color 0.2s;
  
  &:focus {
    border-color: $color-accent;
    outline: none;
    box-shadow: 0 0 0 2px rgba(91, 209, 151, 0.2);
  }
}

.modal-actions { 
  display: flex; 
  justify-content: flex-end; 
  gap: 12px; 
  margin-top: 24px; 
}

.modal-btn {
  padding: 8px 16px; 
  border-radius: 6px; 
  font-size: 13px; 
  cursor: pointer;
  border: 1px solid $color-border; 
  background: #fff;
  color: $color-text-main;
  transition: all 0.2s;
  
  &:hover {
    background: $bg-pale;
  }
}

/* Inline Delete Popover */
.delete-popover {
  position: absolute;
  top: 100%;
  right: 0;
  margin-top: 8px;
  background: #fff;
  border: 1px solid $color-border;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
  padding: 8px;
  z-index: 100;
  min-width: 120px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  animation: fadeIn 0.1s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}

.pop-arrow {
  position: absolute;
  top: -4px;
  right: 10px;
  width: 8px;
  height: 8px;
  background: #fff;
  border-top: 1px solid $color-border;
  border-left: 1px solid $color-border;
  transform: rotate(45deg);
}

.pop-text {
  font-size: 12px;
  color: $color-text-main;
  text-align: center;
  font-weight: 500;
}

.pop-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.pop-btn {
  flex: 1;
  font-size: 11px;
  padding: 4px 0;
  text-align: center;
  border-radius: 4px;
  cursor: pointer;
  background: $bg-pale;
  color: $color-text-light;
  transition: all 0.2s;
  
  &:hover {
    background: #e2e8f0;
    color: $color-text-main;
  }
}

.pop-btn.danger {
  background: #FEF2F2;
  color: #DC2626;
  
  &:hover {
    background: #FEE2E2;
  }
}

.modal-btn.primary { 
  background: $color-primary; 
  color: #fff; 
  border-color: transparent; 
  
  &:hover {
    background: mix($color-primary, #000, 90%);
  }
  
  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.var-act-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}
</style>

