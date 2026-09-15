// 路径与端口常量集中在这一处；其余文件不再各写各的相对路径。
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

export const PIPELINE_DIR = path.resolve(__dirname, '..')
export const REPO_ROOT = path.resolve(PIPELINE_DIR, '..', '..', '..', '..')
export const FRONTEND_DIR = path.join(REPO_ROOT, 'frontend')
export const DESKTOP_DIR = path.join(REPO_ROOT, 'desktop')
export const BACKEND_DIR = path.join(REPO_ROOT, 'backend')
export const CASE_MATERIALS_DIR = path.join(REPO_ROOT, 'docs', 'marketing', 'launch-video', 'case-materials')
export const OUT_DIR = path.join(PIPELINE_DIR, 'out')

// 不用 9696（维护者真实桌面后端）也不用 9797/5173/5174（既有 e2e 套件的约定端口，
// 并行会话可能正占着）——这条流水线自己的端口，可用 LAUNCH_VIDEO_* 环境变量覆盖。
export const BACKEND_PORT = Number(process.env.LAUNCH_VIDEO_BACKEND_PORT) || 9895
export const DEVSERVER_PORT = Number(process.env.LAUNCH_VIDEO_DEVSERVER_PORT) || 5183
export const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`
export const DEVSERVER_URL = `http://127.0.0.1:${DEVSERVER_PORT}`

export const VIEWPORT = { width: 1920, height: 1080 }
export const DEMO_PROJECT_NAME = '林芳劳动争议'
export const FPS = 30
