<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# 2026-10-10 Word 与确认卡片回归记录

关联 PR #1104；私有台账 #1172–1175。测试输入全部为合成文字。无发版、部署或正式客户端升级。

## 粘贴与视图

主任务在真实 LOWA r5 中经键盘、剪贴板和 IME 覆盖层粘贴 17,891 字符 / 180 段；同样本旧 worker 的 `replace_selection` 为 13,749ms，批量锁修复后为 313ms。扩展轮普通剪贴板 427ms、同时含 HTML/plain 的剪贴板 275ms、17,610 字符单段 1,030ms。均为单次样本，不表示稳定性能分位数；HTML 用例沿用文本粘贴语义。后续键入、全部段落分页回读、DOCX 导出重开全文一致且保留修订。

真实引擎复现格式标记开启时的辅助矩形；只关闭 ShowTextBoundaries 后矩形消失，格式标记、横竖标尺、页边距与页面尺寸保持，clean/dirty 原状态不变。回归命令为 `npm run test:lowa-paste-large` 和 `npm run test:lowa-view-boundaries`，环境路径按测试脚本提供 LOWA_ENGINE_DIR / LOWA_FONT_DIR。

## 完整 macOS 桌面实例

在独立 Electron userData、H2 与存储目录启动当前前端源码、LOWA r5 和本机已安装后端，沙箱阻止读取用户业务数据及外部网络；原安装客户端进程保持运行。通过 UI 新建项目及 Word，使用系统剪贴板和 Cmd+V 粘贴 21,671 字符 / 180 段。首次粘贴加全文回读约 1,067ms，自动保存后下载实际 DOCX 并核对全部段落，关闭重开一致。

- 上传请求暂停 11 秒：固定栏保持“保存中…”，标签仍在，不出现失败对话框；重复关闭只产生一次上传。释放后正常关闭，磁盘和重开正文含新增 Q。
- 上传返回 HTTP 500：保留 dirty，出现继续编辑/放弃入口。选择继续编辑并点击重试后保存成功，磁盘和重开全文含 QF。
- 未编辑直接关闭：无额外上传。最终 21,673 字符 / 180 段全文一致。

本地合成验收产物位于 `/tmp/awd-word1172-runtime/`：`flow.mjs`、`flow-result.json`、`logs/flow.log` 和保存状态截图。剪贴板原内容只在内存暂存并恢复，未写入日志或文件。自动化必须定位活动 webview 并聚焦宿主和输入层；编辑器 ready 早于工具栏 bootstrap 完成，视图断言需等实际偏好生效。

## 确认卡片与回归范围

嵌套 ask_user/question/options 经实际 ChatInterface 的合成流式响应验证：正文不泄漏协议外壳，卡片选择、提交请求及历史选中态正常；合法代码示例保留。测试 `node tests/chat-presentation-ui/ask-user.mjs` 通过。兼容解析仅处理显示，不授予工具执行权限。

- LOWA 引擎整套：607 / 607。
- project-home（合入确认卡片前）：1,329 / 1,329。
- 相关编辑器、修订视图、重载单测：300 / 300。
- 最终确认卡片和保存专项：33 / 33；新增用例曾在旧源码转红。
- build:zetaoffice、build:h5、SPDX、locale/emit 契约通过。

主任务核对所有 diff 与实际测试输出。两个 Codex 子代理交叉复核各自未实现的粘贴/视图与保存改动；其中粘贴锁验证包括 32 组受控 UNO 时序。此复核不等于第二位代理独立运行真实引擎。

未验证：升级后的正式安装包、Windows 运行、大型复杂表格/脚注粘贴、所有撤销及修订双向还原。完整桌面测试关闭了隔离实例的 AI 审阅偏好，未测试真实模型服务。CI 构建结果以 PR 检查为准。
