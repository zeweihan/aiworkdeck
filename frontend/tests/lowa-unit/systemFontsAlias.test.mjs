// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 本机字体与 CJK 别名的交互（zetaOfficeBoot.buildCjkAliasConf）：用户启用了真的
// 宋体/仿宋_GB2312 时，同名 assign 别名规则必须跳过，否则真字体被劫持到随包 Noto 上；
// 没启用的专有名仍照常映射到随包同类字体。另守注入文件名唯一。

import test from 'node:test'
import assert from 'node:assert/strict'
import { buildCjkAliasConf, systemFontFileName } from '../../src/composables/zetaOfficeBoot.js'

const BUNDLED = { sans: 'Noto Sans SC', serif: 'Noto Serif SC', kai: '霞鹜文楷', fangsong: '朱雀仿宋（预览测试版）' }
const hasRule = (conf, fam) => conf.includes('<string>' + fam + '</string></test>')

test('没有本机字体：与原先一致，全部专有名都有别名规则', () => {
  const r = buildCjkAliasConf(BUNDLED, [])
  assert.ok(r)
  assert.equal(r.skipped, 0)
  for (const fam of ['宋体', 'SimSun', '仿宋_GB2312', '黑体', '楷体']) assert.ok(hasRule(r.conf, fam), fam)
  assert.deepEqual(r.targets, ['Noto Sans SC', 'Noto Serif SC', '霞鹜文楷', '朱雀仿宋（预览测试版）'])
  assert.ok(r.conf.includes('mode="assign"'))
})

test('启用了真的仿宋_GB2312 / 宋体：同名规则跳过（大小写与空白不敏感），其余照常', () => {
  const r = buildCjkAliasConf(BUNDLED, new Set(['仿宋_GB2312', 'fangsong_gb2312', '宋体', 'Sim Sun']))
  assert.equal(r.skipped, 4) // 仿宋_GB2312、FangSong_GB2312、宋体、SimSun
  for (const fam of ['仿宋_GB2312', 'FangSong_GB2312', '宋体', 'SimSun']) assert.ok(!hasRule(r.conf, fam), fam)
  for (const fam of ['仿宋', '新宋体', '黑体', '楷体_GB2312']) assert.ok(hasRule(r.conf, fam), fam)
})

test('随包字体全缺：没有别名目标，返回 null', () => {
  assert.equal(buildCjkAliasConf({}, ['宋体']), null)
})

test('仅有 sans：其余类别落到 sans；XML 特殊字符被转义', () => {
  const r = buildCjkAliasConf({ sans: 'A&B <Sans>' }, [])
  assert.ok(r.conf.includes('A&amp;B &lt;Sans&gt;'))
  assert.deepEqual(r.targets, ['A&B <Sans>', 'A&B <Sans>', 'A&B <Sans>', 'A&B <Sans>'])
})

test('注入文件名：带序号唯一，只留安全字符', () => {
  assert.equal(systemFontFileName(5, '仿宋_GB2312.ttf'), 'SYS-5-___GB2312.ttf')
  assert.notEqual(systemFontFileName(4, 'a.ttf'), systemFontFileName(5, 'a.ttf'))
  assert.equal(systemFontFileName(0, ''), 'SYS-0-font')
  assert.ok(!systemFontFileName(1, '../../etc/x.ttf').includes('/'))
})
