<#
    seed-test-company.ps1
    Fills an EMPTY Tally test company with just enough data to prove the
    warehouse integration works.

    RUN IN AN ADMINISTRATOR POWERSHELL, on the Tally machine.

    Round trips to this machine are expensive, so where a piece of XML is not
    certain this tries SEVERAL SHAPES and keeps the first Tally accepts. That
    is the whole method of Phase 0: stop guessing at the schema, make Tally
    tell us which one it wants.

    WHAT IT CREATES, in the named company only:
      - a unit of measure
      - four stock items with batch tracking on, using real Simplex part
        numbers so the fixtures match the cartons on the dock
      - a supplier ledger and a customer ledger
      - one Sales Order, so the outgoing flow has something to pick against

    It never touches another company: every request names the company, and it
    refuses to run unless that company is the only one Tally has open.
    It talks to 127.0.0.1 only. Nothing leaves this machine.
    Safe to run repeatedly.
#>

param(
    [string] $Company  = "New Test Company",
    [string] $TallyUrl = "http://127.0.0.1:9000"
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$outDir = Join-Path $env:USERPROFILE "Desktop\tally-seed-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host ""
Write-Host "Seeding '$Company'" -ForegroundColor Cyan
Write-Host ""

# --- safety: only the company Tally has open ---------------------------------

$listBody = @'
<ENVELOPE><HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
<TYPE>Collection</TYPE><ID>SeedCo</ID></HEADER><BODY><DESC>
<STATICVARIABLES><SVEXPORTFORMAT>$$SysName:XML</SVEXPORTFORMAT></STATICVARIABLES>
<TDL><TDLMESSAGE><COLLECTION NAME="SeedCo" ISMODIFY="No"><TYPE>Company</TYPE>
<NATIVEMETHOD>NAME</NATIVEMETHOD></COLLECTION></TDLMESSAGE></TDL>
</DESC></BODY></ENVELOPE>
'@

try {
    $r = Invoke-WebRequest -Uri $TallyUrl -Method Post -Body $listBody `
            -ContentType "text/xml" -TimeoutSec 20 -UseBasicParsing
    $open = @([regex]::Matches($r.Content, '<COMPANY NAME="([^"]+)"') |
              ForEach-Object { $_.Groups[1].Value })
}
catch {
    Write-Host "Could not reach Tally at $TallyUrl" -ForegroundColor Red; exit 1
}

if ($open -notcontains $Company) {
    Write-Host "'$Company' is not open in Tally. Refusing to run." -ForegroundColor Red; exit 1
}
if ($open.Count -gt 1) {
    Write-Host "More than one company is open. Close the others and run again." -ForegroundColor Yellow; exit 1
}
Write-Host "Confirmed: '$Company' is the only company open." -ForegroundColor Green
Write-Host ""

# --- helpers -----------------------------------------------------------------

function Invoke-Import {
    param([string] $Label, [string] $Payload, [string] $ReportName = "All Masters")

    $envelope = @"
<ENVELOPE>
 <HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>
 <BODY><IMPORTDATA>
  <REQUESTDESC>
   <REPORTNAME>$ReportName</REPORTNAME>
   <STATICVARIABLES><SVCURRENTCOMPANY>$Company</SVCURRENTCOMPANY></STATICVARIABLES>
  </REQUESTDESC>
  <REQUESTDATA>
$Payload
  </REQUESTDATA>
 </IMPORTDATA></BODY>
</ENVELOPE>
"@
    $result = [ordered]@{ ok = $false; message = ""; raw = "" }
    try {
        $resp = Invoke-WebRequest -Uri $TallyUrl -Method Post `
                    -Body ([Text.Encoding]::UTF8.GetBytes($envelope)) `
                    -ContentType "text/xml; charset=utf-8" -TimeoutSec 60 -UseBasicParsing
        $txt = $resp.Content
        $result.raw = $txt
        Set-Content -Path (Join-Path $outDir "$Label.xml") -Value $txt -Encoding UTF8
        Set-Content -Path (Join-Path $outDir "$Label.request.xml") -Value $envelope -Encoding UTF8

        if ($txt -match '<LINEERROR>(.*?)</LINEERROR>') {
            $result.message = ($Matches[1] -replace '&apos;', "'")
        }
        elseif ($txt -match '<CREATED>(\d+)</CREATED>') {
            $c = $Matches[1]
            $e = if ($txt -match '<ERRORS>(\d+)</ERRORS>') { $Matches[1] } else { "0" }
            $a = if ($txt -match '<ALTERED>(\d+)</ALTERED>') { $Matches[1] } else { "0" }
            if ($e -ne "0") { $result.message = "errors=$e" }
            elseif ($c -eq "0" -and $a -eq "0") { $result.message = "created nothing" }
            else { $result.ok = $true; $result.message = "created=$c altered=$a" }
        }
        else { $result.message = "unexpected reply" }
    }
    catch { $result.message = $_.Exception.Message }
    return $result
}

# Try shapes in order; keep the first Tally accepts.
function Try-Variants {
    param([string] $What, [array] $Variants)

    Write-Host "  $What" -ForegroundColor White
    $n = 0
    foreach ($v in $Variants) {
        $n++
        Write-Host ("    {0,-32}" -f $v.name) -NoNewline
        $res = Invoke-Import "$What-$n-$($v.name)" $v.xml
        if ($res.ok) {
            Write-Host ("OK  " + $res.message) -ForegroundColor Green
            return $v.name
        }
        Write-Host $res.message -ForegroundColor DarkYellow
    }
    Write-Host "    -> none of the shapes worked" -ForegroundColor Red
    return $null
}

# --- 1. unit of measure ------------------------------------------------------
# The units collection came back EMPTY, so "Nos" genuinely does not exist and
# "DUPLICATE ORIGINAL NAME" was Tally objecting to the XML, not to a clash.
# Most likely cause: NAME attribute, <NAME> and <ORIGINALNAME> were all "Nos".

Write-Host "1. Unit of measure" -ForegroundColor Cyan
$unitWinner = Try-Variants "unit" @(
    @{ name = "minimal-no-attr"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT ACTION="Create">
     <NAME>Nos</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT><DECIMALPLACES>0</DECIMALPLACES>
    </UNIT>
   </TALLYMESSAGE>
"@ },
    @{ name = "attr-no-originalname"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT NAME="Nos" ACTION="Create">
     <NAME>Nos</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT><DECIMALPLACES>0</DECIMALPLACES>
    </UNIT>
   </TALLYMESSAGE>
"@ },
    @{ name = "distinct-formal-name"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT NAME="Nos" ACTION="Create">
     <NAME>Nos</NAME><ORIGINALNAME>Numbers</ORIGINALNAME>
     <ISSIMPLEUNIT>Yes</ISSIMPLEUNIT><DECIMALPLACES>0</DECIMALPLACES>
    </UNIT>
   </TALLYMESSAGE>
"@ },
    @{ name = "with-gst-uqc"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT NAME="Nos" ACTION="Create">
     <NAME>Nos</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT>
     <DECIMALPLACES>0</DECIMALPLACES><GSTREPUOM>NOS-NUMBERS</GSTREPUOM>
    </UNIT>
   </TALLYMESSAGE>
"@ },
    @{ name = "symbol-PCS"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT ACTION="Create">
     <NAME>PCS</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT><DECIMALPLACES>0</DECIMALPLACES>
    </UNIT>
   </TALLYMESSAGE>
"@ },
    @{ name = "unit-masters-report"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT ACTION="Create"><NAME>Nos</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT></UNIT>
   </TALLYMESSAGE>
"@ }
)

# Whichever symbol actually got created is the one the items must reference.
$unitSymbol = if ($unitWinner -eq "symbol-PCS") { "PCS" } else { "Nos" }
Write-Host ""

if (-not $unitWinner) {
    Write-Host "No unit could be created, so stock items cannot be either." -ForegroundColor Red
    Write-Host "The replies in $outDir will say why. Sending those back is enough."
    exit 1
}

# --- 2. stock items ----------------------------------------------------------

Write-Host "2. Stock items (batch tracking ON), unit = $unitSymbol" -ForegroundColor Cyan
$items = @(
    @{ n = "4098-9792 SSD SENSOR BASE";           p = "0677197CN" },
    @{ n = "4090-9001 ADDRESSABLE HEAT DETECTOR"; p = "0655011AB" },
    @{ n = "4100-1234 IDNAC REPEATER MODULE";     p = "0677444CN" },
    @{ n = "2081-9027 CONTROL RELAY";             p = "0612900XX" }
)

$itemOk = 0
foreach ($it in $items) {
    Write-Host ("    {0,-40}" -f $it.n) -NoNewline
    $res = Invoke-Import ("item-" + $it.p) @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <STOCKITEM ACTION="Create">
     <NAME>$($it.n)</NAME>
     <BASEUNITS>$unitSymbol</BASEUNITS>
     <ISBATCHWISEON>Yes</ISBATCHWISEON>
     <ISPERISHABLEON>No</ISPERISHABLEON>
     <PARTNO>$($it.p)</PARTNO>
    </STOCKITEM>
   </TALLYMESSAGE>
"@
    if ($res.ok) { Write-Host ("OK  " + $res.message) -ForegroundColor Green; $itemOk++ }
    else { Write-Host $res.message -ForegroundColor DarkYellow }
}
Write-Host ""

# --- 3. ledgers --------------------------------------------------------------

Write-Host "3. Ledgers" -ForegroundColor Cyan
foreach ($l in @(
    @{ n = "Simplex Supplies";   g = "Sundry Creditors" },
    @{ n = "Example Project FZC"; g = "Sundry Debtors" })) {
    Write-Host ("    {0,-40}" -f $l.n) -NoNewline
    $res = Invoke-Import ("ledger-" + ($l.n -replace '\W', '')) @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <LEDGER ACTION="Create">
     <NAME>$($l.n)</NAME><PARENT>$($l.g)</PARENT><ISBILLWISEON>No</ISBILLWISEON>
    </LEDGER>
   </TALLYMESSAGE>
"@
    if ($res.ok) { Write-Host ("OK  " + $res.message) -ForegroundColor Green }
    else { Write-Host $res.message -ForegroundColor DarkGray }  # already exists is fine
}
Write-Host ""

# --- 4. sales order ----------------------------------------------------------

if ($itemOk -gt 0) {
    Write-Host "4. Sales Order" -ForegroundColor Cyan
    $today = Get-Date -Format "yyyyMMdd"
    $soWinner = Try-Variants "salesorder" @(
        @{ name = "order-voucher-view"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <VOUCHER VCHTYPE="Sales Order" ACTION="Create" OBJVIEW="Order Voucher View">
     <DATE>$today</DATE><EFFECTIVEDATE>$today</EFFECTIVEDATE>
     <VOUCHERTYPENAME>Sales Order</VOUCHERTYPENAME>
     <REFERENCE>SO-TEST-0001</REFERENCE>
     <PARTYLEDGERNAME>Example Project FZC</PARTYLEDGERNAME>
     <NARRATION>Seeded for warehouse scanning tests</NARRATION>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4098-9792 SSD SENSOR BASE</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <RATE>100/$unitSymbol</RATE><AMOUNT>3000</AMOUNT>
      <ACTUALQTY>30 $unitSymbol</ACTUALQTY><BILLEDQTY>30 $unitSymbol</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4090-9001 ADDRESSABLE HEAT DETECTOR</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <RATE>150/$unitSymbol</RATE><AMOUNT>1800</AMOUNT>
      <ACTUALQTY>12 $unitSymbol</ACTUALQTY><BILLEDQTY>12 $unitSymbol</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
    </VOUCHER>
   </TALLYMESSAGE>
"@ },
        @{ name = "with-ledger-entry"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <VOUCHER VCHTYPE="Sales Order" ACTION="Create" OBJVIEW="Order Voucher View">
     <DATE>$today</DATE><VOUCHERTYPENAME>Sales Order</VOUCHERTYPENAME>
     <REFERENCE>SO-TEST-0001</REFERENCE>
     <PARTYLEDGERNAME>Example Project FZC</PARTYLEDGERNAME>
     <ALLLEDGERENTRIES.LIST>
      <LEDGERNAME>Example Project FZC</LEDGERNAME>
      <ISDEEMEDPOSITIVE>Yes</ISDEEMEDPOSITIVE><AMOUNT>-3000</AMOUNT>
     </ALLLEDGERENTRIES.LIST>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4098-9792 SSD SENSOR BASE</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <RATE>100/$unitSymbol</RATE><AMOUNT>3000</AMOUNT>
      <ACTUALQTY>30 $unitSymbol</ACTUALQTY><BILLEDQTY>30 $unitSymbol</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
    </VOUCHER>
   </TALLYMESSAGE>
"@ },
        @{ name = "no-objview"; xml = @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <VOUCHER VCHTYPE="Sales Order" ACTION="Create">
     <DATE>$today</DATE><VOUCHERTYPENAME>Sales Order</VOUCHERTYPENAME>
     <REFERENCE>SO-TEST-0001</REFERENCE>
     <PARTYLEDGERNAME>Example Project FZC</PARTYLEDGERNAME>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4098-9792 SSD SENSOR BASE</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <ACTUALQTY>30 $unitSymbol</ACTUALQTY><BILLEDQTY>30 $unitSymbol</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
    </VOUCHER>
   </TALLYMESSAGE>
"@ }
    )
    Write-Host ""
}

# --- summary -----------------------------------------------------------------

Write-Host "Summary" -ForegroundColor Cyan
Write-Host "  unit        : $(if ($unitWinner) { "$unitSymbol (via $unitWinner)" } else { 'FAILED' })"
Write-Host "  stock items : $itemOk of $($items.Count)"
Write-Host "  sales order : $(if ($soWinner) { "created (via $soWinner)" } else { 'not created' })"
Write-Host ""
Write-Host "Replies saved to: $outDir" -ForegroundColor DarkGray
Write-Host "The connector will pick up the new data within 2 minutes."
Write-Host ""
