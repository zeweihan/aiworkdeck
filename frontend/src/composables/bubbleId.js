// 聊天气泡的唯一 ID。
//
// 病灶：原来两处都是 `msg-${Date.now()}`，而用户气泡与助手气泡是在**同一个同步块**里
// 先后创建的（useAgentStream 里 push(createUserBubble(...)) 紧接着 createAssistantBubble()），
// 同一毫秒 = 同一个 ID。ChatInterface 的列表是 `:key="msg.id || index"`——
// key 撞了之后 Vue 的 diff 会复用错节点：一条消息的正文渲染进另一条气泡、
// 用户/助手样式串位、旧内容残留。用户看到的就是「历史对话记录杂乱无序」。
//
// 单调递增序号保证同一页面会话内绝不重复；仍带上时间戳，便于按 ID 排查日志。
// 历史消息由后端带自己的 ID，不走这里。

let seq = 0

/** 生成一个页面会话内唯一的气泡 ID。 */
export function nextBubbleId() {
  seq += 1
  return `msg-${Date.now()}-${seq}`
}

/** 仅供测试：重置序号。 */
export function __resetBubbleIdSeqForTest() {
  seq = 0
}
