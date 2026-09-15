# 验证「JS 下标 -> Document.Range 字符位置」的换算规律。
# 第一轮测量出的模型：表格的单元格/行结束符在 doc.Range().Text 里是 "\r\x07"
# 两个 UTF-16 单元，但在 Range(s,e) 坐标系里只占 1 个位置，
# 所以  docPos = jsIndex - (该下标之前的 \x07 个数)。
# 本脚本在更复杂的文档上端到端验证它，并测出它管不住的边界（批注/域/修订态）。
$ErrorActionPreference = 'Continue'
$r = [ordered]@{}
function U([int[]]$cp) { -join ($cp | ForEach-Object { [char]$_ }) }
function IdxOf([string]$h, [string]$n) { return $h.IndexOf($n, [System.StringComparison]::Ordinal) }

function BellShift([string]$body, [int]$jsIndex) {
  $n = 0
  for ($i = 0; $i -lt $jsIndex; $i++) { if ([int]$body[$i] -eq 7) { $n++ } }
  return $n
}

# 按模型换算后取 Range，逐笔校验文本是否与预期一致
function CheckMapped($doc, [string]$body, [string]$needle) {
  $res = [ordered]@{ needle = $needle }
  $i = IdxOf $body $needle
  $res.jsIndex = $i
  if ($i -lt 0) { $res.status = 'absent'; return $res }
  $s = $i - (BellShift $body $i)
  $e = ($i + $needle.Length) - (BellShift $body ($i + $needle.Length))
  $res.mappedStart = $s
  $res.mappedEnd = $e
  try {
    $t = [string]$doc.Range($s, $e).Text
    $res.mappedText = $t
    $res.ok = ($t -ceq $needle)
  } catch { $res.err = $_.Exception.Message.Split("`n")[0]; $res.ok = $false }
  return $res
}

$app = $null
foreach ($how in @('active', 'create')) {
  if ($app) { break }
  try {
    $o = if ($how -eq 'active') { [Runtime.InteropServices.Marshal]::GetActiveObject('kwps.Application') } else { New-Object -ComObject kwps.Application }
    if (([string]$o.Path) -match '(?i)wps|kingsoft') { $app = $o }
  } catch { }
}
if (-not $app) { $r.fatal = 'no wps'; $r | ConvertTo-Json -Depth 8 | Set-Content '\\Mac\Home\Downloads\wps-verify.json'; exit 1 }
$r.appPath = [string]$app.Path
$r.appBuild = [string]$app.Build
try { $app.Visible = $true } catch { }
$doc = $app.Documents.Add()

$CN = U 0x7532,0x65B9,0x4E59,0x65B9   # 甲方乙方
$marks = New-Object System.Collections.ArrayList

# ---- 更狠的文档：段落 + 5x4 表格（单元格里还有两段） + 段落 + 第二张表 + 段落
$doc.Content.InsertAfter("P0HEAD $CN`r")
$e0 = $doc.Content.End
$t1 = $doc.Tables.Add($doc.Range($e0 - 1, $e0 - 1), 5, 4)
for ($rw = 1; $rw -le 5; $rw++) {
  for ($cl = 1; $cl -le 4; $cl++) {
    $t1.Cell($rw, $cl).Range.Text = "R${rw}C${cl}X"
    [void]$marks.Add("R${rw}C${cl}X")
  }
}
# 一个单元格里塞两段，验证多段单元格
$t1.Cell(3, 2).Range.Text = "R3C2X" + [char]13 + "SECONDLINE"
[void]$marks.Add('SECONDLINE')
$doc.Content.InsertAfter("P1MID $CN`r")
[void]$marks.Add('P1MID')
$e1 = $doc.Content.End
$t2 = $doc.Tables.Add($doc.Range($e1 - 1, $e1 - 1), 2, 2)
$t2.Cell(1, 1).Range.Text = 'T2A'
$t2.Cell(2, 2).Range.Text = 'T2D'
[void]$marks.Add('T2A')
[void]$marks.Add('T2D')
$doc.Content.InsertAfter("P2TAIL $CN`r")
[void]$marks.Add('P2TAIL')
[void]$marks.Add('P0HEAD')

