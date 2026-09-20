// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
export default {
  panelTitle: 'Optional components',
  panelSubtitle: 'These components are not bundled with the installer. Download only what you use — everything runs on this computer and nothing leaves it.',
  installSelected: 'Download selected',
  installOne: 'Download this component',
  later: 'Not now',
  laterHint: 'You can download them any time from Settings → Components.',
  alreadyReady: 'Already installed: {names}.',
  nameSeparator: ', ',
  manageTitle: 'Components',

  promptWithModel: '{name} needs to be downloaded (about {runtime} MB; about {total} MB including the model). It is stored under ~/.aiworkdeck and can be removed from Settings → Components.',
  promptNoModel: '{name} needs to be downloaded (about {runtime} MB). It is stored under ~/.aiworkdeck and can be removed from Settings → Components.',
  promptUsage: 'It powers: {unlocks}. Without it: {impact}. Everything else keeps working.',

  sizeUnknown: 'Checking size…',
  stateNotInstalled: 'Not installed',
  stateReady: 'Ready',
  stateDownloadingRuntime: 'Downloading runtime {percent}%',
  stateDownloadingModel: 'Downloading model {percent}%',
  stateStartingService: 'Starting the component…',
  stateFailed: 'Download failed: {msg}',
  progressTotal: 'Overall {done}/{count} ({percent}%)',
  retry: 'Retry',

  chatTitle: 'A component is required',
  chatConfirm: 'Download and continue',
  chatCancel: 'Not now',
  chatInstalling: 'Preparing the component…',
  chatResending: 'The component is ready. Continuing your request…',
  chatReadyRetry: 'The component is ready. You can resend your request.',

  backgroundDownload: 'Download in background',
  backgroundStarted: 'Downloading in the background. Track progress in Settings → Components.',
  backgroundDone: '{name} is ready to use.',
  backgroundFailed: '{name} failed to download: {msg}',

  asrRuntimeMissingAction: 'Download on-device speech recognition',

  pptxRuntime: {
    name: 'Slides and PDF-to-Word',
    unlocks: 'AI slide generation, PPTX reading and formatting, layout-preserving Word conversion for text-based PDFs; scanned PDFs also need the OCR engine below',
    impact: 'those features are unavailable; PDF preview and structural conversion still work',
  },
  mineruRuntime: {
    name: 'Scanned-document OCR engine (MinerU)',
    unlocks: 'Word conversion and layout recognition for scanned PDFs with no text layer, editable PPT export; only needed for scans, and requires "Slides and PDF-to-Word"',
    impact: 'scanned PDFs cannot be converted to Word; text-based PDFs are unaffected',
  },
  kokoroRuntime: {
    name: 'Speech synthesis',
    unlocks: 'the Speech synthesis tab of the Voice panel',
    impact: 'speech synthesis is unavailable',
  },
  asrRuntime: {
    name: 'On-device speech recognition',
    unlocks: 'the "recordings never leave this computer" transcription tier',
    impact: 'only the two cloud transcription tiers remain',
  },

  features: {
    pptxGenerate: 'AI slide generation',
    pptxFormat: 'PPTX read/edit formatting',
    pdfToWordLayout: 'PDF to Word (layout-preserving)',
    scannedOcrEntry: 'scanned-document OCR entry (also needs the OCR engine)',
    scannedPdfToWord: 'scanned PDF to Word',
    ocrParse: 'OCR layout parsing',
    pptxEditableExport: 'editable PPT export',
    ttsPanel: 'speech synthesis panel',
    localTranscription: 'on-device transcription',
  },
}
