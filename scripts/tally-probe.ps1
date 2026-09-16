<#
    tally-probe.ps1  --  Phase 0 evidence collector for the Scan to Tally project

    WHAT THIS DOES
      Asks the Tally running on THIS machine for its schema and saves the
      answers to a folder. Nothing is changed, created or deleted: every
      request is an export. It is safe to run on a live company, though using
      a test company is still better.

    WHY IT MATTERS
      Tally's XML schema differs between versions and company configurations.
      The app is written against templates; these files replace the templates
      with what your Tally actually says. Without them we are guessing.

    HOW TO RUN
      1. In TallyPrime:  F1 Help > Settings > Connectivity >
                         Client/Server configuration
            TallyPrime acts as .......... Both
            Enable ODBC ................. Yes
            Port ........................ 9000
         Accept, and leave the company OPEN.

      2. Right-click this file > Run with PowerShell.
         If Windows blocks it, open PowerShell and run:
            Set-ExecutionPolicy -Scope Process Bypass
            .\tally-probe.ps1

      3. Send back the .zip it creates on your Desktop.

    BEFORE STEP 2, hand-key these two vouchers in Tally, dated TODAY. They are
    the most valuable part of the whole exercise, because exporting a voucher
    Tally itself created is the only way to learn the exact field names:

      A) A RECEIPT NOTE with ONE stock item and THREE different batches
         (box numbers) on that single item, each with a different quantity,
         and a godown set on each batch. Put a reference and a narration on it.

      B) A SALES ORDER for one item, then a DELIVERY NOTE raised AGAINST that
         sales order, delivering only PART of the ordered quantity from a
         named batch.

    If your Tally is not on port 9000, run:  .\tally-probe.ps1 -Port 9001
#>

param(
    [string] $TallyHost = "127.0.0.1",
    [int]    $Port      = 9000,
    [string] $Company   = "",          # blank = whichever company is open
    [int]    $DaysBack  = 3            # day book window for the hand-keyed vouchers
)

$ErrorActionPreference = "Stop"
$base = "http://${TallyHost}:${Port}"

$stamp   = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir  = Join-Path ([Environment]::GetFolderPath("Desktop")) "tally-probe-$stamp"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host ""
Write-Host "Scan to Tally - Phase 0 probe" -ForegroundColor Cyan
Write-Host "Target : $base"
Write-Host "Output : $outDir"
Write-Host ""

