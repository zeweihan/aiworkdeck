// 往一个已就绪的隔离后端里灌演示数据：新建项目「林芳劳动争议」，
// 把 case-materials/ 下的虚构材料转成 docx（macOS 自带 textutil），
// 放进项目里的一个文件夹，走的是产品自己的 REST 接口（不是拖文件到 UI 里）。
//
// local-mode 后端（desktop profile）对任何请求都解析成本机用户，不需要登录/会话头。

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { CASE_MATERIALS_DIR, DEMO_PROJECT_NAME, OUT_DIR } from './config.mjs'

const MATERIALS_FOLDER_NAME = '当事人材料'

async function api(baseUrl, ep, opts = {}) {
  const r = await fetch(baseUrl + ep, {
    method: opts.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  })
  if (!r.ok) {
    const text = await r.text().catch(() => '')
    throw new Error(`${opts.method || 'GET'} ${ep} -> HTTP ${r.status}: ${text.slice(0, 300)}`)
  }
  return r.json()
}

/** md → txt(UTF-8 中转) → docx，用 macOS 自带 textutil。转不动就原样退回 md。 */
function convertToDocx(mdPath, workDir) {
  const base = path.basename(mdPath, '.md')
  const txtPath = path.join(workDir, base + '.txt')
  const docxPath = path.join(workDir, base + '.docx')
  fs.copyFileSync(mdPath, txtPath)
  try {
    execFileSync('textutil', ['-convert', 'docx', '-output', docxPath, txtPath], { stdio: 'pipe' })
    if (fs.existsSync(docxPath)) return { path: docxPath, name: base + '.docx', fileType: 'docx' }
  } catch (e) {
    console.log(`  ! textutil 转换失败（${base}），原样用 md: ${String(e.message || e).slice(0, 120)}`)
  }
  return { path: mdPath, name: base + '.md', fileType: 'md' }
}

async function uploadFile(baseUrl, projectId, parentId, localPath, name, fileType) {
  const bytes = fs.readFileSync(localPath)
  const created = await api(baseUrl, `/api/projects/${projectId}/files/file`, {
    method: 'POST',
    body: { parentId, name, fileType, fileSize: bytes.length },
  })
  const fileId = created.id
  const form = new FormData()
  form.append('file', new Blob([bytes]), name)
  const r = await fetch(`${baseUrl}/api/files/${fileId}/upload`, { method: 'POST', body: form })
  if (!r.ok) throw new Error(`上传 ${name} 失败: HTTP ${r.status}`)
  return created
}

/**
 * @param {string} baseUrl 隔离后端地址
 * @returns {Promise<{projectId:number, folderId:number, files:Array<{id:number,name:string}>}>}
 */
export async function seedDemoProject(baseUrl) {
  const project = await api(baseUrl, '/api/projects', {
    method: 'POST',
    body: { name: DEMO_PROJECT_NAME, projectType: 'BLANK' },
  })
  console.log(`  项目已建：${DEMO_PROJECT_NAME} (id=${project.id})`)

  const folder = await api(baseUrl, `/api/projects/${project.id}/files/folder`, {
    method: 'POST',
    body: { parentId: null, name: MATERIALS_FOLDER_NAME },
  })
  console.log(`  文件夹已建：${MATERIALS_FOLDER_NAME} (id=${folder.id})`)

  const workDir = path.join(OUT_DIR, 'converted-materials')
  fs.mkdirSync(workDir, { recursive: true })

  const mdFiles = fs.readdirSync(CASE_MATERIALS_DIR)
    .filter((f) => f.endsWith('.md') && f !== 'README.md')
    .sort()

  const files = []
  for (const f of mdFiles) {
    const converted = convertToDocx(path.join(CASE_MATERIALS_DIR, f), workDir)
    const created = await uploadFile(baseUrl, project.id, folder.id, converted.path, converted.name, converted.fileType)
    console.log(`  已导入：${converted.name}`)
    files.push({ id: created.id, name: converted.name })
  }

  return { projectId: project.id, folderId: folder.id, files }
}
