#!/usr/bin/env node
// 发布视频 AI 空镜（B-roll）批量生成工具。dev-board #59。
// 读取 shots.json，调用火山方舟视频生成 API（Seedance 系列模型）批量出片。
// 仅依赖 Node 内置 fetch，不装第三方依赖。
//
// 用法：
//   node generate.mjs --shots A1,B2      只生成指定镜头
//   node generate.mjs --all              生成全部镜头（跳过未填写提示词的预留位）
//   node generate.mjs --all --dry-run    只打印将发出的请求体与预估成本，不调用 API
//   node generate.mjs --help             查看帮助
//
// 鉴权：从环境变量 ARK_API_KEY 读取（Bearer）。绝不硬编码任何密钥，也绝不读取
// .agent/CREDENTIALS.md ——那里放的是账号级 AccessKey/SecretKey，与本工具用的
// Ark API Key 是两回事。获取方式见 README.md。

import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SHOTS_PATH = path.join(__dirname, "shots.json");
const OUT_DIR = path.join(__dirname, "out");
const MANIFEST_PATH = path.join(OUT_DIR, "manifest.json");

const ARK_BASE_URL = process.env.ARK_BASE_URL || "https://ark.cn-beijing.volces.com/api/v3";
const ARK_MODEL_ID = process.env.ARK_MODEL_ID || "doubao-seedance-1-0-pro-fast-251015";
const ARK_API_KEY = process.env.ARK_API_KEY || "";

const PRICE_YUAN_PER_MILLION_TOKENS = 4.2;
const FPS = 24;
// 标准 16:9 分辨率像素数，用于成本预估。与 broll-plan.md 第四节已用官方口径
// 校验过的数字对齐（1080p/5s = 243,000 token，对应官方公布的约 1.03 元）。
// 真实生成完成后，成本以 API 返回的 usage.total_tokens 为准，此表仅用于
// --dry-run 预估与兜底（API 未返回 usage 时）。
const RESOLUTION_DIMENSIONS = {
  "480p": { width: 854, height: 480 },
  "720p": { width: 1280, height: 720 },
  "1080p": { width: 1920, height: 1080 },
};

// 轮询退避参数：起始 5 秒，每次 ×1.5，封顶 30 秒；单个任务最长轮询 15 分钟。
const POLL_INITIAL_MS = 5000;
const POLL_BACKOFF_FACTOR = 1.5;
const POLL_MAX_INTERVAL_MS = 30000;
const POLL_TIMEOUT_MS = 15 * 60 * 1000;

function printHelp() {
  console.log(`发布视频 B-roll 批量生成工具

用法：
  node generate.mjs --shots A1,B2      只生成指定镜头（逗号分隔，不带空格）
  node generate.mjs --all              生成全部镜头（自动跳过未填写提示词的预留位）
  node generate.mjs --all --dry-run    只打印请求体与预估成本，不调用 API，不消耗额度
  node generate.mjs --help             查看本帮助

环境变量：
  ARK_API_KEY    必需（非 --dry-run 时）。方舟控制台「系统管理 -> API Key」创建。
  ARK_MODEL_ID   可选，覆盖默认模型 ${ARK_MODEL_ID}
  ARK_BASE_URL   可选，覆盖默认接口地址 ${ARK_BASE_URL}
`);
}

function parseArgs(argv) {
  const args = { shots: null, all: false, dryRun: false, help: false };
  for (const raw of argv) {
    if (raw === "--all") {
      args.all = true;
    } else if (raw === "--dry-run") {
      args.dryRun = true;
    } else if (raw === "--help" || raw === "-h") {
      args.help = true;
    } else if (raw.startsWith("--shots=")) {
      args.shots = raw.slice("--shots=".length).split(",").map((s) => s.trim()).filter(Boolean);
    } else if (raw === "--shots") {
      // 支持 `--shots A1,B2` 两种写法之一：这里处理不了下一个 argv，交给下面的兜底
      args._expectShotsValue = true;
    } else if (args._expectShotsValue) {
      args.shots = raw.split(",").map((s) => s.trim()).filter(Boolean);
      args._expectShotsValue = false;
    } else {
      console.error(`未识别的参数：${raw}`);
      process.exit(1);
    }
  }
  delete args._expectShotsValue;
  return args;
}

