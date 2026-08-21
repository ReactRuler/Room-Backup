$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "Building Room Backup..."
mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

$jar = Join-Path $root "target\RoomBackup.jar"
if (-not (Test-Path $jar)) { throw "missing $jar" }

$repo = Split-Path $root
$storeDir = Join-Path $repo "store-submission\Room Backup"
New-Item -ItemType Directory -Force -Path $storeDir | Out-Null

$stage = Join-Path $env:TEMP ("room-backup-store-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item $jar (Join-Path $stage "RoomBackup.jar") -Force
New-Item -ItemType Directory -Force -Path (Join-Path $stage "snapshots") | Out-Null

$zip = Join-Path $storeDir "extension.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force
Remove-Item -Recurse -Force $stage

Write-Host "Wrote $zip"
Write-Host "Done."
