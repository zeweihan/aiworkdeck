// fetch-drawio-assets.js — 把 draw.io 编辑器烙进前端产物，供「诉讼可视化」出的
// .drawio 文件在应用内直接编辑（离线，不出网）。
//
// WHY 内嵌而不是跳外部程序：出图产物里 .drawio 是唯一的「可继续编辑版」。指望用户
// 自己装 draw.io 桌面版等于把这个格式变成死文件；跳 app.diagrams.net 则意味着案件
// 材料要出网——法律工具不能有这条路径。所以离线内嵌是唯一说得通的形态。
//
// WHY 裁剪：官方只发 draw.war（53 MB），解开是 151 MB。里面大半是我们用不到的东西：
// AWS/Azure/Cisco/机架/楼层平面等扩展模具（stencils/，41 MB）、模板库（templates/，
// 5.4 MB）、Java servlet（WEB-INF/，5.2 MB）、未压缩的开发态源码（js/diagramly、
// js/grapheditor、mxgraph/src，13 MB）、以及 viewer / integrate / mermaid / plantuml /
// orgchart 等另几套入口的 bundle（30 MB+）。诉讼图要的是主编辑器 + 默认图形，
// 裁完约 45 MB 落盘、约 20 MB 压缩。**裁剪白名单是实测出来的**（逐个补齐 404 直到
// 编辑器能起、能读能存能导出 SVG），不是照着目录名猜的——改动白名单必须重新实测。
//
// WHY 自带解压：本脚本在 CI 里跑在 `npm ci`（desktop）之前，那时没有任何第三方依赖
// 可用；而 macOS/Windows 两个 runner 的 tar 行为并不一致（bsdtar 能解 zip，GNU tar
// 不能）。所以这里自带一个最小 zip 读取器，只依赖 node 内置的 zlib。
//
// 产物落点（electron-builder 经 extraResources 装包，见 desktop/package.json）：
//   frontend/dist/drawio/…                 裁剪后的 draw.io webapp
//   frontend/dist/drawio/.drawio-version    版本标记，用于幂等跳过
//
// Usage:  node desktop/scripts/fetch-drawio-assets.js   （幂等；版本与校验都过就跳过）
//         DRAWIO_WAR_URL=file:///abs/path/draw.war 可覆盖来源（离线构建 / 自建包）
// 路径相对本脚本解析，cwd 无关。

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const http = require('http');
const https = require('https');

// 钉死版本：draw.io 的 min.js 会随版本改名（js/shapes-<ver>.min.js），白名单靠模式
// 匹配兜住，但升级仍必须重新实测一遍「能起、能存、能导出」。
const DRAWIO_VERSION = 'v31.1.8';
const DEFAULT_WAR_URL =
  `https://github.com/jgraph/drawio/releases/download/${DRAWIO_VERSION}/draw.war`;

const OUT_DIR = path.join(__dirname, '../../frontend/dist/drawio');
const VERSION_FILE = path.join(OUT_DIR, '.drawio-version');

// draw.war 的下限。低于这个说明下到的是错误页/被截断的响应，而不是包。
const MIN_WAR_BYTES = 40 * 1024 * 1024;

// ---------------------------------------------------------------------------
// 裁剪白名单
// ---------------------------------------------------------------------------

// 整目录保留。img/ 与 images/ 是图形面板的缩略图与 UI 图标，少了面板就是一片空白；
// shapes/ 是默认图形的矢量定义；styles/ 是编辑器样式；plugins/ 体积很小但去掉会让
// 若干菜单项报错；mxgraph/css/ 只有 8 KB，缺了 common.css 控制台会报 404；
// math4/ 是 MathJax，drawio 启动时无条件预载它，不带就每次开图都吐一条 404。
const DIR_PREFIXES = [
  'styles/',
  'images/',
  'img/',
  'shapes/',
  'plugins/',
  'mxgraph/css/',
  'math4/',
];

