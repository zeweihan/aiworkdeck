<template>
  <div class="settings">
    <section class="card">
      <h2>连接</h2>

      <p v-if="token.trim()" class="summary">
        已连接 {{ displayServerUrl }}
      </p>

      <p class="hint">
        用 AI WorkDeck 账户登录即可连接，与桌面版是同一个账户。
      </p>

      <!-- 手机号是大陆站主路径；国际站与存量账号走邮箱口令 -->
      <div class="tabs">
        <button class="tab" :class="{ 'is-active': mode === 'phone' }" @click="switchMode('phone')">手机号</button>
        <button class="tab" :class="{ 'is-active': mode === 'email' }" @click="switchMode('email')">邮箱</button>
      </div>

      <template v-if="mode === 'phone'">
        <label class="field">
          <span class="label">手机号</span>
          <input v-model="phone" type="tel" placeholder="11 位手机号" spellcheck="false" autocomplete="tel" />
        </label>

        <!-- 不用 label 包住整行：label 的激活行为会把点在按钮上的一次点击转发给里面的 input -->
        <div class="field">
          <span class="label">验证码</span>
          <div class="code-row">
            <input v-model="smsCode" type="text" placeholder="6 位验证码" spellcheck="false" autocomplete="one-time-code" />
            <button class="btn secondary code-btn" :disabled="sendingCode || cooldown > 0 || !phone.trim()" @click="sendCode">
              {{ codeBtnLabel }}
            </button>
          </div>
          <!--
            人机验证控件的落点。阿里云是 popup 模式，平时不占位；`-trigger` 是 SDK 要求的
            触发元素，由 getToken() 代点，用户看不到它，所以藏起来但**必须留在文档里**
            （display:none 的节点仍可被 click()，移出文档就取不到 token 了）。
          -->
          <div id="login-captcha" class="captcha-holder"></div>
          <button id="login-captcha-trigger" type="button" class="captcha-trigger" aria-hidden="true" tabindex="-1"></button>
        </div>
      </template>

      <template v-else>
        <label class="field">
          <span class="label">邮箱</span>
          <input v-model="account" type="email" placeholder="注册时使用的邮箱" spellcheck="false" autocomplete="username" />
        </label>

        <label class="field">
          <span class="label">口令</span>
          <input v-model="password" type="password" placeholder="账户口令" spellcheck="false" autocomplete="current-password" />
        </label>
      </template>

      <div class="actions">
        <button class="btn primary" :disabled="connecting" @click="connectWithAccount">
          {{ connecting ? '连接中...' : '登录并连接' }}
        </button>
      </div>

      <p v-if="loginStatus" class="status" :class="loginStatusKind">{{ loginStatus }}</p>

      <details class="advanced">
        <summary>高级设置</summary>

        <p class="hint">
          私有部署与团队服务器场景：可填律所自建后端地址，或同机桌面版的 http://127.0.0.1:5269，
          再用官网 API Key 或手工粘贴的设备令牌连接。
        </p>

        <label class="field">
          <span class="label">后端地址</span>
          <input
            v-model="serverUrl"
            type="text"
            placeholder="例如 https://ai.yourfirm.com 或 http://127.0.0.1:5269"
            spellcheck="false"
          />
        </label>

        <label class="field">
          <span class="label">官网 API Key（awdk_ 开头）</span>
          <input
            v-model="awdkKey"
            type="password"
            placeholder="粘贴 awdk_ 开头的 API Key"
            spellcheck="false"
            autocomplete="off"
          />
        </label>

        <div class="actions">
          <button class="btn secondary" :disabled="connecting" @click="connectWithKey">
            {{ connecting ? '连接中...' : '用 Key 连接' }}
          </button>
        </div>

        <p v-if="keyStatus" class="status" :class="keyStatusKind">{{ keyStatus }}</p>

        <label class="field">
          <span class="label">设备令牌（awdt_ 开头）</span>
          <textarea
            v-model="token"
            rows="3"
            placeholder="粘贴 awdt_ 设备令牌。可在 AI WorkDeck 桌面版个人中心的「账号安全」中生成。"
            spellcheck="false"
          ></textarea>
        </label>

        <div class="actions">
          <button class="btn secondary" :disabled="testing" @click="testConnection">
            {{ testing ? '测试中...' : '测试连接' }}
          </button>
          <button class="btn primary" @click="save">保存</button>
        </div>

        <p v-if="status" class="status" :class="statusKind">{{ status }}</p>
      </details>
    </section>

  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  fetchMyProjects,
  getAccountLoginCaptchaConfig,
  postAccountLogin,
  postAccountLoginSendCode,
  postAwdkLogin
} from '../lib/api.js'
import { setupCaptcha } from '../lib/captcha.js'
import { saveSettings, normalizeBaseUrl, DEFAULT_SERVER_URL } from '../lib/settings.js'

const props = defineProps({
  initialServerUrl: { type: String, default: '' },
  initialToken: { type: String, default: '' }
})
const emit = defineEmits(['saved'])

