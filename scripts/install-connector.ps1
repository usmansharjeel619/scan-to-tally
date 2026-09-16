<#
    install-connector.ps1  --  installs the Scan to Tally connector as a
                               Windows service on the machine that holds Tally.

    WHAT THE CONNECTOR DOES
      Talks to Tally on localhost only, and dials OUT to the relay over a
      WebSocket. Nothing dials in. No inbound port is opened, no firewall rule
      is added, and no static IP is needed.

    WHY THAT MATTERS
      Tally's XML gateway on port 9000 has NO AUTHENTICATION. Anyone who can
      reach it can read every ledger and party in the company and post vouchers
      into it. It must never be reachable from a network, which is exactly what
      an outbound-only design guarantees.

    RUN AS ADMINISTRATOR:
      .\install-connector.ps1 -RelayUrl "wss://relay.example.com/connector/ws" `
                              -Secret "..." -Company "ACME FIRE SYSTEMS"

    To remove:  .\install-connector.ps1 -Uninstall
#>

param(
    [string] $RelayUrl   = "",
    [string] $Secret     = "",
    [string] $Company    = "",
    [string] $TallyUrl   = "http://127.0.0.1:9000",
    [string] $InstallDir = "C:\ScanToTally",
    [string] $ServiceName = "ScanToTallyConnector",
    # Phase 0 only. Lets the relay run READ-ONLY Tally queries so the XML
    # templates can be reconciled against this installation. Imports are refused
    # by the connector regardless. Turn it off once Phase 0 is signed off:
    #   .\install-connector.ps1 ... (without -Diagnostics)
    [switch] $Diagnostics,
    [switch] $Uninstall
)

$ErrorActionPreference = "Stop"

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Write-Host "This script must be run as Administrator." -ForegroundColor Red
        Write-Host "Right-click PowerShell and choose 'Run as administrator'."
        exit 1
    }
}

Assert-Admin

# --- uninstall --------------------------------------------------------------

if ($Uninstall) {
    Write-Host "Removing $ServiceName..." -ForegroundColor Cyan
    if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        sc.exe delete $ServiceName | Out-Null
        Write-Host "  service removed" -ForegroundColor Green
    } else {
        Write-Host "  service was not installed" -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "$InstallDir was left in place. It holds connector.db, which is the"
    Write-Host "record of which scan sessions already reached Tally. Deleting it risks"
    Write-Host "duplicate vouchers if the connector is ever reinstalled, so keep it"
    Write-Host "unless you are certain you are done." -ForegroundColor Yellow
    exit 0
}

# --- validate ---------------------------------------------------------------

foreach ($p in @(
    @{ n = "RelayUrl"; v = $RelayUrl },
    @{ n = "Secret";   v = $Secret },
    @{ n = "Company";  v = $Company })) {
    if (-not $p.v) {
        Write-Host "-$($p.n) is required." -ForegroundColor Red
        exit 1
    }
}

if ($Secret -match '^<.*>$' -or $Secret -eq "CHANGE-ME") {
    Write-Host "That secret is a placeholder, not the real one." -ForegroundColor Red
    Write-Host "Run bootstrap.ps1 instead -- it carries the real secret:" -ForegroundColor Yellow
    Write-Host "  irm <download-url>/bootstrap.ps1 -OutFile `$env:TEMP\stt.ps1; & `$env:TEMP\stt.ps1"
    exit 1
}

Write-Host ""
Write-Host "Scan to Tally connector" -ForegroundColor Cyan
Write-Host "  Install dir : $InstallDir"
Write-Host "  Tally       : $TallyUrl"
Write-Host "  Company     : $Company"
Write-Host "  Relay       : $RelayUrl"
Write-Host ""

# --- 1. can we actually reach Tally? ----------------------------------------

Write-Host "1. Checking Tally" -ForegroundColor White
try {
    Invoke-WebRequest -Uri $TallyUrl -TimeoutSec 8 -UseBasicParsing | Out-Null
    Write-Host "   Tally is answering on $TallyUrl" -ForegroundColor Green
}
catch {
    Write-Host "   Could not reach $TallyUrl" -ForegroundColor Yellow
    Write-Host "   The connector will install and simply wait, which is correct --"
    Write-Host "   a closed Tally is a transient condition, not a failure. But check:"
    Write-Host "     - TallyPrime is running with the company OPEN"
    Write-Host "     - F1 Help > Settings > Connectivity > Client/Server configuration:"
    Write-Host "         TallyPrime acts as = Both,  Port = 9000"
}

# --- 2. files ---------------------------------------------------------------

Write-Host ""
Write-Host "2. Installing files" -ForegroundColor White
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$exeSource = Join-Path $PSScriptRoot "connector.exe"
if (-not (Test-Path $exeSource)) {
    Write-Host "   connector.exe was not found next to this script." -ForegroundColor Red
    exit 1
}
$exeDest = Join-Path $InstallDir "connector.exe"
if ((Resolve-Path $exeSource).Path -ne $exeDest) {
    Copy-Item $exeSource $exeDest -Force
    Write-Host "   connector.exe -> $InstallDir" -ForegroundColor Green
} else {
    # bootstrap.ps1 already downloaded it straight into the install directory.
    Write-Host "   connector.exe already in place" -ForegroundColor Green
}

$configPath = Join-Path $InstallDir "connector.json"
$config = [ordered]@{
    tally = [ordered]@{
        baseUrl      = $TallyUrl
        company      = $Company
        timeoutSec   = 30
        probeSeconds = 15
        # Tally's TDL surface varies by version and company configuration.
        # A corrected query goes here rather than requiring a rebuilt service.
        tdl          = @{}
    }
    relay = [ordered]@{
        url         = $RelayUrl
        secret      = $Secret
        connectorId = "connector-$env:COMPUTERNAME"
        syncSeconds = 120
    }
    diagnostics = [ordered]@{
        enabled  = [bool]$Diagnostics
        maxBytes = 8000000
    }
    dbPath  = (Join-Path $InstallDir "connector.db")
    logFile = (Join-Path $InstallDir "connector.log")
    # Loopback only. The status page shows stock figures and must never be
    # bound to a routable address.
    statusAddr = "127.0.0.1:9787"
    logLevel   = "info"
}
$config | ConvertTo-Json -Depth 5 | Set-Content -Path $configPath -Encoding UTF8
Write-Host "   connector.json written" -ForegroundColor Green

# The config holds the relay secret, so keep it to Administrators and SYSTEM.
$acl = Get-Acl $configPath
$acl.SetAccessRuleProtection($true, $false)
foreach ($who in @("BUILTIN\Administrators", "NT AUTHORITY\SYSTEM")) {
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
        $who, "FullControl", "Allow")))
}
Set-Acl -Path $configPath -AclObject $acl
Write-Host "   permissions restricted to Administrators and SYSTEM" -ForegroundColor Green

# --- 3. service -------------------------------------------------------------

Write-Host ""
Write-Host "3. Registering the service" -ForegroundColor White
if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 2
}

$binPath = "`"$InstallDir\connector.exe`" -config `"$configPath`""
sc.exe create $ServiceName binPath= $binPath start= auto DisplayName= "Scan to Tally Connector" | Out-Null
sc.exe description $ServiceName "Bridges the warehouse scanning app to TallyPrime on this machine." | Out-Null

