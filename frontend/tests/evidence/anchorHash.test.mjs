import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { normalizeAnchor, anchorHash } from '../../src/utils/anchorHash.js'

const vectors = JSON.parse(readFileSync(new URL('./anchor-hash-vectors.json', import.meta.url)))

test('normalize matches shared vectors', () => {
  for (const v of vectors) assert.equal(normalizeAnchor(v.in), v.norm)
})

test('hash matches shared vectors (double-end parity)', async () => {
  for (const v of vectors) assert.equal(await anchorHash(v.in), v.hash)
})

test('hash is 64 hex and whitespace-insensitive', async () => {
  assert.match(await anchorHash('abc'), /^[0-9a-f]{64}$/)
  assert.equal(await anchorHash('a b'), await anchorHash('ab'))
})
