<template>
  <div class="settings">
    <section class="card">
      <h2>连接</h2>

      <p v-if="token.trim()" class="summary">
        已连接 {{ displayServerUrl }}
      </p>

      <p class="hint">
        填入官网账户页生成的 API Key 即可连接。Key 仅用于本次换取设备令牌，不保存在本机。
      </p>

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
        <button class="btn primary" :disabled="connecting" @click="connectWithKey">
          {{ connecting ? '连接中...' : '连接' }}
        </button>
      </div>

      <p v-if="keyStatus" class="status" :class="keyStatusKind">{{ keyStatus }}</p>

      <details class="advanced">
        <summary>高级设置</summary>

        <p class="hint">
          自建服务器场景：可填律所自建后端地址，或同机桌面版的 http://127.0.0.1:5269，
          并手工粘贴设备令牌。
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
import { computed, ref } from 'vue'
import {
  fetchMyProjects,
  postAwdkLogin
} from '../lib/api.js'
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
/** 平台 AI 通道额度；null = 该后端没有这条能力（旧版本/单机形态），整块不展示 */

/** 当前连接状态摘要：只读本地设置，不发请求 */
const displayServerUrl = computed(() => normalizeBaseUrl(serverUrl.value) || '（未设置地址）')

/** 金额与时间都可能是 null（用量拿不到时不许把 0 当成剩余额度），统一显示为「—」 */

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

/**
 * awdk_ 账户 Key 一键连接：换取 awdt_ 设备令牌后立即保存并进入对话视图。
 * Key 本身用完即弃（不落 localStorage，只有换回的 awdt_ 令牌被保存）。
 */
async function connectWithKey() {
  keyStatus.value = ''
  if (!serverUrl.value.trim()) {
    keyStatusKind.value = 'error'
    keyStatus.value = '连接未就绪：后端地址为空，可在「高级设置」中填写'
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
    token.value = awdtToken
    saveSettings({ serverUrl: serverUrl.value, token: awdtToken })
    keyStatusKind.value = 'ok'
    keyStatus.value = '连接成功：已换取设备令牌'
    // 桥接同时会为本账号取一把平台 AI 密钥，这里顺手把额度显示出来
    await loadQuota()
    emit('saved', { serverUrl: normalizeBaseUrl(serverUrl.value), token: awdtToken })
  } catch (e) {
    keyStatusKind.value = 'error'
    keyStatus.value = e.message || '账户直连失败'
  } finally {
    connecting.value = false
  }
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
