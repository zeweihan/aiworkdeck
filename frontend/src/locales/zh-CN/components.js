// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可选组件（四个 Python 运行时 + 各自模型）的文案。设计 §4.3 的模板落在这里：
// 每一条提示都要同时说清「下载什么 / 多大 / 解锁什么 / 不装则什么不可用」。
// 体积占位符由界面从接口填（manifest 实测值），不写死。
export default {
  panelTitle: '可选组件',
  panelSubtitle: '这些组件不随安装包分发，用到哪个下哪个。全部在本机运行，内容不出这台电脑。',
  installSelected: '立即下载所选',
  installOne: '下载此组件',
  later: '稍后再说',
  laterHint: '随时可以在「设置 → 组件管理」里下载。',
  manageTitle: '组件管理',

  promptWithModel: '需要下载{name}（约 {runtime} MB；含模型约 {total} MB）。落盘于 ~/.aiworkdeck，可在「设置→组件管理」卸载释放。',
  promptNoModel: '需要下载{name}（约 {runtime} MB）。落盘于 ~/.aiworkdeck，可在「设置→组件管理」卸载释放。',
  promptUsage: '它用于：{unlocks}。不下载则：{impact}；其余功能不受影响。',

  sizeUnknown: '体积获取中…',
  stateNotInstalled: '未安装',
  stateReady: '已就绪',
  stateDownloadingRuntime: '正在下载运行时 {percent}%',
  stateDownloadingModel: '正在下载模型 {percent}%',
  stateStartingService: '正在启动组件…',
  stateFailed: '下载失败：{msg}',
  progressTotal: '总进度 {done}/{count}（{percent}%）',
  retry: '重试',

  chatTitle: '需要下载组件',
  chatConfirm: '下载并继续',
  chatCancel: '暂不下载',
  chatInstalling: '正在准备组件…',
  chatResending: '组件已就绪，正在继续刚才的请求…',

  asrRuntimeMissingAction: '下载本机语音识别组件',

  pptxRuntime: {
    name: 'PPT 与 PDF 组件',
    unlocks: 'AI 生成 PPT、PPTX 读/改格式、PDF 转 Word（版式级）、扫描件 OCR 入口',
    impact: '上述功能不可用；PDF 仍可预览与结构级转换',
  },
  mineruRuntime: {
    name: '文档解析引擎',
    unlocks: '扫描件 PDF 转 Word、OCR 版面解析',
    impact: '扫描件转换退回云端 MinerU 或不可用',
  },
  kokoroRuntime: {
    name: '语音合成',
    unlocks: '语音面板的「语音合成」',
    impact: '语音合成不可用',
  },
  asrRuntime: {
    name: '本机语音识别',
    unlocks: '录音转写的「录音不出本机」档',
    impact: '只能用云端听悟两档',
  },

  features: {
    pptxGenerate: 'AI 生成 PPT',
    pptxFormat: 'PPTX 读/改格式',
    pdfToWordLayout: 'PDF 转 Word（版式级）',
    scannedOcrEntry: '扫描件 OCR 入口',
    scannedPdfToWord: '扫描件 PDF 转 Word',
    ocrParse: 'OCR 版面解析',
    ttsPanel: '语音合成面板',
    localTranscription: '录音不出本机的转写',
  },
}
