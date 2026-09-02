# 一键安装器 UI 冒烟驱动（installer-ui-smoke 工作流用，dev-board#339）：
# 启动 ui-harness 安装器，按 awd-oneclick-ui.nsh 的基准坐标点击热区，逐阶段截图。
# 只在 100% DPI 的 runner 上跑，基准坐标即物理像素；窗口无边框，客户区 == 窗口矩形。
param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [Parameter(Mandatory = $true)][string]$OutDir,
  # 磁盘空间闸（dev-board#350）的反向用例：用一个所需空间大到不可能满足的
  # harness 产物驱动，断言「点了立即安装，但没开装」。
  [switch]$ExpectBlocked,
  # 关窗路径专用（dev-board#354）：欢迎大卡片右上角 ✕ 点下去安装器就该退出。
  # 这条路径原先没有任何用例覆盖过（zh/en 正常流程关的是完成卡的 ✕，走的是另一个
  # 函数），且必须单独跑——接在别的点击或对话框后面会被焦点/ESC 干扰，分不清
  # 「点击没落到热区上」还是「关窗动作本身没生效」。
  [switch]$CloseOnly
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
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
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr wp, IntPtr lp);
  [DllImport("user32.dll")] public static extern IntPtr GetDlgItem(IntPtr h, int id);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr SendMessageTimeout(IntPtr h, uint msg, IntPtr wp, IntPtr lp, uint flags, uint timeout, out IntPtr result);
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

