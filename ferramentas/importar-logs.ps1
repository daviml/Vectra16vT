# Extrai os .msl do zip mais novo do TurboDash (mandado pelo WhatsApp) para a pasta logs/.
# Uso: .\importar-logs.ps1            -> pega o TurboDash-logs_*.zip mais recente em Downloads
#      .\importar-logs.ps1 caminho.zip -> usa esse zip
param([string]$Zip)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$logs = Join-Path $PSScriptRoot '..\logs' | Resolve-Path
if (-not $Zip) {
    $downloads = Join-Path $env:USERPROFILE 'Downloads'
    $found = Get-ChildItem $downloads -Filter 'TurboDash-logs_*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $found) { Write-Host "Nenhum TurboDash-logs_*.zip em $downloads"; exit 1 }
    $Zip = $found.FullName
}

Write-Host "Zip: $Zip"
$archive = [System.IO.Compression.ZipFile]::OpenRead($Zip)
try {
    $novos = 0
    foreach ($entry in $archive.Entries | Where-Object { $_.Name -like '*.msl' }) {
        $dest = Join-Path $logs $entry.Name
        if (Test-Path $dest) { Write-Host "  ja existe: $($entry.Name)"; continue }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest)
        Write-Host "  novo:      $($entry.Name)"
        $novos++
    }
    Write-Host "$novos log(s) novo(s) em $logs"
} finally { $archive.Dispose() }
