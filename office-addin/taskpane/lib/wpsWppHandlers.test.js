/**
 * wpsWppHandlers 单测：node 自带 test runner，零依赖。
 * mock globalThis.wps 里的 WPP 对象模型（VBA 同构同步 API），
 * 契约口径以 officeExecutor.js 的 ppt_* 为准绳。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { WPS_WPP_HANDLERS as H, hexToComRgb, comRgbToHex } from './wpsWppHandlers.js'

// ==================== mock WPP 对象模型 ====================

let idSeq = 1000
function nextId() { return ++idSeq }

function makeFont() { return { Color: { RGB: null } } }

/**
 * TextRange mock：offset 为 0 基绝对起点，length=null 表示动态到文本末尾。
 * Characters(Start, Length) 的 Start 是相对本 range 的 1 基；Replace 找不到返回 null，
 * 命中则原地替换并返回 {Start(1 基相对), Length(替换后长度)}——与调研口径一致。
 */
function makeRange(state, offset, length, ctx, isSub = false) {
  function end() {
    const cap = length == null ? Infinity : offset + length
    return Math.min(cap, state.text.length)
  }
  const self = {
    Font: makeFont(),
    get Text() { return state.text.slice(offset, end()) },
    set Text(v) { state.text = state.text.slice(0, offset) + String(v) + state.text.slice(end()) },
    get Length() { return end() - offset },
    Characters(start, len) {
      const abs = offset + (start - 1)
      // 真机实测：起点越界不是静默塌到末尾，而是抛 COM E_FAIL
      if (start < 1 || abs > state.text.length) {
        throw new Error('mock：Characters 起点越界（真机是 COM E_FAIL）')
      }
      const sub = makeRange(state, abs, len, ctx, true)
      ctx.log.push({ start, len, absStart: abs, range: sub })
      return sub
    },
    ActionSettings(idx) {
      if (isSub && ctx.subActionThrows) throw new Error('mock: 子串不支持 ActionSettings')
      self._actions = self._actions || {}
      self._actions[idx] = self._actions[idx] || { Action: 0, Hyperlink: { Address: '' } }
      return self._actions[idx]
    },
    Replace(findWhat, replaceWhat) {
      const span = state.text.slice(offset, end())
      const p = span.indexOf(findWhat)
      if (p === -1) return null
      state.text = state.text.slice(0, offset + p) + replaceWhat +
        state.text.slice(offset + p + findWhat.length)
      // Start 是**形状内绝对位置**（1 基），不是相对本 range 的——真机实测确认
      // （原先这个 mock 返回相对位置，正好把「续查串坐标系」那个 bug 掩盖住了）
      return { Start: offset + p + 1, Length: String(replaceWhat).length }
    }
  }
  return self
}

function makeTableObj(rowsData) {
  const cells = rowsData.map((r) => r.map((t) => ({ text: String(t) })))
  // 单元格里的 Shape 要长得跟真形状一样（HasTextFrame + TextFrame.HasText + 完整
  // TextRange），否则写入侧的遍历看不见它们——真机上它们就是普通形状
  const cellShapes = cells.map((row) => row.map((st) => {
    const ctx = { log: [], subActionThrows: false }
    return {
      HasTextFrame: -1,
      HasTable: 0,
      Type: 17,
      TextFrame: {
        get HasText() { return st.text ? -1 : 0 },
        TextRange: makeRange(st, 0, null, ctx)
      }
    }
  }))
  return {
    Rows: { get Count() { return cells.length } },
    Columns: { get Count() { return cells[0].length } },
    Cell(r, c) {
      if (r < 1 || r > cells.length || c < 1 || c > cells[0].length) throw new Error('mock: cell 越界')
      return { Shape: cellShapes[r - 1][c - 1] }
    },
    _cells: cells
  }
}

