// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 客户门户链接（dev-board#1050）：码放 fragment（#code=），不进服务器与 access log。
// 链接拼错一处，客户打开就是一个要他手输 20 位码的空表单，或者更糟——码进了查询串。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { clientPortalLink, codeFromHash, expiryDate } from '../../src/utils/memberLookup.js'

test('门户链接：码进 fragment，不进查询串', () => {
  const link = clientPortalLink('https://case.aiworkdeck.com/client/', 'Ab3xYz')
  assert.equal(link, 'https://case.aiworkdeck.com/client/#code=Ab3xYz')
  assert.ok(!link.includes('?'), '码不许进查询串')
})

test('门户链接：缺地址或缺码时不给链接', () => {
  assert.equal(clientPortalLink('', 'x'), '')
  assert.equal(clientPortalLink('https://a/client/', ''), '')
  assert.equal(clientPortalLink(null, null), '')
})

test('门户链接：地址里原有的 fragment 被替换而不是叠加', () => {
  assert.equal(clientPortalLink('https://a/client/#/pages/x', 'k'), 'https://a/client/#code=k')
})

test('从 hash 取码：标准形态与被路由改写过的形态都认', () => {
  assert.equal(codeFromHash('#code=Ab3xYz'), 'Ab3xYz')
  assert.equal(codeFromHash('code=Ab3xYz'), 'Ab3xYz')
  assert.equal(codeFromHash('#/pages/client-portal/client-portal?code=Q1'), 'Q1')
  assert.equal(codeFromHash('#/pages/client-portal/client-portal&code=Q2'), 'Q2')
  assert.equal(codeFromHash('#/'), '')
  assert.equal(codeFromHash(''), '')
})

test('往返：拼出来的链接能原样取回码', () => {
  const link = clientPortalLink('https://a/client/', 'aB9_z')
  assert.equal(codeFromHash(link.slice(link.indexOf('#'))), 'aB9_z')
})

test('有效期只取日期', () => {
  assert.equal(expiryDate('2026-10-29T10:11:12.123'), '2026-10-29')
  assert.equal(expiryDate(null), '')
  assert.equal(expiryDate('garbage'), '')
})
