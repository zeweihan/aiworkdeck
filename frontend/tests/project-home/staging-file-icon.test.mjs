// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#973（v0.49.0 BUG-77 / C9-08）：「文件暂存区」条目的文件图标是空白。
//   cd frontend && node --test tests/project-home/staging-file-icon.test.mjs
//
// 根因：FileStagingArea.vue 自己抄了一份扩展名 → 图标映射（getFileIcon），指向
// /static/file.png、/static/word.png、/static/pdf.png——这三张图 static/ 里根本没有，
// <image> 加载失败就是一块空白。资源管理器的文件行用的是 FileTypeIcon 组件
// （:type="item.fileType"），暂存区改为复用同一个组件，不再另留映射表。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')
const STAGING = read('../../src/components/FileStagingArea.vue')
const TREE = read('../../src/components/FileTree.vue')

test('资源管理器文件行的图标来源是 FileTypeIcon :type="item.fileType"（对照基准）', () => {
  assert.match(TREE, /<FileTypeIcon :type="item\.fileType"/)
  assert.match(TREE, /import FileTypeIcon from '@\/components\/FileTypeIcon\.vue'/)
})

test('暂存区条目复用同一个 FileTypeIcon 组件，按 file.fileType 取图标', () => {
  assert.match(STAGING, /import FileTypeIcon from '@\/components\/FileTypeIcon\.vue'/)
  assert.match(STAGING, /components:\s*\{[^}]*\bFileTypeIcon\b[^}]*\}/)
  assert.match(STAGING, /<FileTypeIcon :type="file\.fileType"/)
})

test('暂存区不再自带扩展名 → 图片映射，也不再引用不存在的静态图', () => {
  assert.doesNotMatch(STAGING, /getFileIcon/)
  for (const png of ['file.png', 'word.png', 'pdf.png']) {
    assert.doesNotMatch(STAGING, new RegExp(`/static/${png.replace('.', '\\.')}`))
    // 顺带钉住根因：这几张图确实不存在，别有人「补一张图」当修复
    assert.equal(existsSync(new URL(`../../src/static/${png}`, import.meta.url)), false)
  }
})
