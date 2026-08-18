#!/usr/bin/env bash
# update-mirror-sync.sh — 把最新 GitHub Release 的产物同步到官网镜像
# （增量更新设计 §5.2）。在官网 ECS（8.152.169.44）上运行：发版后手动执行，
# 或挂 cron 每小时一次（幂等，asset 未变时只做 HEAD 级比对开销）。
#
# 同步两类产物：
#   1. 补丁产物 patch-*.tar.gz + manifest.json(.sig) → $WEB_ROOT/assets/（应用内增量更新）
#   2. 安装包 .dmg / .exe → $WEB_ROOT/installers/ + latest.json（官网下载直链，
#      大陆用户打不开 GitHub，官网必须自己发包；留最新两版，更老的同步后清掉——
#      为什么不是只留一版见 prune_old_installers 的注释）
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

# 清理旧安装包：只删「比上一版更老的」，当前版与上一版都留着。
#
# 为什么不能一换 latest.json 就把旧包全删——2026-08-18 发 v0.18.0 时实测到的 404 窗口：
# 官网 /start 的下载按钮是服务端渲染的，数据源 aiworkdeck_website 的 lib/latest-release.ts
# 对本清单用了 `revalidate: 300`。latest.json 已指向新版、旧包已被删，而页面缓存里还是
# 旧版文件名，用户点下载直接 404（stale-while-revalidate 还会让过期后的第一个访客继续
# 拿到旧页面，真实窗口比 300 秒更长）。
# 留一版旧包同时也照顾到「已经点了下载、正在传」的用户：安装包 1.4GB，大陆常要传很久，
# 传到一半文件没了就是断流——这个比 404 更隐蔽。
#
# 入参：$1 = 本版版本号（如 0.18.0），$2 = 本版资产清单 tsv
prune_old_installers() {
  local cur_ver="$1" tsv="$2"
  local prev_ver f base stem

  # 上一版 = 盘上除本版外最高的那个版本号。版本号从文件名里取
  #（AI.WorkDeck-0.18.0-arm64.dmg / AI.WorkDeck.Setup.0.18.0.exe），不认品牌大小写
  # ——0.18.0 起 Workdeck 改成了 WorkDeck，按名字前缀比对会在改名那一版失手。
  # 必须 sort -V：字典序会把 0.9.0 判得比 0.18.0 新。
  prev_ver=$(find "$WEB_ROOT/installers" -maxdepth 1 -type f \( -name '*.dmg' -o -name '*.exe' \) \
    | sed 's#.*/##' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -uV | grep -vFx "$cur_ver" | tail -1 || true)
  [ -n "$prev_ver" ] && echo "[mirror-sync] keep previous installer: $prev_ver"

  while IFS= read -r -d '' f; do
    base="$(basename "$f")"
    stem="${base#.}"; stem="${stem%.part}"
    # 本版的（含正在续传的半成品）：留
    if grep -qF "$stem" "$tsv"; then continue; fi
    # 上一版的成品安装包：留。半成品不留——`.part` 只对还在下的那一版有意义，
    # 旧版的半成品没人会再续传，是纯占盘。
    if [ -n "$prev_ver" ]; then
      case "$base" in
        *"$prev_ver"*.dmg|*"$prev_ver"*.exe) continue ;;
      esac
    fi
    echo "[mirror-sync] prune old installer: $base"
    rm -f "$f"
  done < <(find "$WEB_ROOT/installers" -maxdepth 1 -type f \( -name '*.dmg' -o -name '*.exe' -o -name '.*.part' \) -print0)
}

# 测试只想拿上面的函数，不要跑同步本身（deploy/update-mirror-sync_prune_test.sh）
[ "${MIRROR_SYNC_SOURCE_ONLY:-0}" = "1" ] && return 0

# 全局互斥锁：安装包是 GB 级、大陆拉 GitHub 可能一跑数小时，cron 每小时一发，
# 不加锁必然叠车——2026-08-14 实测三个并发实例互分带宽，每个只剩 ~15KB/s，
# 谁都跑不完。锁不住就静默退出，把机会留给在跑的那个。
exec 9>/var/lock/awd-update-mirror-sync.lock
flock -n 9 || { echo "[mirror-sync] 已有实例在跑（/var/lock/awd-update-mirror-sync.lock），本次退出"; exit 0; }

