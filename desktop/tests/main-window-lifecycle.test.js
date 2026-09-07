const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')

const source = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
function section(from, to) {
  const start = source.indexOf(from)
  const end = source.indexOf(to, start)
  assert.ok(start >= 0 && end > start)
  return source.slice(start, end)
}

// Execute the shipping functions, with only Electron/OS effects replaced. No source-pattern
// assertion can catch a stale closed callback nulling the replacement window.
function harness() {
  const windows = []
  const intervals = []
  class Window extends EventEmitter {
    constructor(options) {
      super()
      this.options = options
      this.destroyed = false
      this.sent = []
      this.webContents = new EventEmitter()
      this.webContents.send = (...args) => this.sent.push(args)
      this.webContents.setWindowOpenHandler = () => {}
      this.webContents.session = new EventEmitter()
      windows.push(this)
    }
    isDestroyed() { return this.destroyed }
    loadFile() {}
    isFullScreen() { return false }
    static getAllWindows() { return windows.filter(w => !w.destroyed) }
  }
  const app = new EventEmitter()
  app.isPackaged = false
  const clipboard = { text: 'already copied before launch', availableFormats: () => ['text/plain'], readText() { return this.text } }
  const context = vm.createContext({
    app, BrowserWindow: Window, clipboard, path, console,
    process: { platform: 'darwin', env: {} }, __dirname: path.join(__dirname, '../main'),
    screen: { getPrimaryDisplay: () => ({ workAreaSize: { width: 1400, height: 900 } }) },
    require: name => { assert.equal(name, './services/win-arch'); return { isWinArmEmulated: () => false } },
    setInterval: fn => { intervals.push(fn); return intervals.length }, clearInterval: () => {},
    views: { layoutAll() {} }, restoreViewsVisibility() {}, syncOcrSelectWinBounds() {},
    attachCopyListener() {}, attachDownloadListener() {},
  })
  vm.runInContext(`let mainWindow = null; let mainWindowStartupReady = false;
    let services = { ports: { backend: 9799 } }; const IS_DEV = false;
    let clipboardWatchTimer = null; let clipboardPrimed = false; let lastClipboardFingerprint = '';
    ${section('function emitClipboard(', 'function closeOcrSelectWin(')}
    ${section('function startClipboardWatcher()', 'function stopClipboardWatcher()')}
    ${section('function createMainWindow()', 'function syncOcrSelectWinBounds()')}
    ${section("app.on('activate'", "app.on('before-quit'")}
    globalThis.create = createMainWindow;
    globalThis.current = () => mainWindow;
    globalThis.finishStartup = () => { ${section('      mainWindowStartupReady = true', '      retireFirstLaunchSplash()')} };
  `, context)
  return { context, windows, intervals, app, clipboard }
}

test('mac activation during service startup waits for the allocated backend port', () => {
  const h = harness()
  h.app.emit('activate')
  assert.equal(h.windows.length, 0)
  h.context.finishStartup()
  assert.equal(h.windows.length, 1)
  assert.ok(h.windows[0].options.webPreferences.additionalArguments.includes('--checkba-api-base=http://127.0.0.1:9799'))
})

test('repeated creation keeps the current window and its clipboard destination', () => {
  const h = harness()
  h.context.finishStartup()
  const active = h.context.current()
  h.context.create()
  assert.equal(h.windows.length, 1)
  assert.equal(h.context.current(), active)
  h.intervals[0]() // prime, without replaying the pre-launch clipboard
  assert.equal(active.sent.length, 0)
  h.clipboard.text = 'new copy after restart'
  h.intervals[0]()
  assert.equal(active.sent[0][0], 'checkba:clipboard-copied')
  assert.equal(active.sent[0][1].text, 'new copy after restart')
})

test('late closed event from an old window cannot disconnect the replacement', () => {
  const h = harness()
  h.context.finishStartup()
  const old = h.context.current()
  old.destroyed = true
  h.context.create()
  const active = h.context.current()
  old.emit('closed')
  assert.equal(h.context.current(), active)
  h.intervals[0]()
  h.clipboard.text = 'copy after old window closed'
  h.intervals[0]()
  assert.equal(active.sent[0][1].text, 'copy after old window closed')
  assert.equal(old.sent.length, 0)
})

test('closing the current window still permits Dock activation to reopen it', () => {
  const h = harness()
  h.context.finishStartup()
  const old = h.context.current()
  old.destroyed = true
  old.emit('closed')
  assert.equal(h.context.current(), null)
  h.app.emit('activate')
  assert.equal(h.windows.length, 2)
  assert.equal(h.context.current(), h.windows[1])
  assert.equal(h.intervals.length, 1)
})
