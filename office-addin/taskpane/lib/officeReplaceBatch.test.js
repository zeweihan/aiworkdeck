/**
 * Word 面批量改写（office_replace_batch）的过桥量与安全不变式（dev-board#419）：
 *   node --test office-addin/taskpane/lib/officeReplaceBatch.test.js
 *
 * 病灶：整篇校对是「一处一处改」的工作负载（一份合同几十到上百处），而 Word 面
 * 唯一的写入通道 `replace_text` 一次只改一处。每处都要一整轮 LLM + 一次 SSE 下发
 * + 一个 `Word.run` + 七次 `context.sync()`（其中四次只是为了把修订开关开了又关）。
 * 于是 N 处修改 = N 轮 LLM + 7N 次跨进程往返，后端 MAX_LOOP_DEPTH=30 又把一轮的
 * 步数封死在 30 —— 整篇校对结构上跑不完，用户看到的就是「正在操作文档」六分钟
 * 不回来。
 *
 * 不变式（本用例逐条盯死）：
 * 1. 一次 `replace_batch` 改 N 处的 `context.sync()` 次数**与 N 无关**（常数级），
 *    修订开关整批只开关一次；
 * 2. **所有查找都在任何一次写入之前完成**——一条定位失败不许留下改了一半的文档；
 * 3. 定位失败的条目逐条回报（模型只重试失败的那几条，而不是整批重来）；
 * 4. searchText 重复的批次整批拒绝（两条会指向同一处，落笔两遍）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

/* ==================== Office.js / Word 假宿主 ==================== */

/**
 * 把文档建模成一个可变字符串 + 一批「活的」Range。
 * Word 的 Range 在别处发生编辑后仍指向同一段逻辑文本，所以写入落地时要把
 * 其余 Range 的偏移一起搬——不搬的话本用例会因为 mock 失真而误报。
 */
function installWord(initialText) {
  const savedWord = globalThis.Word
  const savedOffice = globalThis.Office
  const state = {
    text: initialText,
    syncs: 0,
    /** 每次 sync 的序号 -> 该次 sync 里是否发生过写入 */
    writeSyncs: [],
    trackingModeWrites: [],
    liveRanges: [],
    queue: []
  }

  function shiftRanges(start, end, newLen) {
    const delta = newLen - (end - start)
    if (!delta) return
    for (const r of state.liveRanges) {
      if (r._start >= end) { r._start += delta; r._end += delta } else if (r._start <= start && r._end >= end) r._end += delta
    }
  }

  function makeRange(start, end) {
    const r = {
      _start: start,
      _end: end,
      text: undefined,
      load(props) {
        const wanted = String(props)
        state.queue.push({ kind: 'load', run: () => { if (wanted.includes('text')) r.text = state.text.slice(r._start, r._end) } })
      },
      search(needle, opts) { return makeCollection(needle, opts, r) },
      insertText(value, location) {
        state.queue.push({
          kind: 'write',
          run: () => {
            const loc = String(location || 'Replace').toLowerCase()
            let from = r._start
            let to = r._end
            if (loc === 'after') { from = r._end; to = r._end } else if (loc === 'before') { from = r._start; to = r._start }
            state.text = state.text.slice(0, from) + value + state.text.slice(to)
            shiftRanges(from, to, value.length)
            if (loc === 'replace') r._end = r._start + value.length
          }
        })
      },
      delete() { r.insertText('', 'Replace') }
    }
    state.liveRanges.push(r)
    return r
  }

  function makeCollection(needle, opts, scope) {
    const col = {
      items: [],
      load() {
        state.queue.push({
          kind: 'load',
          run: () => {
            const from = scope ? scope._start : 0
            const to = scope ? scope._end : state.text.length
            const hay = state.text.slice(from, to)
            const found = []
            let at = hay.indexOf(needle)
            while (at !== -1 && needle) {
              found.push(makeRange(from + at, from + at + needle.length))
              at = hay.indexOf(needle, at + needle.length)
            }
            col.items = found
          }
        })
      }
    }
    return col
  }

  const context = {
    document: {
      changeTrackingMode: 'Off',
      get body() { return makeRange(0, state.text.length) },
      load(props) {
        void props // changeTrackingMode 在 mock 里始终是最新值
      }
    },
    async sync() {
      const idx = state.syncs++
      const pending = state.queue
      state.queue = []
      let wrote = false
      for (const item of pending) {
        if (item.kind === 'write') wrote = true
        item.run()
      }
      state.writeSyncs[idx] = wrote
    }
  }

  // 修订开关的写入要留痕：整批只该开关一次
  let mode = 'Off'
  Object.defineProperty(context.document, 'changeTrackingMode', {
    get() { return mode },
    set(v) { mode = v; state.trackingModeWrites.push(v) },
    configurable: true
  })

  globalThis.Word = {
    run: async (cb) => cb(context),
    InsertLocation: { replace: 'Replace', after: 'After', before: 'Before' },
    ChangeTrackingMode: { trackAll: 'TrackAll', off: 'Off' },
    UnderlineType: {},
    Alignment: {}
  }
  globalThis.Office = {
    context: { host: 'Word', requirements: { isSetSupported: () => true } },
    HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' }
  }

  return {
    state,
    restore() { globalThis.Word = savedWord; globalThis.Office = savedOffice }
  }
}

