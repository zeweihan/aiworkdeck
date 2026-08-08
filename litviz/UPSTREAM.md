# litviz/engine —— vendor 自 mqc-litigation-visual-redraw

`engine/` 下的全部内容是**上游原样拷贝**，不是我们写的代码。手改它之前先读本文。

| | |
|---|---|
| 上游仓库 | https://github.com/MiaoQichuan/mqc-litigation-visual-redraw |
| 作者 | 缪奇川（Miao Qichuan） |
| 协议 | MIT（见 `engine/LICENSE`，随发行版一同保留） |
| vendor 版本 | v1.0.2 |
| vendor commit | `9c6558f1094b7ca71fa8ab49c56971985de93fa4`（2026-08-01） |
| vendor 日期 | 2026-08-08 |

## 为什么 vendor 而不是当依赖装

引擎是纯 stdlib Python 脚本，没有发到 PyPI，也没有版本化的分发物。桌面端要在
用户机器上离线出图，只能把脚本随包带走。vendor 的代价是升级要手动同步，收益是
发行版不依赖任何在线源。

## 拷了什么 / 没拷什么

拷贝：`scripts/ schemas/ references/ examples/ tests/ assets/style-tokens.json
SKILL.md LICENSE AUTHOR.md CHANGELOG.md`。

**没拷 `assets/` 下的 10MB 美术资源**（README 长图、品牌 logo、模式对比图）——那是
上游 README 用的展示素材，与出图链路无关，带上会让安装包白白胖 10MB。
`assets/style-tokens.json` 是例外，它是 `scripts/common.py` 启动就要读的配色/字体
令牌，必须留在 `assets/` 下（common.py 里是写死的相对路径 `../assets/style-tokens.json`）。

## 升级步骤

```bash
git clone --depth 1 https://github.com/MiaoQichuan/mqc-litigation-visual-redraw /tmp/mqc-lit
```

1. 按上面的清单覆盖 `engine/`（同样跳过 `assets/` 里的美术资源）。
2. 重新套用 `PATCHES.md` 里记录的每一条本地补丁，逐条确认上游是否已自行修掉——
   已修的就从 PATCHES.md 里删掉，别叠着打。
3. 跑上游自带的 149 项回归：`python3 litviz/engine/tests/run_checks.py`（要 graphviz）。
4. 跑我们的契约测试：`python3 litviz/tests/test_cli.py`。
5. 更新本文的 commit / 版本 / 日期。

## 红线

- **不要为了"顺手改好看点"去动 `engine/` 里的几何、配色、字号。** 上游把这些叫
  frozen numbers，改一处，它自带的 149 项自检就会红，且下次升级必冲突。
  真需要改，走 `PATCHES.md` 登记，并说明为什么非改不可。
- 我们自己的代码一律写在 `litviz/` 下 `engine/` 之外（`cli.py`、`tests/`），
  通过 import 使用引擎，不往引擎里塞业务逻辑。
