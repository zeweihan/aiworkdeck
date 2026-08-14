# 前端 i18n 迁移约定（翻译/抽取工作的执行基准）

与 glossary.md 配套：glossary 管「译成什么」，本文管「怎么抽、怎么接线」。

## 架构

- vue-i18n 9（legacy 模式）在 `src/i18n/index.js` 创建，`main.js` 里 `app.use(i18n)`。
- locale 文件按命名空间分文件：`src/locales/zh-CN/<ns>.js` 与 `src/locales/en-US/<ns>.js`，
  各自 `export default { key: '...' }`；`src/locales/zh-CN/index.js` 聚合。
  **两种语言的同名文件键集合必须完全一致**（校验脚本会对拍）。
- 组件内：模板 `{{ $t('ns.key') }}`、属性 `:placeholder="$t('ns.key')"`、脚本 `this.$t('ns.key')`。
- 非组件 JS（utils/composables/config/services）：`import { t } from '@/i18n'`。
- 语言切换 = 整页 reload（admin.vue 语言项触发）。因此模块加载期取值（config 里的
  label 数组等）是安全的，可以直接在模块顶层调 `t()`。
- 语言判定唯一来源 `utils/appLanguage.js`；**禁止** uni.getLocale()/navigator.language。

## 抽取范围（什么算用户可见）

要抽：模板文本节点、placeholder/title/confirmText 等属性、uni.showToast/showModal 文案、
错误提示、状态文案、config 的 label/description、Date 格式里的中文（「今天/昨天」等）。

不抽（保持原样）：
- console.log / console.warn / console.error 与注释（注释保持中文）。
- 埋点事件名与属性值、API 字段值、storage 键、CSS 类名、e2e 锚点类名。
- 后端返回的动态 message（透传显示，后端侧另行处理）。
- 用户数据（文件名、项目名、文档内容）。
- `glossary.md` 里标注「不译」的品牌名。

## 键名

- `<ns>.<camelCaseKey>`，ns 与文件对应（如 `fileTree.deleteConfirm`）。
- 完整句子一个键，不拼接碎片。变量用具名插值：`{ count }`、`{ name }`。
  - zh: `已选中 {count} 个文件` / en: `{count} files selected`
- vue-i18n 消息语法转义：文案里的字面量 `@` `|` `{` `}` 要写成 `{'@'}` 形式。

## 翻译

- 一律以 `glossary.md` 为准；未收录术语先补 glossary 再用。
- Title Case 用于短标签（按钮/菜单/栏目），sentence case 用于句子提示。
- 禁 emoji。en 文案不得出现中文标点（，。：「」）。

## 验证

每片改完必须：`npm run check:emits` 通过；改动文件无漏抽的用户可见中文
（grep 中文后逐条核对是否属于「不抽」清单）；键对拍脚本通过。