function makeShape(spec, parentArr) {
  const ctx = { log: [], subActionThrows: !!spec.subActionThrows }
  const shape = {
    Id: spec.id != null ? spec.id : nextId(),
    Type: spec.type != null ? spec.type : 17,
    Left: spec.left != null ? spec.left : 10,
    Top: spec.top != null ? spec.top : 20,
    Width: spec.width != null ? spec.width : 100,
    Height: spec.height != null ? spec.height : 50,
    HasTextFrame: 0,
    HasTable: 0,
    _charLog: ctx.log,
    Delete() { parentArr.splice(parentArr.indexOf(shape), 1) }
  }
  if (spec.text != null) {
    const state = { text: String(spec.text) }
    shape._state = state
    shape.HasTextFrame = -1
    shape.TextFrame = {
      get HasText() { return state.text ? -1 : 0 },
      TextRange: makeRange(state, 0, null, ctx)
    }
  }
  if (spec.table) {
    shape.Type = 19
    shape.HasTable = -1
    shape.Table = makeTableObj(spec.table)
  }
  if (spec.group) {
    shape.Type = 6 // msoGroup
    const children = []
    for (const childSpec of spec.group) children.push(makeShape(childSpec, children))
    shape.GroupItems = {
      get Count() { return children.length },
      Item(i) { return children[i - 1] }
    }
  }
  return shape
}

function makeSlide(shapeSpecs) {
  const arr = []
  const slide = {
    _shapes: arr,
    CustomLayout: { tag: 'layout-' + nextId() },
    Shapes: {
      get Count() { return arr.length },
      Item(j) {
        if (j < 1 || j > arr.length) throw new Error('mock: shape 序号越界')
        return arr[j - 1]
      },
      AddTextbox(orientation, left, top, width, height) {
        const sp = makeShape({ text: '', left, top, width, height }, arr)
        sp._addTextboxArgs = { orientation, left, top, width, height }
        arr.push(sp)
        return sp
      },
      AddShape(type, left, top, width, height) {
        const sp = makeShape({ type: 1, left, top, width, height }, arr)
        sp._autoShapeType = type
        sp.Fill = { _solid: false, Solid() { sp.Fill._solid = true }, ForeColor: { RGB: null } }
        arr.push(sp)
        return sp
      },
      AddTable(rows, cols) {
        const sp = makeShape({
          table: Array.from({ length: rows }, () => Array.from({ length: cols }, () => ''))
        }, arr)
        arr.push(sp)
        return sp
      }
    }
  }
  for (const spec of shapeSpecs) arr.push(makeShape(spec, arr))
  return slide
}

/** slideSpecs：每页一个 shape spec 数组 */
function makeDeck(slideSpecs) {
  const slides = []
  function wireSlide(s) {
    s.Delete = () => { slides.splice(slides.indexOf(s), 1) }
    s.MoveTo = (to) => {
      const i = slides.indexOf(s)
      slides.splice(i, 1)
      slides.splice(to - 1, 0, s)
    }
    return s
  }
  const pres = {
    _slides: slides,
    SlideMaster: { CustomLayouts: { Item(i) { return { master: i } } } },
    Slides: {
      get Count() { return slides.length },
      Item(i) {
        if (i < 1 || i > slides.length) throw new Error('mock: slide 序号越界')
        return slides[i - 1]
      },
      AddSlide(index, layout) {
        const s = wireSlide(makeSlide([]))
        s._layoutUsed = layout
        slides.splice(index - 1, 0, s)
        return s
      }
    }
  }
  for (const spec of slideSpecs) slides.push(wireSlide(makeSlide(spec)))
  return pres
}

function install(pres) {
  globalThis.wps = { WppApplication: () => ({ ActivePresentation: pres }) }
  return pres
}

// ==================== 颜色转换 ====================

test('hexToComRgb/comRgbToHex：BGR 打包（低字节红）与往返', () => {
  assert.equal(hexToComRgb('#336699'), 0x996633)
  assert.equal(hexToComRgb('ff0000'), 0x0000ff)
  assert.equal(comRgbToHex(0x996633), '#336699')
  assert.equal(comRgbToHex(hexToComRgb('#0a1b2c')), '#0a1b2c')
  assert.throws(() => hexToComRgb('#f00'), /颜色值非法/)
  assert.throws(() => hexToComRgb('红色'), /颜色值非法/)
})

// ==================== 表格 / 组合里的文字（dev-board#288） ====================

