// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 关联 git 仓库入口（dev-board#720，Task 14）：
 *   node --test office-addin/taskpane/lib/gitLink.test.js
 *
 * 钉三件事：
 *   1. `validateRepoUrl` 与后端 `GitProviderClient.parseUrl` 同规则——前端先挡一遍，
 *      少一次「填了 GitLab 地址、等一次往返、再被后端拒绝」。**只能比后端宽松，不能更严**：
 *      前端多拒一个后端认的地址，用户就被自己人挡在门外，还没有任何出路。
 *   2. 令牌只在「保存」这一刻出站，之后哪儿都不留——store 里、日志里都不许有它。
 *      这是用户交给我们的凭据，前端唯一该做的就是转交完就忘掉。
 *   3. 服务端的 message 原样显示：「服务器未配置 git 令牌密钥」「令牌无效」这类是
 *      用户唯一能据以改正的信息，换成自造的通用文案等于把界面做成哑巴。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { ZH, EN } from './i18n.js'
import { validateRepoUrl, links, loadLinks, addLink, removeLink } from './gitLink.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (rel) => fs.readFileSync(path.join(here, rel), 'utf8')

const SETTINGS = { serverUrl: 'https://addin.example.com', token: 'awdt_x' }

/** 替换 globalThis.fetch，返回 {calls, restore} */
function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return {
    calls,
    restore: () => {
      if (original === undefined) delete globalThis.fetch
      else globalThis.fetch = original
    }
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

// ==================== validateRepoUrl ====================

test('validateRepoUrl 认 GitHub / Gitee 的 https 与 ssh 两种写法', () => {
  assert.equal(validateRepoUrl('https://github.com/acme/docs').provider, 'github')
  assert.equal(validateRepoUrl('https://github.com/acme/docs.git').provider, 'github')
  assert.equal(validateRepoUrl('https://gitee.com/acme/docs/').provider, 'gitee')
  assert.equal(validateRepoUrl('git@gitee.com:acme/docs.git').provider, 'gitee')
  assert.equal(validateRepoUrl('git@github.com:acme/docs.git').provider, 'github')
  assert.equal(validateRepoUrl('  https://github.com/acme/docs  ').ok, true)
})

test('validateRepoUrl 拒别的代码托管站，并说清只支持哪两家', () => {
  const r = validateRepoUrl('https://gitlab.com/a/b')
  assert.equal(r.ok, false)
  assert.ok([ZH.gitLinkOnlyHosts, EN.gitLinkOnlyHosts].includes(r.error), '实际: ' + r.error)
  assert.equal(validateRepoUrl('https://bitbucket.org/a/b').ok, false)
  assert.equal(validateRepoUrl('https://github.evil.com/a/b').ok, false, '域名后缀混淆：github.evil.com 不是 GitHub')
})

test('validateRepoUrl 对「是这两家但不是一个仓库」的地址给形状提示，不说「只支持这两家」', () => {
  const r = validateRepoUrl('https://github.com/acme')
  assert.equal(r.ok, false)
  const badUrlText = [ZH.gitLinkBadUrl, EN.gitLinkBadUrl]
  assert.ok(badUrlText.includes(r.error), '缺仓库名时应提示地址形状，实际: ' + r.error)
  assert.ok(badUrlText.includes(validateRepoUrl('hello world').error))
})

test('validateRepoUrl 空串判不通过，但不给错误文案（输入框还空着，按钮本就禁用）', () => {
  const r = validateRepoUrl('')
  assert.equal(r.ok, false)
  assert.ok(!r.error, '空输入不该弹错误，实际: ' + r.error)
  assert.equal(validateRepoUrl('   ').ok, false)
  assert.equal(validateRepoUrl(null).ok, false)
  assert.equal(validateRepoUrl(undefined).ok, false)
})

// ==================== 清单 ====================

test('loadLinks 按项目拉清单并灌进模块级 store', async () => {
  const f = stubFetch(() => jsonReply({
    code: 0,
    links: [{ id: 4, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main', tokenLast4: 'cd12' }]
  }))
  try {
    await loadLinks(SETTINGS, 11)
    assert.equal(f.calls.length, 1)
    assert.match(f.calls[0].url, /\/api\/addin\/git-links\?projectId=11$/)
    assert.equal(f.calls[0].options.headers['X-Session-Id'], 'awdt_x')
    assert.equal(links.value.length, 1)
    assert.equal(links.value[0].repo, 'docs')
  } finally {
    f.restore()
  }
})

test('loadLinks 拉取期间先清空：换项目重开面板时不拿上个项目的仓库顶名', async () => {
  links.value = [{ id: 99, provider: 'github', owner: 'old', repo: 'project-a', branch: 'main' }]
  let duringFetch = null
  const f = stubFetch(() => {
    duringFetch = links.value.slice()
    return jsonReply({ code: 0, links: [] })
  })
  try {
    await loadLinks(SETTINGS, 22)
    assert.deepEqual(duringFetch, [], '拉取期间界面上还挂着上一个项目的仓库')
  } finally {
    f.restore()
  }
})

test('loadLinks 没有项目时清空并且不发请求（git 关联是挂在项目上的）', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, links: [] }))
  try {
    await loadLinks(SETTINGS, '')
    assert.equal(f.calls.length, 0)
    assert.deepEqual(links.value, [])
  } finally {
    f.restore()
  }
})

