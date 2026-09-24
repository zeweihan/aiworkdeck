<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<!--
  TaskDialog — 全产品唯一的事项弹窗（dev-board#896 重做，spec 2026-09-25-task-calendar-redesign 第二节）。

  宿主 API：
    props  visible / mode('create'|'edit'，缺省按 task.id 推断) / task(编辑用) /
           projectId(锁定项目) / projects(全局创建时可选的项目) / presetFileIds / presetDate / presetTime
    emits  saved(task) / deleted(task) / open-project(task) / open-file({task, fileId}) / close
  兼容旧用法：仍 emit update:visible(false)（v-model:visible），仍认 defaultDate（= presetDate）。

  写操作一律走 utils/taskStore（就地更新缓存并广播），宿主不必再自己重拉。
  拿到项目（锁定、全局创建时选中、或编辑态事项所属）后，弹窗自己拉项目文件（@ 选择器与
  「添加文件」用，口径同 QuickOpenPanel：扁平清单、剔除系统文件夹、只要文件）与成员（负责人）。

  键盘：Esc 关闭（@ 选择器开着时只关选择器、文件选择框开着时只关文件选择框）；
  Ctrl/Cmd+Enter 保存；标题框里 Enter 也保存。监听挂在 window 捕获段，工作台快捷键吃不到。
  保存中禁止重复提交。
