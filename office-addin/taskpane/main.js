// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'

function mount() {
  createApp(App).mount('#app')
}

// Office 宿主内等 Office.onReady；普通浏览器直开调试时直接挂载
if (typeof Office !== 'undefined' && Office.onReady) {
  Office.onReady(() => mount())
} else {
  mount()
}
