<#
    Scan to Tally -- update the connector on the Tally machine.

    RUN IN AN ADMINISTRATOR POWERSHELL.

    Downloads the current connector, swaps the binary and restarts the service.
    It does NOT touch the config or re-register the service, so nothing that
    already works can be disturbed: the relay address, the secret, the company
    and the new-product setting are all left exactly as they are.
#>

param(
    [string] $InstallDir  = "C:\ScanToTally",
    [string] $ServiceName = "ScanToTallyConnector"
)

$ErrorActionPreference = "Stop"

$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Run this in an Administrator PowerShell." -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $InstallDir)) {
    Write-Host "No connector at $InstallDir" -ForegroundColor Red
    exit 1
}

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$base   = "__BASE_URL__"
$target = Join-Path $InstallDir "connector.exe"
$temp   = Join-Path $InstallDir "connector.new.exe"

Write-Host ""
Write-Host "Downloading the connector..." -ForegroundColor Cyan
Invoke-WebRequest -Uri "$base/connector.exe" -OutFile $temp -UseBasicParsing

$size = (Get-Item $temp).Length
if ($size -lt 1000000) {
    Remove-Item $temp -Force
    Write-Host "The download looks wrong ($size bytes). Nothing was changed." -ForegroundColor Red
    exit 1
}
Write-Host "   got $([math]::Round($size / 1MB, 1)) MB" -ForegroundColor Green

Write-Host "Stopping $ServiceName..." -ForegroundColor Cyan
if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    # The file stays locked for a moment after the service reports stopped.
    Start-Sleep -Seconds 3
}

# Keep the old binary until the new one is in place, so a failed swap can be
# undone rather than leaving the machine with no connector at all.
$backup = Join-Path $InstallDir "connector.previous.exe"
if (Test-Path $target) {
    Copy-Item $target $backup -Force
}

try {
    Move-Item $temp $target -Force
} catch {
    Write-Host "Could not replace the connector: $($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path $backup) { Copy-Item $backup $target -Force }
    Start-Service -Name $ServiceName -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "Starting $ServiceName..." -ForegroundColor Cyan

# Start-Service THROWS when the service will not start, and with
# ErrorActionPreference = Stop that ended the script before the rollback below
# could run -- so a bad binary left the Tally machine with no connector at all
# and no way back. It is caught now, and the previous binary is always restored.
$started = $true
try {
    Start-Service -Name $ServiceName -ErrorAction Stop
} catch {
    $started = $false
    Write-Host "   it refused to start: $($_.Exception.Message)" -ForegroundColor Yellow
}
Start-Sleep -Seconds 4

$svc = Get-Service -Name $ServiceName
if (-not $started -or $svc.Status -ne "Running") {
    Write-Host ""
    Write-Host "Putting the previous connector back." -ForegroundColor Yellow

    # Keep the binary that failed, so the reason can be found rather than lost.
    $kept = Join-Path $InstallDir "connector.failed.exe"
    Copy-Item $target $kept -Force -ErrorAction SilentlyContinue

    Copy-Item $backup $target -Force
    Start-Service -Name $ServiceName -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3

    $svc = Get-Service -Name $ServiceName
    Write-Host "   rolled back; service is $($svc.Status)" -ForegroundColor $(
        if ($svc.Status -eq "Running") { "Green" } else { "Red" })
    Write-Host ""
    Write-Host "The update did NOT apply. The binary that failed is kept at:"
    Write-Host "   $kept"
    Write-Host "To see why it would not start, run it in this window:"
    Write-Host "   & '$kept'"
    exit 1
}

Write-Host "   service is $($svc.Status)" -ForegroundColor Green
Write-Host ""
Write-Host "Updated. Stock figures will refresh within two minutes." -ForegroundColor Green
