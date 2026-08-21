#!/usr/bin/env bash
#
# update-mirror-sync.sh 的「发版推送让路」单元测试
# ---------------------------------------------------------------------------
# 只测抢锁之前那个 marker 判断：造一个假的 marker 文件，跑真脚本，断言它
# 在**任何网络动作之前**就退出（或该忽略时不退出）。不碰网络、不碰真镜像目录。
#
# 用法：bash deploy/update-mirror-sync_marker_test.sh
# 退出码：0=全通过。
#
# 起因是 v0.23.0 发版：CI 16:15 开始推安装包，服务器 cron 16:17 起来（那一刻
# exe 还没推完，它的 skip-exists 判据当时确实成立不了），抢到 flock 后从 GitHub
# 慢拉 1.59GB；CI 推完回头等锁，1200s 超时，本脚本从没跑过，latest.json 停在
# 上一版，sync-mirror job 转红、官网下载页停在 0.22.0 直到人工介入。
# v0.17.0 已经踩过一次同款（当时只加了 CI 侧的 flock 等待，没挡住「推送途中
# 新起的 cron」）。治法是让路发生在抢锁之前，用例 1 就是这个回归。
# ---------------------------------------------------------------------------
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL=0

ok()  { printf '\033[32m  PASS\033[0m %s\n' "$*"; }
bad() { printf '\033[31m  FAIL\033[0m %s\n' "$*"; FAIL=1; }

ROOT="$(mktemp -d)"
cleanup() { rm -rf "$ROOT"; }
trap cleanup EXIT

# 本用例测的是**抢锁之前**那道 marker 闸，不是锁本身。开发机（macOS）没有
# flock，缺了它脚本会在锁那步就退出，用例 2-4「有没有越过闸」的正向信号
# （fetching latest release metadata）永远出不来。给个只在缺失时生效的垫片，
# 让被测的那段逻辑照常往下跑。线上 Linux 有真 flock，垫片不参与。
SHIM=""
if ! command -v flock >/dev/null 2>&1; then
  SHIM="$ROOT/shim"; mkdir -p "$SHIM"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$SHIM/flock"; chmod +x "$SHIM/flock"
  printf '\033[33m  提示\033[0m 本机没有 flock，已用垫片跳过加锁（被测对象是 marker 闸，不是锁）\n'
fi

# 跑真脚本，把 marker / WEB_ROOT / 锁都指到临时目录。
# REPO 指向一个必然打不通的地址：万一 marker 没拦住，脚本会往下走到取 release
# 元数据那步然后失败——这正是我们要区分的两种结局，绝不会真去动网络或镜像。
run_script() {
  PUSH_MARKER="$ROOT/marker" \
  WEB_ROOT="$ROOT/web" \
  REPO="invalid.localhost/nonexistent" \
  API="http://127.0.0.1:1/nope" \
  LOCK_FILE="$ROOT/lock" \
  PATH="${SHIM:+$SHIM:}$PATH" \
  "$@" bash "$HERE/update-mirror-sync.sh" 2>&1
}

# --- 用例 1：marker 新鲜 → 让路，且在任何网络动作之前就退出 ---
touch "$ROOT/marker"
out="$(run_script)"
rc=$?
if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '发版推送进行中'; then
  ok "marker 新鲜时让路并 exit 0"
else
  bad "marker 新鲜时应当让路 exit 0，实际 rc=$rc；输出：$out"
fi
if printf '%s' "$out" | grep -q 'fetching latest release metadata'; then
  bad "让路发生得太晚——已经走到取元数据那步（必须在抢锁之前就退出）"
else
  ok "让路发生在抢锁与取元数据之前"
fi

# --- 用例 2：FORCE=1 无视 marker（CI 自己回头调用时用的口子）---
out="$(run_script env FORCE=1)"
if printf '%s' "$out" | grep -q 'fetching latest release metadata'; then
  ok "FORCE=1 无视 marker，继续往下跑"
else
  bad "FORCE=1 没能越过 marker（或根本没跑起来）——CI 推完永远补不上 latest.json；输出：$out"
fi

# --- 用例 3：marker 过期（>90 分钟）当作残留忽略 ---
# 不然 CI 半路死掉留下的一个空文件能让镜像永久停更。
touch -d '3 hours ago' "$ROOT/marker" 2>/dev/null || touch -t "$(date -v-3H +%Y%m%d%H%M 2>/dev/null)" "$ROOT/marker"
out="$(run_script)"
if printf '%s' "$out" | grep -q 'fetching latest release metadata'; then
  ok "过期 marker 当作残留忽略，继续往下跑"
else
  bad "过期 marker 仍在拦（或根本没跑起来）——CI 半路死掉会让镜像永久停更；输出：$out"
fi

# --- 用例 4：没有 marker 时照常往下走 ---
rm -f "$ROOT/marker"
out="$(run_script)"
if printf '%s' "$out" | grep -q 'fetching latest release metadata'; then
  ok "无 marker 时照常往下走"
else
  bad "无 marker 时没能正常往下走；输出：$out"
fi

[ "$FAIL" -eq 0 ] && printf '\033[32m全部通过\033[0m\n' || printf '\033[31m有用例失败\033[0m\n'
exit "$FAIL"
