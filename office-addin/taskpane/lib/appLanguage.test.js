// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 界面语言一路上送后端的回归用例（dev-board#713）。
 *   node --test office-addin/taskpane/lib/appLanguage.test.js
 *
 * 病灶：插件从来没告诉后端自己的界面语言，而后端唯一的语言来源是全局 system_setting
 * 的 app.language——多租户云后端上那个值恒为默认的 zh-CN。于是英文界面 + 英文文档
 * 仍然拿到中文回答（AppSource 政策 1100.7 的界面语言一致要求，是上架阻塞项）。
 *
 * 钉住四件事：
 *   1. 所有到后端的请求带 X-App-Language，且**每次现取**——切语言后下一条请求立刻是新语言；
 *   2. POST /api/agent/chat 的请求体里另带 appLanguage（编排循环跑在池线程上，
 *      HTTP 线程上的语言作用域不跟着走，只有随请求体带过去的值能活到那一轮）；
 *   3. 工具 chip 名随当前语言现查字典（此前是模块加载时 t() 求值一次、此后再也不变，
 *      表现成同一条会话里 Word 的 chip 是英文、Excel 的是中文）；
 *   4. 后端懒建的「插件临时项目」按当前语言显示（名字存在库里，切语言不会跟着变）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage / Office 环境桩（须在 import 被测模块之前就位） ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
// detectHost 的兜底路径：有 Word 全局即判 word 宿主（node 里没有 Office.js）
globalThis.Word = globalThis.Word || {}

const { setLang, getLang, getLangTag } = await import('./i18n.js')
const { fetchMyProjects } = await import('./api.js')
const { commandDisplayName } = await import('./officeExecutor.js')
const { displayProjectName } = await import('./projectName.js')
const { activateSession, input, send, stop } = await import('./chatSession.js')

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

/** 永不出数据的 SSE 响应体（建连成功后读流挂起，不影响用例收尾） */
function sseOkResponse() {
  return {
    ok: true,
    status: 200,
    body: { getReader: () => ({ read: () => new Promise(() => {}) }) }
  }
}

function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return { calls, restore: () => { globalThis.fetch = original } }
}

/** 每个用例收尾都把语言还原，避免跨用例污染（activeLang 是模块级可变状态） */
function withLang(lang, body) {
  const previous = getLang()
  setLang(lang)
  return (async () => {
    try {
      return await body()
    } finally {
      setLang(previous)
    }
  })()
}

test('普通请求带 X-App-Language，且切语言后下一条请求立刻是新语言', async () => {
  const f = stubFetch(() => jsonReply([]))
  try {
    await withLang('en', () => fetchMyProjects({ serverUrl: 'https://x.example', token: 'awdt_1' }))
    assert.equal(f.calls[0].options.headers['X-App-Language'], 'en-US',
      '英文界面必须声明 en-US，否则后端回落全局 app.language（恒 zh-CN）')

    await withLang('zh', () => fetchMyProjects({ serverUrl: 'https://x.example', token: 'awdt_1' }))
    assert.equal(f.calls[1].options.headers['X-App-Language'], 'zh-CN',
      '语言头必须每次现取；模块加载时定死就是本轮要修的病灶')
  } finally {
    f.restore()
  }
})

test('语言标签与后端 AppLanguageService.SUPPORTED 逐字对齐（发 zh/en 会被后端当没声明）', async () => {
  await withLang('zh', async () => assert.equal(getLangTag(), 'zh-CN'))
  await withLang('en', async () => assert.equal(getLangTag(), 'en-US'))
})

test('POST /api/agent/chat 的请求体带 appLanguage（池线程上的那一轮靠它）', async () => {
  store.clear()
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-lang-1' })
    if (url.includes('/api/agent/connect/')) return sseOkResponse()
    if (url.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
    if (url.includes('/api/ai/models') || url.includes('/api/skills/list')) return jsonReply([], false, 404)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    return jsonReply({}, false, 404)
  })
  try {
    await withLang('en', async () => {
      await activateSession({
        settings: { serverUrl: 'https://x.example', token: 'awdt_lang' }, projectId: '31'
      })
      input.value = 'Please review this clause.'
      await send()
    })
    const chat = f.calls.filter((c) => c.url.endsWith('/api/agent/chat'))
    assert.equal(chat.length, 1, '应发出恰好一条 chat 请求')
    const payload = JSON.parse(chat[0].options.body)
    assert.equal(payload.appLanguage, 'en-US',
      'chat 请求体必须带 appLanguage：只靠请求头的话，异步编排那一轮拿不到语言')
    assert.equal(chat[0].options.headers['X-App-Language'], 'en-US')
  } finally {
    await stop()
    f.restore()
  }
})

test('工具 chip 名随当前语言现查（不是模块加载时定死一次）', async () => {
  await withLang('zh', async () => {
    assert.equal(commandDisplayName('excel_get_range'), '读取区域')
    assert.equal(commandDisplayName('replace_batch'), '批量替换（修订）')
  })
  await withLang('en', async () => {
    assert.equal(commandDisplayName('excel_get_range'), 'Read range',
      '同一进程里切到英文后，chip 名必须跟着换——混着中英正是 dev-board#713 的症状之一')
    assert.equal(commandDisplayName('replace_batch'), 'Batch replace (tracked)')
  })
})

test('未知命令的 chip 名也按当前语言回退', async () => {
  await withLang('zh', async () => {
    assert.ok(commandDisplayName('no_such_command').includes('no_such_command'))
  })
  await withLang('en', async () => {
    const label = commandDisplayName('no_such_command')
    assert.ok(label.includes('no_such_command'))
    assert.ok(!/[一-鿿]/.test(label), '英文界面下的回退文案不许带中文')
  })
})

test('懒建的「插件临时项目」按当前语言显示，其余项目名原样', async () => {
  await withLang('en', async () => {
    assert.equal(displayProjectName('插件临时项目'), 'Plugin Temporary Project',
      '中文界面下建出来的老项目，切到英文后也要显示英文')
    assert.equal(displayProjectName('Plugin Temporary Project'), 'Plugin Temporary Project')
    assert.equal(displayProjectName('Acme 尽调'), 'Acme 尽调', '用户自己的项目名一个字都不许动')
  })
  await withLang('zh', async () => {
    assert.equal(displayProjectName('Plugin Temporary Project'), '插件临时项目',
      '英文界面下建出来的老项目，切回中文也要显示中文')
    assert.equal(displayProjectName('插件临时项目'), '插件临时项目')
    assert.equal(displayProjectName(''), '')
    assert.equal(displayProjectName(null), '')
  })
})
