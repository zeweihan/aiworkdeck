# Word 审阅显示与定位验证（#586 / #587 / #588）

## 原因与实现

旧页边模式使用 LOWA 的 ShowChangesInMargin，会把被删文字画在原生窄页边区域，长删除显示不全。现在工具栏提供正文内显示修订、批注框中显示修订、最终稿三种选择。批注框模式关闭原生行内及页边标记，以最终正文排版，把完整修订内容放到纸外右侧气泡；正文内模式仍用删除线，并且右侧只显示批注，不重复修订。旧 margin 参数仅保留内部最终正文操作和兼容调用。

批注和修订气泡使用原生反向命中测试定位，以文档坐标排布、避让重叠，再按当前滚动和缩放映射到屏幕，用虚线连接原文。原汇总审阅面板保留并跟随所在条目。定位不移动真实光标、不改文档、不触发自动保存。画布尺寸变化由 ResizeObserver 通知原生窗口，替代每秒无条件发送的 resize；隐藏画布恢复时也会重绘。

AI、补全、即时审校仍按最终正文读写。视图切换不改变内容版本，但必须清空段落范围缓存：整段删除会改变段落成员，否则会留下空段落及错误索引。处置气泡中的修订后恢复用户所选模式，准备阶段刷新失败也恢复原模式。

导入 DOCX 的整表插入可能被拆成多个单元格 redline。仅在同表、同作者、同精确时间、覆盖全部非空单元格且无同表删除项时聚为一次表格插入；不同表和普通局部修改不混并。

批注支持编辑、删除、解决/重新打开。删除批注时临时关闭修订记录，避免删除字段产生幽灵修订。数字字符串 ID 按原生 Name 查找，不作为位置索引。编辑带原文及版本校验；替换文档取消旧草稿；版本对比不提供修改操作，worker 同时拒绝只读文档的修订处置。带 revision 的单条/批量/全部处置在切换视图前拒绝过期请求；旧调用不传 revision 时仍兼容，不能据此宣称其列表读取与处置之间的竞态已全部消除。

## 原生错误根因与回归

list_revisions 和 debug_revisions 旧实现先调用 SwXRedline.getString()，捕获异常后才从正文区间取字。该对象的文本游标要求存在隐藏内容段；行内修订没有这个段，调用会在原生层抛异常。公开源码见 [SwXRedline::createXTextCursor / RedlineText](https://github.com/LibreOffice/core/blob/master/sw/source/core/unocore/unoredline.cxx)。

本机实测中，连续滚动、缩放、批注改写、修订处置和往返导入后，旧读取路径出现 memory access out of bounds；同一引擎的跨 Writer / Calc / Impress 回归还在组 23 的 PPTX 重开超时。PPTX 导出件在全新引擎中可打开，文件本身并未损坏。删除空闲重绘、修补测试监听器、改批注删除方式和避免旧 redline 引用复用均未解决。

修复为先读取可为空的 RedlineText，只在存在隐藏内容时读取其中的文字；否则直接使用 RedlineStart/End 的正文范围。只替换这个读取方式的 Word 对照实验通过；两处调用点均修复后，完整原生回归 548 项全部通过。没有禁用内存回收，没有更换引擎，也没有用跳过失败用例代替修复。

同时纠正 Word 专项测试的往返验证：导出字节必须在浏览器内 Array.from 后再返回测试进程，否则类型化数组会变成普通对象，load_document 返回 empty 而不真正加载。现在断言导出字节非空、加载未走 empty 分支、显示模式已复位，并检查往返后的真实修订内容。

## 已通过

环境：本机安装包 LOWA（soffice.wasm SHA-256 `923d4ef01fe28b33ed327b0d51e9202aac4c7ea3dff8dcb47edc4d952e268dbb`），Chrome 152。

- 单元测试 145 项：分组、显示状态、无效原生读取保护、画布尺寸生命周期、补全、即时审校等。
- Chromium 生命周期测试 5 项：跨文档草稿、迟到响应、只读操作、状态变化、行内/气泡模式切换。
- Word 原生专项：长删除/长批注全文、纸外列、12 个锚点、虚线、跨页滚动、缩放、选区保持、两张表独立分组、气泡模式隐藏原生删除标记、编辑/删除及撤销重做、真实 DOCX 往返、整组接受、删除拒绝、过期版本保护、只读对比、整段删除缓存、连续退格和中文输入。
- 完整原生混合文档回归：548 passed / 0 failed。
- 补全原生全套、即时审校原生全套。
- zetaoffice / H5 构建、109 个 Vue 文件的 emit 检查、25 个语言命名空间键对拍。

复现命令（仓库根目录）：

```sh
npm --prefix frontend run build:zetaoffice
node --test frontend/tests/revision-view/*.test.mjs frontend/tests/project-home/review-*.test.mjs frontend/tests/completion/*.test.mjs frontend/tests/inline-review/*.test.mjs
node --test frontend/tests/revision-view/word-review-lifecycle.mjs
LOWA_ENGINE_DIR='/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa' LOWA_E2E_PORT=8941 node frontend/tests/lowa-e2e/word-review.mjs
LOWA_ENGINE_DIR='/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/lowa' LOWA_E2E_PORT=8944 node frontend/tests/lowa-e2e/run.mjs
```

## 边界

复杂分栏、浮动框、页眉页脚锚点和超大批注量未完成原生覆盖。无法精确命中的项保留在审阅列表并提示，不绘制猜测连线；单次列表上限 500 条。不能声称与 Word 完全等价。

测量方法及根因证据同步到共享知识 PR：https://github.com/zeweihan/zeweiandmasterC/pull/4 。
