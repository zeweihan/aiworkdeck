# litviz 的两个 vendor 模块 —— 来自 new-litigation-visualization

`mqc-litigation-visual-redraw/`（重画引擎）与 `mqc-timeline-master/`（时间轴大师）
的全部内容是**上游原样拷贝**，不是我们写的代码。手改它们之前先读本文。

| | |
|---|---|
| 上游仓库 | https://github.com/MiaoQichuan/new-litigation-visualization |
| 仓库内路径 | `plugins/mqc-nlv/skills/mqc-litigation-visual-redraw`、`plugins/mqc-nlv/skills/mqc-timeline-master` |
| 作者 | 缪奇川（Miao Qichuan） |
| 协议 | MIT（见各模块 `LICENSE`，随发行版一同保留） |
| vendor 版本 | 重画 v1.0.2 · 时间轴大师 v2.0.1 |
| vendor commit | `0b2c8f81f272e52e89b8e2c60d83ac1a3b1685e0`（2026-08-23） |
| vendor 日期 | 2026-08-25 |

历史沿革：重画引擎最初 vendor 自独立仓库 mqc-litigation-visual-redraw（v1.0.2，
commit `9c6558f`，2026-08-08，当时放在 `litviz/engine/`）。2026-08-23 上游把它并进
monorepo `new-litigation-visualization` 并新增时间轴大师模块；我们 2026-08-25 跟进，
同时把目录名从 `engine/` 改回上游原名——**时间轴大师的脚本与测试里有十几处
`../../mqc-litigation-visual-redraw/scripts` 的兄弟目录硬引用**（共享内核机制），
保持上游目录名可以让这些引用原生成立，升级永远零补丁、上游测试原样可跑。

## 为什么 vendor 而不是当依赖装

引擎是纯 stdlib Python 脚本，没有发到 PyPI，也没有版本化的分发物。桌面端要在
用户机器上离线出图，只能把脚本随包带走。vendor 的代价是升级要手动同步，收益是
发行版不依赖任何在线源。

## 拷了什么 / 没拷什么

两个模块都拷：`scripts/ schemas/ references/ examples/ tests/ SKILL.md LICENSE
AUTHOR.md CHANGELOG.md`；时间轴大师另有 `docs/adr/`（决策记录，升级前值得读）与
`README.md`、`HANDOVER.md`、`RELAY-*.md`。

**没拷两个模块 `assets/` 下的美术资源**（重画约 10MB、时间轴约 4.2MB 的 README
长图与展示图）——与出图链路无关，带上会让安装包白白胖十几 MB。
唯一例外是 `mqc-litigation-visual-redraw/assets/style-tokens.json`：它是
`scripts/common.py` 启动就要读的配色/字体令牌（写死的相对路径
`../assets/style-tokens.json`），必须保留。

重画模块的上游 README.md 也没拷（引用的全是没拷的图），代价是上游 149 项回归里
3 项 README 文档守卫恒红——`litviz/tests/test_cli.py` 断言失败的**正好是且仅是**
那 3 项。

## 升级步骤

```bash
git clone --depth 1 https://github.com/MiaoQichuan/new-litigation-visualization /tmp/mqc-nlv
```

1. 按上面的清单覆盖两个模块目录（同样跳过 `assets/` 里的美术资源）。
2. 重新套用 `PATCHES.md` 里记录的每一条本地补丁，逐条确认上游是否已自行修掉——
   已修的就从 PATCHES.md 里删掉，别叠着打。
3. 跑上游回归：
   `python3 litviz/mqc-litigation-visual-redraw/tests/run_checks.py`（要 graphviz，预期 146/149）；
   `cd litviz/mqc-timeline-master && python3 tests/run_checks.py`（预期全绿）。
4. 跑我们的契约测试：`python3 litviz/tests/test_cli.py`。
5. 更新本文的 commit / 版本 / 日期。

## 红线

- **不要为了"顺手改好看点"去动模块里的几何、配色、字号。** 上游把这些叫
  frozen numbers，改一处，它自带的回归就会红，且下次升级必冲突。
  真需要改，走 `PATCHES.md` 登记，并说明为什么非改不可。
- 我们自己的代码一律写在 `litviz/` 下两个模块目录之外（`cli.py`、`tests/`），
  通过 import 使用引擎，不往引擎里塞业务逻辑。
- 时间轴大师依赖重画模块作共享内核（sys.path 兄弟目录机制），**两个模块的目录名
  必须保持上游原名**，改名等于打断内核解析。
