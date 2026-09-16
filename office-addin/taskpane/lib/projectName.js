// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 自动建出来的「插件临时项目」的展示名（dev-board#713）。
 *
 * 后端 ProjectController 懒建这个项目时按**当时的**语言取名（LangText.of），名字之后就
 * 原样存在库里。于是一个中文界面下建过项目的用户切到英文，下拉里照样挂着「插件临时项目」——
 * AppSource 政策 1100.7 要求界面语言一致，这条会被挑出来。
 *
 * 只做**展示层**映射，不改库里的数据：项目名是用户可改的字段，后台按界面语言偷偷改名
 * 会把用户自己起的名字覆盖掉，而且两台机器语言不同时会来回翻。
 *
 * 判据是「这个名字恰好是后端两个固定名之一」——用户把自己的项目就叫这个名字的概率可以忽略，
 * 而漏判的代价只是显示成另一种语言，不会丢数据。
 */
import { t } from './i18n.js'

/**
 * 后端 ProjectController 的 ADDIN_DEFAULT_PROJECT_NAME_ZH / _EN 两个常量。
 * 改后端那两个字面量时必须同步这里，否则老项目的名字就翻不过来了（静默，不报错）。
 */
export const ADDIN_DEFAULT_PROJECT_NAMES = ['插件临时项目', 'Plugin Temporary Project']

/**
 * 项目名的展示值：插件懒建的临时项目按当前界面语言取名，其余项目原样返回。
 */
export function displayProjectName(name) {
  const raw = name == null ? '' : String(name)
  if (ADDIN_DEFAULT_PROJECT_NAMES.includes(raw.trim())) {
    return t('addinDefaultProjectName')
  }
  return raw
}
