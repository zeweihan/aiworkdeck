// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * manifest.xml 的三宿主功能区结构守卫（dev-board#714）。
 *
 * 为什么要这条测试：Word / Excel / PowerPoint 三个 VersionOverrides 宿主段是
 * 逐字复制出来的三份，除了 Group id / Control id 之外必须完全同构。漏掉或改坏
 * 其中一份，`office-addin-manifest validate` **不会报错**（缺一个宿主段仍是合法清单），
 * 装上去的表现是「那个宿主的功能区上没有 AI WorkDeck 组」——而这恰好和平台侧
 * 的旁加载问题长得一模一样，排查时会先怀疑错方向、浪费一轮真机往返。
 *
 * 这条测试**不能**发现 dev-board#714 的真实成因（那是 Mac PowerPoint 旁加载
 * 自身的行为，清单是对的，见下面的说明），它挡的是「以后有人把 PPT 段删了/
 * 改坏了」这种真·清单回归。
 *
 * 跑法：npm test（package.json 的 test 脚本已 glob 到 scripts/*.test.mjs）
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const manifest = fs.readFileSync(path.join(rootDir, 'manifest.xml'), 'utf8')

/** 三个宿主：VersionOverrides 里的 xsi:type、外层 <Hosts> 里的 Name、各自的 Group/Control id。 */
const HOSTS = [
  { xsiType: 'Document', hostName: 'Document', groupId: 'AwdGroup', buttonId: 'AwdTaskpaneButton' },
  { xsiType: 'Workbook', hostName: 'Workbook', groupId: 'AwdGroupExcel', buttonId: 'AwdTaskpaneButtonExcel' },
  { xsiType: 'Presentation', hostName: 'Presentation', groupId: 'AwdGroupPpt', buttonId: 'AwdTaskpaneButtonPpt' },
]

/** 取 VersionOverrides 里某个宿主段的整块文本。 */
function hostBlock(xsiType) {
  const re = new RegExp(`<Host xsi:type="${xsiType}">[\\s\\S]*?</Host>`)
  return (manifest.match(re) || [null])[0]
}

test('外层 <Hosts> 三个宿主都在（决定 Office 哪几个应用认这个加载项）', () => {
  for (const { hostName } of HOSTS) {
    assert.match(
      manifest,
      new RegExp(`<Host Name="${hostName}"\\s*/>`),
      `外层 <Hosts> 缺 <Host Name="${hostName}"/>，该宿主根本不会加载这个插件`
    )
  }
})

test('三个宿主各有一段 VersionOverrides Host', () => {
  for (const { xsiType } of HOSTS) {
    assert.ok(
      hostBlock(xsiType),
      `VersionOverrides 里缺 <Host xsi:type="${xsiType}">——该宿主会变成「只能从'插入→加载项'插入、功能区没有按钮」`
    )
  }
})

test('三个宿主的按钮都挂在「开始」选项卡（TabHome）的自有组里', () => {
  for (const { xsiType, groupId } of HOSTS) {
    const block = hostBlock(xsiType)
    assert.ok(block, `找不到 ${xsiType} 宿主段`)
    // TabHome 对 Word/Excel/PowerPoint 三家都是合法的内置「开始」选项卡 id：
    // https://learn.microsoft.com/office/dev/add-ins/develop/built-in-ui-ids
    assert.match(
      block,
      /<ExtensionPoint xsi:type="PrimaryCommandSurface">/,
      `${xsiType} 宿主缺 PrimaryCommandSurface 扩展点`
    )
    assert.match(block, /<OfficeTab id="TabHome">/, `${xsiType} 宿主的按钮不在 TabHome 上`)
    assert.match(
      block,
      new RegExp(`<Group id="${groupId}">`),
      `${xsiType} 宿主的组 id 不是 ${groupId}`
    )
  }
})

test('每个宿主组里都有一个打开任务窗格的按钮，且三个 Control id 互不相同', () => {
  const seen = new Set()
  for (const { xsiType, buttonId } of HOSTS) {
    const block = hostBlock(xsiType)
    assert.match(
      block,
      new RegExp(`<Control xsi:type="Button" id="${buttonId}">`),
      `${xsiType} 宿主缺按钮 ${buttonId}`
    )
    assert.match(
      block,
      /<Action xsi:type="ShowTaskpane">[\s\S]*?<TaskpaneId>AwdTaskpane<\/TaskpaneId>[\s\S]*?<SourceLocation resid="Taskpane\.Url"\/>[\s\S]*?<\/Action>/,
      `${xsiType} 宿主的按钮没有指向 Taskpane.Url 的 ShowTaskpane 动作`
    )
    assert.ok(!seen.has(buttonId), `Control id 重复：${buttonId}`)
    seen.add(buttonId)
  }
  assert.equal(seen.size, HOSTS.length)
})

test('三个宿主的组与按钮都给齐 16/32/80 三档图标', () => {
  for (const { xsiType } of HOSTS) {
    const block = hostBlock(xsiType)
    for (const size of [16, 32, 80]) {
      const hits = block.match(new RegExp(`<bt:Image size="${size}" resid="Icon\\.${size}x${size}"/>`, 'g')) || []
      // 组一处、按钮一处，共两处。
      assert.equal(
        hits.length,
        2,
        `${xsiType} 宿主的 ${size}px 图标应出现 2 次（组 + 按钮），实际 ${hits.length} 次`
      )
    }
  }
})

test('三个宿主段除 Group/Control id 外逐字同构（防止只改一份）', () => {
  // 只归一 id="..." 属性值，不做裸串替换：三个宿主段里都有 <Label resid="AwdGroup.Label"/>，
  // 裸串替换会把 Document 那份的 resid 一起改掉（"AwdGroup" 是 "AwdGroupExcel" 的前缀），
  // 三份就永远对不齐了。
  const normalized = HOSTS.map(({ xsiType, groupId, buttonId }) =>
    hostBlock(xsiType)
      .replace(new RegExp(`<Host xsi:type="${xsiType}">`), '<Host>')
      .replace(new RegExp(`id="${groupId}"`, 'g'), 'id="GROUP"')
      .replace(new RegExp(`id="${buttonId}"`, 'g'), 'id="BUTTON"')
  )
  // Workbook / Presentation 都必须和 Document 那份一模一样。
  assert.equal(
    normalized[1],
    normalized[0],
    'Workbook 宿主段与 Document 宿主段结构不一致'
  )
  assert.equal(
    normalized[2],
    normalized[0],
    'Presentation 宿主段与 Document 宿主段结构不一致（PowerPoint 功能区会缺组）'
  )
})
