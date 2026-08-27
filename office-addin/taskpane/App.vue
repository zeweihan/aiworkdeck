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
        @change="onProjectSelect($event)"
      >
        <option value="" disabled>{{ t('selectProject') }}</option>
        <option v-for="p in projects" :key="p.id" :value="String(p.id)">{{ p.name }}</option>
        <!-- 新建项目（dev-board#196）：哨兵值，onProjectSelect 拦截后弹输入面板 -->
        <option value="__new__">{{ t('newProjectOption') }}</option>
      </select>
      <span class="header-spacer"></span>
      <!-- 语言切换（dev-board#177/#193）：未登录也要能切，所以放头部而不是账户菜单里；
           地球图标 + 目标语言缩写，让它一眼可读为「切换语言」而不是一个谜之汉字按钮 -->
      <button
        class="icon-btn lang-btn"
        :title="t('languageLabel')"
        @click="toggleLang"
      >
        <svg class="lang-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
          <circle cx="12" cy="12" r="9"/>
          <path d="M3 12h18"/>
          <ellipse cx="12" cy="12" rx="4.2" ry="9"/>
        </svg>
        <span class="lang-target">{{ langBtnLabel }}</span>
      </button>
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

    <!-- 账户菜单（dev-board#194）：展示账户基本信息与 AI 额度，不再挂「高级设置」入口——
         那个入口把已登录用户带回登录表单，看起来像是被登出了 -->
    <div v-if="accountOpen" class="account-overlay" @click.self="accountOpen = false">
      <div ref="accountMenuEl" class="account-menu glass">
        <div class="account-head">
          <div class="account-avatar">
            <img v-if="me && me.avatarUrl" class="avatar-img" :src="me.avatarUrl" alt="" />
            <span v-else class="avatar-initial">{{ avatarInitial }}</span>
          </div>
          <div class="account-id">
            <div class="account-name">{{ me && (me.displayName || me.username) ? (me.displayName || me.username) : t('accountTitle') }}</div>
            <div v-if="accountContact" class="account-sub">{{ accountContact }}</div>
          </div>
        </div>
        <div v-if="quotaText" class="account-quota">
          <span class="quota-label">{{ t('aiQuotaLabel') }}</span>
          <span class="quota-value" :class="{ low: quotaLow }">{{ quotaText }}</span>
        </div>
        <!-- 充值走官网账户页（dev-board#198）：云后端不落用户 awdk key，收银台进不了任务窗格 -->
        <button v-if="siteRecharge" class="menu-item" :title="t('rechargeTitle')" @click="openRecharge">{{ t('recharge') }}</button>
        <button class="menu-item danger" @click="logout">{{ t('logout') }}</button>
      </div>
    </div>

    <!-- 新建项目弹层（dev-board#196）：Office webview 里不用 window.prompt -->
    <div v-if="newProjectOpen" class="account-overlay" @click.self="newProjectOpen = false">
      <div ref="newProjectEl" class="account-menu glass new-project-menu">
        <div class="np-title">{{ t('newProjectTitle') }}</div>
        <input
          ref="newProjectInputEl"
          v-model="newProjectName"
          class="np-input"
          maxlength="60"
          :placeholder="t('newProjectPlaceholder')"
          @keydown.enter.prevent="confirmCreateProject"
          @keydown.esc="newProjectOpen = false"
        />
        <div class="np-actions">
          <button class="np-btn" @click="newProjectOpen = false">{{ t('cancel') }}</button>
          <button
            class="np-btn primary"
            :disabled="creatingProject || !newProjectName.trim()"
            @click="confirmCreateProject"
          >{{ creatingProject ? t('creating') : t('create') }}</button>
        </div>
        <p v-if="newProjectError" class="np-error">{{ newProjectError }}</p>
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
import {
  fetchMyProjects, ensureAddinDefaultProject, fetchMe, postLogout,
  createProject, fetchPlatformAiStatus
} from './lib/api.js'
import { t, getLang, setLang } from './lib/i18n.js'
import { rechargeUrl, openExternal } from './lib/site.js'
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
/** 按用户的平台 AI 额度（/api/platform-ai/key/status；拿不到就不展示额度行） */
const aiQuota = ref(null)

// 新建项目弹层（dev-board#196）
const newProjectOpen = ref(false)
const newProjectName = ref('')
const newProjectError = ref('')
const creatingProject = ref(false)
const newProjectEl = ref(null)
const newProjectInputEl = ref(null)

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

