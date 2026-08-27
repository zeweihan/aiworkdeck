/**
 * 极简中英双语字典（dev-board#150）。
 *
 * 语言判定：用户手动选择（localStorage 覆盖，dev-board#177）优先，其次
 * Office.context.displayLanguage（Office 未就绪时 try/catch 吞掉），退回
 * navigator.language；'zh' 开头判中文，否则英文。
 * setLang() 可在运行时切换——字典查询走可变的 activeLang，界面由 App.vue 的
 * :key 重挂载让所有 t() 重新求值（officeExecutor 的 chip 名随模块加载定死一次，
 * 重开任务窗格才换，与 Office 显示语言变更的降级口径一致）。
 *
 * key 一律平铺，不分命名空间；带插值的串用 {name} 占位，t(key, {name: 'x'}) 替换。
 */

export const LANG_STORAGE_KEY = 'awd_addin_lang'

function storedLang() {
  try {
    const v = localStorage.getItem(LANG_STORAGE_KEY)
    if (v === 'zh' || v === 'en') return v
  } catch (e) {
    // 存储不可用：走自动判定
  }
  return ''
}

function detectLang() {
  const stored = storedLang()
  if (stored) return stored
  try {
    if (typeof Office !== 'undefined' && Office.context && Office.context.displayLanguage) {
      if (String(Office.context.displayLanguage).toLowerCase().startsWith('zh')) return 'zh'
      return 'en'
    }
  } catch (e) {
    // Office 未就绪：退回 navigator.language
  }
  try {
    const nav = typeof navigator !== 'undefined' ? navigator.language : ''
    if (nav && String(nav).toLowerCase().startsWith('zh')) return 'zh'
  } catch (e) {
    // navigator 也拿不到：默认英文
  }
  return 'en'
}

