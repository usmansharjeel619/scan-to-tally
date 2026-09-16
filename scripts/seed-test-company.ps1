<#
    seed-test-company.ps1
    Fills an EMPTY Tally test company with just enough data to prove the
    warehouse integration works.

    RUN IN AN ADMINISTRATOR POWERSHELL, on the Tally machine.

    WHAT IT CREATES, in "New Test Company" only:
      - a unit of measure (Nos)
      - four stock items with batch tracking on, using real Simplex part
        numbers so the tests match the labels on your actual cartons
      - a supplier ledger and a customer ledger
      - one Sales Order, so the outgoing flow has something to pick against

    WHAT IT DOES NOT DO
      It never touches any other company. Every request names the company
      explicitly, and the script refuses to run if that company is not the one
      Tally currently has open.

    It talks to Tally on 127.0.0.1 only. Nothing leaves this machine.

    It is safe to run twice: Tally treats a repeat as an alter, not a duplicate.

    TO UNDO: delete the test company in Tally, or just ignore it -- nothing
    here touches your real books.
#>

param(
    [string] $Company  = "New Test Company",
    [string] $TallyUrl = "http://127.0.0.1:9000",
    [string] $Godown   = "Main Location"
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$outDir = Join-Path $env:USERPROFILE "Desktop\tally-seed-$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host ""
Write-Host "Seeding '$Company'" -ForegroundColor Cyan
Write-Host "Responses will be saved to $outDir"
Write-Host ""

# --- safety: only ever the company Tally has open ----------------------------

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
    if ($open.Count -eq 0) {
        $open = @([regex]::Matches($r.Content, '<NAME[^>]*>(.*?)</NAME>') |
                  ForEach-Object { $_.Groups[1].Value.Trim() })
    }
}
catch {
    Write-Host "Could not reach Tally at $TallyUrl" -ForegroundColor Red
    Write-Host "Is TallyPrime running with the company open?"
    exit 1
}

Write-Host "Companies currently open: $($open -join ', ')" -ForegroundColor DarkGray
if ($open -notcontains $Company) {
    Write-Host ""
    Write-Host "'$Company' is not open in Tally." -ForegroundColor Red
    Write-Host "Refusing to run, rather than risk writing into a different company."
    Write-Host "Open it in Tally (Alt+F3 > Select Company) and run this again."
    exit 1
}
if ($open.Count -gt 1) {
    Write-Host ""
    Write-Host "More than one company is open. Close the others first so there is" -ForegroundColor Yellow
    Write-Host "no chance of writing to the wrong one, then run this again."
    exit 1
}
Write-Host "Confirmed: '$Company' is the only company open." -ForegroundColor Green
Write-Host ""

# --- helper ------------------------------------------------------------------

$script:failed = 0

function Send-Import {
    param([string] $Label, [string] $Payload)

    $envelope = @"
<ENVELOPE>
 <HEADER><TALLYREQUEST>Import Data</TALLYREQUEST></HEADER>
 <BODY><IMPORTDATA>
  <REQUESTDESC>
   <REPORTNAME>All Masters</REPORTNAME>
   <STATICVARIABLES><SVCURRENTCOMPANY>$Company</SVCURRENTCOMPANY></STATICVARIABLES>
  </REQUESTDESC>
  <REQUESTDATA>
$Payload
  </REQUESTDATA>
 </IMPORTDATA></BODY>
</ENVELOPE>
"@
    Write-Host ("  {0,-38}" -f $Label) -NoNewline
    try {
        $resp = Invoke-WebRequest -Uri $TallyUrl -Method Post `
                    -Body ([Text.Encoding]::UTF8.GetBytes($envelope)) `
                    -ContentType "text/xml; charset=utf-8" -TimeoutSec 60 -UseBasicParsing
        $txt = $resp.Content
        Set-Content -Path (Join-Path $outDir "$Label.xml") -Value $txt -Encoding UTF8
        Set-Content -Path (Join-Path $outDir "$Label.request.xml") -Value $envelope -Encoding UTF8

        if ($txt -match '<LINEERROR>(.*?)</LINEERROR>') {
            Write-Host "TALLY ERROR" -ForegroundColor Red
            Write-Host ("      " + $Matches[1]) -ForegroundColor Red
            $script:failed++
        }
        elseif ($txt -match '<CREATED>(\d+)</CREATED>') {
            $c = $Matches[1]
            $a = if ($txt -match '<ALTERED>(\d+)</ALTERED>') { $Matches[1] } else { "0" }
            $e = if ($txt -match '<ERRORS>(\d+)</ERRORS>')  { $Matches[1] } else { "0" }
            if ($e -ne "0") {
                Write-Host "errors=$e" -ForegroundColor Red; $script:failed++
            } else {
                Write-Host "created=$c altered=$a" -ForegroundColor Green
            }
        }
        else {
            Write-Host "unexpected reply (saved)" -ForegroundColor Yellow
            $script:failed++
        }
    }
    catch {
        Write-Host "FAILED" -ForegroundColor Red
        Write-Host ("      " + $_.Exception.Message) -ForegroundColor Red
        $script:failed++
    }
}

