# 一键安装器 UI 冒烟驱动（installer-ui-smoke 工作流用，dev-board#339）：
# 启动 ui-harness 安装器，按 awd-oneclick-ui.nsh 的基准坐标点击热区，逐阶段截图。
# 只在 100% DPI 的 runner 上跑，基准坐标即物理像素；窗口无边框，客户区 == 窗口矩形。
param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [Parameter(Mandatory = $true)][string]$OutDir
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int w, int ht, uint f);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(POINT pt);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr h, uint flags);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  public struct RECT { public int L, T, R, B; }
  public struct POINT { public int x, y; }
}
"@

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$p = Start-Process -FilePath (Resolve-Path $Exe) -PassThru
$deadline = (Get-Date).AddSeconds(30)
while ((Get-Date) -lt $deadline) {
  $p.Refresh()
  if ($p.MainWindowHandle -ne [IntPtr]::Zero) { break }
  Start-Sleep -Milliseconds 200
}
if ($p.MainWindowHandle -eq [IntPtr]::Zero) { throw "installer window never appeared" }
$h = $p.MainWindowHandle
# 置顶 + 反遮挡：ARM runner 桌面盖着一层 OOBE 隐私设置全屏窗，且它自己也是
# 置顶层（TOPMOST 压不过，真机两轮实锤）。用 WindowFromPoint 探测卡片中心
# 实际最上层归属，不是我们就杀掉那个进程（runner 一次性虚机，无副作用），
# 循环直到视线畅通。HWND_TOPMOST=-1，SWP_NOMOVE|SWP_NOSIZE=0x3
[W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x3) | Out-Null
[W]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 800
# 视线清障：目标点被别的窗口盖住时——先 ESC（关掉开始菜单/弹出层，杀 OOBE 的
# 副作用就是弹开始菜单，真机实锤），仍在就杀掉遮挡进程，再置顶重探
function Clear-Overlay([int]$x, [int]$y) {
  for ($try = 0; $try -lt 6; $try++) {
    $pt = New-Object W+POINT
    $pt.x = $x; $pt.y = $y
    $top = [W]::GetAncestor([W]::WindowFromPoint($pt), 2)   # GA_ROOT
    if ($top -eq $h) { if ($try -gt 0) { Write-Host "line of sight restored (try $try)" }; return }
    [W]::keybd_event(0x1B, 0, 0, [UIntPtr]::Zero)   # ESC down
    [W]::keybd_event(0x1B, 0, 2, [UIntPtr]::Zero)   # ESC up
    Start-Sleep -Milliseconds 500
    $top2 = [W]::GetAncestor([W]::WindowFromPoint($pt), 2)
    if ($top2 -ne $h -and $top2 -ne [IntPtr]::Zero) {
      $tpid = [uint32]0
      [W]::GetWindowThreadProcessId($top2, [ref]$tpid) | Out-Null
      if ($tpid -ne 0 -and $tpid -ne $PID -and $tpid -ne $p.Id) {
        $blocker = Get-Process -Id $tpid -ErrorAction SilentlyContinue
        Write-Host "killing overlay process: $($blocker.ProcessName) (pid $tpid)"
        Stop-Process -Id $tpid -Force -ErrorAction SilentlyContinue
      }
    }
    Start-Sleep -Seconds 1
    [W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x3) | Out-Null
    [W]::SetForegroundWindow($h) | Out-Null
  }
  Write-Warning "line of sight still blocked at ($x,$y)"
}
$r0 = New-Object W+RECT
[W]::GetWindowRect($h, [ref]$r0) | Out-Null
Clear-Overlay ([int](($r0.L + $r0.R) / 2)) ([int](($r0.T + $r0.B) / 2))

function Get-Rect {
  $r = New-Object W+RECT
  [W]::GetWindowRect($h, [ref]$r) | Out-Null
  return $r
}

function Shot([string]$name) {
  $r = Get-Rect
  $w = $r.R - $r.L; $ht = $r.B - $r.T
  if ($w -le 0 -or $ht -le 0) { Write-Warning "window gone, skip $name"; return }
  $bmp = New-Object System.Drawing.Bitmap($w, $ht)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
  $g.Dispose()
  $bmp.Save((Join-Path $OutDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "shot $name ($w x $ht at $($r.L),$($r.T))"
}

function ClickAt([int]$bx, [int]$by) {
  $r = Get-Rect
  Clear-Overlay ($r.L + $bx) ($r.T + $by)
  $r = Get-Rect
  [W]::SetCursorPos($r.L + $bx, $r.T + $by) | Out-Null
  Start-Sleep -Milliseconds 120
  [W]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)   # LEFTDOWN
  [W]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)   # LEFTUP
  Start-Sleep -Milliseconds 250
}

# 1. 大卡片首页
Shot '01-welcome'
# 2. 展开自定义安装（AWDUI_TOGGLE 44,448,130,26 → 中心 109,461）
ClickAt 109 461
Start-Sleep -Milliseconds 600
Shot '02-expanded'
# 3. 点「立即安装」（AWDUI_CTA 460,414,260,72 → 中心 590,450）
ClickAt 590 450
Start-Sleep -Milliseconds 1500
Shot '03-progress'
Start-Sleep -Milliseconds 2500
Shot '04-progress2'
# 4. 等安装收尾进完成卡（payload + 3x2s Sleep，10 秒余量）
Start-Sleep -Seconds 10
Shot '05-done'
# 5. 点完成卡右上角 ✕（AWDUI_MCLOSE 320,6,34,28 → 中心 337,20）
ClickAt 337 20
Start-Sleep -Seconds 2
if (-not $p.HasExited) {
  Shot '06-should-have-closed'
  $p.Kill()
  throw "installer did not exit after closing finish card"
}
Write-Host 'smoke flow completed, installer exited cleanly'
