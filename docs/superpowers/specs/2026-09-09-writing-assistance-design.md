# 文档写作辅助设计 / Writing assistance

任务：dev-board#538。目标：少打字、少查找、少重复操作，不打断思路。用户明确要求实现、测试、上线；已确认本地优先、滚动积累项目与个人常用内容，外查必须由选中文字后的明确操作触发。

## 调研与功能取舍

| 编辑辅助能力 | 文档映射 | 本次处理 |
| --- | --- | --- |
| IntelliSense 下拉、排序、键盘接受 | 主体、人名、法规、条款、案例、案号、词语、表述 | 本地候选；Tab/方向键/鼠标；Esc关闭 |
| 候选详情、Code Actions | 主体信息表、法条原文、案例资料与出处 | 已存资料按需查看并显式插入；外查独立点击 |
| Word completion / locality | 文档→项目→个人常用内容 | 当前文档抽取、项目与个人持久词库、使用次数排序 |
| Snippets | 复用完整常用表述 | 作为实际出现过的PHRASE补全；不另建多占位符引擎 |
| Hover / diagnostics / quick fix | 来源、主体称谓、简称定义、引用缺口 | 来源详情与既有依据窗格复用；更广语义诊断列入后续评估，避免未经验证推断 |
| Inline ghost text | 光标灰字续写 | 暂不做；WASM排版无浏览器decoration契约，下拉先实现同样的选择与接受能力 |
| Signature help | 写作结构的下一步提示 | 对已完成实体提供可执行且有本地依据的后续操作；不生搬函数参数 |
| Navigation / references | 跳转原资料、引用位置 | 保留已有依据窗格与链接能力 |
| AI next edit / paragraph reasoning | 逻辑缺口、改写、上下文建议 | 需独立明确触发与评估，不接逐键自动调用 |

一手来源（2026-09-09读取）：
- https://code.visualstudio.com/docs/editing/intellisense
- https://code.visualstudio.com/api/language-extensions/programmatic-language-features
- https://code.visualstudio.com/docs/editing/userdefinedsnippets
- https://code.visualstudio.com/docs/editing/refactoring
- https://code.visualstudio.com/docs/editing/editingevolved
- https://code.visualstudio.com/docs/configure/accessibility/accessibility
- https://microsoft.github.io/language-server-protocol/specifications/specification-current
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1text_1_1XText.html
- https://api.libreoffice.org/docs/idl/ref/interfacecom_1_1sun_1_1star_1_1document_1_1XUndoManager.html
- https://help.libreoffice.org/latest/en-US/text/swriter/guide/word_completion.html
- https://codemirror.net/examples/autocompletion/
- https://tiptap.dev/docs/editor/api/utilities/suggestion
- https://www.w3.org/WAI/ARIA/apg/patterns/combobox/

## 选型

保留 Vue3/Electron/LibreOffice WASM。JS客体层提供候选与提示UI；宿主从现有本地后端加载授权词库；UNO worker完成有位置令牌校验的原子接受/插入。无需新供应商、LLM调用、完整LSP或替换编辑器。CodeMirror/Tiptap有完善补全扩展但换引擎会重做DOCX排版；LO自带word completion没有项目语义和后续动作。

## 数据与学习

类别 COMPANY/PERSON/LAW/ARTICLE/CASE/WORD/PHRASE。已有项目/用户变量与项目insight提供来源；当前文档和用户实际输入按可解释规则提取（人名仅明确标签，法规书名号/条号，案例案号，机构后缀，Intl.Segmenter分词和标点短句）。不猜测法条正文，不把数字标识作为词条。

项目资料只能进入本项目词库；个人词库只从用户自己输入或明确接受的内容积累。项目/用户数据按权限隔离，不使用无租户字段的CompanyMirror。项目及个人词库持久化、限制容量、按使用频次和时间滚动更新；自动学习可关闭，已学记录可删除/清空。常见词与表述需重复出现后自动推荐，实体可一次收录。

## 交互与安全契约

中文组合输入中不查询、不接管键盘；候选只有可见时才拦截Tab。Enter仍换段（用户可用Tab/鼠标确认），避免中文候选确认后误接受。无候选Tab保持现行编辑行为。输入、导航、失焦、切文档时取消旧候选。弹层不改变webview尺寸。所有文字textContent渲染。

worker持有短期模型+光标range+上下文token。接受时同一次同步执行重新核对；只做前缀的suffix扩展，不异步选择后替换，不全文查找替换。表格/正文插入再次验证token且可撤销。非Writer、选区或内联修订上下文不适用时暂停补全。

外部查询只在用户选中文字、点明确的机构/法规/案例查询动作后发起；结果展示原始来源、日期及状态，再次点击才插入。已有缓存可读，自动打字链绝不调外库或AI。

## 验证与交付

纯函数匹配/学习，后端权限隔离与本地零外呼，真实LOWA输入链（IME/Tab/过期光标/undo/表格/导出回读），双语UI、构建、现有LOWA与应用E2E。审查后PR，按项目发版规范发布并同步适用的后端/插件产物。未验证项不算完成。
