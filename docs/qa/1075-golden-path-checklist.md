# #1075 合同审查黄金路径 · 手测清单

跟踪：[#1075](https://github.com/zeweihan/aiworkdeck/issues/1075)  
夹具：[`fixtures/contracts/`](../../fixtures/contracts/)（**合成材料，禁止真实客户数据**）

## 夹具

| ID | 文件 | 覆盖点 |
|---|---|---|
| C1 | `fixtures/contracts/complex-table.docx` | 跨格合并表格、目标保密短语 |
| C2 | `fixtures/contracts/with-revisions.docx` | 已有插入/删除修订、管辖法院锚点 |
| C3 | `fixtures/contracts/zh-en-mixed.docx` | 中英混排条款 |

## 手测步骤（每份夹具各跑一遍）

1. 桌面端打开夹具（zh-CN）
2. 确认无英文 LO 整条菜单栏（#66 选项 2；PR-A `#1086` 重载后仍隐藏）
3. AI：改一处关键条款（锚点定位，勿盲替换）
4. 在修订 UI：accept 一条、reject 一条  
   - 优先：自建工具条「接受/拒绝」当前条（#66 PR-B `#1088`，合入后）  
   - 回退：审阅面板 / 工作台菜单逐条或全量
5. 保存 → 关闭标签 → 重开同一文件
6. 用 Word 或 WPS 打开导出/落盘文件
7. 记录：段落/表格是否崩、修订是否可解释、关键句是否仍在

## 结构断言（能自动化再升 CI）

- [ ] 重开后段落数在预期容差内
- [ ] 目标短语仍可 `find_text_locations` 命中（C1 保密二十四个月；C2 徐汇区人民法院；C3 二十四（24）个月保密）
- [ ] 无 DOC_REJECTED / 打开失败终态

## 本周交付

- [x] 本清单进仓（本文件）
- [x] 合成夹具 C1/C2/C3 进仓
- [ ] 至少 C2 手测绿（可在 PR-B 合入后用工具条路径）
- [ ] 发版前门禁：勾选本清单（CI 结构断言可后补）
- [ ] 5 分钟录屏 → #66 PR-C / showcase（**非本 PR**）

## 非目标

- 不在本清单创作全新审查 Skill 文案
- 不扩到鸿蒙或其他壳
