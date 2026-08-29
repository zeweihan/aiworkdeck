/**
 * 模型视觉能力（/api/ai/models 的 vision 字段）回归用例（dev-board#264）：
 *   node --test office-addin/taskpane/lib/visionCapability.test.js
 *
 * 三条容易做错、做错了又不会报错只会静默误导的判定：
 *   1. **必须消费 defaultModel**：selectedModel 为空串的语义是「跟随后端默认」，
 *      而默认模型今天恰恰是 vision=false 的那个。只对显式选中的模型判能力，
 *      绝大多数用户（从没选过模型）永远收不到提示。
 *   2. **必须是三态**：true / false / undefined。插件连的是用户自填的服务器地址，
 *      很可能是不返回 vision 字段的旧后端——把 undefined 当 false 会对所有模型
 *      误报「不支持读图」。未知时什么都不提示。
 *   3. **判图不能只看 fileType**：项目树里的 fileType 是后端原样透传的，桌面端那张
 *      扩展名映射表没有 bmp，从桌面端传上来的 .bmp 会是 'other'。
 * 另钉住「两个时机都要有提示」：先选模型后加图片、先加图片后换模型，两条都不许静默。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩：settings.js 的持久化走它 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

const {
  activateSession, stop, modelCatalog, selectedModel, chooseModel,
  attachedFiles, activeModelVision, defaultModelInfo, visionNotice, isImageAttachment
} = await import('./chatSession.js')
const { ZH, EN, currentLang } = await import('./i18n.js')

const DICT = currentLang === 'zh' ? ZH : EN

/** 今天线上的形状：14 条里 3 条 vision=false，默认那条正在其中 */
const CATALOG = {
  defaultModel: 'deepseek/deepseek-v4-flash',
  models: [
    { id: 'deepseek/deepseek-v4-flash', name: 'DeepSeek V4 Flash', vision: false },
    { id: 'z-ai/glm-5.2', name: 'GLM 5.2', vision: false },
    { id: 'anthropic/claude-opus-5', name: 'Claude Opus 5', vision: true }
  ]
}

/** 旧后端：同样的清单，但整个 vision 字段不存在 */
const LEGACY_CATALOG = {
  defaultModel: 'deepseek/deepseek-v4-flash',
  models: [
    { id: 'deepseek/deepseek-v4-flash', name: 'DeepSeek V4 Flash' },
    { id: 'anthropic/claude-opus-5', name: 'Claude Opus 5' }
  ]
}

/** 每个用例自带初态：模块级单例，不重置会互相串味 */
function reset(catalog = CATALOG, model = '') {
  modelCatalog.value = catalog
  attachedFiles.value = []
  // 经 chooseModel 落地，顺带把「刚选了看不了图的模型」标记位置成与 model 相符
  chooseModel(model)
}

// ==================== 能力判定：三态 + defaultModel ====================

test('默认模型（selectedModel 为空串）走 defaultModel 回查能力', () => {
  reset(CATALOG, '')
  assert.equal(selectedModel.value, '')
  assert.equal(activeModelVision.value, false)
})

test('显式选中的模型优先于 defaultModel', () => {
  reset(CATALOG, 'anthropic/claude-opus-5')
  assert.equal(activeModelVision.value, true)
  chooseModel('z-ai/glm-5.2')
  assert.equal(activeModelVision.value, false)
})

test('旧后端不返回 vision 字段：判为未知（undefined），不是 false', () => {
  reset(LEGACY_CATALOG, '')
  assert.equal(activeModelVision.value, undefined)
  chooseModel('anthropic/claude-opus-5')
  assert.equal(activeModelVision.value, undefined)
})

test('清单拉不到（modelCatalog=null）或模型不在清单里：同样是未知', () => {
  reset(null, '')
  assert.equal(activeModelVision.value, undefined)
  reset({ defaultModel: 'gone/model', models: CATALOG.models }, '')
  assert.equal(activeModelVision.value, undefined)
})

test('后端没给 defaultModel 且用户也没选：未知', () => {
  reset({ defaultModel: '', models: CATALOG.models }, '')
  assert.equal(activeModelVision.value, undefined)
})

test('vision 是非布尔值（字段形状变了）时按未知处理，不当真值读', () => {
  reset({ defaultModel: 'x', models: [{ id: 'x', name: 'X', vision: 'yes' }] }, '')
  assert.equal(activeModelVision.value, undefined)
})

test('defaultModelInfo 回查出默认模型的名字与能力（给「默认模型」那一行标角标）', () => {
  reset(CATALOG, '')
  assert.equal(defaultModelInfo.value.name, 'DeepSeek V4 Flash')
  assert.equal(defaultModelInfo.value.vision, false)
  reset(LEGACY_CATALOG, '')
  assert.equal(defaultModelInfo.value.vision, undefined)
  reset(null, '')
  assert.equal(defaultModelInfo.value, null)
})

// ==================== 判图：fileType 与扩展名取并集 ====================

test('判图：后端 fileType=image 直接算', () => {
  assert.equal(isImageAttachment({ name: 'a.png', fileType: 'image' }), true)
  assert.equal(isImageAttachment({ name: 'a.png', fileType: 'IMAGE' }), true)
})

test('判图：桌面端传上来的 .bmp 是 fileType=other，靠扩展名兜住', () => {
  assert.equal(isImageAttachment({ name: '扫描件.bmp', fileType: 'other' }), true)
})

