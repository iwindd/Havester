param(
    [string]$GameFolder = $null
)

$ErrorActionPreference = 'Stop'

if (-not $GameFolder) {
    $GameFolder = Read-Host "Enter your CobblemonOrigins game folder (e.g. E:\Minecraft\Game\CobblemonOrigins)"
    if (-not $GameFolder) {
        Write-Host "Usage: .\install.ps1 -GameFolder 'E:\...\CobblemonOrigins'" -ForegroundColor Yellow
        pause; exit 1
    }
}

$versionJson = Join-Path $GameFolder 'game\versions\CobblemonOrigins.json'
$jarSource = Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'Havester-*.jar' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
$destDir = Join-Path $GameFolder 'Havester\build\libs'

if (-not $jarSource) {
    Write-Host "ERROR: jar not found next to this script" -ForegroundColor Red
    Write-Host "Expected: Havester-*.jar" -ForegroundColor Red
    pause; exit 1
}

$jarDest = Join-Path $destDir $jarSource.Name

if (-not (Test-Path -LiteralPath $versionJson)) {
    Write-Host "ERROR: version json not found: $versionJson" -ForegroundColor Red
    Write-Host "Is this the correct game folder?" -ForegroundColor Yellow
    pause; exit 1
}

New-Item -ItemType Directory -Path $destDir -Force | Out-Null
Copy-Item -LiteralPath $jarSource.FullName -Destination $jarDest -Force
Write-Host "Copied jar to: $destDir" -ForegroundColor Green

$originalBytes = [System.IO.File]::ReadAllBytes($versionJson)
$hasBom = $originalBytes.Length -ge 3 -and $originalBytes[0] -eq 0xEF -and $originalBytes[1] -eq 0xBB -and $originalBytes[2] -eq 0xBF
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$utf8WithBom = [System.Text.UTF8Encoding]::new($true)
$encoding = if ($hasBom) { $utf8WithBom } else { $utf8NoBom }
Write-Host "Original JSON has BOM: $hasBom (will preserve)" -ForegroundColor Cyan

$jsonContent = $utf8NoBom.GetString($originalBytes)

if ($jsonContent -match 'fabric\.addMods') {
    Write-Host "fabric.addMods already present; updating jar path..." -ForegroundColor Yellow
    $jsonContent = [regex]::Replace($jsonContent, '"E:\\.*?Havester[^"]*\.jar"', '"' + ($jarDest -replace '\\','\\') + '"')
} else {
    Write-Host "Adding --fabric.addMods to version json..." -ForegroundColor Cyan
    $backup = $versionJson + '.havester.bak'
    [System.IO.File]::WriteAllBytes($backup, $originalBytes)
    Write-Host "Backup saved: $backup" -ForegroundColor Green

    $escapedJar = $jarDest -replace '\\', '\\'

    $insertBlock = "`r`n      {`r`n        ""values"": [`r`n          ""--fabric.addMods""`r`n        ]`r`n      },`r`n      {`r`n        ""values"": [`r`n          """ + $escapedJar + """`r`n        ]`r`n      },"

    $jsonContent = [regex]::Replace($jsonContent, '("game"\s*:\s*\[)', '$1' + $insertBlock, 1)
}

$newBytes = $encoding.GetBytes($jsonContent)
[System.IO.File]::WriteAllBytes($versionJson, $newBytes)

Write-Host "=========================================" -ForegroundColor Green
Write-Host "Install complete!" -ForegroundColor Green
Write-Host "Keys: K = start/stop  O = settings  P = unpause" -ForegroundColor Cyan
Write-Host "Restart the game to take effect." -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Green

pause
