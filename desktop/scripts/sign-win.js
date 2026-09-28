// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// electron-builder 的 win.sign 钩子：Azure Artifact Signing（原 Trusted Signing）
// 代码签名（dev-board#1011）。
//
// ── 调用方 ──
// app-builder-lib 24.13.3 没有 26.x 的内建 win.azureSignOptions，只能走 win.sign
// 自定义钩子。winPackager.js 的 sign() 在「没有 cscInfo（不给 CSC_LINK /
// certificateFile）且配置了 win.sign」时对每个待签文件调一次本函数：
//   - signApp：win-unpacked 根目录的应用 exe（rcedit 改完资源之后）与其它 .exe，
//     以及 resources/app.asar.unpacked、swiftshader 里的 .exe；
//   - extraResources 里的 .exe（jre/bin/*.exe、python/*.exe 等）；
//   - NsisTarget：卸载器（编进安装器之前）与最终安装器。
// `electron-builder --dir --arm64` 同样走 signApp，所以 ARM64 壳目录里的
// AI WorkDeck.exe 在被 installer.nsh 用 File /r 原样塞进安装器之前就已签好。
//
// ── 三种情况 ──
// 1. 没有签名配置（本机构建、fork PR、普通 PR）：三个 AZURE_SIGNING_* 环境变量
//    缺任意一个就打印一次跳过说明并返回，产物保持未签名，构建不红。
// 2. 有配置且签名成功：Invoke-ArtifactSigning 签名 + RFC3161 时间戳，签完用
//    Get-AuthenticodeSignature 回读，Status 必须 Valid 且带时间戳证书。
// 3. 有配置但签名失败：重试两次仍失败就抛错，electron-builder 让整个打包步骤失败。
//
// 已带有效 Authenticode 签名的第三方 exe（Temurin 的 java.exe 等）不重签：
// 保留原厂签名，也不占签名配额。应用 exe 被 rcedit 改过资源，原有签名（若有）
// 已失效，不会被这条规则跳过。
//
// 凭据：workflow 先用 azure/login@v2 走 GitHub OIDC 登录 Azure CLI，这里只让
// AzureCliCredential 生效，其余凭据类型全部排除（微软 FAQ：CI 上不排除会报 400 /
// CredentialUnavailableException / 超时）。
//
// PowerShell 里的日志写英文：runner 控制台代码页下中文会乱码。
//
// 串行：ArtifactSigning 模块每次调用都往 %LOCALAPPDATA% 检查/安装依赖并覆写同一个
// metadata.json，electron-builder 会以并发 4 调本钩子，并发会互相踩，所以排队执行。

'use strict';

const { spawn } = require('child_process');
const path = require('path');

const TIMESTAMP_URL = 'http://timestamp.acs.microsoft.com';

const PS_SCRIPT = `
$ErrorActionPreference = 'Stop'
$InformationPreference = 'Continue'
$file = $env:AWD_SIGN_FILE
$existing = Get-AuthenticodeSignature -LiteralPath $file
if ($existing.Status -eq 'Valid') {
  Write-Host "[sign-win] already validly signed, skip: $file ($($existing.SignerCertificate.Subject))"
  exit 0
}
Import-Module ArtifactSigning -ErrorAction Stop
$params = @{
  Endpoint = $env:AZURE_SIGNING_ENDPOINT
  CodeSigningAccountName = $env:AZURE_SIGNING_ACCOUNT
  CertificateProfileName = $env:AZURE_SIGNING_PROFILE
  Files = $file
  FileDigest = 'SHA256'
  TimestampRfc3161 = '${TIMESTAMP_URL}'
  TimestampDigest = 'SHA256'
  ExcludeEnvironmentCredential = $true
  ExcludeWorkloadIdentityCredential = $true
  ExcludeManagedIdentityCredential = $true
  ExcludeSharedTokenCacheCredential = $true
  ExcludeVisualStudioCredential = $true
  ExcludeVisualStudioCodeCredential = $true
  ExcludeAzurePowerShellCredential = $true
  ExcludeAzureDeveloperCliCredential = $true
  ExcludeInteractiveBrowserCredential = $true
}
Invoke-ArtifactSigning @params | Out-Host
$sig = Get-AuthenticodeSignature -LiteralPath $file
if ($sig.Status -ne 'Valid') {
  throw "[sign-win] signature invalid after signing: $file Status=$($sig.Status) $($sig.StatusMessage)"
}
if ($null -eq $sig.TimeStamperCertificate) {
  throw "[sign-win] signature has no RFC3161 timestamp: $file"
}
Write-Host "[sign-win] signed: $file ($($sig.SignerCertificate.Subject))"
`;

let skipLogged = false;
let queue = Promise.resolve();

function signingConfig() {
  const endpoint = process.env.AZURE_SIGNING_ENDPOINT;
  const account = process.env.AZURE_SIGNING_ACCOUNT;
  const profile = process.env.AZURE_SIGNING_PROFILE;
  if (!endpoint || !account || !profile) return null;
  return { endpoint, account, profile };
}

function runPowerShell(file) {
  return new Promise((resolve, reject) => {
    const child = spawn('pwsh', ['-NoProfile', '-NonInteractive', '-Command', PS_SCRIPT], {
      stdio: 'inherit',
      env: { ...process.env, AWD_SIGN_FILE: file },
    });
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`[sign-win] 签名失败（pwsh 退出码 ${code}）：${file}`));
    });
  });
}

async function signOne(file) {
  if (process.platform !== 'win32') {
    throw new Error(`[sign-win] 配置了 Azure 签名但当前平台是 ${process.platform}，只能在 Windows 上签：${file}`);
  }
  // 签名服务与时间戳服务偶发抖动（同 sign-mac-natives.sh 的时间戳退避），重试两次再判死
  for (let attempt = 1; ; attempt++) {
    try {
      await runPowerShell(file);
      return;
    } catch (e) {
      if (attempt >= 3) throw e;
      console.warn(`${e.message}，${attempt * 10} 秒后重试（第 ${attempt + 1} 次）`);
      await new Promise((r) => setTimeout(r, attempt * 10000));
    }
  }
}

module.exports = async function sign(configuration) {
  // 只做一次 SHA256 签名；package.json 已设 signingHashAlgorithms: ["sha256"]，
  // 这里防的是有人删掉那个字段后被 electron-builder 默认的 sha1 轮次再调一次。
  if (configuration.hash && configuration.hash !== 'sha256') return;

  if (!signingConfig()) {
    if (!skipLogged) {
      skipLogged = true;
      console.log('[sign-win] 未配置 AZURE_SIGNING_ENDPOINT / AZURE_SIGNING_ACCOUNT / AZURE_SIGNING_PROFILE，跳过 Windows 代码签名（本机构建、fork PR 与普通 PR 属正常）');
    }
    return;
  }

  const file = path.resolve(configuration.path);
  const task = queue.then(() => signOne(file));
  // 队列本身不因单个失败而断，失败由本次调用抛给 electron-builder
  queue = task.catch(() => {});
  await task;
};