# ---- 1) 朴素直切 vs 按模型换算，逐个 marker 对照
$body = [string]$doc.Range().Text
$r.bodyLen = $body.Length
$r.contentEnd = $doc.Content.End
$r.bellCount = (BellShift $body $body.Length)
$naiveOk = 0; $mappedOk = 0
$rows = New-Object System.Collections.ArrayList
foreach ($m in $marks) {
  $i = IdxOf $body $m
  $naive = $false
  if ($i -ge 0) {
    try { $naive = ([string]$doc.Range($i, $i + $m.Length).Text) -ceq $m } catch { }
  }
  $chk = CheckMapped $doc $body $m
  if ($naive) { $naiveOk++ }
  if ($chk.ok) { $mappedOk++ }
  [void]$rows.Add([ordered]@{ needle = $m; jsIndex = $i; naiveAligned = $naive; mappedOk = $chk.ok; mappedStart = $chk.mappedStart; mappedText = $chk.mappedText })
}
$r.markerCount = $marks.Count
$r.naiveAligned = $naiveOk
$r.mappedAligned = $mappedOk
$r.perMarker = $rows

# ---- 2) 加批注后（批注引用标记占位置但不进文本）模型还成立吗
try {
  $b2 = [string]$doc.Range().Text
  $i = IdxOf $b2 'P1MID'
  $s = $i - (BellShift $b2 $i)
  [void]$doc.Comments.Add($doc.Range($s, $s + 5), (U 0x6279,0x6CE8))
  $b3 = [string]$doc.Range().Text
  $r.afterComment = [ordered]@{
    beforeMark = (CheckMapped $doc $b3 'P0HEAD')
    afterMark  = (CheckMapped $doc $b3 'P2TAIL')
  }
} catch { $r.afterCommentErr = $_.Exception.Message.Split("`n")[0] }

# ---- 3) 修订态下写一笔之后，模型还成立吗（插件所有写入都在 withTracking 里）
try {
  $prev = $doc.TrackRevisions
  $doc.TrackRevisions = $true
  $b4 = [string]$doc.Range().Text
  $i = IdxOf $b4 'R1C1X'
  $s = $i - (BellShift $b4 $i)
  $doc.Range($s, $s + 5).Text = 'R1C1Y'
  $b5 = [string]$doc.Range().Text
  $r.underRevision = [ordered]@{
    revisions = $doc.Revisions.Count
    laterMark = (CheckMapped $doc $b5 'P2TAIL')
    cellMark  = (CheckMapped $doc $b5 'R5C4X')
  }
  $doc.TrackRevisions = $prev
} catch { $r.underRevisionErr = $_.Exception.Message.Split("`n")[0] }

# ---- 4) Find 定位 + 命中后逐笔校验（有了校验就不是「猜」了）
$fr2 = New-Object System.Collections.ArrayList
foreach ($m in @('R5C4X', 'P2TAIL', 'SECONDLINE')) {
  $one = [ordered]@{ needle = $m }
  try {
    $rng = $doc.Content
    try { $rng.Find.ClearFormatting() } catch { }
    $one.found = [bool]$rng.Find.Execute($m, $true, $false, $false, $false, $false, $true, 0, $false)
    $one.text = [string]$rng.Text
    $one.verified = ([string]$rng.Text -ceq $m)
    $one.start = $rng.Start
  } catch { $one.err = $_.Exception.Message.Split("`n")[0] }
  [void]$fr2.Add($one)
}
$r.findVerified = $fr2

# ---- 5) 超长/跨段查找串：Execute 返回 true 之后命中的到底是不是原串
$lim = [ordered]@{}
try {
  $long = 'Z' * 300
  $doc.Content.InsertAfter("$long`r")
  $rng = $doc.Content
  try { $rng.Find.ClearFormatting() } catch { }
  $lim.long300Found = [bool]$rng.Find.Execute($long, $true, $false, $false, $false, $false, $true, 0, $false)
  $lim.long300Verified = ([string]$rng.Text -ceq $long)
  $lim.long300GotLen = ([string]$rng.Text).Length
} catch { $lim.long300Err = $_.Exception.Message.Split("`n")[0] }
try {
  $rng = $doc.Content
  try { $rng.Find.ClearFormatting() } catch { }
  $cross = "P1MID $CN" + [char]13 + 'T2A'
  $lim.crossFound = [bool]$rng.Find.Execute($cross, $true, $false, $false, $false, $false, $true, 0, $false)
  $lim.crossVerified = ([string]$rng.Text -ceq $cross)
  $lim.crossGot = ([string]$rng.Text).Substring(0, [Math]::Min(40, ([string]$rng.Text).Length))
} catch { $lim.crossErr = $_.Exception.Message.Split("`n")[0] }
$r.findLimitsVerified = $lim

$j = $r | ConvertTo-Json -Depth 12 -Compress
[System.IO.File]::WriteAllText('\\Mac\Home\Downloads\wps-verify.json', $j, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "VERIFY_DONE naive=$naiveOk mapped=$mappedOk of $($marks.Count)"
