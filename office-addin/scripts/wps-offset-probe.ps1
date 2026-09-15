# WPS 字符偏移口径真机探针（dev-board#264 的前置测量）
#
# 回答的问题：doc.Range().Text 这根 JS 字符串的 UTF-16 下标，与 doc.Range(start,end)
# 接受的字符位置是不是同一套口径？含表格（\x07 占位）、域、批注标记、脚注、超链接
# 的文档上会不会错位、错多少？以及 Find 能不能只定位不替换、它的边界在哪。
# 测量结果决定「写入类命令的 Find 兜底」是常态路径还是例外路径。
#
# 用法（Windows 虚拟机，WPS 已启动）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File <本文件路径>
# 报告去向：剪贴板（前缀 WPSPROBE>>>）+ %USERPROFILE%\wps-probe-report.json
#
# 环境坑（2026-08-29 实测，见 .claude/agents/office-addin.md「WPS 加载项」章）：
#   - 同机装了 Microsoft Office 时，`Word.Application` 与 `kwps.Application` 会双双
#     解析到微软 Word（Office 覆盖了 WPS 的 ProgID），`wps.Application` 未注册。
#     本脚本因此自解析 ProgID 并拒收 Name 含 Microsoft 的宿主，把证据留在
#     progIdAttempts 字段里；这种机器上 COM 走不通，改走 JS 宏或任务窗格 devtools。
#
# 脚本主体刻意全 ASCII：Windows PowerShell 5.1 读不带 BOM 的 UTF-8 脚本会把非 ASCII
# 字符读乱，所以文中每个中文串都由 U() 按码位拼出来（注释行不参与执行，不受影响）。

$ErrorActionPreference = 'Continue'
$report = [ordered]@{}
$errors = New-Object System.Collections.ArrayList
function Note($m) { [void]$errors.Add([string]$m) }
function U([int[]]$cp) { -join ($cp | ForEach-Object { [char]$_ }) }
function IdxOf([string]$h, [string]$n) { return $h.IndexOf($n, [System.StringComparison]::Ordinal) }

function Emit($obj) {
  $j = $obj | ConvertTo-Json -Depth 12 -Compress
  try { [System.IO.File]::WriteAllText((Join-Path $env:USERPROFILE 'wps-probe-report.json'), $j, (New-Object System.Text.UTF8Encoding($false))) } catch { }
  try { Set-Clipboard -Value ("WPSPROBE>>>" + $j) } catch { }
  Write-Host "EMITTED len=$($j.Length)"
}

