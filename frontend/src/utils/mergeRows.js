// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 裁决总览里每一份文件那一行：行态判定与文案（规格
// docs/superpowers/specs/2026-09-14-docx-three-way-merge-design.md §5.3 的那张表）。
//
// 纯函数，不 import 任何东西（node --test 直接跑）。行的数据形状来自 /status 冲突
// 对象里的 documentMerges 元素：
//   {path, kind: DOCX|XLSX|PPTX|WHOLE, decision: AUTO|MANUAL|WHOLE,
//    reason: NO_BASE|BINARY|UNSUPPORTED|OVERLAP|CLEAN|TOO_LARGE|PARSE_FAILED,
//    mainChanges, otherChanges, overlapCount, state: PENDING|MERGED}
// 再加三个**只在前端出现**的字段（useDocumentMerge 跑自动合并时挂上去的）：
//   failed / failReason（自动合并没成）、mainCount / otherCount（引擎实际合了几处）、
//   formatOnlyCount（另一侧只改格式、没能自动带过来的段数）。
//
// 判定顺序是有讲究的，别按"看着顺"重排：
//   已经合好 > 整份文件 > 需要引擎但没有引擎 > 自动合并失败 > 还在自动合 > 逐处裁决。
// 把 MERGED 排在最前，是因为崩溃恢复后 /status 会同时给出 decision=AUTO 与
// state=MERGED——那份文件已经落盘了，再显示成"正在合并"会让律师等一个永远不来的结果；
// 把「整份文件」排在「非桌面端」前面，是因为 pdf 在桌面端也只能整份选，
// 说成"去桌面端就能逐处合"是假承诺。

// docx 要引擎（比较 + 逐段重放都在 LOWA 里跑）；xlsx/pptx 的合并文件由后端 POI 拼，
// 与有没有引擎无关——这条判据错了，Web 端会白白把表格行也降级成整份三选一。
function needsEngine(row) {
  return String(row && row.kind || '').toUpperCase() === 'DOCX'
}

export function mergeRowState(row, opts = {}) {
  const isDesktop = opts.isDesktop === undefined ? true : !!opts.isDesktop
  if (!row) return 'whole'
  const kind = String(row.kind || '').toUpperCase()
  const decision = String(row.decision || '').toUpperCase()
  if (String(row.state || '').toUpperCase() === 'MERGED') return 'merged'
  if (kind === 'WHOLE' || decision === 'WHOLE') return 'whole'
  if (needsEngine(row) && !isDesktop) return 'whole-nondesktop'
  if (row.failed) return 'auto-failed'
  if (decision === 'AUTO') return 'auto-running'
  if (kind === 'XLSX') return 'manual-xlsx'
  if (kind === 'PPTX') return 'manual-pptx'
  return 'manual-docx'
}

// 一侧的称呼。self 为真说的是律师自己（「你」），否则用后端给的展示名；
// 两样都没有才落到「同事」——**任何情况下都不许退回用户名**（那是账号 id，不是名字，
// 全局纪律见 CLAUDE.md「任何界面不显示 username」）。
function sideName(t, side) {
  if (side && side.self) return t('version.actorYou')
  const name = side && typeof side.authorName === 'string' ? side.authorName.trim() : ''
  return name || t('version.unnamedColleague')
}

const WHOLE_REASON_KEYS = {
  BINARY: 'version.mergeRowWholeBinary',
  TOO_LARGE: 'version.mergeRowWholeTooLarge',
  NO_BASE: 'version.mergeRowWholeNoBase',
}

// 自动合并失败的原因句。引擎回的 stage 有六种（load-other / compare-other / load-main /
// compare-main / align / replay），律师只需要分清两件事：「段落对不上了」（align，
// 说明这份文件两边的结构差太远，只能整份选）与「引擎没起来」（engine）；
// 其余统一说一句"没能合上"，不要把 stage 原文抖给律师看。
const FAIL_REASON_KEYS = {
  align: 'version.mergeFailReasonAlign',
  engine: 'version.mergeFailReasonEngine',
}

export function mergeRowText(t, row, sides = {}, opts = {}) {
  const state = mergeRowState(row, opts)
  switch (state) {
    case 'auto-running':
      return t('version.mergeAutoRunning')
    case 'merged': {
      const mainCount = Number(row.mainCount != null ? row.mainCount : row.mainChanges) || 0
      const otherCount = Number(row.otherCount != null ? row.otherCount : row.otherChanges) || 0
      return t('version.mergeRowMerged', {
        main: sideName(t, sides && sides.main),
        mainCount,
        other: sideName(t, sides && sides.other),
        otherCount,
      })
    }
    case 'manual-docx':
      return t('version.mergeRowManualDocx', { count: Number(row.overlapCount) || 0 })
    case 'manual-xlsx':
      return t('version.mergeRowManualXlsx', { count: Number(row.overlapCount) || 0 })
    case 'manual-pptx':
      return t('version.mergeRowManualPptx', { count: Number(row.overlapCount) || 0 })
    case 'whole-nondesktop':
      return t('version.mergeRowDesktopOnly')
    case 'auto-failed':
      return t('version.mergeRowAutoFailed', {
        reason: t(FAIL_REASON_KEYS[row.failReason] || 'version.mergeFailReasonGeneric'),
      })
    default:
      return t(WHOLE_REASON_KEYS[String(row && row.reason || '').toUpperCase()] || 'version.mergeRowWholeUnsupported')
  }
}
