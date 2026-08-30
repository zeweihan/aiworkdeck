# 插件生态 P4：声明式长尾——体裁/模板/HOUSE 贡献点 + manifest.settings（设计稿）

> dev-board#284，延续 #275 生态路线（docs/PLUGIN_API_ROADMAP.md §3 P4）。
> 对位 VS Code 的 languages/snippets/themes——生态数量大头是**零代码声明式插件**，
> 也最贴近「律师/会计师/文员 + AI 写插件」的作者画像。本篇是形状定稿，实施另开工。

## 1. 设计原则

- 全部走 manifest 新顶层字段 `contributes`（P3 的 evidenceSources 也挂这里，一处收口）；
- 声明式内容**不进 JVM、不跑脚本**：纯数据文件（JSON/docx/md），风险量级最低，
  广场受理可以走最轻的审核档；
- 老宿主忽略未知字段 + `minHostVersion`（P0）兜底，只加不改。

## 2. 贡献点一：样式画像（styleProfiles）

```json
"contributes": {
  "styleProfiles": [
    { "id": "sh-court-filing", "name": "上海法院诉讼文书", "file": "profiles/sh-court.json" }
  ]
}
```

- `file` 相对插件目录，内容为 styleProfile v1 JSON（与 `_模板/画像.json` 同格式，
  解析走既有 `StyleProfiles.parse`，merge 到 house-default 之上——单源不破）；
- 接入点：`StyleProfileResolver` 解析顺序**插在**「项目 `_模板/画像.json`」与
  「SystemSetting `dd.styleProfile.default`」之间新增一档「用户显式选中的插件画像」；
  选中状态存 `system_setting`（`ai.styleProfile.selected` = `<pluginId>.<id>`），
  UI 落在模板/画像既有选择入口；
- 插件禁用 → 该画像不可选，已选中的回退默认链并记 WARN（不炸文档导出）。

## 3. 贡献点二：文书模板（templates）

```json
"contributes": {
  "templates": [
    { "id": "labor-contract-v2", "name": "劳动合同（标准版）",
      "genre": "contract", "file": "templates/labor-contract.docx",
      "description": "适配 2026 劳动法修订", "language": "zh-CN" }
  ]
}
```

- `file` 支持 docx/md；宿主在「新建文件」与项目模板选择处列出（来源标注插件名）；
  AI 侧经 `list_files` 同款只读工具面暴露「可用模板清单」（新工具 `list_contributed_templates`，
  实施期定名），模型可按 genre 挑模板起草；
- `genre` 是自由字符串 + 宿主维护一张建议值表（contract/pleading/opinion/report/letter…），
  不做硬枚举——体裁本身就是长尾；
- HR 用工模板包 v2.0（30 份，现在是散文件交付）是第一个改造对象：打成一个纯声明式
  插件包，广场可装可卸——**这就是这一期的狗粮**。

## 4. 贡献点三：设置（settings）

```json
"settings": [
  { "key": "apiRegion", "type": "select", "label": "数据源区域",
    "options": ["cn", "intl"], "default": "cn" },
  { "key": "autoRefresh", "type": "boolean", "label": "自动刷新", "default": true }
]
```

- 顶层字段 `settings`（不挂 contributes——它描述插件自身配置，不是向宿主贡献内容）；
- `type` ∈ `string | boolean | number | select`；上限 20 条；`secret: true` 的项
  渲染为密码框、读取时不回显全文（只回显尾 4 位）——但**平台方向是免配置**
  （[[feedback-official-edition-only]]：主线全走平台 Credits），secret 项主要服务
  自部署数据源类插件；
- 存储：既有 Settings SPI 前缀 `plugin.<id>.` 之下（`plugin.<id>.settings.<key>`），
  JAR 插件经 `host.settings().get()`、Web 插件经桥 `settings.get {key}`（新方法，
  只读自己的、无独立权限）读取；写入只经宿主设置表单（插件广场详情页渲染），
  插件自身不可写——配置权在用户手里；
- 桥推送：设置变更时向已打开的面板推 `{type:'event', event:'settings.changed'}`
  （P1 事件通道的自然扩展）。

## 5. 贡献点四：l10n 字符串表

- 插件目录 `l10n/en.json` / `l10n/zh-CN.json`；manifest 与 contributes 里的
  `name`/`description`/`label` 值支持 `%key%` 引用（VS Code 的 package.nls.json 机制）；
- 宿主按当前应用语言解析，缺键回退声明原文；与既有 skill 的 `name_en`/`languages`
  机制并存不合并（skill 那套已稳定，不动）。

## 6. 与四类插件形态的关系

声明式贡献点是**第五种能力维度**，可叠加在任何形态上：纯声明插件（只有 manifest +
数据文件，零代码）、Web 插件带设置、JAR 插件带模板，全部合法。校验规则：
纯声明插件（无 backendJars/frontendEntry/skills）走 dev 免签直装通道也放行——
它比纯 Web 插件风险更低（连脚本都没有）。

## 7. finalization 三问

1. **真实插件**：HR 模板包 30 份（狗粮）；样式画像的真实需求来自尽调模块
   （lockModel/画像单源已在用）；settings 的第一个用户是会议录音类需要区域配置的插件。
2. **能跑的示例**：实施期 examples/ 加 `hello-declarative-plugin`（一个模板 + 一个画像 + 两条设置）。
3. **过窄/过宽**：贡献点逐个独立发布（templates 先行，settings 次之，l10n 最后），
   每个贡献点落地前单独过三问；`contributes` 容器本身只加不改。

## 8. 实施拆单（另开工，按贡献点分 PR）

1. PR-a：`contributes.templates` + 新建入口接线 + HR 模板包插件化（狗粮）；
2. PR-b：`settings` + 广场表单渲染 + 桥 `settings.get` + `settings.changed` 事件；
3. PR-c：`contributes.styleProfiles` + Resolver 插档；
4. PR-d：l10n 字符串表。每个 PR 各自升 PLUGIN_SPEC 小版本。

## 9. 落地实况（2026-08-30 追记）

已随规范 v2.9 一次性落地（未按 §8 分四个 PR——四点共用同一套解析/服务/表单基建，拆开反而重复）。
与本设计稿的实现差异：设置值直存 `plugin.<id>.<key>`（与 SPI Settings 同命名空间，JAR 免转接，
废弃 §4 的 settings. 子前缀）；新增 `settings.changed` 桥事件；dev 直装仍要求 frontendEntry
（§6 的「纯声明插件 dev 放行」未做——走广场/手动安装，需求出现再补）。权威形状以
PLUGIN_SPEC §14 为准。
