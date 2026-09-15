# 可选组件两处提示（dev-board#530）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在两个时刻看到「要不要下载这个组件」——首次安装登录后的「可选组件」面板，以及首次触发对应功能时——并且每次提示都写明下载什么、多大、解锁什么、不装则什么不可用。

**Architecture:** 一个 composable（`useOptionalComponents.js`）收口「读状态 + 顺序下载（pack → 模型 → ensure）+ 总进度」，一个卡片组件（`OptionalComponentCard.vue`）被首次登录面板与设置页组件管理复用，文案集中在新 locale 命名空间 `components`（zh-CN + en-US）。AI 对话侧在 `ChatInterface` 的 `onClientAction` 接缝里就地拦下 `component_required`，走同一个 composable 装完后 `host.services.ensure(service)` 并自动重发原消息。「已提示过」的标记存 electron 侧 `~/.aiworkdeck/prefs.json`（不是 localStorage：重装才该重置）。

**Tech Stack:** uni-app + Vue 3（Options API，`$t` 走 vue-i18n legacy）、Electron 30 主进程 + preload contextBridge、`node --test`（`frontend/tests/**.test.mjs` 与 `desktop/tests/*.test.js`）。

**Spec:** `docs/superpowers/specs/2026-09-09-installer-slimming-design.md` §4 / §5；接口由同批的 `2026-09-09-python-services-native-pack.md`（#529）产出。

## Global Constraints

- 四个组件 id 固定：`pptx-runtime` / `mineru-runtime` / `kokoro-runtime` / `asr-runtime`；对应服务名 `pptx-service` / `mineru-service` / `kokoro-service` / `asr-service`；模型 id `mineru-models` / `kokoro-models` / `asr-models`（pptx 无模型）。
- 「已提示过」的持久化位置：**electron 侧 `~/.aiworkdeck/prefs.json`**，键 `optionalComponentsPromptedVersion`（值 = 提示时的应用大版本号）。**不许用 localStorage / uni.setStorageSync**——那个随浏览器数据一起被清，也不区分重装。
- 体积一律从接口读，**不写死**；接口给 0（未知）时才回落到 locale 里写的近似值（`≈165MB` 等）。
- 文案两语言齐全：`frontend/src/locales/zh-CN/components.js` 与 `frontend/src/locales/en-US/components.js` 键集合必须一致（`npm run check:locales` 会红）。
- 下载顺序恒定：**先 pack，后模型，最后 `ensure(service)`**。模型下载器本身跑在 pack 的 venv 里，反过来必失败。
- 提示里必须同时出现四件事：下载什么、多大、解锁哪些功能、不下载则哪些功能不可用（用户硬性要求）。
- 「稍后再说」是一等公民：关掉之后不再打扰，入口保留在 设置 → 组件管理。
- 与 #529 共享的接口契约（逐字相同）：
  - `GET /api/packs/optional-components` 响应见 Task 3。
  - SSE `client_action` 的 `component_required` payload：
    `{"action":"component_required","packId":string,"service":string,"modelId":string|null,"sizeMb":number,"features":string[],"trigger":string}`
  - `LocalAsrClient.Status` 四态：`RUNTIME_MISSING` / `SERVICE_DOWN` / `MODEL_MISSING` / `READY`。
  - `host.services.ensure(name)` 的服务名同上四个。

## 对 #529 的依赖

| 本计划 Task | 依赖 #529 的 | 能否先用 mock 推进 |
|---|---|---|
| T1 prefs | 无 | — |
| T2 locales | 无 | — |
| T3 composable | #529 Task 8（`/api/packs/optional-components`） | **能**：`useOptionalComponents` 的所有依赖都从参数注入，单测用桩函数；真接口没上线前前端跑在桩上 |
| T4 卡片 | 无（消费 T3 的视图模型） | — |
| T5 首次登录面板 | #529 Task 8 | **能**（同 T3） |
| T6 组件管理复用 | #529 Task 8 | **能** |
| T7 AI 对话弹窗 | #529 Task 10（`component_required`） | **能**：测试直接构造 payload 调 handler |
| T8 语音面板 | #529 Task 6（`enabled` 判 pack）、Task 8 | **能** |
| T9 录音四态 | **不能**：需要 #529 Task 7 的 `RUNTIME_MISSING` 真的从 `/api/asr/local/probe` 回来 | 否 |
| T10 验收 | #529 全部 | 否 |

---

### Task 1: electron 侧 prefs 存储（~/.aiworkdeck/prefs.json）

**Files:**
- Create: `desktop/main/services/prefs.js`
- Modify: `desktop/main/main.js`（IPC 面，紧跟 `checkba:service-ensure` 之后）
- Modify: `desktop/preload/preload.js:140-142`（`services` 之后加 `prefs`）
- Create: `desktop/tests/prefs.test.js`
- Modify: `desktop/package.json:12`（test script 加新文件）

**Interfaces:**
- Consumes: 无。
- Produces:
  - `createPrefs({dataDir}) => { get(key, fallback), set(key, value), all() }`（同步、幂等、坏 JSON 不抛）
  - IPC `checkba:prefs-get` → `{ok: true, value}`；`checkba:prefs-set` → `{ok: true}`
  - 渲染层：`host.prefs.get(key)` / `host.prefs.set(key, value)`（Promise）。Web 态 `host.prefs` 为 `undefined`，调用点用 `if (host.prefs)` 守卫。

- [ ] **Step 1: 写失败测试**

```js
// desktop/tests/prefs.test.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 本机偏好（~/.aiworkdeck/prefs.json）。「可选组件面板提示过没有」存在这里而不是
// localStorage：那个随浏览器数据被清、也不区分重装，用户会被反复打扰。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { createPrefs } = require('../main/services/prefs')

test('写入后立即可读，并且真的落到 ~/.aiworkdeck/prefs.json', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  const prefs = createPrefs({ dataDir })

  assert.strictEqual(prefs.get('optionalComponentsPromptedVersion', null), null)
  prefs.set('optionalComponentsPromptedVersion', '0.38.0')
  assert.strictEqual(prefs.get('optionalComponentsPromptedVersion', null), '0.38.0')

  const onDisk = JSON.parse(fs.readFileSync(path.join(dataDir, 'prefs.json'), 'utf8'))
  assert.strictEqual(onDisk.optionalComponentsPromptedVersion, '0.38.0')
})

test('新进程读得到上一个进程写的值（面板的「不再打扰」跨重启有效）', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  createPrefs({ dataDir }).set('k', { a: 1 })
  assert.deepStrictEqual(createPrefs({ dataDir }).get('k', null), { a: 1 })
})

test('文件是坏 JSON 时按空表处理，不抛——它绝不能拦住应用启动', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  fs.writeFileSync(path.join(dataDir, 'prefs.json'), '{ 半个文件')
  const prefs = createPrefs({ dataDir })
  assert.strictEqual(prefs.get('k', 'fallback'), 'fallback')
  prefs.set('k', 1)
  assert.strictEqual(prefs.get('k', null), 1, '坏文件应被整体重写，而不是永远写不进去')
})

test('目录不存在时自建；set 用临时文件+rename，中途断电不会留半个文件', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const dataDir = path.join(root, 'nested', '.aiworkdeck')
  createPrefs({ dataDir }).set('k', 'v')
  assert.ok(fs.existsSync(path.join(dataDir, 'prefs.json')))
  assert.deepStrictEqual(fs.readdirSync(dataDir), ['prefs.json'], '不留 .tmp 残骸')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd desktop && node --test tests/prefs.test.js`
Expected: FAIL —「Cannot find module '../main/services/prefs'」。

- [ ] **Step 3: 最小实现**

```js
// desktop/main/services/prefs.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const fs = require('fs')
const path = require('path')

/**
 * 本机偏好的极简 KV（~/.aiworkdeck/prefs.json）。
 *
 * 为什么不是 localStorage：这里存的是「这台机器上这次安装是否已经提示过可选组件」，
 * 语义上属于安装，不属于浏览器会话——localStorage 被清一次用户就会被重新打扰一遍，
 * 而 ~/.aiworkdeck 在 DMG 覆盖安装与大版本升级时不被触碰（规范 §4.1），重装才重置。
 *
 * 为什么不引 electron-store：只有一个键，不值得多一个依赖与一套 schema 迁移。
 */
function createPrefs(ctx) {
  const file = path.join(ctx.dataDir, 'prefs.json')

  function readAll() {
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'))
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch (e) {
      return {} // 不存在 / 坏 JSON：按空表，绝不抛（它挂在启动链上）
    }
  }

  return {
    all: readAll,
    get(key, fallback) {
      const all = readAll()
      return Object.prototype.hasOwnProperty.call(all, key) ? all[key] : fallback
    },
    set(key, value) {
      const all = readAll()
      all[key] = value
      fs.mkdirSync(ctx.dataDir, { recursive: true })
      // tmp + rename：断电只会丢这次写入，不会留半个文件让下次读成坏 JSON（overlay.js 同款）
      const tmp = file + '.tmp'
      fs.writeFileSync(tmp, JSON.stringify(all, null, 2))
      fs.renameSync(tmp, file)
      return value
    },
  }
}

module.exports = { createPrefs }
```

`desktop/main/main.js` 在 `checkba:service-ensure` 之后加：

```js
// 本机偏好（~/.aiworkdeck/prefs.json）。目前只有「可选组件面板提示过没有」一个键，
// 键名与取值由渲染层决定，主进程只负责落盘。
let prefs = null
function getPrefs() {
  if (!prefs) {
    prefs = require('./services/prefs').createPrefs({ dataDir: path.join(app.getPath('home'), '.aiworkdeck') })
  }
  return prefs
}
ipcMain.handle('checkba:prefs-get', async (_evt, payload) => {
  try {
    return { ok: true, value: getPrefs().get(payload && payload.key, null) }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})
ipcMain.handle('checkba:prefs-set', async (_evt, payload) => {
  try {
    getPrefs().set(payload && payload.key, payload && payload.value)
    return { ok: true }
  } catch (e) {
    return { ok: false, message: String(e && e.message ? e.message : e) }
  }
})
```

`desktop/preload/preload.js` 在 `services` 之后加：

```js
  // 本机偏好（落 ~/.aiworkdeck/prefs.json）。只做 KV 转发，键名与语义在渲染层。
  prefs: {
    get: (key) => ipcRenderer.invoke('checkba:prefs-get', { key }),
    set: (key, value) => ipcRenderer.invoke('checkba:prefs-set', { key, value })
  },
```

`desktop/package.json` 的 test 脚本加 `tests/prefs.test.js`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd desktop && node --test tests/prefs.test.js`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add desktop/main/services/prefs.js desktop/main/main.js desktop/preload/preload.js desktop/tests/prefs.test.js desktop/package.json
git commit -m "feat(desktop): 新增本机偏好存储 ~/.aiworkdeck/prefs.json 与 host.prefs"
```

---

### Task 2: components 文案命名空间（zh-CN + en-US）

**Files:**
- Create: `frontend/src/locales/zh-CN/components.js`
- Create: `frontend/src/locales/en-US/components.js`
- Create: `frontend/tests/optional-components/copy.test.mjs`
- Modify: `frontend/package.json`（`"test:optional-components": "node --test tests/optional-components/*.test.mjs"`）

