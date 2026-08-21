/**
 * HOUSE 单源对拍（dev-board#111）：taskpane/lib/house-default.json 是后端
 * backend/src/main/resources/style-profiles/house-default.json 的字节副本，officeExecutor.js
 * 的 HOUSE 从它派生。副本与源 sha256 不一致 = 改了源没重跑 frontend/scripts/sync-house-profile.mjs，
 * 或有人手改了副本——两种都不许静默通过。
 *   node --test office-addin/taskpane/lib/houseProfile.test.js
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { fileURLToPath } from 'node:url'

import { houseFromProfile } from './officeExecutor.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const COPY = path.join(here, 'house-default.json')
const SOURCE = path.resolve(here, '../../../backend/src/main/resources/style-profiles/house-default.json')
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex')

test('office-addin 副本与后端 house-default.json 字节 sha256 一致', () => {
  assert.ok(fs.existsSync(SOURCE), '后端源缺失: ' + SOURCE)
  assert.equal(sha256(fs.readFileSync(COPY)), sha256(fs.readFileSync(SOURCE)),
    '副本与源不一致：cd frontend && node scripts/sync-house-profile.mjs')
})

test('HOUSE 从画像派生出与改造前常量相同的数值', () => {
  const h = houseFromProfile(JSON.parse(fs.readFileSync(COPY, 'utf8')))
  assert.deepEqual(h, {
    fontAsian: '楷体_GB2312', fontWestern: 'Arial', bodyPt: 12, titlePt: 16,
    spaceAfterPt: 18, lineSpacingPt: 16, firstLineIndentPt: 24, tablePt: 10
  })
})
