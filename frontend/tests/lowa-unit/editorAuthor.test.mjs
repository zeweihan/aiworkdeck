// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 修订署名的取数层（utils/editorAuthor.js）。
//
// 病灶（v0.44.1 真机）：引擎对空作者有自己的兜底——zh-CN 语言包里就是「未知作者」。
// 宿主原来只读 uni 本地缓存里的登录用户（utils/auth.js 的 getCurrentUser），而桌面端
// local-mode 免登**从不写那个键**（saveSession 只在登录页调用），于是整机没登录过的
// 安装上，用户自己的每一条修订（手打、工具栏、查找替换）都署「未知作者」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pickAuthorName, createAuthorNameResolver } from '../../src/utils/editorAuthor.js'

test('pickAuthorName 只认展示字段，且不回落 username（Spec §6）', () => {
  assert.equal(pickAuthorName({ displayName: '韩泽伟' }), '韩泽伟')
  assert.equal(pickAuthorName({ nickname: '小韩' }), '小韩')
  assert.equal(pickAuthorName({ name: '老韩' }), '老韩')
  // username 是 `u`+随机串，落进修订就是永久的——宁可空着让引擎兜底
  assert.equal(pickAuthorName({ username: 'u8f3a91' }), '')
  assert.equal(pickAuthorName({ displayName: '   ' }), '')
  assert.equal(pickAuthorName(null, undefined, ''), '')
  // 多个来源：先到先得（本地缓存优先于远端）
  assert.equal(pickAuthorName({ displayName: '缓存' }, { displayName: '远端' }), '缓存')
  assert.equal(pickAuthorName(null, { displayName: '远端' }), '远端')
})

test('缓存里有名字：一次远端请求都不发', async () => {
  let fetched = 0
  const r = createAuthorNameResolver({
    readCached: () => ({ displayName: '韩泽伟' }),
    fetchRemote: async () => { fetched++; return { displayName: '远端' } },
  })
  assert.equal(r.current(), '韩泽伟')
  assert.equal(await r.resolve(), '韩泽伟')
  assert.equal(fetched, 0)
})

test('缓存里没有名字：问一次后端，之后 current() 同步就能拿到', async () => {
  let fetched = 0
  const r = createAuthorNameResolver({
    readCached: () => null,
    fetchRemote: async () => { fetched++; return { displayName: '本机用户' } },
  })
  assert.equal(r.current(), '', '解析之前只能是空串——不许编造名字')
  assert.equal(await r.resolve(), '本机用户')
  assert.equal(r.current(), '本机用户')
  assert.equal(await r.resolve(), '本机用户')
  assert.equal(fetched, 1, '一个进程内只问一次')
})

test('并发 resolve 只发一次请求（单飞）', async () => {
  let fetched = 0
  let release = null
  const r = createAuthorNameResolver({
    readCached: () => null,
    fetchRemote: () => { fetched++; return new Promise((res) => { release = () => res({ displayName: '甲' }) }) },
  })
  const a = r.resolve()
  const b = r.resolve()
  await new Promise((res) => setTimeout(res, 0))   // fetchRemote 在微任务里才被调到
  release()
  assert.deepEqual(await Promise.all([a, b]), ['甲', '甲'])
  assert.equal(fetched, 1)
})

test('后端读不到（未登录 / 老后端 / 网络错）不抛、不重试：署名退回引擎兜底', async () => {
  let fetched = 0
  const r = createAuthorNameResolver({
    readCached: () => null,
    fetchRemote: async () => { fetched++; throw new Error('401') },
  })
  assert.equal(await r.resolve(), '')
  assert.equal(await r.resolve(), '', '失败后不再打网络——署名拿不到不值得反复重试')
  assert.equal(fetched, 1)
})