test('ppt_get_slides：表格单元格与组合子形状里的文字必须读得到', async () => {
  // 演示稿的正文常常不在顶层文本框里——对比表在表格里、图示标注在组合里。
  // 读取侧（wpsDoc 的内联正文）早就按三条路收，写入侧此前只看顶层 TextFrame：
  // 模型在上下文里看得到那些字，一改就说「未找到」。
  install(makeDeck([[
    { text: '本页标题' },
    { table: [['条款', '约定'], ['违约金', '合同总价百分之五']] },
    { group: [{ text: '图示标注甲' }, { group: [{ text: '嵌套标注乙' }] }] }
  ]]))
  const out = await H.ppt_get_slides({})
  const texts = out.slides[0].texts
  assert.ok(texts.includes('本页标题'))
  assert.ok(texts.includes('违约金'), `表格单元格文字应读到，实际 ${JSON.stringify(texts)}`)
  assert.ok(texts.includes('合同总价百分之五'))
  assert.ok(texts.includes('图示标注甲'), '组合子形状文字应读到')
  assert.ok(texts.includes('嵌套标注乙'), '组合套组合也要递归')
})

test('ppt_replace_text：改得到表格单元格里的文字（此前只能改顶层文本框）', async () => {
  const pres = install(makeDeck([[
    { table: [['甲方', '乙方'], ['甲方应付款', '见附件']] }
  ]]))
  const out = await H.ppt_replace_text({ searchText: '甲方', replaceText: '委托人' })
  assert.equal(out.replaced, 2, `两处「甲方」都在表格里，都要替换到，实际 ${out.replaced}`)
  const cells = pres.Slides.Item(1).Shapes.Item(1).Table._cells
  assert.equal(cells[0][0].text, '委托人')
  assert.equal(cells[1][0].text, '委托人应付款')
  assert.equal(cells[0][1].text, '乙方', '不该动到别的单元格')
})

// ==================== ppt_get_slides ====================

test('ppt_get_slides：逐页收集非空文本，跳过无文本/空文本形状', async () => {
  install(makeDeck([
    [{ text: ' 标题一 ' }, { type: 13 }],
    [{ text: '第二页' }, { text: '' }]
  ]))
  const out = await H.ppt_get_slides()
  assert.equal(out.slideCount, 2)
  assert.deepEqual(out.slides[0], { slide: 1, texts: ['标题一'] })
  assert.deepEqual(out.slides[1], { slide: 2, texts: ['第二页'] })
})

test('无打开的演示文稿：抛中文错误', async () => {
  install(null)
  await assert.rejects(H.ppt_get_slides(), /当前没有打开的演示文稿/)
})

// ==================== ppt_replace_text ====================

test('ppt_replace_text：TextRange.Replace 续查多命中，跨页统计', async () => {
  const pres = install(makeDeck([
    [{ text: '甲方与甲方' }],
    [{ text: '甲方代表签字' }, { type: 13 }]
  ]))
  const out = await H.ppt_replace_text({ searchText: '甲方', replaceText: '乙方' })
  assert.equal(out.replaced, 3)
  assert.deepEqual(out.slides, [1, 2])
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._state.text, '乙方与乙方')
  assert.equal(pres.Slides.Item(2).Shapes.Item(1)._state.text, '乙方代表签字')
})

test('ppt_replace_text：同一文本框里三处以上全部替换（续查用形状内绝对游标）', async () => {
  // 旧写法拿上一次命中的 Start（形状内绝对）去切子区间（相对坐标），第一轮恰好相等
  // 看不出来，第二轮起就串坐标系——真机第三处直接抛 COM E_FAIL。
  // 「把甲方改成乙方」这种全篇替换，第三处起漏替而工具报成功，肉眼发现不了。
  const pres = install(makeDeck([[{ text: 'AA甲方BB甲方CC甲方DD' }]]))
  const out = await H.ppt_replace_text({ searchText: '甲方', replaceText: '乙方' })
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._state.text, 'AA乙方BB乙方CC乙方DD')
  assert.equal(out.replaced, 3)
})

