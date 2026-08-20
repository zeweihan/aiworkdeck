<!--
  日历日程创建/编辑弹窗。task 为 null（或无 id）时是创建态，否则是编辑态。
  创建态可选项目；编辑态项目已归属，不做迁移，只显示 + 「进入项目」跳转。
  API 调用（createTask/updateTask/deleteTask）在组件内部完成，成功后向父级
  抛 saved/deleted，父级只管重新拉取当前视图区间的数据，不重复实现请求逻辑。
-->
<template>
  <view v-if="visible" class="task-dialog-mask" @tap.self="close">
    <view class="task-dialog">
      <view class="task-dialog-header">
        <text class="task-dialog-title">{{ isEdit ? $t('calendar.editTask') : $t('calendar.createTask') }}</text>
        <text class="task-dialog-close" @tap="close">×</text>
      </view>

      <view class="task-dialog-body">
        <view class="form-row">
          <text class="form-label">{{ $t('calendar.taskTitleLabel') }}</text>
          <input
            class="form-input"
            v-model="form.title"
            :placeholder="$t('calendar.taskTitlePlaceholder')"
          />
        </view>

        <view class="form-row" v-if="!isEdit">
          <text class="form-label">{{ $t('calendar.projectLabel') }}</text>
          <AwdSelect
            class="form-select"
            :range="projectLabels"
            :value="projectIndex"
            @change="onProjectChange"
          />
        </view>
        <view class="form-row" v-else>
          <text class="form-label">{{ $t('calendar.projectLabel') }}</text>
          <text class="form-static">{{ task.projectName || '' }}</text>
        </view>

        <view class="form-row form-row-inline">
          <view class="form-col">
            <text class="form-label">{{ $t('calendar.dateLabel') }}</text>
            <input class="form-input" type="date" v-model="form.dueDate" />
          </view>
          <view class="form-col">
            <text class="form-label">{{ $t('calendar.timeLabel') }}</text>
            <input class="form-input" type="time" v-model="form.dueTime" />
          </view>
        </view>

        <view class="form-row" v-if="isEdit && task.fileName">
          <text class="form-label">{{ $t('calendar.fileLabel') }}</text>
          <text class="form-static">{{ task.fileName }}</text>
        </view>

        <view class="form-row" v-if="isEdit">
          <text class="form-label">{{ $t('calendar.statusOpen') }}/{{ $t('calendar.statusDone') }}</text>
          <button class="btn-status-toggle" :class="{ 'is-done': isDone }" @tap="toggleStatus" :disabled="busy">
            {{ isDone ? $t('calendar.markOpen') : $t('calendar.markDone') }}
          </button>
        </view>
      </view>

      <view class="task-dialog-footer">
        <button v-if="isEdit" class="btn-danger-text" @tap="handleDelete" :disabled="busy">{{ $t('calendar.delete') }}</button>
        <button v-if="isEdit" class="btn-secondary" @tap="openProject" :disabled="busy">{{ $t('calendar.openProject') }}</button>
        <view class="footer-spacer"></view>
        <button class="btn-secondary" @tap="close" :disabled="busy">{{ $t('calendar.cancel') }}</button>
        <button class="btn-primary" @tap="submit" :disabled="busy">{{ $t('calendar.save') }}</button>
      </view>
    </view>
  </view>
</template>

<script>
import AwdSelect from '@/components/AwdSelect.vue'
import { createTask, updateTask, deleteTask } from '@/services/api.js'

