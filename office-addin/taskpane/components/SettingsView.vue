<template>
  <div class="settings">
    <section class="card">
      <h2>连接设置</h2>
      <p class="hint">
        插件独立连接后端实例：可填律所自建服务器地址，或同机桌面版的 http://127.0.0.1:5269。
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
          placeholder="粘贴 awdt_ 设备令牌。可在 AI Workdeck 桌面版的设置中生成。"
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
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { fetchMyProjects } from '../lib/api.js'
import { saveSettings, normalizeBaseUrl } from '../lib/settings.js'

const props = defineProps({
  initialServerUrl: { type: String, default: '' },
  initialToken: { type: String, default: '' }
})
const emit = defineEmits(['saved'])

const serverUrl = ref(props.initialServerUrl)
const token = ref(props.initialToken)
const testing = ref(false)
const status = ref('')
const statusKind = ref('ok')

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
</style>