function Send-Tally {
    param([string] $Name, [string] $Xml)

    Write-Host ("  {0,-22}" -f $Name) -NoNewline
    $file = Join-Path $outDir "$Name.xml"
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Xml)
        $resp  = Invoke-WebRequest -Uri $base -Method Post -Body $bytes `
                    -ContentType "text/xml; charset=utf-8" -TimeoutSec 120 -UseBasicParsing

        $text = $resp.Content
        # Save the request too: if a query returns nothing we need to see what
        # was asked, not just what came back.
        Set-Content -Path (Join-Path $outDir "$Name.request.xml") -Value $Xml -Encoding UTF8
        Set-Content -Path $file -Value $text -Encoding UTF8

        if ($text -match "<LINEERROR>(.*?)</LINEERROR>") {
            Write-Host "TALLY ERROR" -ForegroundColor Yellow
            Write-Host ("      " + $Matches[1]) -ForegroundColor Yellow
        } else {
            Write-Host ("OK  ({0:N0} bytes)" -f $text.Length) -ForegroundColor Green
        }
    }
    catch {
        Write-Host "FAILED" -ForegroundColor Red
        Write-Host ("      " + $_.Exception.Message) -ForegroundColor Red
        Set-Content -Path (Join-Path $outDir "$Name.ERROR.txt") -Value $_.Exception.Message -Encoding UTF8
    }
}

function Export-Collection {
    param([string] $Name, [string] $Tdl, [string] $From = "", [string] $To = "")

    $sv = "<SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>"
    if ($Company) { $sv += "<SVCURRENTCOMPANY>$Company</SVCURRENTCOMPANY>" }
    if ($From)    { $sv += "<SVFROMDATE>$From</SVFROMDATE>" }
    if ($To)      { $sv += "<SVTODATE>$To</SVTODATE>" }

    Send-Tally $Name @"
<ENVELOPE>
 <HEADER>
  <VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Collection</TYPE><ID>$Name</ID>
 </HEADER>
 <BODY><DESC>
  <STATICVARIABLES>$sv</STATICVARIABLES>
  <TDL><TDLMESSAGE>$Tdl</TDLMESSAGE></TDL>
 </DESC></BODY>
</ENVELOPE>
"@
}

# --- 1. reachability --------------------------------------------------------

Write-Host "1. Reachability" -ForegroundColor White
try {
    $null = Invoke-WebRequest -Uri $base -TimeoutSec 10 -UseBasicParsing
    Write-Host "  Tally gateway is answering on $base" -ForegroundColor Green
}
catch {
    Write-Host "  Could not reach $base" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Check, in order:" -ForegroundColor Yellow
    Write-Host "    - TallyPrime is running and a company is OPEN"
    Write-Host "    - F1 Help > Settings > Connectivity > Client/Server configuration:"
    Write-Host "        TallyPrime acts as = Both,  Port = $Port"
    Write-Host "    - No other program is using port $Port"
    Write-Host ""
    exit 1
}
Write-Host ""

# --- 2. environment ---------------------------------------------------------

Write-Host "2. Environment" -ForegroundColor White
$env_info = [ordered]@{
    probedAt      = (Get-Date).ToString("o")
    computerName  = $env:COMPUTERNAME
    windowsVer    = (Get-CimInstance Win32_OperatingSystem).Caption
    tallyHost     = $base
    companyParam  = $Company
}
# Tally's own version, from its install folder, tells us which schema to expect.
$tallyExe = Get-ChildItem "C:\Program Files\TallyPrime\tally.exe",
                          "C:\Program Files (x86)\TallyPrime\tally.exe",
                          "C:\Tally\tally.exe" -ErrorAction SilentlyContinue |
            Select-Object -First 1
if ($tallyExe) {
    $env_info.tallyExe     = $tallyExe.FullName
    $env_info.tallyVersion = $tallyExe.VersionInfo.ProductVersion
    Write-Host "  TallyPrime $($tallyExe.VersionInfo.ProductVersion)" -ForegroundColor Green
} else {
    Write-Host "  Tally install folder not found (not a problem)" -ForegroundColor DarkGray
}
$env_info | ConvertTo-Json | Set-Content (Join-Path $outDir "00-environment.json") -Encoding UTF8
Write-Host ""

# --- 3. master data ---------------------------------------------------------

Write-Host "3. Master data" -ForegroundColor White

Export-Collection "STT_Companies" @"
<COLLECTION NAME="STT_Companies" ISMODIFY="No">
 <TYPE>Company</TYPE>
 <NATIVEMETHOD>NAME</NATIVEMETHOD><NATIVEMETHOD>STARTINGFROM</NATIVEMETHOD><NATIVEMETHOD>GUID</NATIVEMETHOD>
</COLLECTION>
"@

Export-Collection "STT_StockItems" @"
<COLLECTION NAME="STT_StockItems" ISMODIFY="No">
 <TYPE>StockItem</TYPE>
 <NATIVEMETHOD>NAME</NATIVEMETHOD><NATIVEMETHOD>PARENT</NATIVEMETHOD>
 <NATIVEMETHOD>BASEUNITS</NATIVEMETHOD><NATIVEMETHOD>ADDITIONALUNITS</NATIVEMETHOD>
 <NATIVEMETHOD>ISBATCHWISEON</NATIVEMETHOD><NATIVEMETHOD>ISPERISHABLEON</NATIVEMETHOD>
 <NATIVEMETHOD>PARTNO</NATIVEMETHOD><NATIVEMETHOD>GUID</NATIVEMETHOD>
 <FETCH>ALIASNAME</FETCH>
</COLLECTION>
"@

Export-Collection "STT_Godowns" @"
<COLLECTION NAME="STT_Godowns" ISMODIFY="No">
 <TYPE>Godown</TYPE>
 <NATIVEMETHOD>NAME</NATIVEMETHOD><NATIVEMETHOD>PARENT</NATIVEMETHOD><NATIVEMETHOD>GUID</NATIVEMETHOD>
</COLLECTION>
"@

Export-Collection "STT_VoucherTypes" @"
<COLLECTION NAME="STT_VoucherTypes" ISMODIFY="No">
 <TYPE>VoucherType</TYPE>
 <NATIVEMETHOD>NAME</NATIVEMETHOD><NATIVEMETHOD>PARENT</NATIVEMETHOD><NATIVEMETHOD>NUMBERINGMETHOD</NATIVEMETHOD>
</COLLECTION>
"@
Write-Host ""

# --- 4. the queries the app depends on --------------------------------------

Write-Host "4. Batch balances  (the outgoing quantity ceiling)" -ForegroundColor White
# Three shapes, because this is the query we are least sure of. Whichever one
# comes back populated is the one the connector will be configured to use.
Export-Collection "STT_BatchBalances_A" @"
<COLLECTION NAME="STT_BatchBalances_A" ISMODIFY="No">
 <TYPE>StockItem</TYPE>
 <NATIVEMETHOD>NAME</NATIVEMETHOD><NATIVEMETHOD>BASEUNITS</NATIVEMETHOD>
 <NATIVEMETHOD>CLOSINGBALANCE</NATIVEMETHOD>
 <FETCH>BATCHALLOCATIONS.*</FETCH>
</COLLECTION>
"@

Export-Collection "STT_BatchBalances_B" @"
<COLLECTION NAME="STT_BatchBalances_B" ISMODIFY="No">
 <TYPE>StockItem</TYPE>
 <FETCH>NAME, BASEUNITS, CLOSINGBALANCE</FETCH>
 <FETCH>BATCHALLOCATIONS.BATCHNAME, BATCHALLOCATIONS.GODOWNNAME</FETCH>
 <FETCH>BATCHALLOCATIONS.CLOSINGBALANCE, BATCHALLOCATIONS.CLOSINGQTY</FETCH>
</COLLECTION>
"@

Export-Collection "STT_BatchBalances_C" @"
<COLLECTION NAME="STT_BatchBalances_C" ISMODIFY="No">
 <TYPE>BatchAllocations</TYPE>
 <FETCH>*</FETCH>
</COLLECTION>
"@
Write-Host ""

Write-Host "5. Sales orders  (with what is still outstanding)" -ForegroundColor White
Export-Collection "STT_SalesOrders" @"
<COLLECTION NAME="STT_SalesOrders" ISMODIFY="No">
 <TYPE>Voucher</TYPE>
 <FILTER>STT_IsSO</FILTER>
 <FETCH>VOUCHERNUMBER, DATE, PARTYLEDGERNAME, REFERENCE, GUID, VOUCHERTYPENAME</FETCH>
 <FETCH>ALLINVENTORYENTRIES.*</FETCH>
</COLLECTION>
<SYSTEM TYPE="Formulae" NAME="STT_IsSO">`$VOUCHERTYPENAME = "Sales Order"</SYSTEM>
"@
Write-Host ""

