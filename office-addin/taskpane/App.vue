<template>
  <!-- :key=langKey：语言切换时整树重挂载，让模板里的 t() 全部重新求值（会话态在模块级 store，不丢） -->
  <div class="app-shell" :key="langKey">
    <!-- Office 宿主已按 manifest 的 DisplayName 自绘一条标题，这里不重复品牌名，只放 Logo 图形 -->
    <header class="app-header glass">
      <img class="brand-logo" :src="logoSrc" alt="AI WorkDeck" />
      <!-- 项目归属必须一直可见且可切换（dev-board#148/#173）：只要有项目就渲染下拉，
           单项目也渲染——旧版单项目只给只读名牌，用户以为不能选择项目 -->
      <select
        v-if="view === 'chat' && projects.length"
        class="project-select"
        :value="projectId"
        :title="currentProjectName ? t('currentProjectTitle', { name: currentProjectName }) : t('selectProject')"
        @change="onProjectChange($event.target.value)"
      >
        <option value="" disabled>{{ t('selectProject') }}</option>
        <option v-for="p in projects" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
      </select>
      <span class="header-spacer"></span>
      <!-- 语言切换（dev-board#177）：未登录也要能切，所以放头部而不是账户菜单里 -->
      <button
        class="icon-btn lang-btn"
        :title="t('languageLabel')"
        @click="toggleLang"
      >{{ langBtnLabel }}</button>
      <template v-if="view === 'chat'">
        <!-- 右上角是登录入口，不是设置（dev-board#176）：设置里剩下的只有连接这一件事 -->
        <button v-if="!configured" class="login-btn" @click="view = 'settings'">{{ t('login') }}</button>
        <button
          v-else
          class="avatar-btn"
          :title="me && (me.displayName || me.username) ? (me.displayName || me.username) : t('accountTitle')"
          @click="accountOpen = !accountOpen"
        >
          <img v-if="me && me.avatarUrl" class="avatar-img" :src="me.avatarUrl" alt="" />
          <span v-else class="avatar-initial">{{ avatarInitial }}</span>
        </button>
      </template>
      <button v-else class="icon-btn" @click="view = 'chat'">{{ t('back') }}</button>
    </header>

    <!-- 账户菜单：毛玻璃弹层 -->
    <div v-if="accountOpen" class="account-overlay" @click.self="accountOpen = false">
      <div ref="accountMenuEl" class="account-menu glass">
        <div class="account-head">
          <div class="account-avatar">
            <img v-if="me && me.avatarUrl" class="avatar-img" :src="me.avatarUrl" alt="" />
            <span v-else class="avatar-initial">{{ avatarInitial }}</span>
          </div>
          <div class="account-name">{{ me && (me.displayName || me.username) ? (me.displayName || me.username) : t('accountTitle') }}</div>
        </div>
        <button class="menu-item" @click="openSettings">{{ t('connectionSettings') }}</button>
        <button class="menu-item danger" @click="logout">{{ t('logout') }}</button>
      </div>
    </div>

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
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import SettingsView from './components/SettingsView.vue'
import ChatView from './components/ChatView.vue'
import {
  loadSettings, saveProjectId, isConfigured, hydrateSettings, clearToken, mirrorLang
} from './lib/settings.js'
import { fetchMyProjects, ensureAddinDefaultProject, fetchMe, postLogout } from './lib/api.js'
import { t, getLang, setLang } from './lib/i18n.js'
import { popIn } from './lib/motion.js'

const settings = reactive(loadSettings())
const configured = computed(() => isConfigured(settings))
const view = ref(configured.value ? 'chat' : 'settings')
const projects = ref([])
const projectId = ref(settings.projectId || '')
const langKey = ref(getLang())
const me = ref(null)
const accountOpen = ref(false)
const accountMenuEl = ref(null)

// Logo 走运行时相对路径（动态绑定绕开 vite 的静态资源改写）：
// 部署在 /office-addin/ 子路径与 dev 根路径下都能落到 dist 根的图标
const logoSrc = 'icon-32.png'

const currentProjectName = computed(() => {
  const hit = projects.value.find(p => String(p.id) === projectId.value)
  return hit ? hit.name : ''
})

// 按钮显示「切过去」的语言名（在中文界面显示 EN，反之显示中）；
// 字面量放 script 里——i18n 静态扫描只盯模板段的裸中文
const langBtnLabel = computed(() => (langKey.value === 'zh' ? 'EN' : '中'))

const avatarInitial = computed(() => {
  const name = me.value && (me.value.displayName || me.value.username)
  return name ? String(name).trim().charAt(0).toUpperCase() : 'A'
})

