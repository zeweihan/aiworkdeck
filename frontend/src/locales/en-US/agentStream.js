// agentStream: user-visible strings of the AI chat SSE stream (useAgentStream.js).
// SSE event names / status literals (awaiting_input etc.) and the AI_REGION_BLOCKED
// marker are contracts and are intentionally not here.
export default {
  // Background task panel
  taskInProgress: 'Task in progress…',
  taskStarting: 'Task starting…',
  taskCompleted: 'Task completed',
  taskFailed: 'Task failed',
  // Stream connection / stop markers (markdown fragments appended to bubble content)
  connectionInterrupted: '*[Connection lost]*',
  stopping: '*[Stopping]*',
  // Re-entrancy guard toast on send
  alreadyStreamingToast: 'AI is still running. Wait for it to finish or click Stop.',
  // Error messages
  chatRequestFailed: 'Chat request failed: HTTP {status}',
  errorWithMessage: '**Error**: {message}',
  executionInterrupted: '> **Execution interrupted**: {message}',
  regionBlockedNotice: '> **This model is not available in your current network region**: models hosted overseas are blocked by their providers when accessed from a Mainland China network. Switch to the AI WorkDeck Cloud channel in Settings, or choose a model marked as available in both regions and resend.',
  quotaExhaustedNotice: '> **AI service credits exhausted**: the balance or quota for the current channel has been used up. With your own key, top up with your provider (such as OpenRouter); with the AI WorkDeck Cloud channel, check your credit allocation on the account page.',
  contextOverflowNotice: '> **The conversation exceeds the model context window**: automatic compression was attempted but it still does not fit. Start a new conversation, or attach fewer or shorter files.',
  // Subtask progress rows
  subtaskStarted: 'Subtask started',
  subtaskEnded: 'Subtask finished',
  // Document streaming placeholder
  docStreamingPlaceholder: '*(Streaming content into the document…)*',
  // Fallback process card title (ProcessCard.vue matches this exact string, do not reword)
  systemOperation: 'System Actions',
  // Artifact fallback file names
  taskListArtifact: 'Task List',
  planArtifact: 'Plan',
}