/** 账户第二行：脱敏手机号或邮箱，都没有就不占位 */
const accountContact = computed(() => {
  if (!me.value) return ''
  return me.value.phoneMasked || me.value.emailMasked || ''
})

/** 官网充值页（仅官方云后端有；私有部署/桌面本机为空串，入口隐藏） */
const siteRecharge = computed(() => rechargeUrl(settings.serverUrl))

/** 低额度警示：剩余不足 $1 时数字转警示色 */
const quotaLow = computed(() => {
  const q = aiQuota.value
  return Boolean(q && q.hasKey && q.remainingUsd != null && q.remainingUsd <= 1)
})

function openRecharge() {
  accountOpen.value = false
  openExternal(siteRecharge.value)
}

/** 额度文案：有上限就给「剩余 / 共」，只有用量就给「已用」，什么都没有则隐藏 */
const quotaText = computed(() => {
  const q = aiQuota.value
  if (!q || !q.hasKey) return ''
  const fmt = (v) => '$' + Number(v).toFixed(2)
  if (q.remainingUsd != null && q.limitUsd != null) {
    return t('quotaRemaining', { remaining: fmt(q.remainingUsd), limit: fmt(q.limitUsd) })
  }
  if (q.usageUsd != null) return t('quotaUsed', { used: fmt(q.usageUsd) })
  return ''
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

/** 下拉换项目：拦下「新建项目」哨兵项，其余走正常切换 */
function onProjectSelect(ev) {
  const value = ev.target.value
  if (value === '__new__') {
    // select 的显示值退回当前项目，弹层里再决定建不建
    ev.target.value = projectId.value || ''
    openNewProject()
    return
  }
  onProjectChange(value)
}

function openNewProject() {
  newProjectName.value = ''
  newProjectError.value = ''
  newProjectOpen.value = true
  nextTick(() => {
    popIn(newProjectEl.value)
    if (newProjectInputEl.value) newProjectInputEl.value.focus()
  })
}

async function confirmCreateProject() {
  const name = newProjectName.value.trim()
  if (!name || creatingProject.value) return
  creatingProject.value = true
  newProjectError.value = ''
  try {
    const created = await createProject(settings, name)
    projects.value = [...projects.value, { id: created.id, name: created.name }]
    onProjectChange(String(created.id))
    newProjectOpen.value = false
  } catch (e) {
    newProjectError.value = (e && e.message) || t('createProjectFailed')
  } finally {
    creatingProject.value = false
  }
}

function toggleLang() {
  const next = langKey.value === 'zh' ? 'en' : 'zh'
  setLang(next)
  mirrorLang(next)
  langKey.value = next
  accountOpen.value = false
}

async function logout() {
  accountOpen.value = false
  postLogout(settings) // 尽力而为，不等待
  clearToken()
  settings.token = ''
  me.value = null
  aiQuota.value = null
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
  if (open) {
    nextTick(() => popIn(accountMenuEl.value))
    // 每次打开都刷一次额度（静默降级：拿不到就不展示那一行）
    fetchPlatformAiStatus(settings).then((s) => { aiQuota.value = s }).catch(() => {})
  }
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
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 999px;
}

.lang-icon {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}

.lang-target {
  font-weight: 600;
  letter-spacing: 0.02em;
  line-height: 1;
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

.account-id {
  flex: 1;
  min-width: 0;
}

.account-name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-sub {
  font-size: 11px;
  color: var(--awd-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* AI 额度行（dev-board#194） */
.account-quota {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  margin-bottom: 6px;
  border-bottom: 1px solid var(--awd-border);
  font-size: 11px;
}

.quota-label { color: var(--awd-text-secondary); flex-shrink: 0; }

.quota-value {
  color: var(--awd-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quota-value.low { color: var(--awd-danger); }

/* 新建项目弹层（dev-board#196） */
.new-project-menu { padding: 12px 12px 10px; }

.np-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.np-input {
  width: 100%;
  padding: 6px 9px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.np-input:focus {
  outline: none;
  border-color: var(--awd-accent);
  box-shadow: 0 0 0 3px rgba(91, 209, 151, 0.18);
}

.np-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 10px;
}

.np-btn {
  padding: 4px 12px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  transition: background 0.2s ease, transform 0.1s ease;
}

.np-btn:active { transform: translateY(1px); }

.np-btn.primary {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.np-btn.primary:hover:not(:disabled) { background: var(--awd-primary-hover); }
.np-btn:disabled { opacity: 0.5; cursor: default; }

.np-error {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--awd-danger);
  word-break: break-word;
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
