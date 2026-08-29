/**
 * wpsWordHandlers 单测：mock globalThis.wps 的 VBA 风对象模型（同步桥），
 * 覆盖偏移直切定位、最小修订与回退、withTracking 保存/恢复、编号降级、
 * 批注回复降级与若干错误路径。
 *   node --test office-addin/taskpane/lib/wpsWordHandlers.test.js
 *
 * mock 的文档主体是一根 JS 字符串：Range(s,e) 直接切片读写，段落按 \r 派生
 * ——这与 WPS 的字符偏移口径同构，天然能验证「右到左应用」的偏移稳定性
 * （mock 的写入会真实推移右侧偏移，应用顺序错了测试就会红）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { WPS_WORD_HANDLERS as H, locateInWpsDocument } from './wpsWordHandlers.js'

/* ==================== VBA 风 mock ==================== */

function installWps(opts = {}) {
  const state = {
    text: opts.text || '',
    track: false,
    trackLog: [],
    writes: [], // {kind, prop, value, start, end}
    calls: [], // {name, ...}
    selected: null
  }

  function fullLen() {
    return state.text.length
  }

  function makeRecorder(kind, start, end) {
    return new Proxy({}, {
      set(t, prop, v) {
        if (opts.throwOnWrite && opts.throwOnWrite.kind === kind && opts.throwOnWrite.prop === prop) {
          throw new Error(`mock：拒绝写 ${kind}.${prop}`)
        }
        state.writes.push({ kind, prop, value: v, start, end })
        t[prop] = v
        return true
      },
      get(t, prop) {
        return t[prop]
      }
    })
  }

  function paragraphSegs() {
    const parts = state.text.split('\r')
    const segs = []
    let pos = 0
    for (let k = 0; k < parts.length; k++) {
      const segStart = pos
      const segEnd = pos + parts[k].length + (k < parts.length - 1 ? 1 : 0)
      segs.push({ start: segStart, end: segEnd })
      pos = segEnd
    }
    return segs
  }

  function makeParagraph(seg) {
    return {
      get Range() {
        return makeRange(seg.start, seg.end)
      },
      get Format() {
        return makeRecorder('paraFormat', seg.start, seg.end)
      }
    }
  }

  function paragraphsIn(s, e) {
    let list = paragraphSegs().filter((g) => g.start < e && s < g.end)
    if (!list.length) list = paragraphSegs().filter((g) => s >= g.start && s <= g.end)
    const items = list.map(makeParagraph)
    return { Count: items.length, Item: (i) => items[i - 1] }
  }

  const listFormat = {
    RemoveNumbers: () => state.calls.push({ name: 'RemoveNumbers' }),
    ApplyBulletDefault: () => state.calls.push({ name: 'ApplyBulletDefault' }),
    ApplyNumberDefault: () => state.calls.push({ name: 'ApplyNumberDefault' }),
    ApplyListTemplateWithLevel: (...a) => state.calls.push({ name: 'ApplyListTemplateWithLevel', args: a })
  }

  function makeRange(start, end) {
    let s = start
    let e = end
    return {
      get Start() { return s },
      get End() { return e },
      get Text() {
        // readSkew：模拟「文档含占位符导致偏移口径失准」——非全文 Range 的读带偏移
        const skew = opts.readSkew && !(s === 0 && e === fullLen()) ? opts.readSkew : 0
        return state.text.slice(s + skew, e + skew)
      },
      set Text(v) {
        state.text = state.text.slice(0, s) + v + state.text.slice(e)
        e = s + v.length
      },
      InsertBefore(t) {
        state.text = state.text.slice(0, s) + t + state.text.slice(s)
      },
      InsertAfter(t) {
        state.text = state.text.slice(0, e) + t + state.text.slice(e)
      },
      Select() {
        state.selected = [s, e]
      },
      InsertBreak(type) {
        state.calls.push({ name: 'InsertBreak', type, start: s })
      },
      get Font() {
        return makeRecorder('font', s, e)
      },
      get ParagraphFormat() {
        return makeRecorder('paraFormat', s, e)
      },
      get Paragraphs() {
        return paragraphsIn(s, e)
      },
      get ListFormat() {
        return listFormat
      },
      set Style(v) {
        state.writes.push({ kind: 'range', prop: 'Style', value: v, start: s, end: e })
      },
      get Find() {
        return {
          ClearFormatting() {},
          Execute: (...a) => {
            state.calls.push({ name: 'Find.Execute', args: a })
            const findText = a[0]
            const replaceWith = a[9]
            if (state.text.includes(findText)) {
              state.text = state.text.replace(findText, replaceWith == null ? '' : replaceWith)
              return true
            }
            return false
          }
        }
      }
    }
  }

  const doc = {
    get TrackRevisions() {
      if (opts.trackBroken) throw new Error('mock：TrackRevisions 不可用')
      return state.track
    },
    set TrackRevisions(v) {
      if (opts.trackBroken) throw new Error('mock：TrackRevisions 不可用')
      state.track = !!v
      state.trackLog.push(!!v)
    },
    Range(s, e) {
      return s == null ? makeRange(0, fullLen()) : makeRange(s, e == null ? s : e)
    },
    get Paragraphs() {
      const items = paragraphSegs().map(makeParagraph)
      return { Count: items.length, Item: (i) => items[i - 1] }
    },
    Comments: opts.comments || {
      Count: 0,
      Item: () => { throw new Error('mock：无批注') },
      Add: (r, t) => state.calls.push({ name: 'Comments.Add', start: r.Start, end: r.End, text: t })
    },
    ...(opts.docExtras || {})
  }
  // add_comment 的默认 Comments 也要有 Add
  if (opts.comments && !opts.comments.Add) {
    opts.comments.Add = (r, t) => state.calls.push({ name: 'Comments.Add', start: r.Start, end: r.End, text: t })
  }

  const listLevel = makeRecorder('listLevel', 0, 0)
  const listTemplate = { ListLevels: { Item: () => listLevel } }
  const appObj = {
    ActiveDocument: doc,
    Selection: (() => {
      // 带状态的选区 mock，忠于 VBA/WPS 语义：Text 赋值把选区文本替换成新值，
      // **赋值后选区扩展覆盖新文本**（这正是连续无锚点插入互相覆盖的病灶）；
      // Collapse(0)=折叠到末尾、Collapse(1)=折叠到起点。
      let selS = opts.selStart || 0
      let selE = opts.selEnd || opts.selStart || 0
      return {
        get Text() { return state.text.slice(selS, selE) },
        set Text(v) {
          state.calls.push({ name: 'Selection.Text=', value: v })
          state.text = state.text.slice(0, selS) + v + state.text.slice(selE)
          selE = selS + v.length
        },
        Collapse(dir) {
          state.calls.push({ name: 'Selection.Collapse', dir })
          if (dir === 1) selE = selS
          else selS = selE
        },
        get Range() { return makeRange(selS, selE) },
        Type: opts.selType
      }
    })(),
    get ListGalleries() {
      if (opts.listGalleriesBroken) throw new Error('mock：ListGalleries 不可用')
      return { Item: () => ({ ListTemplates: { Item: () => listTemplate } }) }
    }
  }

  globalThis.wps = { WpsApplication: () => appObj }
  return { state, doc, listTemplate }
}

