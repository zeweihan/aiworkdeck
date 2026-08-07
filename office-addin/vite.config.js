import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = path.dirname(fileURLToPath(import.meta.url))

/**
 * office-addin-dev-certs 生成的本地 HTTPS 证书。
 * Office 宿主只加载 https 的任务窗格页面，dev server 必须走 https；
 * 未安装证书时退回 http（可在普通浏览器里调试 UI，但 Word 里加载不了）。
 * 安装方式见 README：npx office-addin-dev-certs install
 */
function devHttps() {
  const dir = path.join(os.homedir(), '.office-addin-dev-certs')
  const key = path.join(dir, 'localhost.key')
  const cert = path.join(dir, 'localhost.crt')
  if (fs.existsSync(key) && fs.existsSync(cert)) {
    return { key: fs.readFileSync(key), cert: fs.readFileSync(cert) }
  }
  return undefined
}

/**
 * 默认后端地址（构建期烧进产物）。
 * 普通用户只需填一个官网 API Key，不必知道后端地址；律所自建服务器场景可在
 * 「高级设置」里改，改过的值存 localStorage 并优先于此默认值。
 * 私有部署可用 VITE_ADDIN_SERVER_URL 环境变量改默认值后重新构建。
 */
const defaultServerUrl = process.env.VITE_ADDIN_SERVER_URL || 'https://addin.aiworkdeck.com'

export default defineConfig({
  plugins: [vue()],
  define: {
    __ADDIN_DEFAULT_SERVER__: JSON.stringify(defaultServerUrl)
  },
  // 图标等静态资源目录（构建时原样拷入 dist 根，dev 下按根路径直出）
  publicDir: 'assets',
  server: {
    port: 3000,
    https: devHttps()
  },
  build: {
    rollupOptions: {
      input: path.resolve(rootDir, 'taskpane.html')
    }
  }
})
