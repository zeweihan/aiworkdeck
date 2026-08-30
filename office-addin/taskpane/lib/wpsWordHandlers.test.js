/**
 * wpsWordHandlers 单测：mock globalThis.wps 的 VBA 风对象模型（同步桥），
 * 覆盖偏移直切定位、最小修订与回退、withTracking 保存/恢复、编号降级、
 * 批注回复降级与若干错误路径。
 *   node --test office-addin/taskpane/lib/wpsWordHandlers.test.js
 *
 * mock 的文档主体是一根 JS 字符串，段落按 \r 派生；写入会真实推移右侧偏移，
 * 所以「右到左应用」的顺序错了测试就会红。
 *
 * **mock 刻意模拟两套坐标系**（真机实测 WPS 12.1.0.28043，2026-08-29）：
 * `state.text` 是 `doc.Range().Text` 那根 JS 串，而 `Range(s,e)` 收的是**文档字符
 * 位置**。表格的单元格/行结束符在文本里是 "\r\x07" 两个 UTF-16 单元，在文档坐标
 * 里只占 1 个位置。不含表格时两套坐标恒等，所以不带 \x07 的用例照旧。
 * `hiddenMarkAtJs` 再模拟「占文档位置但不进文本」的隐藏标记（批注引用、域），
 * 那一档推算不出来，必须退 Find 定位。
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
    findProps: {}, // Find 对象上被显式钉死的匹配宽松度属性
    paraRangeReads: 0, // Paragraph.Range 被取过几次（逐段扫描的跨桥代价代理指标）
    selected: null
  }

  // ---- 两套坐标系（真机实测，WPS 12.1.0.28043，2026-08-29）----
  // state.text 是 doc.Range().Text 那根 JS 串；Range(s,e) 收的却是**文档字符位置**。
  // 表格的单元格/行结束符在文本里是 "\r\x07" 两个 UTF-16 单元，在文档坐标里只占
  // 1 个位置（\r 占，\x07 不占）。不含表格时两套坐标恒等，所以既有用例不受影响。
  // hiddenMarkAtJs：在该 JS 下标处埋一个「占文档位置但不出现在文本里」的隐藏标记
  // （批注引用标记就是这样，实测每条至少 +1；域更狠）。这类占位**推算不出来**，
  // 是坐标映射管不住、必须退 Find 定位的那一档。
  const hiddenAt = opts.hiddenMarkAtJs
  const hiddenSize = opts.hiddenMarkSize == null ? 1 : opts.hiddenMarkSize
  function jsToDoc(j) {
    let d = 0
    const n = Math.min(j, state.text.length)
    for (let i = 0; i < n; i++) if (state.text.charCodeAt(i) !== 7) d++
    if (hiddenAt != null && j > hiddenAt) d += hiddenSize
    return d
  }
  function docToJs(d) {
    for (let i = 0; i <= state.text.length; i++) {
      if (jsToDoc(i) >= d) {
        let j = i
        // 落点压在 \x07 上时跳到它后面：\r\x07 在文档坐标里是一个位置、文本里是两个
        while (j < state.text.length && state.text.charCodeAt(j) === 7) j++
        return j
      }
    }
    return state.text.length
  }
  function docLen() {
    return jsToDoc(state.text.length)
  }
  function fullLen() {
    return docLen()
  }

  // start/end 是文档坐标；jsStart/jsEnd 是同一区间在 state.text 里的 JS 下标
  // （断言按段落折算时要用 JS 那一对，否则含表格的文档上会对不齐）
  function makeRecorder(kind, start, end, jsStart, jsEnd) {
    return new Proxy({}, {
      set(t, prop, v) {
        if (opts.throwOnWrite && opts.throwOnWrite.kind === kind && opts.throwOnWrite.prop === prop) {
          throw new Error(`mock：拒绝写 ${kind}.${prop}`)
        }
        state.writes.push({
          kind,
          prop,
          value: v,
          start,
          end,
          jsStart: jsStart == null ? start : jsStart,
          jsEnd: jsEnd == null ? end : jsEnd
        })
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
      segs.push({ jsStart: segStart, jsEnd: segEnd, start: jsToDoc(segStart), end: jsToDoc(segEnd) })
      pos = segEnd
    }
    return segs
  }

  function makeParagraph(seg) {
    return {
      get Range() {
        state.paraRangeReads++
        return makeRange(seg.start, seg.end)
      },
      get Format() {
        return makeRecorder('paraFormat', seg.start, seg.end, seg.jsStart, seg.jsEnd)
      }
    }
  }

  function paragraphsIn(s, e) {
    let list = paragraphSegs().filter((g) => g.start < e && s < g.end)
    if (!list.length) list = paragraphSegs().filter((g) => s >= g.start && s <= g.end)
    const items = list.map(makeParagraph)
    // paragraphCountLie：只让「非全文区间」的 Paragraphs.Count 多报一个，用来触发
    // 合并落笔的安全阀（全文区间不能骗，那是 apply_standard_format 的总段数来源）
    const lie = opts.paragraphCountLie && !(s === 0 && e === fullLen()) ? 1 : 0
    return { Count: items.length + lie, Item: (i) => items[i - 1] }
  }

  const listFormat = {
    RemoveNumbers: () => state.calls.push({ name: 'RemoveNumbers' }),
    ApplyBulletDefault: () => state.calls.push({ name: 'ApplyBulletDefault' }),
    ApplyNumberDefault: () => state.calls.push({ name: 'ApplyNumberDefault' }),
    ApplyListTemplateWithLevel: (...a) => state.calls.push({ name: 'ApplyListTemplateWithLevel', args: a })
  }

  /** s/e 是**文档字符位置**（不是 JS 下标），与真机 Range(s,e) 同口径 */
  function makeRange(start, end) {
    let s = start
    let e = end
    const js = () => [docToJs(s), docToJs(e)]
    return {
      get Start() { return s },
      get End() { return e },
      get Text() {
        // readSkew：模拟「批注引用标记/域这类推算不出来的占位」——非全文 Range 的读带偏移。
        // readSkewAfter：只让起点 >= 该值的 Range 失准，用来构造「前几处校验通过、
        // 后面某处才失败」——半写入类 bug 只有这种形状才暴露得出来。
        const inSkewZone = opts.readSkewAfter == null || s >= opts.readSkewAfter
        const skew = opts.readSkew && inSkewZone && !(s === 0 && e === fullLen()) ? opts.readSkew : 0
        const [a, b] = js()
        return state.text.slice(a + skew, b + skew)
      },
      set Text(v) {
        const [a, b] = js()
        state.text = state.text.slice(0, a) + v + state.text.slice(b)
        e = jsToDoc(a + v.length)
      },
      InsertBefore(t) {
        const [a] = js()
        state.text = state.text.slice(0, a) + t + state.text.slice(a)
      },
      InsertAfter(t) {
        const [, b] = js()
        state.text = state.text.slice(0, b) + t + state.text.slice(b)
      },
      Select() {
        state.selected = [s, e]
      },
      Collapse(kind) {
        // wdCollapseEnd=0 / wdCollapseStart=1
        if (kind === 0) s = e
        else e = s
      },
      InsertBreak(type) {
        state.calls.push({ name: 'InsertBreak', type, start: s })
      },
      get Font() {
        const [a, b] = js()
        return makeRecorder('font', s, e, a, b)
      },
      get ParagraphFormat() {
        const [a, b] = js()
        return makeRecorder('paraFormat', s, e, a, b)
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
        const find = {
          ClearFormatting() {},
          ClearAllFuzzyOptions() {
            state.calls.push({ name: 'Find.ClearAllFuzzyOptions' })
          },
          Execute: (...a) => {
            state.calls.push({ name: 'Find.Execute', args: a })
            const findText = a[0]
            const replaceWith = a[9]
            if (replaceWith == null) {
              // 只定位不替换：从本区间起点往后找，命中则把**本 Range 重定义为命中
              // 区间**（VBA 语义，已在 WPS 真机确认），返回 true
              const at = state.text.indexOf(findText, docToJs(s))
              if (at < 0) return false
              s = jsToDoc(at)
              e = jsToDoc(at + findText.length)
              return true
            }
            if (state.text.includes(findText)) {
              state.text = state.text.replace(findText, replaceWith)
              return true
            }
            return false
          }
        }
        // 记录匹配宽松度属性的写入：这几项不在 Execute 的 15 参签名里，会从用户
        // 上一次手动查找继承，必须由代码显式钉死
        return new Proxy(find, {
          set(t, prop, v) {
            state.findProps[prop] = v
            t[prop] = v
            return true
          }
        })
      }
    }
  }

  // 页眉/页脚（独立 story）。state.hf 记录被写过什么，用来钉住
  // 「只给 alignment 时不许动文字」这条（dev-board#288）。
  state.hf = { header: { text: opts.headerText || '原页眉', writes: 0, alignment: null } }
  const headerRange = {
    get Text() { return state.hf.header.text },
    set Text(v) { state.hf.header.writes++; state.hf.header.text = String(v) },
    ParagraphFormat: {
      set Alignment(v) { state.hf.header.alignment = v },
      get Alignment() { return state.hf.header.alignment }
    }
  }
  const sectionObj = {
    Headers: { Item: () => ({ Range: headerRange }) },
    Footers: { Item: () => ({ Range: headerRange }) }
  }

  const doc = {
    Sections: { Item: () => sectionObj },
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
    // 默认无表格（apply_standard_format 的表格字号块要读 Tables.Count）；
    // 要测表格分支就用 opts.docExtras 覆盖
    Tables: { Count: 0, Item: () => { throw new Error('mock：无表格') } },
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

/* ============ 锚点归一化与带证据的报错（dev-board#286） ============ */

test('锚点归一化：文档里是全角括号+弯引号，模型给半角+直引号也要命中', async () => {
  // 律师核对时"两边一模一样"，逐字比较却必然失配——这正是用户反复看到
  // 「未找到锚点文本，请确认 anchorText 与文档内容精确一致」的那一类。
  const { state } = installWps({ text: '第三条　违约责任（含“逾期利息”）由甲方承担。\r' })
  const out = await H.insert_text({ anchorText: '违约责任(含"逾期利息")', text: '【补充】', position: 'after' })
  assert.equal(out.inserted, true)
  assert.ok(state.text.includes('【补充】'), `应已插入，实际正文：${state.text}`)
})

test('锚点归一化：不间断空格与零宽字符（PDF 转出来的文书里成片都是）不该毁掉匹配', async () => {
  const { state } = installWps({ text: '合计\u00A0壹​佰万元整，于交割日支付。\r' })
  const out = await H.insert_text({ anchorText: '合计 壹佰万元整', text: '（大写）', position: 'after' })
  assert.equal(out.inserted, true)
  assert.ok(state.text.includes('（大写）'), state.text)
})

test('锚点未命中的报错必须带证据：给出文档里最接近的原文与下一步', async () => {
  installWps({ text: '第八条 甲方应于每月十五日前向乙方支付服务费。\r' })
  await assert.rejects(
    H.insert_text({ anchorText: '甲方应于每月十日前向乙方支付服务费', text: 'x', position: 'after' }),
    (e) => {
      assert.ok(/最接近的一段原文/.test(e.message), `报错要摆出候选：${e.message}`)
      assert.ok(/十五日/.test(e.message), '候选片段要是文档原文')
      assert.ok(/重试|重新读取/.test(e.message), '要告诉模型下一步做什么')
      return true
    }
  )
})

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

/* ============ 含表格文档的锚点定位（真机实测的两套坐标系）============ */

// 一段正文 + 一张 1×2 表格 + 一段正文。表格结构照真机实测的形状：每个单元格
// 以 "\r\x07" 结束、整行再以 "\r\x07" 结束——这两个字符在 doc.Range().Text 里
// 是 2 个 UTF-16 单元，在 Range(s,e) 坐标系里只占 1 个位置。
const DOC_WITH_TABLE = '合同标题\r' + '甲方\r\x07乙方\r\x07\r\x07' + '本合同自签署之日起生效。\r'

test('含表格文档：表格之前的锚点不受影响', async () => {
  const { state } = installWps({ text: DOC_WITH_TABLE })
  await H.insert_text({ anchorText: '合同标题', text: '（草案）', position: 'after' })
  assert.ok(state.text.startsWith('合同标题（草案）\r'))
})

test('含表格文档：表格之后的锚点也能定位（坐标换算）', async () => {
  // 这是 dev-board#264 说的「成片死路」：JS 下标 15，文档位置 12（前面 3 个 \x07），
  // 直接拿 JS 下标去切会读到错位的文本，旧实现在这里直接报「定位校验失败」。
  const { state } = installWps({ text: DOC_WITH_TABLE })
  const data = await H.insert_text({ anchorText: '本合同自签署之日起生效。', text: '（本条为效力条款）', position: 'after' })
  assert.equal(data.inserted, true)
  assert.ok(state.text.includes('本合同自签署之日起生效。（本条为效力条款）'), state.text)
})

test('含表格文档：单元格内的锚点也能定位', async () => {
  const { state } = installWps({ text: DOC_WITH_TABLE })
  await H.replace_text({ searchText: '乙方', replaceText: '乙方（受让方）' })
  assert.ok(state.text.includes('乙方（受让方）\r\x07'), state.text)
})

test('含表格文档：add_comment 落在表格之后的锚点上', async () => {
  const { state } = installWps({ text: DOC_WITH_TABLE })
  const data = await H.add_comment({ anchorText: '本合同自签署之日起生效。', comment: '请确认生效条件' })
  assert.equal(data.commented, true)
  const added = state.calls.find((c) => c.name === 'Comments.Add')
  assert.ok(added, '应当真的调了 Comments.Add')
  assert.equal(added.start, 12) // 文档坐标 12 = JS 下标 15 − 3 个 \x07
})

test('含表格文档：format_text 的 applyToAll 覆盖表格前后的多处命中', async () => {
  const text = '甲方概况\r甲方\r\x07乙方\r\x07\r\x07甲方应当履行义务。\r'
  const { state } = installWps({ text })
  const data = await H.format_text({ anchorText: '甲方', bold: true, applyToAll: true })
  assert.equal(data.totalMatches, 3)
  assert.equal(data.formatted, 3)
  // 三处命中的 JS 下标是 0 / 5 / 15，换算成文档坐标是 0 / 5 / 12
  // （第三处之前有 3 个 \x07：单元格 ×2 + 行结束 ×1）
  const bolds = state.writes.filter((w) => w.kind === 'font' && w.prop === 'Bold')
  assert.deepEqual(bolds.map((w) => w.start).sort((a, b) => a - b), [0, 5, 12])
})

/* ====== 坐标推算不出来时（批注引用标记/域/修订漂移）退 Find 定位 ====== */
// readSkew 让「按坐标直切」读到错位文本，模拟这一档

test('Find 兜底：insert_text 在坐标推算不出来时改用 Find 定位并落笔', async () => {
  // 第一条后面埋一个隐藏标记（批注引用），后面所有锚点的坐标推算就都偏了
  const { state } = installWps({ text: '第一条 定金条款。\r第二条 违约责任。\r', hiddenMarkAtJs: 9 })
  const data = await H.insert_text({ anchorText: '第二条 违约责任。', text: '（另见附件三）', position: 'after' })
  assert.equal(data.inserted, true)
  assert.ok(state.text.includes('第二条 违约责任。（另见附件三）'), state.text)
  assert.ok(state.calls.some((c) => c.name === 'Find.Execute'))
})

test('Find 兜底：只定位不替换——不传 ReplaceWith，原文一个字不改', async () => {
  const { state } = installWps({ text: '甲方与乙方签署本协议。\r', hiddenMarkAtJs: 1 })
  await H.add_comment({ anchorText: '乙方', comment: '确认主体资格' })
  assert.equal(state.text, '甲方与乙方签署本协议。\r') // 定位不许动文档
  const exec = state.calls.filter((c) => c.name === 'Find.Execute')
  assert.ok(exec.length >= 1)
  assert.equal(exec[0].args[9], undefined, '定位调用不许传 ReplaceWith')
})

test('Find 兜底：applyToAll 逐处取第 N 个命中，不是恒定最靠前那处', async () => {
  const { state } = installWps({ text: '甲方甲方甲方', hiddenMarkAtJs: 0 })
  const data = await H.format_text({ anchorText: '甲方', bold: true, applyToAll: true })
  assert.equal(data.formatted, 3)
  const starts = state.writes.filter((w) => w.kind === 'font' && w.prop === 'Bold').map((w) => w.start)
  // 三处必须落在三个不同位置——照 findReplaceOnce 那样每次新建 Range 重搜的话，
  // 三次都会命中最靠前那一处（#261 的 leftmost-repeat 覆辙），而格式写入是幂等的、
  // 不会报错，返回值照样是 formatted:3，属于静默撒谎。
  // 这里第一处走坐标直切、后两处退 Find（隐藏标记之后坐标才失准）——格式写入不改
  // 文本，所以两条路混用不会像 replace_text 那样互相错位。
  assert.deepEqual(starts.sort((a, b) => a - b), [0, 3, 5])
})

test('Find 兜底：两条路都取不到逐字相同的文本时仍然报错，绝不猜', async () => {
  // readSkew 让坐标路失准，锚点又不在文档里 → Find 也找不到
  installWps({ text: '本协议一式两份。\r', hiddenMarkAtJs: 1 })
  await assert.rejects(
    H.add_comment({ anchorText: '不存在的条款', comment: 'x' }),
    /未找到批注目标文本/
  )
})

/* ==================== apply_standard_format ==================== */

/** 按 \r 切段，与 mock 的 paragraphSegs 同口径 */
function paraSegsOf(text) {
  const parts = text.split('\r')
  const segs = []
  let pos = 0
  for (let k = 0; k < parts.length; k++) {
    const start = pos
    const end = pos + parts[k].length + (k < parts.length - 1 ? 1 : 0)
    segs.push({ start, end, text: parts[k] })
    pos = end
  }
  return segs
}

/**
 * 把区间写入折算成「每个段落最终拿到的格式」。断言用这个而不是 state.writes 原样，
 * 才能让同一组期望值在「逐段落笔」和「按 run 合并落笔」两种实现下都成立——
 * 这正是本次改动必须保持不变的东西。
 */
function effectiveFormat(state) {
  return paraSegsOf(state.text).map((seg) => {
    const props = {}
    for (const w of state.writes) {
      if (w.jsStart < seg.end && seg.start < w.jsEnd) props[`${w.kind}.${w.prop}`] = w.value
    }
    return { text: seg.text, props }
  })
}

const DOC_OPINION = [
  '法律意见书',
  '一、背景',
  '本所律师根据委托进行了尽职调查。',
  '经核查，目标公司股权结构清晰。',
  '目标公司不存在重大未决诉讼。',
  '',
  '二、结论',
  '综上所述，本所认为不存在实质性法律障碍。',
  ''
].join('\r')

/** 标题/小标题/正文三类各自该拿到的格式特征（不写死 HOUSE 数值，只钉住区分度） */
function assertHouseShape(rows) {
  const [title, h1, b1, b2, b3, blank, h2, b4] = rows
  for (const t of [title, h1, h2]) {
    assert.equal(t.props['font.Bold'], -1, `${t.text}：标题/小标题应加粗`)
    assert.equal(t.props['paraFormat.FirstLineIndent'], 0, `${t.text}：标题/小标题不缩进`)
  }
  for (const b of [b1, b2, b3, b4]) {
    assert.equal(b.props['font.Bold'], 0, `${b.text}：正文不加粗`)
    assert.ok(b.props['paraFormat.FirstLineIndent'] > 0, `${b.text}：正文首行缩进`)
    assert.equal(b.props['paraFormat.Alignment'], 3, `${b.text}：正文两端对齐`)
  }
  assert.equal(title.props['paraFormat.Alignment'], 1, '主标题居中')
  assert.ok(title.props['font.Size'] > b1.props['font.Size'], '主标题字号大于正文')
  assert.equal(h1.props['font.Size'], b1.props['font.Size'], '小标题与正文同字号，仅靠加粗区分')
  // 空段落一个属性都不该被写到——合并区间若跨过它，段后间距和行距会落到空行上
  assert.deepEqual(blank.props, {}, '空段落不应被格式化')
}

test('apply_standard_format：标题/小标题/正文分类与落笔结果（特征化）', async () => {
  const { state } = installWps({ text: DOC_OPINION })
  const data = await H.apply_standard_format({})
  assertHouseShape(effectiveFormat(state))
  assert.equal(data.paragraphs, 7) // 1 标题 + 2 小标题 + 4 正文，空段不计
  assert.equal(data.titles, 1)
  assert.equal(data.headings, 2)
  assert.equal(data.tracked, true)
  assert.deepEqual(state.trackLog, [true, false])
})

test('apply_standard_format：连续同类段落合并成一次写入（审阅窗格里少几百条格式修订）', async () => {
  const { state } = installWps({ text: DOC_OPINION })
  const data = await H.apply_standard_format({})
  // run 划分：[标题][一、背景][正文×3][二、结论][正文] = 5 批，而不是 7 段各写一次
  assert.equal(data.writeBatches, 5)
  assert.equal(state.writes.filter((w) => w.kind === 'font' && w.prop === 'Size').length, 5)
  assert.equal(data.degradedRuns, undefined)
  // 合并没有改变任何一段拿到的格式
  assertHouseShape(effectiveFormat(state))
})

test('apply_standard_format：合并区间与段落边界对不上时诚实降级、结果不变', async () => {
  // paragraphCountLie 模拟「偏移口径失准导致合并区间多盖了一段」
  const { state } = installWps({ text: DOC_OPINION, paragraphCountLie: true })
  const data = await H.apply_standard_format({})
  assert.equal(data.degradedRuns, 1) // 只有正文那个 3 段的 run 会走合并校验
  assert.equal(data.writeBatches, 7) // 降级后该 run 逐段落笔：4 + 3
  assert.match(data.note, /逐段落笔/)
  assertHouseShape(effectiveFormat(state)) // 降级路径给出完全相同的格式
})

test('apply_standard_format：表格单元格段落单独落笔，不与正文合并成区间', async () => {
  // 单元格文本尾部带 \x07（VBA 结束符）——合并区间横跨表格边界没有真机验证过
  const { state } = installWps({ text: '甲文书\r正文一。\r单元格\x07\r正文二。\r' })
  const data = await H.apply_standard_format({})
  assert.equal(data.writeBatches, 4) // 四段各写一次，没有任何合并
  const rows = effectiveFormat(state)
  assert.ok(rows[2].props['font.Size'] > 0, '单元格段落仍然被格式化了')
  // 没有任何一次写入同时盖住表格前后的两段正文
  const spanning = state.writes.filter((w) => w.jsStart < rows[1].end && rows[3].start < w.jsEnd)
  assert.equal(spanning.length, 0)
})

test('set_numbering：锚点不在文档里时立刻报错，不做逐段扫描', async () => {
  const { state } = installWps({ text: '第一段\r第二段\r第三段\r' })
  await assert.rejects(
    H.set_numbering({ anchorText: '根本不存在的锚点', kind: 'decimal' }),
    /未找到锚点段落/
  )
  // 全文快照 1 次跨桥就能判定；旧写法要逐段取 Range+Text，5000 段的文书上是好几秒
  assert.equal(state.paraRangeReads, 0)
})

/* ============ Find 兜底路的前置守卫（能力边界落差，宁可报错不要猜） ============ */

test('Find 兜底：拦截「replaceText 里含 searchText」——否则会反复替换刚生成的文本', async () => {
  // 兜底是「循环替换最靠前一处」。'AA'→'AA补' 时第一轮的产物里仍含 'AA' 且位置最靠前，
  // 第二轮会替换自己刚写出来的内容，堆出嵌套垃圾，而第二处原始命中一个没动。
  const { state } = installWps({ text: '甲AA乙AA丙', readSkew: 1 })
  await assert.rejects(
    H.replace_text({ searchText: 'AA', replaceText: 'AA补', replaceAll: true }),
    /replaceText 里包含了 searchText/
  )
  assert.equal(state.text, '甲AA乙AA丙') // 一个字都没动
  assert.equal(state.calls.filter((c) => c.name === 'Find.Execute').length, 0)
})

test('Find 兜底：单处替换时不拦「replaceText 含 searchText」（只有循环才会自噬）', async () => {
  const { state } = installWps({ text: '甲AA乙', readSkew: 1 })
  const data = await H.replace_text({ searchText: 'AA', replaceText: 'AA补' })
  assert.equal(state.text, '甲AA补乙')
  assert.equal(data.via, 'fullReplace')
})

test('Find 兜底：拦截跨段落的 replaceText（查找替换的替换框不认裸段落符）', async () => {
  const { state } = installWps({ text: '本条待定。', readSkew: 1 })
  await assert.rejects(
    H.replace_text({ searchText: '本条待定', replaceText: '第一行\n第二行' }),
    /不支持跨段落的替换文本/
  )
  assert.equal(state.text, '本条待定。')
})

test('Find 兜底：不再拦长锚点（真机实测 WPS 没有 255 字上限）', async () => {
  // 律师的锚点常常是一整条条款，几百字是常态。Word 的 255 字上限在 WPS 上不成立
  // （2026-08-29 实测：300 字查找串命中且回读文本逐字相同、长度也是 300）。
  const needle = 'A'.repeat(300)
  const { state } = installWps({ text: '甲' + needle + '乙', readSkew: 1 })
  const data = await H.replace_text({ searchText: needle, replaceText: '短文本' })
  assert.equal(state.text, '甲短文本乙')
  assert.equal(data.via, 'fullReplace')
})

test('Find 兜底：拦截含 ^ 的 searchText（^ 是查找语法的转义前导符）', async () => {
  const { state } = installWps({ text: '公式 a^b 结尾', readSkew: 1 })
  await assert.rejects(
    H.replace_text({ searchText: 'a^b', replaceText: 'x' }),
    /含 \^ 字符/
  )
  assert.equal(state.text, '公式 a^b 结尾')
})

test('Find 兜底：显式钉死匹配宽松度，不继承用户上次手动查找的设置', async () => {
  // IgnorePunct/IgnoreSpace/MatchFuzzy/MatchByte 不在 Execute 的 15 参签名里，
  // ClearFormatting() 也不清它们——不钉死的话同一份文档在两台机器上命中范围会不同。
  const { state } = installWps({ text: 'ABCDEFGH', readSkew: 1 })
  await H.replace_text({ searchText: 'CDE', replaceText: 'XYZ' })
  assert.deepEqual(state.findProps, {
    IgnorePunct: false,
    IgnoreSpace: false,
    MatchFuzzy: false,
    MatchByte: true // 极性与其余三个相反：true 才是「区分全角/半角」
  })
  assert.ok(state.calls.some((c) => c.name === 'Find.ClearAllFuzzyOptions'))
})

test('format_text：applyToAll 任一命中校验失败时不留半写入', async () => {
  // 前一处校验通过、后一处失准。边校验边写的旧写法会先把第一处加粗再抛错，
  // 用户拿到「半篇改了格式 + 一条报错」，还留着一片修订。
  const { state } = installWps({ text: '甲AA乙AA丙', readSkew: 1, readSkewAfter: 4 })
  await assert.rejects(
    H.format_text({ anchorText: 'AA', bold: true, applyToAll: true }),
    /定位校验失败/
  )
  assert.equal(state.writes.length, 0)
  assert.equal(state.track, false) // withTracking 的 finally 仍把修订开关恢复了
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

/* ============ edit_header_footer：只给 alignment 不许清空页眉（dev-board#288） ============ */

test('edit_header_footer：只改对齐方式时绝不许动页眉文字', async () => {
  // 旧写法无条件整替，text 兜底成空串——模型只想改对齐，一调用就把用户的页眉清空，
  // 返回值还报成功。律师的页眉常是所名/文号，清掉了很难第一时间发现。
  const { state } = installWps({ text: '正文\r', headerText: '某某律师事务所' })
  const out = await H.edit_header_footer({ part: 'header', alignment: 'center' })
  assert.equal(state.hf.header.text, '某某律师事务所', '页眉文字不该被动')
  assert.equal(state.hf.header.writes, 0, `不该对页眉 Range.Text 赋值，实际写了 ${state.hf.header.writes} 次`)
  assert.equal(out.textUpdated, false, '返回值要如实说没改文字')
  assert.ok(state.hf.header.alignment != null, '对齐方式应当改到')
})

test('edit_header_footer：显式传空串仍然是「清空」这个合法意图', async () => {
  const { state } = installWps({ text: '正文\r', headerText: '某某律师事务所' })
  const out = await H.edit_header_footer({ part: 'header', text: '' })
  assert.equal(state.hf.header.text, '')
  assert.equal(out.textUpdated, true)
})

test('edit_header_footer：text 与 alignment 都不给时报错，而不是静默清空', async () => {
  installWps({ text: '正文\r', headerText: '某某律师事务所' })
  await assert.rejects(H.edit_header_footer({ part: 'header' }), /至少给 text.*或 alignment/)
})