// ==================== 新增 ====================

test('addLink 本地先挡一遍：地址不合规时一条请求都不发', async () => {
  const f = stubFetch(() => jsonReply({ code: 0 }))
  try {
    await assert.rejects(
      () => addLink(SETTINGS, { projectId: 11, url: 'https://gitlab.com/a/b', token: 'ghp_x' }),
      (e) => e.message === ZH.gitLinkOnlyHosts || e.message === EN.gitLinkOnlyHosts
    )
    assert.equal(f.calls.length, 0, '地址没过本地校验却已经把令牌发出去了')
  } finally {
    f.restore()
  }
})

test('addLink 把地址/分支/令牌交给后端校验并把返回的关联并进 store', async () => {
  links.value = []
  const f = stubFetch(() => jsonReply({
    code: 0,
    link: { id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main', tokenLast4: 'p_x1' }
  }))
  try {
    const link = await addLink(SETTINGS, {
      projectId: 11, url: '  https://github.com/acme/docs  ', branch: ' main ', token: ' ghp_x1 '
    })
    assert.equal(f.calls.length, 1)
    assert.equal(f.calls[0].options.method, 'POST')
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.projectId, 11)
    assert.equal(body.url, 'https://github.com/acme/docs')
    assert.equal(body.branch, 'main')
    assert.equal(body.token, 'ghp_x1')
    assert.equal(link.id, 7)
    assert.equal(links.value.length, 1)
    assert.equal(links.value[0].id, 7)
  } finally {
    f.restore()
  }
})

