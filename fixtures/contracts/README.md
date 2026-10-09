# fixtures/contracts — #1075 合成夹具

**禁止**放入真实客户、当事人姓名、案号、手机号或内部合同。

| ID | 文件 | 覆盖点 |
|---|---|---|
| C1 | `complex-table.docx` | 合并单元格表格 + 目标保密短语 |
| C2 | `with-revisions.docx` | 已有插入/删除修订 + 管辖法院锚点 |
| C3 | `zh-en-mixed.docx` | 中英混排保密/准据法条款 |

手测步骤见 `docs/qa/1075-golden-path-checklist.md`。

生成方式：最小 OOXML（stdlib zip）；可用 Word/WPS/LOWA 打开后另存以丰富样式，但勿掺入客户数据。