-->
<template>
  <view v-if="visible" class="task-dialog-mask" @tap.self="close">
    <view class="task-dialog" role="dialog" aria-modal="true" @tap.stop>
      <view class="td-header">
        <text class="td-title">{{ isEdit ? $t('calendar.dialogEditTitle') : $t('calendar.dialogCreateTitle') }}</text>
        <view class="td-header-right">
          <view
            v-if="isEdit"
            class="td-status"
            :class="{ 'is-done': form.done }"
            role="checkbox"
            :aria-checked="form.done ? 'true' : 'false'"
            @tap="form.done = !form.done"
          >
            <view class="td-status-box">
              <svg viewBox="0 0 24 24" fill="none"><path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" /></svg>
            </view>
            <text>{{ $t('calendar.doneLabel') }}</text>
          </view>
          <text class="td-close" :title="$t('calendar.cancel')" @tap="close">×</text>
        </view>
      </view>

      <view class="td-body">
        <!-- 1. 标题 -->
        <MentionInput
          ref="titleInput"
          class="td-title-input"
          v-model="form.title"
          :placeholder="$t('calendar.titlePlaceholder')"
          :files="mentionFiles"
          :members="members"
          :loading="filesLoading"
          :maxlength="200"
          @mention-file="addFile"
          @mention-member="setAssignee"
          @submit="submit"
        />

        <!-- 2. 类型 + 重要 -->
        <view class="td-row td-type-row">
          <view class="td-types" role="radiogroup" :aria-label="$t('calendar.typeLabel')">
            <view
              v-for="m in typeOptions"
              :key="m.key"
              class="td-type-chip"
              :class="{ 'is-active': form.type === m.key }"
              :style="form.type === m.key ? { borderColor: m.color, background: m.soft } : null"
              role="radio"
              :aria-checked="form.type === m.key ? 'true' : 'false'"
              @tap="onTypeChange(m.key)"
            >
              <svg class="td-type-icon" viewBox="0 0 24 24" fill="none" :style="{ color: m.color }"><path v-for="(d, i) in m.icon" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
              <text>{{ m.label }}</text>
            </view>
          </view>
          <view class="td-priority">
            <text class="td-priority-label">{{ $t('calendar.priorityHigh') }}</text>
            <AwdSwitch :checked="form.high" @change="(v) => (form.high = v)" />
          </view>
        </view>

        <!-- 3. 日期 | 时间 | 提醒 -->
        <view class="td-row td-cols">
          <view class="td-col">
            <text class="td-label">{{ $t('calendar.dateLabel') }}</text>
            <AwdDatePicker type="date" v-model="form.dueDate" />
          </view>
          <view class="td-col">
            <text class="td-label">{{ $t('calendar.timeField') }}</text>
            <view class="td-time">
              <AwdDatePicker class="td-time-picker" type="time" v-model="form.dueTime" :placeholder="$t('calendar.timePlaceholder')" />
              <text v-if="form.dueTime" class="td-time-clear" :title="$t('calendar.timeClear')" @tap="form.dueTime = ''">×</text>
            </view>
          </view>
          <view class="td-col">
            <text class="td-label">{{ $t('calendar.remindLabel') }}</text>
            <AwdSelect :range="remindLabels" :value="remindIndex" @change="onRemindChange" />
          </view>
        </view>

        <!-- 4. 项目（仅全局创建）| 负责人 -->
        <view class="td-row td-cols">
          <view v-if="showProjectSelect" class="td-col">
            <text class="td-label">{{ $t('calendar.projectLabel') }}</text>
            <AwdSelect
              :range="projectLabels"
              :value="projectIndex"
              @change="onProjectChange"
            />
          </view>
          <view v-else-if="projectName" class="td-col">
            <text class="td-label">{{ $t('calendar.projectLabel') }}</text>
            <text class="td-static">{{ projectName }}</text>
          </view>
          <view class="td-col">
            <text class="td-label">{{ $t('calendar.assigneeLabel') }}</text>
            <AwdSelect
              :range="assigneeLabels"
              :value="assigneeIndex"
              :disabled="!effectiveProjectId"
              @change="onAssigneeChange"
            />
          </view>
        </view>

        <!-- 5. 关联文件 -->
        <view class="td-row">
          <text class="td-label">{{ $t('calendar.filesLabel') }}</text>
          <view class="td-files">
            <view
              v-for="f in fileChips"
              :key="f.fileId"
              class="td-file-chip"
              :class="{ 'is-missing': f.fileName == null }"
            >
              <text
                class="td-file-name"
                :title="f.fileName == null ? $t('calendar.fileMissing') : f.fileName"
                @tap="openFile(f.fileId)"
              >{{ f.fileName == null ? $t('calendar.fileMissing') : f.fileName }}</text>
              <text class="td-file-remove" :title="$t('calendar.removeFile')" @tap="removeFile(f.fileId)">×</text>
            </view>
            <view
              class="td-add-file"
              :class="{ 'is-disabled': !effectiveProjectId }"
              :title="effectiveProjectId ? '' : $t('calendar.pickProjectFirst')"
              @tap="openFilePicker"
            >
              <text>+ {{ $t('calendar.addFile') }}</text>
            </view>
          </view>
        </view>

        <!-- 6. 备注 -->
        <view class="td-row">
          <text class="td-label">{{ $t('calendar.notesLabel') }}</text>
          <MentionInput
            v-model="form.notes"
            multiline
            :rows="3"
            :maxlength="4000"
            :placeholder="$t('calendar.notesPlaceholder')"
            :files="mentionFiles"
            :members="members"
            :loading="filesLoading"
            @mention-file="addFile"
            @mention-member="setAssignee"
          />
        </view>
      </view>

      <!-- 7. 页脚 -->
      <view class="td-footer">
        <view v-if="isEdit" class="td-btn td-btn-danger" :class="{ 'is-disabled': busy }" @tap="handleDelete">{{ $t('calendar.delete') }}</view>
        <view v-if="isEdit && fileChips.length" class="td-btn td-btn-link" @tap="openFile(firstOpenableFileId)">{{ $t('calendar.openFile') }}</view>
        <view v-if="isEdit" class="td-btn td-btn-link" @tap="openProject">{{ $t('calendar.openProject') }}</view>
        <view class="td-spacer"></view>
        <text class="td-hint">{{ $t('calendar.saveShortcutHint') }}</text>
        <view class="td-btn td-btn-secondary" @tap="close">{{ $t('calendar.cancel') }}</view>
        <view class="td-btn td-btn-primary" :class="{ 'is-disabled': busy }" @tap="submit">{{ busy ? $t('calendar.saving') : $t('calendar.save') }}</view>
      </view>
    </view>

    <FilePickerDialog
      v-if="effectiveProjectId"
      :visible="filePickerOpen"
      :project-id="effectiveProjectId"
      :title="$t('calendar.filePickerTitle')"
      @update:visible="(v) => (filePickerOpen = v)"
      @confirm="addFile"
    />
  </view>
</template>

