// useEditorBridge.js — editor-agnostic dispatch seam for the AI agent command pipeline.
//
// Epic #43 (LibreOffice migration), WPS removal #79. The agent command pipeline is:
//   backend SSE `client_action` -> useAgentStream -> ChatInterface emits
//   -> project-overview.vue handleWpsCommand -> executor.executeCommand(action, params)
//   -> POST /api/ai/agent/wps-result
//
// RFC v2's thesis (docs/LIBREOFFICE_MIGRATION_PLAN.md) was that the backend agent
// contract is editor-agnostic and ONLY the frontend executor changes. With WPS
// removed (#79), LibreOffice (ZetaOffice) is the single executor behind this seam;
// the seam itself stays so a future second editor plugs in the same way.
//
// The LibreOffice executor is pluggable. Default = the in-context bridge (worker
// port via connectLibreOffice). The webview architecture passes the host-side
// relay executor instead:
//   useEditorBridge({ libreExecutor: createWebviewEditorExecutor(webviewEl) })
// — see useZetaOfficeWebview.js. Both expose executeCommand(action, params).

import { useLibreOfficeBridge } from './useLibreOfficeBridge.js'

export const EDITOR_LIBREOFFICE = 'libreoffice'

export function useEditorBridge(opts = {}) {
  const libre = opts.libreExecutor || useLibreOfficeBridge()

  // Wire the embedded ZetaOffice worker thread port (Module.uno_main result) —
  // only meaningful for the in-context executor; the webview relay executor owns
  // the worker on the other side of the boundary, so this is a no-op for it.
  const connectLibreOffice = (port) => (typeof libre.connect === 'function' ? libre.connect(port) : undefined)

  const executeCommand = async (action, params = {}) => libre.executeCommand(action, params)

  return {
    executeCommand,
    connectLibreOffice,
    isLibreOfficeConnected: libre.isConnected || (() => true),
  }
}
