# AI WorkDeck 法律领域 zh→en 术语表（翻译唯一基准）

所有 UI/文案/prompt 的中英翻译以本表为准。新增术语先加进本表再用。
维护规则：改动产品名词的英文叫法 = 契约变更，PR 里同步更新本表与受影响的 locale 键。

## 全局风格约定

- 语体：American English。界面短标签（菜单/按钮/栏目名）用 Title Case；句子式提示、toast、错误信息用 sentence case。
- 禁 emoji（全局红线）。
- 法域口径：英文版是 jurisdiction-neutral 的国际商事/通用法律助手，不预设中国大陆法条；"法条" 一律译 statutory provision / legal provision，不写 "PRC law" 除非内容本身如此。
- 品牌名不译：AI WorkDeck。"AI WorkDeck 云端" → AI WorkDeck Cloud。

## 产品结构名词

| 中文 | English | 备注 |
|---|---|---|
| 工作台 | Workbench | 四列干活界面（路由 project-overview） |
| 项目概览页 | Project Overview | 路由 project-home |
| 项目列表页 | Projects | 路由 project-list；"全部项目" → All Projects |
| 项目 | Project | |
| 案卷 | Case File | 协作语境；单份工作文档集 |
| 团队案件库 | Team Case Library | |
| 案件参与人 | Case Members | |
| 负责人 | Lead | 项目负责人；「负责人：{name}」 → Lead: {name} |
| 暂存区 | Staging Area | |
| 回收站 | Recycle Bin | 文件树软删除；toast 句中用小写 recycle bin |
| 根目录 | Root Directory | 文件树；句中用小写 the root directory |
| 左栏/侧边栏 | Sidebar | |
| 底部工具抽屉 | Tools Panel | |
| AI 面板 | AI Panel | |
| 资源管理器（文件树） | Explorer | 对齐 VS Code 惯例 |
| 变量库 | Variable Library | |
| 收藏夹 | Favorites | |
| 标签 | Tag | 文件标签；标签管理 → Manage Tags |
| 剪贴板 | Clipboard | |
| 浏览器面板 | Browser | |
| 插件广场 | Plugin Marketplace | |
| 插件 | Plugin | |
| 技能 | Skill | |
| 组件管理 | Components | 模型/组件下载 |
| 设置 | Settings | 统一设置页（2026-08-20 个人中心并入，侧栏分「个人 / 系统」两组）|
| 账户与安全 | Account & Security | 个人组里原「个人中心 → 设置」那一栏 |
| 工作记录 | Activity Log | |
| 代办/待办 | To-dos | |
| 事项 | Task | 日程系统的条目（dev-board#896）；「新建事项」 → New Task；「任务」一词只留在代码与 AI 工具名 |
| 日程 | Schedule | 日历视图与入口（rail/头像菜单/页标题）；「我的日程」 → My Schedule |
| 截止日 / 开庭 / 会议 / 待办（事项类型） | Deadline / Hearing / Meeting / To-do | 事项类型芯片 |
| 负责人（事项） | Assignee | 事项的指派人；与项目「负责人 Lead」区分 |
| 向导 | Setup Wizard | |
| 解锁 | Unlock | |
| 试用码 | Trial Code | |
| 授权（桌面激活） | License | 设置里的授权卡片；「解除授权」 → Deactivate License |
| 正式版 | Full Edition | 桌面授权 edition |
| 试用版 | Trial Edition | |
| 插件访问令牌 | Plugin Access Token | Office 插件等外部客户端连接本机的凭据 |
| 认证器 | Authenticator | TOTP 登录二次验证 |
| 案卷访问码 | Case File Access Code | 客户登录入口 |
| 无限版 | Unlimited edition | 缓存区/存储容量档位；「解锁无限版」 → Unlock the Unlimited edition |
| 额度 | Credits | 站内唯一计价单位，不译成 quota |
| 平台通道（AI WorkDeck 云端） | AI WorkDeck Cloud | |
| 自备 Key | Your Own Key (BYOK) | |
| 网络区域 | Network Region | 境内 → Mainland China；国际 → International |

