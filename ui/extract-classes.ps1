$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$files = @((Join-Path $root 'index.html'), (Join-Path $root 'app.js'))
$all = New-Object System.Collections.Generic.List[string]
foreach ($f in $files) {
    $t = Get-Content -Path $f -Raw
    foreach ($m in [regex]::Matches($t, 'class\s*=\s*"([^"]+)"')) {
        foreach ($c in ($m.Groups[1].Value -split '\s+')) { $all.Add($c) }
    }
    foreach ($m in [regex]::Matches($t, "className\s*=\s*'([^']+)'")) {
        foreach ($c in ($m.Groups[1].Value -split '\s+')) { $all.Add($c) }
    }
}
$all | Where-Object { $_ -and $_ -match '^[a-z\[]' } | Sort-Object -Unique | Set-Content -Path (Join-Path $root 'classes.txt') -Encoding utf8
Write-Output ("count=" + (Get-Content (Join-Path $root 'classes.txt')).Count)