/* ==================== get_text / get_selection / search ==================== */

test('get_text：返回全文与截断标记', async () => {
  const { } = installWps({ text: '第一段\r第二段\r' })
  const data = await H.get_text({})
  assert.equal(data.text, '第一段\r第二段\r')
  assert.equal(data.truncated, false)
  assert.equal(data.totalChars, 8)
})

test('get_selection：光标态（wdSelectionIP）按空处理', async () => {
  installWps({ text: '正文', selType: 1 })
  const data = await H.get_selection({})
  assert.equal(data.text, '')
})

test('search：跨段计数与段落上下文', async () => {
  installWps({ text: '甲方应付款。\r乙方应收款。\r甲方违约。' })
  const data = await H.search({ query: '甲方' })
  assert.equal(data.count, 2)
  assert.equal(data.shown, 2)
  assert.equal(data.matches[0].context, '甲方应付款。')
  assert.equal(data.matches[1].context, '甲方违约。')
})

/* ==================== replace_text ==================== */

test('replace_text：最小修订路径（差异段落笔 + 修订开关保存/恢复）', async () => {
  const { state } = installWps({ text: '甲方应当支付价款。\r' })
  const data = await H.replace_text({ searchText: '支付价款', replaceText: '支付全部价款' })
  assert.equal(state.text, '甲方应当支付全部价款。\r')
  assert.equal(data.via, 'minimalRedline')
  assert.equal(data.replaced, 1)
  assert.ok(data.edits >= 1)
  assert.equal(data.tracked, true)
  // withTracking：先开 true、finally 恢复原值 false
  assert.deepEqual(state.trackLog, [true, false])
  assert.equal(state.track, false)
})

