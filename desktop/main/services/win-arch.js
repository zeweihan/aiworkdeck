// Windows-on-ARM（如 Apple Silicon Mac 上的 Parallels/VMware 虚拟机）检测。
// 我们的 Windows 包只出 x64，在 ARM64 Windows 上整套跑在系统的 x64 转译层里：
// JVM 后端首启从十几秒膨胀到数分钟，60 秒级的启动看门狗必然把它杀在半路，
// 表现为「正在启动本地服务」永远转圈（dev-board#340）。
// 检测方式：读注册表里的系统环境变量 PROCESSOR_ARCHITECTURE——这是机器的原生值
//（HKLM\SYSTEM 不做 WOW 重定向），x64 进程里 process.arch 只会看到被转译的假象。
'use strict'
const { execFileSync } = require('child_process')

let cached = null

function isWinArmEmulated() {
  if (cached !== null) return cached
  cached = false
  if (process.platform === 'win32') {
    try {
      const out = execFileSync('reg', [
        'query', 'HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment',
        '/v', 'PROCESSOR_ARCHITECTURE',
      ], { encoding: 'utf8', timeout: 5000, windowsHide: true })
      cached = /ARM64/i.test(out)
    } catch (e) {
      cached = false
    }
  }
  return cached
}

module.exports = { isWinArmEmulated }
