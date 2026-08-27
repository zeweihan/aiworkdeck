// 「依据」面板（InsightPane.vue，dev-board#181/#182）。
export default {
  title: '依据',

  // 头部与 run 状态
  noDoc: '未打开文档',
  parse: '解析',
  reparse: '重新解析',
  running: '正在解析…',
  done: '解析完成',
  failed: '解析失败',
  loading: '加载中…',
  loadingDetail: '正在取检索详情…',
  noDetail: '这一条没有检索详情',
  loadFailed: '取解析结果失败',
  parseFailed: '发起解析失败',
  detailFailed: '取检索详情失败',
  refreshFailed: '重新检索失败',
  retry: '重试',

  tab: {
    retrieval: '外部检索',
    checks: '一致性校验',
  },

  kind: {
    COMPANY: '公司',
    LAW: '法规',
    CASE: '案例',
  },

  mentions: '{count} 处',
  mentionsTitle: '文中出处',
  shareholders: '股东出资',
  moreCandidates: '其余候选',
  caseSection: {
    ascertain: '查明事实',
    reason: '裁判理由',
    result: '裁判结果',
    gist: '裁判要旨',
    fullText: '判决书全文',
  },

  // 法宝升级件：引用校验回填的权威条文原文 / 案号识别先导步
  authoritative: '权威原文（北大法宝）',
  implementDate: '施行日期 {date}',
  recognition: '案号识别',
  openInPkulaw: '在法宝打开',

  // 两类引用发现。候选可能来自旧版法规（条文会重编号），只提示人工核对，不给一键修改。
  citation: {
    citedText: '引用条文',
    candidates: '按内容定位到的条文',
    article: '第 {n} 条',
  },

  severity: {
    warn: '存疑',
    error: '错误',
  },
  unifyTo: '统一为 {value}',
  cannotFix: '不能一键修改：{reason}',
  fixNotUnique: '未能唯一定位，请手动修改。',
  fixPartial: '已修改 {done} 处，另有 {failed} 处未能唯一定位，请手动修改。',
  fixed: '已修改',
  fixedHint: '可用 Cmd+Z 撤销；重新解析可刷新结论。',
  noEditor: '请先激活一个文档编辑窗口',

  empty: {
    noRun: '这份文档还没有解析过',
    noRunHint: '点右上角「解析」，AI 会通读全文、抽出公司/法规/案例并检索外部库，同时做一遍前后一致性校验。',
    noEntity: '没有抽到可检索的实体',
    noEntityHint: '文档里没有出现公司名、法规条号或案号。',
    noFinding: '没有发现前后矛盾',
    noFindingHint: '数量陈述与统一社会信用代码都校验通过了。',
  },
}