mkdir -p "$WEB_ROOT/assets" "$WEB_ROOT/installers"
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

# 安装包清单（官网下载直链用；大版本首发只有安装包没有补丁，两类各自独立同步）
python3 - "$TMP/release.json" <<'EOF' > "$TMP/installers.tsv"
import json, sys
rel = json.load(open(sys.argv[1]))
for a in rel.get('assets', []):
    n = a['name']
    if n.endswith('.dmg') or n.endswith('.exe'):
        print(f"{n}\t{a['size']}\t{a['browser_download_url']}")
EOF

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
# 注意不能在此 exit：安装包镜像在后面独立同步，补丁失败不该连坐。
if [ "$FAILED" = "1" ]; then
  echo "[mirror-sync] 有补丁产物未就位，本次不更新 manifest（客户端继续走 GitHub 兜底）；重跑本脚本可续传补齐" >&2
fi

# assets 全部就位后再原子换 manifest（+签名，先 sig 后 manifest 也无妨，
# 客户端总是成对拉取并验签）
if [ "$FAILED" = "0" ] && [ "$MANIFEST_READY" = "1" ]; then
  mv "$TMP/manifest.json.sig" "$WEB_ROOT/manifest.json.sig"
  mv "$TMP/manifest.json" "$WEB_ROOT/manifest.json"
  echo "[mirror-sync] manifest updated"
fi

# ---- 安装包镜像（官网下载直链）----------------------------------------------
# 全部就位才写 latest.json（原子替换，官网只信这份清单，绝不指向缺失文件）；
# 写完再清理更老的安装包（1.4GB 级别，留最新两版，更老的省磁盘）。
# 任一下载失败则保留旧 latest.json 与旧安装包——官网继续发上一版，不发半套。
INSTALLERS_FAILED=0
if [ -s "$TMP/installers.tsv" ]; then
  while IFS=$'\t' read -r name size url; do
    dest="$WEB_ROOT/installers/$name"
    if [ -f "$dest" ] && [ "$(stat -c %s "$dest" 2>/dev/null || stat -f %z "$dest")" = "$size" ]; then
      echo "[mirror-sync] skip (exists): $name"
    else
      # 断点续传的半成品放固定路径而不是 mktemp：GB 级下载常跨越多次 cron，
      # 每轮换一个 TMP 会把已下的几百 MB 全部作废、永远从零开始。
      part="$WEB_ROOT/installers/.$name.part"
      echo "[mirror-sync] download installer: $name (${size} bytes)"
      if fetch_with_retry "$url" "$part" "$size" "$name"; then
        mv "$part" "$dest"
      else
        echo "[mirror-sync] 放弃: $name（重试耗尽，半成品保留供下轮续传）" >&2
        INSTALLERS_FAILED=1
      fi
    fi
  done < "$TMP/installers.tsv"

  if [ "$INSTALLERS_FAILED" = "0" ]; then
    python3 - "$TMP/release.json" <<'EOF' > "$TMP/latest.json"
import json, sys
rel = json.load(open(sys.argv[1]))
out = {
    'tag': rel['tag_name'],
    'publishedAt': rel.get('published_at', ''),
    'assets': [
        {'name': a['name'], 'size': a['size']}
        for a in rel.get('assets', [])
        if a['name'].endswith('.dmg') or a['name'].endswith('.exe')
    ],
}
json.dump(out, sys.stdout, ensure_ascii=False)
EOF
    mv "$TMP/latest.json" "$WEB_ROOT/installers/latest.json"
    echo "[mirror-sync] installers/latest.json updated -> $TAG"

    # 清理旧安装包与过期半成品（只删 .dmg/.exe/.part，不碰 latest.json）
    prune_old_installers "${TAG#v}" "$TMP/installers.tsv"
  else
    echo "[mirror-sync] 安装包未全部就位，latest.json 不更新（官网继续发上一版）；重跑本脚本可续传补齐" >&2
  fi
else
  echo "[mirror-sync] 该 release 没有安装包资产，跳过安装包镜像"
fi

if [ "$FAILED" = "1" ] || [ "$INSTALLERS_FAILED" = "1" ]; then
  exit 1
fi

echo "[mirror-sync] done -> $WEB_ROOT"
