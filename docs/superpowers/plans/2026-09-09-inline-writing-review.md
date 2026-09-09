# 实施记录：行内即时审校 #547

分支：codex/inline-writing-review-547；独立 worktree：.worktrees/inline-writing-review-547。

- [x] 阅读 CLAUDE 与编辑器/解析/桥接领域规范，创建 #547。
- [x] 用户确认 AI 只在点击深入审校时调用；形成设计。
- [ ] 后端复用规则 + 显式深度审校，鉴权/单飞/限额/来源校验。
- [ ] worker 文档世代/光标上下文/原子定位修改；guest 行内提示。
- [ ] host 防抖/分页/过期防护/偏好/生命周期。
- [ ] 移除旧解析按钮；保留显式全文在线核验并先保存。
- [ ] 单元、集成、真实 LOWA 与实际桌面流程验证。
- [ ] 独立复核、CI、合并、发布和上线复核。

协作分工：主代理负责方案、宿主与最终验证；research_completion 负责后端；editor_feasibility 负责 guest/worker；assistance_review 负责旧入口迁移。均在同一独立工作树按文件范围协作，原检出目录保持只读。
