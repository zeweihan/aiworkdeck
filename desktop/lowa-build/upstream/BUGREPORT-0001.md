# 0001 提交材料：bugzilla 报告 + gerrit 提交

维护者按下面三步走即可，正文都是可直接粘贴的成品。**账号登录与提交必须本人操作**
（建账号、签 gerrit 的许可声明都是法律动作，不由 AI 代劳）。

---

## 第 1 步：提 bug（约 3 分钟）

打开 https://bugs.documentfoundation.org → 登录（没有账号先 New Account）→
File a Bug → LibreOffice → 按下表填写。**Component 选 Writer**。

**Summary**

```
Tracked deletions shown in margin overlap the neighbouring cell when the change is inside a table
```

**Description**（整段粘贴）

```
When "Show changes in margin" is enabled (Tools > Options > LibreOffice Writer >
View > "Tracked deletions in margin"; tdf#34355, LO 7.1+), the shortened text of
a tracked deletion that lives inside a table is painted INSIDE the neighbouring
cell instead of in the page margin. In body paragraphs the same feature works as
intended.

In the attached screenshot the body deletion ("This bod...") is correctly placed
in the left page margin, while the deletion from the third column ("[2024] 41")
is drawn inside the second column's cell. When that cell's own text is long
enough -- the normal case in real documents -- the two overlap and neither is
readable, which makes the feature unusable for any document containing tables.

Steps to Reproduce:
1. Open the attached document (a body paragraph with a tracked deletion, plus a
   3-column table whose third cell carries a tracked deletion + insertion).
   Alternatively: create a table, enable Edit > Track Changes > Record, and
   delete some text inside a cell that is NOT in the leftmost column.
2. Enable Tools > Options > LibreOffice Writer > View > "Tracked deletions in
   margin".
3. Look at the table row.

Actual Results:
The deleted text of the in-table change is drawn inside the cell to its left
(overlapping that cell's own text whenever it is long enough). The deletion in
the body paragraph is placed correctly in the page margin.

Expected Results:
The deleted text of an in-table change should be placed in the page margin, next
to the "changed line" mark -- the same place the change bar for that row already
uses, and the same behaviour as for body paragraphs.

Reproducible: Always

User Profile Reset: n/a (rendering behaviour, not profile dependent)

Additional Info:
Root cause looks straightforward. In SwExtraPainter::PaintExtra
(sw/source/core/text/frmpaint.cxx) the margin text is right-aligned at m_nX,
which is the TEXT FRAME's left edge -- and inside a table that frame is the CELL:

    Point aTmpPos( m_nX, nY );
    aTmpPos.AdjustY(nAsc );
    if ( pRedlineText )
    {
        Size aSize = pTmpFnt->GetTextSize_( aDrawInf );
        aTmpPos.AdjustX( -(aSize.Width()) - 200 );
    }

The "changed line" mark in the same class already handles this: its x position
(m_nRedX, set in the SwExtraPainter constructor) is derived from
pFrame->FindTabFrame() when the frame is inside a table. Doing the same for the
redline text fixes the overlap; a patch is attached.

Observed with an unmodified 24.2.8 build of this code; the code path in master is
unchanged as of 2026-08-02, so master is affected as well.
```

**附件**（Attach 两个文件，都在本目录）

| 文件 | 说明填这句 |
|---|---|
| `repro-margin-table.docx` | `Repro document: tracked deletion inside a table cell plus one in a body paragraph` |
| `screenshot-overlap.png` | `Deleted text of the in-table change painted over the neighbouring cell` |

提交后记下 bug 号（形如 `167xxx`）。

---

## 第 2 步：把 bug 号补进补丁（10 秒）

`0001-margin-redline-table-anchor.patch` 里注释首行改成：

```
        // tdf#NNNNNN: m_nX is the text frame's left edge, which inside a table is
```

（`NNNNNN` 换成第 1 步拿到的号。）

---

## 第 3 步：提交到 gerrit（首次约 15 分钟，之后 2 分钟）

首次需要：TDF 账号登录 https://gerrit.libreoffice.org、在 Settings 里加 SSH key、
并同意 contributor 许可声明（**这一步是法律声明，必须本人操作**）。

```bash
git clone https://git.libreoffice.org/core lo-core && cd lo-core
git apply /path/to/0001-margin-redline-table-anchor.patch
./logerrit setup            # 配置 gerrit remote 与 commit-msg hook
git checkout -b margin-redline-table-anchor
git commit -a -F - <<'MSG'
tdf#NNNNNN sw: anchor margin redline text at the table frame

In ShowChangesInMargin mode the shortened text of a tracked deletion was
right-aligned at m_nX, the text frame's left edge. Inside a table that frame
is the cell, so the text was painted over the neighbouring cell's content.

Anchor at the table frame's left edge instead, which is what the "changed
line" mark in the same class already does (m_nRedX in the constructor).
Body paragraphs are unaffected.

Change-Id: I0000000000000000000000000000000000000000
MSG
./logerrit submit master
```

（`Change-Id` 那行留着占位即可——`./logerrit setup` 装的 commit-msg hook 会在
commit 时自动替换成真值；若报缺 Change-Id，执行 `git commit --amend --no-edit`
让 hook 补上。）

提交后在 gerrit 页面把 Caolán McNamara 或 László Németh（tdf#34355 原作者）加为
reviewer，走审阅流程。

---

## 合入之后

1. 从 `patches/apply-source-patches.py` 摘掉第 4 条补丁
2. `RECIPE.md` r3 节记一笔「已上游，自 X.Y 起无需自带」
3. `upstream/README.md` 把 0001 标记为 done
