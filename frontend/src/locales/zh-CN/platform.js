// 「平台服务」共用文案：首启向导步骤 2 与系统管理「平台服务」分区两处共用。
//
// 服务清单的权威源在后端（GET /api/platform-services 的 service / provider / hasLocal），
// 这里只管界面上叫什么、干什么用。后端加一项而这里没加，那一项会以 key 原样显示——
// 宁可丑也不要静默少掉一整项服务。
//
// 文案红线（两条，改这个文件时逐条对一遍）：
//   1. 不许出现「登录」「未授权」「请先」三个子串。api.js 历史上拿它们判掉线并清会话，
//      后端 AccountServiceTest / PlatformGatewayClientTest 至今按同一口径断言，两侧必须一致。
//   2. 全站禁 emoji。
export default {
  // ---- 七项服务（AI 不在其列：它走凭证下发 + 本机直连，不经网关） ----
  svcAsrName: '会议录音转写',
  svcAsrDesc: '把会议录音转成带说话人区分的文字稿',
  svcOcrName: '图片文字识别',
  svcOcrDesc: '从截图、扫描件与照片里提取文字',
  svcSearchName: '联网搜索',
  svcSearchDesc: 'AI 回答问题时查阅公开网页',
  svcTtsName: '语音合成',
  svcTtsDesc: '把文字读成语音',
  svcQichachaName: '企业工商信息',
  svcQichachaDesc: '查询公司登记、股东与关联关系',
  svcTushareName: '证券与财务数据',
  svcTushareDesc: '查询上市公司行情与财务指标',
  svcPkulawName: '法律法规与案例',
  svcPkulawDesc: '检索法条、司法解释与裁判文书',

  // ---- 档位 ----
  tierLabel: '来源',
  tierPlatform: '平台代采',
  tierByok: '自备 Key',
  tierLocal: '本地',
  tierNeedsAccount: '需要连接账户',
  tierPlatformNote: '由 AI Workdeck 统一采购，按用量折算 Credits 从账户余额扣，你不用自己去开账号。',
  tierLocalNote: '在本机运行，数据不出本机，不消耗 Credits。',

  // ---- 三种全局状态 ----
  notConnectedTitle: '尚未连接账户',
  notConnectedBody: '平台代采要用官网账户结算。在账户页粘贴一枚 awdk_ 开头的账户 Key 即可连接；不连接也能工作，把下面各项改成自备 Key 就行。',
  goConnect: '去连接账户',
  serverModeTitle: '本机形态不支持平台代采',
  serverModeBody: '团队服务器与云端实例统一使用自备 Key：那里的外部服务是整台机器共账的，平台代采会把全所的用量记到同一个账户上。各项凭证在下面各自的「使用自己的 Key（高级）」里填。',
  loadFailed: '读取平台服务状态失败，稍后重试',
  switchFailed: '切换失败，稍后重试',
  switched: '已切换',

  // ---- 自备 Key 折叠区 ----
  useOwnKey: '使用自己的 Key（高级）',
  switchToOwnKey: '改用自己的 Key',
  expand: '展开',
  collapse: '收起',
  byokPresentNote: '本机已存有这项服务的 Key，当前正在使用它。想省掉自己的账号可以切到平台代采。',
  byokMissingNote: '切到自备 Key 之前，先在下面填好这项服务的凭证，否则它会处于不可用状态。',
  saveHint: '改完凭证要点下方的「保存配置」；档位切换是即时生效的，不用保存。',

  // ---- AI 那条单列说明 ----
  aiRowName: 'AI 对话与写作',
  aiRowDesc: 'AI 不走平台服务这条通路：模型请求由本机直接发往所选供应商，不经过 AI Workdeck 的服务器。供应商、模型与账户余额在「AI 功能设置」里配。',
  goAiSettings: '前往 AI 功能设置',

  // ---- 本地档尚未就绪（P3 的本地 ASR 落地后翻牌）----
  // 会议录音面板那个开关的说法在组件里，不走这里：两处语境不同（这里是下拉里少了一项，
  // 那里是一个灰着的开关），共用一句必然有一处读起来是错的。
  localAsrPending: '本地转写引擎将在后续版本提供，所以「本地」这一档暂时不在选项里——不做成「能选中、录完两小时才发现转不了」的样子。',
}