<script>
import AwdSelect from '@/components/AwdSelect.vue'
import AwdDatePicker from '@/components/AwdDatePicker.vue'
import AwdSwitch from '@/components/AwdSwitch.vue'
import FilePickerDialog from '@/components/FilePickerDialog.vue'
import MentionInput from '@/components/calendar/MentionInput.vue'
import { getProjectFiles, getProjectMembers } from '@/services/api.js'
import { createTask, updateTask, deleteTask } from '@/utils/taskStore.js'
import { dirLabelOf, excludeSystemFolders } from '@/utils/aiContextFiles.js'
import { writableProjects } from '@/utils/personalCollections.js'
import { roleLabel } from '@/config/memberRoles.js'
import { memberName } from '@/components/calendar/mentionText.js'
import {
  TASK_TYPES,
  isDone,
  isHigh,
  normalizeType,
  typeMeta,
  remindOptionsFor,
  remindLabelKey,
  defaultRemindFor,
  taskFiles,
  timeOf,
} from '@/components/calendar/taskUtils.js'

const CLIENT_ROLES = new Set(['CLIENT', 'CLIENT_NAMED', 'CLIENT_GENERIC'])

function emptyForm() {
  return {
    title: '',
    type: 'DEADLINE',
    high: false,
    dueDate: '',
    dueTime: '',
    remindBefore: null,
    projectId: null,
    assigneeId: null,
    notes: '',
    fileIds: [],
    done: false,
  }
}

