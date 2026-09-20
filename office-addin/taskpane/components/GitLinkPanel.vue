<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <div class="overlay" @click.self="$emit('close')">
    <div class="panel glass">
      <div class="panel-head">
        <span>{{ t('gitLinkTitle') }}</span>
        <button class="panel-close" @click="$emit('close')">x</button>
      </div>

      <p class="intro">{{ t('gitLinkHint') }}</p>

      <!-- git 关联挂在项目上：没选项目时连清单都无从谈起，把表单藏掉比给一个
           永远保存失败的表单诚实 -->
      <p v-if="!projectId" class="hint">{{ t('gitLinkNoProject') }}</p>

      <template v-else>
        <p v-if="loading" class="hint">{{ t('loading') }}</p>
        <p v-else-if="!links.length" class="hint">{{ t('gitLinkEmpty') }}</p>

        <div v-if="links.length" class="link-list">
          <div v-for="l in links" :key="l.id" class="link-row">
            <div class="link-main">
              <span class="link-name">{{ linkLabel(l) }}</span>
              <span v-if="l.tokenLast4" class="link-token">…{{ l.tokenLast4 }}</span>
            </div>
            <!-- lastError 是服务端上次读该仓库时记下的真实原因（令牌失效居多），
                 以警示色常驻——不说的话用户只会看到 AI「找不到文件」 -->
            <p v-if="l.lastError" class="link-error">{{ l.lastError }}</p>
            <button class="act" :disabled="removingId === l.id" @click="unlink(l)">{{ t('gitLinkRemove') }}</button>
          </div>
        </div>

        <div class="form">
          <label class="field-label">{{ t('gitLinkUrl') }}</label>
          <input v-model="url" class="field-input" :placeholder="t('gitLinkUrlPlaceholder')" />
          <!-- 地址不合规时保存按钮是禁用的：不把原因说出来，用户面对的就是一个
               按不动又不解释的按钮（真渲染走查实锤） -->
          <p v-if="urlError" class="error-line">{{ urlError }}</p>

          <label class="field-label">{{ t('gitLinkBranch') }}</label>
          <input v-model="branch" class="field-input" />

          <label class="field-label">{{ t('gitLinkToken') }}</label>
          <!-- 任务窗格是常驻的：明文令牌会一直挂在屏幕上给旁边的人看 -->
          <input v-model="token" class="field-input" type="password" autocomplete="off" />

          <button class="btn primary" :disabled="!canSave" @click="save">
            {{ saving ? t('gitLinkVerifying') : t('gitLinkSave') }}
          </button>
          <p v-if="error" class="error-line">{{ error }}</p>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { t } from '../lib/i18n.js'
import { links, loading, loadLinks, addLink, removeLink, linkLabel, validateRepoUrl } from '../lib/gitLink.js'

/**
 * 关联 git 仓库面板（dev-board#720）：账户菜单入口，overlay 模式（与 TransferPanel /
 * RevisionLogPanel 同款），样式自含。
 *
 * 面板只做接线：地址校验与三个端点的往返都在 lib/gitLink.js（可在 node 里测）。
 * 错误一律显示服务端原文——「服务器未配置 git 令牌密钥」「令牌无效」这类是用户
 * 唯一能据以改正的信息。
 */
const props = defineProps({
  settings: { type: Object, required: true },
  projectId: { type: [String, Number], default: '' }
})
defineEmits(['close'])

const url = ref('')
const branch = ref('')
const token = ref('')
const saving = ref(false)
const removingId = ref(null)
const error = ref('')

// 令牌不是必填：公开仓库不需要令牌，逼着用户造一个反而更糟
const canSave = computed(() => !saving.value && validateRepoUrl(url.value).ok)
// 空输入不算错（按钮本就禁用），填了才说哪儿不对
const urlError = computed(() => validateRepoUrl(url.value).error || '')

onMounted(async () => {
  try {
    await loadLinks(props.settings, props.projectId)
  } catch (e) {
    error.value = e && e.message ? e.message : String(e)
  }
})

async function save() {
  error.value = ''
  saving.value = true
  try {
    await addLink(props.settings, {
      projectId: props.projectId,
      url: url.value,
      branch: branch.value,
      token: token.value
    })
    url.value = ''
    branch.value = ''
    // 令牌转交完就忘掉：服务端加密存了，这里再留着只是多一份泄露面
    token.value = ''
  } catch (e) {
    error.value = e && e.message ? e.message : String(e)
  } finally {
    saving.value = false
  }
}

async function unlink(link) {
  error.value = ''
  removingId.value = link.id
  try {
    await removeLink(props.settings, link.id)
  } catch (e) {
    error.value = e && e.message ? e.message : String(e)
  } finally {
    removingId.value = null
  }
}
</script>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgba(14, 33, 23, 0.32);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.panel {
  width: 100%;
  max-width: 360px;
  max-height: 92%;
  overflow-y: auto;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-md, 10px);
  box-shadow: var(--awd-shadow-float, 0 8px 32px rgba(18, 58, 38, 0.16));
  padding: 12px 14px 14px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.panel-close {
  border: none;
  background: none;
  color: var(--awd-text-secondary);
  font-family: var(--awd-font-mono, monospace);
  cursor: pointer;
  padding: 0 4px;
}

.intro {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-mint-pale);
  color: var(--awd-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.hint {
  font-size: 11px;
  color: var(--awd-text-secondary);
  margin: 2px 0 8px;
  line-height: 1.5;
}

.link-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.link-row {
  padding: 8px 10px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
}

.link-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.link-name {
  font-size: 12px;
  color: var(--awd-text);
  word-break: break-all;
}

.link-token {
  font-family: var(--awd-font-mono, monospace);
  font-size: 11px;
  color: var(--awd-text-secondary);
  flex-shrink: 0;
}

.link-error {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--awd-danger);
}

.act {
  margin-top: 6px;
  padding: 3px 10px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 11px;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.act:hover:not(:disabled) {
  color: var(--awd-danger);
  border-color: var(--awd-danger);
}

.act:disabled { opacity: 0.5; cursor: not-allowed; }

.form {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-secondary);
  margin-top: 4px;
}

.field-input {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  box-sizing: border-box;
}

.field-input:focus {
  outline: none;
  border-color: var(--awd-primary);
}

.btn {
  margin-top: 8px;
  padding: 7px 12px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  transition: background 0.15s ease, transform 0.1s ease;
}

.btn:active { transform: translateY(1px); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn.primary {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.btn.primary:hover:not(:disabled) { background: var(--awd-primary-hover); }

.error-line {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--awd-danger);
}
</style>
