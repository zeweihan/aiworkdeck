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
# 不能用 releases/latest：它是仓库级「最新」，native pack 的 release
# （tag pack-*-v*，见 pack-release.yml）也算数。2026-08-19 实测：pack 上架后到
# 下一个应用版发布前的 2.5 小时里，releases/latest 一直返回 pack release，
# 本脚本连挂三轮 cron（pack 带 manifest.json 却没有 .sig，mv 直接炸），
# 镜像停更、官网 /start 发旧版。改为列出 releases 后只认 tag 形如 v<数字> 的
# 正式应用版。per_page=15 足够：应用版最密一天三发，15 条怎么也罩得住。
API="https://api.github.com/repos/${REPO}/releases?per_page=15"

# 回调官网 revalidate 端点用的配置（AWD_REVALIDATE_URLS / AWD_REVALIDATE_TOKEN）。
# 不能放 WEB_ROOT 下：nginx 那个 location 只 deny .sh/.py，其它文件名公网可下载。
if [ -f /etc/aiworkdeck/mirror-sync.env ]; then . /etc/aiworkdeck/mirror-sync.env; fi

# latest.json 落地后回调官网，立即作废 /start 下载链的 fetch 缓存
# （官网仓 app/api/revalidate-release，tag 'latest-release'）。没配置就跳过；
# 回调失败只记日志——官网自身 revalidate:300 兜底，最迟 5 分钟自愈。
notify_website() {
  [ -n "${AWD_REVALIDATE_URLS:-}" ] || return 0
  local u
  for u in $AWD_REVALIDATE_URLS; do
    if curl -sf -m 10 -X POST -H "authorization: Bearer ${AWD_REVALIDATE_TOKEN:-}" "$u" >/dev/null; then
      echo "[mirror-sync] revalidate 回调成功: $u"
    else
      echo "[mirror-sync] revalidate 回调失败（忽略，页面最迟 5 分钟自行刷新）: $u" >&2
    fi
  done
}

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

# 发版推送进行中就让路：tag 构建的 release job 会把安装包从境外 runner 直推上来
# （上行不受境内拉 GitHub 那 ~12KB/s 的限制）。这个窗口里 cron 再去 GitHub 拉同一批
# 包不但纯属浪费，还会**先抢到下面那把锁再拉上一小时**，把随后来收尾的 CI 卡在
# flock 上超时——latest.json 停在上一版、job 转红、官网下载页继续发旧版。
# v0.17.0 踩过一次（当时只加了 CI 侧的 flock 等待），v0.23.0 又踩一次：cron 在
# 16:17 开跑（那一刻 exe 还没推完，它的 skip-exists 判据当时确实不成立），
# CI 等锁 1200s 超时，官网下载页停在 0.22.0 直到人工介入。
# 所以让路要发生在**抢锁之前**：CI 推送前落 marker、收尾后删除。
# FORCE=1 是 CI 自己回头调用本脚本时用的，marker 拦不住它。
# marker 放 /var/lock 不放 WEB_ROOT——那个 location 只 deny .sh/.py，别的文件名公网可下。
PUSH_MARKER="${PUSH_MARKER:-/var/lock/awd-release-push-in-progress}"
# mtime 取值分两种 stat：GNU（线上 Linux）与 BSD（开发机 macOS 跑单测）。
# **两种都拿不到时按「新鲜」处理**：这里唯一的安全方向是让路。反过来兜底成
# 「已过期」等于 stat 一变脸这道闸就悄悄失效，又回到 v0.23.0 那个坑，
# 而且不会有任何告警。真正防「marker 永久残留」的是下面的 90 分钟上限。
marker_mtime() { stat -c%Y "$1" 2>/dev/null || stat -f%m "$1" 2>/dev/null; }
if [ "${FORCE:-0}" != "1" ] && [ -f "$PUSH_MARKER" ]; then
  marker_mt="$(marker_mtime "$PUSH_MARKER")"
  if [ -z "$marker_mt" ]; then
    echo "[mirror-sync] 读不到 marker 的 mtime，按新鲜处理并让路（安全方向）" >&2
    exit 0
  fi
  marker_age=$(( $(date +%s) - marker_mt ))
  # 上限按「最慢的一次整包推送」留：3GB 两个包实测 ~25 分钟，90 分钟足够宽。
  # 超过就当 CI 半路死了没清理，忽略 marker 照常跑，避免镜像因为一个残留文件永久停更。
  if [ "$marker_age" -lt 5400 ]; then
    echo "[mirror-sync] 发版推送进行中（marker ${marker_age}s 前落下），本次让路"
    exit 0
  fi
  echo "[mirror-sync] marker 已过期（${marker_age}s），当作残留忽略" >&2
fi

# 全局互斥锁：安装包是 GB 级、大陆拉 GitHub 可能一跑数小时，cron 每小时一发，
# 不加锁必然叠车——2026-08-14 实测三个并发实例互分带宽，每个只剩 ~15KB/s，
# 谁都跑不完。锁不住就静默退出，把机会留给在跑的那个。
# 锁路径可覆盖只为让单测能在开发机上跑（macOS 没有 /var/lock）；线上不要设它，
# 换了路径就等于没锁——cron 与 CI 各锁各的，叠车照旧。
LOCK_FILE="${LOCK_FILE:-/var/lock/awd-update-mirror-sync.lock}"
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "[mirror-sync] 已有实例在跑（$LOCK_FILE），本次退出"; exit 0; }

mkdir -p "$WEB_ROOT/assets" "$WEB_ROOT/installers"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# cron 重定向进同一个日志文件，没有时间戳就没法做事后取证
#（2026-08-19 复盘时全靠文件 mtime 反推时间线）
echo "[mirror-sync] run at $(date '+%F %T')"
echo "[mirror-sync] fetching latest release metadata: $REPO"
curl -sfL "$API" -o "$TMP/releases.json"
# 列表按 created_at 倒序；挑第一个非草稿、非预发布、tag 形如 v<数字> 的应用版
python3 - "$TMP/releases.json" <<'EOF' > "$TMP/release.json"
import json, re, sys
for r in json.load(open(sys.argv[1])):
    if r.get('draft') or r.get('prerelease'):
        continue
    if re.match(r'^v\d', r.get('tag_name', '')):
        json.dump(r, sys.stdout)
        break
else:
    sys.exit('[mirror-sync] 列表里没有 tag 形如 v<数字> 的应用 release，放弃本次同步')
EOF
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
# 客户端总是成对拉取并验签）。两个文件必须都在才动手——2026-08-19 pack release
# 恰好带一个（pack 契约的）manifest.json 而没有 .sig，单腿落地会把桌面端
# 更新通道的 manifest 换成 pack 的；当时全靠 mv sig 在前先炸救了一命。
if [ "$FAILED" = "0" ] && [ "$MANIFEST_READY" = "1" ] \
   && [ -f "$TMP/manifest.json" ] && [ -f "$TMP/manifest.json.sig" ]; then
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
    notify_website

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