export const ZH = {
  // ---- App.vue ----
  // ---- 语音听写（dev-board#153）----
  dictate: '语音输入',
  dictateRecording: '录音中，点击结束',
  dictateTranscribing: '转写中…',
  dictateDenied: '未获得麦克风权限：请在系统设置中允许 Word 使用麦克风，或使用 Word 自带「听写」',
  dictateUnsupported: '当前环境不支持录音，可使用 Word 自带「听写」',
  dictateEmpty: '没有听清，请靠近麦克风再试一次',
  dictateFailed: '听写失败：{message}',
  selectProject: '选择项目',
  currentProjectTitle: '当前项目：{name}',
  settings: '设置',
  backToChat: '返回对话',
  back: '返回',
  login: '登录',
  logout: '退出登录',
  accountTitle: '账户',
  languageLabel: '界面语言',
  langZh: '中文',
  langEn: 'English',
  connectionSettings: '连接与高级设置',
  moreMenuTitle: '更多操作',
  menuAttach: '附加项目文件',
  menuSkills: '技能',
  menuModel: '模型',
  menuHistory: '历史对话',

  // ---- App.vue：账户菜单（dev-board#194/#198）----
  aiQuotaLabel: 'AI 额度',
  quotaRemaining: '剩余 {remaining} / 共 {limit}',
  quotaUsed: '已用 {used}',
  recharge: '充值',
  rechargeTitle: '在官网账户页充值（浏览器打开，与插件同一账户）',
  quotaExhaustedNotice: '账户额度不足，本轮已中止。充值到账后重发这条消息即可继续。',

  // ---- App.vue：新建项目（dev-board#196）----
  newProjectOption: '+ 新建项目',
  newProjectTitle: '新建项目',
  newProjectPlaceholder: '项目名称，如「某某公司尽调」',
  create: '创建',
  creating: '创建中...',
  createProjectFailed: '项目创建失败',

  // ---- ChatView.vue：空态 ----
  emptyHint: '与 AI 讨论当前文档或项目事务。',
  connectionNotReady: '尚未登录：点击右上角「登录」连接账户。',
  noProjectSelected: '尚未选择项目：在顶部下拉中选一个项目。',
  signInWelcomeTitle: '连接你的 AI WorkDeck 账户',
  signInWelcomeHint: '登录后即可让 AI 阅读与修改当前文档、检索项目资料，与桌面版是同一个账户。',

  // ---- ChatView.vue：快捷入口 ----
  quickSummarizeLabel: '总结当前文档',
  quickSummarizeText: '请阅读当前文档并给出要点总结。',
  quickCommentsLabel: '逐条处理文档批注',
  quickCommentsText:
    '请逐条处理当前文档的所有批注：先用 get_comments 读取每条批注，'
    + '按批注要求以修订模式修改其锚定的文字，改完用 reply_comment 回复说明改动；'
    + '全部处理完给我一张处理清单。',
  quickProofreadLabel: '校对错别字与病句',
  quickProofreadText: '请校对当前文档的错别字与病句，以修订模式逐处修改，不改变原意。',

  // ---- ChatView.vue：消息气泡 ----
  thinkingProcess: '思考过程',
  toolFailedSuffix: '（失败）',
  planLabel: '计划',
  proceedWithPlan: '按此计划推进',
  proposeChanges: '提出修改意见',
  workingOnDocument: '正在操作文档…',
  thinkingEllipsis: '正在思考…',
  locateInDocumentTitle: '在文档中定位：{text}',
  locateQuoteButton: '定位「{text}」',
  done: '已完成',
  doneWithDuration: '已完成 · {seconds} 秒',
  answered: '已回答',

  // ---- ChatView.vue：历史面板 ----
  historyTitle: '历史对话',
  loading: '加载中…',
  noConversationsYet: '本项目还没有历史对话',
  save: '保存',
  cancel: '取消',
  rename: '重命名',
  deleteConversation: '删除会话',
  confirmDeleteAgain: '再点一次确认删除',
  delete: '删除',
  confirmDelete: '确认删除',
  untitledConversation: '（未命名对话）',
  runningSuffix: ' · 进行中',

  // ---- ChatView.vue：技能面板 ----
  skillsTitle: '技能',
  noSkillsAvailable: '服务器上还没有可用技能',

  // ---- ChatView.vue：附件面板 ----
  attachFilesTitle: '附加项目文件',
  noProjectFiles: '项目里还没有文件',

  // ---- ChatView.vue：composer ----
  docPillOnTitle: '每条消息附带当前文档正文（点击改为不附带）',
  docPillOffTitle: '当前不附带文档正文，AI 仍可用工具按需读取（点击恢复附带）',
  docPillOffPrefix: '不附带 ',
  currentDocument: '当前文档',
  attachButton: '+ 附件',
  attachPillTitle: '附加项目里的其他文件作为上下文',
  skillsPillTitle: '选择随对话生效的技能',
  skillsButton: '技能',
  defaultModelOption: '默认模型',
  modelSelectTitle: '本轮使用的模型',
  historyPillTitle: '查看本项目的历史对话',
  historyButton: '历史',
  newConversationTitle: '开始新对话',
  newConversationButton: '新对话',
  inputPlaceholder: '输入消息，Enter 发送，/ 选技能',
  stop: '停止',
  send: '发送',
  reconnectingBanner: '连接中断，正在自动重连……',

  // ---- ChatView.vue：script 里的提示/报错 ----
  renameFailed: '重命名失败',
  deleteFailed: '删除失败',
  quoteNotFound: '原文中未找到该句（可能已被修改或另有措辞）',

  // ---- SettingsView.vue ----
  connectionTitle: '登录',
  connectedTo: '已连接 {url}',
  noAddressSet: '（未设置地址）',
  loginHint: '用 AI WorkDeck 账户登录即可连接，与桌面版是同一个账户。',
  tabPhone: '手机号',
  tabEmail: '邮箱',
  phoneLabel: '手机号',
  phonePlaceholder: '11 位手机号',
  smsCodeLabel: '验证码',
  smsCodePlaceholder: '6 位验证码',
  resendCountdown: '{seconds} 秒后重发',
  sending: '发送中...',
  getCode: '获取验证码',
  emailLabel: '邮箱',
  emailPlaceholder: '注册时使用的邮箱',
  passwordLabel: '口令',
  passwordPlaceholder: '账户口令',
  connecting: '连接中...',
  loginAndConnect: '登录并连接',
  serverUrlEmptyHint: '连接未就绪：后端地址为空，可在「高级设置」中填写',
  captchaIncomplete: '安全验证未完成，请重试',
  codeSent: '验证码已发送，请查收短信',
  codeSendFailed: '验证码发送失败',
  fillPhoneAndCode: '请填写手机号与验证码',
  fillEmailAndPassword: '请填写邮箱与口令',
  connectSuccess: '连接成功',
  accountConnectFailed: '账户连接失败',
  advancedSettings: '高级设置',
  advancedHint:
    '私有部署与团队服务器场景：可填律所自建后端地址，或同机桌面版的 http://127.0.0.1:5269，'
    + '再用官网 API Key 或手工粘贴的设备令牌连接。',
  serverUrlLabel: '后端地址',
  serverUrlPlaceholder: '例如 https://ai.yourfirm.com 或 http://127.0.0.1:5269',
  awdkKeyLabel: '官网 API Key（awdk_ 开头）',
  awdkKeyPlaceholder: '粘贴 awdk_ 开头的 API Key',
  connectWithKeyButton: '用 Key 连接',
  serverUrlEmptySimple: '连接未就绪：后端地址为空',
  awdkKeyEmpty: '连接未就绪：请粘贴 awdk_ 开头的 API Key',
  connectSuccessWithToken: '连接成功：已换取设备令牌',
  directConnectFailed: '账户直连失败',
  deviceTokenLabel: '设备令牌（awdt_ 开头）',
  deviceTokenPlaceholder: '粘贴 awdt_ 设备令牌。可在 AI WorkDeck 桌面版个人中心的「账号安全」中生成。',
  testConnectionButton: '测试连接',
  testing: '测试中...',
  serverAndTokenEmpty: '连接未就绪：请填写后端地址与设备令牌',
  connectSuccessWithProjects: '连接成功：可访问 {count} 个项目',
  connectFailed: '连接失败',

  // ---- lib/chatSession.js ----
  connectionNotReadySimple: '连接未就绪',
  previousTaskInProgress: '上一次的任务仍在进行中，正在接收后续回复……',
  awaitingAnswer: '等待你的回答，回答后 AI 继续',
  awaitingConfirmation: 'AI 等你确认后继续：把意见发过去即可',
  toolResultSendFailed: '工具结果回传失败，AI 会在等待超时后继续',
  resultSendFailedPrefix: '结果回传失败：',
  networkError: '网络错误',
  stoppedPlaceholder: '（已停止）',
  sendFailed: '消息发送失败',
  executionError: '执行出错',
  noProjectBanner: '尚未选择项目：在顶部下拉中选一个项目',
  docReadFailedBanner: '未能读取文档内容，本条消息不附带文档内容',

  // ---- lib/api.js（客户端自造文案；服务端透传文案不进字典） ----
  apiServerUrlEmpty: '连接未就绪：后端地址为空',
  apiBackendUnreachable: '后端不可达：请检查地址、网络与 HTTPS/证书',
  apiConnectFailedHttp: '连接失败（HTTP {status}）：令牌无效或后端拒绝了请求',
  apiBadResponseFormat: '后端响应格式异常',
  apiDeleteFailedHttp: '删除失败（HTTP {status}）',
  apiRenameFailedHttp: '重命名失败（HTTP {status}）',
  apiAccountVerifyFailed: '账户校验未通过，请重试',
  apiAccountLoginUnsupported: '该服务器不支持账户直接连接，请在「高级设置」中改用 API Key 或设备令牌',
  apiAccountConnectFailedHttp: '账户连接失败（HTTP {status}）',
  apiAccountBridgeDisabled: '该服务器未开启账户直连，请在「高级设置」中改用 API Key 或设备令牌',
  apiAccountConnectFailedRetry: '账户连接失败，请稍后重试',
  apiAwdkBridgeDisabled: '该服务器未开启账户直连，请改用设备令牌',
  apiAwdkConnectFailedHttp: '账户直连失败（HTTP {status}）',
  apiAwdkVerifyFailed: '账户 Key 校验未通过：请确认 Key 正确且未过期，或改用设备令牌',
  apiAiRefreshUnsupported: '该服务器不支持按账号的 AI 额度刷新',
  apiAiRefreshFailedHttp: '额度刷新失败（HTTP {status}）',
  apiAiRefreshVerifyFailed: '额度刷新未通过：请确认这枚 Key 属于本账号且未过期',
  apiConversationIssueFailedHttp: '会话签发失败（HTTP {status}）',
  apiChatUnreachable: '后端不可达：消息未送出',
  apiChatFailedHttp: '对话请求失败（HTTP {status}）',

  // ---- lib/officeExecutor.js：工具活动 chip（COMMAND_DISPLAY_NAMES） ----
  cmdGetText: '读取文档',
  cmdGetSelection: '读取选区',
  cmdSearch: '查找文本',
  cmdReplaceText: '替换文本（修订）',
  cmdInsertText: '插入文本（修订）',
  cmdAddComment: '插入批注',
  cmdFormatText: '设置文字格式',
  cmdSetParagraphFormat: '设置段落格式',
  cmdGetFormatting: '读取格式',
  cmdSetNumbering: '设置自动编号',
  cmdFormatTable: '设置表格格式',
  cmdApplyStandardFormat: '套用标准格式',
  cmdInsertTable: '插入表格',
  cmdTableRead: '读取表格',
  cmdTableSetCell: '修改单元格',
  cmdTableAddRow: '插入表格行',
  cmdTableDeleteRow: '删除表格行',
  cmdTableAddCol: '插入表格列',
  cmdTableDeleteCol: '删除表格列',
  cmdInsertBreak: '插入分页符',
  cmdSetHyperlink: '设置超链接',
  cmdEditHeaderFooter: '编辑页眉页脚',
  cmdGetComments: '读取批注',
  cmdReplyComment: '回复批注',
  cmdResolveComment: '解决批注',
  cmdGetRevisions: '读取修订',
  cmdAcceptRevision: '接受修订',
  cmdRejectRevision: '拒绝修订',
  cmdInsertFootnote: '插入脚注',
  cmdInsertEndnote: '插入尾注',
  cmdInsertImage: '插入图片',
  cmdApplyStyle: '应用样式',
  cmdManageContentControl: '管理内容控件',
  cmdSetDocumentProperties: '设置文档属性',
  cmdExcelGetRange: '读取区域',
  cmdExcelSetValues: '写入区域',
  cmdExcelSearch: '查找单元格',
  cmdExcelFormatCells: '设置单元格格式',
  cmdExcelSetBorders: '设置边框',
  cmdExcelEditRowsCols: '编辑行列',
  cmdExcelMergeCells: '合并单元格',
  cmdExcelSortRange: '排序',
  cmdExcelManageSheets: '管理工作表',
  cmdExcelFreezePanes: '冻结窗格',
  cmdExcelSetFormulas: '写入公式',
  cmdExcelGetOverview: '读取总览',
  cmdExcelSelectRange: '选中区域',
  cmdExcelSetAutofilter: '设置自动筛选',
  cmdExcelConditionalFormat: '设置条件格式',
  cmdExcelAddComment: '添加批注',
  cmdExcelGetComments: '读取批注',
  cmdExcelReplyComment: '回复批注',
  cmdExcelResolveComment: '解决批注',
  cmdExcelDeleteComment: '删除批注',
  cmdExcelSetDataValidation: '设置数据验证',
  cmdExcelAddChart: '插入图表',
  cmdExcelDefineName: '管理命名区域',
  cmdExcelProtectSheet: '保护工作表',
  cmdExcelGroupRowsCols: '分组行列',
  cmdExcelAddPivotTable: '创建透视表',
  cmdPptGetSlides: '读取幻灯片',
  cmdPptReplaceText: '替换幻灯片文本',
  cmdPptFormatText: '设置幻灯片文字格式',
  cmdPptAddSlide: '新增幻灯片',
  cmdPptDeleteSlide: '删除幻灯片',
  cmdPptAddTextBox: '插入文本框',
  cmdPptMoveSlide: '移动幻灯片',
  cmdPptAddShape: '插入形状',
  cmdPptGetSlideDetails: '读取幻灯片明细',
  cmdPptDeleteShape: '删除形状',
  cmdPptAddTable: '插入表格',
  cmdPptTableRead: '读取表格',
  cmdPptTableSetCell: '修改表格单元格',
  cmdPptSetHyperlink: '设置超链接',
  cmdFallback: '文档操作（{command}）'
}

