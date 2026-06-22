// vite.zetaoffice.config.js — DEDICATED build for the embedded LibreOffice
// editor page (Epic #43). Separate from vite.config.js (uni-app) on purpose:
// uni-app's h5 build won't let a static/ page import src/ modules, so the
// webview editor page is its own self-contained bundle. This config does NOT use
// the uni plugin — it is a plain Vite multi-asset build of src/zetaoffice/.
//
// Build:  npm run build:zetaoffice   (-> dist/zetaoffice/)
// The output is loaded by <webview partition="persist:zetaoffice"> in the desktop
// app. base:'./' so it works from a file/relative origin.

import { defineConfig } from 'vite'
import { fileURLToPath, URL } from 'node:url'

const r = (p) => fileURLToPath(new URL(p, import.meta.url))

export default defineConfig({
  root: r('src/zetaoffice'),
  base: './',
  build: {
    outDir: r('dist/zetaoffice'),
    emptyOutDir: true,
    target: 'esnext', // top-level structured-clone / modern APIs; Electron Chromium is current
    rollupOptions: {
      input: r('src/zetaoffice/editor.html'),
    },
  },
})