test('判图：只有名字的条目（上传中间态）也能判', () => {
  assert.equal(isImageAttachment({ name: 'shot.jpeg' }), true)
  assert.equal(isImageAttachment({ name: 'report.docx' }), false)
})

test('判图：非图片与空条目不误判', () => {
  assert.equal(isImageAttachment({ name: 'a.pdf', fileType: 'pdf' }), false)
  assert.equal(isImageAttachment(null), false)
  assert.equal(isImageAttachment({}), false)
})

// ==================== 提示：未知不提示 + 两个时机都覆盖 ====================

test('未知态（旧后端）什么都不提示——把 undefined 当 false 会对所有模型误报', () => {
  reset(LEGACY_CATALOG, '')
  attachedFiles.value = [{ id: 1, name: 'a.png', fileType: 'image' }]
  assert.equal(visionNotice.value, '')
})

test('支持视觉的模型不提示', () => {
  reset(CATALOG, 'anthropic/claude-opus-5')
  attachedFiles.value = [{ id: 1, name: 'a.png', fileType: 'image' }]
  assert.equal(visionNotice.value, '')
})

test('时机一：选了看不了图的模型（还没加图片）就提示', () => {
  reset(CATALOG, 'z-ai/glm-5.2')
  assert.equal(visionNotice.value, DICT.visionModelPicked)
})

test('时机一续：先选模型、后加图片 → 提示换成附件版，不静默', () => {
  reset(CATALOG, 'z-ai/glm-5.2')
  attachedFiles.value = [{ id: 1, name: 'a.png', fileType: 'image' }]
  assert.equal(visionNotice.value, DICT.visionImagesDowngraded)
})

test('时机二：先加图片、后换成看不了图的模型 → 照样提示', () => {
  reset(CATALOG, 'anthropic/claude-opus-5')
  attachedFiles.value = [{ id: 1, name: 'a.png', fileType: 'image' }]
  assert.equal(visionNotice.value, '')
  chooseModel('z-ai/glm-5.2')
  assert.equal(visionNotice.value, DICT.visionImagesDowngraded)
})

test('时机二续：附件跨轮不清空，用户从没选过模型（走后端默认）也要提示', () => {
  reset(CATALOG, '')
  attachedFiles.value = [{ id: 1, name: '扫描件.bmp', fileType: 'other' }]
  assert.equal(visionNotice.value, DICT.visionImagesDowngraded)
})

test('只附了非图片文件时不提示（降级只关图片）', () => {
  reset(CATALOG, 'z-ai/glm-5.2')
  attachedFiles.value = [{ id: 1, name: 'a.pdf', fileType: 'pdf' }]
  // 模型仍是刚选的那个，所以是选模型那一条，不是附件那一条
  assert.equal(visionNotice.value, DICT.visionModelPicked)
})

test('选回支持视觉的模型后提示自行落下', () => {
  reset(CATALOG, 'z-ai/glm-5.2')
  assert.equal(visionNotice.value, DICT.visionModelPicked)
  chooseModel('anthropic/claude-opus-5')
  assert.equal(visionNotice.value, '')
})

test('未知态下选模型不置提示标记：清单补齐后也不该冒出旧提示', () => {
  reset(LEGACY_CATALOG, 'anthropic/claude-opus-5')
  assert.equal(visionNotice.value, '')
  // 后端升级后清单带上 vision：没有图片附件时不该凭空出现「刚选了」的提示
  modelCatalog.value = CATALOG
  assert.equal(visionNotice.value, '')
})

// ==================== 端到端：vision 从 /api/ai/models 一路透传到判定 ====================

test('fetchModels 原样透传 vision，activateSession 后即可判定默认模型的能力', async () => {
  const original = globalThis.fetch
  globalThis.fetch = async (url) => {
    const u = String(url)
    if (u.includes('/api/ai/models')) return { ok: true, status: 200, json: async () => CATALOG }
    if (u.includes('/api/ai/history')) return { ok: true, status: 200, json: async () => [] }
    if (u.endsWith('/api/agent/conversations')) {
      return { ok: true, status: 200, json: async () => ({ conversationId: 'conv-vis-1' }) }
    }
    if (u.includes('/api/agent/connect/')) {
      return { ok: true, status: 200, body: { getReader: () => ({ read: () => new Promise(() => {}) }) } }
    }
    if (u.includes('/api/skills/list')) return { ok: true, status: 200, json: async () => [] }
    if (u.includes('/api/agent/cancel/')) return { ok: true, status: 200, json: async () => ({}) }
    throw new Error(`未预期的请求: ${u}`)
  }
  try {
    modelCatalog.value = null
    chooseModel('')
    attachedFiles.value = []
    await activateSession({
      settings: { serverUrl: 'https://cloud.example', token: 'awdt_vis' }, projectId: '31'
    })
    // refreshCatalogs 不被 await（清单是旁路），等它落地
    for (let i = 0; i < 50 && !modelCatalog.value; i++) await new Promise((r) => setTimeout(r, 5))
    assert.ok(modelCatalog.value, '模型清单未拉到')
    assert.equal(modelCatalog.value.defaultModel, 'deepseek/deepseek-v4-flash')
    assert.equal(modelCatalog.value.models[0].vision, false)
    assert.equal(activeModelVision.value, false)
    assert.equal(defaultModelInfo.value.name, 'DeepSeek V4 Flash')
  } finally {
    await stop()
    globalThis.fetch = original
  }
})