test('ppt_replace_text：replaceText 里含 searchText 时不自噬', async () => {
  // 「甲」→「甲方」是律师最常做的改写之一。游标跳到刚写进去的内容之后，
  // 否则会反复替换自己刚生成的文本，把一页撑成「甲方方方方…」
  const pres = install(makeDeck([[{ text: '第一条 甲负责，第二条 甲配合' }]]))
  const out = await H.ppt_replace_text({ searchText: '甲', replaceText: '甲方' })
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._state.text, '第一条 甲方负责，第二条 甲方配合')
  assert.equal(out.replaced, 2)
})

test('ppt_replace_text：未命中抛错、searchText 空抛错', async () => {
  install(makeDeck([[{ text: '正文' }]]))
  await assert.rejects(H.ppt_replace_text({ searchText: '不存在', replaceText: 'x' }), /未找到目标文本/)
  await assert.rejects(H.ppt_replace_text({ searchText: '' }), /查找文本不能为空/)
})

// ==================== ppt_format_text ====================

test('ppt_format_text：Characters 按 1 基精确切子串，applyToAll 命中全部', async () => {
  const pres = install(makeDeck([[{ text: '本合同期限为三年，续约三年' }]]))
  const out = await H.ppt_format_text({
    searchText: '三年', bold: true, color: '#c00000', fontName: '宋体', applyToAll: true
  })
  assert.equal(out.formatted, 2)
  const log = pres.Slides.Item(1).Shapes.Item(1)._charLog
  assert.equal(log.length, 2)
  assert.deepEqual([log[0].start, log[0].len], [7, 2]) // 0 基偏移 6 → 1 基 7
  assert.deepEqual([log[1].start, log[1].len], [12, 2])
  for (const entry of log) {
    assert.equal(entry.range.Font.Bold, -1)
    assert.equal(entry.range.Font.Color.RGB, hexToComRgb('#c00000'))
    assert.equal(entry.range.Font.Name, '宋体')
    assert.equal(entry.range.Font.NameFarEast, '宋体') // 中文字体双设
  }
})

test('ppt_format_text：默认只格式化第一处命中', async () => {
  const pres = install(makeDeck([[{ text: '三年之后又三年' }]]))
  const out = await H.ppt_format_text({ searchText: '三年', italic: true })
  assert.equal(out.formatted, 1)
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._charLog.length, 1)
})

test('ppt_format_text：下划线 wave 降级为开并 note 交底，none 落关', async () => {
  const pres = install(makeDeck([[{ text: '重点条款' }]]))
  const out = await H.ppt_format_text({ searchText: '重点', underline: 'wave' })
  assert.match(out.note, /降级为普通下划线/)
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._charLog[0].range.Font.Underline, -1)

  const pres2 = install(makeDeck([[{ text: '重点条款' }]]))
  const out2 = await H.ppt_format_text({ searchText: '重点', underline: 'none' })
  assert.equal(out2.note, undefined)
  assert.equal(pres2.Slides.Item(1).Shapes.Item(1)._charLog[0].range.Font.Underline, 0)
})

test('ppt_format_text：非法下划线值与无格式参数报错', async () => {
  install(makeDeck([[{ text: '正文' }]]))
  await assert.rejects(H.ppt_format_text({ searchText: '正文', underline: 'thick' }), /underline 值非法/)
  await assert.rejects(H.ppt_format_text({ searchText: '正文' }), /未给出任何格式参数/)
})

// ==================== ppt_add_slide ====================

test('ppt_add_slide：AddSlide 原生带插入位置，版式取附近现有页', async () => {
  const pres = install(makeDeck([[{ text: '第一页' }], [{ text: '第二页' }]]))
  const out = await H.ppt_add_slide({ position: 2, title: '新标题', body: '新正文' })
  assert.deepEqual(out, { slideAdded: true, position: 2, moved: false, titleAdded: true, bodyAdded: true })
  assert.equal(pres.Slides.Count, 3)
  const added = pres.Slides.Item(2)
  assert.equal(added._layoutUsed, pres.Slides.Item(1).CustomLayout)
  assert.equal(added.Shapes.Count, 2)
  const titleBox = added.Shapes.Item(1)
  assert.equal(titleBox._state.text, '新标题')
  assert.equal(titleBox._addTextboxArgs.orientation, 1) // msoTextOrientationHorizontal
  assert.equal(titleBox.TextFrame.TextRange.Font.Size, 28)
  assert.equal(titleBox.TextFrame.TextRange.Font.Bold, -1)
  assert.equal(added.Shapes.Item(2)._state.text, '新正文')
})

