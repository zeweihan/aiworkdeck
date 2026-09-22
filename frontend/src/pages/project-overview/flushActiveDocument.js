// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 发 AI 消息之前把当前文档落盘（dev-board#793 K14 ⑤，审查 E-9）。
//
// 病灶：桌面端只上送 activeContext 的 id/name，后端回落到 read_document 去读
// **磁盘上已保存的那一版**；而 LOWA 的自动保存是防抖的
// （LibreOfficeEditor.scheduleAutoSave：Math.max(200, Math.min(2500, 15000 - elapsed))）。
// 用户敲完一段话立刻按回车问「我刚改的这段有没有问题」，如果落在防抖窗口内，
// 模型看到的 <active_document> 是改动之前的版本——而末位 [系统提醒] 还斩钉截铁地说
// 「其正文已内联注入 system prompt，可直接阅读分析」。
//
// 代码库自己是知道要先 flush 的：prepareInsightDocument 在跑在线核验前专门
// await inst.flushSave({ timeoutMs: 10000 }) 并校验 !inst.dirty，聊天这条路没有对应动作。
//
// 刻意做成零依赖纯函数（同目录 flushDirtyEditors.js 的路数）：node:test 能直接跑，
// 而 project-overview.vue 里那些 import 了 '@/' 别名的模块测不动。

/**
 * 落盘当前活跃文档。
 *
 * <p><b>超时必须短</b>：这一步串在用户按下回车到消息真正发出之间。
 * prepareInsightDocument 用 10 秒是因为那是点一个「在线核验」按钮、用户知道自己在等；
 * 聊天回车等 10 秒是不可接受的。默认 1500ms。
 *
 * <p><b>失败不抛</b>：调用方拿 false 去把活跃文档降级成「只带壳」，
 * 消息照发。落不了盘绝不能变成「消息发不出去」。
 *
 * @param refs     形如 { 'left:123': inst }，来自 project-overview 的 _libreRefs
 * @param options  { side, fileId, timeoutMs }
 * @returns {Promise<boolean>} true = 磁盘上那份就是用户眼前这份
 */
export async function flushActiveDocument(refs, { side, fileId, timeoutMs = 1500 } = {}) {
  const id = Number(fileId)
  if (!id || !refs) return false

  // 先按「聚焦那一侧」找，找不到再扫整张表：用户可能把焦点放在文件树上，
  // 而活跃文档判定用的是 activeFileLeft/Right，两者不一定同侧
  const keys = side ? [`${side}:${id}`, `left:${id}`, `right:${id}`] : [`left:${id}`, `right:${id}`]
  let inst = null
  for (const key of keys) {
    if (refs[key]) { inst = refs[key]; break }
  }
  // 这份文件根本没开在 LOWA 里（纯文本标签、或还没加载完）——没有要落盘的东西，
  // 按「磁盘上那份就是当前那份」处理，不要报 false 去无谓地降级
  if (!inst) return true

  const usable = () => inst && inst.ready && !inst.docLoadFailed && !inst._reloading
      && inst.canWrite !== false && Number(inst.file && inst.file.id) === id
  if (!usable() || typeof inst.flushSave !== 'function') return true
  // 本来就不脏：没什么可落的，磁盘那份就是当前那份
  if (!inst.dirty && !inst.saving) return true

  try {
    const saved = await inst.flushSave({ timeoutMs })
    // 「保存调用返回了」不等于「保存完了」：再核一遍脏标记，
    // 与 prepareInsightDocument / flushDirtyEditors 同一口径
    return saved !== false && !inst.dirty && !inst.saving
  } catch (e) {
    return false
  }
}
