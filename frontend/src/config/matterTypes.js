/**
 * 法律事项分类——项目档案「事项类型」字段的候选值。
 *
 * 这 11 个值必须与后端 MatterClassifierService.PROMPT_PREFIX
 * （backend/src/main/java/com/checkba/service/telemetry/MatterClassifierService.java:31）
 * 的类别表逐字一致：Plan 2 的档案 AI 抽取会复用同一份 prompt，两边漂了就会出现
 * 「AI 填进来的值不在下拉里」。Plan 2 上线时以后端为准。
 */
export const MATTER_TYPES = [
  '公司治理',
  '资本市场证券',
  '并购交易',
  '争议解决',
  '合同审查起草',
  '合规监管',
  '知识产权',
  '劳动人事',
  '破产重整',
  '其他法律事务',
  '非法律事务',
]
