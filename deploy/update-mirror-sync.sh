#!/usr/bin/env bash
# update-mirror-sync.sh — 把最新 GitHub Release 的补丁产物同步到官网镜像
# （增量更新设计 §5.2）。在官网 ECS（8.152.169.44）上运行：发版后手动执行，
# 或挂 cron 每小时一次（幂等，asset 未变时只做 HEAD 级比对开销）。
#
#   bash deploy/update-mirror-sync.sh
#
# nginx 侧已配好（www.aiworkdeck.com.conf 的 `location ^~ /update/desktop/`，
# root /www/wwwroot，与 /lowa-engine/ 同款）：manifest no-cache、assets/ immutable、
# .sh/.py 一律 deny。前缀必须用 ^~ ——否则请求落到 Next.js 被 i18n middleware
# 重定向成 /zh/update/...（实测 307）。
# manifest.json 最后原子替换（先 assets 后 manifest，客户端不会拿到指向
# 尚未就位 asset 的清单）。

set -euo pipefail

REPO="${REPO:-zeweihan/aiworkdeck}"
WEB_ROOT="${WEB_ROOT:-/www/wwwroot/update/desktop}"
API="https://api.github.com/repos/${REPO}/releases/latest"

mkdir -p "$WEB_ROOT/assets"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "[mirror-sync] fetching latest release metadata: $REPO"
curl -sfL "$API" -o "$TMP/release.json"
TAG=$(python3 -c "import json;print(json.load(open('$TMP/release.json'))['tag_name'])")
echo "[mirror-sync] latest release: $TAG"

# 逐个下载 patch-*.tar.gz 与 manifest（已存在且大小一致的 asset 跳过）
python3 - "$TMP/release.json" <<'EOF' > "$TMP/assets.tsv"
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get('assets', []):
    n = a['name']
    if n.startswith('patch-') or n in ('manifest.json', 'manifest.json.sig'):
        print(f"{n}\t{a['size']}\t{a['browser_download_url']}")
EOF

if ! [ -s "$TMP/assets.tsv" ]; then
  echo "[mirror-sync] 该 release 没有补丁产物（大版本首发或旧版本），无事可做"
  exit 0
fi

# 大陆机器直连 GitHub 拉几十 MB 常中断：带重试与续传，并对下载结果验字节数。
# 首次部署 v0.11.0 时 28MB 的壳层补丁就是这么半路断掉的（set -e 让整次同步
# 前功尽弃，manifest 没落地——所幸客户端有 GitHub 兜底通道，用户无感）。
fetch_with_retry() {
  local url="$1" out="$2" want="$3" name="$4"
  local attempt
  for attempt in 1 2 3 4; do
    # -C - 续传（配合 --retry 让长文件能接着上次的字节走）
    if curl -fL --retry 3 --retry-delay 5 --retry-all-errors \
            --connect-timeout 20 --speed-limit 1024 --speed-time 60 \
            -C - -o "$out" "$url" 2>/dev/null || [ -f "$out" ]; then
      local got
      got=$(stat -c %s "$out" 2>/dev/null || stat -f %z "$out" 2>/dev/null || echo 0)
      [ "$got" = "$want" ] && return 0
      echo "[mirror-sync]   第 $attempt 次不完整（$got/$want），重试" >&2
    else
      echo "[mirror-sync]   第 $attempt 次失败，重试" >&2
    fi
    sleep $((attempt * 5))
  done
  return 1
}

MANIFEST_READY=0
FAILED=0
while IFS=$'\t' read -r name size url; do
  case "$name" in
    manifest.json|manifest.json.sig)
      # manifest 很小，失败即整次放弃（不能让 assets 换了而清单还是旧的）
      curl -sfL --retry 3 --retry-delay 3 --retry-all-errors "$url" -o "$TMP/$name" || { FAILED=1; break; }
      MANIFEST_READY=1
      ;;
    *)
      dest="$WEB_ROOT/assets/$name"
      if [ -f "$dest" ] && [ "$(stat -c %s "$dest" 2>/dev/null || stat -f %z "$dest")" = "$size" ]; then
        echo "[mirror-sync] skip (exists): $name"
      else
        echo "[mirror-sync] download: $name (${size} bytes)"
        if fetch_with_retry "$url" "$TMP/$name" "$size" "$name"; then
          mv "$TMP/$name" "$dest"
        else
          echo "[mirror-sync] 放弃: $name（重试耗尽）" >&2
          FAILED=1
        fi
      fi
      ;;
  esac
done < "$TMP/assets.tsv"

# 任一 asset 没就位就绝不换 manifest——否则客户端会拿到指向缺失文件的清单，
# 每次检查更新都下到一半失败。宁可镜像停在旧版本（客户端自动走 GitHub 兜底）。
if [ "$FAILED" = "1" ]; then
  echo "[mirror-sync] 有产物未就位，本次不更新 manifest（客户端继续走 GitHub 兜底）；重跑本脚本可续传补齐" >&2
  exit 1
fi

# assets 全部就位后再原子换 manifest（+签名，先 sig 后 manifest 也无妨，
# 客户端总是成对拉取并验签）
if [ "$MANIFEST_READY" = "1" ]; then
  mv "$TMP/manifest.json.sig" "$WEB_ROOT/manifest.json.sig"
  mv "$TMP/manifest.json" "$WEB_ROOT/manifest.json"
  echo "[mirror-sync] manifest updated"
fi

echo "[mirror-sync] done -> $WEB_ROOT"