const serverUrl = ref(props.initialServerUrl || normalizeBaseUrl(DEFAULT_SERVER_URL))
const token = ref(props.initialToken)
const testing = ref(false)
const status = ref('')
const statusKind = ref('ok')
const awdkKey = ref('')
const connecting = ref(false)
const keyStatus = ref('')
const keyStatusKind = ref('ok')

// 账户登录（主路径）
const mode = ref('phone')
const phone = ref('')
const smsCode = ref('')
const account = ref('')
const password = ref('')
const sendingCode = ref(false)
const cooldown = ref(0)
const loginStatus = ref('')
const loginStatusKind = ref('ok')

let cooldownTimer = null

// 人机验证控件。null = 官网未启用（或配置拿不到），此时跳过控件直接发码。
let captcha = null
let captchaReady = null

/** 当前连接状态摘要：只读本地设置，不发请求 */
const displayServerUrl = computed(() => normalizeBaseUrl(serverUrl.value) || '（未设置地址）')

const codeBtnLabel = computed(() => {
  if (cooldown.value > 0) return `${cooldown.value} 秒后重发`
  return sendingCode.value ? '发送中...' : '获取验证码'
})

// 任务窗格切视图会卸载本组件：倒计时的定时器必须跟着停（否则回来时还在跑），
// 验证码控件也要拆掉（否则 SDK 还攥着已经不在文档里的节点）。
onBeforeUnmount(() => {
  stopCooldown()
  if (captcha && captcha.destroy) captcha.destroy()
  captcha = null
})

// 装控件要下载第三方脚本，放在挂载时预热；失败不打扰用户——真出问题会在发码那步
// 由官网给出可读的报错（「请先完成安全验证后再试」）。
onMounted(() => { captchaReady = ensureCaptcha() })

async function ensureCaptcha() {
  if (captcha) return captcha
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: serverUrl.value })
    captcha = await setupCaptcha(config, 'login-captcha')
  } catch (e) {
    console.warn('[settings] 人机验证控件装配失败:', e)
    captcha = null
  }
  return captcha
}

function stopCooldown() {
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
}

function startCooldown() {
  stopCooldown()
  cooldown.value = 60
  cooldownTimer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) stopCooldown()
  }, 1000)
}

function switchMode(next) {
  mode.value = next
  loginStatus.value = ''
}

async function sendCode() {
  loginStatus.value = ''
  if (!serverUrl.value.trim()) {
    loginStatusKind.value = 'error'
    loginStatus.value = '连接未就绪：后端地址为空，可在「高级设置」中填写'
    return
  }
  sendingCode.value = true
  try {
    // 先过人机验证再发码：官网把 verifyCaptcha 排在发短信之前，不带 token 就是 403。
    // 官网未启用时 ensureCaptcha() 给 null，token 留空，行为与从前一致。
    const widget = await (captchaReady || ensureCaptcha())
    let captchaToken = ''
    if (widget) {
      captchaToken = await widget.getToken()
      if (!captchaToken) {
        loginStatusKind.value = 'error'
        loginStatus.value = '安全验证未完成，请重试'
        return
      }
    }
    await postAccountLoginSendCode({ serverUrl: serverUrl.value }, phone.value, captchaToken)
    loginStatusKind.value = 'ok'
    loginStatus.value = '验证码已发送，请查收短信'
    startCooldown()
  } catch (e) {
    loginStatusKind.value = 'error'
    loginStatus.value = e.message || '验证码发送失败'
  } finally {
    sendingCode.value = false
  }
}

/**
 * 账户登录：手机号+验证码 或 邮箱+口令换取 awdt_ 设备令牌后保存并进入对话视图。
 * 凭据用完即弃（不落 localStorage，只有换回的 awdt_ 令牌被保存）。
 */
async function connectWithAccount() {
  loginStatus.value = ''
  if (!serverUrl.value.trim()) {
    loginStatusKind.value = 'error'
    loginStatus.value = '连接未就绪：后端地址为空，可在「高级设置」中填写'
    return
  }
  const credentials = mode.value === 'phone'
    ? { phone: phone.value.trim(), code: smsCode.value.trim() }
    : { account: account.value.trim(), password: password.value }
  const filled = mode.value === 'phone'
    ? credentials.phone && credentials.code
    : credentials.account && credentials.password
  if (!filled) {
    loginStatusKind.value = 'error'
    loginStatus.value = mode.value === 'phone' ? '请填写手机号与验证码' : '请填写邮箱与口令'
    return
  }
  connecting.value = true
  try {
    const awdtToken = await postAccountLogin({ serverUrl: serverUrl.value }, credentials)
    smsCode.value = ''
    password.value = ''
    applyToken(awdtToken)
    loginStatusKind.value = 'ok'
    loginStatus.value = '连接成功'
  } catch (e) {
    loginStatusKind.value = 'error'
    loginStatus.value = e.message || '账户连接失败'
  } finally {
    connecting.value = false
  }
}

/**
 * awdk_ 账户 Key 连接（高级设置）：私有部署与团队服务器仍要用这条。
 * Key 本身用完即弃（不落 localStorage，只有换回的 awdt_ 令牌被保存）。
 */
