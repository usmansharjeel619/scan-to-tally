<#
    Scan to Tally -- let the app add products Tally does not have yet.

    RUN IN AN ADMINISTRATOR POWERSHELL.

    Edits the connector's config in place and restarts the service. Nothing is
    downloaded and the service is not re-registered, so this cannot disturb a
    working install the way a full reinstall can.

    A stock item cannot be deleted once it has transactions, which is why this
    is a deliberate step rather than the default.

    Re-run with -Off to switch it back.
#>

param(
    [string] $InstallDir  = "C:\ScanToTally",
    [string] $ServiceName = "ScanToTallyConnector",
    [string] $Group       = "",
    [switch] $Off
)

$ErrorActionPreference = "Stop"

$principal = New-Object Security.Principal.WindowsPrincipal(
    [Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Run this in an Administrator PowerShell." -ForegroundColor Red
    exit 1
}

$configPath = Join-Path $InstallDir "connector.json"
if (-not (Test-Path $configPath)) {
    Write-Host "No connector config at $configPath" -ForegroundColor Red
    Write-Host "Is the connector installed? Expected folder: $InstallDir"
    exit 1
}

$wanted = -not $Off
$config = Get-Content -Raw -Path $configPath | ConvertFrom-Json

# The masters block is absent on connectors installed before this existed.
if ($null -eq $config.masters) {
    $config | Add-Member -NotePropertyName masters -NotePropertyValue ([pscustomobject]@{
        allowCreate   = $wanted
        defaultParent = $Group
    })
} else {
    $config.masters.allowCreate = $wanted
    if ($Group -ne "") { $config.masters.defaultParent = $Group }
}

# Go's JSON parser rejects a leading byte-order mark, and Set-Content writes one
# on Windows PowerShell 5. Write plain UTF-8.
$json = $config | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText($configPath, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
if ($wanted) {
    Write-Host "New products ON." -ForegroundColor Green
    Write-Host "  A scanned product Tally does not have will now be created from"
    Write-Host "  what the operator types at the scan."
} else {
    Write-Host "New products OFF." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Restarting $ServiceName..." -ForegroundColor Cyan
Restart-Service -Name $ServiceName -Force
Start-Sleep -Seconds 3

$svc = Get-Service -Name $ServiceName
Write-Host "   service is $($svc.Status)" -ForegroundColor Green
Write-Host ""
Write-Host "Check the phone: Receipts should still show Tally connected."