/* ==================== 夹具 ==================== */

/** 一份「像合同」的正文，正文里埋 n 处待校对的错别字 */
function makeContract(n) {
  const lines = []
  for (let i = 1; i <= n; i++) {
    lines.push(`第${i}条  甲方应当于本协议签署之日起${i}个工作日内向乙方支付第${i}期款项，逾期按日承担违约责仁。`)
  }
  return lines.join('\r')
}

function makeEdits(n) {
  const out = []
  for (let i = 1; i <= n; i++) {
    out.push({
      searchText: `逾期按日承担违约责仁。`.replace('逾期', `第${i}期款项，逾期`),
      replaceText: `逾期按日承担违约责任。`.replace('逾期', `第${i}期款项，逾期`)
    })
  }
  return out
}

const BATCH_N = 20

/* ==================== 用例 ==================== */

test('replace_batch：改 N 处的过桥量与 N 无关，修订开关整批只开关一次', async () => {
  const { executeOfficeCommand } = await import('./officeExecutor.js')
  const wordSingle = installWord(makeContract(BATCH_N))
  let sequentialSyncs = 0
  try {
    // 基线：现状路径（一条命令一处）
    for (const e of makeEdits(BATCH_N)) {
      const r = await executeOfficeCommand('replace_text', e)
      assert.equal(r.ok, true, r.error)
    }
    sequentialSyncs = wordSingle.state.syncs
    assert.equal(wordSingle.state.text.includes('违约责仁'), false, '基线路径应已改完全部 N 处')
    console.error(`[基线] 逐处 replace_text 改 ${BATCH_N} 处：context.sync() ${sequentialSyncs} 次、`
      + `修订开关写入 ${wordSingle.state.trackingModeWrites.length} 次、Word.run ${BATCH_N} 次、LLM 轮次 ${BATCH_N} 轮`)
  } finally {
    wordSingle.restore()
  }

  const wordBatch = installWord(makeContract(BATCH_N))
  try {
    const r = await executeOfficeCommand('replace_batch', { items: makeEdits(BATCH_N) })
    assert.equal(r.ok, true, r.error)
    assert.equal(r.data.replaced, BATCH_N)
    assert.equal(wordBatch.state.text.includes('违约责仁'), false, '批量路径应改完全部 N 处')

    console.error(`[批量] replace_batch 改 ${BATCH_N} 处：context.sync() ${wordBatch.state.syncs} 次、`
      + `修订开关写入 ${wordBatch.state.trackingModeWrites.length} 次、Word.run 1 次、LLM 轮次 1 轮`)
    // 不变式 1：过桥量常数级。基线是 7N 量级，批量必须低一个数量级。
    assert.ok(
      wordBatch.state.syncs * 5 <= sequentialSyncs,
      `批量过桥量应至少比逐处低 5 倍：batch=${wordBatch.state.syncs} sequential=${sequentialSyncs}`
    )
    // 修订开关：TrackAll 一次 + 恢复一次
    assert.equal(wordBatch.state.trackingModeWrites.length, 2,
      `修订开关整批只该开关一次，实际写了 ${wordBatch.state.trackingModeWrites.length} 次`)
    assert.equal(wordBatch.state.trackingModeWrites[0], 'TrackAll')
  } finally {
    wordBatch.restore()
  }
})

