#!/usr/bin/env bash
#
# update-mirror-sync.sh 的 prune 单元测试
# ---------------------------------------------------------------------------
# 只测 prune_old_installers 一个函数：造一个假的 installers 目录，跑一次，
# 断言留下/删掉的正是该留该删的。不碰网络、不碰真镜像目录。
#
# 用法：bash deploy/update-mirror-sync_prune_test.sh
# 退出码：0=全通过。
#
# 起因是 2026-08-18 发 v0.18.0 时官网下载按钮的 404 窗口：旧包一换 latest.json
# 就被删，而 /start 页的下载直链带 ISR 缓存（lib/latest-release.ts revalidate 300），
# 缓存里还是旧文件名。用例 1 就是这个回归。
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL=0

ok()  { printf '\033[32m  PASS\033[0m %s\n' "$*"; }
bad() { printf '\033[31m  FAIL\033[0m %s\n' "$*"; FAIL=1; }

# 只取函数，不跑同步主体
MIRROR_SYNC_SOURCE_ONLY=1 . "$HERE/update-mirror-sync.sh"

ROOT=""
cleanup() { [ -n "$ROOT" ] && rm -rf "$ROOT"; }
trap cleanup EXIT

# 造场景：$1=本版版本号，其余=盘上的文件名。设 WEB_ROOT/installers 与本版 tsv。
setup() {
  local cur="$1"; shift
  ROOT="$(mktemp -d)"
  WEB_ROOT="$ROOT"
  mkdir -p "$WEB_ROOT/installers"
  local f
  for f in "$@"; do : > "$WEB_ROOT/installers/$f"; done
  # latest.json 永远在，且永远不该被删
  echo '{}' > "$WEB_ROOT/installers/latest.json"
  # 本版资产清单，格式与主脚本的 installers.tsv 一致：name<TAB>size<TAB>url
  TSV="$ROOT/installers.tsv"
  : > "$TSV"
  printf 'AI.WorkDeck-%s-arm64.dmg\t1\thttps://x\n' "$cur" >> "$TSV"
  printf 'AI.WorkDeck.Setup.%s.exe\t1\thttps://x\n' "$cur" >> "$TSV"
}

# 断言盘上剩下的文件恰好是期望集合（排序后逐字比较）
expect_left() {
  local label="$1"; shift
  local want got
  want="$(printf '%s\n' "$@" | sort)"
  got="$(find "$WEB_ROOT/installers" -maxdepth 1 -type f | sed 's#.*/##' | sort)"
  if [ "$want" = "$got" ]; then
    ok "$label"
  else
    bad "$label"
    printf '    期望: %s\n' "$(echo "$want" | tr '\n' ' ')"
    printf '    实际: %s\n' "$(echo "$got" | tr '\n' ' ')"
  fi
}

printf '\033[1m[prune]\033[0m update-mirror-sync.sh prune_old_installers\n'

# 1) 本次 404 的回归：换到 0.18.0 之后，上一版 0.17.0 必须还在（官网页面缓存里
#    还是它的文件名），更老的 0.16.0 才删。
setup 0.18.0 \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe \
  AI.Workdeck-0.17.0-arm64.dmg AI.Workdeck.Setup.0.17.0.exe \
  AI.Workdeck-0.16.0-arm64.dmg AI.Workdeck.Setup.0.16.0.exe
prune_old_installers 0.18.0 "$TSV" >/dev/null
expect_left "留本版 + 上一版，删更老的（含品牌大小写变更前的旧命名）" \
  latest.json \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe \
  AI.Workdeck-0.17.0-arm64.dmg AI.Workdeck.Setup.0.17.0.exe
cleanup

# 2) 盘上只有本版：什么都不该删
setup 0.18.0 AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe
prune_old_installers 0.18.0 "$TSV" >/dev/null
expect_left "只有本版时不误删" \
  latest.json AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe
cleanup

# 3) 半成品：本版的 .part 是正在续传的，要留；旧版的 .part 没人会再续，删。
setup 0.18.0 \
  AI.WorkDeck-0.18.0-arm64.dmg \
  .AI.WorkDeck.Setup.0.18.0.exe.part \
  AI.Workdeck-0.17.0-arm64.dmg \
  .AI.Workdeck.Setup.0.17.0.exe.part \
  .AI.Workdeck-0.16.0-arm64.dmg.part
prune_old_installers 0.18.0 "$TSV" >/dev/null
expect_left "留本版半成品，删旧版半成品（旧版成品仍留）" \
  latest.json \
  AI.WorkDeck-0.18.0-arm64.dmg \
  .AI.WorkDeck.Setup.0.18.0.exe.part \
  AI.Workdeck-0.17.0-arm64.dmg
cleanup

# 4) 连着发两版之后，只留最近两版
setup 0.19.0 \
  AI.WorkDeck-0.19.0-arm64.dmg AI.WorkDeck.Setup.0.19.0.exe \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe \
  AI.Workdeck-0.17.0-arm64.dmg AI.Workdeck.Setup.0.17.0.exe
prune_old_installers 0.19.0 "$TSV" >/dev/null
expect_left "再发一版后窗口向前滚，只留最近两版" \
  latest.json \
  AI.WorkDeck-0.19.0-arm64.dmg AI.WorkDeck.Setup.0.19.0.exe \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe
cleanup

# 5) 版本号里有两位数段：0.9.0 比 0.18.0 老（字典序会判反，必须走 sort -V）
setup 0.18.0 \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe \
  AI.Workdeck-0.9.0-arm64.dmg AI.Workdeck-0.17.0-arm64.dmg
prune_old_installers 0.18.0 "$TSV" >/dev/null
expect_left "版本比较按数值而非字典序" \
  latest.json \
  AI.WorkDeck-0.18.0-arm64.dmg AI.WorkDeck.Setup.0.18.0.exe \
  AI.Workdeck-0.17.0-arm64.dmg
cleanup

[ "$FAIL" = 0 ] && printf '\033[32m[prune] 全部通过\033[0m\n' || printf '\033[31m[prune] 有失败\033[0m\n'
exit "$FAIL"
