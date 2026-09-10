// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

/** Fail closed before driving a backend; only this disposable fixture may be touched. */
export function prepareWritingIsolation({ root, desktopDir, editorDist, backendPort }) {
  assert.ok(root, 'Set WRITING_E2E_ROOT to the isolated backend fixture root')
  root = fs.realpathSync(root)
  const tempRoots = [fs.realpathSync(os.tmpdir()), fs.realpathSync('/tmp')]
  assert.ok(tempRoots.some(temp => root.startsWith(temp + path.sep)), 'Writing acceptance requires a temporary root')
  const home = path.join(root, 'home'), profile = path.join(root, 'profile')
  assert.ok(fs.existsSync(path.join(home, '.aiworkdeck/license.json')), 'Isolated backend fixture is not prepared')
  const pid = Number(execFileSync('lsof', ['-nP', '-iTCP:' + backendPort, '-sTCP:LISTEN', '-t'], { encoding: 'utf8' }).trim())
  assert.ok(pid > 1, 'Expected exactly one isolated backend listener')
  const command = execFileSync('ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' })
  const javaHome = command.match(/-Duser\.home=(\S+)/)?.[1]
  assert.ok(javaHome && fs.realpathSync(javaHome) === home, 'Backend user.home is not this fixture')
  const cwd = execFileSync('lsof', ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], { encoding: 'utf8' })
  assert.ok(cwd.split('\n').includes('n' + path.join(root, 'backend')), 'Backend cwd is not this fixture')
  const dir = path.join(root, 'desktop')
  fs.mkdirSync(dir, { recursive: true }); fs.mkdirSync(profile, { recursive: true })
  for (const name of ['main', 'preload']) fs.cpSync(path.join(desktopDir, name), path.join(dir, name), { recursive: true })
  const modules = path.join(dir, 'node_modules')
  if (!fs.existsSync(modules)) fs.symlinkSync(fs.realpathSync(path.join(desktopDir, 'node_modules')), modules, 'dir')
  const pkg = JSON.parse(fs.readFileSync(path.join(desktopDir, 'package.json'), 'utf8'))
  pkg.main = 'bootstrap.cjs'
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify(pkg))
  fs.writeFileSync(path.join(dir, 'bootstrap.cjs'), `const {app}=require('electron'); const fs=require('node:fs'); app.setPath('home',${JSON.stringify(home)}); app.setPath('userData',${JSON.stringify(profile)}); fs.writeFileSync(${JSON.stringify(path.join(root, 'home-proof.json'))},JSON.stringify({pid:process.pid,home:app.getPath('home'),profile:app.getPath('userData')})); require('./main/main.js');`)
  const targetDist = path.join(root, 'frontend/dist/zetaoffice')
  if (!fs.existsSync(targetDist) || fs.realpathSync(editorDist) !== fs.realpathSync(targetDist)) fs.cpSync(editorDist, targetDist, { recursive: true })
  assert.ok(fs.existsSync(path.join(targetDist, 'lowa/soffice.js')), 'Isolated editor engine is missing')
  return { desktopDir: dir, root, home, profile }
}