export default {
  name: 'TaskDialog',
  components: { AwdSelect, AwdDatePicker, AwdSwitch, FilePickerDialog, MentionInput },
  props: {
    visible: { type: Boolean, default: false },
    /** 'create' | 'edit'；缺省时按 task.id 推断 */
    mode: { type: String, default: '' },
    /** 编辑态传入完整事项 */
    task: { type: Object, default: null },
    /** 锁定项目（工作台、文件右键、概览页）；给了就不显示项目下拉 */
    projectId: { type: [Number, String], default: null },
    /** 全局创建时可选的项目 [{id, name, myRole?}]，有 myRole 时只留可写的 */
    projects: { type: Array, default: () => [] },
    /** 预置关联文件 id（文件右键「添加事项…」） */
    presetFileIds: { type: Array, default: () => [] },
    /** 预置日期 YYYY-MM-DD（日程页点空白日） */
    presetDate: { type: String, default: '' },
    /** 预置时间 HH:mm（周视图点时段） */
    presetTime: { type: String, default: '' },
    /** 旧名，等同 presetDate（日程页重做前的调用方） */
    defaultDate: { type: String, default: '' },
  },
  emits: ['saved', 'deleted', 'open-project', 'open-file', 'close', 'update:visible'],
  data() {
    return {
      form: emptyForm(),
      busy: false,
      files: [],
      filesLoading: false,
      members: [],
      loadedProjectId: null,
      filePickerOpen: false,
      /** create 模式下用户是否手动改过提醒；改过之后切类型不再跟着变默认值 */
      remindTouched: false,
      /** 已知文件名：fileId(字符串) → 名字（null = 已删除） */
      knownNames: {},
    }
  },
  computed: {
    isEdit() {
      if (this.mode) return this.mode === 'edit'
      return !!(this.task && this.task.id)
    },
    selectableProjects() {
      const list = Array.isArray(this.projects) ? this.projects : []
      // 调用方给了角色就按角色滤；只给 {id,name} 的旧调用方原样用（后端仍会做写权限校验）
      return list.some((p) => p && p.myRole) ? writableProjects(list) : list.filter(Boolean)
    },
    showProjectSelect() {
      return !this.isEdit && (this.projectId === null || this.projectId === '')
    },
    effectiveProjectId() {
      if (this.isEdit) return this.task ? this.task.projectId : null
      if (this.projectId !== null && this.projectId !== '') return this.projectId
      return this.form.projectId
    },
    projectName() {
      if (this.isEdit) return (this.task && this.task.projectName) || ''
      const hit = this.selectableProjects.find((p) => String(p.id) === String(this.effectiveProjectId))
      return hit ? hit.name : ''
    },
    projectLabels() {
      return this.selectableProjects.length
        ? this.selectableProjects.map((p) => p.name)
        : [this.$t('calendar.noWritableProject')]
    },
    projectIndex() {
      const i = this.selectableProjects.findIndex((p) => String(p.id) === String(this.form.projectId))
      return i < 0 ? 0 : i
    },
    typeOptions() {
      return TASK_TYPES.map((k) => typeMeta(k, (key) => this.$t(key)))
    },
    remindValues() {
      return remindOptionsFor(!!this.form.dueTime)
    },
    remindLabels() {
      return this.remindValues.map((v) => this.$t(remindLabelKey(v)))
    },
    remindIndex() {
      const i = this.remindValues.findIndex((v) => v === this.form.remindBefore)
      return i < 0 ? 0 : i
    },
    assigneeOptions() {
      const list = this.members.slice()
      // 编辑态的负责人可能已不在成员表里（被移出项目），仍要能显示出来
      const cur = this.form.assigneeId
      if (cur != null && !list.some((m) => String(m.userId) === String(cur)) && this.task && this.task.assigneeName) {
        list.push({ userId: cur, displayName: this.task.assigneeName })
      }
      return list
    },
    assigneeLabels() {
      return [this.$t('calendar.unassigned')].concat(this.assigneeOptions.map((m) => memberName(m)))
    },
    assigneeIndex() {
      if (this.form.assigneeId == null) return 0
      const i = this.assigneeOptions.findIndex((m) => String(m.userId) === String(this.form.assigneeId))
      return i < 0 ? 0 : i + 1
    },
    mentionFiles() {
      return this.files
    },
    fileChips() {
      return this.form.fileIds.map((id) => {
        const key = String(id)
        const name = Object.prototype.hasOwnProperty.call(this.knownNames, key) ? this.knownNames[key] : undefined
        return { fileId: id, fileName: name === undefined ? '#' + key : name }
      })
    },
    firstOpenableFileId() {
      const hit = this.fileChips.find((f) => f.fileName != null)
      return hit ? hit.fileId : null
    },
  },
  watch: {
    visible: {
      immediate: true,
      handler(v) {
        if (v) this.onOpen()
        else this.onHide()
      },
    },
    effectiveProjectId(pid) {
      if (this.visible) this.loadProjectData(pid)
    },
    'form.dueTime'(t) {
      // 清掉时间 = 变全天；全天没有「提前 30 分 / 1 小时」，退成「准时」（当天 9:00）
      if (!t && (this.form.remindBefore === 30 || this.form.remindBefore === 60)) this.form.remindBefore = 0
    },
  },
  beforeUnmount() {
    this.detachKeys()
  },
  methods: {
    onOpen() {
      this.resetForm()
      this.attachKeys()
      this.loadProjectData(this.effectiveProjectId)
      this.$nextTick(() => {
        const input = this.$refs.titleInput
        if (input && input.focus) input.focus()
      })
    },
    onHide() {
      this.detachKeys()
      // 下次打开重新拉文件与成员（期间可能新增了文件）
      this.loadedProjectId = null
      this.filePickerOpen = false
      this.busy = false
    },
    resetForm() {
      const form = emptyForm()
      const names = {}
      if (this.isEdit && this.task) {
        const t = this.task
        form.title = t.title || ''
        form.type = normalizeType(t.type)
        form.high = isHigh(t)
        form.dueDate = t.dueDate || ''
        form.dueTime = timeOf(t)
        form.remindBefore = t.remindBefore === undefined || t.remindBefore === '' ? null : t.remindBefore
        form.assigneeId = t.assigneeId == null ? null : t.assigneeId
        form.notes = t.notes || ''
        form.done = isDone(t)
        for (const f of taskFiles(t)) {
          form.fileIds.push(f.fileId)
          names[String(f.fileId)] = f.fileName
        }
      } else {
        form.remindBefore = defaultRemindFor(form.type)
        form.dueDate = this.presetDate || this.defaultDate || ''
        form.dueTime = this.presetTime || ''
        if (this.showProjectSelect) {
          const first = this.selectableProjects[0]
          form.projectId = first ? first.id : null
        }
        for (const id of Array.isArray(this.presetFileIds) ? this.presetFileIds : []) {
          if (id != null && !form.fileIds.some((x) => String(x) === String(id))) form.fileIds.push(id)
        }
      }
      this.form = form
      this.remindTouched = false
      this.knownNames = names
    },
    async loadProjectData(pid) {
      if (pid === null || pid === undefined || pid === '') {
        this.files = []
        this.members = []
        this.loadedProjectId = null
        return
      }
      // 同一次打开里同一个项目只拉一次（onOpen 与 effectiveProjectId 的 watcher 会先后进来）
      if (String(this.loadedProjectId) === String(pid)) return
      this.loadedProjectId = pid
      this.filesLoading = true
      const [filesRes, membersRes] = await Promise.allSettled([getProjectFiles(pid), getProjectMembers(pid)])
      // 请求期间切了项目：丢弃旧结果
      if (String(this.loadedProjectId) !== String(pid)) return
      this.filesLoading = false
      if (filesRes.status === 'fulfilled') {
        const resp = filesRes.value
        const all = excludeSystemFolders(Array.isArray(resp) ? resp : (resp && resp.data) || [])
        const byId = new Map(all.map((f) => [f.id, f]))
        this.files = all
          .filter((f) => f && f.id != null && !f.isFolder && !f.isDir && !f.isDeleted)
          .map((f) => ({ id: f.id, name: f.name, parentId: f.parentId, isDir: false, kind: 'file', dirLabel: dirLabelOf(f, byId) }))
          .sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh'))
        const names = { ...this.knownNames }
        for (const f of this.files) {
          const key = String(f.id)
          if (names[key] === undefined) names[key] = f.name
        }
        this.knownNames = names
      } else {
        console.warn('[TaskDialog] 加载项目文件失败', filesRes.reason)
        this.files = []
      }
      if (membersRes.status === 'fulfilled') {
        const raw = (membersRes.value && membersRes.value.data) || []
        const seen = new Set()
        this.members = (Array.isArray(raw) ? raw : [])
          .filter((m) => {
            if (!m || m.userId == null || CLIENT_ROLES.has(m.role) || seen.has(String(m.userId))) return false
            seen.add(String(m.userId))
            return true
          })
          .map((m) => ({ ...m, roleLabel: roleLabel(m.role) }))
      } else {
        console.warn('[TaskDialog] 加载项目成员失败', membersRes.reason)
        this.members = []
      }
    },
    onProjectChange(i) {
      const p = this.selectableProjects[i]
      if (!p || String(p.id) === String(this.form.projectId)) return
      // 文件与负责人都属于项目：换项目就清掉
      this.form.projectId = p.id
      this.form.assigneeId = null
      this.form.fileIds = []
    },
    onTypeChange(type) {
      this.form.type = type
      // 新建时提醒默认值跟类型走（截止日/开庭提前 1 天，其余不提醒），用户手动改过就不再跟；编辑态不动
      if (!this.isEdit && !this.remindTouched) this.form.remindBefore = defaultRemindFor(type)
    },
    onRemindChange(i) {
      this.remindTouched = true
      this.form.remindBefore = this.remindValues[i] === undefined ? null : this.remindValues[i]
    },
    onAssigneeChange(i) {
      this.form.assigneeId = i === 0 ? null : this.assigneeOptions[i - 1].userId
    },
    setAssignee(member) {
      if (member && member.userId != null) this.form.assigneeId = member.userId
    },
    addFile(file) {
      if (!file || file.id == null) return
      if (file.isFolder || file.isDir || file.fileType === 'folder') return
      const key = String(file.id)
      if (!this.form.fileIds.some((x) => String(x) === key)) this.form.fileIds.push(file.id)
      this.knownNames = { ...this.knownNames, [key]: file.name }
    },
    removeFile(fileId) {
      this.form.fileIds = this.form.fileIds.filter((x) => String(x) !== String(fileId))
    },
    openFilePicker() {
      if (!this.effectiveProjectId) {
        uni.showToast({ title: this.$t('calendar.pickProjectFirst'), icon: 'none' })
        return
      }
      this.filePickerOpen = true
    },
    openFile(fileId) {
      if (fileId == null) return
      const name = this.knownNames[String(fileId)]
      if (name === null) return
      this.$emit('open-file', { task: this.currentTask(), fileId })
    },
    openProject() {
      this.$emit('open-project', this.currentTask())
    },
    currentTask() {
      if (this.task && this.isEdit) return this.task
      return { projectId: this.effectiveProjectId, projectName: this.projectName }
    },
    close() {
      if (this.busy) return
      this.$emit('close')
      this.$emit('update:visible', false)
    },
    buildBody() {
      const title = (this.form.title || '').trim()
      const notes = (this.form.notes || '').trim()
      const body = {
        title,
        type: this.form.type,
        priority: this.form.high ? 'HIGH' : 'NORMAL',
        dueDate: this.form.dueDate,
        dueTime: this.form.dueTime || null,
        remindBefore: this.form.remindBefore,
        assigneeId: this.form.assigneeId,
        notes: notes || null,
        fileIds: this.form.fileIds.slice(),
      }
      if (this.isEdit) {
        body.status = this.form.done ? 'DONE' : 'OPEN'
        return body
      }
      // 新建：空值不传，交给后端默认
      body.projectId = this.effectiveProjectId
      for (const k of ['dueTime', 'remindBefore', 'assigneeId', 'notes']) {
        if (body[k] === null) delete body[k]
      }
      if (!body.fileIds.length) delete body.fileIds
      return body
    },
    async submit() {
      if (this.busy) return
      const title = (this.form.title || '').trim()
      if (!title) {
        uni.showToast({ title: this.$t('calendar.requiredTitle'), icon: 'none' })
        return
      }
      if (!this.form.dueDate) {
        uni.showToast({ title: this.$t('calendar.requiredDate'), icon: 'none' })
        return
      }
      if (!this.isEdit && !this.effectiveProjectId) {
        uni.showToast({ title: this.$t('calendar.requiredProject'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        const body = this.buildBody()
        const saved = this.isEdit
          ? await updateTask(this.task.id, body)
          : await createTask(body, { projectName: this.projectName })
        uni.showToast({ title: this.$t('calendar.saved'), icon: 'success' })
        this.$emit('saved', saved)
        this.busy = false
        this.close()
      } catch (e) {
        console.error('[TaskDialog] 保存事项失败', e)
        uni.showToast({ title: (e && e.message) || this.$t('calendar.saveFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    handleDelete() {
      if (this.busy || !this.isEdit) return
      const task = this.task
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title: task.title || '' }),
        cancelText: this.$t('calendar.cancel'),
        confirmText: this.$t('calendar.delete'),
        success: async (res) => {
          if (!res.confirm) return
          this.busy = true
          try {
            await deleteTask(task.id)
            uni.showToast({ title: this.$t('calendar.deleted'), icon: 'success' })
            this.$emit('deleted', task)
            this.busy = false
            this.close()
          } catch (e) {
            console.error('[TaskDialog] 删除事项失败', e)
            uni.showToast({ title: this.$t('calendar.deleteFailed'), icon: 'none' })
          } finally {
            this.busy = false
          }
        },
      })
    },
    attachKeys() {
      if (this._keyHandler || typeof window === 'undefined') return
      this._keyHandler = (e) => {
        if (!this.visible) return
        const target = e.target
        // @ 选择器开着：交给 MentionInput 自己的捕获监听（它只关选择器）
        if (target && target.closest && target.closest('.mention-input.is-picking')) return
        if (e.key === 'Escape') {
          // 全局确认框（删除确认）开着时让它先处理
          if (document.querySelector('.awd-dlg-mask')) return
          e.preventDefault()
          e.stopPropagation()
          if (this.filePickerOpen) this.filePickerOpen = false
          else this.close()
          return
        }
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
          if (this.filePickerOpen) return
          e.preventDefault()
          e.stopPropagation()
          this.submit()
        }
      }
      window.addEventListener('keydown', this._keyHandler, true)
    },
    detachKeys() {
      if (!this._keyHandler) return
      window.removeEventListener('keydown', this._keyHandler, true)
      this._keyHandler = null
    },
  },
}
</script>

<style lang="scss" scoped>
.task-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 5000;
  background: var(--awd-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-dialog {
  width: 560px;
  max-width: calc(100vw - 48px);
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 10px;
  box-shadow: var(--awd-shadow-lg);
  display: flex;
  flex-direction: column;
}

.td-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.td-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-text);
}

.td-header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.td-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--awd-text-2);
  cursor: pointer;

  &.is-done {
    color: var(--awd-accent-text);
  }
}