function ShotFull([string]$name) {
  # 磁盘不足提示是独立的 MessageBox 顶层窗，位置不受卡片矩形约束，只能整屏截
  $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bmp = New-Object System.Drawing.Bitmap($b.Width, $b.Height)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.CopyFromScreen($b.X, $b.Y, 0, 0, $bmp.Size)
  $g.Dispose()
  $bmp.Save((Join-Path $OutDir "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Host "shot $name (full screen $($b.Width) x $($b.Height))"
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

# 绝对坐标的真实鼠标移动（MOUSEEVENTF_MOVE|ABSOLUTE，归一到主屏 0..65535）。拖窗必须走
# 真输入：无边框窗口的移动靠系统的模态拖动循环，它只认输入队列里的鼠标消息，
# SetWindowPos 之类从外部挪窗口证明不了「用户能拖」。
$screen = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
function MoveAbs([int]$x, [int]$y) {
  $nx = [uint32][math]::Round($x * 65535 / ($screen.Width - 1))
  $ny = [uint32][math]::Round($y * 65535 / ($screen.Height - 1))
  [W]::mouse_event(0x8001, $nx, $ny, 0, [UIntPtr]::Zero)
}

# 可拖动断言（dev-board#366）：在窗口相对点 (bx,by) 按下真实鼠标、分步拖 (dx,dy)、抬起，
# 断言窗口矩形跟着走了，且进程全程活着。失败不当场 throw——三张卡各拖一次，
# 一次红就中断会把后面两张卡的结论一起吞掉，全部记下来最后一并报。
$dragFailures = @()
function DragAssert([string]$stage, [int]$bx, [int]$by, [int]$dx, [int]$dy) {
  $r = Get-Rect
  Clear-Overlay ($r.L + $bx) ($r.T + $by)
  $r = Get-Rect
  $sx = $r.L + $bx; $sy = $r.T + $by
  MoveAbs $sx $sy
  Start-Sleep -Milliseconds 150
  [W]::mouse_event(2, 0, 0, 0, [UIntPtr]::Zero)   # LEFTDOWN
  Start-Sleep -Milliseconds 200
  for ($i = 1; $i -le 10; $i++) {
    MoveAbs ($sx + [int]($dx * $i / 10)) ($sy + [int]($dy * $i / 10))
    Start-Sleep -Milliseconds 40
  }
  Start-Sleep -Milliseconds 200
  [W]::mouse_event(4, 0, 0, 0, [UIntPtr]::Zero)   # LEFTUP
  Start-Sleep -Milliseconds 500
  if ($p.HasExited) { throw "installer exited during the $stage card drag" }
  $r2 = Get-Rect
  $mx = $r2.L - $r.L; $my = $r2.T - $r.T
  Write-Host "drag[$stage]: pressed at ($bx,$by), dragged ($dx,$dy) -> window moved ($mx,$my)"
  # 位移至少要走到指针位移的一半：绝对坐标归一有 1px 级误差，但「一动不动」就是没接上
  if ([math]::Abs($mx) -lt [math]::Abs($dx) / 2 -or [math]::Abs($my) -lt [math]::Abs($dy) / 2) {
    $script:dragFailures += "$stage card did not follow the mouse drag (moved $mx,$my; expected about $dx,$dy)"
  }
}

# UI 线程活着的判据：SendMessageTimeout(WM_NULL, SMTO_ABORTIFHUNG) 3 秒内有应答。
# 安装期间的 Section 跑在 NSIS 自己开的 install_thread 上（Ui.c 的 WM_NOTIFY_START），
# 消息泵本来就不会停——这条把它钉成用例，免得下次再往「消息泵卡死」上猜。
function AssertResponsive([string]$stage) {
  $res = [IntPtr]::Zero
  $ok = [W]::SendMessageTimeout($h, 0, [IntPtr]::Zero, [IntPtr]::Zero, 0x2, 3000, [ref]$res)
  if ($ok -eq [IntPtr]::Zero) { throw "$stage card: UI thread did not answer WM_NULL within 3s (message pump stalled)" }
  Write-Host "$stage card: UI thread answered WM_NULL"
}

# 1. 大卡片首页
Shot '01-welcome'

if ($CloseOnly) {
  # 先把视线清障单独做掉再断言进程还活着：Clear-Overlay 挡不住视线时会按 ESC，
  # 而 NSIS 下 ESC 本身就等于「取消」，安装器会因此退出——那样点都没点就绿了。
  # 这一步把这个假绿口子堵上；清完障后 ClickAt 里那次 Clear-Overlay 会在第 0 轮
  # 直接返回，不会再按 ESC。
  $rc = Get-Rect
  Clear-Overlay ($rc.L + 732) ($rc.T + 24)
  if ($p.HasExited) { throw "installer exited before the close click was issued (line-of-sight cleanup interfered)" }
  # 点右上角 ✕（AWDUI_CLOSE 712,8,40,32 → 中心 732,24）。欢迎卡刚出来、没弹过任何
  # 对话框、没点过任何别的热区，是这条路径最干净的一次点击。
  ClickAt 732 24
  Start-Sleep -Seconds 3
  if ($p.HasExited) {
    Write-Host 'close flow completed: welcome card close button exited the installer'
    exit 0
  }
  # 没退出。下面两条对照都跑一遍并把结论留在日志里，回归时不用再猜是哪一头坏了。
  Shot '02-still-open'
  # 对照一：同一行、左边 44px 的最小化热区（AWDUI_MIN 668,8,40,32 → 中心 688,24），
  # 走的是 ShowWindow，与关窗机制无关。它生效 = 这一行热区确实被点中了，
  # 那么 ✕ 关不掉就只能是 AwdCloseClick 里的关窗动作没生效。
  ClickAt 688 24
  Start-Sleep -Milliseconds 800
  $iconic = [W]::IsIconic($h)
  Write-Host "diag: minimize hotspot (688,24) -> IsIconic=$iconic"
  if ($iconic) {
    [W]::ShowWindow($h, 9) | Out-Null    # SW_RESTORE
    Start-Sleep -Milliseconds 500
    [W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x3) | Out-Null
    [W]::SetForegroundWindow($h) | Out-Null
    Start-Sleep -Milliseconds 500
  }
  # 对照二：从外部发一次「取消」（WM_COMMAND，IDCANCEL=2），即走 NSIS 页面机自己的
  # 退出通道——完成卡的 ✕ 用的就是同族的 WM_COMMAND，已知可用。它能关掉、而 ✕ 关不掉，
  # 就把结论钉死在「AwdCloseClick 用的退出方式不对」上。
  [W]::PostMessage($h, 0x0111, [IntPtr]2, [IntPtr]::Zero) | Out-Null
  Start-Sleep -Seconds 3
  Write-Host "diag: WM_COMMAND IDCANCEL from outside -> HasExited=$($p.HasExited)"
  if (-not $p.HasExited) { $p.Kill() }
  throw "installer did not exit after clicking the welcome card close button"
}

# 1b. 拖大卡片（dev-board#366）：按在没有任何热区的空白处（380,240），拖 (100,40)。
# 位移刻意小：runner 常见 1024x768，大卡片 760 宽居中后右侧只剩百来像素余量。
DragAssert 'welcome' 380 240 100 40
Shot '01b-welcome-dragged'

# 2. 展开自定义安装（AWDUI_TOGGLE 44,448,130,26 → 中心 109,461）
# 展开只许向下长高，不许把刚拖走的卡片弹回初始居中位（引擎里曾用初始化时记下的坐标
# 重定位，拖动接上之后那就成了「一点自定义安装卡片就跳回去」）。
$rBeforeToggle = Get-Rect
ClickAt 109 461
Start-Sleep -Milliseconds 600
Shot '02-expanded'
$rAfterToggle = Get-Rect
if ($rAfterToggle.L -ne $rBeforeToggle.L -or $rAfterToggle.T -ne $rBeforeToggle.T) {
  $dragFailures += "expanding custom install moved the welcome card from ($($rBeforeToggle.L),$($rBeforeToggle.T)) to ($($rAfterToggle.L),$($rAfterToggle.T)); it must only grow downward"
}
# 3. 点「立即安装」（AWDUI_CTA 460,414,260,72 → 中心 590,450）
ClickAt 590 450

if ($ExpectBlocked) {
  # 磁盘空间闸应当在这一步就拦下：弹提示框、留在大卡片上、一个字节都不该开始拷
  Start-Sleep -Milliseconds 1500
  $fg = [W]::GetForegroundWindow()
  ShotFull '03-blocked'
  if ($fg -eq $h) { throw "disk-space gate did not fire: no modal appeared after clicking install" }
  # MB_OK 用回车关掉
  [W]::SetForegroundWindow($fg) | Out-Null
  [W]::keybd_event(0x0D, 0, 0, [UIntPtr]::Zero)
  [W]::keybd_event(0x0D, 0, 2, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 1000
  if ($p.HasExited) { throw "installer exited instead of staying on the welcome card" }
  Shot '04-still-welcome'
  $rb = Get-Rect
  $wb = $rb.R - $rb.L
  # 开装了窗口会缩成 360x132 的角落小进度卡；还是 760 宽就说明确实没开装
  if ($wb -lt 700) { throw "installer proceeded to the progress card despite insufficient space (window width $wb)" }
  # 收尾直接杀进程，不点大卡片的 ✕。本用例要断言的是「闸拦住了、没开装」，上面
  # 三条断言已经全覆盖；大卡片 ✕ 的关窗行为由 -CloseOnly 那条独立用例覆盖，
  # 不在这里重复点——首轮实跑就是因为把两件事绑在一根流程上，点 ✕ 没退出时
  # 分不清是闸的问题还是关窗的问题（dev-board#354，根因已确认并修掉）。
  $p.Kill()
  Write-Host 'blocked flow completed: gate fired, install never started'
  exit 0
}

Start-Sleep -Milliseconds 1500
Shot '03-progress'
# 断言真的进了「角落小进度卡」形态（dev-board#356）。此前这一步只截图不断言，
# 于是真机上安装页塌回 NSIS 原生向导、流水线照样全绿。两条判据各堵一头：
#   宽度——大卡片 760、小进度卡 360，没缩就是 AwdInstFilesShow 根本没跑；
#   原生「上一步」按钮（IDC 3）——AwdHideChrome 每页都压一遍，它可见就说明
#   这一页压根没经过我们的 SHOW 回调（用户实机截图上飘在窗口中间的就是它）。
$rp = Get-Rect
$wp = $rp.R - $rp.L
$hp = $rp.B - $rp.T
$back = [W]::GetDlgItem($h, 3)
$backVisible = ($back -ne [IntPtr]::Zero) -and [W]::IsWindowVisible($back)
Write-Host "progress card: $wp x $hp, native back button visible=$backVisible"
if ($wp -gt 700) {
  throw "install started but the window never shrank to the corner progress card ($wp x $hp, expected 360x132) - AwdInstFilesShow did not run"
}
if ($backVisible) {
  throw "native wizard chrome is showing on the install page (back button visible) - AwdInstFilesShow did not run"
}
# 3b. 进度卡期间（Section 还在 install_thread 上跑）：UI 线程必须有应答，且卡片必须能被
# 真实鼠标拖走（dev-board#366：用户反馈的「像卡死了」就是这张卡既没标题栏也拖不动）。
# 按在窗口顶部 12px 处、向左下拖：卡片钉在工作区右上角，往右上拖会出屏。
AssertResponsive 'progress'
DragAssert 'progress' 180 12 -200 120
Start-Sleep -Milliseconds 1500
Shot '04-progress2'
# 4. 等安装收尾进完成卡（payload + 3x2s Sleep，10 秒余量）
Start-Sleep -Seconds 10
Shot '05-done'
# 4b. 完成卡也要能拖：按在副标题文字上（120,60，不在「立即体验」与 ✕ 的热区里）
DragAssert 'done' 120 60 -150 100
# 5. 点完成卡右上角 ✕（AWDUI_MCLOSE 320,6,34,28 → 中心 337,20）
ClickAt 337 20
Start-Sleep -Seconds 2
if (-not $p.HasExited) {
  Shot '06-should-have-closed'
  $p.Kill()
  throw "installer did not exit after closing finish card"
}
if ($dragFailures.Count -gt 0) {
  throw ("drag assertions failed (dev-board#366):`n - " + ($dragFailures -join "`n - "))
}
Write-Host 'smoke flow completed, installer exited cleanly'
