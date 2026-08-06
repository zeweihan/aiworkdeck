# 桌面/插件 ↔ 官网契约：本仓侧待办条目

**权威契约文档在官网仓** `doc/desktop-contract.md`（人读版）+ `scripts/contract-check.mts`
（机器可执行版）。本文件只记录由本仓（桌面/server 后端）一侧先行提出、**官网侧尚未实施**的
契约条目；官网侧落地后应把条目并入权威文档与 contract-check，并从此处删除。

## 待官网侧实施

### 1. `GET /api/account/me` 需增加稳定 `accountId` 字段（2026-08-06，插件云后端 awdk 桥）

- 用途：server 模式桥接端点 `POST /api/auth/awdk-login`（本仓 `AwdkLoginService`）用 awdk_ Key
  调 `GET /api/account/me` 校验通过后，以 `accountId` 作为 `account_binding.external_account_id`
  的映射键——同一官网账户在 Key 轮换、username/displayName 改名后仍映射到同一个 server 用户。
- 要求：字段对账户终身稳定（不随 username / displayName / Key 轮换变化），字符串形态，长度 <= 128。
- 官网侧落地时须同步官网仓 `doc/desktop-contract.md` 与 `scripts/contract-check.mts`。
- 未实施期间的桌面仓行为：awdk-login 对缺失 `accountId` 的响应按 MALFORMED 拒绝——**不猜、
  不回落 username 做映射键**（username 可改名，以它为键会在改名后凭空生出第二个 server 用户）。

### 2. per-user 平台 AI key（设计待办，未排期）

server 模式的平台 AI 通道目前仍是机器级（`~/.aiworkdeck/platform-ai-key.json` 一台机器一把 key），
多租户下所有用户共享同一账户额度且 `PlatformUsageAccountant` 的差分对账会串位。按用户化的要点：

- 桌面/server 侧：`AccountService` / `PlatformAiChannel` 从机器级文件改 per-user DB 记录
  （可挂在 `account_binding` 上），每用户一把 provisioned OpenRouter runtime key；
- 官网侧：`POST /api/account/ai-key` 现成且幂等，per-user 化后由 server 后端代表每个绑定的
  官网账户各自调用（每次桥接登录时已持有该用户的 awdk_，但明文不落库——需要设计
  「何时用什么凭据取 key」：候选是桥接时即取即存 per-user key，或官网增加服务端-服务端凭据）；
- 对账：OpenRouter `GET /api/v1/key` 的累计消费差分随 key 隔离到用户维度，串位问题随之消解。
