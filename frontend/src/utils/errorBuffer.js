// 最近的前端报错环形缓冲，只服务于「反馈里带上下文」这一个用途。
//
// 用户点开反馈浮窗时，往往已经离刚才那声报错过去几十秒了，控制台里翻不到、
// 也不会有人去翻。App.vue 里本来就有全局 error / unhandledrejection 处理器，
// 顺手把它们记进这个缓冲，提交反馈时一并送走——这是「反馈可不可定位」的关键差别。
//
// 只留最近 20 条、每条正文截断，避免把一个刷屏的错误变成几 MB 的请求体。
const MAX_ITEMS = 20
const MAX_MESSAGE_CHARS = 500
const MAX_STACK_CHARS = 1200

const buffer = []

export function recordFrontendError(entry) {
  try {
    buffer.push({
      at: new Date().toISOString(),
      kind: String((entry && entry.kind) || 'error'),
      message: truncate(entry && entry.message, MAX_MESSAGE_CHARS),
      source: truncate(entry && entry.source, 200),
      stack: truncate(entry && entry.stack, MAX_STACK_CHARS),
    })
    while (buffer.length > MAX_ITEMS) buffer.shift()
  } catch (e) {
    // 记录报错本身不能再抛错
  }
}

export function getRecentErrors() {
  return buffer.slice()
}

export function recentErrorCount() {
  return buffer.length
}

function truncate(v, max) {
  if (v === undefined || v === null) return ''
  const s = String(v)
  return s.length > max ? s.slice(0, max) + '…' : s
}
