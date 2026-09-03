// 仓库流量快照：GitHub traffic API 只保留 14 天，本脚本把 views/clones/referrers/paths
// 与 star/fork 数拼成一行 JSON 追加到 metrics/traffic.jsonl（由 repo-metrics 工作流每周
// 跑一次并推到 repo-metrics 分支）。traffic 端点要求 push 权限：Actions 的 GITHUB_TOKEN
// 对本仓库有 contents:write 即可；若日后权限收紧，加 STAR_TOKEN（fine-grained，
// Administration read）接管。本地试跑：GITHUB_TOKEN=$(gh auth token) node scripts/repo-metrics.mjs
import { mkdir, appendFile } from 'node:fs/promises';
import { dirname } from 'node:path';

const token = process.env.STAR_TOKEN || process.env.GITHUB_TOKEN;
const repo = process.env.GITHUB_REPOSITORY || 'zeweihan/aiworkdeck';
const out = process.env.METRICS_FILE || 'metrics/traffic.jsonl';
if (!token) throw new Error('GITHUB_TOKEN or STAR_TOKEN required');

async function api(path) {
  const res = await fetch(`https://api.github.com/repos/${repo}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
    },
  });
  if (!res.ok) throw new Error(`${path}: ${res.status} ${await res.text()}`);
  return res.json();
}

const [meta, views, clones, referrers, paths] = await Promise.all([
  api(''),
  api('/traffic/views'),
  api('/traffic/clones'),
  api('/traffic/popular/referrers'),
  api('/traffic/popular/paths'),
]);

const snapshot = {
  captured_at: new Date().toISOString(),
  stars: meta.stargazers_count,
  forks: meta.forks_count,
  watchers: meta.subscribers_count,
  views_14d: { count: views.count, uniques: views.uniques },
  clones_14d: { count: clones.count, uniques: clones.uniques },
  views_daily: views.views.map((v) => ({ d: v.timestamp.slice(0, 10), c: v.count, u: v.uniques })),
  referrers: referrers.map((r) => ({ ref: r.referrer, c: r.count, u: r.uniques })),
  paths: paths.map((p) => ({ path: p.path, c: p.count, u: p.uniques })),
};

await mkdir(dirname(out), { recursive: true });
await appendFile(out, JSON.stringify(snapshot) + '\n');

const top = snapshot.referrers.slice(0, 5).map((r) => `${r.ref}=${r.u}`).join(', ');
console.log(`[repo-metrics] ${repo} stars=${snapshot.stars} views14d=${snapshot.views_14d.count}/${snapshot.views_14d.uniques}u referrers: ${top}`);
console.log(`[repo-metrics] appended -> ${out}`);