function loadShots() {
  const data = JSON.parse(readFileSync(SHOTS_PATH, "utf8"));
  if (!Array.isArray(data.shots)) {
    throw new Error(`shots.json 格式不对：缺少 shots 数组`);
  }
  return data.shots;
}

function selectShots(allShots, args) {
  if (args.shots) {
    const byId = new Map(allShots.map((s) => [s.id, s]));
    const missing = args.shots.filter((id) => !byId.has(id));
    if (missing.length > 0) {
      throw new Error(`shots.json 中找不到镜头：${missing.join(",")}`);
    }
    return args.shots.map((id) => byId.get(id));
  }
  if (args.all) {
    return allShots;
  }
  throw new Error("必须指定 --shots <id列表> 或 --all，用 --help 查看用法");
}

function estimateTokens(shot) {
  const dims = RESOLUTION_DIMENSIONS[shot.resolution];
  if (!dims) {
    throw new Error(`镜头 ${shot.id} 分辨率 ${shot.resolution} 不在预估表中`);
  }
  return Math.round((dims.width * dims.height * FPS * shot.generateDurationSec) / 1024);
}

function tokensToYuan(tokens) {
  return (tokens / 1_000_000) * PRICE_YUAN_PER_MILLION_TOKENS;
}

function buildRequestBody(shot) {
  const content = [{ type: "text", text: shot.prompt }];
  if (shot.firstFrameImagePath) {
    content.push({
      type: "image_url",
      image_url: { url: shot.firstFrameImagePath },
    });
  }
  return {
    model: ARK_MODEL_ID,
    content,
    // 分辨率/时长/宽高比用官方推荐的独立字段写法（强校验），不采用文本追加
    // --参数 的旧式弱校验写法。
    resolution: shot.resolution,
    ratio: shot.ratio,
    duration: shot.generateDurationSec,
    // 不加水印：AI 生成水印本身也是一段文字，会违反「画面无任何可读文字」红线。
    watermark: false,
    camera_fixed: false,
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function createTask(body) {
  const res = await fetch(`${ARK_BASE_URL}/contents/generations/tasks`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${ARK_API_KEY}`,
    },
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    throw new Error(`创建任务失败 HTTP ${res.status}：${JSON.stringify(json)}`);
  }
  if (!json || !json.id) {
    throw new Error(`创建任务响应异常：${JSON.stringify(json)}`);
  }
  return json.id;
}

async function pollTask(taskId) {
  const startedAt = Date.now();
  let interval = POLL_INITIAL_MS;
  while (true) {
    if (Date.now() - startedAt > POLL_TIMEOUT_MS) {
      throw new Error(`任务 ${taskId} 轮询超时（超过 ${POLL_TIMEOUT_MS / 1000}秒）`);
    }
    const res = await fetch(`${ARK_BASE_URL}/contents/generations/tasks/${taskId}`, {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${ARK_API_KEY}`,
      },
    });
    const json = await res.json().catch(() => null);
    if (!res.ok) {
      throw new Error(`查询任务失败 HTTP ${res.status}：${JSON.stringify(json)}`);
    }
    const status = json?.status;
    if (status === "succeeded") {
      return json;
    }
    if (status === "failed" || status === "expired") {
      throw new Error(`任务 ${taskId} 状态为 ${status}：${JSON.stringify(json?.error || json)}`);
    }
    console.log(`  轮询中，状态：${status}，${Math.round(interval / 1000)}秒后重试`);
    await sleep(interval);
    interval = Math.min(interval * POLL_BACKOFF_FACTOR, POLL_MAX_INTERVAL_MS);
  }
}

async function downloadVideo(url, destPath) {
  const res = await fetch(url);
  if (!res.ok) {
    throw new Error(`下载视频失败 HTTP ${res.status}`);
  }
  const buf = Buffer.from(await res.arrayBuffer());
  writeFileSync(destPath, buf);
}

