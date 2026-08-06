#!/usr/bin/env bash
# update-mirror-sync.sh — 把最新 GitHub Release 的补丁产物同步到官网镜像
# （增量更新设计 §5.2）。在官网 ECS（8.152.169.44）上运行：发版后手动执行，
# 或挂 cron 每小时一次（幂等，asset 未变时只做 HEAD 级比对开销）。
#
#   WEB_ROOT=/var/www/aiworkdeck/update/desktop bash deploy/update-mirror-sync.sh
#
# nginx 需将 https://www.aiworkdeck.com/update/desktop/ 指到 $WEB_ROOT。
# manifest.json 最后原子替换（先 assets 后 manifest，客户端不会拿到指向
# 尚未就位 asset 的清单）。

set -euo pipefail

REPO="${REPO:-zeweihan/aiworkdeck}"
WEB_ROOT="${WEB_ROOT:-/var/www/aiworkdeck/update/desktop}"
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

MANIFEST_READY=0
while IFS=$'\t' read -r name size url; do
  case "$name" in
    manifest.json|manifest.json.sig)
      curl -sfL "$url" -o "$TMP/$name"
      MANIFEST_READY=1
      ;;
    *)
      dest="$WEB_ROOT/assets/$name"
      if [ -f "$dest" ] && [ "$(stat -c %s "$dest" 2>/dev/null || stat -f %z "$dest")" = "$size" ]; then
        echo "[mirror-sync] skip (exists): $name"
      else
        echo "[mirror-sync] download: $name (${size} bytes)"
        curl -sfL "$url" -o "$TMP/$name"
        mv "$TMP/$name" "$dest"
      fi
      ;;
  esac
done < "$TMP/assets.tsv"

# assets 全部就位后再原子换 manifest（+签名，先 sig 后 manifest 也无妨，
# 客户端总是成对拉取并验签）
if [ "$MANIFEST_READY" = "1" ]; then
  mv "$TMP/manifest.json.sig" "$WEB_ROOT/manifest.json.sig"
  mv "$TMP/manifest.json" "$WEB_ROOT/manifest.json"
  echo "[mirror-sync] manifest updated"
fi

echo "[mirror-sync] done -> $WEB_ROOT"
