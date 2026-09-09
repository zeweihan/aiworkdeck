// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「依据」面板（InsightPane.vue，dev-board#181/#182）。
export default {
  title: '依据',

  // 头部与 run 状态
  noDoc: '未打开文档',
  parse: '全文在线核验（可能产生费用）',
  reparse: '再次全文在线核验（可能产生费用）',
  onlineHint: '点击后先保存当前文档，再在线核验已保存版本。AI 与外部检索可能产生费用。',
  saveRequired: '当前文档未能完成保存，请保存成功后再在线核验。',
  running: '正在在线核验…',
  done: '在线核验完成',
  failed: '在线核验失败',
  loading: '加载中…',
  loadingDetail: '正在取检索详情…',
  noDetail: '这一条没有检索详情',
  loadFailed: '读取在线核验结果失败',
  parseFailed: '发起在线核验失败',
  detailFailed: '取检索详情失败',
  refreshFailed: '重新检索失败',
  retry: '重试',

  // 配置类检索失败的「下一步」（dev-board#458）。这四种重试不了，给的是路不是按钮上的安慰。
  goConnectAccount: '去连接账户',
  goRecharge: '去充值',
  hint: {
    // 官方版没有法宝凭据输入框（BYOK 界面已撤），自建部署只能由管理员在服务端补。
    NO_CREDENTIAL: '该检索通道的凭据需要由部署管理员在服务端配置。',
  },

  tab: {
    retrieval: '外部检索',
    checks: '一致性校验',
  },

  kind: {
    COMPANY: '公司',
    LAW: '法规',
    CASE: '案例',
    // 第四类实体：正文里提到的、项目文件树里的那份文件（dev-board#541）
    DOC: '文档',
  },
  // 单个实体的类型徽标（浮窗 / 实体详情标签页）。与上面 kind 的分组标题分开：
  // 分组标题在英文版是复数（Companies），当徽标读起来不对。
  entityKind: {
    COMPANY: '公司',
    LAW: '法规',
    CASE: '案例',
    DOC: '文档',
  },
  // 正文 Cmd/Ctrl 点中实体后的浮窗（dev-board#541）
  openInNewTab: '在新标签页打开',
  // DOC 实体：命中项目文件时直接打开那份文件（落右侧分屏），没命中只说明情况
  openDocFile: '打开文件',
  docNotFound: '项目中未找到该文件',
  docMissing: '文件已不在项目中',
  // 检索来源的人话（浮窗 / 详情标签页的来源行）
  source: {
    projectFile: '项目文件',
    qichacha: '企查查',
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
    noRun: '这份文档尚未在线核验',
    noRunHint: '日常写作提示在本地进行。点击上方在线核验后，AI 才会通读已保存文档、抽取实体、查询外部资料并检查前后一致性。',
    noEntity: '没有抽到可检索的实体',
    noEntityHint: '文档里没有出现公司名、法规条号或案号。',
    noFinding: '没有发现前后矛盾',
    noFindingHint: '数量陈述与统一社会信用代码都校验通过了。',
  },
}
