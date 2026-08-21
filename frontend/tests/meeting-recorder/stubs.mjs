// meetingRecorder.js 的依赖替身（见 stop-upload-cap.test.mjs 的模块改写）。
// 上传端点固定失败，用来模拟「鉴权过期 / 文件被后台删掉」这类永久性失败。

let finishCalls = 0

// 断言只看状态里的值，不需要真的响应式；而且 worktree 里不一定装了 node_modules，
// 依赖 vue 会让这条用例在干净检出上跑不起来。
export const reactive = (o) => o

export const getApiBaseUrl = () => 'http://127.0.0.1:0'
export const getAuthHeaders = () => ({ 'X-Test': '1' })
export const createMeetingRecording = async () => ({
  meeting: { id: 'm1', audioFileId: 'f1' }, configured: true,
})
export const finishMeetingRecording = async () => { finishCalls += 1; return { id: 'm1', status: 'RECORDED' } }
export const finishCallCount = () => finishCalls
// 断言里要能读出 attempt，所以把参数一起编进返回值
export const t = (key, params) => (params ? key + JSON.stringify(params) : key)
