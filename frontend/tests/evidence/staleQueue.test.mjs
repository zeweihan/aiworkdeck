import test from 'node:test'
import assert from 'node:assert/strict'
import { StaleQueue } from '../../src/utils/evidenceStaleQueue.js'

function clock(start = 0) {
  let t = start
  return { now: () => t, tick: (ms) => { t += ms } }
}

test('same key within 3s window is offered once', () => {
  const c = clock()
  const q = new StaleQueue({ now: c.now })
  assert.equal(q.offer('A', 'x'), true)
  assert.deepEqual(q.flush(), [{ linkKey: 'A', text: 'x' }])
  c.tick(1000)
  assert.equal(q.offer('A', 'y'), false)
  assert.deepEqual(q.flush(), [])
  c.tick(2500)
  assert.equal(q.offer('A', 'z'), true)
  assert.deepEqual(q.flush(), [{ linkKey: 'A', text: 'z' }])
})

test('pending re-offer before flush keeps latest text, no duplicate', () => {
  const q = new StaleQueue({ now: () => 0 })
  q.offer('A', 'x')
  q.offer('A', 'y')
  assert.deepEqual(q.flush(), [{ linkKey: 'A', text: 'y' }])
})

test('ignore blocks future offers and drops pending', () => {
  const q = new StaleQueue({ now: () => 0 })
  q.offer('A', 'x')
  q.ignore('A')
  assert.deepEqual(q.flush(), [])
  assert.equal(q.offer('A', 'x'), false)
  assert.equal(q.isIgnored('A'), true)
  assert.equal(q.offer('B', 'x'), true)
})

test('flush merges multiple keys into one batch', () => {
  const q = new StaleQueue({ now: () => 0 })
  q.offer('A', 'a'); q.offer('B', 'b'); q.offer('C', 'c')
  const items = q.flush()
  assert.deepEqual(items.map((i) => i.linkKey), ['A', 'B', 'C'])
  assert.deepEqual(q.flush(), [])
})
