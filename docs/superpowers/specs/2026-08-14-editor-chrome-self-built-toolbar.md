# 编辑器外壳自建化：用我们自己的工具栏替换 LibreOffice chrome

2026-08-14 立项。触发：维护者体验反馈（右上角 × 会关掉文档、整体样式不够现代）。
决策：**最终形态是自建工具栏**，前提是**体验不能退步**——LO 菜单栏/工具栏能做的，
自建这套要么覆盖，要么留得住逃生口。

领域：`doc-editor`（编辑器内核与宿主集成）。相关：`sidebar-shell`（外壳布局）。

---

## 1. 已验证的技术前提（真机 spike，2026-08-14）

用 `tests/lowa-e2e` 的服务器 + 探针注入机制，在 24.2.8-zhcn-r4 真引擎上跑通：

| 前提 | 结论 |
|---|---|
| frame 的 `LayoutManager` 属性可达 | ✅ `ctrl.getFrame().getPropertyValue('LayoutManager')` |
| 能列出 UI 元素 | ✅ 见下方清单 |
| 能隐藏 menubar / 工具栏 / 状态栏 | ✅ `isElementVisible` 由 true 变 false |
| 可逆（能再显示回来） | ✅ `showElement` 返回 true 且 visible 恢复 |
| 标尺能关 | ✅ `ViewSettings.ShowHoriRuler/ShowVertRuler` |
| 隐藏 chrome 后仍能编辑 / 派发 `.uno:` / 缩放 | ✅ 写入、select_all、format_selection、set_zoom 全部照常 |

**地雷：`hideElement()` 的返回值恒为 false，不代表失败。**必须用 `isElementVisible()`
复核，别拿返回值当成功判据（真机实测：返回 false 但元素确实隐藏了）。

引擎当前的 UI 元素全集（Writer 文档态）：

```
private:resource/toolbar/standardbar          标准工具栏
private:resource/toolbar/textobjectbar        格式工具栏
private:resource/toolbar/singlemode-{ole,draw,form,text,frame,media,
                         table,graphic,drawtext,annotation,printpreview}
                                              上下文工具栏（选中表格/图片时自动冒出）
private:resource/menubar/menubar              菜单栏（右上角那个 × 就长在它末端）
private:resource/statusbar/statusbar          状态栏
```

## 2. 分期

### P0 — 缺陷修复（已完成）

与工具栏无关、可独立发布的五条，见本次 PR：中文标点吞键、输入过程不可见、
手势缩放、保存状态胶囊、审阅面板逐字符删除合并。

### P1 — 命令层底座（worker 侧）

自建工具栏的每个按钮最终都要落到 worker 的一个 action 上。四件事：

1. **`ui_command` 白名单扩容**。现在只有 12 条（IME 快捷键用）。按菜单分组扩到
   覆盖工具栏 + 菜单条所需的 `.uno:` 全集。白名单仍是硬约束——**不开放任意
   `.uno:` 透传**，否则宿主 DOM 就成了引擎的任意命令通道。
2. **`get_ui_state`**：一次调用返回工具栏要显示的全部状态。已有的
   `get_formatting` 覆盖了字符/段落属性，但缺三样：撤销/重做是否可用、修订开关
   当前状态、当前缩放。工具栏是高频回读，必须一次拿全、且廉价。
3. **`list_styles`**：段落样式清单，喂样式下拉（`set_style` 已经有了）。
4. **`set_chrome`**：一次性隐藏/显示 menubar / 两条工具栏 / 状态栏 / 标尺，
   每一步用 `isElementVisible` 复核后如实返回。

### P1.5 — 状态刷新机制（spike 已完成，2026-08-14）

工具栏的激活态（B 是否高亮、当前字体字号、当前样式）必须跟着光标走。实测结论：

| 探测 | 结果 |
|---|---|
| `zetajs.unoObject([css.view.XSelectionChangeListener])` + `ctrl.addSelectionChangeListener` | ✅ 装得上、能触发 |
| 扩选 / 全选 / 选中段落 | ✅ 每次都触发（一次一条） |
| **纯光标移动（collapsed，无选区）** | ❌ **基本不触发**（连移 5 次只收到 1 条） |
| 一次性回读全部工具栏状态的耗时 | **5.8ms/次**（20 次共 116ms） |
| `xModel.getPropertyValue('UndoManager')` | ❌ UnknownPropertyException——**要改走 `XUndoManagerSupplier.getUndoManager()`**，P1 里重测；实在读不到就让撤销/重做常亮（LO 自己也这么干过一阵） |
| 段落样式清单 | ✅ 126 条，但 `getElementNames()` 给的是**英文程序名**（Standard / Heading 1 / Text body）。下拉要显示中文，得读每个样式的 `DisplayName` |

**定案：事件 + 轮询混合。**光标移动这条路 listener 靠不住，但回读只要 5.8ms，
按 400ms 轮询完全够廉价。刷新触发点：

1. `XSelectionChangeListener` 事件（选区类变化，最及时）；
2. 任何经 executor 发出的命令之后（工具栏自己的按钮、AI 命令）；
3. 已有的 `modified` 信号（打字）；
4. 编辑器聚焦时 400ms 安全轮询，失焦即停（兜住纯光标移动与画布点击）。

注：方向键其实也会经 IME 覆盖层转成 `move_cursor` 命令，命中第 2 条；轮询主要
兜的是画布点击定位和引擎内部的光标移动。

### P2 — 工具栏组件（宿主 DOM）

`frontend/src/components/EditorToolbar.vue`，挂在 `LibreOfficeEditor.vue` 的
canvas 上方（占布局高度，不是浮层——浮层压在 webview 上不可靠，且会遮正文）。

单行紧凑布局 + 溢出收进「更多」。分区：

```
撤销 重做 │ 段落样式▾ 字体▾ 字号▾ │ B I U S 字色 高亮 │ 对齐 列表 缩进
         │ 插入（表格/图片/链接/批注/分页）│ 修订开关 审阅 │ 缩放 −100%+
```

- 每个按钮 = 一次 `executor.executeCommand`，走宿主已有的 executor（不新开通道）。
- 激活态由 P1.5 的状态流驱动。
- 视觉对齐官网 DESIGN.md 与既有面板（浅色、无 emoji）。

### P3 — 菜单层

`frontend/src/components/EditorMenus.vue`：文件/编辑/视图/插入/格式/表格/工具
七个下拉，条目直接落到 `ui_command` 或已有原语。

引擎自带的对话框（插入表格、查找替换、段落设置…）画在 canvas 上，**隐藏
chrome 不影响它们弹出**，所以复杂对话框沿用引擎的，不重做。

### P4 — 切换与逃生口

- `set_chrome` 默认隐藏 LO chrome。
- 设置里留「显示原生菜单栏」开关（`showElement` 已验证可逆）——自建这套万一
  漏了什么，用户当场能拿回全部功能。这条是「体验不能退步」的兜底保证，
  在自建覆盖度做到位之前不许摘。

### P5 — 验证

- `npm run test:lowa-e2e` 加工具栏组：逐个按钮点一次，断言文档/状态真的变了
  （不是「点了没报错」）。
- `npm run test:app-e2e` 全应用走查。
- 人工走查清单：律师日常动作（改字号、套样式、插表格、接受修订、导出）在
  自建工具栏上全跑一遍。

## 3. 不做的事

- **不动引擎画布配色**（深色化已被否决，见 doc-editor 领域文档）。
- **不重做引擎对话框**。
- **不开放任意 `.uno:` 透传**——白名单是安全边界。
