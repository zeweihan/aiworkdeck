// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { catalogOptions } from './projectCatalog.js'
test('same identity appears once, cloud project remains routable, names do not merge', () => {
  const rows = [{ projectUid: 'one', name: 'same', locations: [{ kind:'cloud',cloudProjectId: 1 }, {kind:'desktop',deviceId:'a',key:'8',online:true}] },
    {projectUid:'two',name:'same',locations:[{kind:'desktop',deviceId:'b',key:'8',online:false},{kind:'desktop',deviceId:'c',key:'9',online:true}]}]
  const result=catalogOptions(rows,[{id:1,name:'same'}])
  assert.equal(result.projects.length,1);assert.equal(result.devices.length,1)
  assert.equal(result.devices[0].deviceId,'c');assert.equal(result.devices[0].projects[0].key,'9')
})
