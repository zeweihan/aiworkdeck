// useEditorBridge.js — editor-agnostic dispatch seam for the AI agent command pipeline.
//
// Epic #43 (LibreOffice migration). The agent command pipeline is:
//   backend SSE `client_action` -> useAgentStream -> ChatInterface emits
//   -> project-overview.vue handleWpsCommand -> executor.executeCommand(action, params)
//   -> POST /api/ai/agent/wps-result
// Today handleWpsCommand (project-overview.vue) HARDCODES the WPS executor:
//   useWpsBridge().executeCommand(commandAction, params, wpsInstance)
//
// RFC v2's thesis (docs/LIBREOFFICE_MIGRATION_PLAN.md) is that the backend agent
// contract is editor-agnostic and ONLY the frontend executor changes. This
// composable is that seam: ONE place that routes an editor-agnostic command
// {action, params} to either the WPS executor or the LibreOffice (ZetaOffice)
// executor, normalizing the single signature difference between them:
//   - WPS         needs the live document instance: executeCommand(action, params, wpsInstance)
//   - LibreOffice talks to a pre-connected worker port: executeCommand(action, params)
//
// DORMANT: not yet imported by the live WPS dispatch path, so the WPS product is
// byte-for-byte unaffected. Default editor = 'wps', so activation is a
// zero-behavior-change switch until LibreOffice is explicitly selected.
//
// ACTIVATION (real-machine step — sandbox can't boot ZetaOffice/backend):
//   1. In project-overview.vue handleWpsCommand, replace the inline
//      `useWpsBridge().executeCommand(commandAction, params, wpsInstance)` with a
//      shared editorBridge.executeCommand(commandAction, params, { wpsInstance }).
//   2. When the embedded ZetaOffice finishes booting, call
//      editorBridge.connectLibreOffice(port) with the worker thread port
//      (the value Module.uno_main resolves to in the host).
//   3. Flip the active editor with editorBridge.setEditor(EDITOR_LIBREOFFICE)
//      (e.g. behind a system_setting / gray-rollout flag — Phase 3).

import { ref } from 'vue'
import { useWpsBridge } from './useWpsBridge.js'
import { useLibreOfficeBridge } from './useLibreOfficeBridge.js'

export const EDITOR_WPS = 'wps'
export const EDITOR_LIBREOFFICE = 'libreoffice'

export function useEditorBridge(initialEditor = EDITOR_WPS, opts = {}) {
  const activeEditor = ref(initialEditor)
  const wps = useWpsBridge()
  // The LibreOffice executor is pluggable. Default = the in-context bridge (worker
  // port via connectLibreOffice). The webview architecture passes the host-side
  // relay executor instead:
  //   useEditorBridge(EDITOR_WPS, { libreExecutor: createWebviewEditorExecutor(webviewEl) })
  // — see useZetaOfficeWebview.js. Both expose executeCommand(action, params).
  const libre = opts.libreExecutor || useLibreOfficeBridge()

  // Wire the embedded ZetaOffice worker thread port (Module.uno_main result) —
  // only meaningful for the in-context executor; the webview relay executor owns
  // the worker on the other side of the boundary, so this is a no-op for it.
  const connectLibreOffice = (port) => (typeof libre.connect === 'function' ? libre.connect(port) : undefined)

  // Editor-agnostic dispatch. `ctx` carries editor-specific handles the seam
  // normalizes away — currently only WPS's live document instance, which the
  // LibreOffice executor does not need (its worker port is pre-connected).
  const executeCommand = async (action, params = {}, ctx = {}) => {
    if (activeEditor.value === EDITOR_LIBREOFFICE) {
      return libre.executeCommand(action, params)
    }
    return wps.executeCommand(action, params, ctx.wpsInstance)
  }

  const setEditor = (kind) => { activeEditor.value = kind }

  return {
    activeEditor,
    setEditor,
    executeCommand,
    connectLibreOffice,
    isLibreOfficeConnected: libre.isConnected || (() => true),
  }
}
