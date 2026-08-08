# 优化者的家：维护者机器上的常驻进程

优化者**不跟收件箱同机**。收件箱开在公网上收各安装的反馈；优化者要带着仓库、
GitHub 推送凭据和一个能写代码的 Agent——把它放到生产站那台机器上，等于让用户可控的
文本和生产环境做邻居。所以：

```
用户桌面端 ──上传──► addin.aiworkdeck.com（收件箱，北京 ECS，只收不改代码）
                              ▲   │
                     回执状态  │   │ 取件（X-Optimizer-Token）
                              │   ▼
                    维护者机器上的优化者进程（clone + codex/claude + gh）
                              └──► 开 PR / 发邮件
```

## 一次性准备

1. **仓库工作副本**（优化者会在它上面开临时 worktree，不动你当前的工作区）：
   ```bash
   git clone git@github.com:zeweihan/aiworkdeck.git ~/aiworkdeck-optimizer
   ```
2. **`gh` 已登录**且对该仓库有推送权限：`gh auth status`
3. **编码 Agent 已登录**：`claude` 或 `codex`。注意它是被后端进程 spawn 的，
   拿不到你终端里的交互式登录态——**必须是这台机器上持久化的登录**。
   验一下：`claude -p "print ok"` 或 `codex exec -m gpt-5.5 "print ok"` 能出结果就行。
4. **后端 jar**：`cd backend && mvn -DskipTests package`，把 `target/backend-*.jar`
   放到 `~/aiworkdeck-optimizer-run/backend.jar`。

## 配置

复制 `optimizer.env.example` 为 `~/aiworkdeck-optimizer-run/optimizer.env` 并填好，
`chmod 600`。里面有收件箱地址、共享 token、SMTP 授权码——**不要进仓库**。

## 跑起来

```bash
# 前台跑一次看看（Ctrl-C 退出）
bash deploy/optimizer/run-optimizer.sh

# 常驻（macOS launchd，开机自启、崩了自动拉起）
cp deploy/optimizer/com.aiworkdeck.optimizer.plist ~/Library/LaunchAgents/
launchctl load  ~/Library/LaunchAgents/com.aiworkdeck.optimizer.plist
launchctl list | grep aiworkdeck        # 看在不在
tail -f ~/aiworkdeck-optimizer-run/optimizer.log
```

进程起来之后：

```bash
# 状态（source 那行会显示它在读云端哪个收件箱）
curl -s http://127.0.0.1:9799/api/optimizer/status | python3 -m json.tool

# 手动跑一轮，不等 cron
curl -s -X POST http://127.0.0.1:9799/api/optimizer/run
```

## 搬到 Mac mini

优化者的全部状态就是**三个文件 + 一个 clone**，没有本地数据库（反馈都在云端收件箱）：

```bash
# 在 mini 上
git clone git@github.com:zeweihan/aiworkdeck.git ~/aiworkdeck-optimizer
mkdir -p ~/aiworkdeck-optimizer-run
scp <旧机>:~/aiworkdeck-optimizer-run/{backend.jar,optimizer.env} ~/aiworkdeck-optimizer-run/
cp deploy/optimizer/com.aiworkdeck.optimizer.plist ~/Library/LaunchAgents/
launchctl load ~/Library/LaunchAgents/com.aiworkdeck.optimizer.plist

# 旧机上停掉，别让两台同时跑（同一条反馈会被处理两次）
launchctl unload ~/Library/LaunchAgents/com.aiworkdeck.optimizer.plist
```

mini 上还要确认两件事：`gh auth status` 已登录、`claude`（或 codex）已登录。
其余什么都不用迁——**没有本地状态**，取件与回执都在云端那条记录上。

> 两台同时跑没有数据损坏风险（回执是幂等的状态覆盖），但同一条反馈会被分诊两次、
> 可能开出两个 PR。所以搬家时先停旧的再起新的。

## 出问题先看这三处

| 现象 | 多半是 |
|---|---|
| 状态里 `pending` 一直 0、战报 note 写「取件失败」 | token 与收件箱不一致，或收件箱那边 `feedback.optimizer-token` 没配（没配 = 整组 403） |
| 每条都走邮件、从不开 PR | 编码 Agent 没登录 → diff 为空 → 按 NO_CHANGES 转邮件。手动跑一次 `claude -p`/`codex exec` 验登录 |
| 战报里 `failed` 全是「邮件出口不可用」 | `SPRING_MAIL_*` 没配全，或 `OPTIMIZER_MAIL_TO` 空 |