async function connectWithKey() {
  keyStatus.value = ''
  if (!serverUrl.value.trim()) {
    keyStatusKind.value = 'error'
    keyStatus.value = '连接未就绪：后端地址为空'
    return
  }
  const key = awdkKey.value.trim()
  if (!key) {
    keyStatusKind.value = 'error'
    keyStatus.value = '连接未就绪：请粘贴 awdk_ 开头的 API Key'
    return
  }
  connecting.value = true
  try {
    const awdtToken = await postAwdkLogin({ serverUrl: serverUrl.value }, key)
    awdkKey.value = ''
    applyToken(awdtToken)
    keyStatusKind.value = 'ok'
    keyStatus.value = '连接成功：已换取设备令牌'
  } catch (e) {
    keyStatusKind.value = 'error'
    keyStatus.value = e.message || '账户直连失败'
  } finally {
    connecting.value = false
  }
}

/** 两条换令牌的路径共用的收尾：落盘 + 通知外层切到对话视图。 */
function applyToken(awdtToken) {
  token.value = awdtToken
  saveSettings({ serverUrl: serverUrl.value, token: awdtToken })
  emit('saved', { serverUrl: normalizeBaseUrl(serverUrl.value), token: awdtToken })
}

async function testConnection() {
  status.value = ''
  if (!serverUrl.value.trim() || !token.value.trim()) {
    statusKind.value = 'error'
    status.value = '连接未就绪：请填写后端地址与设备令牌'
    return
  }
  testing.value = true
  try {
    const projects = await fetchMyProjects({ serverUrl: serverUrl.value, token: token.value.trim() })
    statusKind.value = 'ok'
    status.value = `连接成功：可访问 ${projects.length} 个项目`
  } catch (e) {
    statusKind.value = 'error'
    status.value = e.message || '连接失败'
  } finally {
    testing.value = false
  }
}

function save() {
  if (!serverUrl.value.trim() || !token.value.trim()) {
    statusKind.value = 'error'
    status.value = '连接未就绪：请填写后端地址与设备令牌'
    return
  }
  saveSettings({ serverUrl: serverUrl.value, token: token.value })
  emit('saved', { serverUrl: normalizeBaseUrl(serverUrl.value), token: token.value.trim() })
}
</script>

<style scoped>
.settings {
  padding: 12px;
  overflow-y: auto;
}

.card {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 6px;
  padding: 14px;
}


.summary {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--awd-text-secondary);
  word-break: break-all;
}

.tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}

.tab {
  padding: 5px 12px;
  font-size: 12px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  cursor: pointer;
}

.tab.is-active {
  border-color: var(--awd-primary);
  color: var(--awd-primary);
}

.code-row {
  display: flex;
  gap: 8px;
}

.code-row input { flex: 1; }

.code-btn {
  flex: none;
  white-space: nowrap;
}

/*
  阿里云是 popup 模式，控件本身不占位，这个 div 平时是空的（留 margin 只为
  turnstile 那条分支——它会把控件渲染进来，需要一点与上方输入框的间距）。
  **不要给它 display:none**：turnstile 渲染进不可见容器会拿不到尺寸而不出现。
*/
.captcha-holder:not(:empty) {
  margin-top: 8px;
}

/*
  SDK 要求的触发元素，由 getToken() 代点，用户不该看见也不该 Tab 到。
  用 position:absolute + 0 尺寸而不是 display:none——两者都能被 click()，
  但前者对个别 WebView 更保险（隐藏元素的合成点击在 WKWebView 上有过不触发的先例）。
*/
.captcha-trigger {
  position: absolute;
  width: 0;
  height: 0;
  padding: 0;
  border: 0;
  opacity: 0;
  pointer-events: none;
}

.advanced {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--awd-border);
}

.advanced > summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--awd-text-secondary);
  user-select: none;
}

.advanced > summary:hover { color: var(--awd-primary); }

.advanced .hint { margin-top: 10px; }




h2 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
}

.hint {
  margin: 0 0 12px;
  color: var(--awd-text-secondary);
  font-size: 12px;
}

.field {
  display: block;
  margin-bottom: 12px;
}

.label {
  display: block;
  margin-bottom: 4px;
  color: var(--awd-text-secondary);
  font-size: 12px;
}

input, textarea {
  width: 100%;
  padding: 7px 9px;
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  background: var(--awd-surface);
  resize: vertical;
}

input:focus, textarea:focus {
  outline: none;
  border-color: var(--awd-primary);
}

.actions {
  display: flex;
  gap: 8px;
}

.btn {
  padding: 6px 14px;
  border-radius: 4px;
  border: 1px solid var(--awd-border);
}

.btn.primary {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.btn.primary:hover { background: var(--awd-primary-hover); }

.btn.secondary {
  background: var(--awd-surface);
  color: var(--awd-text);
}

.btn:disabled { opacity: 0.6; cursor: default; }

.status {
  margin: 10px 0 0;
  font-size: 12px;
}

.status.ok { color: #1d7a3e; }
.status.error { color: var(--awd-danger); }
.status.warn { color: var(--awd-text-secondary); }
</style>
