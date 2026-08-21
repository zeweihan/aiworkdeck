// 审计（dev-board#74）第二批前端缺陷的回归断言。
// 组件带 @/ 别名 import 不进来（本仓既有 node:test 的一贯限制），能抽出纯函数的
// 已经在各自的 *.test.mjs 里真跑了；这里覆盖：(a) 只能靠源码文本核实的 .vue 内嵌
// 逻辑（拖拽守卫 / 卸载清理 / 节流绕过），(b) 确认那些纯函数真的被组件接上了线，
// 不是定义了但没人调用。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// ======================================================================
// 1. FileTree.vue：还原嵌套文件时连带检查祖先（接线核实，判定逻辑见
//    file-tree-recycle.test.mjs）
// ======================================================================

test('restoreFile 接了 findTopmostDeletedAncestor，且在真的调用还原接口之前检查', () => {
  const src = stripComments(read('components/FileTree.vue'))
  assert.match(src, /import\s*\{\s*findTopmostDeletedAncestor,\s*summarizeDeleteResults\s*\}\s*from\s*'@\/utils\/fileTreeRecycle\.js'/,
    'FileTree.vue 必须从 utils/fileTreeRecycle.js 引入这两个判定函数')

  const start = src.indexOf('async restoreFile(item)')
  assert.ok(start > 0, '找不到 restoreFile')
  const nextMethodStart = src.indexOf('\n    buildTreeView(', start)
  const body = src.slice(start, nextMethodStart > 0 ? nextMethodStart : start + 1500)

  assert.match(body, /findTopmostDeletedAncestor\(item,\s*this\.recycleBin\)/, 'restoreFile 必须查一遍祖先链')
  const blockerIdx = body.indexOf('findTopmostDeletedAncestor')
  const apiCallIdx = body.indexOf('restoreFileApi(projectId, item.id)')
  assert.ok(blockerIdx > 0 && apiCallIdx > blockerIdx,
    '祖先检查必须排在真正调用 restoreFileApi 之前，不能查完还是无条件还原')
  assert.match(body, /uni\.showModal/, '祖先仍被删除时必须明确告诉用户，不能无声 return')
})

// ======================================================================
// 2. FileTree.vue：回收站视图禁用拖拽（draggable 属性 + drop 处理都要挡）
// ======================================================================

test('回收站视图下 :draggable 必须为 false（H5 拖拽源头就该被掐掉）', () => {
  const src = stripComments(read('components/FileTree.vue'))
  assert.match(src, /:draggable="viewMode !== 'recycle'"/,
    'draggable 必须挂 viewMode 条件，否则回收站行还能被拖走')
})

test('handleDrop 必须在真正处理放下之前先挡住回收站视图（只挡 draggable 不够——\n' +
  '外部拖拽比如暂存区拖进来，drop 处理器不看 draggedIndex 也会命中）', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const start = src.indexOf('async handleDrop(e, index)')
  assert.ok(start > 0, '找不到 handleDrop')
  const end = src.indexOf('handleDragEnd()', start)
  const body = src.slice(start, end > 0 ? end : start + 2000)

  const guardIdx = body.indexOf("viewMode === 'recycle'")
  const moveFileIdx = body.indexOf('moveFile(projectId')
  assert.ok(guardIdx > 0, 'handleDrop 里必须有回收站早退判断')
  assert.ok(moveFileIdx > guardIdx, '回收站守卫必须排在调用 moveFile 之前，否则挡了个寂寞')
  // 早退要离函数开头很近（紧跟 preventDefault 之后），不能埋在一堆业务逻辑之后
  const preventDefaultIdx = body.indexOf('e.preventDefault()')
  assert.ok(guardIdx - preventDefaultIdx < 200,
    '回收站守卫应该紧跟在函数开头，不要埋在中间——否则容易被后续改动绕过')
})

// ======================================================================
// 3. FileTree.vue：批量彻底删除吞掉逐条失败（接线核实，归并逻辑见
//    file-tree-recycle.test.mjs）
// ======================================================================

