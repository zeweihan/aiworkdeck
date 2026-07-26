# 插件广场提级：Railway 入口 + IDE 式管理 + Skill 生效方式

日期：2026-07-27 · 状态：已确认（与维护者讨论定稿）

## 背景

插件广场现藏在「齿轮 → 系统管理 → 插件广场」两跳之下，语义上也不该挂在管理员设置里。
Skill 靠触发词隐式激活，用户不知道"AI 为什么突然换了行为"，也没有地方统一管理已安装能力。

## 概念模型（对齐 IDE）

| 概念 | 对位 | 说明 |
|---|---|---|
| 插件 | VS Code 扩展 | 独立能力（manifest + JAR + 可选 frontendEntry），装完在 Railway 长出自己的图标，只有启用/停用，无作用域问题 |
| Skill | Copilot instructions/prompt | 本质是提示词 + 工具裁剪，在对话中生效，需要"什么时候生效"的控制 |
| 安装 | 用户级（唯一） | 单机桌面只有一份 skills/、plugins/ 目录，安装环节不做作用域选择 |
| 生效 | 三档 | 自动触发（触发词命中，现状默认）/ 仅手动（对话中勾选才生效）/ 停用 |
| 激活 | 按轮次 | 触发词按轮命中即天然的上下文级生效；钉选 > 触发词自动匹配 |

## PR-A：入口 + 广场重构

1. **Railway 入口**：底部功能区（文件暂存区与齿轮之间）新增"插件广场"按钮，
   内联 SVG 积木图标（三块方块 + 一块悬浮装配，viewBox 24），点击 navigateTo
   `/pages/plugin-market/plugin-market`。admin 页原 nav 项保留。
2. **plugin-market 两 tab**：
   - **广场**：分 Skill / 插件两个分区（分段控件）。Skill 分区 = 在线 registry 列表，
     带搜索框（名称/描述/触发词）+ 分类 chips；插件分区 = 占位空态
     （在线分发属 Phase 2，涉及 JAR 签名/沙箱）。
   - **已安装**：插件区（现有卡片 + 启停）与 Skill 区（现有卡片 + 启停，
     插件携带的 skill 跟随插件）。保留"重新扫描"。
3. **分类**：沿用官网 SKILL_CATEGORIES 七类
   （contract 合同 / litigation 诉讼与争议 / compliance 合规风控 / research 法律研究 /
   corporate 公司与投融资 / office 办公效率 / other 其他）。
   官网 registry 接口增量返回 `category`；桌面端本地维护 id→中文映射；
   缺失 category 归入"其他"（兼容旧 registry）。
4. **桌面后端**：`MarketSkillView` 增加 `category` 字段，解析容错。

## PR-B：生效方式 + 对话区选择器

1. **后端**：Skill 生效方式 auto / manual（停用沿用现有 disabled 名单）。
   存储沿用 system_setting（新 key `ai.skills.manual`，JSON 数组，与 disabled 同款机制）。
   `SkillRouter.match` 跳过 manual skill 的自动匹配；对话请求新增可选 `pinnedSkillId`，
   钉选优先于触发词匹配（钉选的 skill 需存在且未停用）。
   不改编排器构造器（EvalHarness 同步约束）。
2. **前端**：聊天输入区 Skill chip + 弹出选择器：
   - 默认档"自动匹配（按触发词）"；
   - 可钉选任一已启用 Skill，本会话固定使用，chip 显示当前钉选；
   - 仅手动的 Skill 只能通过钉选生效；
   - 底部"管理已安装 Skill"跳转广场页已安装 tab。
   已安装 tab 的 Skill 行增加生效方式下拉（自动触发 / 仅手动 / 停用）。

## 非目标

- 插件 JAR 在线分发（Phase 2，安全模型另议）
- 项目级启停覆盖（等真实噪音出现再做）
- MCP 服务器上架广场
