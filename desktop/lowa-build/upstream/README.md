# 待回馈上游的补丁 / Patches to contribute upstream

我们自建引擎里带的修复，凡是**通用问题**（不是 AI Workdeck 特有配置）都该推回上游，
将来升引擎就不用自己背。这里放的是可直接提交的补丁与提交说明。

提交需要账号与人工审阅，**必须由维护者本人操作**（这类对外动作不由 AI 代劳）。

---

## 0001 — LibreOffice core：页边修订在表格内叠画邻格

- **文件**：`0001-margin-redline-table-anchor.patch`（针对 `sw/source/core/text/frmpaint.cxx`，
  基于 GitHub 镜像 master 生成，2026-08-02 核对时 master 与 24.2 该处代码一致，可直接套）
- **问题**：开启「在页边显示修订」（tdf#34355，LO 7.1+）后，**表格单元格内**的删除文本
  被画在左邻单元格的正文上。根因是 `SwExtraPainter::PaintExtra` 用 `m_nX`
  （文本框左缘）作锚点右对齐绘制——表格内那个框就是单元格。
- **修复**：锚点改取 `FindTabFrame()` 的整表左缘，与同文件里「改动竖线」`m_nRedX`
  在构造器里的既有做法一致。约 6 行。
- **上游状态**（2026-08-02 核对）：master 未修；bugzilla 未搜到对应报告。
- **提交材料已备好**：见 `BUGREPORT-0001.md`——bugzilla 的 Summary/Description 全文
  可直接粘贴，附件两个（`repro-margin-table.docx` 最小复现文档、`screenshot-overlap.png`
  未修复引擎上的实拍证据：正文删除正确进左页边、表格删除被画进左邻单元格），
  gerrit 提交命令与 commit message 也一并写好。只剩「登录 + 粘贴 + 点提交」。
- 合入后，下次跟随上游升引擎时即可从 `patches/apply-source-patches.py` 里摘掉第 4 条。

## 0002 — zetajs：typedef 类型不解析导致结构体编组失败

- **文件**：`0002-zetajs-resolve-typedef.patch`（针对 `source/zeta.js`，仓库
  https://github.com/allotropia/zetajs）
- **问题**：`translateTypeDescriptionAndDelete` 不处理 `TypeClass.TYPEDEF`，凡是成员为
  typedef 的结构体（如 `com.sun.star.util.Color` → long，进而 `BorderLine2` /
  `TableBorder2`）双向编组都抛 `bad type description`。表格边框类原语会直接踩到。
- **修复**：TYPEDEF 分支解析到 referenced type 递归处理。约 11 行。
- **上游状态**（2026-08-02 核对）：上游 main 的 `source/zeta.js` **与我们 vendored 版本
  逐字节一致，只差这一处修复**——也就是说上游没有我们缺的东西，反而缺我们这一条。
  **结论：zetajs 无需升级；贸然拉上游反而会丢掉本修复。**
- **提交方式**：GitHub PR 到 allotropia/zetajs（MIT 许可，无 CLA 门槛）。

---

## 维护约定

- 新增自维护补丁时，判断一下是不是通用问题；是就在这里加一节，附上游状态与提交路径。
- 上游合入后，摘掉 `patches/apply-source-patches.py` 里对应条目，并在 RECIPE.md 记一笔。