test('ppt_add_slide：空演示文稿兜底母版版式，缺省追加到末尾', async () => {
  const pres = install(makeDeck([]))
  const out = await H.ppt_add_slide({})
  assert.equal(out.position, 1)
  assert.deepEqual(pres.Slides.Item(1)._layoutUsed, { master: 2 })

  const pres2 = install(makeDeck([[{ text: 'A' }]]))
  const out2 = await H.ppt_add_slide({})
  assert.equal(out2.position, 2)
  assert.equal(pres2.Slides.Count, 2)
})

// ==================== ppt_delete_slide ====================

test('ppt_delete_slide：正常删除；只剩一页拒删；越界报错', async () => {
  const pres = install(makeDeck([[{ text: 'A' }], [{ text: 'B' }]]))
  const out = await H.ppt_delete_slide({ slideNumber: 1 })
  assert.deepEqual(out, { deleted: true, slideNumber: 1, remaining: 1 })
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._state.text, 'B')
  await assert.rejects(H.ppt_delete_slide({ slideNumber: 1 }), /只剩一页/)

  install(makeDeck([[{ text: 'A' }], [{ text: 'B' }]]))
  await assert.rejects(H.ppt_delete_slide({ slideNumber: 5 }), /越界/)
  await assert.rejects(H.ppt_delete_slide({ slideNumber: 0 }), /大于等于 1 的整数/)
})

// ==================== ppt_add_text_box ====================

test('ppt_add_text_box：默认几何 50/50/400/100，字体与颜色落地', async () => {
  const pres = install(makeDeck([[{ text: '已有' }]]))
  const out = await H.ppt_add_text_box({ slideNumber: 1, text: '新文本', fontSize: 20, bold: true, color: '#ff0000' })
  assert.deepEqual(out, { added: true, slideNumber: 1 })
  const box = pres.Slides.Item(1).Shapes.Item(2)
  assert.deepEqual(box._addTextboxArgs, { orientation: 1, left: 50, top: 50, width: 400, height: 100 })
  assert.equal(box._state.text, '新文本')
  assert.equal(box.TextFrame.TextRange.Font.Size, 20)
  assert.equal(box.TextFrame.TextRange.Font.Bold, -1)
  assert.equal(box.TextFrame.TextRange.Font.Color.RGB, 255) // #ff0000 低字节是红
})

test('ppt_add_text_box：空文本与越界页码报错', async () => {
  install(makeDeck([[{ text: 'A' }]]))
  await assert.rejects(H.ppt_add_text_box({ slideNumber: 1, text: '' }), /文本框内容不能为空/)
  await assert.rejects(H.ppt_add_text_box({ slideNumber: 9, text: 'x' }), /越界/)
})

// ==================== ppt_move_slide ====================

test('ppt_move_slide：MoveTo 1 基移动，toPosition 越界 clamp', async () => {
  const pres = install(makeDeck([[{ text: 'A' }], [{ text: 'B' }], [{ text: 'C' }]]))
  const out = await H.ppt_move_slide({ slideNumber: 3, toPosition: 1 })
  assert.deepEqual(out, { moved: true, from: 3, to: 1 })
  const order = [1, 2, 3].map((i) => pres.Slides.Item(i).Shapes.Item(1)._state.text)
  assert.deepEqual(order, ['C', 'A', 'B'])

  const out2 = await H.ppt_move_slide({ slideNumber: 1, toPosition: 99 })
  assert.equal(out2.to, 3)
  await assert.rejects(H.ppt_move_slide({ slideNumber: 9, toPosition: 1 }), /越界/)
})

// ==================== ppt_add_shape ====================

