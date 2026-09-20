// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// useInDocumentVisibility.js — 「用到文档」那组操作（插入 / 替换选区 / 导出 Word）什么时候出。
//
// 原来的判据只有 `bubble.content && !bubble.isStreaming`：每一条助手回复下面都挂一个按钮，
// 包括「好的」「已改好」这类一句话回执，以及 AI 刚刚自己写进文档的那一轮（dev-board#728）。
// 现在收窄成三类不该出的情况 + 一条「这段话值不值得放进文书」的体量门槛。
//
// 纯函数、不 import Vue：判定逻辑要能被 node --test 直接喂假气泡验证，
// 放在 .vue 的 computed 里就只能靠渲染整棵组件树才测得到。
import { markdownToPlainText } from './markdownPlain.js'

/**
 * 正文短于这个长度、又没有列表/标题结构时不出按钮。
 *
 * <p>分界线画在「一句回执」与「一段可以落进文书的内容」之间：
 * 「好的，第三条的违约金已经改成 10% 了。」是 21 个字符，
 * 而一条完整的合同条款、一段可引用的法条释义通常在 40 个字符以上。
 *
 * <p><b>从 80 降到 50</b>：80 个汉字差不多是两句完整的话，
 * 「根据《公司法》第七十一条，股东向股东以外的人转让股权，应当经其他股东过半数同意。」
 * 这种一句话就能落进文书的引述只有 40 个字符，被整个挡在外面了。
 * 漏出一个按钮只是多一个图标，藏掉才会让用户找不到把 AI 产出放进文书的入口。
 *
 * <p>阈值放这里而不是散在模板里——它是会被调的产品参数，不是魔数。
 */
export const USE_IN_DOCUMENT_MIN_CHARS = 50

/** 助手气泡上表示「模型在反问、这一轮等用户回答」的状态字面量（后端 bubble_end.status）。 */
const AWAITING_INPUT = 'awaiting_input'

/**
 * 这段 Markdown 有没有「可以整块搬进文书」的结构。
 *
 * <p>长度门槛会误杀短而有结构的产出——三条要点的清单、一个带小标题的条款，
 * 它们加起来可能不到 80 字符，却正是用户要往文档里放的东西。
 * 判据用<b>原始 Markdown</b> 而不是纯文本化之后的：markdownToPlainText 会剥掉 `#`、
 * 把 `- ` 换成 `• `，结构信号在那一步就没了。
 *
 * <p><b>「多段」刻意不算结构信号</b>：bubble.content 是「调工具前随口说的那句」与
 * `&lt;final&gt;` 正文拼起来的，解析器在两者之间会补空行。于是
 * 「先读原文。\n\n建议明确验收起算日。」——一句引子加一句结论，17 个字符——
 * 也是「两段」。段落数在这里量的是流式拼接的痕迹，不是内容的成篇程度，
 * 按它放行等于把长度门槛整个架空（真实渲染夹具 tests/chat-presentation-ui 实测）。
 * 标题与列表没有这个问题：模型不会为了一句回执去敲 `## `。
 * 这类短而成段的正文交给长度门槛判，宁可漏出一个按钮，不要处处都是按钮。
 */
export function hasDocumentStructure(md) {
  const s = String(md || '')
  if (!s.trim()) return false
  // 标题
  if (/^#{1,6}[ \t]+\S/m.test(s)) return true
  // 无序 / 有序列表
  return /^[ \t]*(?:[-*+]|\d+[.)])[ \t]+\S/m.test(s)
}

