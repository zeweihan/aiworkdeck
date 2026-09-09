// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// 共享模块：add-spdx-headers.mjs 与 check-spdx.mjs 共用同一套"谁该有头"的判定，
// 避免两份规则跑偏。只描述范围与格式，不做文件 I/O。

export const COPYRIGHT_LINE =
  '2026 北京京微资易科技有限公司 and AI WorkDeck contributors';
export const LICENSE_ID = 'AGPL-3.0-or-later';

// 整目录排除（相对仓库根的 posix 路径前缀，必须以 "/" 结尾）。
// 覆盖：生成物/第三方 vendor/数据/文档/被其他子代理处理中的文件。
export const EXCLUDED_DIR_PREFIXES = [
  'data/',
  'docs/',
  'experiments/',
  '.claude/',
  'litviz/skills/mqc-litigation-visual-redraw/', // 上游 vendor（重画引擎，MIT，见 litviz/UPSTREAM.md）
  'litviz/skills/mqc-timeline-master/', // 上游 vendor（时间轴大师，MIT，见 litviz/UPSTREAM.md）
  'frontend/src/static/',
  'office-addin/assets/',
  // pptx-service 整目录是上游 banana-slides 的 vendored 源码（CC BY-NC-SA 4.0，
  // 见 pptx-service/LICENSE 与 pptx-service/UPGRADE_CHECKBA.md），checkba 侧定制
  // 靠 FORCE_INCLUDE_FILES 单独挑出。
  'pptx-service/',
];

// 目录名黑名单：路径中任意一级目录名命中即排除（生成物/依赖，常见于任意深度）。
export const EXCLUDED_DIR_SEGMENTS = new Set([
  'node_modules',
  'dist',
  'target',
  'build',
]);

// 单个文件路径的精确排除。
export const EXCLUDED_EXACT_FILES = new Set([
  // MIT 许可的 zetajs vendor 文件（上游原文，头不改，见同目录 UPSTREAM.md）。
  'frontend/src/zetaoffice/public/zeta.js',
  // 由 frontend/scripts/sync-house-profile.mjs 生成的包装，跑一次构建就被重写，加头会反复被抹掉。
  'frontend/src/zetaoffice/public/house-default.js',
]);

// pptx-service 是整体 vendor，但下列文件是 UPGRADE_CHECKBA.md 明确登记的
// "checkba 新增"（不是对上游文件的补丁，是全新文件，代码内 [checkba] 标记贯穿全文），
// 应视为一方源码正常加头。
export const FORCE_INCLUDE_FILES = new Set([
  'pptx-service/backend/utils/text_sanitizer.py',
  'pptx-service/backend/utils/pptx_format_utils.py',
  'pptx-service/backend/services/pptx_format_service.py',
  'pptx-service/backend/controllers/pptx_edit_controller.py',
  'pptx-service/backend/services/pdf_convert_service.py',
  'pptx-service/backend/controllers/pdf_convert_controller.py',
  'pptx-service/backend/tests/unit/test_text_sanitizer.py',
  'pptx-service/backend/tests/unit/test_pptx_formatting.py',
  'pptx-service/backend/tests/unit/test_pdf_convert.py',
  'pptx-service/compat_smoke_test.sh',
]);

// desktop/lowa-build/patches/ 下的 .patch 文件跳过（对上游的补丁，不是我们的源码）。
// .patch 本就不在下面的扩展名支持列表里，这条属于双保险、防止以后误加支持。
function isSkippedPatchFile(relPath) {
  return (
    relPath.startsWith('desktop/lowa-build/patches/') && relPath.endsWith('.patch')
  );
}

function isMinified(relPath) {
  const base = relPath.slice(relPath.lastIndexOf('/') + 1);
  return base.includes('.min.');
}

function hasExcludedDirSegment(relPath) {
  const segs = relPath.split('/');
  segs.pop(); // 去掉文件名本身，只看目录层级
  return segs.some((s) => EXCLUDED_DIR_SEGMENTS.has(s));
}

// relPath 必须是相对仓库根、以 "/" 分隔的 posix 路径（git ls-files 输出的原生格式）。
// 目录名撞上生成物黑名单、但实为一方手写源码的目录（desktop/build/ 是安装器 UI 与
// NSIS 脚本源码，不是构建产物），整目录强制纳入。
export const FORCE_INCLUDE_DIR_PREFIXES = ['desktop/build/'];

export function isPathExcluded(relPath) {
  if (FORCE_INCLUDE_FILES.has(relPath)) return false;
  if (FORCE_INCLUDE_DIR_PREFIXES.some((prefix) => relPath.startsWith(prefix))) return false;
  if (EXCLUDED_EXACT_FILES.has(relPath)) return true;
  if (isSkippedPatchFile(relPath)) return true;
  if (isMinified(relPath)) return true;
  if (hasExcludedDirSegment(relPath)) return true;
  for (const prefix of EXCLUDED_DIR_PREFIXES) {
    if (relPath.startsWith(prefix)) return true;
  }
  return false;
}

// 支持的扩展名 -> 注释语法配置。
// style: 'line' 用 lineComment 前缀两行；'block' 用一个块注释包住两行。
export const EXTENSION_HANDLERS = {
  '.java': { style: 'line', lineComment: '//', placement: 'before-package' },
  '.js': { style: 'line', lineComment: '//', placement: 'top-after-shebang' },
  '.mjs': { style: 'line', lineComment: '//', placement: 'top-after-shebang' },
  '.cjs': { style: 'line', lineComment: '//', placement: 'top-after-shebang' },
  '.ts': { style: 'line', lineComment: '//', placement: 'top-after-shebang' },
  '.vue': { style: 'html', placement: 'top' },
  '.py': { style: 'line', lineComment: '#', placement: 'top-after-shebang-or-coding' },
  '.sh': { style: 'line', lineComment: '#', placement: 'top-after-shebang' },
  '.scss': { style: 'block', placement: 'top' },
  '.css': { style: 'block', placement: 'top' },
  '.html': { style: 'html', placement: 'after-doctype' },
};

export function getExtension(relPath) {
  const base = relPath.slice(relPath.lastIndexOf('/') + 1);
  const dot = base.lastIndexOf('.');
  if (dot <= 0) return null;
  return base.slice(dot).toLowerCase();
}

export function getHandler(relPath) {
  const ext = getExtension(relPath);
  if (!ext) return null;
  return EXTENSION_HANDLERS[ext] || null;
}

export function hasExistingSpdxHeader(content) {
  return content.includes('SPDX-License-Identifier');
}

// 生成头部文本片段（不含首尾换行处理，由调用方决定怎么拼进原内容）。
export function buildHeaderLines(handler) {
  if (handler.style === 'line') {
    const c = handler.lineComment;
    return [
      `${c} SPDX-FileCopyrightText: ${COPYRIGHT_LINE}`,
      `${c} SPDX-License-Identifier: ${LICENSE_ID}`,
    ];
  }
  if (handler.style === 'html') {
    return [
      `<!-- SPDX-FileCopyrightText: ${COPYRIGHT_LINE} -->`,
      `<!-- SPDX-License-Identifier: ${LICENSE_ID} -->`,
    ];
  }
  if (handler.style === 'block') {
    return [
      `/* SPDX-FileCopyrightText: ${COPYRIGHT_LINE} */`,
      `/* SPDX-License-Identifier: ${LICENSE_ID} */`,
    ];
  }
  throw new Error(`unknown header style: ${handler.style}`);
}