test('ppt_add_shape：ellipse 映射 msoShapeOval=9，填充先 Solid 再 RGB', async () => {
  const pres = install(makeDeck([[{ text: 'A' }]]))
  const out = await H.ppt_add_shape({ slideNumber: 1, shapeType: 'ellipse', fillColor: '#00ff00' })
  assert.deepEqual(out, { added: true, slideNumber: 1, shapeType: 'ellipse' })
  const sp = pres.Slides.Item(1).Shapes.Item(2)
  assert.equal(sp._autoShapeType, 9)
  assert.equal(sp.Fill._solid, true)
  assert.equal(sp.Fill.ForeColor.RGB, hexToComRgb('#00ff00'))
  await assert.rejects(H.ppt_add_shape({ slideNumber: 1, shapeType: 'star' }), /shapeType 值非法/)
})

// ==================== ppt_get_slide_details ====================

test('ppt_get_slide_details：Type 数值转可读名、Id 转字符串、文本截断 500', async () => {
  install(makeDeck([[
    { id: 5, type: 17, text: '长'.repeat(600), left: 1, top: 2, width: 3, height: 4 },
    { id: 6, table: [['x']] },
    { id: 7, type: 42 }
  ]]))
  const out = await H.ppt_get_slide_details({ slideNumber: 1 })
  assert.equal(out.shapeCount, 3)
  assert.deepEqual(out.shapes[0], {
    id: '5', type: 'textBox', left: 1, top: 2, width: 3, height: 4, text: '长'.repeat(500)
  })
  assert.equal(out.shapes[1].type, 'table')
  assert.equal(out.shapes[2].type, 'unknown(42)')
})

// ==================== ppt_delete_shape ====================

test('ppt_delete_shape：textMatch 全等定位（\\r/\\n 段落符归一）', async () => {
  const pres = install(makeDeck([[{ text: '别删我' }, { id: 33, text: '第一行\r第二行' }]]))
  const out = await H.ppt_delete_shape({ slideNumber: 1, textMatch: '第一行\n第二行' })
  assert.deepEqual(out, { deleted: true, slideNumber: 1, shapeId: '33' })
  assert.equal(pres.Slides.Item(1).Shapes.Count, 1)
  assert.equal(pres.Slides.Item(1).Shapes.Item(1)._state.text, '别删我')
})

test('ppt_delete_shape：shapeId 定位与错误路径', async () => {
  const pres = install(makeDeck([[{ id: 8, text: 'A' }, { id: 9, type: 13 }]]))
  const out = await H.ppt_delete_shape({ slideNumber: 1, shapeId: 9 })
  assert.equal(out.shapeId, '9')
  assert.equal(pres.Slides.Item(1).Shapes.Count, 1)
  await assert.rejects(H.ppt_delete_shape({ slideNumber: 1, shapeId: '404' }), /未找到 id 为 404/)
  await assert.rejects(H.ppt_delete_shape({ slideNumber: 1, textMatch: '不存在' }), /textMatch 精确一致/)
  await assert.rejects(H.ppt_delete_shape({ slideNumber: 1 }), /至少给一个/)
})

// ==================== ppt_add_table / ppt_table_read / ppt_table_set_cell ====================

test('ppt_add_table：rows 二维数组建表填格（Cell 1 基），几何按给参落地', async () => {
  const pres = install(makeDeck([[]]))
  const out = await H.ppt_add_table({
    slideNumber: 1, rows: [['甲', '乙'], ['丙', '丁']], left: 30, top: 40
  })
  assert.deepEqual(out, { added: true, slideNumber: 1, rows: 2, cols: 2 })
  const sp = pres.Slides.Item(1).Shapes.Item(1)
  assert.equal(sp.Left, 30)
  assert.equal(sp.Top, 40)
  assert.deepEqual(sp.Table._cells.map((r) => r.map((c) => c.text)), [['甲', '乙'], ['丙', '丁']])
  await assert.rejects(H.ppt_add_table({ slideNumber: 1 }), /表格行列数非法/)
})