test('replace_text：replaceAll 多命中从右到左应用（偏移不互相推移）', async () => {
  const { state } = installWps({ text: '见附件一。见附件一。见附件一。' })
  const data = await H.replace_text({ searchText: '附件一', replaceText: '附件二', replaceAll: true })
  assert.equal(state.text, '见附件二。见附件二。见附件二。')
  assert.equal(data.replaced, 3)
  assert.equal(data.via, 'minimalRedline')
})

test('replace_text：新旧文完全不同时回退整段替换（fullReplace）', async () => {
  const { state } = installWps({ text: '本条待定。\r' })
  const data = await H.replace_text({ searchText: '本条待定', replaceText: '双方另行协商' })
  assert.equal(state.text, '双方另行协商。\r')
  assert.equal(data.via, 'fullReplace')
  assert.equal(data.fallbacks, 1)
})

test('replace_text：偏移校验失败时走 Find.Execute 兜底', async () => {
  // readSkew=1 模拟占位符导致的偏移失准：非全文 Range 读到的文本对不上
  const { state } = installWps({ text: 'ABCDEFGH', readSkew: 1 })
  const data = await H.replace_text({ searchText: 'CDE', replaceText: 'XYZ' })
  assert.equal(state.text, 'ABXYZFGH')
  assert.equal(data.via, 'fullReplace')
  assert.equal(data.fallbacks, 1)
  assert.ok(state.calls.some((c) => c.name === 'Find.Execute'))
})

test('replace_text：跨段 searchText 快速失败', async () => {
  installWps({ text: '第一段\r第二段\r' })
  await assert.rejects(
    H.replace_text({ searchText: '第一段\n第二段', replaceText: '合并段' }),
    /跨段落/
  )
})

test('replace_text：未命中报错（错误路径）', async () => {
  installWps({ text: '正文内容。\r' })
  await assert.rejects(
    H.replace_text({ searchText: '不存在的文本', replaceText: 'x' }),
    /未找到目标文本/
  )
})

/* ==================== insert_text ==================== */

test('insert_text：锚点后插与前插，\\n 归一为 \\r', async () => {
  let env = installWps({ text: '合同正文。\r' })
  let data = await H.insert_text({ text: '（补充）', anchorText: '合同正文', position: 'after' })
  assert.equal(env.state.text, '合同正文（补充）。\r')
  assert.equal(data.inserted, true)
  assert.equal(data.position, 'after')
  assert.equal(data.tracked, true)

  env = installWps({ text: '合同正文。\r' })
  data = await H.insert_text({ text: '序言\n', anchorText: '合同正文', position: 'before' })
  assert.equal(env.state.text, '序言\r合同正文。\r')
  assert.equal(data.position, 'before')
})