# --- 6. THE IMPORTANT ONE: real vouchers Tally itself created ---------------

Write-Host "6. Day book  (your hand-keyed vouchers - the golden templates)" -ForegroundColor White
$from = (Get-Date).AddDays(-$DaysBack).ToString("yyyyMMdd")
$to   = (Get-Date).ToString("yyyyMMdd")

$sv = "<SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT><SVFROMDATE>$from</SVFROMDATE><SVTODATE>$to</SVTODATE>"
if ($Company) { $sv += "<SVCURRENTCOMPANY>$Company</SVCURRENTCOMPANY>" }

Send-Tally "STT_DayBook" @"
<ENVELOPE>
 <HEADER>
  <VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Data</TYPE><ID>DayBook</ID>
 </HEADER>
 <BODY><DESC><STATICVARIABLES>$sv</STATICVARIABLES></DESC></BODY>
</ENVELOPE>
"@
Write-Host ""
Write-Host "   The day book is the single most useful file here: it contains the" -ForegroundColor DarkGray
Write-Host "   exact XML Tally produces for a Receipt Note with several batches" -ForegroundColor DarkGray
Write-Host "   and a Delivery Note linked to a Sales Order." -ForegroundColor DarkGray
Write-Host ""

# --- 7. package -------------------------------------------------------------

$zip = "$outDir.zip"
Compress-Archive -Path "$outDir\*" -DestinationPath $zip -Force

Write-Host "Done." -ForegroundColor Cyan
Write-Host "  Folder : $outDir"
Write-Host "  Zip    : $zip" -ForegroundColor Green
Write-Host ""
Write-Host "Send the zip back. Before you do, open a couple of the .xml files in"
Write-Host "Notepad and check nothing in them is commercially sensitive - they"
Write-Host "contain item names, party names and order quantities from the company"
Write-Host "that was open."
Write-Host ""
