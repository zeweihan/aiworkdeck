<template>
  <div class="app-shell">
    <!-- Office 宿主已按 manifest 的 DisplayName 自绘一条标题，这里不再重复品牌名 -->
    <header class="app-header">
      <!-- 只有一个项目（含懒建的「插件临时项目」）时没什么可选，直接不渲染下拉 -->
      <select
        v-if="view === 'chat' && projects.length > 1"
        class="project-select"
        :value="projectId"
        @change="onProjectChange($event.target.value)"
      >
        <option value="" disabled>选择项目</option>
        <option v-for="p in projects" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
      </select>
      <span class="header-spacer"></span>
      <button class="icon-btn" :title="view === 'chat' ? '设置' : '返回对话'" @click="toggleView">
        {{ view === 'chat' ? '设置' : '返回' }}
      </button>
    </header>

    <main class="app-main">
      <SettingsView
        v-if="view === 'settings'"
        :initial-server-url="settings.serverUrl"
        :initial-token="settings.token"
        @saved="onSettingsSaved"
      />
      <ChatView
        v-else
        :settings="settings"
        :project-id="projectId"
        :configured="configured"
        @need-settings="view = 'settings'"
      />
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import SettingsView from './components/SettingsView.vue'
import ChatView from './components/ChatView.vue'
import { loadSettings, saveProjectId, isConfigured } from './lib/settings.js'
import { fetchMyProjects, ensureAddinDefaultProject } from './lib/api.js'

const settings = reactive(loadSettings())
const configured = computed(() => isConfigured(settings))
const view = ref(configured.value ? 'chat' : 'settings')
const projects = ref([])
const projectId = ref(settings.projectId || '')

/**
 * 项目不是插件的必选项：
 * - 有多个项目：照旧由用户在下拉里选；
 * - 只有一个项目：直接选中它（没什么可选，下拉也不渲染）；
 * - 一个都没有：让后端懒建「插件临时项目」并静默选中，用户无感。
 *   旧后端没有该端点时降级为现状（空态提示去选项目），不报错。
 */
async function refreshProjects() {
  if (!configured.value) return
  try {
    const list = await fetchMyProjects(settings)
    projects.value = list
    // 记住的项目已不存在时清空选择
    if (projectId.value && !list.some(p => String(p.id) === projectId.value)) {
      projectId.value = ''
      saveProjectId('')
    }
    if (list.length === 1) {
      onProjectChange(String(list[0].id))
      return
    }
    if (list.length) return

    const created = await ensureAddinDefaultProject(settings)
    if (created) {
      projects.value = [created]
      onProjectChange(String(created.id))
    }
  } catch (e) {
    console.warn('[Addin] 项目列表拉取失败', e)
  }
}

function onProjectChange(id) {
  projectId.value = id
  saveProjectId(id)
}

function toggleView() {
  view.value = view.value === 'chat' ? 'settings' : 'chat'
}

function onSettingsSaved({ serverUrl, token }) {
  settings.serverUrl = serverUrl
  settings.token = token
  view.value = 'chat'
  refreshProjects()
}

onMounted(refreshProjects)
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 3px 8px;
  background: var(--awd-surface);
  border-bottom: 1px solid var(--awd-border);
  flex-shrink: 0;
}

.header-spacer {
  flex: 1;
  min-width: 0;
}

.project-select {
  max-width: 160px;
  padding: 3px 6px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  background: var(--awd-surface);
}

.icon-btn {
  padding: 3px 10px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
}

.icon-btn:hover {
  color: var(--awd-primary);
  border-color: var(--awd-primary);
}

.app-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
