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

### P1 — 命令层底座（worker 侧，已完成）

自建工具栏的每个按钮最终都要落到 worker 的一个 action 上。

1. **`ui_command` 白名单扩容**：加了删除线/上下标/清除格式/大小写/四种对齐/
   增减缩进/项目符号/编号/分页/格式标记。白名单是硬约束——**不开放任意
   `.uno:` 透传**，否则宿主 DOM 就成了引擎的任意命令通道。
2. **`get_ui_state`**：一次拿全字符/段落/视图/选区/撤销可用性。实测 ~6ms。
3. **`list_styles`**：段落样式清单（`name` 程序名 + `display` 显示名 + `inUse`）。
4. **`set_chrome`**：逐项或 `{all:false}` 一刀切隐藏 LO chrome，每一步用
   `isElementVisible` 复核后如实返回。
5. **`set_track_changes`**：修订开关。直接写 `RecordChanges` 属性而不是派发
   `.uno:TrackChanges`——后者是切换语义，宿主要设成确定状态还得先读再判。

**真机实测得到的三条硬结论：**

- **`.uno:Grow` / `.uno:Shrink` 在本引擎是哑弹**：派发不报错，`CharHeight`
  纹丝不动（12→12）。已从白名单剔除。字号步进改由宿主做：`get_ui_state` 读到
  当前字号 → `format_selection {sizePt}`（这条路已验证）。**宁可不给按钮，
  也不给点了没反应的按钮。**
- **撤销/重做可用性走 `xModel.getUndoManager()`**（XUndoManagerSupplier 的方法），
  不是属性——`getPropertyValue('UndoManager')` 抛 UnknownPropertyException。
  实测能读出 `canUndo/canRedo`。读不到时宿主让两个按钮常亮，不许灰掉能用的功能。
- **`LayoutManager.setVisible(false)` 可用**，是关掉全部 chrome 最稳的一刀切；
  逐项关的时候必须把 11 条 `singlemode-*` 上下文工具栏一起关，否则一选中表格
  就又钻出一条老气的工具栏。

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

**P1 留给 P2 的两个已知缺口：**

- **空选区下改不了格式**。`format_selection` 要求非空选区（`nothing selected —
  select first, then format`）。但工具栏的常见用法是「先设好字号再开始打字」，
  这条路现在是断的。P2 要么扩原语支持塌陷光标（写 CharHeight 到 view cursor 上，
  LO 本身支持「后续输入用此格式」），要么在没有选区时把按钮置灰并说明原因——
  但**不许点了没反应**。
  （注意参数名：`get_ui_state` 回的是 `sizePt`，`format_selection` 收的是
  `fontSize`；这是既有的 AI 工具契约，别为了对称去改名。）
- **样式显示名的语言存疑**。spike 环境里 `get_ui_lang` 回 `ooLocale: en-US`，
  于是 `DisplayName` 拿到的是英文（Default Paragraph Style / Body Text）；而真机
  截图里 LO 自己的样式下拉显示的是中文（默认段落样式）。两者对不上，说明 spike
  的 boot 没走到 zh-CN。P2 的做法：优先用 `display`，同时自备一张常用样式
  （正文/标题 1-6/引用/列表…）的中文名映射兜底，**两条路都在，语言就不会翻车**。

### P2a — 工具栏组件（宿主 DOM，已完成）

`frontend/src/components/EditorToolbar.vue`，挂在 `LibreOfficeEditor.vue` 的
canvas 上方（占布局高度，不是浮层——浮层压在 webview 上不可靠，且会遮正文）。

**这一期只加不减：LO 自己的 chrome 原样保留**，两套并存。功能覆盖到位之前
不砍旧的——「体验不能退步」的最稳走法就是先并存再收敛。

```
撤销 重做 │ 样式▾ 字体▾ 字号−N+ │ B I U S X² X₂ 字色▾ 高亮▾ 清除
         │ 对齐×4 项目符号 编号 缩进∓ │ 分页 ¶      ‖ 修订 审阅 缩放−N+
```

主命令区横向滚动（窄了不换行，换行会把画布挤下去），右侧修订/审阅/缩放常驻不滚。

**激活态刷新：纯事件驱动，没有轮询。**原方案的 400ms 轮询被替换成三路信号：

1. worker 的 `XSelectionChangeListener` → `post('sel_changed')`（选区类变化）
2. IME 覆盖层新增的 `onCursorMoved` 钩子（纯光标移动 / 画布点击——第 1 路盖不住）
3. 组件自己发完命令之后

第 2 路是关键：覆盖层在**每一个**移动光标的动作后都会走 `reposition()`，一个
钩子就兜住了上屏/回车/退格/方向键/快捷键/画布点击。两路在编辑器页合流、节流
1/150ms（带尾随一发，保证停下时不是旧状态）后送宿主。

其余落地决定：

- **字号按常用档跳**（12→14→16），不是 ±1——`.uno:Grow` 是哑弹本来就得自己实现。
- **样式下拉只列 12 条常用的**（126 条全塞没人找得到），中文名自备映射兜底，
  当前段落用的样式不在表里会自动补进列表，不会「显示的样式选不回来」。
- **中文字体排在字体下拉最前面**——给中国律师用的编辑器，别让他们在几百个
  西文字体里翻。
- 撤销/重做读不到可用性时**不置灰**：宁可多点一下，也不把能用的功能锁死。
- 预热备胎（`file=null`）不挂工具栏，否则会对着隐藏的空白实例白跑三个查询。

### P2b — 插入菜单（未做）

表格（网格选择器）/ 图片（宿主文件选择）/ 超链接 / 批注 —— 都需要参数输入，
放在一个「插入▾」下拉里。原语都已存在（`insert_table`/`insert_image`/
`set_selection_hyperlink`/`add_comment`）。

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