export default {
  name: 'TaskDialog',
  components: { AwdSelect },
  props: {
    visible: { type: Boolean, default: false },
    /** 编辑态传入完整任务对象；创建态传 null */
    task: { type: Object, default: null },
    /** 创建态预填日期（点击的日期格） */
    defaultDate: { type: String, default: '' },
    /** 项目下拉候选（创建态用） */
    projects: { type: Array, default: () => [] },
  },
  emits: ['update:visible', 'saved', 'deleted', 'open-project'],
  data() {
    return {
      form: { title: '', dueDate: '', dueTime: '' },
      projectIndex: 0,
      busy: false,
    }
  },
  computed: {
    isEdit() {
      return !!(this.task && this.task.id)
    },
    isDone() {
      return String((this.task && this.task.status) || '').toUpperCase() === 'DONE'
    },
    projectLabels() {
      return this.projects.map((p) => p.name)
    },
  },
  watch: {
    visible(v) {
      if (v) this.resetForm()
    },
  },
  methods: {
    resetForm() {
      if (this.isEdit) {
        this.form = {
          title: this.task.title || '',
          dueDate: this.task.dueDate || '',
          dueTime: this.task.dueTime || '',
        }
      } else {
        this.form = { title: '', dueDate: this.defaultDate || '', dueTime: '' }
        this.projectIndex = 0
      }
    },
    onProjectChange(i) {
      this.projectIndex = i
    },
    close() {
      if (this.busy) return
      this.$emit('update:visible', false)
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
      this.busy = true
      try {
        if (this.isEdit) {
          await updateTask(this.task.id, {
            title,
            dueDate: this.form.dueDate,
            dueTime: this.form.dueTime || null,
          })
        } else {
          const project = this.projects[this.projectIndex]
          if (!project) {
            uni.showToast({ title: this.$t('calendar.requiredProject'), icon: 'none' })
            this.busy = false
            return
          }
          await createTask({
            projectId: project.id,
            title,
            dueDate: this.form.dueDate,
            dueTime: this.form.dueTime || undefined,
          })
        }
        uni.showToast({ title: this.$t('calendar.saved'), icon: 'success' })
        this.$emit('saved')
        this.$emit('update:visible', false)
      } catch (e) {
        console.error('[calendar] 保存日程失败', e)
        uni.showToast({ title: this.$t('calendar.saveFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async toggleStatus() {
      if (this.busy || !this.isEdit) return
      this.busy = true
      try {
        const nextStatus = this.isDone ? 'OPEN' : 'DONE'
        await updateTask(this.task.id, { status: nextStatus })
        uni.showToast({ title: this.$t('calendar.saved'), icon: 'success' })
        this.$emit('saved')
        this.$emit('update:visible', false)
      } catch (e) {
        console.error('[calendar] 更新状态失败', e)
        uni.showToast({ title: this.$t('calendar.saveFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    handleDelete() {
      if (this.busy || !this.isEdit) return
      uni.showModal({
        title: this.$t('calendar.deleteConfirmTitle'),
        content: this.$t('calendar.deleteConfirmContent', { title: this.task.title || '' }),
        cancelText: this.$t('calendar.cancel'),
        confirmText: this.$t('calendar.delete'),
        success: async (res) => {
          if (!res.confirm) return
          this.busy = true
          try {
            await deleteTask(this.task.id)
            uni.showToast({ title: this.$t('calendar.deleted'), icon: 'success' })
            this.$emit('deleted')
            this.$emit('update:visible', false)
          } catch (e) {
            console.error('[calendar] 删除日程失败', e)
            uni.showToast({ title: this.$t('calendar.deleteFailed'), icon: 'none' })
          } finally {
            this.busy = false
          }
        },
      })
    },
    openProject() {
      if (!this.isEdit) return
      this.$emit('open-project', this.task.projectId)
    },
  },
}
</script>

<style lang="scss" scoped>
.task-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 5000;
  background: rgba(33, 38, 41, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
}

.task-dialog {
  width: 420px;
  max-width: calc(100vw - 48px);
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 12px 40px rgba(18, 52, 77, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.task-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #E9ECEF;
}

.task-dialog-title {
  font-size: 15px;
  font-weight: 600;
  color: #2C3338;
}

.task-dialog-close {
  font-size: 18px;
  line-height: 1;
  color: #ADB5BD;
  cursor: pointer;

  &:hover { color: #2C3338; }
}

.task-dialog-body {
  padding: 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-row-inline {
  flex-direction: row;
  gap: 12px;
}

.form-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.form-label {
  font-size: 12px;
  color: #6C757D;
}

.form-input {
  height: 36px;
  padding: 0 12px;
  border: 1px solid #E6EAE8;
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  color: #212629;
  box-sizing: border-box;

  &:focus { border-color: #5BD197; outline: none; }
}

.form-select {
  width: 100%;
}

.form-static {
  font-size: 13px;
  color: #2C3338;
  padding: 8px 0;
}

.btn-status-toggle {
  align-self: flex-start;
  background: rgba(26, 83, 54, 0.08);
  color: #1A5336;
  font-size: 13px;
  font-weight: 500;
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid rgba(26, 83, 54, 0.1);

  &.is-done {
    background: #F1F3F5;
    color: #6C757D;
    border-color: #E9ECEF;
  }
}

.task-dialog-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #E9ECEF;
}

.footer-spacer {
  flex: 1;
}

.btn-secondary {
  background: #fff;
  color: #6C757D;
  font-size: 13px;
  font-weight: 500;
  padding: 7px 14px;
  border-radius: 6px;
  border: 1px solid #E9ECEF;

  &:hover { border-color: #1A5336; color: #1A5336; }
}

.btn-primary {
  background: #1A5336;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  padding: 7px 16px;
  border-radius: 6px;
  border: 1px solid #1A5336;

  &:hover { background: #164429; }
}

.btn-danger-text {
  background: transparent;
  color: #E74C3C;
  font-size: 13px;
  font-weight: 500;
  padding: 7px 10px;
  border: none;

  &:hover { text-decoration: underline; }
}
</style>
