// 会议录音引擎：模块级单例，挂在页面树之外。
//
// 为什么不放面板组件里：录音要横跨页面跳转（navigateTo 去别处、reLaunch 切项目）
// 持续进行，页面实例会被销毁/重建，MediaRecorder 一旦跟着实例走，切页即断录。
// 状态用 Vue reactive 暴露，面板与全局浮动指示器（utils/recordingIndicator.js）都读它。
//
// 崩溃安全：MediaRecorder 按 5s 出 chunk，chunk 顺序追加上传（X-File-Offset 协议，
// 与 FileTree 分片上传同一后端端点）——进程被杀最多丢最后 5 秒 + 未上传队列。
// 录音配方（getUserMedia/pickAudioMime/轨道必须 stop）抄自 FeedbackWidget。
import { reactive } from 'vue'
import { getApiBaseUrl, createMeetingRecording, finishMeetingRecording } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'
// 报错直接显示在会议录音面板里（recorderState.error 与 throw 出去的 message 都是），
// 所以文案与面板同一个命名空间。非组件模块的翻译入口是 t()，且只能在函数体内取值。
import { t } from '@/i18n'

const CHUNK_TIMESLICE_MS = 5000
const UPLOAD_TIMEOUT_MS = 60000
const LEVEL_INTERVAL_MS = 200

export const recorderState = reactive({
  status: 'idle', // idle | starting | recording | paused | stopping
  projectId: null,
  meetingId: null,
  audioFileId: null,
  seconds: 0,
  level: 0, // 0..1 实时电平（让用户确信收音正常）
  uploadedBytes: 0,
  error: '',
  configured: null, // 后端是否已配转写凭证（create 时回报，null=未知）
})

let mediaRecorder = null
let mediaStream = null
let audioContext = null
let analyser = null
let secondsTimer = null
let levelTimer = null
let stopResolve = null

// 顺序上传队列：offset 必须严格递增，绝不能并发发块
let uploadQueue = []
let uploadOffset = 0
let uploading = false
let recordingDone = false
// 本模块最近写进 recorderState.error 的那条「上传受阻」原文，传通之后据此清理。
// 原先比的是文案前缀，文案进了 i18n 就不能这么判（英文版永远匹配不上）；
// 而清空又必须只认自己写的那条，不能顺手抹掉别处的报错——留原文比对是两者兼顾的写法。
let uploadStalledNotice = ''

function pickAudioMime() {
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']
  for (const c of candidates) {
    try {
      if (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(c)) return c
    } catch (e) { /* ignore */ }
  }
  return ''
}

export function isRecordingActive() {
  return recorderState.status === 'recording' || recorderState.status === 'paused'
    || recorderState.status === 'starting' || recorderState.status === 'stopping'
}

/**
 * 开始录音：建会议档 → 拿麦克风 → 开录。
 * 全局同一时刻只允许一场；重复调用直接抛。
 * @returns {Promise<object>} 后端返回的 meeting
 */
export async function startRecording(projectId) {
  if (isRecordingActive()) {
    throw new Error(t('meeting.alreadyRecording'))
  }
  if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices) {
    throw new Error(t('meeting.recordingUnsupported'))
  }
  recorderState.status = 'starting'
  recorderState.error = ''
  try {
    // 先拿麦克风再建档：权限被拒时不留空会议记录
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const res = await createMeetingRecording(projectId)
    const meeting = res.meeting || res
    recorderState.projectId = projectId
    recorderState.meetingId = meeting.id
    recorderState.audioFileId = meeting.audioFileId
    recorderState.configured = res.configured !== undefined ? !!res.configured : null
    recorderState.seconds = 0
    recorderState.uploadedBytes = 0
    uploadQueue = []
    uploadOffset = 0
    uploading = false
    recordingDone = false
    uploadStalledNotice = ''

    const mimeType = pickAudioMime()
    mediaRecorder = mimeType
      ? new MediaRecorder(mediaStream, { mimeType })
      : new MediaRecorder(mediaStream)
    mediaRecorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size) {
        uploadQueue.push(ev.data)
        drainUploadQueue()
      }
    }
    stopResolve = null
    mediaRecorder.onstop = () => {
      recordingDone = true
      drainUploadQueue()
    }
    mediaRecorder.start(CHUNK_TIMESLICE_MS)

    startLevelMeter()
    secondsTimer = setInterval(() => {
      if (recorderState.status === 'recording') recorderState.seconds += 1
    }, 1000)
    recorderState.status = 'recording'
    return meeting
  } catch (e) {
    cleanupMedia()
    recorderState.status = 'idle'
    if (e && (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError')) {
      throw new Error(t('meeting.micPermissionDenied'))
    }
    throw e
  }
}

export function pauseRecording() {
  if (recorderState.status !== 'recording' || !mediaRecorder) return
  try { mediaRecorder.pause() } catch (e) { /* ignore */ }
  recorderState.status = 'paused'
}

export function resumeRecording() {
  if (recorderState.status !== 'paused' || !mediaRecorder) return
  try { mediaRecorder.resume() } catch (e) { /* ignore */ }
  recorderState.status = 'recording'
}

/**
 * 停止录音：等最后一个 chunk 上传完 → 通知后端 finish（凭证已配则自动转写）。
 * @returns {Promise<object|null>} finish 后的 meeting（面板据此刷新状态）
 */
