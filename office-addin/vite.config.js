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

export default defineConfig(({ command }) => ({
  plugins: [vue()],
  // 构建产物用相对路径引资源：dist 可能被托管在子路径下（官方云是
  // https://addin.aiworkdeck.com/office-addin/），默认的绝对 /assets/... 会打到站点根、
  // 被 SPA 回退顶成 index.html，任务窗格白屏（真机踩过）。dev 仍是 '/' 不受影响。
  base: command === 'build' ? './' : '/',
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
    // WPS 任务窗格跑在随宿主版本参差的 CEF 内核里（可能低至 Chromium 7x），
    // 语法降到 es2018 消掉可选链等新语法的硬解析错；Office 家族的现代 webview
    // 跑降级产物无差别。运行时能力（fetch/ReadableStream）转译不了，真机验证。
    target: 'es2018',
    rollupOptions: {
      input: {
        taskpane: path.resolve(rootDir, 'taskpane.html'),
        'taskpane-wps': path.resolve(rootDir, 'taskpane-wps.html')
      }
    }
  }
}))
