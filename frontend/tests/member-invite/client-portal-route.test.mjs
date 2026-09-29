// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 客户门户构建的路由收口（dev-board#1050，src/utils/clientPortal.js）：门户包里只有三页，
// 其余硬编码跳转（4010 回登录页、退出登录回启动页……）必须改写回门户，否则是空白页。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { portalRoute, capturePortalCode, PORTAL_ENTRY } from '../../src/utils/clientPortal.js'
import { codeFromHash } from '../../src/utils/memberLookup.js'

test('三页之内原样放行（含查询串）', () => {
  assert.equal(portalRoute('/pages/project-list/project-list'), '/pages/project-list/project-list')
  assert.equal(portalRoute('/pages/project-overview/project-overview?id=3'), '/pages/project-overview/project-overview?id=3')
  assert.equal(portalRoute('/pages/client-portal/client-portal'), '/pages/client-portal/client-portal')
})

test('门户包里不存在的页面一律回门户', () => {
  for (const u of ['/pages/login/login', '/pages/launch/launch', '/pages/calendar/calendar',
    '/pages/admin/admin?nav=team', '/pages/unlock/unlock']) {
    assert.equal(portalRoute(u), PORTAL_ENTRY, u)
  }
})

test('取码后从地址栏清掉 hash，码交给门户页', () => {
  const calls = []
  const w = {
    location: { hash: '#code=Zz9', pathname: '/client/', search: '' },
    history: { replaceState: (...a) => calls.push(a) },
  }
  assert.equal(capturePortalCode(w, codeFromHash), 'Zz9')
  assert.equal(w.__AWD_PORTAL_CODE__, 'Zz9')
  assert.deepEqual(calls, [[null, '', '/client/']])
})

test('没有码时地址栏不动', () => {
  const calls = []
  const w = { location: { hash: '#/pages/x', pathname: '/client/', search: '' }, history: { replaceState: (...a) => calls.push(a) } }
  assert.equal(capturePortalCode(w, codeFromHash), '')
  assert.equal(calls.length, 0)
})
