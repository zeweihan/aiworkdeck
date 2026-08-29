# WPS 表格/演示 API 真机探针：把 wpsEtHandlers.js / wpsWppHandlers.js 里
# 「从 Word/Office.js 类推、从未在 WPS 真机跑过」的调用逐条打一遍，看它到底
# 存不存在、签名对不对、失败时是抛异常还是静默返回 undefined。
#
# 用法（Windows 虚拟机）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File <本文件>
# 报告：剪贴板（前缀 ETWPP>>>）+ %USERPROFILE%\wps-etwpp-report.json + Mac 共享目录
#
# 判宿主按 Application.Path，不能按 Name（WPS 为兼容 Word VBA 宏，Name 返回
# "Microsoft Word"；表格/演示同理可能返回 Microsoft Excel/PowerPoint）。
# 本文件主体全 ASCII；中文只在注释里，且文件存为带 BOM 的 UTF-8。

$ErrorActionPreference = 'Continue'
$R = [ordered]@{}
function U([int[]]$cp) { -join ($cp | ForEach-Object { [char]$_ }) }

# 统一记法：每条 API 试探记 ok / err / value
function Try1([string]$name, [scriptblock]$sb) {
  $o = [ordered]@{ api = $name }
  try {
    $v = & $sb
    $o.ok = $true
    if ($null -ne $v) { $o.value = [string]$v }
  } catch {
    $o.ok = $false
    $o.err = $_.Exception.Message.Split("`n")[0]
  }
  return $o
}

function Emit($obj) {
  $j = $obj | ConvertTo-Json -Depth 12 -Compress
  try { [System.IO.File]::WriteAllText((Join-Path $env:USERPROFILE 'wps-etwpp-report.json'), $j, (New-Object System.Text.UTF8Encoding($false))) } catch { }
  try { [System.IO.File]::WriteAllText('\\Mac\Home\Downloads\wps-etwpp-report.json', $j, (New-Object System.Text.UTF8Encoding($false))) } catch { }
  try { Set-Clipboard -Value ('ETWPP>>>' + $j) } catch { }
  Write-Host "EMITTED len=$($j.Length)"
}

function GetApp([string[]]$progIds) {
  foreach ($pg in $progIds) {
    foreach ($how in @('active', 'create')) {
      try {
        $o = if ($how -eq 'active') { [Runtime.InteropServices.Marshal]::GetActiveObject($pg) } else { New-Object -ComObject $pg }
        $p = ''
        try { $p = [string]$o.Path } catch { }
        if ($p -match '(?i)wps|kingsoft') { return @{ app = $o; via = "$pg/$how"; path = $p } }
      } catch { }
    }
  }
  return $null
}

