// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 复敏映射（.awd-recovery）另存为的默认位置。
//
// 病灶（dev-board C3）：will-download 的 setSaveDialogOptions 只给了裸文件名，
// 于是「另存为」开在系统上次用过的任意目录（真机上落到「7-常用图片」）。复敏文件
// 是**必须与脱敏副本分开保管**的密钥材料，随手落在图片目录里就是等着丢。
//
// 口径：项目文件夹之外的一个固定目录 ~/Documents/AI WorkDeck 复敏文件/
//（英文界面 AI WorkDeck Recovery），不存在就建。只对 .awd-recovery 生效，
// 其它下载仍沿用系统默认目录，行为不变。

const path = require('path')

const DIR_NAME = { zh: 'AI WorkDeck 复敏文件', en: 'AI WorkDeck Recovery' }
const EXTENSION = '.awd-recovery'

/** 这个下载是不是复敏映射文件。 */
function isRecoveryFile(filename) {
  return String(filename || '').toLowerCase().endsWith(EXTENSION)
}

/**
 * 复敏文件的「另存为」默认路径；不是复敏文件、或目录建不出来时返回 null
 * （调用方退回裸文件名，也就是原来的行为，不因为建目录失败而中断下载）。
 *
 * @param {string} filename 下载项的文件名（由面板给出，已带原文件名与时间戳）
 * @param {{documentsDir: string, language: string, mkdir?: Function}} env
 */
function recoveryDefaultPath(filename, env) {
  if (!isRecoveryFile(filename)) return null
  const documentsDir = env && env.documentsDir
  if (!documentsDir) return null
  const dirName = (env.language === 'en-US' ? DIR_NAME.en : DIR_NAME.zh)
  const dir = path.join(documentsDir, dirName)
  const mkdir = (env.mkdir) || ((target) => require('fs').mkdirSync(target, { recursive: true }))
  try {
    mkdir(dir)
  } catch (e) {
    return null
  }
  return path.join(dir, path.basename(String(filename)))
}

module.exports = { recoveryDefaultPath, isRecoveryFile, DIR_NAME, EXTENSION }
