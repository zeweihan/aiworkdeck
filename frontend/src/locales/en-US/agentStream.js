// agentStream: user-visible strings of the AI chat SSE stream (useAgentStream.js).
// SSE event names / status literals (awaiting_input etc.) and the AI_REGION_BLOCKED
// marker are contracts and are intentionally not here.
export default {
  // Background task panel
  taskInProgress: 'Task in progress…',
  taskStarting: 'Task starting…',
  taskCompleted: 'Task completed',
  taskFailed: 'Task failed',
  // Stream connection markers (markdown fragments appended to bubble content)
  connectionInterrupted: '*[Connection lost]*',
  // Stop notice (rendered from the separate bubble.stopNotice field, plain text)
  stopRequested: 'Stop requested',
  // Re-entrancy guard toast on send
  alreadyStreamingToast: 'AI is still running. Wait for it to finish or click Stop.',
  // Error messages
  chatRequestFailed: 'Chat request failed: HTTP {status}',
  errorWithMessage: '**Error**: {message}',
  executionInterrupted: '> **Execution interrupted**: {message}',
  regionBlockedNotice: '> **This model is not available in your current network region**: models hosted overseas are blocked by their providers when accessed from a Mainland China network. Switch to the AI WorkDeck Cloud channel in Settings, or choose a model marked as available in both regions and resend.',
  quotaExhaustedNotice: '> **AI service credits exhausted**: the balance or quota for the current channel has been used up. With your own key, top up with your provider (such as OpenRouter); with the AI WorkDeck Cloud channel, check your credit allocation on the account page.',
  contextOverflowNotice: '> **The conversation exceeds the model context window**: automatic compression was attempted but it still does not fit. Start a new conversation, or attach fewer or shorter files.',
  interruptedRunEndedNotice: '> **The connection was interrupted**: this run finished in the background while the connection was down, so the text above may be incomplete. Refresh the page or reopen this conversation to see the full record.',
  internalErrorNotice: '> **This run was interrupted by an internal error**: the work already done and the tool execution log have been saved and remain visible in the history. Send another message to continue; if this keeps happening, use the feedback button in the bottom-right corner to send us this conversation.',
  // Subtask progress rows
  subtaskStarted: 'Subtask started',
  subtaskEnded: 'Subtask finished',
  // Document streaming placeholder
  docStreamingPlaceholder: '*(Streaming content into the document…)*',
  // Streaming write failed: surface it rather than leaving the placeholder above (dev-board#465)
  docStreamFailedNotice: '> **The content did not make it into the document**: {reason}. The file was created but may be empty - open it to check, and ask me to write it again if needed.',
  docStreamReasonEditorNotReady: 'the document editor is not ready yet',
  docStreamReasonWrongTarget: 'the editor currently has a different document open than the write target',
  docStreamReasonInsertFailed: 'stream_insert failed',
  docStreamReasonBlocked: 'the content could not be written into the document',
  docStreamReasonNoBody: 'the model did not emit any document body',
  docStreamReasonNothingReceived: 'the document received no content',
  // Fallback process card title (ProcessCard.vue matches this exact string, do not reword)
  systemOperation: 'System Actions',
  // Artifact fallback file names
  taskListArtifact: 'Task List',
  planArtifact: 'Plan',
}