test('replace_batch：所有查找都在任何一次写入之前完成，定位失败逐条回报', async () => {
  const { executeOfficeCommand } = await import('./officeExecutor.js')
  const word = installWord(makeContract(5))
  try {
    const items = makeEdits(5)
    // 第 3 条锚点被改坏：模型凭印象拼错了一个字
    items[2].searchText = '第3期款项，逾期按日承担违约金责仁。'
    const r = await executeOfficeCommand('replace_batch', { items })
    assert.equal(r.ok, true, r.error)
    assert.equal(r.data.replaced, 4, '其余四条应正常落笔')
    assert.equal(r.data.failed.length, 1)
    assert.equal(r.data.failed[0].index, 3)
    assert.ok(r.data.failed[0].error, '失败条目必须带可自纠的说明')
    // 第 3 条原文保持不动
    assert.ok(word.state.text.includes('第3期款项，逾期按日承担违约责仁。'))

    // 不变式 2：第一次发生写入的那次 sync，必须晚于最后一次查找解析
    const firstWriteSync = word.state.writeSyncs.findIndex(Boolean)
    assert.ok(firstWriteSync > 0, '第一次 sync 只该发查找，不该带写入')
  } finally {
    word.restore()
  }
})

test('replace_batch：searchText 重复的批次整批拒绝，一个字都不写', async () => {
  const { executeOfficeCommand } = await import('./officeExecutor.js')
  const before = makeContract(3)
  const word = installWord(before)
  try {
    const items = makeEdits(3)
    items[2].searchText = items[0].searchText
    const r = await executeOfficeCommand('replace_batch', { items })
    assert.equal(r.ok, false)
    assert.match(r.error, /重复/)
    assert.equal(word.state.text, before, '整批拒绝时文档必须原样不动')
  } finally {
    word.restore()
  }
})

test('replace_batch：一条 searchText 是另一条的子串时整批拒绝（两处会重叠、改两遍）', async () => {
  const { executeOfficeCommand } = await import('./officeExecutor.js')
  const before = makeContract(3)
  const word = installWord(before)
  try {
    const r = await executeOfficeCommand('replace_batch', {
      items: [
        { searchText: '承担违约责仁。', replaceText: '承担违约责任。' },
        { searchText: '违约责仁', replaceText: '违约责任' }
      ]
    })
    assert.equal(r.ok, false)
    assert.match(r.error, /一部分|重叠/)
    assert.equal(word.state.text, before, '整批拒绝时文档必须原样不动')
  } finally {
    word.restore()
  }
})

test('replace_batch：空 items 与跨段 searchText 前置拒绝', async () => {
  const { executeOfficeCommand } = await import('./officeExecutor.js')
  const word = installWord(makeContract(2))
  try {
    assert.equal((await executeOfficeCommand('replace_batch', { items: [] })).ok, false)
    const r = await executeOfficeCommand('replace_batch', {
      items: [{ searchText: '第1条\r第2条', replaceText: 'x' }]
    })
    assert.equal(r.ok, false)
    assert.match(r.error, /跨段/)
  } finally {
    word.restore()
  }
})