.td-status-box {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1.5px solid var(--awd-border-strong);
  color: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  transition: background 0.15s ease, border-color 0.15s ease;

  svg {
    width: 10px;
    height: 10px;
  }
}

.td-status.is-done .td-status-box {
  background: var(--awd-accent);
  border-color: var(--awd-accent);
  color: var(--awd-text-on-accent);
}

.td-close {
  font-size: 18px;
  line-height: 1;
  color: var(--awd-text-3);
  cursor: pointer;

  &:hover {
    color: var(--awd-text);
  }
}

.td-body {
  padding: 16px 20px 6px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.td-title-input :deep(.mi-textarea) {
  font-size: 15px;
  font-weight: 500;
}

.td-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.td-type-row {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.td-types {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.td-type-chip {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--awd-border);
  border-radius: 14px;
  font-size: 12px;
  color: var(--awd-text-2);
  background: var(--awd-surface);
  cursor: pointer;
  box-sizing: border-box;
  transition: border-color 0.12s ease, background 0.12s ease;

  &:hover {
    border-color: var(--awd-border-strong);
  }

  &.is-active {
    color: var(--awd-text);
    font-weight: 500;
  }
}

.td-type-icon {
  width: 13px;
  height: 13px;
}

.td-priority {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.td-priority-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.td-cols {
  flex-direction: row;
  gap: 12px;
}

.td-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.td-label {
  font-size: 12px;
  color: var(--awd-text-2);
}

.td-static {
  height: 36px;
  line-height: 36px;
  font-size: 13px;
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.td-time {
  position: relative;
}

.td-time-clear {
  position: absolute;
  right: 30px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  line-height: 1;
  color: var(--awd-text-3);
  cursor: pointer;

  &:hover {
    color: var(--awd-text);
  }
}

.td-files {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.td-file-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  max-width: 240px;
  height: 26px;
  padding: 0 4px 0 9px;
  border: 1px solid var(--awd-border);
  border-radius: 5px;
  background: var(--awd-surface-2);
  font-size: 12px;
  box-sizing: border-box;

  &.is-missing .td-file-name {
    color: var(--awd-text-3);
    text-decoration: line-through;
    cursor: default;
  }
}

.td-file-name {
  color: var(--awd-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;

  &:hover {
    color: var(--awd-accent-text);
  }
}

.td-file-remove {
  flex: none;
  width: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--awd-text-3);
  cursor: pointer;

  &:hover {
    color: var(--awd-danger-text);
  }
}

.td-add-file {
  display: flex;
  align-items: center;
  height: 26px;
  padding: 0 10px;
  border: 1px dashed var(--awd-border-strong);
  border-radius: 5px;
  font-size: 12px;
  color: var(--awd-text-2);
  cursor: pointer;
  box-sizing: border-box;

  &:hover {
    color: var(--awd-accent-text);
    border-color: var(--awd-accent);
  }

  &.is-disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

.td-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  margin-top: 10px;
  border-top: 1px solid var(--awd-border-subtle);
}

.td-spacer {
  flex: 1;
}

.td-hint {
  font-size: 11px;
  color: var(--awd-text-3);
  margin-right: 4px;
}

.td-btn {
  font-size: 13px;
  font-weight: 500;
  padding: 6px 14px;
  border-radius: 6px;
  border: 1px solid transparent;
  cursor: pointer;
  white-space: nowrap;

  &.is-disabled {
    opacity: 0.55;
    pointer-events: none;
  }
}

.td-btn-secondary {
  background: var(--awd-surface);
  color: var(--awd-text-2);
  border-color: var(--awd-border);

  &:hover {
    border-color: var(--awd-accent);
    color: var(--awd-accent-text);
  }
}

.td-btn-primary {
  background: var(--awd-accent);
  color: var(--awd-text-on-accent);
  border-color: var(--awd-accent);

  &:hover {
    background: var(--awd-accent-hover);
  }
}

.td-btn-danger {
  padding: 6px 8px;
  color: var(--awd-danger-text);

  &:hover {
    background: var(--awd-danger-soft);
  }
}

.td-btn-link {
  padding: 6px 8px;
  color: var(--awd-text-2);

  &:hover {
    color: var(--awd-accent-text);
    background: var(--awd-accent-wash);
  }
}
</style>