**Interfaces:**
- Consumes: 无。
- Produces（后续所有任务只许用这些键）：
  - `components.panelTitle` / `panelSubtitle` / `installSelected` / `later` / `laterHint` / `manageTitle`
  - `components.promptWithModel` / `promptNoModel` / `promptUsage`
  - `components.<pptxRuntime|mineruRuntime|kokoroRuntime|asrRuntime>.{name, unlocks, impact}`
  - `components.features.<pptxGenerate|pptxFormat|pdfToWordLayout|scannedOcrEntry|scannedPdfToWord|ocrParse|ttsPanel|localTranscription>`
  - `components.stateNotInstalled|stateReady|stateDownloadingRuntime|stateDownloadingModel|stateStartingService|stateFailed`
  - `components.progressTotal` / `retry` / `sizeUnknown`
  - `components.chatTitle` / `chatConfirm` / `chatCancel` / `chatResending` / `chatInstalling`
  - `components.asrRuntimeMissingAction`

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/copy.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 提示文案的硬性内容要求（设计 §0 用户硬性要求 / §4.3）：每一条提示都必须同时说清
// 下载什么、多大、解锁哪些功能、不下载则哪些功能不可用。
import test from 'node:test'
import assert from 'node:assert/strict'
import zh from '../../src/locales/zh-CN/components.js'
import en from '../../src/locales/en-US/components.js'

const PACKS = ['pptxRuntime', 'mineruRuntime', 'kokoroRuntime', 'asrRuntime']

test('四个组件各有 name / unlocks / impact，两语言齐全', () => {
  for (const p of PACKS) {
    for (const k of ['name', 'unlocks', 'impact']) {
      assert.ok(zh[p] && zh[p][k], `zh-CN 缺 components.${p}.${k}`)
      assert.ok(en[p] && en[p][k], `en-US 缺 components.${p}.${k}`)
    }
  }
})

test('提示模板带全部占位符：组件名 / 运行时体积 / 含模型体积 / 落盘位置 / 卸载入口', () => {
  for (const [lang, m] of [['zh-CN', zh], ['en-US', en]]) {
    for (const ph of ['{name}', '{runtime}', '{total}']) {
      assert.ok(m.promptWithModel.includes(ph), `${lang} promptWithModel 缺 ${ph}`)
    }
    assert.ok(m.promptNoModel.includes('{name}') && m.promptNoModel.includes('{runtime}'))
    assert.ok(!m.promptNoModel.includes('{total}'), '无模型的组件不该出现「含模型约」')
    assert.ok(m.promptWithModel.includes('~/.aiworkdeck'), `${lang} 没写落盘位置`)
    assert.ok(m.promptUsage.includes('{unlocks}') && m.promptUsage.includes('{impact}'),
      `${lang} promptUsage 必须同时说「用于什么」与「不装则什么不可用」`)
  }
})

test('八个功能键齐备（后端 featureKeys 的对面）', () => {
  const keys = ['pptxGenerate', 'pptxFormat', 'pdfToWordLayout', 'scannedOcrEntry',
    'scannedPdfToWord', 'ocrParse', 'ttsPanel', 'localTranscription']
  for (const k of keys) {
    assert.ok(zh.features[k], `zh-CN 缺 components.features.${k}`)
    assert.ok(en.features[k], `en-US 缺 components.features.${k}`)
  }
})

test('英文文案里不许残留中文（英文界面下会当场露馅）', () => {
  const walk = (o, p = '') => Object.entries(o).forEach(([k, v]) =>
    typeof v === 'object' ? walk(v, `${p}${k}.`) : assert.ok(!/[\u4e00-\u9fa5]/.test(v), `en-US ${p}${k} 含中文：${v}`))
  walk(en)
})