// 单文件保留。
const EXACT_FILES = new Set([
  'index.html',
  'favicon.ico',
  // dia.txt 就是英文默认词条（没有 dia_en.txt），dia_zh.txt 是中文界面。
  // 其余 58 个语种一律不带。
  'resources/dia.txt',
  'resources/dia_zh.txt',
  // 启动链：index.html -> bootstrap.js -> main.js，配置钩子在 Pre/PostConfig。
  'js/bootstrap.js',
  'js/main.js',
  'js/PreConfig.js',
  'js/PostConfig.js',
  'js/clear.js',
  'js/open.js',
  'js/export-init.js',
  'js/export.js',
  // 主编辑器 bundle 与默认图形库。
  'js/app.min.js',
  'js/stencils.min.js',
  'js/extensions.min.js',
]);

// 带版本号的文件名（js/shapes-14-6-5.min.js）——按模式收，免得升级时漏。
const PATTERNS = [/^js\/shapes-[\d-]+\.min\.js$/];

// 解压后必须存在的文件。缺任何一个都说明上游改了布局，与其发一个开不起来的编辑器，
// 不如在构建期当场失败。
const REQUIRED = [
  'index.html',
  'js/bootstrap.js',
  'js/main.js',
  'js/app.min.js',
  'js/stencils.min.js',
  'resources/dia_zh.txt',
  'mxgraph/css/common.css',
];

// 裁剪后的体积下限（实测约 45 MB）。明显偏小说明白名单没匹配上。
const MIN_OUT_BYTES = 30 * 1024 * 1024;

function wanted(name) {
  if (name.endsWith('/')) return false;
  if (EXACT_FILES.has(name)) return true;
  if (DIR_PREFIXES.some((p) => name.startsWith(p))) return true;
  return PATTERNS.some((re) => re.test(name));
}

// ---------------------------------------------------------------------------
// 下载
// ---------------------------------------------------------------------------

function download(url, redirects = 0) {
  if (url.startsWith('file://')) {
    return Promise.resolve(fs.readFileSync(new URL(url)));
  }
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('http:') ? http : https;
    mod
      .get(url, { headers: { 'User-Agent': 'aiworkdeck-build' } }, (res) => {
        const sc = res.statusCode || 0;
        if (sc >= 300 && sc < 400 && res.headers.location && redirects < 5) {
          res.resume();
          return resolve(download(new URL(res.headers.location, url).toString(), redirects + 1));
        }
        if (sc !== 200) {
          res.resume();
          return reject(new Error(`下载失败 HTTP ${sc}: ${url}`));
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks)));
        res.on('error', reject);
      })
      .on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// 最小 zip 读取器
//
// 只支持我们真正会遇到的那一档：非 ZIP64、无加密、stored(0) 或 deflate(8)。
// 越界的情况一律显式抛错——静默错解出来的编辑器只会在用户机器上坏掉。
// ---------------------------------------------------------------------------

const SIG_EOCD = 0x06054b50;
const SIG_CDH = 0x02014b50;
const SIG_LFH = 0x04034b50;

function findEocd(buf) {
  // EOCD 定长 22 字节，尾部可跟最长 65535 字节的注释，所以最多往回找 65557。
  const start = Math.max(0, buf.length - 65557);
  for (let i = buf.length - 22; i >= start; i--) {
    if (buf.readUInt32LE(i) === SIG_EOCD) return i;
  }
  throw new Error('不是有效的 zip：找不到 EOCD');
}

function* centralEntries(buf) {
  const eocd = findEocd(buf);
  const total = buf.readUInt16LE(eocd + 10);
  const cdOffset = buf.readUInt32LE(eocd + 16);
  if (cdOffset === 0xffffffff || total === 0xffff) {
    throw new Error('这个包是 ZIP64，本读取器不支持');
  }
  let p = cdOffset;
  for (let i = 0; i < total; i++) {
    if (buf.readUInt32LE(p) !== SIG_CDH) throw new Error('中央目录损坏 @' + p);
    const flags = buf.readUInt16LE(p + 8);
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    yield { name, method, compSize, localOffset, flags };
    p += 46 + nameLen + extraLen + commentLen;
  }
}