test('executeBatchDelete 的 hard 分支接了 summarizeDeleteResults，失败的 id 不进 succeededIds', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const start = src.indexOf('async executeBatchDelete()')
  assert.ok(start > 0, '找不到 executeBatchDelete')
  const end = src.indexOf('this.clearChecked()', start)
  const body = src.slice(start, end > 0 ? end : start + 3000)

  assert.match(body, /summarizeDeleteResults\(results\)/, '必须用归并函数处理逐条结果，不能循环完就无条件当全体成功')
  assert.match(body, /succeededIds\.map\(Number\)/, '本地列表只应该摘掉真正成功的 id')
  assert.match(body, /failedIds\.length > 0/, '必须区分部分失败的情况，如实提示')
  assert.doesNotMatch(body, /this\.recycleBin\s*=\s*this\.recycleBin\.filter\(f => !deletedSet\.has\(f\.id\)\);\s*\n\s*uni\.showToast\(\{ title: this\.\$t\('fileTree\.permDeleted'\)/,
    '不能再是"无条件过滤+无条件成功提示"的旧写法')
})

// ======================================================================
// 4. project-overview.vue / ocrCapture.js：OCR 截图 MediaStream 从不释放
// ======================================================================

test('beforeUnmount 必须释放 OCR 屏幕共享 stream（stopOcrCapture 曾经全仓没有调用点）', () => {
  const src = stripComments(read('pages/project-overview/project-overview.vue'))
  const start = src.indexOf('beforeUnmount() {')
  assert.ok(start > 0, '找不到 beforeUnmount')
  // beforeUnmount 之后到下一个生命周期/methods 分界之间
  const end = src.indexOf('\n  methods: {', start)
  const body = src.slice(start, end > 0 ? end : start + 6000)
  assert.match(body, /this\.stopOcrCapture\(\)/, 'beforeUnmount 必须调用 stopOcrCapture 释放 stream')
})

test('closeOcrOverlay 刻意不停 stream（授权一次后保持是设计取舍，写在注释里）', () => {
  const src = stripComments(read('pages/project-overview/ocrCapture.js'))
  const start = src.indexOf('closeOcrOverlay() {')
  assert.ok(start > 0, '找不到 closeOcrOverlay')
  const end = src.indexOf('async startOcrCapture()', start)
  const body = src.slice(start, end > 0 ? end : start + 1200)
  assert.doesNotMatch(body, /ocrStream/,
    'closeOcrOverlay 不应该碰 ocrStream——同页内反复截图不该每次都重新弹系统选择器，' +
    '释放交给 beforeUnmount（面板/页面离开时）')
})

test('stopOcrCapture 仍然会真的停轨道（这是 beforeUnmount 能生效的前提）', () => {
  const src = stripComments(read('pages/project-overview/ocrCapture.js'))
  const start = src.indexOf('stopOcrCapture() {')
  assert.ok(start > 0, '找不到 stopOcrCapture')
  const body = src.slice(start, start + 400)
  assert.match(body, /getTracks\(\)\.forEach\(t => t\.stop\(\)\)/)
})

// ======================================================================
// 5. meetingRecorder.js：录音设备中途死掉，界面照常计时报「录音中」
//    （接线核实，状态判定逻辑见 meeting-recorder-status.test.mjs）
// ======================================================================

test('startRecording 给 stream 的每条 track 挂了 onended', () => {
  const src = stripComments(read('utils/meetingRecorder.js'))
  assert.match(src, /track\.onended = handleTrackEnded/)
  // 必须在拿到 mediaStream 之后立刻挂，不能等 MediaRecorder 建好才挂（那之间还有一个 await）
  const acquireIdx = src.indexOf('mediaStream = await acquireMicStream(deviceId)')
  const bindIdx = src.indexOf('track.onended = handleTrackEnded')
  const createRecorderIdx = src.indexOf('new MediaRecorder(mediaStream')
  assert.ok(acquireIdx > 0 && bindIdx > acquireIdx && bindIdx < createRecorderIdx,
    'onended 绑定必须紧跟在拿到 stream 之后，覆盖到 MediaRecorder 建立之前的窗口')
})

test('isRecordingActive 认 interrupted 为"活跃"，否则面板会跳回"未在录音"的界面', () => {
  const src = stripComments(read('utils/meetingRecorder.js'))
  const start = src.indexOf('export function isRecordingActive()')
  const body = src.slice(start, start + 300)
  assert.match(body, /'interrupted'/)
})

test('handleTrackEnded 会写 recorderState.error，提示用户（不是无声复位）', () => {
  const src = stripComments(read('utils/meetingRecorder.js'))
  const start = src.indexOf('function handleTrackEnded()')
  assert.ok(start > 0)
  const body = src.slice(start, start + 400)
  assert.match(body, /recorderState\.error = t\('meeting\.deviceInterrupted'\)/)
})

test('面板与胶囊的状态标签都要认 interrupted，否则头条文案仍然骗人地写着「录音中」', () => {
  const panel = stripComments(read('components/MeetingRecordingPanel.vue'))
  assert.match(panel, /recState\.status === 'interrupted' \? \$t\('meeting\.interrupted'\)/,
    'MeetingRecordingPanel 的实时标签必须区分 interrupted，不能落到"录音中"/"已暂停"两选一')

  const indicator = stripComments(read('components/MeetingRecordingIndicator.vue'))
  assert.match(indicator, /state\.status === 'interrupted' \? '已中断'/,
    'MeetingRecordingIndicator 同理——它是硬编码中文没做 i18n，是既有欠账，这里保持同一风格')
})

// ======================================================================
// 6. ProjectFavoritesPanel.vue：删收藏后的刷新被节流吞掉
// ======================================================================

test('confirmDelete 必须调用 refresh(true) 绕过节流', () => {
  const src = stripComments(read('components/ProjectFavoritesPanel.vue'))
  const start = src.indexOf('async confirmDelete(id)')
  assert.ok(start > 0, '找不到 confirmDelete')
  const body = src.slice(start, start + 400)
  assert.match(body, /this\.refresh\(true\)/, 'confirmDelete 必须传 force=true，否则 1.2s 节流窗口内的删除后刷新会被整个吞掉')
})

test('refresh 的节流条件仍然认 force（否则上面那条传参就是摆设）', () => {
  const src = stripComments(read('components/ProjectFavoritesPanel.vue'))
  const start = src.indexOf('async refresh(force = false)')
  assert.ok(start > 0, 'refresh 签名必须保留 force 参数')
  const body = src.slice(start, start + 400)
  assert.match(body, /if \(!force && /, 'force 必须能绕过节流早退')
})

// ======================================================================
// 7. PersonalSettingsPanel.vue：TOTP 绑定慢响应覆盖新密钥
//    （接线核实，代次判定逻辑见 request-generation.test.mjs）
// ======================================================================

test('toggleTotpPanel 在 in-flight 期间禁用、且用请求代次丢弃过期响应', () => {
  const src = stripComments(read('components/userprofile/PersonalSettingsPanel.vue'))
  assert.match(src, /import\s*\{\s*shouldAcceptResponse\s*\}\s*from\s*'@\/utils\/requestGeneration\.js'/)

  const start = src.indexOf('async toggleTotpPanel()')
  assert.ok(start > 0, '找不到 toggleTotpPanel')
  const end = src.indexOf('async confirmTotpBind()', start)
  const body = src.slice(start, end > 0 ? end : start + 2000)

  assert.match(body, /if \(this\.totpBusy\) return/, '必须在方法开头就挡住 in-flight 期间的重复点击')
  assert.ok(body.indexOf('if (this.totpBusy) return') < body.indexOf('await totpSetup()'),
    'in-flight 闸必须排在发起请求之前')
  const seqAssignIdx = body.indexOf('++this._totpRequestSeq')
  assert.ok(seqAssignIdx > 0, '必须递增请求代次')
  const shouldAcceptCount = (body.match(/shouldAcceptResponse\(seq, this\._totpRequestSeq\)/g) || []).length
  assert.ok(shouldAcceptCount >= 2, '每一段 await 之后都要重新判代次（setup 响应 + 二维码生成两处 await）')
})

test('cancelTotpPanel 解锁 totpBusy，否则 in-flight 期间取消一次就永久锁死绑定按钮', () => {
  const src = stripComments(read('components/userprofile/PersonalSettingsPanel.vue'))
  const start = src.indexOf('cancelTotpPanel() {')
  assert.ok(start > 0)
  const body = src.slice(start, start + 400)
  assert.match(body, /this\.totpBusy = false/)
})

// ======================================================================
// 8. EasyVoicePane.vue：TTS 生成在组件卸载后继续，泄漏一段没有控件能停的音频
// ======================================================================

test('beforeUnmount 置卸载位并释放 audioUrl', () => {
  const src = stripComments(read('components/EasyVoicePane.vue'))
  const start = src.indexOf('beforeUnmount() {')
  assert.ok(start > 0)
  const end = src.indexOf('methods: {', start)
  const body = src.slice(start, end > 0 ? end : start + 800)
  assert.match(body, /this\._unmounted = true/)
  assert.match(body, /URL\.revokeObjectURL\(this\.audioUrl\)/)
})

test('handleGenerate 在 await 之后、真正播放之前检查卸载位', () => {
  const src = stripComments(read('components/EasyVoicePane.vue'))
  const start = src.indexOf('async handleGenerate()')
  assert.ok(start > 0)
  const end = src.indexOf('downloadAudio()', start)
  const body = src.slice(start, end > 0 ? end : start + 2000)

  const awaitIdx = body.indexOf('await generateTtsAudio(payload)')
  const guardIdx = body.indexOf('if (this._unmounted) return')
  const togglePlayIdx = body.lastIndexOf('this.togglePlay()')
  assert.ok(awaitIdx > 0 && guardIdx > awaitIdx,
    '卸载判据必须排在 generateTtsAudio 的 await 之后')
  assert.ok(guardIdx < togglePlayIdx, '卸载判据必须排在真正播放之前，不能播完才检查')
})

// ======================================================================
// 9. DrawioEditor.vue：快速两次保存，其中一次悄无声息地没做
//    （接线核实，串行队列行为见 async-serialize.test.mjs）
// ======================================================================

test('persist 通过 _persistQueue 串行化，不再是裸的并发调用', () => {
  const src = stripComments(read('components/DrawioEditor.vue'))
  assert.match(src, /import\s*\{\s*createSerialQueue\s*\}\s*from\s*'@\/utils\/asyncSerialize\.js'/)
  assert.match(src, /_persistQueue:\s*createSerialQueue\(\)/)

  const start = src.indexOf('persist(xml) {')
  assert.ok(start > 0, '找不到 persist')
  const body = src.slice(start, start + 200)
  assert.match(body, /this\._persistQueue\(\(\) => this\.persistNow\(xml\)\)/)
})

test('save 事件的分发仍然调用 persist（对外接口没变，call site 不用跟着改）', () => {
  const src = stripComments(read('components/DrawioEditor.vue'))
  assert.match(src, /msg\.event === 'save'\) \{\s*\n\s*this\.persist\(msg\.xml\)/)
})
