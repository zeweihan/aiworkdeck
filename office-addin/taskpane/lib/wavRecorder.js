/**
 * 语音听写录音器（dev-board#153）：WebAudio 采 PCM → 16kHz 单声道 WAV。
 *
 * 为什么不用 MediaRecorder：各家 Office webview 的容器支持五花八门
 * （WebView2 出 webm/opus、WKWebView 出 mp4/aac），而服务端上游只收 wav/mp3。
 * 直接采 PCM 自己封 WAV，兼容面最大（getUserMedia + ScriptProcessor 连老 Safari 都有），
 * 60 秒 16k 单声道约 1.9MB，完全在上限内。
 *
 * ScriptProcessorNode 是废弃 API 但仍全平台可用；AudioWorklet 在部分 WKWebView
 * 版本上缺席，这里刻意选老 API 保覆盖面。
 */

const TARGET_RATE = 16000
export const MAX_RECORD_MS = 60_000

/** 浏览器/webview 有没有拿麦克风的入口（没有就别渲染麦克风按钮） */
export function micSupported() {
  return typeof navigator !== 'undefined'
    && !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
}

/**
 * 开始录音。返回 {stop} —— stop() 结束采集并解析出
 * {base64, durationMs}（16kHz 单声道 WAV 的 base64）。
 * getUserMedia 被拒/不可用时抛错（err.name === 'NotAllowedError' 等原样透传，
 * 调用方据此给「去系统设置授权 / 用 Word 自带听写」的提示）。
 */
export async function startRecording() {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
  const AudioCtx = window.AudioContext || window.webkitAudioContext
  const ctx = new AudioCtx()
  const source = ctx.createMediaStreamSource(stream)
  // 4096 帧一批：延迟无所谓（不是实时回放），批次大点省回调开销
  const processor = ctx.createScriptProcessor(4096, 1, 1)
  const chunks = []
  let startedAt = Date.now()

  processor.onaudioprocess = (e) => {
    chunks.push(new Float32Array(e.inputBuffer.getChannelData(0)))
  }
  source.connect(processor)
  // 不接扬声器会有实现不跑回调；接一个增益为 0 的终点静音兜底
  const mute = ctx.createGain()
  mute.gain.value = 0
  processor.connect(mute)
  mute.connect(ctx.destination)

  let stopped = false
  return {
    stop() {
      if (stopped) return Promise.resolve(null)
      stopped = true
      const durationMs = Date.now() - startedAt
      try { processor.disconnect() } catch (e) { /* 已断开 */ }
      try { source.disconnect() } catch (e) { /* 已断开 */ }
      stream.getTracks().forEach((t) => { try { t.stop() } catch (e) { /* 已停止 */ } })
      const sampleRate = ctx.sampleRate
      const closing = ctx.close().catch(() => {})
      return closing.then(() => {
        const pcm = mergeChunks(chunks)
        const down = downsample(pcm, sampleRate, TARGET_RATE)
        return { base64: encodeWavBase64(down, TARGET_RATE), durationMs }
      })
    }
  }
}

function mergeChunks(chunks) {
  let total = 0
  for (const c of chunks) total += c.length
  const out = new Float32Array(total)
  let offset = 0
  for (const c of chunks) { out.set(c, offset); offset += c.length }
  return out
}

/** 简单抽点降采样：听写场景够用（语音带宽 8k 以内，48k→16k 的混叠可忽略） */
export function downsample(input, fromRate, toRate) {
  if (fromRate === toRate) return input
  const ratio = fromRate / toRate
  const outLength = Math.floor(input.length / ratio)
  const out = new Float32Array(outLength)
  for (let i = 0; i < outLength; i++) {
    // 相邻窗口取均值，比裸抽点抗一点噪
    const begin = Math.floor(i * ratio)
    const end = Math.min(Math.floor((i + 1) * ratio), input.length)
    let sum = 0
    for (let j = begin; j < end; j++) sum += input[j]
    out[i] = end > begin ? sum / (end - begin) : 0
  }
  return out
}

/** Float32 PCM → 16-bit WAV → base64 */
export function encodeWavBase64(samples, sampleRate) {
  const dataLength = samples.length * 2
  const buffer = new ArrayBuffer(44 + dataLength)
  const view = new DataView(buffer)
  const writeStr = (offset, str) => { for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i)) }
  writeStr(0, 'RIFF')
  view.setUint32(4, 36 + dataLength, true)
  writeStr(8, 'WAVE')
  writeStr(12, 'fmt ')
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true)          // PCM
  view.setUint16(22, 1, true)          // mono
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true)
  view.setUint16(32, 2, true)
  view.setUint16(34, 16, true)
  writeStr(36, 'data')
  view.setUint32(40, dataLength, true)
  let offset = 44
  for (let i = 0; i < samples.length; i++, offset += 2) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true)
  }
  // 分块转 base64，避免大数组展开炸栈
  const bytes = new Uint8Array(buffer)
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}