test('addLink 之后令牌不留在 store 里（凭据转交完就忘掉）', async () => {
  links.value = []
  const f = stubFetch(() => jsonReply({
    code: 0,
    link: { id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main', tokenLast4: 'p_x1' }
  }))
  try {
    await addLink(SETTINGS, { projectId: 11, url: 'https://github.com/acme/docs', token: 'ghp_secret_x1' })
    assert.ok(!JSON.stringify(links.value).includes('ghp_secret_x1'), '令牌明文留在了 store 里')
  } finally {
    f.restore()
  }
})

test('addLink 撞已有关联时按 id 覆盖，不在清单里留两行同一个仓库', async () => {
  links.value = [{ id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main', tokenLast4: 'old0' }]
  const f = stubFetch(() => jsonReply({
    code: 0,
    link: { id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'dev', tokenLast4: 'new9' }
  }))
  try {
    await addLink(SETTINGS, { projectId: 11, url: 'https://github.com/acme/docs', branch: 'dev', token: 'ghp_y' })
    assert.equal(links.value.length, 1)
    assert.equal(links.value[0].branch, 'dev')
    assert.equal(links.value[0].tokenLast4, 'new9')
  } finally {
    f.restore()
  }
})

test('服务端的 message 原样上浮（未配密钥 503 / 令牌无效 400）', async () => {
  const f = stubFetch(() => jsonReply({ code: 503, message: '服务器未配置 git 令牌密钥' }))
  try {
    await assert.rejects(
      () => addLink(SETTINGS, { projectId: 11, url: 'https://github.com/acme/docs', token: 'ghp_x' }),
      /服务器未配置 git 令牌密钥/
    )
  } finally {
    f.restore()
  }
  const f2 = stubFetch(() => jsonReply({ code: 400, message: '仓库访问失败：令牌无效' }, false, 400))
  try {
    await assert.rejects(
      () => addLink(SETTINGS, { projectId: 11, url: 'https://github.com/acme/docs', token: 'ghp_x' }),
      /令牌无效/
    )
  } finally {
    f2.restore()
  }
})

test('后端不可达时报「连不上」而不是把 TypeError 抛给用户', async () => {
  const f = stubFetch(() => { throw new TypeError('Failed to fetch') })
  try {
    await assert.rejects(
      () => addLink(SETTINGS, { projectId: 11, url: 'https://github.com/acme/docs', token: 'ghp_x' }),
      (e) => e.message === ZH.apiBackendUnreachable || e.message === EN.apiBackendUnreachable
    )
  } finally {
    f.restore()
  }
})

// ==================== 解除 ====================

test('removeLink 删服务端那行并从 store 里摘掉', async () => {
  links.value = [
    { id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main' },
    { id: 8, provider: 'gitee', owner: 'acme', repo: 'notes', branch: 'master' }
  ]
  const f = stubFetch(() => jsonReply({ code: 0 }))
  try {
    await removeLink(SETTINGS, 7)
    assert.equal(f.calls.length, 1)
    assert.equal(f.calls[0].options.method, 'DELETE')
    assert.match(f.calls[0].url, /\/api\/addin\/git-links\/7$/)
    assert.deepEqual(links.value.map((l) => l.id), [8])
  } finally {
    f.restore()
  }
})

test('removeLink 服务端拒绝时不动 store（界面显示的必须是服务端的真实状态）', async () => {
  links.value = [{ id: 7, provider: 'github', owner: 'acme', repo: 'docs', branch: 'main' }]
  const f = stubFetch(() => jsonReply({ code: 1, message: '无权删除' }))
  try {
    await assert.rejects(() => removeLink(SETTINGS, 7), /无权删除/)
    assert.deepEqual(links.value.map((l) => l.id), [7])
  } finally {
    f.restore()
  }
})

// ==================== 文案与接线 ====================

const REQUIRED_KEYS = [
  'gitLinkTitle', 'gitLinkUrl', 'gitLinkBranch', 'gitLinkToken', 'gitLinkSave',
  'gitLinkVerifying', 'gitLinkRemove', 'gitLinkOnlyHosts', 'gitLinkBadUrl',
  'gitLinkHint', 'gitLinkNoProject', 'menuGitLink'
]

test('git 关联的文案 ZH/EN 两本字典都有', () => {
  const missingZh = REQUIRED_KEYS.filter((k) => !Object.prototype.hasOwnProperty.call(ZH, k))
  const missingEn = REQUIRED_KEYS.filter((k) => !Object.prototype.hasOwnProperty.call(EN, k))
  assert.deepEqual(missingZh, [], 'ZH 缺: ' + missingZh.join(', '))
  assert.deepEqual(missingEn, [], 'EN 缺: ' + missingEn.join(', '))
})

test("GitLinkPanel.vue 里 t('…') 用到的 key 都在字典里", () => {
  const src = read('../components/GitLinkPanel.vue')
  const used = new Set()
  for (const m of src.matchAll(/\bt\(\s*'([A-Za-z0-9_]+)'/g)) used.add(m[1])
  assert.ok(used.size > 0, '一个 t() 都没有？扫描正则或文件路径不对')
  const missing = [...used].filter((k) => !Object.prototype.hasOwnProperty.call(ZH, k)
    || !Object.prototype.hasOwnProperty.call(EN, k))
  assert.deepEqual(missing, [], '用了字典里没有的 key: ' + missing.join(', '))
})

test('地址校验的结果显示在界面上（否则按钮禁用而用户不知道为什么）', () => {
  const src = read('../components/GitLinkPanel.vue')
  assert.match(src, /const urlError = computed\(\(\) => validateRepoUrl\(url\.value\)\.error/,
    '没有把地址校验的错误算出来')
  assert.match(src, /v-if="urlError"/, '算出来了却没渲染：用户面对的还是一个按不动又不解释的按钮')
})

test('令牌输入框是 password 类型，且保存后清空（窗格常驻，明文会一直挂在屏幕上）', () => {
  const src = read('../components/GitLinkPanel.vue')
  assert.match(src, /type="password"/, '令牌输入框不是 password 类型')
  assert.match(src, /token\.value\s*=\s*''/, '保存后没有清空令牌输入框')
})

test('App.vue 挂了面板，账户菜单有入口', () => {
  const app = read('../App.vue')
  assert.match(app, /import GitLinkPanel from '\.\/components\/GitLinkPanel\.vue'/, '面板没有被 App.vue 引入')
  assert.match(app, /<GitLinkPanel/, '面板没有挂在 App.vue 的模板里')
  assert.match(app, /gitLinkOpen/, 'App.vue 没有面板开关')
  assert.match(app, /t\('menuGitLink'\)/, '账户菜单里没有「关联 git 仓库」入口')
})

test('i18n.test.js 的裸中文扫描覆盖了新面板', () => {
  assert.match(read('./i18n.test.js'), /components\/GitLinkPanel\.vue/,
    'SCAN_FILES 没加 GitLinkPanel.vue：面板里的裸中文没人守')
})
