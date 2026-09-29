<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# AI WorkDeck：Agent 入口

开始项目工作前读根目录 `CLAUDE.md`，按其中的领域路由读取相关 `.claude/agents/*.md`。
这些 Markdown 保存项目事实、文件地图、契约、已知问题与验证命令，供各宿主共用；不要复制另一套领域文档。
其中 Claude 专属的模型名、agent 类型与工具参数不适用于 Codex，调度使用当前宿主提供的能力。

可独立拆分的任务可交给原生子代理，任务书写清绝对工作目录、文件范围、验证方式和交付要求。
Codex 子代理共享文件系统；并行写入须明确隔离 worktree 或互不重叠的文件，不能假定自动隔离。
主任务核对 diff 和实际验证结果后整合交付。

旧任务的 `.remember/remember.md`、本地会话与记忆仅作线索，继续之前核对当前分支、未提交改动、PR 和测试产物。
保护旧工作树；提交与未提交补丁分别恢复到独立工作树。不要把原始会话、凭据或机器私有记忆提交进仓库。

合并与发版分开：完成审阅和必要检查后可按用户授权合并；发版、部署或升级客户端需要本次明确指示。
