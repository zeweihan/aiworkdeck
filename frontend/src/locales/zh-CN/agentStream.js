// agentStream：AI 对话 SSE 流（useAgentStream.js）的用户可见提示文案。
// SSE 事件名/状态字面量（awaiting_input 等）与 AI_REGION_BLOCKED 判据是契约，不在此列。
export default {
  // 后台任务面板
  taskInProgress: '任务进行中...',
  taskStarting: '任务开始...',
  taskCompleted: '任务完成',
  taskFailed: '任务失败',
  // 流式连接与停止标记（拼进气泡 content 的 markdown 片段）
  connectionInterrupted: '*[连接中断]*',
  stopping: '*[正在停止]*',
  // 发送防重入 toast
  alreadyStreamingToast: 'AI 正在执行中，请等待完成或点击停止',
  // 错误提示
  chatRequestFailed: '对话请求失败: HTTP {status}',
  errorWithMessage: '**错误**: {message}',
  executionInterrupted: '> **执行中断**：{message}',
  regionBlockedNotice: '> **该模型在当前网络环境不可用**：境外模型在境内网络会被服务商按地域拒绝。可在设置中改用 AI WorkDeck 云端通道，或换成标注「境内外均可用」的模型后重新发送。',
  quotaExhaustedNotice: '> **AI 服务额度不足**：当前通道的余额或配额已用完。使用自备 Key 时请到服务商（如 OpenRouter）充值；使用 AI WorkDeck 云端通道时请到官网账户页检查额度分配。',
  contextOverflowNotice: '> **对话上下文超出模型窗口**：已尝试自动压缩仍超限。建议开启新对话继续，或减少一次携带的文件数量与长度。',
  // 子任务进度行
  subtaskStarted: '子任务开始',
  subtaskEnded: '子任务结束',
  // 文档流式写入占位
  docStreamingPlaceholder: '*（正在向文档流式写入内容…）*',
  // 过程卡兜底标题（ProcessCard.vue 按此串识别系统卡，勿改措辞）
  systemOperation: '系统操作',
  // artifact 兜底文件名
  taskListArtifact: '任务清单',
  planArtifact: '计划',
}