test('ppt_table_read：逐格读取并去掉末尾段落符，缺省取第一个表格', async () => {
  install(makeDeck([[
    { text: '不是表格' },
    { table: [['甲', '乙\r'], ['丙', '']] }
  ]]))
  const out = await H.ppt_table_read({ slideNumber: 1 })
  assert.deepEqual(out, { slideNumber: 1, rowCount: 2, colCount: 2, cells: [['甲', '乙'], ['丙', '']] })
})

test('ppt_table_read：shapeId 定位与无表格报错', async () => {
  install(makeDeck([[
    { id: 11, table: [['一']] },
    { id: 12, table: [['二']] }
  ]]))
  const out = await H.ppt_table_read({ slideNumber: 1, shapeId: '12' })
  assert.deepEqual(out.cells, [['二']])
  await assert.rejects(H.ppt_table_read({ slideNumber: 1, shapeId: '404' }), /未找到 id 为 404/)

  install(makeDeck([[{ text: '没有表格' }]]))
  await assert.rejects(H.ppt_table_read({ slideNumber: 1 }), /该页没有表格/)
})

test('ppt_table_set_cell：契约 0 基转 Cell 1 基，越界报单元格不存在', async () => {
  const pres = install(makeDeck([[{ table: [['a', 'b'], ['c', 'd']] }]]))
  const out = await H.ppt_table_set_cell({ slideNumber: 1, row: 1, col: 1, text: '新值' })
  assert.deepEqual(out, { slideNumber: 1, row: 1, col: 1, updated: true })
  assert.equal(pres.Slides.Item(1).Shapes.Item(1).Table._cells[1][1].text, '新值')
  await assert.rejects(H.ppt_table_set_cell({ slideNumber: 1, row: 5, col: 0, text: 'x' }),
    /单元格 \(5,0\) 不存在/)
  await assert.rejects(H.ppt_table_set_cell({ slideNumber: 1, row: -1, col: 0, text: 'x' }), /row 不能为负/)
})

// ==================== ppt_set_hyperlink ====================

test('ppt_set_hyperlink：子串路线——先 Action=ppActionHyperlink 再设 Address', async () => {
  const pres = install(makeDeck([[{ text: '详见官网链接' }]]))
  const out = await H.ppt_set_hyperlink({ slideNumber: 1, searchText: '官网', url: 'https://aiworkdeck.com' })
  assert.deepEqual(out, { slideNumber: 1, linked: true, url: 'https://aiworkdeck.com' })
  const log = pres.Slides.Item(1).Shapes.Item(1)._charLog
  assert.equal(log.length, 1)
  assert.deepEqual([log[0].start, log[0].len], [3, 2]) // 0 基偏移 2 → 1 基 3
  const action = log[0].range._actions[1] // ppMouseClick=1
  assert.equal(action.Action, 7) // ppActionHyperlink
  assert.equal(action.Hyperlink.Address, 'https://aiworkdeck.com')
})

test('ppt_set_hyperlink：子串挂链失败降级整段挂链并 note 交底', async () => {
  const pres = install(makeDeck([[{ text: '点此访问', subActionThrows: true }]]))
  const out = await H.ppt_set_hyperlink({ slideNumber: 1, searchText: '访问', url: 'https://wps.cn' })
  assert.equal(out.linked, true)
  assert.match(out.note, /降级为对整个文本框文字设置超链接/)
  const whole = pres.Slides.Item(1).Shapes.Item(1).TextFrame.TextRange
  assert.equal(whole._actions[1].Action, 7)
  assert.equal(whole._actions[1].Hyperlink.Address, 'https://wps.cn')
})

test('ppt_set_hyperlink：未命中与空参报错', async () => {
  install(makeDeck([[{ text: '正文' }]]))
  await assert.rejects(H.ppt_set_hyperlink({ slideNumber: 1, searchText: '不存在', url: 'https://x.cn' }),
    /未找到目标文本/)
  await assert.rejects(H.ppt_set_hyperlink({ slideNumber: 1, searchText: '', url: 'https://x.cn' }),
    /查找文本不能为空/)
  await assert.rejects(H.ppt_set_hyperlink({ slideNumber: 1, searchText: '正文', url: '' }), /url 不能为空/)
})