export async function stopRecording() {
  if (!mediaRecorder || recorderState.status === 'idle' || recorderState.status === 'stopping') {
    return null
  }
  recorderState.status = 'stopping'
  const durationMs = recorderState.seconds * 1000
  const meetingId = recorderState.meetingId

  const stopped = new Promise((resolve) => { stopResolve = resolve })
  try {
    if (mediaRecorder.state !== 'inactive') mediaRecorder.stop()
    else { recordingDone = true; drainUploadQueue() }
  } catch (e) {
    recordingDone = true
    drainUploadQueue()
  }
  await stopped // 队列全部落库后 resolve（见 drainUploadQueue）

  cleanupMedia()
  let meeting = null
  try {
    meeting = await finishMeetingRecording(meetingId, durationMs)
  } catch (e) {
    console.error('[meeting] finish 失败', e)
    recorderState.error = t('meeting.finishWriteBackFailed', { message: (e && e.message) || e })
  }
  recorderState.status = 'idle'
  recorderState.meetingId = null
  recorderState.projectId = null
  recorderState.level = 0
  return meeting
}

// ==================== 上传 ====================

async function drainUploadQueue() {
  if (uploading) return
  uploading = true
  try {
    while (uploadQueue.length > 0) {
      const blob = uploadQueue[0]
      const isLast = recordingDone && uploadQueue.length === 1
      await uploadChunkWithRetry(blob, uploadOffset, isLast)
      uploadOffset += blob.size
      recorderState.uploadedBytes = uploadOffset
      uploadQueue.shift()
    }
  } finally {
    uploading = false
  }
  // 录音已结束且队列清空 → 通知 stopRecording 收尾
  if (recordingDone && uploadQueue.length === 0 && stopResolve) {
    const r = stopResolve
    stopResolve = null
    r()
  }
}

async function uploadChunkWithRetry(blob, offset, isLast) {
  let attempt = 0
  // 录音进行期间无限重试（指数退避封顶 10s）：块顺序不能乱，丢一块整段音频作废
  for (;;) {
    try {
      await uploadChunk(blob, offset, isLast)
      if (uploadStalledNotice && recorderState.error === uploadStalledNotice) {
        recorderState.error = ''
      }
      uploadStalledNotice = ''
      return
    } catch (e) {
      attempt += 1
      uploadStalledNotice = t('meeting.uploadStalled', { attempt })
      recorderState.error = uploadStalledNotice
      await new Promise(r => setTimeout(r, Math.min(1000 * attempt, 10000)))
    }
  }
}

function uploadChunk(blob, offset, isLast) {
  return new Promise((resolve, reject) => {
    // #ifdef H5
    const xhr = new XMLHttpRequest()
    const timer = setTimeout(() => { try { xhr.abort() } catch (e) { /* ignore */ } }, UPLOAD_TIMEOUT_MS)
    xhr.open('POST', `${getApiBaseUrl()}/api/files/${recorderState.audioFileId}/upload`)
    const headers = getAuthHeaders()
    for (const key in headers) xhr.setRequestHeader(key, headers[key])
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.setRequestHeader('X-File-Offset', String(offset))
    if (isLast) {
      // 最后一块才报总大小：后端以此更新 fileSize 并触发完成侧副作用
      xhr.setRequestHeader('X-File-Total-Size', String(offset + blob.size))
    }
    xhr.onload = () => {
      clearTimeout(timer)
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error('HTTP ' + xhr.status))
    }
    xhr.onerror = () => { clearTimeout(timer); reject(new Error('网络错误')) }
    xhr.onabort = () => { clearTimeout(timer); reject(new Error('上传超时')) }
    xhr.send(blob)
    // #endif
    // #ifndef H5
    reject(new Error('当前平台不支持录音上传'))
    // #endif
  })
}

// ==================== 电平与清理 ====================

function startLevelMeter() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    audioContext = new Ctx()
    const source = audioContext.createMediaStreamSource(mediaStream)
    analyser = audioContext.createAnalyser()
    analyser.fftSize = 256
    source.connect(analyser)
    const buf = new Uint8Array(analyser.frequencyBinCount)
    levelTimer = setInterval(() => {
      if (!analyser || recorderState.status === 'paused') { recorderState.level = 0; return }
      analyser.getByteTimeDomainData(buf)
      let sum = 0
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i] - 128) / 128
        sum += v * v
      }
      // RMS 放大到肉眼可见的范围，封顶 1
      recorderState.level = Math.min(1, Math.sqrt(sum / buf.length) * 4)
    }, LEVEL_INTERVAL_MS)
  } catch (e) {
    // 电平只是视觉反馈，拿不到不影响录音
  }
}

function cleanupMedia() {
  // 轨道不停的话 macOS 状态栏会一直亮着录音指示灯
  try { (mediaStream || { getTracks: () => [] }).getTracks().forEach(t => t.stop()) } catch (e) { /* ignore */ }
  try { if (audioContext) audioContext.close() } catch (e) { /* ignore */ }
  if (secondsTimer) clearInterval(secondsTimer)
  if (levelTimer) clearInterval(levelTimer)
  secondsTimer = null
  levelTimer = null
  mediaRecorder = null
  mediaStream = null
  audioContext = null
  analyser = null
}

export function formatSeconds(total) {
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}
