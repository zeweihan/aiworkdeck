// useLibreOfficeBridge.js — Vue composable wrapping the framework-agnostic
// LibreOffice executor client (libreofficeExecutorClient.js).
//
// Epic #43 task ④. The editor-agnostic executor bridge: exposes the standard
// executeCommand(action, params) contract so the agent command pipeline
// (backend SSE action -> frontend executor -> result) stays editor-agnostic
// (the WPS-era useWpsBridge.js implemented the same contract before its removal
// in #79). The real client logic + the editor-agnostic command contract live in
// libreofficeExecutorClient.js (plain ESM, verified against a real LibreOffice in
// the Phase 0 spike); this wrapper only adds Vue reactivity (isProcessing /
// lastError) and is dormant until connect(port) wires an embedded ZetaOffice.

import { ref } from 'vue'
import { createLibreOfficeExecutor } from './libreofficeExecutorClient.js'

export function useLibreOfficeBridge() {
  const isProcessing = ref(false)
  const lastError = ref(null)

  const client = createLibreOfficeExecutor({
    onError: (m) => { lastError.value = m },
  })

  const executeCommand = async (action, params = {}) => {
    isProcessing.value = true
    lastError.value = null
    try {
      return await client.executeCommand(action, params)
    } finally {
      isProcessing.value = false
    }
  }

  return {
    executeCommand,
    connect: client.connect,
    isConnected: client.isConnected,
    isProcessing,
    lastError,
  }
}
