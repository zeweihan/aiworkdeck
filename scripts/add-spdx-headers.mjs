#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// 给全仓一方源码补 SPDX 双行头（dev-board#505/#506）。
// 幂等：已有 SPDX-License-Identifier 的文件一律跳过不动。
// 用法：
//   node scripts/add-spdx-headers.mjs --dry-run   只打印将改动的文件数（按扩展名分组），不写盘
//   node scripts/add-spdx-headers.mjs             真正写盘

import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import {
  isPathExcluded,
  getHandler,
  hasExistingSpdxHeader,
  buildHeaderLines,
} from './spdx-scope.mjs';

const dryRun = process.argv.includes('--dry-run');

const repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
  encoding: 'utf8',
}).trim();

function insertHeader(content, handler) {
  const headerLines = buildHeaderLines(handler);
  const usesCrlf = content.includes('\r\n');
  const normalized = usesCrlf ? content.replace(/\r\n/g, '\n') : content;
  const lines = normalized.split('\n');

  let insertAt = 0;
  let blankAfter = false;

  switch (handler.placement) {
    case 'before-package': {
      const idx = lines.findIndex((l) => l.trim().startsWith('package '));
      insertAt = idx >= 0 ? idx : 0;
      blankAfter = true;
      break;
    }
    case 'top-after-shebang': {
      insertAt = lines[0] && lines[0].startsWith('#!') ? 1 : 0;
      break;
    }
    case 'top-after-shebang-or-coding': {
      let i = 0;
      if (lines[0] && lines[0].startsWith('#!')) i = 1;
      if (lines[i] !== undefined && /^#.*coding[:=]/.test(lines[i])) i += 1;
      insertAt = i;
      break;
    }
    case 'top': {
      insertAt = 0;
      break;
    }
    case 'after-doctype': {
      insertAt = lines[0] && /^<!doctype/i.test(lines[0].trim()) ? 1 : 0;
      break;
    }
    default: {
      insertAt = 0;
    }
  }

  const toInsert = blankAfter ? [...headerLines, ''] : [...headerLines];
  lines.splice(insertAt, 0, ...toInsert);
  let result = lines.join('\n');
  if (usesCrlf) result = result.replace(/\n/g, '\r\n');
  return result;
}

function main() {
  const filesOutput = execFileSync('git', ['ls-files'], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  const allFiles = filesOutput.split('\n').filter(Boolean);

  const changedByExt = new Map();
  let changedTotal = 0;
  let alreadyHadHeader = 0;
  let excludedCount = 0;
  let unsupportedExt = 0;

  for (const relPath of allFiles) {
    const handler = getHandler(relPath);
    if (!handler) {
      unsupportedExt++;
      continue;
    }
    if (isPathExcluded(relPath)) {
      excludedCount++;
      continue;
    }

    const abs = path.join(repoRoot, relPath);
    let content;
    try {
      content = readFileSync(abs, 'utf8');
    } catch {
      continue;
    }

    if (hasExistingSpdxHeader(content)) {
      alreadyHadHeader++;
      continue;
    }

    const ext = relPath.slice(relPath.lastIndexOf('.'));
    changedByExt.set(ext, (changedByExt.get(ext) || 0) + 1);
    changedTotal++;

    if (!dryRun) {
      const updated = insertHeader(content, handler);
      writeFileSync(abs, updated, 'utf8');
    }
  }

  console.log(dryRun ? '=== dry-run：将改动的文件（按扩展名） ===' : '=== 已写盘的文件（按扩展名） ===');
  const exts = [...changedByExt.keys()].sort();
  for (const ext of exts) {
    console.log(`  ${ext}: ${changedByExt.get(ext)}`);
  }
  console.log(`合计: ${changedTotal}`);
  console.log(`已有 SPDX 头跳过: ${alreadyHadHeader}`);
  console.log(`落在排除范围跳过: ${excludedCount}`);
  console.log(`扩展名不支持跳过: ${unsupportedExt}`);
}

main();