test('insert_text：TrackRevisions 不可用时降级直改并标 tracked:false', async () => {
  const { state } = installWps({ text: '合同正文。\r', trackBroken: true })
  const data = await H.insert_text({ text: '（补充）', anchorText: '合同正文', position: 'after' })
  assert.equal(state.text, '合同正文（补充）。\r')
  assert.equal(data.tracked, false)
})

test('insert_text：连续两次无锚点插入不互相覆盖（Selection.Text 赋值后折叠到末尾）', async () => {
  // WPS 语义下 Selection.Text 赋值后选区覆盖新文本：不折叠的话第二次插入会把
  // 第一次整段替换（真机表现为第一段变红色删除线）。修法是每次赋值后 Collapse(0)。
  const { state } = installWps({ text: '' })
  await H.insert_text({ text: '第一段\n' })
  await H.insert_text({ text: '第二段' })
  assert.equal(state.text, '第一段\r第二段')
  assert.ok(state.calls.some((c) => c.name === 'Selection.Collapse' && c.dir === 0))
})

/* ==================== add_comment ==================== */

test('add_comment：Comments.Add 收到锚点 Range 与批注内容', async () => {
  const { state } = installWps({ text: '前言。争议条款在此。\r' })
  const data = await H.add_comment({ anchorText: '争议条款', comment: '此处建议补充管辖约定' })
  assert.equal(data.commented, true)
  const call = state.calls.find((c) => c.name === 'Comments.Add')
  assert.ok(call)
  assert.equal(call.start, 3)
  assert.equal(call.end, 7)
  assert.equal(call.text, '此处建议补充管辖约定')
})

/* ==================== set_paragraph_format ==================== */

test('set_paragraph_format：lineSpacing 落成最小值行距（rule=3 两件套）', async () => {
  const { state } = installWps({ text: '标题\r正文内容一段。\r' })
  const data = await H.set_paragraph_format({ anchorText: '正文内容', lineSpacing: 16 })
  const rule = state.writes.find((w) => w.kind === 'paraFormat' && w.prop === 'LineSpacingRule')
  const ls = state.writes.find((w) => w.kind === 'paraFormat' && w.prop === 'LineSpacing')
  assert.ok(rule && rule.value === 3, 'LineSpacingRule 必须显式设为 wdLineSpaceAtLeast(3)')
  assert.ok(ls && ls.value === 16)
  assert.equal(data.lineSpacingMode, 'atLeast')
  assert.equal(data.formatted, 1)
  assert.equal(data.tracked, true)
})

test('set_paragraph_format：先落 styleBuiltIn 再落其余参数', async () => {
  const { state } = installWps({ text: '一、总则\r正文。\r' })
  await H.set_paragraph_format({ anchorText: '一、总则', styleBuiltIn: 'heading1', alignment: 'center' })
  const styleWrite = state.writes.find((w) => w.kind === 'range' && w.prop === 'Style')
  const alignWrite = state.writes.find((w) => w.kind === 'paraFormat' && w.prop === 'Alignment')
  assert.ok(styleWrite && styleWrite.value === -2, 'heading1 → wdStyleHeading1(-2)')
  assert.ok(alignWrite && alignWrite.value === 1)
  assert.ok(state.writes.indexOf(styleWrite) < state.writes.indexOf(alignWrite), '样式必须先于其余参数落笔')
})

/* ==================== set_numbering ==================== */