function loadManifest() {
  if (!existsSync(MANIFEST_PATH)) {
    return [];
  }
  try {
    const data = JSON.parse(readFileSync(MANIFEST_PATH, "utf8"));
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
}

function saveManifest(manifest) {
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(MANIFEST_PATH, JSON.stringify(manifest, null, 2), "utf8");
}

async function runDryRun(shots) {
  let totalTokens = 0;
  for (const shot of shots) {
    if (!shot.prompt) {
      console.log(`[${shot.id}] 预留位，尚未填写提示词，跳过`);
      continue;
    }
    const body = buildRequestBody(shot);
    const tokens = estimateTokens(shot);
    const yuan = tokensToYuan(tokens);
    totalTokens += tokens;
    console.log(`\n[${shot.id}] ${shot.title}`);
    console.log(`请求体：`);
    console.log(JSON.stringify(body, null, 2));
    console.log(`预估 token：${tokens.toLocaleString()}，预估成本：${yuan.toFixed(4)} 元`);
  }
  console.log(`\n===== dry-run 汇总 =====`);
  console.log(`模型：${ARK_MODEL_ID}`);
  console.log(`接口：${ARK_BASE_URL}/contents/generations/tasks`);
  console.log(`本次镜头数：${shots.filter((s) => s.prompt).length}`);
  console.log(`预估总 token：${totalTokens.toLocaleString()}`);
  console.log(`预估总成本：${tokensToYuan(totalTokens).toFixed(4)} 元`);
}

async function runReal(shots) {
  if (!ARK_API_KEY) {
    console.error(
      `未设置环境变量 ARK_API_KEY。\n请先到方舟控制台「系统管理 -> API Key」创建一把 Key，` +
        `然后：\n  export ARK_API_KEY=你的key\n再重新运行本脚本。`
    );
    process.exit(1);
  }
  mkdirSync(OUT_DIR, { recursive: true });
  const manifest = loadManifest();
  let totalCostYuan = 0;

  // 并发限额为 1，必须串行，逐个镜头跑完再跑下一个。
  for (const shot of shots) {
    if (!shot.prompt) {
      console.log(`[${shot.id}] 预留位，尚未填写提示词，跳过`);
      continue;
    }
    console.log(`\n[${shot.id}] ${shot.title} 开始生成`);
    const body = buildRequestBody(shot);
    const startedAt = Date.now();
    const entry = {
      id: shot.id,
      group: shot.group,
      title: shot.title,
      prompt: shot.prompt,
      model: ARK_MODEL_ID,
      resolution: shot.resolution,
      ratio: shot.ratio,
      durationSec: shot.generateDurationSec,
      startedAt: new Date(startedAt).toISOString(),
    };
    try {
      const taskId = await createTask(body);
      entry.taskId = taskId;
      console.log(`  任务已创建：${taskId}`);
      const result = await pollTask(taskId);
      const elapsedMs = Date.now() - startedAt;
      const timestamp = new Date(startedAt).toISOString().replace(/[:.]/g, "-");
      const filename = `${shot.id}-${timestamp}.mp4`;
      const destPath = path.join(OUT_DIR, filename);
      await downloadVideo(result.content.video_url, destPath);

      const usageTokens = result?.usage?.total_tokens;
      const costYuan = usageTokens != null ? tokensToYuan(usageTokens) : tokensToYuan(estimateTokens(shot));

      entry.status = "succeeded";
      entry.elapsedMs = elapsedMs;
      entry.videoFile = filename;
      entry.tokens = usageTokens ?? null;
      entry.costYuan = Number(costYuan.toFixed(4));
      totalCostYuan += costYuan;
      console.log(`  完成，耗时 ${(elapsedMs / 1000).toFixed(1)}秒，已保存到 out/${filename}`);
    } catch (err) {
      entry.status = "failed";
      entry.error = String(err?.message || err);
      console.error(`  [${shot.id}] 失败：${entry.error}`);
    } finally {
      entry.finishedAt = new Date().toISOString();
      manifest.push(entry);
      saveManifest(manifest);
    }
  }

  console.log(`\n===== 本次运行汇总 =====`);
  console.log(`本次总成本：${totalCostYuan.toFixed(4)} 元`);
  console.log(`详情见 out/manifest.json`);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    printHelp();
    return;
  }
  const allShots = loadShots();
  const shots = selectShots(allShots, args);

  if (args.dryRun) {
    await runDryRun(shots);
  } else {
    await runReal(shots);
  }
}

main().catch((err) => {
  console.error(`执行失败：${err?.message || err}`);
  process.exit(1);
});
