# HR 用工模板包下架记录

任务：dev-board #649；2026-09-15。精确插件 ID 经线上注册表核实为 `hr-template-pack`，版本 2.0.0。

## 已执行的线上下架

北京与新加坡站点的 `data/plugins.json` 已分别备份，按现有 `revokePlugin` 数据契约将该 ID 的全部版本置为 `revoked`。仅更新状态、审核说明与时间；没有删除已生成的用户文书、插件文件或其他条目，也未触发作者邮件。

2026-09-15 实测国内 `www.aiworkdeck.com`、国际 `workdeck.ai`、美国 `aiworkdeck.us` 三个站点：

- `/api/registry/plugins`：不再包含该 ID。
- `/api/registry/plugins/revoked`：包含该 ID 的下架原因。
- `/api/registry/plugins/hr-template-pack/bundle`：HTTP 404。

线上备份位于各站数据目录 `plugins.json.before-hr-retirement-20260915091058`（北京）与 `plugins.json.before-hr-retirement-20260915091102`（新加坡）。

## 客户端交付

`PluginService` 在扫描时跳过该 ID，既不显示面板/已安装条目，也不注册其技能/模板/工具；`isEnabled` 在离线时仍返回 false。`PluginMarketService` 过滤旧缓存列表并拒绝直接安装该 ID。文件保留在原位，用户已创建的项目文书不受影响。

旧版客户端沿现有启动/每日下架同步机制禁用；本次代码发布后无需等待注册表即可隐藏入口。当前用户正在运行的进程不会因仓库改动自动更新。

回归：旧安装离线扫描、重扫/重新启用拒绝、插件文件保留、其他插件正常、旧注册表列表过滤、绕过列表直接安装拒绝。