export const EN = {
  // ---- App.vue ----
  // ---- 语音听写（dev-board#153）----
  dictate: 'Voice input',
  dictateRecording: 'Recording, click to finish',
  dictateTranscribing: 'Transcribing...',
  dictateDenied: 'Microphone permission denied: allow Word to use the microphone in system settings, or use Word built-in Dictate',
  dictateUnsupported: 'Recording is not supported here; use Word built-in Dictate instead',
  dictateEmpty: 'Could not hear that; move closer to the microphone and retry',
  dictateFailed: 'Dictation failed: {message}',
  selectProject: 'Select a project',
  currentProjectTitle: 'Current project: {name}',
  settings: 'Settings',
  backToChat: 'Back to chat',
  back: 'Back',
  login: 'Sign in',
  logout: 'Sign out',
  accountTitle: 'Account',
  languageLabel: 'Language',
  langZh: '中文',
  langEn: 'English',
  connectionSettings: 'Connection & advanced settings',
  moreMenuTitle: 'More actions',
  menuAttach: 'Attach project files',
  menuSkills: 'Skills',
  menuModel: 'Model',
  menuHistory: 'Conversation history',

  // ---- App.vue：账户菜单（dev-board#194/#198）----
  aiQuotaLabel: 'AI quota',
  quotaRemaining: '{remaining} left of {limit}',
  quotaUsed: '{used} used',
  recharge: 'Top up',
  rechargeTitle: 'Top up on the website account page (opens in browser, same account as this add-in)',
  quotaExhaustedNotice: 'Your account is out of credit; this turn was stopped. Top up and resend this message to continue.',

  // ---- App.vue：新建项目（dev-board#196）----
  newProjectOption: '+ New project',
  newProjectTitle: 'New project',
  newProjectPlaceholder: 'Project name, e.g. "Acme due diligence"',
  create: 'Create',
  creating: 'Creating...',
  createProjectFailed: 'Failed to create project',

  // ---- ChatView.vue：空态 ----
  emptyHint: 'Ask AI about the current document or project.',
  connectionNotReady: 'Not signed in: click "Sign in" in the top right to connect your account.',
  noProjectSelected: 'No project selected: pick one from the dropdown at the top.',
  signInWelcomeTitle: 'Connect your AI WorkDeck account',
  signInWelcomeHint: 'Sign in to let AI read and edit this document and search your project files — same account as the desktop app.',

  // ---- ChatView.vue：快捷入口 ----
  quickSummarizeLabel: 'Summarize this document',
  quickSummarizeText: 'Please read the current document and summarize the key points.',
  quickCommentsLabel: 'Resolve all comments',
  quickCommentsText:
    'Please go through every comment in the current document: read each one with get_comments, '
    + 'edit the anchored text in track-changes mode as the comment requests, reply with reply_comment '
    + 'to explain the change, and give me a checklist once you\'re done with all of them.',
  quickProofreadLabel: 'Proofread for typos and grammar',
  quickProofreadText: 'Please proofread the current document for typos and awkward phrasing, making edits in track-changes mode without changing the meaning.',

  // ---- ChatView.vue：消息气泡 ----
  thinkingProcess: 'Thinking',
  toolFailedSuffix: ' (failed)',
  planLabel: 'Plan',
  proceedWithPlan: 'Proceed with this plan',
  proposeChanges: 'Suggest changes',
  workingOnDocument: 'Working on the document…',
  thinkingEllipsis: 'Thinking…',
  locateInDocumentTitle: 'Locate in document: {text}',
  locateQuoteButton: 'Locate "{text}"',
  done: 'Done',
  doneWithDuration: 'Done in {seconds}s',
  answered: 'Answered',

  // ---- ChatView.vue：历史面板 ----
  historyTitle: 'History',
  loading: 'Loading…',
  noConversationsYet: 'No conversations yet in this project',
  save: 'Save',
  cancel: 'Cancel',
  rename: 'Rename',
  deleteConversation: 'Delete conversation',
  confirmDeleteAgain: 'Click again to confirm delete',
  delete: 'Delete',
  confirmDelete: 'Confirm delete',
  untitledConversation: '(Untitled conversation)',
  runningSuffix: ' · Running',

  // ---- ChatView.vue：技能面板 ----
  skillsTitle: 'Skills',
  noSkillsAvailable: 'No skills available on the server yet',

  // ---- ChatView.vue：附件面板 ----
  attachFilesTitle: 'Attach project files',
  noProjectFiles: 'No files in this project yet',

  // ---- ChatView.vue：composer ----
  docPillOnTitle: 'The current document is included with every message (click to exclude)',
  docPillOffTitle: 'The current document is not included; AI can still read it via tools on demand (click to include)',
  docPillOffPrefix: 'Not attached: ',
  currentDocument: 'Current document',
  attachButton: '+ Attach',
  attachPillTitle: 'Attach other project files as context',
  skillsPillTitle: 'Choose skills for this conversation',
  skillsButton: 'Skills',
  defaultModelOption: 'Default model',
  modelSelectTitle: 'Model for this turn',
  historyPillTitle: 'View conversation history for this project',
  historyButton: 'History',
  newConversationTitle: 'Start a new conversation',
  newConversationButton: 'New chat',
  inputPlaceholder: 'Type a message, Enter to send, / for skills',
  stop: 'Stop',
  send: 'Send',
  reconnectingBanner: 'Connection lost, reconnecting automatically…',

  // ---- ChatView.vue：script 里的提示/报错 ----
  renameFailed: 'Rename failed',
  deleteFailed: 'Delete failed',
  quoteNotFound: 'Could not find this text in the document (it may have been edited or reworded)',

  // ---- SettingsView.vue ----
  connectionTitle: 'Sign in',
  connectedTo: 'Connected to {url}',
  noAddressSet: '(no address set)',
  loginHint: 'Sign in with your AI WorkDeck account to connect — it\'s the same account as the desktop app.',
  tabPhone: 'Phone',
  tabEmail: 'Email',
  phoneLabel: 'Phone number',
  phonePlaceholder: '11-digit phone number',
  smsCodeLabel: 'Verification code',
  smsCodePlaceholder: '6-digit code',
  resendCountdown: 'Resend in {seconds}s',
  sending: 'Sending...',
  getCode: 'Get code',
  emailLabel: 'Email',
  emailPlaceholder: 'The email you registered with',
  passwordLabel: 'Password',
  passwordPlaceholder: 'Account password',
  connecting: 'Connecting...',
  loginAndConnect: 'Sign in and connect',
  serverUrlEmptyHint: 'Not connected: server address is empty, fill it in under "Advanced settings"',
  captchaIncomplete: 'Security verification incomplete, please try again',
  codeSent: 'Verification code sent, please check your messages',
  codeSendFailed: 'Failed to send verification code',
  fillPhoneAndCode: 'Please enter your phone number and verification code',
  fillEmailAndPassword: 'Please enter your email and password',
  connectSuccess: 'Connected',
  accountConnectFailed: 'Account connection failed',
  advancedSettings: 'Advanced settings',
  advancedHint:
    'For private deployments and team servers: enter your firm\'s self-hosted backend address, '
    + 'or the desktop app on this machine at http://127.0.0.1:5269, then connect with an API key '
    + 'or a manually pasted device token.',
  serverUrlLabel: 'Server address',
  serverUrlPlaceholder: 'e.g. https://ai.yourfirm.com or http://127.0.0.1:5269',
  awdkKeyLabel: 'API key (starts with awdk_)',
  awdkKeyPlaceholder: 'Paste an API key starting with awdk_',
  connectWithKeyButton: 'Connect with key',
  serverUrlEmptySimple: 'Not connected: server address is empty',
  awdkKeyEmpty: 'Not connected: please paste an API key starting with awdk_',
  connectSuccessWithToken: 'Connected: device token issued',
  directConnectFailed: 'Direct account connection failed',
  deviceTokenLabel: 'Device token (starts with awdt_)',
  deviceTokenPlaceholder: 'Paste an awdt_ device token. You can generate one under Account Security in the AI WorkDeck desktop app.',
  testConnectionButton: 'Test connection',
  testing: 'Testing...',
  serverAndTokenEmpty: 'Not connected: please fill in the server address and device token',
  connectSuccessWithProjects: 'Connected: {count} project(s) accessible',
  connectFailed: 'Connection failed',

  // ---- lib/chatSession.js ----
  connectionNotReadySimple: 'Not connected',
  previousTaskInProgress: 'The previous task is still running, receiving the rest of the reply…',
  awaitingAnswer: 'Waiting for your answer, AI will continue once you reply',
  awaitingConfirmation: 'AI is waiting for your confirmation: send your feedback to continue',
  toolResultSendFailed: 'Failed to send tool result back; AI will continue after the wait times out',
  resultSendFailedPrefix: 'Failed to send result: ',
  networkError: 'network error',
  stoppedPlaceholder: '(Stopped)',
  sendFailed: 'Failed to send message',
  executionError: 'Execution failed',
  noProjectBanner: 'No project selected: pick one from the dropdown at the top',
  docReadFailedBanner: 'Could not read the document content; this message will not include it',

  // ---- lib/api.js（客户端自造文案；服务端透传文案不进字典） ----
  apiServerUrlEmpty: 'Not connected: server address is empty',
  apiBackendUnreachable: 'Backend unreachable: check the address, network, and HTTPS/certificate',
  apiConnectFailedHttp: 'Connection failed (HTTP {status}): invalid token or the backend rejected the request',
  apiBadResponseFormat: 'Unexpected response format from backend',
  apiDeleteFailedHttp: 'Delete failed (HTTP {status})',
  apiRenameFailedHttp: 'Rename failed (HTTP {status})',
  apiAccountVerifyFailed: 'Account verification failed, please try again',
  apiAccountLoginUnsupported: 'This server does not support direct account connection; use an API key or device token under "Advanced settings"',
  apiAccountConnectFailedHttp: 'Account connection failed (HTTP {status})',
  apiAccountBridgeDisabled: 'This server has not enabled direct account connection; use an API key or device token under "Advanced settings"',
  apiAccountConnectFailedRetry: 'Account connection failed, please try again later',
  apiAwdkBridgeDisabled: 'This server has not enabled direct account connection; use a device token instead',
  apiAwdkConnectFailedHttp: 'Direct account connection failed (HTTP {status})',
  apiAwdkVerifyFailed: 'Account key verification failed: confirm the key is correct and not expired, or use a device token instead',
  apiAiRefreshUnsupported: 'This server does not support refreshing AI quota by account',
  apiAiRefreshFailedHttp: 'Quota refresh failed (HTTP {status})',
  apiAiRefreshVerifyFailed: 'Quota refresh failed verification: confirm this key belongs to this account and is not expired',
  apiConversationIssueFailedHttp: 'Failed to issue conversation (HTTP {status})',
  apiChatUnreachable: 'Backend unreachable: message not sent',
  apiChatFailedHttp: 'Chat request failed (HTTP {status})',

  // ---- lib/officeExecutor.js：工具活动 chip（COMMAND_DISPLAY_NAMES） ----
  cmdGetText: 'Read document',
  cmdGetSelection: 'Read selection',
  cmdSearch: 'Search text',
  cmdReplaceText: 'Replace text (tracked)',
  cmdInsertText: 'Insert text (tracked)',
  cmdAddComment: 'Add comment',
  cmdFormatText: 'Set text formatting',
  cmdSetParagraphFormat: 'Set paragraph formatting',
  cmdGetFormatting: 'Read formatting',
  cmdSetNumbering: 'Set numbering',
  cmdFormatTable: 'Set table formatting',
  cmdApplyStandardFormat: 'Apply house style',
  cmdInsertTable: 'Insert table',
  cmdTableRead: 'Read table',
  cmdTableSetCell: 'Edit table cell',
  cmdTableAddRow: 'Insert table row',
  cmdTableDeleteRow: 'Delete table row',
  cmdTableAddCol: 'Insert table column',
  cmdTableDeleteCol: 'Delete table column',
  cmdInsertBreak: 'Insert page break',
  cmdSetHyperlink: 'Set hyperlink',
  cmdEditHeaderFooter: 'Edit header/footer',
  cmdGetComments: 'Read comments',
  cmdReplyComment: 'Reply to comment',
  cmdResolveComment: 'Resolve comment',
  cmdGetRevisions: 'Read revisions',
  cmdAcceptRevision: 'Accept revision',
  cmdRejectRevision: 'Reject revision',
  cmdInsertFootnote: 'Insert footnote',
  cmdInsertEndnote: 'Insert endnote',
  cmdInsertImage: 'Insert image',
  cmdApplyStyle: 'Apply style',
  cmdManageContentControl: 'Manage content control',
  cmdSetDocumentProperties: 'Set document properties',
  cmdExcelGetRange: 'Read range',
  cmdExcelSetValues: 'Write range',
  cmdExcelSearch: 'Search cells',
  cmdExcelFormatCells: 'Format cells',
  cmdExcelSetBorders: 'Set borders',
  cmdExcelEditRowsCols: 'Edit rows/columns',
  cmdExcelMergeCells: 'Merge cells',
  cmdExcelSortRange: 'Sort',
  cmdExcelManageSheets: 'Manage sheets',
  cmdExcelFreezePanes: 'Freeze panes',
  cmdExcelSetFormulas: 'Write formulas',
  cmdExcelGetOverview: 'Read overview',
  cmdExcelSelectRange: 'Select range',
  cmdExcelSetAutofilter: 'Set autofilter',
  cmdExcelConditionalFormat: 'Set conditional format',
  cmdExcelAddComment: 'Add comment',
  cmdExcelGetComments: 'Read comments',
  cmdExcelReplyComment: 'Reply to comment',
  cmdExcelResolveComment: 'Resolve comment',
  cmdExcelDeleteComment: 'Delete comment',
  cmdExcelSetDataValidation: 'Set data validation',
  cmdExcelAddChart: 'Insert chart',
  cmdExcelDefineName: 'Manage named ranges',
  cmdExcelProtectSheet: 'Protect sheet',
  cmdExcelGroupRowsCols: 'Group rows/columns',
  cmdExcelAddPivotTable: 'Create pivot table',
  cmdPptGetSlides: 'Read slides',
  cmdPptReplaceText: 'Replace slide text',
  cmdPptFormatText: 'Set slide text formatting',
  cmdPptAddSlide: 'Add slide',
  cmdPptDeleteSlide: 'Delete slide',
  cmdPptAddTextBox: 'Insert text box',
  cmdPptMoveSlide: 'Move slide',
  cmdPptAddShape: 'Insert shape',
  cmdPptGetSlideDetails: 'Read slide details',
  cmdPptDeleteShape: 'Delete shape',
  cmdPptAddTable: 'Insert table',
  cmdPptTableRead: 'Read table',
  cmdPptTableSetCell: 'Edit table cell',
  cmdPptSetHyperlink: 'Set hyperlink',
  cmdFallback: 'Document action ({command})'
}

export const currentLang = detectLang()

// 运行时可变的当前语言（初值 = currentLang）。t() 每次查它，setLang 改它。
let activeLang = currentLang

export function getLang() {
  return activeLang
}

/**
 * 手动切换语言并持久化（写不进存储时静默降级，本次会话内仍生效）。
 * 界面刷新由调用方负责（App.vue 用 :key 重挂载）。
 */
export function setLang(lang) {
  if (lang !== 'zh' && lang !== 'en') return
  activeLang = lang
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang)
  } catch (e) {
    // 存储不可用：本次会话内生效即可
  }
}

/**
 * t('key', {name: 'x'})：查字典，未知 key 回退 key 本身；{name} 占位做简单替换。
 */
export function t(key, params) {
  const dict = activeLang === 'zh' ? ZH : EN
  const raw = Object.prototype.hasOwnProperty.call(dict, key) ? dict[key] : key
  if (!params) return raw
  return raw.replace(/\{(\w+)\}/g, (match, name) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match)
}
