# Word 审阅显示与定位验证（#586 / #587 / #588）

## 原因与实现

旧默认使用 LOWA 的 ShowChangesInMargin：被删文字移到原生窄页边区域，长删除显示不全。默认改为全部修订，正文内保留完整删除线；右侧卡片也保留全文。AI、补全、即时审校仍按最终正文读写，避免把已删内容当作现行条款。视图切换独立于内容版本，但必须清空段落范围缓存：整段删除会改变段落成员，缓存不清会留下空段落和错误索引。

批注原先使用引擎内的注释窗。现在把卡片放到文档画布右侧的独立列，用原生反向命中测试定位锚点，以文档坐标排布、避让重叠，再按当前滚动和缩放投影到屏幕。虚线连接原文；原汇总审阅面板保留并跟随所在条目。定位过程不移动真实光标、不改文档、不触发自动保存。

导入 DOCX 的表格插入可能被拆成多个单元格 redline。只有同表、同作者、同精确时间、覆盖全部非空单元格、无同表删除项时才聚为一次表格插入；不同表或普通局部单元格修改不混并。

批注支持编辑、删除、解决/重新打开。删除批注时临时关闭修订记录，避免删除字段本身产生幽灵修订。数字字符串批注 ID 按原生 Name 精确查找，不作为位置索引。修改带原文及版本校验；替换文档取消旧草稿；版本对比卡片不提供修改操作。

## 已通过

环境：本机安装包内 LOWA（soffice.wasm SHA-256 `923d4ef01fe28b33ed327b0d51e9202aac4c7ea3dff8dcb47edc4d952e268dbb`），Chrome 152。

- 单元测试 121 项：审阅分组、显示方式、补全和即时审校等。
- Chromium 生命周期测试 4 项：跨文档草稿隔离、迟到布局响应、只读操作、只读状态变化。
- Word 实机专项：完整长删除/长批注、12 个原始锚点、同一行两条批注、跨页滚动、虚线、缩放、保留选区、两张表分别聚合、编辑/删除及撤销、DOCX 往返、整组接受、删除修订拒绝、过期版本拒绝、只读对比、整段删除缓存、默认内联态连续退格和中文输入。
- 补全实机全套、即时审校实机全套。
- zetaoffice / H5 构建、emits / locales 检查。

复现命令（在仓库根目录）：

```sh
npm --prefix frontend run build:zetaoffice
node --test frontend/tests/revision-view/*.test.mjs frontend/tests/project-home/review-*.test.mjs frontend/tests/completion/*.test.mjs
node frontend/tests/revision-view/word-review-lifecycle.mjs
LOWA_ENGINE_DIR='/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa' LOWA_E2E_PORT=8941 node frontend/tests/lowa-e2e/word-review.mjs
```

## 未通过与边界

完整 `frontend/tests/lowa-e2e/run.mjs` 在同一个引擎内反复混合打开 Writer / Calc / Impress，组 23 的 PPTX 导出成功，但再次导入出现 180 秒超时，部分实验还出现 WASM unaligned / unreachable。旧默认 margin 对照可完成后续用例；仅把旧 worker 默认改为 all 也能触发。移除气泡定位、增加导出 refresh、减少隐藏文档视图切换均未解决。尚未定位 native 根因，不能声称完整回归通过。

拆开的组 13+23（65 项）、21–23（126 项）、14–23（280 项）、1–20+23（271 项），以及 Impress 独立往返均通过。产品保活池按 pane:fileId 独立实例（librePool.js），与上述单实例跨类型压力场景不同；这不足以证明异常无风险。PR 保持草稿，不合并、不替换用户安装包。

复杂分栏、浮动框、页眉页脚锚点和超大批注量未完成实机覆盖。无法精确反向命中的项保留在审阅列表并显示提示，不绘制猜测连线；单次列表上限 500 条。不能声称与 Word 完全等价。

测量方法与失败路径另提交共享知识 PR：https://github.com/zeweihan/zeweiandmasterC/pull/4（尚未合并）。