test('set_numbering：中文编号走原生 ListTemplate（NumberStyle=38）', async () => {
  const { state } = installWps({ text: '第一条内容\r第二条内容\r第三条内容\r' })
  const data = await H.set_numbering({ anchorText: '第一条内容', kind: 'chinese', paragraphCount: 2 })
  const style = state.writes.find((w) => w.kind === 'listLevel' && w.prop === 'NumberStyle')
  const fmt = state.writes.find((w) => w.kind === 'listLevel' && w.prop === 'NumberFormat')
  assert.ok(style && style.value === 38, 'wdListNumberStyleSimpChinNum2 = 38')
  assert.ok(fmt && fmt.value === '%1、')
  const apply = state.calls.find((c) => c.name === 'ApplyListTemplateWithLevel')
  assert.ok(apply)
  // (lt, ContinuePreviousList=false, wdListApplyToWholeList=0, wdWord9ListBehavior=1, ApplyLevel=1)
  assert.deepEqual(apply.args.slice(1), [false, 0, 1, 1])
  assert.equal(data.via, 'listTemplate')
  assert.equal(data.paragraphs, 2)
  assert.equal(data.tracked, true)
})

test('set_numbering：ListTemplate 失败降级手写「一、」前缀（从后往前写）', async () => {
  const { state } = installWps({ text: '第一条内容\r第二条内容\r第三条内容\r', listGalleriesBroken: true })
  const data = await H.set_numbering({ anchorText: '第一条内容', kind: 'chinese', paragraphCount: 2 })
  assert.equal(state.text, '一、第一条内容\r二、第二条内容\r第三条内容\r')
  assert.equal(data.via, 'literalText')
  assert.ok(data.note)
})

/* ==================== withTracking finally 恢复 ==================== */

test('withTracking：执行中途抛错也要在 finally 恢复修订开关', async () => {
  const { state } = installWps({
    text: '目标文本一处。\r',
    throwOnWrite: { kind: 'font', prop: 'Bold' }
  })
  await assert.rejects(H.format_text({ anchorText: '目标文本', bold: true }), /拒绝写/)
  assert.equal(state.track, false, '抛错后修订开关必须恢复原值')
  assert.deepEqual(state.trackLog, [true, false])
})

/* ==================== reply_comment ==================== */

function makeCommentMock() {
  const comment = {
    Author: '审阅人',
    Done: false,
    Date: '2026/08/28 10:00:00',
    Range: {
      _t: '原批注内容\r',
      get Text() { return this._t },
      set Text(v) { this._t = v }
    },
    Scope: { Text: '锚定文本', Start: 0, End: 4 },
    Replies: null
  }
  return comment
}

test('reply_comment：Replies.Add 可用时走线程回复', async () => {
  const comment = makeCommentMock()
  const added = []
  comment.Replies = { Add: (scope, text) => added.push({ scope, text }) }
  installWps({ text: '锚定文本。\r', comments: { Count: 1, Item: () => comment } })
  const data = await H.reply_comment({ commentIndex: 0, reply: '已按建议修改' })
  assert.equal(data.replied, true)
  assert.equal(data.via, undefined)
  assert.equal(added.length, 1)
  assert.equal(added[0].text, '已按建议修改')
})

test('reply_comment：Replies.Add 不可用时降级追加进批注正文（via appendText）', async () => {
  const comment = makeCommentMock()
  comment.Replies = { Add: () => { throw new Error('mock：Replies.Add 不支持') } }
  installWps({ text: '锚定文本。\r', comments: { Count: 1, Item: () => comment } })
  const data = await H.reply_comment({ commentId: '0', reply: '已按建议修改' })
  assert.equal(data.replied, true)
  assert.equal(data.via, 'appendText')
  assert.ok(comment.Range.Text.includes('原批注内容'))
  assert.ok(comment.Range.Text.includes('已按建议修改'))
})

test('reply_comment：序号越界与缺参（错误路径）', async () => {
  const comment = makeCommentMock()
  installWps({ text: '锚定文本。\r', comments: { Count: 1, Item: () => comment } })
  await assert.rejects(H.reply_comment({ commentIndex: 5, reply: 'x' }), /越界/)
  await assert.rejects(H.reply_comment({ reply: 'x' }), /缺少批注定位参数/)
})

/* ==================== get_comments ==================== */