/**
 * 「用到文档」这组操作该不该出现在这条助手气泡下面。
 *
 * <p>四条全部满足才出：
 * <ol>
 *   <li>有正文、且流已结束（原有判据，未变）；</li>
 *   <li>本轮没有改过文档（{@code documentEdited}，后端 bubble_end 下发）——
 *       AI 已经把内容写进去了，再请用户手动插一遍是多余的一步，也容易插重；</li>
 *   <li>这一轮不是在反问等回答——那时候正文是一个问句，插进合同里毫无意义；</li>
 *   <li>正文够长、或有列表/标题结构——「好的，已改好」不需要插入文档的入口。</li>
 * </ol>
 *
 * <p>判据错在两个方向的后果不对称：该出没出，用户拿不到把 AI 产出放进文书的唯一入口；
 * 不该出却出了，只是多一个图标。所以拿不准时（历史消息没有 documentEdited 字段等）
 * 一律按「出」处理。
 */
export function shouldShowUseInDocument(bubble) {
  if (!bubble) return false
  if (!bubble.content || bubble.isStreaming) return false
  if (bubble.documentEdited) return false
  if (bubble.question || bubble.status === AWAITING_INPUT) return false
  const plain = markdownToPlainText(bubble.content)
  if (!plain) return false
  return plain.length >= USE_IN_DOCUMENT_MIN_CHARS || hasDocumentStructure(bubble.content)
}

// —— 以下三条是 backend ClientCapabilityService.READ_ONLY_* 的镜像 ——
// 两边必须逐字一致：tests/project-home/use-in-document-visibility.test.mjs 直接读 Java 源码
// 对拍字面量，并把真实的 113 个工具名逐个喂进来比对归类结果。
// 坑在名字上，不在前缀上：doc_find_replace 以 find_ 开头却是写入，doc_set_selection
// 只挪选区（只读）而 doc_replace_selection 是写入，所以既不能按 find 做词头、
// 也不能按 selection 做子串。词头一律 (?:_|$) 收尾，否则 inspect 会咬到 insert_*。
const READ_ONLY_STEM = /^(?:audit|check|count|debug|get|goto|inspect|list|locate|read|search|select)(?:_|$)|^summar/
const READ_ONLY_SUFFIX = /(?:^|_)read$/
const READ_ONLY_EXACT = new Set(['open_file', 'find_text', 'set_selection'])

/** 从 `<tool_code>` 正文里取工具名：`doc_find_replace({...})` -> doc / find_replace。 */
const DOCUMENT_TOOL_CALL = /^(?:\w+\.)?(?:doc|sheet|slide)_(\w+)\s*\(/

/**
 * 这个工具会不会真的改动文档内容（后端 {@code isDocumentWritingTool} 的镜像）。
 * 拿不准的一律算写入：多算只是少出一个按钮，漏算会让用户在 AI 已经写进文档之后
 * 又被请去手动插一遍。
 */
export function isDocumentWritingTool(toolName) {
  const match = DOCUMENT_TOOL_CALL.exec(`${String(toolName || '').trim()}(`)
  if (!match) return false
  const action = match[1]
  return !(READ_ONLY_STEM.test(action) || READ_ONLY_SUFFIX.test(action) || READ_ONLY_EXACT.has(action))
}

/**
 * 历史消息里有没有成功执行过的<b>写入类</b>文档工具。
 *
 * <p>为什么要有这一份：live 的 documentEdited 由后端随 bubble_end 下发，而
 * GET /api/ai/history 回的是原始协议正文，刷新页面重建气泡时那个字段就没了。
 * 不补的话，同一轮对话在刷新前后长得不一样——按钮又全冒出来，用户会以为没修。
 *
 * <p>status 取自 {@code <tool_output status="SUCCESS|FAILURE">}，与后端 ToolResult.success() 同源。
 */
export function documentEditedFromProcesses(processes) {
  for (const proc of processes || []) {
    for (const item of proc?.items || []) {
      if (item?.type !== 'tool' || item.status !== 'success') continue
      const match = DOCUMENT_TOOL_CALL.exec(String(item.code || '').trim())
      if (!match) continue
      const action = match[1]
      if (!(READ_ONLY_STEM.test(action) || READ_ONLY_SUFFIX.test(action) || READ_ONLY_EXACT.has(action))) return true
    }
  }
  return false
}
