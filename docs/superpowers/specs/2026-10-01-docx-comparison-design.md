<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 两份 DOCX 生成独立比对稿（dev-board#1120）

用户目标：选择基础文档和新文档，逐句逐段看到增删与移动，合并两份文档的批注；结果是可保存、重开、继续编辑的 DOCX。暂不发版。补充验收要求：2000 页比对、显示与保存稳定性，尽量降低实现复杂度。

## 调研与选择

现有文件树入口走 DocDiffViewer 文本差异；版本历史已有 LOWA `.uno:CompareDocuments`。沿用原生比较、排版、修订接受/拒绝及普通编辑器，不自建全文比较算法。

官方来源：
- https://help.libreoffice.org/latest/en-US/text/shared/guide/redlining_doccompare.html （加载新版，与基础版比较，产生可接受/拒绝的增删）
- https://help.libreoffice.org/latest/en-US/text/shared/optionen/01040800.html （字符粒度）
- https://github.com/LibreOffice/core/blob/libreoffice-24-2/sw/source/core/doc/docredln.cxx （isMovedImpl 要求至少六字且有内部空格，中文移动会漏判）
- https://github.com/LibreOffice/core/blob/libreoffice-24-2/sw/source/core/unocore/unoredline.cxx （RedlineMovedID 可读，不可设置）

本机 r5 实证：中文句内可细到单字；原生比较会保留旧批注正文但可能丢锚点；标准 DOCX moveFrom/moveTo 导入后移动 ID、颜色、保存重开和接受/拒绝有效。原生只改格式的比较不生成格式修订，保留新版排版，不声称提供逐项格式审计。

## 实现边界

1. 现有保存屏障先保存两份打开且脏的来源；下载快照并计算 SHA-256。对话框保留基础/新版方向和进度、重复提交与失效围栏。
2. 借一个临时 LOWA 实例生成真实修订并导出，随后释放。比较按双方最终正文处理已有修订，仅处理副本；新原语与版本历史的只读 compare_document 分开。
3. 后端只为该入口处理 DOCX：验证双向正文投影，补唯一确定的中文移动标记、按原锚点重建批注并集。重复文字不猜移动。迁移到移动目的地的旧批注避免原位置留下空白段；普通删除后的批注仍由原生引擎保留。
4. 原子创建新文件：源文件同项目、未删除、DOCX、哈希未变化；基础文件同目录，服务器生成不重名结果名；先暂存完整字节再创建文件行，失败和事务回滚清理临时产物。成功后版本记录与索引沿用现有路径。
5. 打开普通 LibreOfficeEditor，复用自动保存、审阅、关闭保护。其他文件类型继续文本比较。无法获取引擎的 DOCX 明确失败，不静默退化成文本差异。

## 验证计划

- 中文单字、句内多点、整段增删/移动、方向互换、相同/空白文档、双方已有修订。
- 两侧批注内容/作者/范围、同批注去重、点批注、多锚点同 run、移动批注，原始文件哈希不变。
- 比对稿独立 ID/文件名/目录，接受/拒绝正文投影，保存重开后继续编辑与再次保存。
- 来源变化、取消、换项目、导出失败、API失败、事务回滚、文件名冲突，不留下假成功或空白结果。
- 2000 个实际分页、约 88 万字的隔离探针：比对/导出/重开/末页显示、进程 RSS、另一个页面心跳；设置时间和内存硬限。保留失败结果，不能用小样本替代通过结论。

实测与限制随交付记录更新；Word 本体互操作与所有复杂版式不能由 LOWA 探针代替。
