<#
    voucher-keys.ps1  --  what does THIS Tally call a voucher?

    WHY
      To put a later carton into the voucher a product already has, the
      connector has to name that voucher in a way Tally recognises. Asked to
      alter voucher 999999 with ACTION="Alter" and a <MASTERID> child, this
      TallyPrime created a new voucher instead -- so that is not the name it
      answers to, and every merge would have been a duplicate.

      Rather than guess again, this reads back a voucher Tally itself wrote and
      prints the fields it uses to identify it. Whatever is in there is what
      the connector will send.

    IT CHANGES NOTHING. Every request here is an export. No voucher is created,
    altered or deleted.

    HOW TO RUN
      Open PowerShell (no need for Administrator) and paste:

        irm <RELAY_ORIGIN>/dl/<DOWNLOAD_PATH>/voucher-keys.ps1 | iex

      Leave TallyPrime open with the company loaded. Send back what it prints.
#>

param(
    [string] $Base = "http://127.0.0.1:9000",
    [string] $Company = "",
    [string] $InstallDir = "C:\ScanToTally",
    [int]    $DaysBack = 7
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Send-Tally([string] $Xml) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Xml)
    (Invoke-WebRequest -Uri $Base -Method Post -Body $bytes `
        -ContentType "text/xml; charset=utf-8" -TimeoutSec 120 -UseBasicParsing).Content
}

Write-Host ""
Write-Host "Scan to Tally - reading how this Tally names a voucher" -ForegroundColor Cyan
Write-Host "Target: $Base   (nothing is changed)" -ForegroundColor DarkGray
Write-Host ""

# --- which company ----------------------------------------------------------
#
# The connector's own config first. It is the name the working connector is
# already using, spelled exactly as Tally has it -- which is a better source
# than anything this script can work out, and it is right there on the disk.
#
# Reading it from Tally came first and failed on a machine where the company
# was plainly open: the query looked for a <NAME> element and Tally had put
# the name in the NAME ATTRIBUTE of <COMPANY>. Both are read now.
$company = $Company

if (-not $company) {
    $cfg = Join-Path $InstallDir "connector.json"
    if (Test-Path $cfg) {
        try {
            $company = (Get-Content $cfg -Raw | ConvertFrom-Json).tally.company
            if ($company) { Write-Host "Company (from the connector's config): $company" -ForegroundColor Green }
        } catch { }
    }
}

if (-not $company) {
    $companies = Send-Tally @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Collection</TYPE><ID>STT_Companies</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>
  <SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>
 </STATICVARIABLES>
 <TDL><TDLMESSAGE><COLLECTION NAME="STT_Companies" ISMODIFY="No">
  <TYPE>Company</TYPE><NATIVEMETHOD>NAME</NATIVEMETHOD>
 </COLLECTION></TDLMESSAGE></TDL></DESC></BODY>
</ENVELOPE>
"@

    # The attribute first, then the element. Tally uses one or the other
    # depending on version and how the collection was asked for.
    $company = ([regex]::Matches($companies, '<COMPANY\b[^>]*\bNAME="([^"]+)"') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -First 1)
    if (-not $company) {
        $company = ([regex]::Matches($companies, "<NAME>(.*?)</NAME>") |
                    ForEach-Object { $_.Groups[1].Value } | Select-Object -First 1)
    }
    if ($company) { Write-Host "Company (from Tally): $company" -ForegroundColor Green }
}

if (-not $company) {
    Write-Host ""
    Write-Host "Could not work out the company name." -ForegroundColor Red
    Write-Host "Pass it directly:" -ForegroundColor Yellow
    Write-Host '   & ([scriptblock]::Create((irm <this url>))) -Company "RGM16-9-2026"'
    Write-Host ""
    Write-Host "What Tally answered, so it can be read by hand:" -ForegroundColor DarkGray
    if ($companies) { Write-Host ($companies.Substring(0, [Math]::Min(600, $companies.Length))) }
    return
}

# --- the day book, which is Tally's own XML for its own vouchers ------------
$from = (Get-Date).AddDays(-$DaysBack).ToString("yyyyMMdd")
$to   = (Get-Date).ToString("yyyyMMdd")

$daybook = Send-Tally @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Data</TYPE><ID>DayBook</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>
  <SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>
  <SVCURRENTCOMPANY>$company</SVCURRENTCOMPANY>
  <SVFROMDATE>$from</SVFROMDATE><SVTODATE>$to</SVTODATE>
 </STATICVARIABLES></DESC></BODY>
</ENVELOPE>
"@

if ($daybook -match "<LINEERROR>(.*?)</LINEERROR>") {
    Write-Host "DayBook: $($Matches[1])" -ForegroundColor DarkGray
    Write-Host "Trying the Vouchers collection instead..." -ForegroundColor DarkGray

    # Not every build answers to the DayBook report id. A collection of
    # vouchers asks the same question a different way.
    $daybook = Send-Tally @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Collection</TYPE><ID>STT_Vouchers</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>
  <SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>
  <SVCURRENTCOMPANY>$company</SVCURRENTCOMPANY>
  <SVFROMDATE>$from</SVFROMDATE><SVTODATE>$to</SVTODATE>
 </STATICVARIABLES>
 <TDL><TDLMESSAGE><COLLECTION NAME="STT_Vouchers" ISMODIFY="No">
  <TYPE>Voucher</TYPE>
  <NATIVEMETHOD>MASTERID</NATIVEMETHOD>
  <NATIVEMETHOD>ALTERID</NATIVEMETHOD>
  <NATIVEMETHOD>VOUCHERNUMBER</NATIVEMETHOD>
  <NATIVEMETHOD>VOUCHERTYPENAME</NATIVEMETHOD>
  <NATIVEMETHOD>NARRATION</NATIVEMETHOD>
 </COLLECTION></TDLMESSAGE></DESC></BODY>
</ENVELOPE>
"@

    if ($daybook -match "<LINEERROR>(.*?)</LINEERROR>") {
        Write-Host "Tally said: $($Matches[1])" -ForegroundColor Yellow
        Write-Host "Neither the day book nor a voucher collection could be read." -ForegroundColor Yellow
        return
    }
}

# --- the opening tag of each voucher, verbatim ------------------------------
#
# The attributes on <VOUCHER ...> are the whole point: REMOTEID and VCHKEY live
# there, and one of them is the name an alter has to use.
$opens = [regex]::Matches($daybook, "<VOUCHER\b[^>]*>")
Write-Host ""
Write-Host "Vouchers found: $($opens.Count)   (last $DaysBack days)" -ForegroundColor Green
Write-Host ""
Write-Host "--- the <VOUCHER> tag as Tally writes it ---" -ForegroundColor Cyan
$opens | Select-Object -Last 6 | ForEach-Object { Write-Host $_.Value }

# --- and the identity fields inside ----------------------------------------
Write-Host ""
Write-Host "--- identity fields present inside a voucher ---" -ForegroundColor Cyan
foreach ($tag in @("MASTERID", "ALTERID", "VOUCHERKEY", "REMOTEID",
                   "VOUCHERNUMBER", "VOUCHERTYPENAME", "PERSISTEDVIEW")) {
    $m = [regex]::Matches($daybook, "<$tag>(.*?)</$tag>")
    if ($m.Count -gt 0) {
        $sample = ($m | Select-Object -Last 4 | ForEach-Object { $_.Groups[1].Value }) -join ", "
        Write-Host ("  {0,-16} {1,3} found   e.g. {2}" -f $tag, $m.Count, $sample)
    } else {
        Write-Host ("  {0,-16}   not present" -f $tag) -ForegroundColor DarkGray
    }
}

# --- one whole Physical Stock voucher, if there is one ----------------------
$phys = [regex]::Match($daybook,
    "<VOUCHER\b[^>]*>(?:(?!</VOUCHER>).)*?Physical Stock(?:(?!</VOUCHER>).)*?</VOUCHER>",
    [Text.RegularExpressions.RegexOptions]::Singleline)
if ($phys.Success) {
    $out = Join-Path ([Environment]::GetFolderPath("Desktop")) "physical-stock-voucher.xml"
    Set-Content -Path $out -Value $phys.Value -Encoding UTF8
    Write-Host ""
    Write-Host "A whole Physical Stock voucher was saved to:" -ForegroundColor Green
    Write-Host "  $out"
    Write-Host "Send that file back -- it is the exact shape an alter has to match."
}

Write-Host ""
Write-Host "Done. Nothing was changed." -ForegroundColor Cyan
Write-Host ""
