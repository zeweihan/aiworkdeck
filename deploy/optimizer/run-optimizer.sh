#!/bin/bash
# 优化者常驻进程：一个只干这件事的后端实例。
#
# 它跟桌面 app 那个后端刻意隔离：独立端口、独立 user.home（自己的空 H2）、
# 独立工作目录。反馈都在云端收件箱里，这个进程本地没有需要保管的状态。
set -euo pipefail

RUN_DIR="${AWD_OPTIMIZER_HOME:-$HOME/aiworkdeck-optimizer-run}"
JAR="$RUN_DIR/backend.jar"
ENV_FILE="$RUN_DIR/optimizer.env"
PORT="${AWD_OPTIMIZER_PORT:-9799}"

[ -f "$JAR" ] || { echo "缺 $JAR：cd backend && mvn -DskipTests package 后拷过来" >&2; exit 2; }
[ -f "$ENV_FILE" ] || { echo "缺 $ENV_FILE：照 deploy/optimizer/optimizer.env.example 填一份" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

# 编码 Agent 与 gh 都从 PATH 找；launchd 起的进程 PATH 很干净，这里补上常见位置
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

mkdir -p "$RUN_DIR/home" "$RUN_DIR/work"
cd "$RUN_DIR/work"

JAVA_BIN="${AWD_OPTIMIZER_JAVA:-$(/usr/libexec/java_home -v 21 2>/dev/null)/bin/java}"
[ -x "$JAVA_BIN" ] || { echo "找不到 JDK 21（本机默认 25 会 SIGBUS）" >&2; exit 2; }

exec "$JAVA_BIN" \
  -Xmx1g \
  -Duser.home="$RUN_DIR/home" \
  -jar "$JAR" \
  --spring.profiles.active=desktop \
  --server.port="$PORT" \
  --server.address=127.0.0.1
