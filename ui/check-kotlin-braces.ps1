$root = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\app\src\main\java'
Get-ChildItem $root -Recurse -Filter *.kt | ForEach-Object {
  $t = Get-Content $_.FullName -Raw
  $pairs = @(@('{','}'), @('(',')'), @('[',']'))
  $bad = @()
  foreach ($p in $pairs) {
    $o = ([regex]::Matches($t, [regex]::Escape($p[0]))).Count
    $c = ([regex]::Matches($t, [regex]::Escape($p[1]))).Count
    if ($o -ne $c) { $bad += "$($p[0])=$o $($p[1])=$c" }
  }
  if ($bad.Count) { Write-Output "UNBALANCED $($_.Name): $($bad -join ', ')" }
}
Write-Output 'brace check done'
