// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Run: electron tests/dropped-files-electron.cjs (isolated profile, no backend or network).
const { app, BrowserWindow, ipcMain } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');
const runtime = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'awd-drop-electron-'));
app.setName('AWD Drop Isolated Test');
app.setPath('userData', path.join(runtime, 'profile'));
app.setPath('sessionData', path.join(runtime, 'session'));
fs.mkdirSync(path.join(runtime, 'session'), { recursive: true });
app.disableHardwareAcceleration();
app.on('quit', () => fs.rmSync(runtime, { recursive: true, force: true }));
const { initDroppedFileService } = require(root + '/desktop/main/dropped-files');
initDroppedFileService(ipcMain);
fs.mkdirSync(path.join(runtime, 'project'), { recursive: true });
const nativeFixture = path.join(runtime, 'native.txt');
fs.writeFileSync(nativeFixture, 'real native file bytes\n');
fs.writeFileSync(path.join(runtime, 'preload.cjs'), fs.readFileSync(root + '/desktop/preload/preload.js', 'utf8') + `\ncontextBridge.exposeInMainWorld('__dropHarness', {copy: (p, parent) => ipcRenderer.invoke('test:copy', p, parent)});`);
let imported = 0;
const copied = [];
ipcMain.handle('test:copy', async (_event, p, parent) => {
  assert.ok(p === nativeFixture || path.basename(path.dirname(p)).startsWith('awd-drop-'));
  assert.equal(fs.lstatSync(p).isSymbolicLink(), false);
  if (parent === 'failure') throw Error('synthetic import failure');
  const dest = path.join(runtime, 'project', String(++imported) + '-' + path.basename(p));
  fs.copyFileSync(p, dest, fs.constants.COPYFILE_EXCL);
  copied.push({ bytes: fs.readFileSync(dest).toString('base64'), parent });
  return { data: { id: imported, name: path.basename(p), fileType: 'txt' } };
});
app.whenReady().then(async () => {
  const win = new BrowserWindow({ show: false, webPreferences: { preload: path.join(runtime, 'preload.cjs'), contextIsolation: true, nodeIntegration: false } });
  win.webContents.session.webRequest.onBeforeRequest((details, callback) => callback({ cancel: !details.url.startsWith('data:') && !details.url.startsWith('devtools:') }));
  try {
    await win.loadURL('data:text/html,<input type="file" id="file">');
    win.webContents.debugger.attach('1.3');
    const doc = await win.webContents.debugger.sendCommand('DOM.getDocument');
    const node = await win.webContents.debugger.sendCommand('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '#file' });
    await win.webContents.debugger.sendCommand('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [nativeFixture] });
    const helper = fs.readFileSync(root + '/frontend/src/utils/fileTreeExternalDrop.js', 'utf8').replace(/^export /gm, '');
    const tree = fs.readFileSync(root + '/frontend/src/components/FileTree.vue', 'utf8');
    const methods = tree.slice(tree.indexOf('    resetExternalDrag() {'), tree.indexOf('    // 非H5端触摸拖拽方法'));
    const result = await win.webContents.executeJavaScript(`(async () => {
      ${helper}
      const host = window.checkbaDesktop, toasts=[], importedPaths=[];
      const uni={showToast:o=>toasts.push(o.title)};
      const importLocalFile=async (pid,p,parent)=>{ importedPaths.push(p);return window.__dropHarness.copy(p,parent); };
      const vm={${methods}};vm.projectId=42;vm.$t=k=>k;vm.loadFiles=async()=>{};vm.showErrorModal=(m)=>toasts.push(m);
      const source=new File(['pathless source bytes\\n'], 'source.txt');
      const native=document.querySelector('#file').files[0];
      const nativePath=host.fs.getPathForFile(native);
      if(!nativePath)throw Error('native file path unavailable');
      if(host.fs.getPathForFile(source))throw Error('pathless fixture unexpectedly has path');
      await vm.importExternalDrop({files:[],items:[{kind:'file',getAsFile:()=>source}]}, 'folder-7');
      const stagedPath=importedPaths[0];
      await vm.importExternalDrop({files:[native],items:[]}, null);
      await vm.importExternalDrop({files:[],items:[],types:['text/uri-list'],getData:()=> 'file:///tmp/never-read'},null);
      await vm.importExternalDrop({files:[new File(['cleanup failure'], 'fail.txt')],items:[]}, 'failure');
      return {toasts,stagedPath,failedPath:importedPaths[2],nativeImported:importedPaths[1]===nativePath};
    })()`);
    assert.equal(result.nativeImported, true);
    assert.equal(fs.existsSync(result.stagedPath), false);
    assert.equal(fs.existsSync(result.failedPath), false);
    assert.equal(imported, 2);
    assert.deepEqual(copied, [
      { bytes: Buffer.from('pathless source bytes\n').toString('base64'), parent: 'folder-7' },
      { bytes: Buffer.from('real native file bytes\n').toString('base64'), parent: null },
    ]);
    assert.ok(result.toasts.includes('fileTree.importDropUnreadable'));
    console.log(JSON.stringify({ passed: true, electron: process.versions.electron, imported, bytesExact: true, tokenCleanupOnSuccessAndFailure: true, uriDidNotImport: true, nativePath: true }));
    console.log('PASS isolated Electron: native File, items-only bytes, exact copy, URI rejection, success/failure temp cleanup');
  } catch (error) { console.error(error); process.exitCode = 1; }
  finally { win.destroy(); app.quit(); }
});