# ------------------------------------------------ resolve a WPS application
# 判宿主必须看可执行文件路径，不能看 Application.Name：**WPS 的 COM 层为了让
# 针对 Word 写的 VBA 宏原样能跑，Name 属性直接返回 "Microsoft Word"**（2026-08-29
# 实测：机器上 Word 已卸载，kwps.Application 建出来的对象 Name 仍是 Microsoft Word）。
# 按 Name 拒收会把 WPS 自己挡在门外。
$app = $null
$appVia = $null
$fallback = $null
$fallbackVia = $null
$tried = New-Object System.Collections.ArrayList
foreach ($pg in @('kwps.Application', 'wps.Application', 'KWPS.Application')) {
  foreach ($how in @('active', 'create')) {
    if ($app) { break }
    try {
      $o = if ($how -eq 'active') { [Runtime.InteropServices.Marshal]::GetActiveObject($pg) } else { New-Object -ComObject $pg }
      $nm = ''
      $pth = ''
      try { $nm = [string]$o.Name } catch { }
      try { $pth = [string]$o.Path } catch { }
      [void]$tried.Add("$pg/$how => name='$nm' path='$pth'")
      if ($pth -match '(?i)wps|kingsoft') {
        $app = $o
        $appVia = "$pg/$how (path)"
      } elseif ($pth -notmatch '(?i)Microsoft Office' -and -not $fallback) {
        # 路径认不出来（旧版可能不暴露 Path），先留作备胎：kwps.Application 这个
        # ProgID 本来就是 WPS 的，只要不是明确指向 Microsoft Office 就可以用
        $fallback = $o
        $fallbackVia = "$pg/$how (progid only, path unverified)"
      }
    } catch { [void]$tried.Add("$pg/$how => ERR $($_.Exception.Message.Split("`n")[0])") }
  }
}
if (-not $app -and $fallback) {
  $app = $fallback
  $appVia = $fallbackVia
}
$report.progIdAttempts = $tried
$report.hostResolvedVia = $appVia
if (-not $app) {
  $report.fatal = 'no WPS application object'
  $report.errors = $errors
  Emit $report
  exit 1
}
$report.appName = [string]$app.Name
try { $report.appPath = [string]$app.Path } catch { }
try { $report.appVersion = [string]$app.Version } catch { }
try { $report.appBuild = [string]$app.Build } catch { }
try { $app.Visible = $true } catch { }

# ------------------------------------------------------------- probe helper
function Probe($doc, [string]$body, [string]$probe, [string]$label) {
  $res = [ordered]@{ label = $label }
  $i = IdxOf $body $probe
  $res.jsIndex = $i
  if ($i -lt 0) { $res.status = 'absent-from-body'; return $res }
  $ok = $false
  try {
    $t = [string]$doc.Range($i, $i + $probe.Length).Text
    $res.rangeText = $t
    $ok = ($t -ceq $probe)
  } catch { $res.rangeError = $_.Exception.Message.Split("`n")[0] }
  $res.aligned = $ok
  if ($ok) { $res.delta = 0; return $res }
  $found = $null
  for ($d = -80; $d -le 80; $d++) {
    $s = $i + $d
    if ($s -lt 0) { continue }
    try { if ([string]$doc.Range($s, $s + $probe.Length).Text -ceq $probe) { $found = $d; break } } catch { }
  }
  $res.delta = $found
  return $res
}

function Snapshot($doc, [string]$name, [string[]]$probes) {
  $body = [string]$doc.Range().Text
  $st = [ordered]@{ name = $name; bodyLen = $body.Length }
  try { $st.contentEnd = $doc.Content.End } catch { $st.contentEnd = 'err' }
  try { $st.rangeEnd = $doc.Range().End } catch { $st.rangeEnd = 'err' }
  try { $st.endMinusBodyLen = [int]$st.contentEnd - [int]$st.bodyLen } catch { $st.endMinusBodyLen = 'err' }
  $cc = New-Object System.Collections.ArrayList
  for ($k = 0; $k -lt $body.Length; $k++) { $c = [int]$body[$k]; if ($c -lt 32) { [void]$cc.Add("$k :$c") } }
  $st.ctrlChars = $cc
  $ps = New-Object System.Collections.ArrayList
  foreach ($p in $probes) { [void]$ps.Add((Probe $doc $body $p $p)) }
  $st.probes = $ps
  return $st
}

# ------------------------------------------------------------------ markers
$CN_P1 = U 0x7B2C,0x4E00,0x6BB5,0x6B63,0x6587
$CN_CELL = U 0x8868,0x683C,0x5185
$CN_TAIL = U 0x672B,0x5C3E,0x6BB5
$M1 = 'M1PLAIN'; $M2 = 'M2BEFORETABLE'
$C11 = 'CELLA1'; $C12 = 'CELLB1'; $C21 = 'CELLA2'; $C22 = 'CELLB2'
$M3 = 'M3AFTERTABLE'; $M4 = 'M4COMMENTTARGET'; $M5 = 'M5FOOTNOTETARGET'
$M6 = 'M6LINKTARGET'; $M7 = 'M7INSERTTARGET'

$doc = $null
try { $doc = $app.Documents.Add() } catch { Note "Documents.Add: $($_.Exception.Message)"; $report.errors = $errors; Emit $report; exit 1 }

$stages = New-Object System.Collections.ArrayList
try {
  $doc.Content.InsertAfter("$M1 $CN_P1`r")
  $doc.Content.InsertAfter("$M2`r")
  [void]$stages.Add((Snapshot $doc 'A-plain' @($M1, $CN_P1, $M2)))
} catch { Note "A: $($_.Exception.Message)" }

try {
  $e = $doc.Content.End
  $tbl = $doc.Tables.Add($doc.Range($e - 1, $e - 1), 2, 2)
  $tbl.Cell(1, 1).Range.Text = "$C11 $CN_CELL"
  $tbl.Cell(1, 2).Range.Text = $C12
  $tbl.Cell(2, 1).Range.Text = $C21
  $tbl.Cell(2, 2).Range.Text = $C22
  $doc.Content.InsertAfter("$M3`r")
  [void]$stages.Add((Snapshot $doc 'B-table' @($M1, $M2, $C11, $CN_CELL, $C12, $C21, $C22, $M3)))
} catch { Note "B: $($_.Exception.Message)" }

try {
  $doc.Content.InsertAfter("$M4`r")
  $b = [string]$doc.Range().Text
  $i = IdxOf $b $M4
  if ($i -ge 0) { [void]$doc.Comments.Add($doc.Range($i, $i + $M4.Length), (U 0x6279,0x6CE8)) }
  [void]$stages.Add((Snapshot $doc 'C-comment' @($M1, $C22, $M3, $M4)))
} catch { Note "C: $($_.Exception.Message)" }

try {
  $doc.Content.InsertAfter("$M5`r")
  $b = [string]$doc.Range().Text
  $i = IdxOf $b $M5
  if ($i -ge 0) { [void]$doc.Footnotes.Add($doc.Range($i + $M5.Length, $i + $M5.Length), '', (U 0x811A,0x6CE8)) }
  [void]$stages.Add((Snapshot $doc 'D-footnote' @($M1, $C22, $M3, $M4, $M5)))
} catch { Note "D: $($_.Exception.Message)" }

try {
  $doc.Content.InsertAfter("$M6`r")
  $doc.Content.InsertAfter("$M7`r")
  $doc.Content.InsertAfter("$CN_TAIL`r")
  $b = [string]$doc.Range().Text
  $i = IdxOf $b $M6
  if ($i -ge 0) { [void]$doc.Hyperlinks.Add($doc.Range($i, $i + $M6.Length), 'https://example.com') }
  [void]$stages.Add((Snapshot $doc 'E-hyperlink' @($M1, $C22, $M3, $M4, $M5, $M6, $M7, $CN_TAIL)))
} catch { Note "E: $($_.Exception.Message)" }
$report.stages = $stages

# ------------------------- Find.Execute WITHOUT replace: does it locate?
$ft = New-Object System.Collections.ArrayList
foreach ($t in @($M1, $C22, $M3, $M7)) {
  $r = [ordered]@{ needle = $t }
  try {
    $b = [string]$doc.Range().Text
    $r.jsIndex = IdxOf $b $t
    $fr = $doc.Content
    try { $fr.Find.ClearFormatting() } catch { }
    $r.executeReturned = [bool]$fr.Find.Execute($t, $true, $false, $false, $false, $false, $true, 0, $false)
    $r.rangeTextAfter = [string]$fr.Text
    $r.startAfter = $fr.Start
    $r.endAfter = $fr.End
    $r.redefinedToMatch = ([string]$fr.Text -ceq $t)
    $r.startEqualsJsIndex = ($fr.Start -eq $r.jsIndex)
  } catch { $r.error = $_.Exception.Message.Split("`n")[0] }
  [void]$ft.Add($r)
}
$report.findAsLocator = $ft

# --------------------------------------------------------- Find hard limits
$lim = [ordered]@{}
try {
  $long = ('L' * 300)
  $doc.Content.InsertAfter("$long`r")
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  $lim.needle300 = [bool]$fr.Find.Execute($long, $true, $false, $false, $false, $false, $true, 0, $false)
} catch { $lim.needle300Error = $_.Exception.Message.Split("`n")[0] }
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  $lim.needle255 = [bool]$fr.Find.Execute(('L' * 255), $true, $false, $false, $false, $false, $true, 0, $false)
} catch { $lim.needle255Error = $_.Exception.Message.Split("`n")[0] }
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  $lim.crossParagraph = [bool]$fr.Find.Execute("$M6`r$M7", $true, $false, $false, $false, $false, $true, 0, $false)
} catch { $lim.crossParagraphError = $_.Exception.Message.Split("`n")[0] }
$report.findLimits = $lim

# --------------------- can a Find-derived Range be written to / anchored on?
$mut = [ordered]@{}
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  if ($fr.Find.Execute($M7, $true, $false, $false, $false, $false, $true, 0, $false)) {
    $fr.InsertAfter('_INSAFTER_')
    $b = [string]$doc.Range().Text
    $mut.insertAfterWorked = ((IdxOf $b ($M7 + '_INSAFTER_')) -ge 0)
  } else { $mut.insertAfterWorked = 'find-missed' }
} catch { $mut.insertAfterError = $_.Exception.Message.Split("`n")[0] }
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  if ($fr.Find.Execute($M3, $true, $false, $false, $false, $false, $true, 0, $false)) {
    $fr.Font.Bold = -1
    $mut.fontOnFindRange = 'ok'
    $mut.paragraphFromFindRange = [string]$fr.Paragraphs.Item(1).Range.Text
  }
} catch { $mut.fontOnFindRangeError = $_.Exception.Message.Split("`n")[0] }
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  if ($fr.Find.Execute($C22, $true, $false, $false, $false, $false, $true, 0, $false)) {
    [void]$doc.Comments.Add($fr, (U 0x6279,0x6CE8,0x4E8C))
    $mut.commentOnFindRangeInTable = 'ok'
  } else { $mut.commentOnFindRangeInTable = 'find-missed-cell-text' }
} catch { $mut.commentOnFindRangeInTableError = $_.Exception.Message.Split("`n")[0] }
try {
  $fr = $doc.Content; try { $fr.Find.ClearFormatting() } catch { }
  if ($fr.Find.Execute($M6, $true, $false, $false, $false, $false, $true, 0, $false)) {
    [void]$doc.Hyperlinks.Add($fr, 'https://example.org')
    $mut.hyperlinkOnFindRange = 'ok'
  }
} catch { $mut.hyperlinkOnFindRangeError = $_.Exception.Message.Split("`n")[0] }
$report.findRangeMutations = $mut