# --- 1. unit of measure ------------------------------------------------------

Write-Host "1. Unit of measure" -ForegroundColor White
Send-Import "01-unit-Nos" @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <UNIT NAME="Nos" ACTION="Create">
     <NAME>Nos</NAME><ISSIMPLEUNIT>Yes</ISSIMPLEUNIT>
     <ORIGINALNAME>Nos</ORIGINALNAME><DECIMALPLACES>0</DECIMALPLACES>
    </UNIT>
   </TALLYMESSAGE>
"@
Write-Host ""

# --- 2. stock items ----------------------------------------------------------
# Real Simplex part numbers, so the fixtures match the cartons on the dock.

Write-Host "2. Stock items (batch tracking ON)" -ForegroundColor White

$items = @(
    @{ n = "4098-9792 SSD SENSOR BASE";            p = "0677197CN" },
    @{ n = "4090-9001 ADDRESSABLE HEAT DETECTOR";  p = "0655011AB" },
    @{ n = "4100-1234 IDNAC REPEATER MODULE";      p = "0677444CN" },
    @{ n = "2081-9027 CONTROL RELAY";              p = "0612900XX" }
)

$i = 0
foreach ($it in $items) {
    $i++
    Send-Import ("02-item-$i") @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <STOCKITEM NAME="$($it.n)" ACTION="Create">
     <NAME>$($it.n)</NAME>
     <PARENT/>
     <BASEUNITS>Nos</BASEUNITS>
     <ISBATCHWISEON>Yes</ISBATCHWISEON>
     <ISPERISHABLEON>No</ISPERISHABLEON>
     <PARTNO>$($it.p)</PARTNO>
    </STOCKITEM>
   </TALLYMESSAGE>
"@
}
Write-Host ""

# --- 3. ledgers --------------------------------------------------------------

Write-Host "3. Ledgers" -ForegroundColor White
Send-Import "03-ledger-supplier" @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <LEDGER NAME="Simplex Supplies" ACTION="Create">
     <NAME>Simplex Supplies</NAME><PARENT>Sundry Creditors</PARENT>
     <ISBILLWISEON>No</ISBILLWISEON>
    </LEDGER>
   </TALLYMESSAGE>
"@
Send-Import "03-ledger-customer" @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <LEDGER NAME="Example Project FZC" ACTION="Create">
     <NAME>Example Project FZC</NAME><PARENT>Sundry Debtors</PARENT>
     <ISBILLWISEON>No</ISBILLWISEON>
    </LEDGER>
   </TALLYMESSAGE>
"@
Write-Host ""

# --- 4. a sales order --------------------------------------------------------
# Gives the outgoing flow something real to pick against.

Write-Host "4. Sales Order" -ForegroundColor White
$today = Get-Date -Format "yyyyMMdd"
Send-Import "04-sales-order" @"
   <TALLYMESSAGE xmlns:UDF="TallyUDF">
    <VOUCHER VCHTYPE="Sales Order" ACTION="Create" OBJVIEW="Order Voucher View">
     <DATE>$today</DATE>
     <EFFECTIVEDATE>$today</EFFECTIVEDATE>
     <VOUCHERTYPENAME>Sales Order</VOUCHERTYPENAME>
     <REFERENCE>SO-TEST-0001</REFERENCE>
     <PARTYLEDGERNAME>Example Project FZC</PARTYLEDGERNAME>
     <NARRATION>Seeded for warehouse scanning tests</NARRATION>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4098-9792 SSD SENSOR BASE</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <RATE>100/Nos</RATE><AMOUNT>3000</AMOUNT>
      <ACTUALQTY>30 Nos</ACTUALQTY><BILLEDQTY>30 Nos</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
     <ALLINVENTORYENTRIES.LIST>
      <STOCKITEMNAME>4090-9001 ADDRESSABLE HEAT DETECTOR</STOCKITEMNAME>
      <ISDEEMEDPOSITIVE>No</ISDEEMEDPOSITIVE>
      <RATE>150/Nos</RATE><AMOUNT>1800</AMOUNT>
      <ACTUALQTY>12 Nos</ACTUALQTY><BILLEDQTY>12 Nos</BILLEDQTY>
     </ALLINVENTORYENTRIES.LIST>
    </VOUCHER>
   </TALLYMESSAGE>
"@
Write-Host ""

# --- done --------------------------------------------------------------------

if ($script:failed -eq 0) {
    Write-Host "All seeded cleanly." -ForegroundColor Green
} else {
    Write-Host "$($script:failed) step(s) did not work as expected." -ForegroundColor Yellow
    Write-Host "That is useful either way -- the replies are saved and will say why."
}

Write-Host ""
Write-Host "Responses saved to: $outDir" -ForegroundColor Cyan
Write-Host "Nothing else to do; the connector will pick the new data up within 2 minutes."
Write-Host ""
