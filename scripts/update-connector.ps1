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
Start-Service -Name $ServiceName
Start-Sleep -Seconds 4

$svc = Get-Service -Name $ServiceName
Write-Host "   service is $($svc.Status)" -ForegroundColor Green
Write-Host ""
if ($svc.Status -ne "Running") {
    Write-Host "It did not start. Putting the previous connector back." -ForegroundColor Yellow
    Copy-Item $backup $target -Force
    Start-Service -Name $ServiceName -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "Updated. Stock figures will refresh within two minutes." -ForegroundColor Green