# ------------------------------- offsets while revisions are being recorded
$rev = [ordered]@{}
try {
  $prev = $doc.TrackRevisions
  $doc.TrackRevisions = $true
  $b = [string]$doc.Range().Text
  $i = IdxOf $b $M1
  $rev.beforeBodyLen = $b.Length
  if ($i -ge 0) { $doc.Range($i, $i + $M1.Length).Text = 'M1REPLACED' }
  $b2 = [string]$doc.Range().Text
  $rev.afterBodyLen = $b2.Length
  try { $rev.afterContentEnd = $doc.Content.End } catch { }
  try { $rev.revisionCount = $doc.Revisions.Count } catch { }
  $rev.probeAfterRevision = (Probe $doc $b2 $M3 'M3-after-revision')
  $rev.probeTail = (Probe $doc $b2 $CN_TAIL 'tail-after-revision')
  $doc.TrackRevisions = $prev
} catch { $rev.error = $_.Exception.Message.Split("`n")[0] }
$report.revisionRegime = $rev

# --------------------------------------- rough in-process bridge call timing
$perf = [ordered]@{}
try {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $n = 0
  $paras = $doc.Paragraphs
  $cnt = [Math]::Min(20, $paras.Count)
  for ($k = 1; $k -le $cnt; $k++) { $null = $paras.Item($k).Range.Text; $n += 3 }
  $sw.Stop()
  $perf.note = 'OUT-OF-PROCESS COM from PowerShell - upper bound only, NOT the in-process JSAPI bridge'
  $perf.calls = $n
  $perf.totalMs = $sw.Elapsed.TotalMilliseconds
  $perf.msPerCall = [Math]::Round($sw.Elapsed.TotalMilliseconds / [Math]::Max(1, $n), 4)
} catch { $perf.error = $_.Exception.Message.Split("`n")[0] }
$report.comTiming = $perf

$report.errors = $errors
try { $doc.SaveAs2((Join-Path $env:USERPROFILE 'wps-offset-probe.docx')) } catch { try { $doc.SaveAs((Join-Path $env:USERPROFILE 'wps-offset-probe.docx')) } catch { Note 'save failed' } }
Emit $report