## 文档编辑

| 中文 | English | 备注 |
|---|---|---|
| 修订 | Tracked Changes | LO/Word 语义；单条修订 → a tracked change / revision |
| 修订模式 | Track Changes | |
| 页边修订 | Changes in Margin | |
| 批注 | Comment | |
| 审阅面板 | Review Panel | |
| 接受/拒绝（修订） | Accept / Reject | 全部接受 → Accept All |
| 标记解决（批注） | Resolve | |
| 检查点 | Checkpoint | AI 改文档前的还原点 |
| 自动保存 | Autosave | |
| 只读预览 | Read-only Preview | |
| 版本记录 | Version History | |
| 工作段 | Work Session | |
| 重要版本 | Milestone | 版本时间线里的星标版本；「标为重要版本」 → Mark as Milestone |
| 主线 | Mainline | 「回到主线工作」 → Return to Mainline |
| 时间线 | Timeline | |
| 退回（版本） | Revert | |
| 丢弃工作 | Discard Changes | |
| 另起一稿 | New Draft | |
| 采纳（稿） | Adopt | 采纳-放弃-冲突三选一：Adopt / Discard / Resolve Conflict |
| 交稿 | Submit Draft | 协作 |
| 取回最新稿 | Pull Latest | 协作 |
| 交稿引导 | Submit-draft guide | dev-board#645；交稿前那张三步清单，界面上不出现这个词组本身 |
| 待做 / 进行中 / 已完成 | To do / Next / Done | 交稿引导的步骤标记；「进行中」= 轮到你做这一步，不是「正在跑」 |
| 稍后再说 | Later | 交稿引导的退出按钮；与「取消 Cancel」区分——什么都没做，不是撤销 |
| 这套协作怎么用 | How collaboration works | 协作抽屉里常驻的说明入口 |
| 提交历史 | History | 中栏标签页（dev-board#624）；标签名短，「完整历史」入口 → Full History |
| 完整历史 | Full History | 版本面板顶部与协作抽屉里通往提交历史标签页的入口 |
| 泳道图 | Lane graph | 提交历史左侧那张图；对外不出现「分支图 / branch graph」 |
| 版（量词） | version | 「领先 2 版」→ ahead by 2；不写 commit |
| 签出（一份案卷） | Check out | 事件行「{谁} 签出了一份」→ {who} checked out a copy |
| 本机 | This Computer | 提交历史里的标签；与「案件库 Case Library」对举 |
| 裁决（冲突三选一的结果） | Resolution | 「留了你这边 / 留了同事那边 / 两边都留」→ kept your side / kept your colleague's side / kept both sides |
| 参与人（筛选项） | Person | 提交历史工具栏的筛选下拉；名单语境仍是 Case Members |
| 合并比对稿 | Merge review | 同一份稿里同时看到两位律师相对「共同的上一版」的修订（dev-board#630）；标签名 Merge review: {name} |
| 自动合并 | Combined automatically | 两边改动段落不重叠时静默合并（dev-board#631）；「自动合并了同事的改动」→ your colleague's changes were combined automatically |
| 共同的上一版 | Common earlier version | 两位律师分头修改前的那一版；对外不出现 merge-base |
| 同一段两边都改了 | Changed on both sides | 只有这种情况才打扰律师逐处裁决 |
| 逐处裁决（接受 / 拒绝 / 用你的 / 用同事的 / 自己改） | Resolve each change (Accept / Reject / Keep yours / Take theirs / Edit myself) | 合并比对稿右栏 |
| 溯源 | Origin | 每段最后改动它的版本 / 作者 / 时间（dev-board#632）；侧栏标签 Origin，光标条「{谁} · {日期} · {版本标题}」 |
| 脱敏 | Redaction | 动词 redact |
| 套红/公文格式 | House Style Formatting | |
| 书签 | Bookmark | |
| 正文 | Body Text | |

## 法律业务

