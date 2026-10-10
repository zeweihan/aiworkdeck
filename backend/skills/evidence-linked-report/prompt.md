<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
## 证据闭环报告

本轮交付物是一份依据项目材料得出结论的报告或备忘录。每一条结论都要能被律师一键点回原文核对；缺证据的结论要在正文里说清楚缺什么。

1. **先读材料、建依据清单**：按事项用 `search_project_content` / `extract_file_text` 读取相关项目文件。逐条记下拟写的结论、来源文件路径、能直接支撑它的**原文句子（逐字摘录）**，并标为 `supports`（直接支撑）、`partial`（只支撑一部分，如只有数额没有时点）或「缺证据」。草稿、对话陈述和你的推断都不是来源。

2. **写进 Writer 文档**：用户已在编辑器打开报告时直接写入；否则用 `write_docx` 新建（返回 `db_id`），再 `doc_open_file(db_id)` 打开——证据链接只能建在编辑器当前打开的 Writer 文档里。每条结论写成一句完整、在全文中只出现一次的话，便于下一步定位。

3. **逐条挂链接（不要等最后、不要只挂一部分）**：每条 `supports` / `partial` 结论写完后立即调用 `doc_link_evidence`：
   - `docFileId` = 报告的文件 ID；`anchorQuote` = 报告里这条结论的原句（须恰好出现一次，命中 0 或多处会被拒——改用更长片段或先 `doc_find_text` 取 `anchorId`）；
   - `targetsJson` = `[{"path":"<文件树相对路径>","locator":{"type":"docx","quote":"<来源原文逐字摘录>"},"relation":"supports|partial"}]`，PDF 用 `{"type":"pdf","page":N,"quote":"..."}`；
   - `quote` 要选来源文件里**唯一**、不跨表格单元格的一句，否则跳转高亮会落空或落错；
   - 一条结论有多个来源就在同一次调用里给多条 target，不要拆成多次。
   `partial` 的结论在正文里同时写明尚缺的那一部分。

4. **缺证据只写正文、不挂链接**：找不到直接证据的结论不得写成定论，改写为「【待补：缺少××（如付款凭证）】」并说明需要补什么材料；**不要**对它调用 `doc_link_evidence`，也不要把相近材料硬挂成依据。缺证据 ≠ 事实未发生。

5. **收尾自查**：用 `doc_list_evidence(docFileId=…)` 核对：每条 `supports`/`partial` 结论各有一条链接、状态不是 `orphan`，「待补」项一条链接都没有。交付时简述：已链接几条、哪些是 partial、哪些待补以及缺什么材料。AI 建的链接状态为「待核对」，提醒用户点开核对。

条款、费用或协议类表述一律只写「以协议为准」，不代拟法律效力文本。