function readEntry(buf, entry) {
  if (entry.flags & 0x1) throw new Error('包内有加密条目: ' + entry.name);
  const p = entry.localOffset;
  if (buf.readUInt32LE(p) !== SIG_LFH) throw new Error('本地头损坏: ' + entry.name);
  // 本地头里的 name/extra 长度可能与中央目录不同（extra 尤其常见），必须读本地头的。
  const nameLen = buf.readUInt16LE(p + 26);
  const extraLen = buf.readUInt16LE(p + 28);
  const dataStart = p + 30 + nameLen + extraLen;
  const raw = buf.subarray(dataStart, dataStart + entry.compSize);
  if (entry.method === 0) return Buffer.from(raw);
  if (entry.method === 8) return zlib.inflateRawSync(raw);
  throw new Error(`不支持的压缩方式 ${entry.method}: ${entry.name}`);
}

// ---------------------------------------------------------------------------

function rmrf(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

function dirSize(dir) {
  let total = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    total += e.isDirectory() ? dirSize(p) : fs.statSync(p).size;
  }
  return total;
}

function alreadyGood() {
  try {
    if (fs.readFileSync(VERSION_FILE, 'utf8').trim() !== DRAWIO_VERSION) return false;
    return REQUIRED.every((r) => fs.existsSync(path.join(OUT_DIR, r)));
  } catch (e) {
    return false;
  }
}

async function main() {
  if (alreadyGood()) {
    console.log(`draw.io ${DRAWIO_VERSION} 已就位，跳过（${(dirSize(OUT_DIR) / 1048576).toFixed(1)} MB）`);
    return;
  }

  const url = process.env.DRAWIO_WAR_URL || DEFAULT_WAR_URL;
  console.log(`下载 draw.io ${DRAWIO_VERSION}: ${url}`);
  const war = await download(url);
  if (war.length < MIN_WAR_BYTES) {
    throw new Error(`draw.war 只有 ${war.length} 字节，明显不对（下限 ${MIN_WAR_BYTES}）`);
  }
  console.log(`  收到 ${(war.length / 1048576).toFixed(1)} MB，开始裁剪解包`);

  rmrf(OUT_DIR);
  fs.mkdirSync(OUT_DIR, { recursive: true });

  let kept = 0;
  let skipped = 0;
  for (const entry of centralEntries(war)) {
    if (!wanted(entry.name)) {
      skipped++;
      continue;
    }
    // 路径穿越防护：zip 里的名字是不可信输入。
    const dest = path.normalize(path.join(OUT_DIR, entry.name));
    if (dest !== OUT_DIR && !dest.startsWith(OUT_DIR + path.sep)) {
      throw new Error('包内条目试图逃出目标目录: ' + entry.name);
    }
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, readEntry(war, entry));
    kept++;
  }

  const missing = REQUIRED.filter((r) => !fs.existsSync(path.join(OUT_DIR, r)));
  if (missing.length) {
    throw new Error(
      '上游布局变了，这些必需文件没解出来：' + missing.join(', ') +
      '\n（升级 draw.io 后要重新核对 DIR_PREFIXES / EXACT_FILES / PATTERNS）'
    );
  }
  const size = dirSize(OUT_DIR);
  if (size < MIN_OUT_BYTES) {
    throw new Error(`裁剪后只有 ${(size / 1048576).toFixed(1)} MB，低于下限，白名单大概率没匹配上`);
  }

  fs.writeFileSync(VERSION_FILE, DRAWIO_VERSION + '\n');
  console.log(`  保留 ${kept} 个文件，丢弃 ${skipped} 个，共 ${(size / 1048576).toFixed(1)} MB`);
  console.log(`draw.io 就位: ${OUT_DIR}`);
}

main().catch((e) => {
  console.error('fetch-drawio-assets 失败:', e.message);
  process.exit(1);
});