| 中文 | English | 备注 |
|---|---|---|
| 尽调 | Due Diligence | 尽调文件 → Due Diligence Files |
| 股东大会核查 | Shareholder Meeting Verification | 英文版隐藏该技能（中国法深度绑定） |
| 合同审查 | Contract Review | |
| 律师函 | Demand Letter | 泛指也可 Legal Letter |
| 法律意见书 | Legal Opinion | |
| 尽调报告 | Due Diligence Report | |
| 庭审 | Hearing | 泛指；正式庭审 trial |
| 诉讼 | Litigation | |
| 诉讼可视化 | Litigation Visualization | |
| 时间轴（诉讼） | Timeline | |
| 流程图 | Flowchart | |
| 当事人关系图 | Party Relationship Map | |
| 当事人 | Party | 复数 parties |
| 委托人/客户 | Client | |
| 客户视图 | Client View | |
| 甲方/乙方 | Party A / Party B | 合同变量场景保留原文语义 |
| 标的公司 | Target Company | |
| 上市公司 | Listed Company | |
| 交易结构 | Transaction Structure | |
| 法条/法律依据 | Legal Provision / Legal Basis | 不预设法域 |
| 证据 | Evidence | |
| 网络核查（活动/域） | Web Verification | 尽调公开信息核查这件事本身 |
| 网核标记/网核收藏/网核证据（取证物） | Web Evidence | 浏览器取证产物；「加入网核收藏」 → Save as Web Evidence；「网核关联」 → Link as Web Evidence |
| 案由 | Cause of Action | |
| 卷宗 | Case Bundle | |
| 控制权收购（上市公司） | Takeover | 项目类型「上市公司控制权收购」 → Listed Company Takeover |

## AI 对话与编排

| 中文 | English | 备注 |
|---|---|---|
| 对话 | Conversation | 新对话 → New Conversation |
| 助手 | Assistant | |
| 模式（问答/计划/代理） | Mode: Ask / Plan / Agent | |
| 计划审批 | Plan Approval | 按此推进 → Proceed；修订（计划） → Revise |
| 反问/追问 | Clarifying Question | 状态"待回答" → Awaiting Your Reply |
| 待审批 | Awaiting Approval | |
| 子任务 | Subtask | |
| 后台任务 | Background Task | |
| 过程卡/执行过程 | Steps | 折叠组标题；单条工具行显示工具名 |
| 思考 | Thinking | |
| 继续 | Resume | 中断恢复按钮 |
| 停止 | Stop | |
| 限流等待中 | Rate limited, waiting to retry | |
| 记忆 | Memory | |
| 上下文 | Context | |
| 活跃文档 | Active Document | |
| 进程中断 | Process Interrupted | |
| 执行中断 | Execution interrupted | 客户端 error 事件标记（agentStream）；句中 sentence case |
| 连接中断 | Connection lost | SSE 断线标记（agentStream） |
| 长上下文单价更高 | Higher rates for long context | 模型下拉提示 |
| 需国际网络 | Requires international network | |

## 通用 UI 动词/状态

| 中文 | English | 中文 | English |
|---|---|---|---|
| 保存 | Save | 另存为 | Save As |
| 打开 | Open | 新建 | New |
| 重命名 | Rename | 删除 | Delete |
| 移动 | Move | 上传 | Upload |
| 下载 | Download | 导出 | Export |
| 导入 | Import | 搜索 | Search |
| 复制 | Copy | 粘贴 | Paste |
| 撤销 | Undo | 重做 | Redo |
| 确认 | Confirm | 取消 | Cancel |
| 关闭 | Close | 重试 | Retry |
| 刷新 | Refresh | 设置 | Settings |
| 展开 | Expand | 收起 | Collapse |
| 启用 | Enable | 停用 | Disable |
| 安装 | Install | 卸载 | Uninstall |
| 发送 | Send | 编辑 | Edit |
| 加载中… | Loading… | 保存中… | Saving… |
| 已保存 | Saved | 已完成 | Done |
| 失败 | Failed | 已取消 | Canceled |
| 暂无数据 | No data yet | 敬请期待 | Coming soon |