test('两语言键集合完全一致（check:locales 的本地前哨）', () => {
  const flat = (o, p = '') => Object.entries(o).flatMap(([k, v]) =>
    typeof v === 'object' ? flat(v, `${p}${k}.`) : [`${p}${k}`])
  assert.deepEqual(flat(zh).sort(), flat(en).sort())
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/copy.test.mjs`
Expected: FAIL — 找不到 `src/locales/zh-CN/components.js`。

- [ ] **Step 3: 最小实现**

```js
// frontend/src/locales/zh-CN/components.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件（四个 Python 运行时 + 各自模型）的文案。设计 §4.3 的模板落在这里：
// 每一条提示都要同时说清「下载什么 / 多大 / 解锁什么 / 不装则什么不可用」。
// 体积占位符由界面从接口填（manifest 实测值），不写死。
export default {
  panelTitle: '可选组件',
  panelSubtitle: '这些组件不随安装包分发，用到哪个下哪个。全部在本机运行，内容不出这台电脑。',
  installSelected: '立即下载所选',
  later: '稍后再说',
  laterHint: '随时可以在「设置 → 组件管理」里下载。',
  manageTitle: '组件管理',

  promptWithModel: '需要下载{name}（约 {runtime} MB；含模型约 {total} MB）。落盘于 ~/.aiworkdeck，可在「设置→组件管理」卸载释放。',
  promptNoModel: '需要下载{name}（约 {runtime} MB）。落盘于 ~/.aiworkdeck，可在「设置→组件管理」卸载释放。',
  promptUsage: '它用于：{unlocks}。不下载则：{impact}；其余功能不受影响。',

  sizeUnknown: '体积获取中…',
  stateNotInstalled: '未安装',
  stateReady: '已就绪',
  stateDownloadingRuntime: '正在下载运行时 {percent}%',
  stateDownloadingModel: '正在下载模型 {percent}%',
  stateStartingService: '正在启动组件…',
  stateFailed: '下载失败：{msg}',
  progressTotal: '总进度 {done}/{count}（{percent}%）',
  retry: '重试',

  chatTitle: '需要下载组件',
  chatConfirm: '下载并继续',
  chatCancel: '暂不下载',
  chatInstalling: '正在准备组件…',
  chatResending: '组件已就绪，正在继续刚才的请求…',

  asrRuntimeMissingAction: '下载本机语音识别组件',

  pptxRuntime: {
    name: 'PPT 与 PDF 组件',
    unlocks: 'AI 生成 PPT、PPTX 读/改格式、PDF 转 Word（版式级）、扫描件 OCR 入口',
    impact: '上述功能不可用；PDF 仍可预览与结构级转换',
  },
  mineruRuntime: {
    name: '文档解析引擎',
    unlocks: '扫描件 PDF 转 Word、OCR 版面解析',
    impact: '扫描件转换退回云端 MinerU 或不可用',
  },
  kokoroRuntime: {
    name: '语音合成',
    unlocks: '语音面板的「语音合成」',
    impact: '语音合成不可用',
  },
  asrRuntime: {
    name: '本机语音识别',
    unlocks: '录音转写的「录音不出本机」档',
    impact: '只能用云端听悟两档',
  },

  features: {
    pptxGenerate: 'AI 生成 PPT',
    pptxFormat: 'PPTX 读/改格式',
    pdfToWordLayout: 'PDF 转 Word（版式级）',
    scannedOcrEntry: '扫描件 OCR 入口',
    scannedPdfToWord: '扫描件 PDF 转 Word',
    ocrParse: 'OCR 版面解析',
    ttsPanel: '语音合成面板',
    localTranscription: '录音不出本机的转写',
  },
}
```

```js
// frontend/src/locales/en-US/components.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
export default {
  panelTitle: 'Optional components',
  panelSubtitle: 'These components are not bundled with the installer. Download only what you use — everything runs on this computer and nothing leaves it.',
  installSelected: 'Download selected',
  later: 'Not now',
  laterHint: 'You can download them any time from Settings → Components.',
  manageTitle: 'Components',

  promptWithModel: '{name} needs to be downloaded (about {runtime} MB; about {total} MB including the model). It is stored under ~/.aiworkdeck and can be removed from Settings → Components.',
  promptNoModel: '{name} needs to be downloaded (about {runtime} MB). It is stored under ~/.aiworkdeck and can be removed from Settings → Components.',
  promptUsage: 'It powers: {unlocks}. Without it: {impact}. Everything else keeps working.',

  sizeUnknown: 'Checking size…',
  stateNotInstalled: 'Not installed',
  stateReady: 'Ready',
  stateDownloadingRuntime: 'Downloading runtime {percent}%',
  stateDownloadingModel: 'Downloading model {percent}%',
  stateStartingService: 'Starting the component…',
  stateFailed: 'Download failed: {msg}',
  progressTotal: 'Overall {done}/{count} ({percent}%)',
  retry: 'Retry',

  chatTitle: 'A component is required',
  chatConfirm: 'Download and continue',
  chatCancel: 'Not now',
  chatInstalling: 'Preparing the component…',
  chatResending: 'The component is ready. Continuing your request…',

  asrRuntimeMissingAction: 'Download on-device speech recognition',

  pptxRuntime: {
    name: 'Slides and PDF component',
    unlocks: 'AI slide generation, PPTX formatting, layout-preserving PDF to Word, scanned-document OCR entry',
    impact: 'those features are unavailable; PDFs can still be previewed and converted structurally',
  },
  mineruRuntime: {
    name: 'Document parsing engine',
    unlocks: 'scanned PDF to Word and OCR layout parsing',
    impact: 'scanned-document conversion falls back to cloud MinerU or is unavailable',
  },
  kokoroRuntime: {
    name: 'Speech synthesis',
    unlocks: 'the Speech synthesis tab of the Voice panel',
    impact: 'speech synthesis is unavailable',
  },
  asrRuntime: {
    name: 'On-device speech recognition',
    unlocks: 'the "recordings never leave this computer" transcription tier',
    impact: 'only the two cloud transcription tiers remain',
  },

  features: {
    pptxGenerate: 'AI slide generation',
    pptxFormat: 'PPTX read/edit formatting',
    pdfToWordLayout: 'PDF to Word (layout-preserving)',
    scannedOcrEntry: 'scanned-document OCR entry',
    scannedPdfToWord: 'scanned PDF to Word',
    ocrParse: 'OCR layout parsing',
    ttsPanel: 'speech synthesis panel',
    localTranscription: 'on-device transcription',
  },
}
```

`frontend/package.json` 的 scripts 加：`"test:optional-components": "node --test tests/optional-components/*.test.mjs"`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && npm run test:optional-components && npm run check:locales`
Expected: PASS，`check:locales` 无输出（零缺键）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/locales/zh-CN/components.js frontend/src/locales/en-US/components.js \
        frontend/tests/optional-components/copy.test.mjs frontend/package.json
git commit -m "feat(i18n): 新增可选组件文案命名空间（zh-CN + en-US）"
```

---

### Task 3: useOptionalComponents —— 状态读取与顺序下载编排

**Files:**
- Create: `frontend/src/composables/useOptionalComponents.js`
- Modify: `frontend/src/services/api.js`（在 `packStatus` 附近新增 `optionalComponents()`）
- Create: `frontend/tests/optional-components/orchestrator.test.mjs`

**Interfaces:**
- Consumes（#529 Task 8）：
  `GET /api/packs/optional-components` → `{code:0, components:[{packId, service, state, installed, installedVersion, latestVersion, downloadBytes, unpackedBytes, modelId, modelInstalled, modelBytes, featureKeys}]}`；
  `POST /api/packs/{id}/install`（`packInstall`）、`GET /api/packs/{id}/status`（`packStatus`）、`GET /api/packs/{id}/info`（`packInfo`）；
  `host.model.download(modelId)` / `host.model.onProgress(cb)` / `host.services.ensure(service)`。
- Produces:
  - `api.js`: `export function optionalComponents()` → `{code, components}`。
  - `createOptionalComponentsController(deps)`，`deps = { optionalComponents, packInstall, packStatus, packInfo, modelDownload, onModelProgress, ensureService, sleep }`（全部注入，便于单测）。
  - 控制器暴露：
    - `state = { items: [], loading: false, running: false, doneCount: 0, totalCount: 0, error: '' }`（普通对象，Vue 组件包 `reactive` 用）
    - `async load()` — 拉列表，写入 `state.items`；每项补 `{ localeKey, percent, phase }`，`phase ∈ 'idle'|'runtime'|'model'|'starting'|'ready'|'failed'`
    - `async fillSizes(item)` — `downloadBytes === 0` 时打 `packInfo` 补，写回 `item.downloadBytes/unpackedBytes`
    - `async installOne(item)` — **pack → 模型 → ensure** 顺序执行，逐段写 `item.phase/percent`；失败置 `failed` 并写 `item.error`，**不抛**
    - `async installAll(items)` — 逐个（顺序，不并发）跑 `installOne`，维护 `state.doneCount/totalCount`
    - `overallPercent()` — 总进度百分比（每个组件等权，组件内 pack 段占 40%、模型段占 55%、启动段占 5%）
    - `PACK_LOCALE_KEY` 映射：`{'pptx-runtime':'pptxRuntime', 'mineru-runtime':'mineruRuntime', 'kokoro-runtime':'kokoroRuntime', 'asr-runtime':'asrRuntime'}`

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/orchestrator.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 下载编排（设计 §3.1「底层两条通道顺序执行」/ §4.1「逐个顺序下载，带总进度」）。
// 顺序是硬约束：模型下载器本身跑在 pack 的 venv 里（model-manager.js 的 PYTHONPATH
// 指向 pack 的 lib/），先下模型必然 ModuleNotFoundError。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createOptionalComponentsController, PACK_LOCALE_KEY } from '../../src/composables/useOptionalComponents.js'

function deps(overrides = {}) {
  const calls = []
  const base = {
    calls,
    optionalComponents: async () => ({ code: 0, components: [
      { packId: 'pptx-runtime', service: 'pptx-service', state: 'not_installed', installed: false,
        downloadBytes: 173015040, unpackedBytes: 0, modelId: null, modelInstalled: false, modelBytes: 0,
        featureKeys: ['pptxGenerate'] },
      { packId: 'kokoro-runtime', service: 'kokoro-service', state: 'not_installed', installed: false,
        downloadBytes: 0, unpackedBytes: 0, modelId: 'kokoro-models', modelInstalled: false,
        modelBytes: 314572800, featureKeys: ['ttsPanel'] },
    ] }),
    packInstall: async (id) => { calls.push('install:' + id) },
    packStatus: async (id) => { calls.push('status:' + id); return { status: { state: 'ready', bytesDownloaded: 1, bytesTotal: 1 } } },
    packInfo: async (id) => { calls.push('info:' + id); return { latestVersion: '1.0.0', totalSize: 209715200, unpackedSize: 800000000 } },
    modelDownload: async (id) => { calls.push('model:' + id) },
    onModelProgress: () => () => {},
    ensureService: async (name) => { calls.push('ensure:' + name); return { ok: true } },
    sleep: async () => {},
  }
  return { ...base, ...overrides }
}

test('load 把四态字段与 locale 键都补齐', async () => {
  const c = createOptionalComponentsController(deps())
  await c.load()
  assert.equal(c.state.items.length, 2)
  assert.equal(c.state.items[0].localeKey, 'pptxRuntime')
  assert.equal(c.state.items[1].localeKey, 'kokoroRuntime')
  assert.equal(c.state.items[0].phase, 'idle')
  assert.deepEqual(PACK_LOCALE_KEY['asr-runtime'], 'asrRuntime')
})

test('downloadBytes 为 0 时才去打 /info 补体积（那条会发网络请求）', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.fillSizes(c.state.items[0])
  await c.fillSizes(c.state.items[1])
  assert.ok(!d.calls.includes('info:pptx-runtime'), '已知体积不该再打 /info')
  assert.ok(d.calls.includes('info:kokoro-runtime'))
  assert.equal(c.state.items[1].downloadBytes, 209715200)
  assert.equal(c.state.items[1].unpackedBytes, 800000000)
})

test('installOne 的顺序恒为 pack → 模型 → ensure', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[1])
  const seq = d.calls.filter((x) => /^(install|model|ensure):/.test(x))
  assert.deepEqual(seq, ['install:kokoro-runtime', 'model:kokoro-models', 'ensure:kokoro-service'])
  assert.equal(c.state.items[1].phase, 'ready')
})

test('没有模型的组件跳过模型段，直接 pack → ensure', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[0])
  assert.deepEqual(d.calls.filter((x) => /^(install|model|ensure):/.test(x)),
    ['install:pptx-runtime', 'ensure:pptx-service'])
})

test('pack 装失败就不往下走：绝不在没有运行时的情况下去下模型', async () => {
  const d = deps({ packStatus: async () => ({ status: { state: 'failed', error: '镜像不可达' } }) })
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[1])
  assert.equal(c.state.items[1].phase, 'failed')
  assert.match(c.state.items[1].error, /镜像不可达/)
  assert.ok(!d.calls.includes('model:kokoro-models'))
})

test('installAll 顺序执行、维护总进度，单个失败不中断后面的', async () => {
  let n = 0
  const d = deps({
    packStatus: async (id) => {
      n++
      return id === 'pptx-runtime'
        ? { status: { state: 'failed', error: 'boom' } }
        : { status: { state: 'ready', bytesDownloaded: 1, bytesTotal: 1 } }
    },
  })
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installAll(c.state.items)
  assert.equal(c.state.totalCount, 2)
  assert.equal(c.state.doneCount, 2, '失败也算「处理完了」，否则总进度永远停在那里')
  assert.equal(c.state.items[0].phase, 'failed')
  assert.equal(c.state.items[1].phase, 'ready')
  assert.equal(c.state.running, false)
  assert.ok(n >= 2)
})

test('总进度按段加权，中途读得出一个 0..100 的数', async () => {
  const c = createOptionalComponentsController(deps())
  await c.load()
  assert.equal(c.overallPercent(), 0)
  c.state.items[0].phase = 'ready'
  c.state.items[1].phase = 'model'
  c.state.items[1].percent = 50
  const p = c.overallPercent()
  assert.ok(p > 50 && p < 100, '一个装完 + 一个下到一半应当落在 50~100 之间，实际 ' + p)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/orchestrator.test.mjs`
Expected: FAIL — 找不到 `src/composables/useOptionalComponents.js`。

- [ ] **Step 3: 最小实现**

`frontend/src/services/api.js`（`packStatus` 之前）：

```js
// 四个可选组件的一次性快照（后端 PackController.optionalComponents）。
// 这条端点**不发网络请求**（体积取内存/落盘快照，0 = 未知），首次登录面板可以放心在
// 登录后立刻调它；要精确体积再按需打 packInfo。
export function optionalComponents() {
  return request('/api/packs/optional-components', 'GET')
}
```
（`request` 的签名与相邻的 `packStatus` 保持一致，照抄那一条的写法。）

```js
// frontend/src/composables/useOptionalComponents.js
// SPDX-FileCopyr```js
// frontend/src/composables/useOptionalComponents.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件的下载编排（设计 §3.1 / §4.1）。
//
// 一个组件在 UI 上是「一次下载」，底层是两条通道顺序执行：
//   1. native pack（后端 /api/packs/{id}/install + 轮询 status）——Python 运行时
//   2. model-manager（Electron 主进程）——模型权重
//   3. host.services.ensure(service) 把服务拉起来
// **顺序不能换**：模型下载器本身跑在 pack 的 venv 里（model-manager.js 的 PYTHONPATH
// 指向 pack 的 lib/），先下模型必然 ModuleNotFoundError。
//
// 依赖全部从参数注入：面板、组件管理页、AI 对话弹窗共用同一份编排，
// 单测则用桩函数跑完整条路径（不需要真后端、不需要 Electron）。

export const PACK_LOCALE_KEY = {
  'pptx-runtime': 'pptxRuntime',
  'mineru-runtime': 'mineruRuntime',
  'kokoro-runtime': 'kokoroRuntime',
  'asr-runtime': 'asrRuntime',
}

// 一个组件内部的三段权重。模型段最重（3GB 对 250MB），启动段只留一点让进度条别在
// 99% 上停死。三个数加起来必须是 100。
const WEIGHT = { runtime: 40, model: 55, starting: 5 }

const POLL_MS = 1000

export function createOptionalComponentsController(deps) {
  const state = {
    items: [],
    loading: false,
    running: false,
    doneCount: 0,
    totalCount: 0,
    error: '',
  }

  const sleep = deps.sleep || ((ms) => new Promise((r) => setTimeout(r, ms)))

  async function load() {
    state.loading = true
    state.error = ''
    try {
      const res = await deps.optionalComponents()
      const list = (res && res.components) || []
      state.items = list.map((c) => ({
        ...c,
        localeKey: PACK_LOCALE_KEY[c.packId] || c.packId,
        // phase: idle / runtime / model / starting / ready / failed
        phase: c.installed && (!c.modelId || c.modelInstalled) ? 'ready' : 'idle',
        percent: 0,
        error: '',
        selected: false,
      }))
    } catch (e) {
      state.error = (e && e.message) || String(e)
      state.items = []
    } finally {
      state.loading = false
    }
    return state.items
  }

  /** 体积未知（后端没有 manifest 快照）时才去打 /info——那条会真发网络请求。 */
  async function fillSizes(item) {
    if (!item || item.downloadBytes > 0) return item
    try {
      const info = await deps.packInfo(item.packId)
      if (info && info.totalSize) item.downloadBytes = info.totalSize
      if (info && info.unpackedSize) item.unpackedBytes = info.unpackedSize
    } catch (e) {
      // 镜像不可达：体积保持未知，卡片显示 components.sizeUnknown，不拦下载按钮
    }
    return item
  }

  /** pack 段：装 + 轮询到 ready / failed。返回 true = 就绪。 */
  async function installPack(item) {
    if (item.installed) return true
    item.phase = 'runtime'
    item.percent = 0
    await deps.packInstall(item.packId)
    for (;;) {
      const res = await deps.packStatus(item.packId)
      const st = (res && res.status) || {}
      if (st.bytesTotal > 0) {
        item.percent = Math.min(99, Math.round((st.bytesDownloaded || 0) / st.bytesTotal * 100))
      }
      if (st.state === 'ready') {
        item.installed = true
        item.percent = 100
        return true
      }
      if (st.state === 'failed' || st.state === 'revoked') {
        item.phase = 'failed'
        item.error = st.error || st.state
        return false
      }
      await sleep(POLL_MS)
    }
  }

  /** 模型段：交给主进程下，进度经 onModelProgress 事件流回来（与组件管理页同一条流）。 */
  async function installModel(item) {
    if (!item.modelId || item.modelInstalled) return true
    item.phase = 'model'
    item.percent = 0
    return new Promise((resolve) => {
      let unsub = () => {}
      const finish = (ok, msg) => {
        try { unsub() } catch (e) { /* ignore */ }
        if (!ok) { item.phase = 'failed'; item.error = msg || '' }
        else { item.modelInstalled = true; item.percent = 100 }
        resolve(ok)
      }
      unsub = deps.onModelProgress((evt) => {
        if (!evt || evt.id !== item.modelId) return
        if (evt.phase === 'progress' && typeof evt.percent === 'number') item.percent = evt.percent
        else if (evt.phase === 'done') finish(true)
        else if (evt.phase === 'error') finish(false, evt.message)
      })
      Promise.resolve(deps.modelDownload(item.modelId)).catch((e) => finish(false, (e && e.message) || String(e)))
    })
  }

  /**
   * 装一个组件。**不抛**：面板要能「一个失败、后面照跑」，抛出去会把 installAll 打断。
   */
  async function installOne(item) {
    item.error = ''
    try {
      if (!(await installPack(item))) return false
      if (!(await installModel(item))) return false
      item.phase = 'starting'
      item.percent = 0
      const res = await deps.ensureService(item.service)
      if (res && res.ok === false && !res.disabled) {
        item.phase = 'failed'
        item.error = res.message || 'service start failed'
        return false
      }
      item.phase = 'ready'
      item.percent = 100
      return true
    } catch (e) {
      item.phase = 'failed'
      item.error = (e && e.message) || String(e)
      return false
    }
  }

  /** 逐个顺序装（不并发：下载是带宽与磁盘 IO 密集，并发只会互相拖慢并让进度条乱跳）。 */
  async function installAll(items) {
    const list = (items || []).filter(Boolean)
    state.running = true
    state.totalCount = list.length
    state.doneCount = 0
    try {
      for (const item of list) {
        await installOne(item)
        state.doneCount += 1 // 失败也算处理完，否则总进度会永远停在那里
      }
    } finally {
      state.running = false
    }
    return list.every((i) => i.phase === 'ready')
  }

  /** 总进度：每个组件等权，组件内按 runtime/model/starting 三段加权。 */
  function overallPercent() {
    const list = state.items.filter((i) => i.selected || i.phase !== 'idle')
    const scope = list.length ? list : state.items
    if (!scope.length) return 0
    let sum = 0
    for (const i of scope) {
      if (i.phase === 'ready') { sum += 100; continue }
      if (i.phase === 'failed') { sum += 100; continue } // 失败也不再前进，按处理完计
      if (i.phase === 'runtime') sum += WEIGHT.runtime * (i.percent / 100)
      else if (i.phase === 'model') sum += WEIGHT.runtime + WEIGHT.model * (i.percent / 100)
      else if (i.phase === 'starting') sum += WEIGHT.runtime + WEIGHT.model
    }
    return Math.round(sum / scope.length)
  }

  return { state, load, fillSizes, installOne, installAll, overallPercent }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/orchestrator.test.mjs`
Expected: PASS（7 个用例）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/composables/useOptionalComponents.js frontend/src/services/api.js frontend/tests/optional-components/orchestrator.test.mjs
git commit -m "feat(components): 可选组件的顺序下载编排（pack → 模型 → ensure）"
```

---

### Task 4: OptionalComponentCard.vue 组件卡片

**Files:**
- Create: `frontend/src/components/OptionalComponentCard.vue`
- Create: `frontend/tests/optional-components/card.test.mjs`

**Interfaces:**
- Consumes: Task 2 的文案键、Task 3 的 item 形状（`{packId, localeKey, service, installed, downloadBytes, unpackedBytes, modelId, modelInstalled, modelBytes, featureKeys, phase, percent, error, selected}`）。
- Produces:
  - props: `item: Object`（必填）、`selectable: Boolean`（面板 true / 组件管理 false）、`busy: Boolean`
  - emits: `toggle`（`(packId, checked)`）、`install`（`(packId)`）、`retry`（`(packId)`）
  - computed（测试直接调）：`title()` / `sizeLine()` / `usageLine()` / `stateLine()`

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/card.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 卡片必须把设计 §4.3 的四件事同时说出来：下载什么 / 多大 / 解锁什么 / 不装则什么不可用。
// 手法同 litigation-visual/packUpdate.test.mjs：抠出 <script> 用 new Function 起一个纯对象。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/components.js'

const source = readFileSync(new URL('../../src/components/OptionalComponentCard.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

function t(key, params) {
  const raw = key.split('.').slice(1).reduce((o, k) => (o || {})[k], { components: zh })
  if (typeof raw !== 'string') return key
  return raw.replace(/\{(\w+)\}/g, (_, k) => String((params || {})[k]))
}

function card(item) {
  const component = new Function('script' in {} ? '' : '', script.replace('export default', 'return'))()
  const vm = Object.assign({}, component.methods, { item, $t: t })
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

const kokoro = {
  packId: 'kokoro-runtime', localeKey: 'kokoroRuntime', service: 'kokoro-service',
  installed: false, downloadBytes: 209715200, unpackedBytes: 800000000,
  modelId: 'kokoro-models', modelInstalled: false, modelBytes: 314572800,
  featureKeys: ['ttsPanel'], phase: 'idle', percent: 0, error: '',
}

test('有模型的组件：体积行同时给「运行时约 N MB」与「含模型约 M MB」', () => {
  const line = card(kokoro).sizeLine
  assert.match(line, /语音合成/)
  assert.match(line, /200/, '运行时 200MB')
  assert.match(line, /500/, '含模型 200+300=500MB')
  assert.match(line, /~\/\.aiworkdeck/)
})

test('无模型的组件不出现「含模型约」', () => {
  const line = card({ ...kokoro, packId: 'pptx-runtime', localeKey: 'pptxRuntime', modelId: null, modelBytes: 0, downloadBytes: 173015040 }).sizeLine
  assert.ok(!line.includes('含模型'), line)
  assert.match(line, /165/)
})

test('用途行同时写解锁功能与不装的影响', () => {
  const line = card(kokoro).usageLine
  assert.match(line, /语音面板/)
  assert.match(line, /语音合成不可用/)
  assert.match(line, /其余功能不受影响/)
})

test('体积未知时显示占位文案而不是「约 0 MB」', () => {
  assert.match(card({ ...kokoro, downloadBytes: 0 }).sizeLine, /体积获取中/)
})

test('状态行按 phase 分档，失败带原因', () => {
  assert.match(card({ ...kokoro, phase: 'idle' }).stateLine, /未安装/)
  assert.match(card({ ...kokoro, phase: 'runtime', percent: 42 }).stateLine, /运行时 42%/)
  assert.match(card({ ...kokoro, phase: 'model', percent: 7 }).stateLine, /模型 7%/)
  assert.match(card({ ...kokoro, phase: 'starting' }).stateLine, /正在启动/)
  assert.match(card({ ...kokoro, phase: 'ready' }).stateLine, /已就绪/)
  assert.match(card({ ...kokoro, phase: 'failed', error: '镜像不可达' }).stateLine, /镜像不可达/)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/card.test.mjs`
Expected: FAIL — 找不到 `OptionalComponentCard.vue`。

- [ ] **Step 3: 最小实现**

```vue
<!-- frontend/src/components/OptionalComponentCard.vue -->
<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="oc-card" :class="{ ready: item.phase === 'ready', failed: item.phase === 'failed' }">
    <view class="oc-head">
      <view v-if="selectable" class="oc-check" :class="{ checked: item.selected }" @tap="onToggle"></view>
      <text class="oc-title">{{ title }}</text>
      <text class="oc-state">{{ stateLine }}</text>
    </view>
    <text class="oc-size">{{ sizeLine }}</text>
    <text class="oc-usage">{{ usageLine }}</text>
    <view v-if="item.phase === 'runtime' || item.phase === 'model'" class="oc-progress">
      <view class="oc-progress-fill" :style="{ width: (item.percent || 0) + '%' }" />
    </view>
    <view class="oc-actions">
      <view v-if="!selectable && item.phase === 'idle'" class="oc-btn primary" :class="{ disabled: busy }" @tap="$emit('install', item.packId)">
        {{ $t('components.installSelected') }}
      </view>
      <view v-if="item.phase === 'failed'" class="oc-btn" @tap="$emit('retry', item.packId)">
        {{ $t('components.retry') }}
      </view>
    </view>
  </view>
</template>

<script>
const MB = 1024 * 1024

export default {
  name: 'OptionalComponentCard',
  props: {
    item: { type: Object, required: true },
    // 面板里是复选（批量下载），组件管理页里是逐条按钮
    selectable: { type: Boolean, default: false },
    busy: { type: Boolean, default: false },
  },
  emits: ['toggle', 'install', 'retry'],
  computed: {
    title() {
      return this.$t('components.' + this.item.localeKey + '.name')
    },
    /** 设计 §4.3 第一行：下载什么、多大、落哪、怎么卸载。 */
    sizeLine() {
      const runtime = Math.round((this.item.downloadBytes || 0) / MB)
      if (!runtime) return this.$t('components.sizeUnknown')
      const name = this.title
      if (!this.item.modelId) {
        return this.$t('components.promptNoModel', { name, runtime })
      }
      const total = runtime + Math.round((this.item.modelBytes || 0) / MB)
      return this.$t('components.promptWithModel', { name, runtime, total })
    },
    /** 设计 §4.3 第二行：解锁什么、不装则什么不可用。 */
    usageLine() {
      return this.$t('components.promptUsage', {
        unlocks: this.$t('components.' + this.item.localeKey + '.unlocks'),
        impact: this.$t('components.' + this.item.localeKey + '.impact'),
      })
    },
    stateLine() {
      const p = this.item.percent || 0
      switch (this.item.phase) {
        case 'runtime': return this.$t('components.stateDownloadingRuntime', { percent: p })
        case 'model': return this.$t('components.stateDownloadingModel', { percent: p })
        case 'starting': return this.$t('components.stateStartingService')
        case 'ready': return this.$t('components.stateReady')
        case 'failed': return this.$t('components.stateFailed', { msg: this.item.error || '' })
        default: return this.$t('components.stateNotInstalled')
      }
    },
  },
  methods: {
    onToggle() {
      this.$emit('toggle', this.item.packId, !this.item.selected)
    },
  },
}
</script>

<style scoped>
.oc-card { border: 1px solid var(--awd-border); border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.oc-card.ready { border-color: var(--awd-success, #3aa76d); }
.oc-card.failed { border-color: var(--awd-danger, #d9534f); }
.oc-head { display: flex; align-items: center; gap: 8px; }
.oc-check { width: 16px; height: 16px; border: 1px solid var(--awd-border); border-radius: 3px; }
.oc-check.checked { background: var(--awd-accent); border-color: var(--awd-accent); }
.oc-title { font-weight: 600; flex: 1; }
.oc-state { font-size: 12px; color: var(--awd-text-secondary); }
.oc-size, .oc-usage { display: block; font-size: 12px; color: var(--awd-text-secondary); margin-top: 6px; line-height: 1.5; }
.oc-progress { height: 6px; background: var(--awd-border); border-radius: 3px; overflow: hidden; margin-top: 8px; }
.oc-progress-fill { height: 100%; background: var(--awd-accent); transition: width .3s; }
.oc-actions { display: flex; gap: 8px; margin-top: 8px; }
.oc-btn { padding: 4px 10px; border: 1px solid var(--awd-border); border-radius: 6px; font-size: 12px; }
.oc-btn.primary { background: var(--awd-accent); color: #fff; border-color: var(--awd-accent); }
.oc-btn.disabled { opacity: .5; }
</style>
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/card.test.mjs`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/components/OptionalComponentCard.vue frontend/tests/optional-components/card.test.mjs
git commit -m "feat(components): 可选组件卡片（体积/解锁/不装影响三行齐备）"
```

---

### Task 5: 首次登录「可选组件」面板与触发条件

**Files:**
- Create: `frontend/src/components/OptionalComponentsDialog.vue`
- Modify: `frontend/src/pages/project-list/project-list.vue:364-367`（components 注册）、`:424-432`（onLoad/onShow）、模板末尾挂对话框
- Create: `frontend/tests/optional-components/first-login.test.mjs`

**Interfaces:**
- Consumes: Task 1 的 `host.prefs`、Task 3 的控制器、Task 4 的卡片。
- Produces:
  - `shouldPromptOptionalComponents({ items, promptedVersion, appVersion, isDesktop })` — 纯函数，从 `OptionalComponentsDialog.vue` 里 `export` 不方便（.vue 单文件），所以放在 `frontend/src/composables/useOptionalComponents.js` 里一并导出。
  - prefs 键：`optionalComponentsPromptedVersion`。
  - 对话框 emits：`close`（用户点「稍后再说」或装完关闭）。

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/first-login.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 触发条件（设计 §4.1）：桌面端 + 登录后 + 有未装组件 + 本机没标记过。
// 标记落 electron prefs（不是 localStorage），值是大版本号：下个大版本多出新组件时再提示一次。
import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldPromptOptionalComponents } from '../../src/composables/useOptionalComponents.js'

const missing = [{ packId: 'kokoro-runtime', installed: false, modelId: 'kokoro-models', modelInstalled: false }]
const allIn = [{ packId: 'kokoro-runtime', installed: true, modelId: 'kokoro-models', modelInstalled: true }]

test('全新安装、有未装组件、没提示过 → 提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), true)
})

test('本大版本已经提示过 → 不再打扰（「稍后再说」之后也走这条）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: '0.38.0', appVersion: '0.38.0', isDesktop: true }), false)
})

test('升到新大版本 → 再提示一次（可能有新组件）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: '0.38.0', appVersion: '0.39.0', isDesktop: true }), true)
})

test('四个都装好了 → 不提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: allIn, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), false)
})

test('浏览器端不提示（那里没有本机组件可言）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, appVersion: '0.38.0', isDesktop: false }), false)
})

test('接口没拿到东西（离线部署 ai.packs.enabled=false）→ 不提示，绝不弹一个空面板', () => {
  assert.equal(shouldPromptOptionalComponents({ items: [], promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), false)
})

test('运行时装了但模型没下也算「未装」——面板要能把模型补上', () => {
  const half = [{ packId: 'mineru-runtime', installed: true, modelId: 'mineru-models', modelInstalled: false }]
  assert.equal(shouldPromptOptionalComponents({ items: half, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), true)
})
```

追加一条面板行为测试（同文件）：

```js
import { readFileSync } from 'node:fs'
const dialogSrc = readFileSync(new URL('../../src/components/OptionalComponentsDialog.vue', import.meta.url), 'utf8')

test('面板：「稍后再说」写标记并关闭；「立即下载所选」只装勾选的', () => {
  assert.match(dialogSrc, /optionalComponentsPromptedVersion/)
  assert.match(dialogSrc, /host\.prefs/)
  assert.match(dialogSrc, /installAll\(\s*this\.controller\.state\.items\.filter\(\(i\) => i\.selected\)\s*\)/)
  assert.match(dialogSrc, /components\.later/)
  assert.match(dialogSrc, /components\.laterHint/)
  assert.ok(!/uni\.setStorageSync/.test(dialogSrc), '「提示过」不许落 localStorage/uni storage')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/first-login.test.mjs`
Expected: FAIL —「shouldPromptOptionalComponents is not a function」。

- [ ] **Step 3: 最小实现**

`useOptionalComponents.js` 末尾追加导出：

```js
export const PROMPTED_PREF_KEY = 'optionalComponentsPromptedVersion'

/** 大版本号（0.38.0 → 0.38）。小版本补丁不该让面板重新弹一次。 */
function majorOf(v) {
  const parts = String(v || '').split('.')
  return parts.length >= 2 ? parts[0] + '.' + parts[1] : String(v || '')
}

/**
 * 首次登录后要不要弹「可选组件」面板（设计 §4.1）。
 * 判据四条全要满足：桌面端 / 接口真的回了组件 / 存在未装的（运行时或模型缺任一都算）/
 * 本大版本没提示过。标记存 electron prefs，重装才重置——localStorage 被清一次
 * 用户就会被重新打扰一遍。
 */
export function shouldPromptOptionalComponents({ items, promptedVersion, appVersion, isDesktop }) {
  if (!isDesktop) return false
  const list = items || []
  if (!list.length) return false
  const anyMissing = list.some((i) => !i.installed || (i.modelId && !i.modelInstalled))
  if (!anyMissing) return false
  return majorOf(promptedVersion) !== majorOf(appVersion)
}
```

```vue
<!-- frontend/src/components/OptionalComponentsDialog.vue -->
<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <view class="ocd-mask" @tap.self="onLater">
    <view class="ocd-panel">
      <text class="ocd-title">{{ $t('components.panelTitle') }}</text>
      <text class="ocd-subtitle">{{ $t('components.panelSubtitle') }}</text>

      <scroll-view scroll-y class="ocd-list">
        <OptionalComponentCard
          v-for="item in controller.state.items"
          :key="item.packId"
          :item="item"
          :selectable="true"
          :busy="controller.state.running"
          @toggle="onToggle"
        />
      </scroll-view>

      <view v-if="controller.state.running" class="ocd-total">
        <text class="ocd-total-text">
          {{ $t('components.progressTotal', {
            done: controller.state.doneCount,
            count: controller.state.totalCount,
            percent: overall
          }) }}
        </text>
        <view class="ocd-total-bar"><view class="ocd-total-fill" :style="{ width: overall + '%' }" /></view>
      </view>

      <view class="ocd-actions">
        <view class="ocd-btn primary" :class="{ disabled: !anySelected || controller.state.running }" @tap="onInstall">
          {{ $t('components.installSelected') }}
        </view>
        <view class="ocd-btn" @tap="onLater">{{ $t('components.later') }}</view>
      </view>
      <text class="ocd-hint">{{ $t('components.laterHint') }}</text>
    </view>
  </view>
</template>

<script>
import { reactive } from 'vue'
import OptionalComponentCard from '@/components/OptionalComponentCard.vue'
import { host } from '@/services/host.js'
import { optionalComponents, packInstall, packStatus, packInfo } from '@/services/api.js'
import { createOptionalComponentsController, PROMPTED_PREF_KEY } from '@/composables/useOptionalComponents.js'

export default {
  name: 'OptionalComponentsDialog',
  components: { OptionalComponentCard },
  props: {
    // 已经由调用方拉好的清单（触发判定要先拿到它，这里不重复请求一次）
    preloaded: { type: Array, default: () => [] },
    appVersion: { type: String, default: '' },
  },
  emits: ['close'],
  data() {
    return {
      controller: createOptionalComponentsController({
        optionalComponents,
        packInstall,
        packStatus,
        packInfo,
        modelDownload: (id) => host.model.download(id),
        onModelProgress: (cb) => host.model.onProgress(cb),
        ensureService: (name) => host.services.ensure(name),
      }),
    }
  },
  computed: {
    anySelected() {
      return this.controller.state.items.some((i) => i.selected)
    },
    overall() {
      return this.controller.overallPercent()
    },
  },
  created() {
    // controller.state 要在模板里响应式地跳数字，包一层 reactive（controller 本身是纯对象）
    this.controller.state = reactive(this.controller.state)
  },
  async mounted() {
    if (this.preloaded.length) {
      await this.controller.load()
    } else {
      await this.controller.load()
    }
    // 体积未知的逐个补一次（会打 /info，只在面板真的打开时才发）
    for (const item of this.controller.state.items) {
      await this.controller.fillSizes(item)
    }
    // 「模型已装的组件默认勾选」——用户此前已经选择过这个功能（设计 §3.4）
    for (const item of this.controller.state.items) {
      if (item.phase !== 'ready' && item.modelId && item.modelInstalled) item.selected = true
    }
  },
  methods: {
    onToggle(packId, checked) {
      const it = this.controller.state.items.find((i) => i.packId === packId)
      if (it) it.selected = checked
    },
    async onInstall() {
      if (!this.anySelected || this.controller.state.running) return
      await this.controller.installAll(this.controller.state.items.filter((i) => i.selected))
      await this.markPrompted()
      if (this.controller.state.items.every((i) => i.phase !== 'failed')) this.$emit('close')
    },
    async onLater() {
      await this.markPrompted()
      this.$emit('close')
    },
    /** 标记落 electron prefs：重装才重置，下个大版本会再问一次 */
    async markPrompted() {
      try {
        if (host.prefs) await host.prefs.set(PROMPTED_PREF_KEY, this.appVersion)
      } catch (e) {
        console.warn('[OptionalComponents] 写提示标记失败', e)
      }
    },
  },
}
</script>

<style scoped>
.ocd-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.ocd-panel { width: 640px; max-width: 92vw; max-height: 82vh; background: var(--awd-bg-elevated, #fff); border-radius: 12px; padding: 20px; display: flex; flex-direction: column; }
.ocd-title { font-size: 18px; font-weight: 600; }
.ocd-subtitle { font-size: 13px; color: var(--awd-text-secondary); margin: 6px 0 12px; line-height: 1.6; }
.ocd-list { flex: 1; overflow: auto; }
.ocd-total { margin-top: 10px; }
.ocd-total-text { font-size: 12px; color: var(--awd-text-secondary); }
.ocd-total-bar { height: 6px; background: var(--awd-border); border-radius: 3px; overflow: hidden; margin-top: 6px; }
.ocd-total-fill { height: 100%; background: var(--awd-accent); transition: width .3s; }
.ocd-actions { display: flex; gap: 10px; margin-top: 14px; }
.ocd-btn { padding: 8px 16px; border: 1px solid var(--awd-border); border-radius: 8px; }
.ocd-btn.primary { background: var(--awd-accent); color: #fff; border-color: var(--awd-accent); }
.ocd-btn.disabled { opacity: .5; }
.ocd-hint { font-size: 12px; color: var(--awd-text-secondary); margin-top: 8px; }
</style>
```

`project-list.vue` 接线：`components` 注册 `OptionalComponentsDialog`；`data()` 加 `showOptionalComponents: false, optionalItems: [], appVersion: ''`；`onLoad()` 末尾加 `this.maybePromptOptionalComponents()`；方法：

```js
    /**
     * 首次登录后的「可选组件」面板（设计 §4.1）。挂在 onLoad 而不是 onShow：
     * 从项目页返回列表页会反复触发 onShow，那会变成每次返回都弹一次。
     */
    async maybePromptOptionalComponents() {
      if (!isDesktopHost() || !host.prefs) return
      try {
        const [res, prompted, status] = await Promise.all([
          optionalComponents(),
          host.prefs.get(PROMPTED_PREF_KEY),
          host.update ? host.update.status() : Promise.resolve(null),
        ])
        this.optionalItems = (res && res.components) || []
        this.appVersion = (status && status.value && status.value.appVersion) || (status && status.appVersion) || ''
        this.showOptionalComponents = shouldPromptOptionalComponents({
          items: this.optionalItems,
          promptedVersion: prompted && prompted.value !== undefined ? prompted.value : prompted,
          appVersion: this.appVersion,
          isDesktop: true,
        })
      } catch (e) {
        // 后端还没起来 / 离线部署关了 ai.packs：不打扰，用户仍可从设置进组件管理
        console.warn('[project-list] 可选组件检查跳过', e)
      }
    },
```

模板末尾（根容器内）挂：

```html
      <OptionalComponentsDialog
        v-if="showOptionalComponents"
        :preloaded="optionalItems"
        :app-version="appVersion"
        @close="showOptionalComponents = false"
      />
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/*.test.mjs && npm run check:emits`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/components/OptionalComponentsDialog.vue frontend/src/composables/useOptionalComponents.js \
        frontend/src/pages/project-list/project-list.vue frontend/tests/optional-components/first-login.test.mjs
git commit -m "feat(components): 首次登录后的可选组件面板（顺序下载 + 总进度 + 稍后再说）"
```

---

### Task 6: 设置 → 组件管理复用同一张卡片

**Files:**
- Modify: `frontend/src/components/admin/AdminPane.vue:603-680`（组件管理区块模板）、`:1714-1722`（`loadComponents`）、`:1723-1737`（下载入口）
- Modify: `frontend/src/locales/zh-CN/admin.js:189`、`frontend/src/locales/en-US/admin.js`（`componentsSubtitle` 改写）
- Create: `frontend/tests/optional-components/admin-reuse.test.mjs`

**Interfaces:**
- Consumes: Task 3 的控制器、Task 4 的卡片。
- Produces: 组件管理页列出的是**四个组件**（运行时 + 模型合并成一条），不再是三行纯模型。

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/admin-reuse.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 组件管理页与首次登录面板必须共用同一张卡片：两处口径不一致，用户在一处看到
// 「已就绪」、另一处看到「未安装」，谁都不知道该信哪个。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/components/admin/AdminPane.vue', import.meta.url), 'utf8')

test('组件管理复用 OptionalComponentCard，而不是自己再写一套 comp-row', () => {
  assert.match(src, /import OptionalComponentCard from '@\/components\/OptionalComponentCard\.vue'/)
  assert.match(src, /<OptionalComponentCard/)
})

test('组件管理走 useOptionalComponents 的编排（顺序 pack → 模型 → ensure）', () => {
  assert.match(src, /createOptionalComponentsController/)
  assert.match(src, /installOne/)
})

test('旧的三行纯模型列表已经拆掉（它只讲模型，不讲运行时，装不上会让用户困惑）', () => {
  assert.ok(!/handleComponentEnable/.test(src) || /installOne/.test(src),
    '「启用」按钮的语义已经并进 installOne 的 ensure 段')
  assert.ok(!/v-for="comp in components"/.test(src), '旧列表仍在')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/admin-reuse.test.mjs`
Expected: FAIL —「import OptionalComponentCard 不存在」。

- [ ] **Step 3: 最小实现**

1. `AdminPane.vue` 顶部 import 加：

```js
import OptionalComponentCard from '@/components/OptionalComponentCard.vue'
import { createOptionalComponentsController } from '@/composables/useOptionalComponents.js'
import { optionalComponents, packInstall, packStatus, packInfo, packUninstall } from '@/services/api.js'
```
并在 `components: { ... }` 里注册 `OptionalComponentCard`。

2. `data()` 里把 `components: []` 换成：

```js
      // 组件管理与首次登录面板共用同一份编排与同一张卡片：两处口径漂移的代价是
      // 用户在一处看到「已就绪」、另一处看到「未安装」。
      optional: createOptionalComponentsController({
        optionalComponents,
        packInstall,
        packStatus,
        packInfo,
        modelDownload: (id) => host.model.download(id),
        onModelProgress: (cb) => host.model.onProgress(cb),
        ensureService: (name) => host.services.ensure(name),
      }),
```
（`created()` 里同样 `this.optional.state = reactive(this.optional.state)`。）

3. 组件管理区块的 `section-body` 整块换成：

```html
            <view class="section-body">
              <view v-if="optional.state.loading" class="empty">
                <text class="empty-text">{{ $t('admin.loadingDots') }}</text>
              </view>
              <OptionalComponentCard
                v-for="item in optional.state.items"
                :key="item.packId"
                :item="item"
                :selectable="false"
                :busy="optional.state.running"
                @install="onInstallComponent"
                @retry="onInstallComponent"
              />
            </view>
```

4. 方法：`loadComponents()` 换成 `async loadComponents() { await this.optional.load(); for (const it of this.optional.state.items) await this.optional.fillSizes(it) }`；新增：

```js
    async onInstallComponent(packId) {
      const item = this.optional.state.items.find((i) => i.packId === packId)
      if (!item || this.optional.state.running) return
      this.optional.state.running = true
      try {
        await this.optional.installOne(item)
      } finally {
        this.optional.state.running = false
      }
    },
    /** 卸载 = 删 pack 目录 + 删模型（规范 §6：确认框注明可释放体积） */
    handleComponentRemove(item) {
      const mb = Math.round(((item.downloadBytes || 0) + (item.modelBytes || 0)) / (1024 * 1024))
      uni.showModal({
        title: this.$t('admin.removeComponentTitle'),
        content: this.$t('admin.removeComponentContent', { name: this.$t('components.' + item.localeKey + '.name'), size: mb + ' MB' }),
        success: async (r) => {
          if (!r.confirm) return
          try {
            if (item.modelId) await host.model.remove(item.modelId)
            await packUninstall(item.packId)
          } finally {
            this.loadComponents()
          }
        },
      })
    },
```
删除 `handleComponentDownload` / `handleComponentCancel` / `handleComponentEnable`（其语义分别并进 `installOne` 的三段）。

5. `admin.js` 两语言的 `componentsSubtitle` 改成：
   - zh-CN：`'本地 AI 组件按需下载：一次下好运行时与模型，之后离线可用，数据不出本机'`
   - en-US：`'On-demand local AI components: the runtime and model are downloaded together, then work offline — nothing leaves this computer'`

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/*.test.mjs && npm run check:locales && npm run check:emits`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/components/admin/AdminPane.vue frontend/src/locales/*/admin.js frontend/tests/optional-components/admin-reuse.test.mjs
git commit -m "refactor(admin): 组件管理复用可选组件卡片与下载编排"
```

---

### Task 7: AI 对话收到 component_required → 弹窗 → 装好自动重发原消息

**Files:**
- Create: `frontend/src/composables/useComponentRequired.js`
- Modify: `frontend/src/components/ChatInterface.vue:739-748`（`onClientAction`）、模板末尾挂弹窗
- Create: `frontend/tests/optional-components/chat-gate.test.mjs`

**Interfaces:**
- Consumes（#529 Task 10）：SSE payload
  `{"action":"component_required","packId":string,"service":string,"modelId":string|null,"sizeMb":number,"features":string[],"trigger":string}`；Task 3 的控制器。
- Produces:
  - `createComponentRequiredHandler(deps)`，`deps = { installOne, fillSizes, confirm, resend, lastUserMessage, toast }`
  - `handler.onAction(payload)` → `Promise<{installed: boolean, resent: boolean}>`
  - `handler.itemFromPayload(payload)` → 与 Task 3 同形的 item（`localeKey` 由 `PACK_LOCALE_KEY` 得出，`downloadBytes = sizeMb * 1024 * 1024`，`modelBytes` 由 payload 无法给出时置 0 并交给 `fillSizes` 补）

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/chat-gate.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 对话里的组件门（设计 §4.2）：弹窗 → install → 模型 → ensure → 自动重发原消息。
// 「自动重发」是这条链的重点：让用户装完组件还得自己把刚才那句话再打一遍，
// 等于把失败的代价原样转嫁给他。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createComponentRequiredHandler } from '../../src/composables/useComponentRequired.js'

const payload = {
  action: 'component_required', packId: 'pptx-runtime', service: 'pptx-service',
  modelId: null, sizeMb: 165, features: ['pptxGenerate', 'pdfToWordLayout'], trigger: 'pptx_generate',
}

function deps(over = {}) {
  const calls = []
  return {
    calls,
    installOne: async (item) => { calls.push('install:' + item.packId); item.phase = 'ready'; return true },
    fillSizes: async () => {},
    confirm: async () => true,
    lastUserMessage: () => '帮我做一份关于并购尽调的 PPT',
    resend: async (text) => { calls.push('resend:' + text) },
    toast: () => {},
    ...over,
  }
}

test('payload 直接变成卡片可用的 item（体积从 sizeMb 换算）', () => {
  const h = createComponentRequiredHandler(deps())
  const item = h.itemFromPayload(payload)
  assert.equal(item.packId, 'pptx-runtime')
  assert.equal(item.localeKey, 'pptxRuntime')
  assert.equal(item.service, 'pptx-service')
  assert.equal(item.downloadBytes, 165 * 1024 * 1024)
  assert.equal(item.modelId, null)
  assert.equal(item.phase, 'idle')
})

test('用户确认 → 装好 → 自动把原消息重发一次', async () => {
  const d = deps()
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: true, resent: true })
  assert.deepEqual(d.calls, ['install:pptx-runtime', 'resend:帮我做一份关于并购尽调的 PPT'])
})

test('用户点「暂不下载」→ 什么都不做，绝不重发', async () => {
  const d = deps({ confirm: async () => false })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: false, resent: false })
  assert.deepEqual(d.calls, [])
})

test('装失败 → 不重发（重发只会再撞一次同样的墙），toast 说明原因', async () => {
  const msgs = []
  const d = deps({
    installOne: async (item) => { item.phase = 'failed'; item.error = '镜像不可达'; return false },
    toast: (m) => msgs.push(m),
  })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.equal(r.resent, false)
  assert.match(msgs.join(' '), /镜像不可达/)
})

test('拿不到原消息也要照常装完（只是不重发）', async () => {
  const d = deps({ lastUserMessage: () => '' })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: true, resent: false })
})

test('同一轮里重复收到同一个 packId 只处理一次（工具可能连着报两次）', async () => {
  const d = deps()
  const h = createComponentRequiredHandler(d)
  await Promise.all([h.onAction(payload), h.onAction(payload)])
  assert.equal(d.calls.filter((c) => c.startsWith('install:')).length, 1)
})
```

补一条 ChatInterface 接线的源码断言（同文件）：

```js
import { readFileSync } from 'node:fs'
const chatSrc = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

test('component_required 在 ChatInterface 就地拦下，不往下透到编辑器执行器', () => {
  assert.match(chatSrc, /action\.action === 'component_required'/)
  const idx = chatSrc.indexOf("action.action === 'component_required'")
  const tail = chatSrc.slice(idx, idx + 600)
  assert.ok(!/emit\('client-action'/.test(tail),
    'component_required 不是编辑器命令，透到 EDITOR_ACTIONS 白名单只会得到 Unknown action')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/chat-gate.test.mjs`
Expected: FAIL — 找不到 `useComponentRequired.js`。

- [ ] **Step 3: 最小实现**

```js
// frontend/src/composables/useComponentRequired.js
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 对话里的「需要下载组件」闸（设计 §4.2）。
//
// 后端工具（PptxTools / PdfTools）在服务打不通且 pack 未装时发 client_action
// component_required，前端在 ChatInterface 的 onClientAction 接缝就地拦下——
// 它不是编辑器命令，透到 libreofficeExecutorClient 的 EDITOR_ACTIONS 白名单
// 只会换来一句 "Unknown action"。
//
// 装完**自动重发原消息**：让用户装完组件还得把刚才那句话再打一遍，
// 等于把失败的代价原样转嫁给他。

import { PACK_LOCALE_KEY } from '@/composables/useOptionalComponents.js'

export function createComponentRequiredHandler(deps) {
  const inFlight = new Set()

  function itemFromPayload(p) {
    return {
      packId: p.packId,
      service: p.service,
      localeKey: PACK_LOCALE_KEY[p.packId] || p.packId,
      installed: false,
      downloadBytes: (p.sizeMb || 0) * 1024 * 1024,
      unpackedBytes: 0,
      modelId: p.modelId || null,
      modelInstalled: false,
      modelBytes: 0,
      featureKeys: p.features || [],
      phase: 'idle',
      percent: 0,
      error: '',
      selected: false,
    }
  }

  async function onAction(p) {
    if (!p || !p.packId) return { installed: false, resent: false }
    // 工具可能在同一轮里连报两次（check_service 之后紧跟 generate）：只处理一次
    if (inFlight.has(p.packId)) return { installed: false, resent: false }
    inFlight.add(p.packId)
    try {
      const item = itemFromPayload(p)
      await deps.fillSizes(item)          // sizeMb 为 0（后端没缓存）时补一次
      if (!(await deps.confirm(item))) return { installed: false, resent: false }

      const ok = await deps.installOne(item)
      if (!ok) {
        deps.toast(item.error || '')
        return { installed: false, resent: false }
      }
      const text = deps.lastUserMessage()
      if (!text) return { installed: true, resent: false }
      await deps.resend(text)
      return { installed: true, resent: true }
    } finally {
      inFlight.delete(p.packId)
    }
  }

  return { itemFromPayload, onAction }
}
```

`ChatInterface.vue` 的 `onClientAction` 改成：

```js
    onClientAction((action) => {
        if (action.action === 'ppt_config_required') {
           pptConfigData.value = action
           pptExportEditable.value = false
           showPptConfigDialog.value = true
        } else if (action.action === 'component_required') {
           // 可选组件缺失（设计 §4.2）：就地弹窗 → 装 → 自动重发原消息。
           // 刻意不往下 emit：它不是编辑器命令，执行器只会回 Unknown action。
           componentRequiredHandler.onAction(action)
        } else {
           emit('client-action', action)
        }
    })
```

在 `setup()` 里构造 handler（`bubbles` / `sendMessage` 已在作用域内）：

```js
    const componentGateItem = ref(null)
    const componentGateResolve = ref(null)
    const componentRequiredHandler = createComponentRequiredHandler({
      installOne: (item) => optionalController.installOne(item),
      fillSizes: (item) => optionalController.fillSizes(item),
      // 弹窗确认：把 item 挂上去，等模板里的按钮 resolve
      confirm: (item) => new Promise((resolve) => {
        componentGateItem.value = item
        componentGateResolve.value = resolve
      }),
      lastUserMessage: () => {
        for (let i = bubbles.value.length - 1; i >= 0; i--) {
          if (bubbles.value[i].role === 'user') return bubbles.value[i].content || ''
        }
        return ''
      },
      resend: async (text) => {
        uni.showToast({ title: t('components.chatResending'), icon: 'none' })
        await sendMessage(text)
      },
      toast: (msg) => uni.showToast({ title: t('components.stateFailed', { msg }), icon: 'none' }),
    })
```

模板末尾挂弹窗（复用卡片，交互与 `DrawioEditor` 的 `installPack*` 同型：确认前先把体积与影响说全）：

```html
    <view v-if="componentGateItem" class="chat-component-gate">
      <view class="cg-panel">
        <text class="cg-title">{{ $t('components.chatTitle') }}</text>
        <OptionalComponentCard :item="componentGateItem" :selectable="false" :busy="true" />
        <view class="cg-actions">
          <view class="cg-btn primary" @tap="resolveComponentGate(true)">{{ $t('components.chatConfirm') }}</view>
          <view class="cg-btn" @tap="resolveComponentGate(false)">{{ $t('components.chatCancel') }}</view>
        </view>
      </view>
    </view>
```

```js
    const resolveComponentGate = (ok) => {
      const resolve = componentGateResolve.value
      componentGateResolve.value = null
      if (!ok) componentGateItem.value = null   // 确认则留着，卡片就地跳进度
      if (resolve) resolve(ok)
    }
```
（`optionalController` 在 `setup()` 里用与 Task 5 相同的依赖构造一次；装完在 `installOne` 返回后把 `componentGateItem.value = null`。）

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/chat-gate.test.mjs && npm run check:emits`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/composables/useComponentRequired.js frontend/src/components/ChatInterface.vue frontend/tests/optional-components/chat-gate.test.mjs
git commit -m "feat(chat): component_required 弹窗、下载后自动重发原消息"
```

---

### Task 8: EasyVoicePane 从「只下模型」扩成「运行时 + 模型」

**Files:**
- Modify: `frontend/src/components/EasyVoicePane.vue:158-170`（常量与 data）、`:195-218`（computed）、`:424-478`（模型状态机）、`:36-52`（gate 模板）
- Modify: `frontend/src/locales/zh-CN/panels.js:262-271`、`frontend/src/locales/en-US/panels.js`（新增两键）
- Create: `frontend/tests/optional-components/voice-gate.test.mjs`

**Interfaces:**
- Consumes: Task 3 的控制器（`load` / `fillSizes` / `installOne`）、Task 2 的 `components.*` 文案。
- Produces: `EasyVoicePane` 的 gate 三态：`runtimeMissing`（pack 未装）/ `modelMissing`（pack 装了模型没下）/ `engineNotRunning`（都装了但拿不到音色）。新增 locale 键 `panels.evRuntimeMissing` / `panels.evDownloadComponent`。

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/voice-gate.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 语音面板的三态（设计 §4.2）。0.38.0 起多了一态：运行时本身没装。
// 「运行时没装」和「模型没下」的下一步不同（前者 200MB，后者 300MB，且必须先装前者），
// 合并成一句「引擎没就绪」会让用户点了下载还是用不了。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/panels.js'

const source = readFileSync(new URL('../../src/components/EasyVoicePane.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

function pane(over) {
  const component = new Function('host', 'ICONS', script.replace('export default', 'return'))(
    { model: {}, services: {} }, {}
  )
  const vm = Object.assign(component.data(), component.methods, { $t: (k) => k }, over)
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

test('运行时没装 → gate 说的是「组件还没装」，按钮是下载组件', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: false, modelState: 'absent' })
  assert.equal(vm.engineGateVisible, true)
  assert.equal(vm.gateMessage, 'panels.evRuntimeMissing')
})

test('运行时装了、模型没下 → 仍是原来的「模型没下」文案', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: true, modelState: 'absent' })
  assert.equal(vm.gateMessage, 'panels.evModelMissing')
})

test('都装了但拿不到音色 → 「引擎没跑起来」，指向重新检测而不是再下一次', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: true, modelState: 'installed' })
  assert.equal(vm.gateMessage, 'panels.evEngineNotRunning')
})

test('两语言都有新增的运行时缺失文案', () => {
  assert.ok(zh.evRuntimeMissing)
  assert.ok(zh.evDownloadComponent)
})

test('下载按钮走 useOptionalComponents 的编排，不再直接调 host.model.download', () => {
  assert.match(script, /installOne/)
  assert.ok(!/host\.model\.download\(TTS_MODEL_ID\)/.test(script),
    '直接下模型会跳过运行时，模型下载器本身就跑在运行时的 venv 里')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/voice-gate.test.mjs`
Expected: FAIL —「gateMessage 返回 panels.evModelMissing 而不是 evRuntimeMissing」。

- [ ] **Step 3: 最小实现**

`EasyVoicePane.vue`：

```js
import { createOptionalComponentsController } from '@/composables/useOptionalComponents.js'
import { optionalComponents, packInstall, packStatus, packInfo } from '@/services/api.js'

// 本机语音引擎 = 运行时 pack + 模型两件事（设计 §3.1）。0.38.0 起运行时也是按需下载的，
// 面板上仍然只是一个按钮，底层顺序执行两条通道。
const TTS_PACK_ID = 'kokoro-runtime'
const TTS_MODEL_ID = 'kokoro-models'
```

`data()` 里加：

```js
      runtimeInstalled: true,   // 初值 true：没问过之前不要先喊「组件没装」
      componentItem: null,
      installing: false,
```

`computed` 改：

```js
    canDownloadModel() {
      return !!host.model && !(this.runtimeInstalled && this.modelState === 'installed')
    },
    modelDownloading() {
      return this.installing || this.modelState === 'downloading'
    },
    /**
     * 三件事三种下一步，不能合并：
     * - 运行时没装：下 200MB 的组件（模型下载器本身就跑在它的 venv 里，必须先装它）
     * - 运行时装了、模型没下：下 300MB 模型
     * - 都装了：点「重新检测」把服务拉起来
     */
    gateMessage() {
      if (!host.model) return this.$t('panels.evNoVoicesNoticeWeb')
      if (!this.runtimeInstalled) return this.$t('panels.evRuntimeMissing')
      if (this.modelState === 'installed') return this.$t('panels.evEngineNotRunning')
      return this.$t('panels.evModelMissing')
    },
    downloadButtonText() {
      return this.runtimeInstalled
        ? this.$t('panels.evDownloadModel', { size: this.modelSizeHint })
        : this.$t('panels.evDownloadComponent', { size: this.componentSizeHint })
    },
    componentSizeHint() {
      const mb = Math.round((((this.componentItem && this.componentItem.downloadBytes) || 0)
        + ((this.componentItem && this.componentItem.modelBytes) || 0)) / (1024 * 1024))
      return mb ? mb + ' MB' : this.modelSizeHint
    },
```

方法：

```js
    async loadModelState() {
      if (!host.model) return
      try {
        const res = await host.model.status()
        const comp = ((res && res.components) || []).find((c) => c.id === TTS_MODEL_ID)
        if (comp) {
          this.modelState = comp.state
          if (comp.sizeHint) this.modelSizeHint = comp.sizeHint
        }
        await this.controller.load()
        const item = this.controller.state.items.find((i) => i.packId === TTS_PACK_ID)
        if (item) {
          await this.controller.fillSizes(item)
          this.componentItem = item
          this.runtimeInstalled = !!item.installed
        }
      } catch (e) {
        console.warn('[EasyVoicePane] 读取语音组件状态失败', e)
      }
    },
    /**
     * 一个按钮，两条通道：pack → 模型 → ensure（顺序不能换，模型下载器跑在 pack 的 venv 里）。
     * 装完直接刷新音色列表，用户不用再点一次「重新检测」。
     */
    async onDownloadModel() {
      if (!this.componentItem || this.installing) return
      this.installing = true
      try {
        const ok = await this.controller.installOne(this.componentItem)
        this.runtimeInstalled = !!this.componentItem.installed
        if (!ok) {
          uni.showToast({ title: this.$t('components.stateFailed', { msg: this.componentItem.error || '' }), icon: 'none' })
          return
        }
        this.modelState = 'installed'
        await this.fetchVoices()
      } finally {
        this.installing = false
        await this.loadModelState()
      }
    },
```
`onCancelModel` / `startEngineThenRefresh` / `onRecheck` 保留（`startEngineThenRefresh` 里的 `host.services.ensure('kokoro-service')` 不变）。`data()` 里加 `controller: createOptionalComponentsController({ optionalComponents, packInstall, packStatus, packInfo, modelDownload: (id) => host.model.download(id), onModelProgress: (cb) => host.model.onProgress(cb), ensureService: (name) => host.services.ensure(name) })`，并在 `created()` 里 `this.controller.state = reactive(this.controller.state)`。

模板里下载按钮的文案换成 `{{ downloadButtonText }}`，进度行改读 `componentItem && componentItem.percent`。

locale 两语言新增：
- zh-CN `panels.evRuntimeMissing: '本机语音引擎的运行时组件还没装。装好运行时与模型后即可离线合成，声音不出本机。'`、`panels.evDownloadComponent: '下载语音合成组件（约 {size}）'`
- en-US `evRuntimeMissing: 'The speech engine runtime is not installed yet. Once the runtime and model are downloaded, synthesis runs offline and your voice never leaves this computer.'`、`evDownloadComponent: 'Download speech synthesis component (about {size})'`

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/voice-gate.test.mjs && npm run check:locales`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/components/EasyVoicePane.vue frontend/src/locales/*/panels.js frontend/tests/optional-components/voice-gate.test.mjs
git commit -m "feat(voice): 语音面板下载状态机扩成「运行时 + 模型」"
```

---

### Task 9: 录音「不出本机」开关的四态提示

> **依赖：#529 Task 7 必须已合入**（`/api/asr/local/probe` 真能回 `RUNTIME_MISSING`）。

**Files:**
- Modify: `frontend/src/config/platformServices.js:60-89`（注释与四态说明）
- Modify: `frontend/src/components/MeetingRecordingPanel.vue:31-48`（gate 模板）、`:435-460`（computed）、`:576-620`（开关）
- Modify: `frontend/src/locales/zh-CN/meeting.js`、`frontend/src/locales/en-US/meeting.js`（新增 `meeting.downloadRuntime`）
- Create: `frontend/tests/optional-components/recorder-gate.test.mjs`

**Interfaces:**
- Consumes: `GET /api/asr/local/probe` 的 `status ∈ {RUNTIME_MISSING, SERVICE_DOWN, MODEL_MISSING, READY}`；Task 3 的控制器。
- Produces: `MeetingRecordingPanel` 的 `canDownloadModel` 拆成 `canInstallRuntime`（`RUNTIME_MISSING`）与 `canDownloadModel`（`MODEL_MISSING`），两者互斥。

- [ ] **Step 1: 写失败测试**

```js
// frontend/tests/optional-components/recorder-gate.test.mjs
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「录音不出本机」的四态出路（设计 §4.2）。四条下一步各不相同：
// RUNTIME_MISSING 下组件 / MODEL_MISSING 下模型 / SERVICE_DOWN 重启 / READY 可用。
// 给 SERVICE_DOWN 一个「下载模型」按钮是错的指路——模型下完了照样没人来跑它。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/components/MeetingRecordingPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

function panel(probe, over = {}) {
  const component = new Function('host', 'localTierReady', 'localAsrProbeResult', 'refreshLocalAsrReadiness', 'NOTICE_COPY',
    script.replace('export default', 'return'))(
    { model: {}, services: {} }, () => probe.status === 'READY', () => probe, async () => probe, {}
  )
  const vm = Object.assign(component.data(), component.methods,
    { $t: (k) => k, localGateOpen: true, asrProvider: 'local', modelState: 'absent' }, over)
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

test('RUNTIME_MISSING：给「下载组件」，不给「下载模型」', () => {
  const vm = panel({ status: 'RUNTIME_MISSING', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, true)
  assert.equal(vm.canDownloadModel, false)
})

test('MODEL_MISSING：给「下载模型」，不给「下载组件」', () => {
  const vm = panel({ status: 'MODEL_MISSING', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, false)
  assert.equal(vm.canDownloadModel, true)
})

test('SERVICE_DOWN：两个下载按钮都不给（下一步是重启，不是再下一遍）', () => {
  const vm = panel({ status: 'SERVICE_DOWN', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, false)
  assert.equal(vm.canDownloadModel, false)
})

test('READY：整块 gate 不出现', () => {
  assert.equal(panel({ status: 'READY' }).localGate, null)
})

test('模板里四态各有一条出路，且组件安装走顺序编排', () => {
  assert.match(script, /canInstallRuntime/)
  assert.match(script, /installOne/)
  assert.match(source, /meeting\.downloadRuntime/)
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/recorder-gate.test.mjs`
Expected: FAIL —「canInstallRuntime is not defined」。

- [ ] **Step 3: 最小实现**

`platformServices.js` 的 `localAsrProbeResult` 注释更新为四态说明：

```js
/**
 * 最近一次本机转写探测的原文（`{status, model, diarization, message, nextStep}`），
 * 未探过时为 null。界面用它渲染「下一步该做什么」——四态各有各的出路：
 * `RUNTIME_MISSING` 下运行时组件、`MODEL_MISSING` 下模型（1.5GB）、
 * `SERVICE_DOWN` 重启应用、`READY` 可用。四者不能合并成一句「不可用」。
 */
```

`MeetingRecordingPanel.vue` computed：

```js
    /** 运行时组件没装：下一步是下组件（模型下载器本身跑在它的 venv 里，必须先装它） */
    canInstallRuntime() {
      return !!host.model && this.localGate && this.localGate.status === 'RUNTIME_MISSING'
    },
    canDownloadModel() {
      // 服务没起时给下载按钮是错的指路：模型下完了照样没人来跑它
      return !!host.model && this.localGate && this.localGate.status === 'MODEL_MISSING'
    },
```

模板 gate 的按钮区加一条（放在 `canDownloadModel` 那条之前）：

```html
          <view class="mr-btn primary" v-if="canInstallRuntime" @tap="onInstallAsrRuntime">
            {{ $t('meeting.downloadRuntime', { size: asrRuntimeSizeHint }) }}
          </view>
```

data 加 `asrItem: null`、`controller: createOptionalComponentsController({...})`（同 Task 8 的依赖），computed 加：

```js
    asrRuntimeSizeHint() {
      const mb = Math.round((((this.asrItem && this.asrItem.downloadBytes) || 0)
        + ((this.asrItem && this.asrItem.modelBytes) || 0)) / (1024 * 1024))
      return mb ? mb + ' MB' : this.modelSizeHint
    },
```

方法：

```js
    /** 一次装完运行时 + 1.5GB 模型，然后拉起服务并重新探测——用户点一次就够 */
    async onInstallAsrRuntime() {
      if (!this.asrItem) await this.loadAsrComponent()
      if (!this.asrItem) return
      const ok = await this.controller.installOne(this.asrItem)
      if (!ok) {
        uni.showToast({ title: this.$t('components.stateFailed', { msg: this.asrItem.error || '' }), icon: 'none' })
      }
      await refreshLocalAsrReadiness()
      await this.loadModelState()
    },
    async loadAsrComponent() {
      try {
        await this.controller.load()
        const item = this.controller.state.items.find((i) => i.packId === 'asr-runtime')
        if (item) {
          await this.controller.fillSizes(item)
          this.asrItem = item
        }
      } catch (e) {
        console.warn('[MeetingRecordingPanel] 读取本机转写组件状态失败', e)
      }
    },
```
`mounted()` 里在 `refreshLocalAsrReadiness()` 之后加 `this.loadAsrComponent()`。

locale 两语言新增：
- zh-CN `meeting.downloadRuntime: '下载本机语音识别组件（约 {size}）'`
- en-US `downloadRuntime: 'Download on-device speech recognition (about {size})'`

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && node --test tests/optional-components/recorder-gate.test.mjs && node --test tests/meeting-recorder/*.test.mjs && npm run check:locales`
Expected: PASS。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add frontend/src/components/MeetingRecordingPanel.vue frontend/src/config/platformServices.js \
        frontend/src/locales/*/meeting.js frontend/tests/optional-components/recorder-gate.test.mjs
git commit -m "feat(meeting): 录音不出本机开关的四态提示与运行时安装入口"
```

---

### Task 10: 全量单测 + 真机走查

**Files:**
- Modify: `frontend/package.json`（`test:optional-components` 已在 Task 2 加；确认 CI 调用它）
- Modify: `.github/workflows/desktop-build.yml`（前端单测那一步的命令列表加 `npm run test:optional-components`）
- Create: `docs/superpowers/plans/2026-09-09-optional-components-walkthrough.md`

**Interfaces:**
- Consumes: 本计划 Task 1–9 与 #529 全部任务。
- Produces: 一份可勾选的真机走查清单（设计 §5 的验收口径）。

- [ ] **Step 1: 写失败测试**

`frontend/tests/optional-components/copy.test.mjs` 末尾追加：

```js
import { readFileSync as rf } from 'node:fs'

test('CI 真的跑这套单测（不跑等于没写）', () => {
  const wf = rf(new URL('../../../.github/workflows/desktop-build.yml', import.meta.url), 'utf8')
  assert.ok(wf.includes('test:optional-components'), 'desktop-build.yml 没有调用 npm run test:optional-components')
})
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && node --test tests/optional-components/copy.test.mjs`
Expected: FAIL —「desktop-build.yml 没有调用 npm run test:optional-components」。

- [ ] **Step 3: 最小实现**

1. `.github/workflows/desktop-build.yml` 里跑前端单测的那一步的命令列表追加一行 `npm run test:optional-components`（与相邻的 `npm run test:panel-dock` 等并列）。
2. 新建走查清单：

```markdown
# 可选组件两处提示 · 真机走查（0.38.0）

前置：四个 pack 已按 `2026-09-09-pack-release-runbook.md` 上两站镜像并 verify 通过。
机器要求：全新用户（`rm -rf ~/.aiworkdeck` 或换一台干净机器），mac 与 win 各走一遍。

## A. 首次登录面板
- [ ] 安装 0.38.0 → 启动 → **没有**「正在准备本地组件…约一分钟」的解压窗
- [ ] 登录/免登进入项目列表 → 自动弹出「可选组件」面板
- [ ] 四张卡片都显示真实体积（不是「体积获取中」），且每张都写了：解锁什么 + 不装则什么不可用
- [ ] 点「稍后再说」→ 面板关闭 → 退出重开应用 → **不再弹**
- [ ] `cat ~/.aiworkdeck/prefs.json` 里有 `"optionalComponentsPromptedVersion": "0.38.0"`

## B. AI 对话触发
- [ ] 在对话里说「帮我做一份关于并购尽调的 PPT」→ 弹出「需要下载组件」，体积 ≈165MB
- [ ] 点「暂不下载」→ 对话继续，模型的回复不再喊「稍后重试」
- [ ] 再说一次同样的话 → 点「下载并继续」→ 进度走完 → **自动重发原消息** → PPT 真的生成出来
- [ ] 全程没有出现 "Unknown action" 之类的控制台错误

## C. 语音合成
- [ ] 语音面板 → 「语音合成」tab → gate 写的是「运行时组件还没装」
- [ ] 点下载 → 先走运行时进度，再走模型进度 → 结束后音色列表自动出现
- [ ] 输入一段中文 → 合成成功、能播放

## D. 录音不出本机
- [ ] 语音面板 → 「录音」tab → 打开「录音不出本机」→ 提示「组件还没安装」+「下载本机语音识别组件」
- [ ] 点下载（40MB 运行时 + 1.5GB 模型）→ 结束后开关能停在打开态
- [ ] 录一段 30 秒音频 → 转写成功，且期间抓包确认没有音频出网

## E. 组件管理
- [ ] 设置 → 组件管理：四条卡片，与首次登录面板逐字一致的文案与体积
- [ ] 卸载「语音合成」→ 确认框写明可释放体积 → 卸载后语音面板回到「运行时组件还没装」

## F. 英文界面
- [ ] 切到 English 重启 → A/B/C/D 的所有提示都是英文，没有中文残留
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd frontend && npm run test:optional-components && npm run check:locales && npm run check:emits`；`cd desktop && npm test`；`cd backend && mvn -B test`
Expected: 全绿。

- [ ] **Step 5: 提交（由主会话执行）**

```bash
git add .github/workflows/desktop-build.yml frontend/tests/optional-components/copy.test.mjs \
        docs/superpowers/plans/2026-09-09-optional-components-walkthrough.md
git commit -m "test(components): CI 接入可选组件单测，补真机走查清单"
```
```

---
