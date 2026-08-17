#!/bin/zsh
# 每日全量 QA / daily QA run — 由用户 crontab 经 ~/aiworkdeck-qa/run.sh 调起。
# 在专用克隆（~/aiworkdeck-qa/repo）上跑，绝不碰维护者的工作 checkout。
#
# 跑什么：
#   1) tests/lowa-e2e  —— 真实 LOWA 引擎 + 覆盖层键盘链路（自包含，必跑）
#   2) tests/app-e2e   —— 全应用真人模拟（需桌面后端 9696 在跑；不在则跳过并注明）
# 产出：~/aiworkdeck-qa/reports/YYYY-MM-DD.md；有失败时用 gh 开 issue（标签 qa-nightly）。
#
# 引擎来源：打包版 /Applications/AI WorkDeck.app 内置的 lowa/ + cjk 字体（离线、稳定，
# 免 CDN；build:zetaoffice 会清空 dist，故引擎复制必须在 build 之后）。

set -u
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

QA_HOME="$HOME/aiworkdeck-qa"
REPO="$QA_HOME/repo"
REPORTS="$QA_HOME/reports"
APP_RES="/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice"
DAY=$(date +%F)
REPORT="$REPORTS/$DAY.md"
PORT=5199
mkdir -p "$REPORTS"

FAILED=0
log() { echo "$(date +%T) $*" }
section() { echo "\n## $*" >> "$REPORT" }

echo "# AI WorkDeck 每日 QA — $DAY" > "$REPORT"

# ---- 更新专用克隆 ----
if [[ ! -d "$REPO/.git" ]]; then
  git clone --depth 20 https://github.com/zeweihan/aiworkdeck.git "$REPO" || { echo "clone 失败" >> "$REPORT"; exit 1 }
fi
cd "$REPO"
git fetch origin master --depth 20 && git reset --hard origin/master >> /dev/null
echo "commit: $(git log -1 --format='%h %s')" >> "$REPORT"

# ---- 前端依赖与构建 ----
cd "$REPO/frontend"
npm install --no-audit --no-fund --loglevel=error > /dev/null 2>&1
npx vite build --config vite.zetaoffice.config.js > /dev/null 2>&1 || { echo "build:zetaoffice 失败" >> "$REPORT"; FAILED=1 }

# ---- 引擎：从打包版复制（build 之后！）----
if [[ -f "$APP_RES/lowa/soffice.js" ]]; then
  cp -R "$APP_RES/lowa" dist/zetaoffice/
  cp "$APP_RES/cjk.ttc" dist/zetaoffice/ 2>/dev/null
else
  echo "⚠️ 打包版引擎缺失（$APP_RES），lowa-e2e 跳过" >> "$REPORT"
fi

# ---- 1) LOWA 编辑器键盘链路 ----
section "lowa-e2e（编辑器键盘/IME 链路）"
if [[ -f dist/zetaoffice/lowa/soffice.js ]]; then
  if npm run test:lowa-e2e > "$QA_HOME/lowa-e2e.log" 2>&1; then
    tail -3 "$QA_HOME/lowa-e2e.log" >> "$REPORT"
  else
    FAILED=1
    echo '```' >> "$REPORT"; tail -30 "$QA_HOME/lowa-e2e.log" >> "$REPORT"; echo '```' >> "$REPORT"
  fi
else
  echo "跳过（无引擎）" >> "$REPORT"
fi

# ---- 2) 全应用真人模拟（需 9696）----
section "app-e2e（全应用真人模拟）"
if nc -z 127.0.0.1 9696 2>/dev/null; then
  (npx uni --port $PORT > "$QA_HOME/dev.log" 2>&1 &)
  DEV_PID=$!
  sleep 25
  if APP_E2E_BASE="http://127.0.0.1:$PORT" npm run test:app-e2e > "$QA_HOME/app-e2e.log" 2>&1; then
    tail -3 "$QA_HOME/app-e2e.log" >> "$REPORT"
  else
    FAILED=1
    echo '```' >> "$REPORT"; tail -40 "$QA_HOME/app-e2e.log" >> "$REPORT"; echo '```' >> "$REPORT"
  fi
  pkill -f "uni --port $PORT" 2>/dev/null
else
  echo "跳过：桌面后端 9696 未运行（打开 AI WorkDeck 后自动恢复覆盖）" >> "$REPORT"
fi

# ---- 汇报 ----
if [[ $FAILED -ne 0 ]]; then
  echo "\n**结论：有失败 ❌**" >> "$REPORT"
  gh issue create -R zeweihan/aiworkdeck \
    --title "每日 QA 失败 $DAY / nightly QA failures" \
    --body-file "$REPORT" --label bug 2>> "$REPORT" || true
else
  echo "\n**结论：全部通过 ✅**" >> "$REPORT"
fi
exit $FAILED