# ================================================================= 表格（ET）
$etInfo = GetApp @('ket.Application', 'et.Application', 'KET.Application')
if (-not $etInfo) {
  $R.etFatal = 'no WPS ET application (tried ket./et./KET.Application)'
} else {
  $et = $etInfo.app
  $R.etVia = $etInfo.via
  $R.etPath = $etInfo.path
  try { $et.Visible = $true } catch { }
  try { $et.DisplayAlerts = $false } catch { }
  $et2 = New-Object System.Collections.ArrayList
  $wb = $null
  try { $wb = $et.Workbooks.Add() } catch { $R.etAddErr = $_.Exception.Message.Split("`n")[0] }
  if ($wb) {
    $sh = $wb.ActiveSheet

    # --- 1) 空表的 UsedRange 形态（usedRangeIsEmpty 的依据）
    [void]$et2.Add((Try1 'empty UsedRange.Address' { $sh.UsedRange.Address($false, $false) }))
    [void]$et2.Add((Try1 'empty UsedRange.Rows.Count' { $sh.UsedRange.Rows.Count }))
    [void]$et2.Add((Try1 'empty UsedRange.Value2 isNull' { ($null -eq $sh.UsedRange.Value2) }))

    # --- 2) Value2 批量写（excel_set_values 整条命令都压在这上面）
    $arr = New-Object 'object[,]' 2,3
    $arr[0,0] = 'A'; $arr[0,1] = 'B'; $arr[0,2] = 'C'
    $arr[1,0] = 1;   $arr[1,1] = 2;   $arr[1,2] = 3
    [void]$et2.Add((Try1 'Range.Value2 = 2D array (write)' { $sh.Range('A1:C2').Value2 = $arr; 'written' }))
    [void]$et2.Add((Try1 'Range.Value2 readback A1' { $sh.Range('A1').Value2 }))
    [void]$et2.Add((Try1 'Range.Value2 readback C2' { $sh.Range('C2').Value2 }))

    # --- 3) 单格/单行/单列 Value2 的返回形态（read2D 的防御性归一是不是必要）
    [void]$et2.Add((Try1 'single cell Value2 type' { $sh.Range('A1').Value2.GetType().Name }))
    [void]$et2.Add((Try1 'single row A1:C1 Value2 type' { $sh.Range('A1:C1').Value2.GetType().Name }))
    [void]$et2.Add((Try1 'single row A1:C1 Value2 rank' { $sh.Range('A1:C1').Value2.Rank }))
    [void]$et2.Add((Try1 'single col A1:A2 Value2 rank' { $sh.Range('A1:A2').Value2.Rank }))

    # --- 4) 公式与错误值经 Value2 的形态（excel_set_formulas 靠 '#' 开头判错）
    [void]$et2.Add((Try1 'set Formula then read Value2' { $sh.Range('E1').Formula = '=1/0'; [string]$sh.Range('E1').Value2 }))
    [void]$et2.Add((Try1 'set bad name formula read Value2' { $sh.Range('E2').Formula = '=NOSUCHFN(1)'; [string]$sh.Range('E2').Value2 }))

    # --- 5) 列宽单位（POINTS_PER_CHAR = 5.69 这个换算对不对）
    [void]$et2.Add((Try1 'ColumnWidth default' { $sh.Columns.Item(1).ColumnWidth }))
    [void]$et2.Add((Try1 'Width(points) at default' { $sh.Columns.Item(1).Width }))
    [void]$et2.Add((Try1 'set ColumnWidth=20 then Width' { $sh.Columns.Item(1).ColumnWidth = 20; $sh.Columns.Item(1).Width }))

    # --- 6) 排序：Key1 传 Range 对象能不能编组
    [void]$et2.Add((Try1 'Range.Sort(Key1=Range)' { $sh.Range('A1:C2').Sort($sh.Range('A1'), 1, $null, $null, 1, $null, 1, 1); 'sorted' }))

    # --- 7) 名称管理
    [void]$et2.Add((Try1 'Names.Add(Name,RefersTo)' { $wb.Names.Add('probeName', '=Sheet1!$A$1'); 'added' }))
    [void]$et2.Add((Try1 'Names.Item(name).RefersTo' { [string]$wb.Names.Item('probeName').RefersTo }))

    # --- 8) 条件格式（xlCellValue=1 / AddColorScale）
    [void]$et2.Add((Try1 'FormatConditions.Add(cellValue)' { $sh.Range('A1:C2').FormatConditions.Add(1, 5, '=1'); 'added' }))
    [void]$et2.Add((Try1 'FormatConditions.AddColorScale(3)' { $sh.Range('A1:C2').FormatConditions.AddColorScale(3); 'added' }))
    [void]$et2.Add((Try1 'FormatConditions.Count' { $sh.Range('A1:C2').FormatConditions.Count }))
    [void]$et2.Add((Try1 'FormatConditions.Delete()' { $sh.Range('A1:C2').FormatConditions.Delete(); 'cleared' }))

    # --- 9) 数据验证
    [void]$et2.Add((Try1 'Validation.Add(list)' { $sh.Range('G1').Validation.Add(3, 1, 1, 'a,b,c'); 'added' }))
    [void]$et2.Add((Try1 'Validation.Delete()' { $sh.Range('G1').Validation.Delete(); 'deleted' }))

    # --- 10) 批注（WPS 表格是老式单条批注）
    [void]$et2.Add((Try1 'Range.AddComment(text)' { $sh.Range('A1').AddComment('probe note'); 'added' }))
    [void]$et2.Add((Try1 'Range.Comment.Text()' { [string]$sh.Range('A1').Comment.Text() }))
    [void]$et2.Add((Try1 'Comments.Count' { $sh.Comments.Count }))
    [void]$et2.Add((Try1 'Comment.Delete()' { $sh.Range('A1').Comment.Delete(); 'deleted' }))

    # --- 11) 图表
    [void]$et2.Add((Try1 'Shapes.AddChart2 exists' { $sh.Shapes.AddChart2(-1, 51); 'ok' }))
    [void]$et2.Add((Try1 'ChartObjects().Add fallback' { $sh.ChartObjects().Add(200, 200, 300, 200); 'ok' }))

    # --- 12) 冻结窗格 / 分组 / 保护
    [void]$et2.Add((Try1 'FreezePanes via Window' { $et.ActiveWindow.FreezePanes = $false; $sh.Range('B2').Select(); $et.ActiveWindow.FreezePanes = $true; 'frozen' }))
    [void]$et2.Add((Try1 'Rows.Group()' { $sh.Rows.Item(3).Group(); 'grouped' }))
    [void]$et2.Add((Try1 'Rows.Ungroup()' { $sh.Rows.Item(3).Ungroup(); 'ungrouped' }))
    [void]$et2.Add((Try1 'Worksheet.Protect()' { $sh.Protect(); 'protected' }))
    [void]$et2.Add((Try1 'Worksheet.Unprotect()' { $sh.Unprotect(); 'unprotected' }))

    # --- 13) 透视表（编组链最长的一条，任何一环失败可能表现为 undefined）
    [void]$et2.Add((Try1 'PivotCaches().Create' { $wb.PivotCaches().Create(1, 'Sheet1!R1C1:R2C3'); 'created' }))
    [void]$et2.Add((Try1 'PivotCaches().Create + CreatePivotTable' {
      $sh2 = $wb.Worksheets.Add()
      $pc = $wb.PivotCaches().Create(1, 'Sheet1!R1C1:R2C3')
      $pt = $pc.CreatePivotTable($sh2.Range('A1'))
      [string]$pt.Name
    }))

    # --- 14) 工作表管理：只剩一张表时删除的行为
    [void]$et2.Add((Try1 'Worksheets.Count' { $wb.Worksheets.Count }))
    [void]$et2.Add((Try1 'Worksheet.Delete with DisplayAlerts=false' { $wb.Worksheets.Item($wb.Worksheets.Count).Delete(); 'deleted' }))

    # --- 15) 自动筛选
    [void]$et2.Add((Try1 'Range.AutoFilter() toggle on' { $sh.Range('A1:C2').AutoFilter(); 'on' }))
    [void]$et2.Add((Try1 'Worksheet.AutoFilterMode' { $sh.AutoFilterMode }))
    [void]$et2.Add((Try1 'ShowAllData' { $sh.ShowAllData(); 'shown' }))

    try { $wb.Close($false) } catch { }
  }
  $R.et = $et2
}

