// 会议录音的全部界面文案：面板 components/MeetingRecordingPanel.vue 与
// 录音引擎 utils/meetingRecorder.js（引擎的报错直接显示在面板里，同属一个语境）。
//
// 文案红线（两条，改这个文件时逐条对一遍）：
//   1. 不许出现「登录」「未授权」「请先」三个子串。api.js 历史上拿它们判掉线并清会话，
//      后端 AccountServiceTest / PlatformGatewayClientTest 至今按同一口径断言，两侧必须一致
//      （同 platform.js）。因此「另一个项目正在录音中」那条写成「停止后即可」而不是「请先停止」。
//   2. 全站禁 emoji。
//
// 档位那几个短标签与 platform.js 的 tier* 各写一份，不共用：那边是设置页下拉里的档位名，
// 这边是录音面板的状态徽标——本地档在那边叫「本地」（一个下拉选项），在这边叫「本地转写」
// （回答「这段录音怎么转」）。共用一句必然有一处读起来是错的。
// 面板标题不在这里：#389 之后标题由外壳的 sidebar-header 统一出，文案走
// config.sidebar.meetingRecorder（左栏图标的名字与标题必须是同一个事实）。
export default {
  // ---- 转写档位与「录音不出本机」 ----
  tierLabel: '转写方式',
  tierPlatform: '平台代采',
  tierByok: '自备 Key',
  tierLocal: '本地转写',
  tierNeedsAccount: '需要连接账户',
  tierDescLocal: '录音与转写都在本机完成，音频不出本机。速度约为实时的一点五倍（两小时的会要跑一小时上下），没有说话人分离。',
  tierDescByok: '用你自己的阿里云听悟账号转写，音频经你自己的 OSS 中转。',
  tierDescNoPlatform: '本机形态使用自备 Key，在「系统管理 - 平台服务」里填听悟凭证。',
  tierDescNotConnected: '连接官网账户后即可直接转写，不用自己开通听悟。',
  tierDescPlatform: '由 AI WorkDeck 代为转写，按时长折算 Credits 从账户余额扣。音频经我们的对象存储中转，转写完成即删除，另有 24 小时兜底清理。',
  localSwitchLabel: '录音不出本机',
  localSwitchNoteOn: '音频不上传，转写在本机完成；比云端慢，且没有说话人分离。',
  localSwitchNoteReady: '打开后音频不上传，转写在本机完成（比云端慢，且没有说话人分离）。',
  localSwitchNoteNeedsModel: '打开需要本机转写模型；下面可以就地下载。',
  switchFailed: '切换失败，稍后重试',

  // ---- 本机转写模型（就地下载） ----
  modelDownloading: '正在下载模型 {percent}%',
  cancelDownload: '取消下载',
  // 「约」在这里、不在 {size} 里：{size} 是 model-manager 的 sizeHint（形如 '1.5 GB'，
  // 语言中立），同一个值还要喂给 admin 的确认文案，修饰词写进那边会漏到英文界面上
  downloadModel: '下载模型（约 {size}）',
  recheck: '重新检测',
  downloadStartFailed: '开始下载失败，稍后重试',

  // ---- 未配置转写凭证（录音仍可用）。下一步按档位分 ----
  notConfiguredPlatform: '转写暂不可用：录音会保存到项目文件，但不能转文字。到「系统管理 - 账户与用量」连接官网账户即可开通。',
  notConfiguredByok: '未配置转写服务：录音会保存到项目文件，但不能转文字。管理员可在「系统管理 - 平台服务 - 会议录音转写」里填阿里云听悟凭证，或改用平台代采。',

  // ---- 录音 ----
  micDeviceLabel: '麦克风',
  micDeviceFallbackName: '麦克风 {n}',
  micDeviceFallback: '选中的麦克风不可用，已切换到默认设备',
  startRecording: '开始录音',
  startHint: '点击即开始，说话人自动区分，结束后自动转写',
  recording: '录音中',
  connectingMic: '正在连接麦克风…',
  paused: '已暂停',
  pause: '暂停',
  resume: '继续',
  stopRecording: '结束录音',
  saving: '保存中...',
  backgroundHint: '切到其他页面录音不会中断，可从顶部胶囊随时停止',
  otherProjectRecording: '另一个项目正在录音中，从顶部胶囊停止后即可在这里开始',
  cannotStartRecording: '无法开始录音',

  // ---- 录音引擎 utils/meetingRecorder.js ----
  alreadyRecording: '已有一场录音在进行中',
  recordingUnsupported: '当前环境不支持录音',
  micPermissionDenied: '拿不到麦克风权限，到系统设置里允许本应用使用麦克风',
  finishWriteBackFailed: '录音已保存，但状态回写失败：{message}',
  uploadStalled: '上传受阻，正在重试（第 {attempt} 次）',

  // ---- 列表与状态徽标 ----
  sectionTitle: '录音记录',
  empty: '还没有录音。上面点「开始录音」。',
  statusRecording: '录音中',
  statusRecorded: '未转写',
  statusTranscribing: '转写中',
  statusTranscribed: '已转写',
  statusEmpty: '未识别到人声',
  statusFailed: '转写失败',

  // ---- 详情动作 ----
  renameTitle: '改标题',
  titlePlaceholder: '会议标题',
  playRecording: '播放录音',
  stopPlayback: '停止播放',
  delete: '删除',
  cancel: '取消',
  save: '保存',
  saveFailed: '保存失败：{message}',
  noAudio: '没有可播放的录音',
  playFailed: '播放失败：{message}',

  // ---- 转写 ----
  needCredentialsHint: '配置转写凭证后可转文字与生成纪要。',
  transcribe: '开始转写',
  retryTranscribe: '重试转写',
  transcribingHint: '转写与说话人分离进行中，通常几分钟内完成，可离开此页',
  transcribeFailed: '转写失败',
  submitTranscribeFailed: '提交转写失败：{message}',
  emptyTranscriptHint: '这段录音已转写完成，但没有识别到有效的人声内容，可能是录音过短或几乎无人说话。如果确认录音中有对话，可以重新转写。',

  // ---- 说话人 ----
  speakersTitle: '说话人（点击改名）',
  speakerDefaultName: '说话人{n}',
  speakerNamePlaceholder: '说话人{n} 的名字',

  // ---- 纪要与导出 ----
  generateMinutes: '生成会议纪要',
  sendingToAi: '正在交给 AI...',
  generateMinutesFailed: '生成纪要失败：{message}',
  exportTranscript: '导出转写稿',
  // {folder} 是后端回的**实际**文件夹名，不要写死：那个文件夹按建档时的界面语言叫
  // 「会议录音」或 Meeting Recordings，写死一个必然有一种语境下指错地方
  exported: '已导出：{name}（见「{folder}」文件夹）',
  transcriptFallbackName: '转写稿',
  exportFailed: '导出失败：{message}',

  // ---- 机器速览与转写稿 ----
  // 「机器速览」= 听悟给的章节/摘要/待办素材，与 AI 写的「会议纪要」是两回事，名字不能混
  autoSummary: '机器速览',
  todoLeads: '待办线索',
  transcript: '转写稿',
  expand: '展开',
  collapse: '收起',

  // ---- 删除确认 ----
  deleteDialogTitle: '删除会议',
  deleteDialogBody: '将删除该会议的转写记录与录音文件，不可恢复。确认删除？',
  confirmDelete: '确认删除',
  deleteFailed: '删除失败：{message}',
}
