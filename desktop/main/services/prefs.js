// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const fs = require('fs')
const path = require('path')

/**
 * 本机偏好的极简 KV（~/.aiworkdeck/prefs.json）。
 *
 * 为什么不是 localStorage：这里存的是「这台机器上这次安装是否已经提示过可选组件」，
 * 语义上属于安装，不属于浏览器会话——localStorage 被清一次用户就会被重新打扰一遍，
 * 而 ~/.aiworkdeck 在 DMG 覆盖安装与大版本升级时不被触碰（规范 §4.1），重装才重置。
 *
 * 为什么不引 electron-store：只有一个键，不值得多一个依赖与一套 schema 迁移。
 */
function createPrefs(ctx) {
  const file = path.join(ctx.dataDir, 'prefs.json')

  function readAll() {
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch (e) {
      return {} // 不存在 / 坏 JSON：按空表，绝不抛（它挂在启动链上）
    }
  }

  return {
    all: readAll,
    get(key, fallback) {
      const all = readAll()
      return Object.prototype.hasOwnProperty.call(all, key) ? all[key] : fallback
    },
    set(key, value) {
      const all = readAll()
      all[key] = value
      fs.mkdirSync(ctx.dataDir, { recursive: true })
      // tmp + rename：断电只会丢这次写入，不会留半个文件让下次读成坏 JSON（overlay.js 同款）
      const tmp = file + '.tmp'
      fs.writeFileSync(tmp, JSON.stringify(all, null, 2))
      fs.renameSync(tmp, file)
      return value
    },
  }
}

module.exports = { createPrefs }
