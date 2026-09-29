// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/components/MarketDetailPane.vue', import.meta.url), 'utf8')
// 截到下一个方法为止（原来是 goToAccountSettings；登录后置后那个方法换成了就地登录的 loginThenContinue）
const action = source.slice(source.indexOf('    goToBuiltinUpdates() {'), source.indexOf('    async loginThenContinue() {'))
const goToBuiltinUpdates = new Function('return ({' + action + '}).goToBuiltinUpdates')()
test('the built-in plugin update button opens updates in the existing workbench', () => {
  let selection
  goToBuiltinUpdates.call({ openSettingsTab: value => { selection = value } })
  assert.deepEqual(selection, { nav: 'updates' })
})
test('outside a settings-tab host, the save-aware workbench exit is used when supplied', () => {
  let destination
  goToBuiltinUpdates.call({ leaveWorkbench: value => { destination = value } })
  assert.equal(destination, '/pages/admin/admin?nav=updates')
})
