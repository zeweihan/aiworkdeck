// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 提示文案的硬性内容要求（设计 §0 用户硬性要求 / §4.3）：每一条提示都必须同时说清
// 下载什么、多大、解锁哪些功能、不下载则哪些功能不可用。
import test from 'node:test'
import assert from 'node:assert/strict'
import zh from '../../src/locales/zh-CN/components.js'
import en from '../../src/locales/en-US/components.js'

const PACKS = ['pptxRuntime', 'mineruRuntime', 'kokoroRuntime', 'asrRuntime']

test('四个组件各有 name / unlocks / impact，两语言齐全', () => {
  for (const p of PACKS) {
    for (const k of ['name', 'unlocks', 'impact']) {
      assert.ok(zh[p] && zh[p][k], `zh-CN 缺 components.${p}.${k}`)
      assert.ok(en[p] && en[p][k], `en-US 缺 components.${p}.${k}`)
    }
  }
})

test('提示模板带全部占位符：组件名 / 运行时体积 / 含模型体积 / 落盘位置 / 卸载入口', () => {
  for (const [lang, m] of [['zh-CN', zh], ['en-US', en]]) {
    for (const ph of ['{name}', '{runtime}', '{total}']) {
      assert.ok(m.promptWithModel.includes(ph), `${lang} promptWithModel 缺 ${ph}`)
    }
    assert.ok(m.promptNoModel.includes('{name}') && m.promptNoModel.includes('{runtime}'))
    assert.ok(!m.promptNoModel.includes('{total}'), '无模型的组件不该出现「含模型约」')
    assert.ok(m.promptWithModel.includes('~/.aiworkdeck'), `${lang} 没写落盘位置`)
    assert.ok(m.promptUsage.includes('{unlocks}') && m.promptUsage.includes('{impact}'),
      `${lang} promptUsage 必须同时说「用于什么」与「不装则什么不可用」`)
  }
})

test('动作与状态键清单齐备（两个下载入口的文案是分开的：批量 vs 单卡）', () => {
  // installSelected = 面板底部「立即下载所选」（复数）；installOne = 组件管理里
  // 单张卡自己的按钮。共用一条会让单卡上写着「立即下载所选」，选都没得选。
  const keys = ['panelTitle', 'panelSubtitle', 'installSelected', 'installOne', 'later',
    'laterHint', 'manageTitle', 'sizeUnknown', 'retry', 'progressTotal',
    'stateNotInstalled', 'stateReady', 'stateDownloadingRuntime', 'stateDownloadingModel',
    'stateStartingService', 'stateFailed']
  for (const k of keys) {
    assert.ok(typeof zh[k] === 'string' && zh[k], `zh-CN 缺 components.${k}`)
    assert.ok(typeof en[k] === 'string' && en[k], `en-US 缺 components.${k}`)
  }
})

test('八个功能键齐备（后端 featureKeys 的对面）', () => {
  const keys = ['pptxGenerate', 'pptxFormat', 'pdfToWordLayout', 'scannedOcrEntry',
    'scannedPdfToWord', 'ocrParse', 'ttsPanel', 'localTranscription']
  for (const k of keys) {
    assert.ok(zh.features[k], `zh-CN 缺 components.features.${k}`)
    assert.ok(en.features[k], `en-US 缺 components.features.${k}`)
  }
})

test('英文文案里不许残留中文（英文界面下会当场露馅）', () => {
  const walk = (o, p = '') => Object.entries(o).forEach(([k, v]) =>
    typeof v === 'object' ? walk(v, `${p}${k}.`) : assert.ok(!/[一-龥]/.test(v), `en-US ${p}${k} 含中文：${v}`))
  walk(en)
})

test('两语言键集合完全一致（check:locales 的本地前哨）', () => {
  const flat = (o, p = '') => Object.entries(o).flatMap(([k, v]) =>
    typeof v === 'object' ? flat(v, `${p}${k}.`) : [`${p}${k}`])
  assert.deepEqual(flat(zh).sort(), flat(en).sort())
})