test('get_comments：id 是 0 起序号字符串，resolved 映射 Done', async () => {
  const comment = makeCommentMock()
  comment.Done = true
  installWps({ text: '锚定文本。\r', comments: { Count: 1, Item: () => comment } })
  const data = await H.get_comments({})
  assert.equal(data.count, 1)
  assert.equal(data.comments[0].id, '0')
  assert.equal(data.comments[0].index, 0)
  assert.equal(data.comments[0].author, '审阅人')
  assert.equal(data.comments[0].content, '原批注内容')
  assert.equal(data.comments[0].resolved, true)
  assert.equal(data.comments[0].anchorText, '锚定文本')
})

/* ==================== 其余错误路径 ==================== */

test('insert_image：明确报错不支持（不许静默吞）', async () => {
  installWps({ text: '正文。\r' })
  await assert.rejects(H.insert_image({ imageBase64: 'abc' }), /不支持插入图片/)
})

test('format_text：underline 枚举非法（错误路径）', async () => {
  installWps({ text: '正文。\r' })
  await assert.rejects(H.format_text({ anchorText: '正文', underline: 'zigzag' }), /underline 值非法/)
})

test('format_table：文档无表格（错误路径）', async () => {
  installWps({ text: '正文。\r', docExtras: { Tables: { Count: 0 } } })
  await assert.rejects(H.format_table({ alignment: 'center' }), /没有表格/)
})

/* ==================== locateInWpsDocument ==================== */

test('locateInWpsDocument：命中即 Select，未命中返回 found:false', async () => {
  const { state } = installWps({ text: '甲方应付款。\r乙方应收款。\r' })
  const hit = await locateInWpsDocument('乙方应收款')
  assert.equal(hit.found, true)
  assert.equal(hit.count, 1)
  assert.deepEqual(state.selected, [7, 12])
  const miss = await locateInWpsDocument('不存在的引文')
  assert.equal(miss.found, false)
})

/* ==================== 导出面完整性 ==================== */

test('HANDLERS 覆盖任务书列出的全部 Word 面命令', () => {
  const expected = [
    'get_text', 'get_selection', 'search', 'replace_text', 'insert_text', 'add_comment',
    'format_text', 'set_paragraph_format', 'get_formatting', 'set_numbering', 'format_table',
    'apply_standard_format', 'insert_table', 'table_read', 'table_set_cell', 'table_add_row',
    'table_delete_row', 'table_add_col', 'table_delete_col', 'insert_break', 'set_hyperlink',
    'edit_header_footer', 'get_comments', 'reply_comment', 'resolve_comment', 'get_revisions',
    'accept_revision', 'reject_revision', 'insert_footnote', 'insert_endnote', 'insert_image',
    'apply_style', 'manage_content_control', 'set_document_properties'
  ]
  // 任务书写「33 个」但逐条列举实为 34 条（get_text..set_document_properties），以列举为准
  assert.equal(expected.length, 34)
  for (const name of expected) {
    assert.equal(typeof H[name], 'function', `缺少 handler：${name}`)
  }
})

test('replace_text：replaceAll + 偏移失准 = 整体切纯 Find 循环，计数正确不误报（混跑病灶回归）', async () => {
  // 三处命中 + readSkew：旧实现在右到左循环里逐个掉 Find 兜底，Find 恒替换最靠前
  // 的命中，与当前处理的那处错位——最后一轮会因「全部已被提前替换」反过来抛错。
  // 修复后：预检发现任一偏移失准即整体走 Find 循环，三处全替、无异常。
  const { state } = installWps({ text: '甲AA乙AA丙AA丁', readSkew: 1 })
  const data = await H.replace_text({ searchText: 'AA', replaceText: 'BB', replaceAll: true })
  assert.equal(state.text, '甲BB乙BB丙BB丁')
  assert.equal(data.replaced, 3)
  assert.equal(data.via, 'fullReplace')
  assert.equal(data.fallbacks, 3)
  assert.equal(state.calls.filter((c) => c.name === 'Find.Execute').length, 3)
})