# ================================================================ 演示（WPP）
$wppInfo = GetApp @('kwpp.Application', 'wpp.Application', 'KWPP.Application')
if (-not $wppInfo) {
  $R.wppFatal = 'no WPS WPP application (tried kwpp./wpp./KWPP.Application)'
} else {
  $wpp = $wppInfo.app
  $R.wppVia = $wppInfo.via
  $R.wppPath = $wppInfo.path
  try { $wpp.Visible = $true } catch { }
  $w2 = New-Object System.Collections.ArrayList
  $pres = $null
  try { $pres = $wpp.Presentations.Add() } catch { $R.wppAddErr = $_.Exception.Message.Split("`n")[0] }
  if ($pres) {
    # --- 1) 加页：AddSlide 带插入位置的签名
    [void]$w2.Add((Try1 'Slides.Add(Index, Layout=2)' { $pres.Slides.Add(1, 2); 'added' }))
    [void]$w2.Add((Try1 'Slides.Count after add' { $pres.Slides.Count }))
    [void]$w2.Add((Try1 'Slides.Add(2, 2) insert at pos' { $pres.Slides.Add(2, 2); [string]$pres.Slides.Count }))

    # --- 2) 形状与文本
    [void]$w2.Add((Try1 'Slide.Shapes.Count' { $pres.Slides.Item(1).Shapes.Count }))
    [void]$w2.Add((Try1 'Shapes.AddTextbox + set text' {
      $sp = $pres.Slides.Item(1).Shapes.AddTextbox(1, 50, 50, 300, 60)
      $sp.TextFrame.TextRange.Text = 'HELLO PROBE TEXT'
      [string]$sp.TextFrame.TextRange.Text
    }))
    [void]$w2.Add((Try1 'TextRange.Characters(start,len).Text' {
      $sp = $pres.Slides.Item(1).Shapes.Item($pres.Slides.Item(1).Shapes.Count)
      [string]$sp.TextFrame.TextRange.Characters(1, 5).Text
    }))

    # --- 3) 子串挂超链接（类推 API，重点）
    [void]$w2.Add((Try1 'Characters().ActionSettings.Item(1).Hyperlink.Address' {
      $sp = $pres.Slides.Item(1).Shapes.Item($pres.Slides.Item(1).Shapes.Count)
      $sp.TextFrame.TextRange.Characters(1, 5).ActionSettings.Item(1).Hyperlink.Address = 'https://example.com'
      [string]$sp.TextFrame.TextRange.Characters(1, 5).ActionSettings.Item(1).Hyperlink.Address
    }))
    [void]$w2.Add((Try1 'whole TextRange ActionSettings hyperlink' {
      $sp = $pres.Slides.Item(1).Shapes.Item($pres.Slides.Item(1).Shapes.Count)
      $sp.TextFrame.TextRange.ActionSettings.Item(1).Hyperlink.Address = 'https://example.org'
      [string]$sp.TextFrame.TextRange.ActionSettings.Item(1).Hyperlink.Address
    }))

    # --- 4) 表格形状
    [void]$w2.Add((Try1 'Shapes.AddTable(2,2)' { $pres.Slides.Item(1).Shapes.AddTable(2, 2, 100, 200, 300, 100); 'added' }))
    [void]$w2.Add((Try1 'Table.Cell(1,1).Shape.TextFrame.TextRange.Text=' {
      $sl = $pres.Slides.Item(1)
      $tb = $null
      for ($i = 1; $i -le $sl.Shapes.Count; $i++) {
        $s = $sl.Shapes.Item($i)
        try { if ($s.HasTable -eq -1) { $tb = $s.Table } } catch { }
      }
      if (-not $tb) { throw 'no table shape found' }
      $tb.Cell(1, 1).Shape.TextFrame.TextRange.Text = 'CELL11'
      [string]$tb.Cell(1, 1).Shape.TextFrame.TextRange.Text
    }))
    [void]$w2.Add((Try1 'Table.Rows.Count / Columns.Count' {
      $sl = $pres.Slides.Item(1)
      for ($i = 1; $i -le $sl.Shapes.Count; $i++) {
        $s = $sl.Shapes.Item($i)
        try { if ($s.HasTable -eq -1) { return "$($s.Table.Rows.Count)x$($s.Table.Columns.Count)" } } catch { }
      }
      throw 'no table'
    }))

    # --- 5) 挪页 / 删页
    [void]$w2.Add((Try1 'Slide.MoveTo(index)' { $pres.Slides.Item(1).MoveTo($pres.Slides.Count); 'moved' }))
    [void]$w2.Add((Try1 'Slide.Delete()' { $pres.Slides.Item($pres.Slides.Count).Delete(); 'deleted' }))
    [void]$w2.Add((Try1 'Slides.Count final' { $pres.Slides.Count }))

    # --- 6) 字体/下划线
    [void]$w2.Add((Try1 'TextRange.Font.Underline set' {
      $sp = $pres.Slides.Item(1).Shapes.Item(1)
      $sp.TextFrame.TextRange.Font.Underline = -1
      [string]$sp.TextFrame.TextRange.Font.Underline
    }))
    [void]$w2.Add((Try1 'TextRange.Font.Color.RGB set' {
      $sp = $pres.Slides.Item(1).Shapes.Item(1)
      $sp.TextFrame.TextRange.Font.Color.RGB = 255
      [string]$sp.TextFrame.TextRange.Font.Color.RGB
    }))

    try { $pres.Saved = $true } catch { }
    try { $pres.Close() } catch { }
  }
  $R.wpp = $w2
}

Emit $R
