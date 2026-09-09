// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
export default {
  panelTitle: 'Optional components',
  panelSubtitle: 'These components are not bundled with the installer. Download only what you use — everything runs on this computer and nothing leaves it.',
  installSelected: 'Download selected',
  installOne: 'Download this component',
  later: 'Not now',
  laterHint: 'You can download them any time from Settings → Components.',
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

  asrRuntimeMissingAction: 'Download on-device speech recognition',

  pptxRuntime: {
    name: 'Slides and PDF component',
    unlocks: 'AI slide generation, PPTX formatting, layout-preserving PDF to Word, scanned-document OCR entry',
    impact: 'those features are unavailable; PDFs can still be previewed and converted structurally',
  },
  mineruRuntime: {
    name: 'Document parsing engine',
    unlocks: 'scanned PDF to Word and OCR layout parsing',
    impact: 'scanned-document conversion falls back to cloud MinerU or is unavailable',
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
    scannedOcrEntry: 'scanned-document OCR entry',
    scannedPdfToWord: 'scanned PDF to Word',
    ocrParse: 'OCR layout parsing',
    ttsPanel: 'speech synthesis panel',
    localTranscription: 'on-device transcription',
  },
}