/**
 * 项目不是插件的必选项：
 * - 有项目：渲染下拉（单项目自动选中，仍可见可换）；
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
    if (list.length) {
      if (!projectId.value && list.length === 1) onProjectChange(String(list[0].id))
      return
    }

    const created = await ensureAddinDefaultProject(settings)
    if (created) {
      projects.value = [created]
      onProjectChange(String(created.id))
    }
  } catch (e) {
    console.warn('[Addin] 项目列表拉取失败', e)
  }
}

async function loadMe() {
  me.value = configured.value ? await fetchMe(settings) : null
}

function onProjectChange(id) {
  projectId.value = id
  saveProjectId(id)
}

function toggleLang() {
  const next = langKey.value === 'zh' ? 'en' : 'zh'
  setLang(next)
  mirrorLang(next)
  langKey.value = next
  accountOpen.value = false
}

function openSettings() {
  accountOpen.value = false
  view.value = 'settings'
}

async function logout() {
  accountOpen.value = false
  postLogout(settings) // 尽力而为，不等待
  clearToken()
  settings.token = ''
  me.value = null
  projects.value = []
  view.value = 'settings'
}

function onSettingsSaved({ serverUrl, token }) {
  settings.serverUrl = serverUrl
  settings.token = token
  view.value = 'chat'
  refreshProjects()
  loadMe()
}

watch(accountOpen, (open) => {
  if (open) nextTick(() => popIn(accountMenuEl.value))
})

onMounted(async () => {
  // webview 清过 localStorage 时从 OfficeRuntime.storage 回灌（dev-board#174）
  try {
    const restored = await hydrateSettings()
    if (restored) {
      settings.serverUrl = restored.serverUrl
      settings.token = restored.token
      settings.projectId = restored.projectId
      if (restored.projectId) projectId.value = restored.projectId
      if (restored.lang && restored.lang !== getLang()) {
        setLang(restored.lang)
        langKey.value = restored.lang
      }
      if (configured.value && view.value === 'settings') view.value = 'chat'
    }
  } catch (e) {
    // 回灌失败不影响主路径
  }
  refreshProjects()
  loadMe()
})
</script>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-header {
  position: relative;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 40px;
  padding: 4px 10px;
  border-bottom: 1px solid rgba(26, 83, 54, 0.10);
  box-shadow: var(--awd-shadow-soft);
  flex-shrink: 0;
}

.brand-logo {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.header-spacer {
  flex: 1;
  min-width: 0;
}

.project-select {
  max-width: 150px;
  padding: 3px 6px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  color: var(--awd-text);
  transition: border-color 0.2s ease;
}

.project-select:hover { border-color: var(--awd-accent); }

.icon-btn {
  padding: 3px 10px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  transition: color 0.2s ease, border-color 0.2s ease, transform 0.1s ease;
}

.icon-btn:hover {
  color: var(--awd-accent);
  border-color: var(--awd-accent);
}

.icon-btn:active { transform: translateY(1px); }

.lang-btn {
  min-width: 34px;
  font-size: 12px;
  padding: 3px 7px;
}

.login-btn {
  padding: 4px 14px;
  border: none;
  border-radius: 999px;
  background: var(--awd-primary);
  color: #fff;
  font-size: 12px;
  transition: background 0.2s ease, transform 0.1s ease;
}

.login-btn:hover { background: var(--awd-primary-hover); }
.login-btn:active { transform: translateY(1px); }

.avatar-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1.5px solid var(--awd-accent);
  border-radius: 50%;
  background: var(--awd-mint-pale);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: box-shadow 0.2s ease, transform 0.1s ease;
}

.avatar-btn:hover { box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.25); }
.avatar-btn:active { transform: translateY(1px); }

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-initial {
  font-size: 13px;
  font-weight: 600;
  color: var(--awd-primary);
  line-height: 1;
}

.account-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
}

.account-menu {
  position: absolute;
  top: 44px;
  right: 8px;
  width: 210px;
  border: 1px solid rgba(26, 83, 54, 0.12);
  border-radius: var(--awd-radius-md);
  box-shadow: var(--awd-shadow-float);
  padding: 10px 8px 8px;
}

.account-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 6px 8px;
  border-bottom: 1px solid var(--awd-border);
  margin-bottom: 6px;
}

.account-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--awd-mint-pale);
  border: 1.5px solid var(--awd-accent);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.account-name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 7px 8px;
  border: none;
  border-radius: var(--awd-radius-sm);
  background: none;
  color: var(--awd-text);
  font-size: 12px;
  transition: background 0.15s ease, color 0.15s ease;
}

.menu-item:hover {
  background: var(--awd-mint-pale);
  color: var(--awd-primary);
}

.menu-item.danger:hover {
  background: rgba(160, 59, 44, 0.08);
  color: var(--awd-danger);
}

.app-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