# Restart on failure. The commonest cause is Tally being closed, which the
# connector handles itself, but a crash must not leave the warehouse cut off
# until somebody notices.
sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/15000/restart/60000 | Out-Null

$logPath = Join-Path $InstallDir "connector.log"
Remove-Item $logPath -ErrorAction SilentlyContinue

try {
    Start-Service -Name $ServiceName -ErrorAction Stop
    Start-Sleep -Seconds 4
    $svc = Get-Service -Name $ServiceName
    Write-Host "   service is $($svc.Status)" -ForegroundColor Green
}
catch {
    # "The service did not respond" tells you nothing on its own. Run the same
    # binary in the foreground to get the actual error out of it.
    Write-Host "   The service would not start." -ForegroundColor Red
    Write-Host ""
    Write-Host "   Running it directly to see why:" -ForegroundColor Yellow
    $p = Start-Process -FilePath (Join-Path $InstallDir "connector.exe") `
            -ArgumentList @("-config", "`"$configPath`"") `
            -NoNewWindow -PassThru `
            -RedirectStandardOutput (Join-Path $InstallDir "console-out.txt") `
            -RedirectStandardError  (Join-Path $InstallDir "console-err.txt")
    Start-Sleep -Seconds 6
    if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }

    foreach ($f in @("console-err.txt", "console-out.txt", "connector.log")) {
        $path = Join-Path $InstallDir $f
        if ((Test-Path $path) -and (Get-Item $path).Length -gt 0) {
            Write-Host ""
            Write-Host "   --- $f ---" -ForegroundColor DarkGray
            Get-Content $path -Tail 20 | ForEach-Object { Write-Host "   $_" }
        }
    }
    Write-Host ""
    Write-Host "   Send the above back and it can be fixed." -ForegroundColor Yellow
    exit 1
}

# --- 4. keep the machine awake ----------------------------------------------

Write-Host ""
Write-Host "4. Power settings" -ForegroundColor White
# Tally has no headless mode: port 9000 exists only while Tally is running.
# A sleeping machine takes the whole warehouse offline.
powercfg /change standby-timeout-ac 0
powercfg /change hibernate-timeout-ac 0
powercfg /change monitor-timeout-ac 15
Write-Host "   sleep and hibernate disabled on mains power" -ForegroundColor Green

Write-Host ""
if ($Diagnostics) {
    Write-Host ""
    Write-Host "DIAGNOSTICS ARE ON." -ForegroundColor Yellow
    Write-Host "  The relay can run read-only Tally queries against this machine."
    Write-Host "  Imports are refused; only Export requests are allowed through."
    Write-Host "  Re-run this script WITHOUT -Diagnostics when Phase 0 is finished."
}

Write-Host "Done." -ForegroundColor Cyan
Write-Host "  Status page : http://127.0.0.1:9787  (this machine only)"
Write-Host "  Logs        : Get-EventLog -LogName Application -Source $ServiceName"
Write-Host "  Stop/start  : Stop-Service $ServiceName  /  Start-Service $ServiceName"
Write-Host ""
Write-Host "Still to do by hand, because Tally cannot be configured from here:" -ForegroundColor Yellow
Write-Host "  - Set TallyPrime to start automatically at login"
Write-Host "  - Set it to load '$Company' on startup (tally.ini)"
Write-Host "  - If this is a laptop that leaves the building, the warehouse app"
Write-Host "    goes read-only while it is away. A dedicated always-on machine"
Write-Host "    removes that failure mode entirely."
Write-Host ""
