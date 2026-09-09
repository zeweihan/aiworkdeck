#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// CI 护栏：新增的一方源码文件必须带 SPDX-License-Identifier，缺失即非零退出。
// 排除规则与 add-spdx-headers.mjs 共用 scripts/spdx-scope.mjs，避免两份判定跑偏。
//
// 用法：
//   node scripts/check-spdx.mjs                      默认对比 <base>...HEAD 里新增的文件
//   node scripts/check-spdx.mjs file1 file2 ...       只检查给定的文件列表（供测试/手工用）
//
// base 取 origin/master；本地没有 origin 时退回 HEAD~1。

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { isPathExcluded, getHandler, hasExistingSpdxHeader } from './spdx-scope.mjs';

const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
  encoding: 'utf8',
}).trim();

function resolveBase() {
  try {
    execFileSync('git', ['rev-parse', '--verify', 'origin/master'], {
      cwd: repoRoot,
      stdio: 'pipe',
    });
    return 'origin/master';
  } catch {
    return 'HEAD~1';
  }
}

function getAddedFiles(base) {
  const output = execFileSync(
    'git',
    ['diff', '--name-only', '--diff-filter=A', `${base}...HEAD`],
    { cwd: repoRoot, encoding: 'utf8' },
  );
  return output.split('\n').filter(Boolean);
}

function main() {
  const argFiles = process.argv.slice(2);
  const files = argFiles.length > 0 ? argFiles : getAddedFiles(resolveBase());

  const missing = [];
  let checked = 0;

  for (const relPath of files) {
    const handler = getHandler(relPath);
    if (!handler) continue;
    if (isPathExcluded(relPath)) continue;

    const abs = path.join(repoRoot, relPath);
    let content;
    try {
      content = readFileSync(abs, 'utf8');
    } catch {
      continue; // 文件已被后续提交删除等边界情况
    }

    checked++;
    if (!hasExistingSpdxHeader(content)) {
      missing.push(relPath);
    }
  }

  if (missing.length > 0) {
    console.error(`发现 ${missing.length} 个新增文件缺少 SPDX-License-Identifier：`);
    for (const f of missing) console.error(`  ${f}`);
    console.error('\n请在文件顶部加 SPDX 双行头（格式与位置见 CLAUDE.md / REUSE.toml），或跑：');
    console.error('  node scripts/add-spdx-headers.mjs');
    process.exit(1);
  }

  console.log(`SPDX 检查通过：检查了 ${checked} 个新增文件，全部带头。`);
}

main();
