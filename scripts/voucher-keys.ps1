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

# --- which company is open --------------------------------------------------
$companies = Send-Tally @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Collection</TYPE><ID>List of Companies</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>
  <SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>
 </STATICVARIABLES>
 <TDL><TDLMESSAGE><COLLECTION NAME="List of Companies" ISMODIFY="No">
  <TYPE>Company</TYPE><NATIVEMETHOD>Name</NATIVEMETHOD>
 </COLLECTION></TDLMESSAGE></TDL></DESC></BODY>
</ENVELOPE>
"@

$company = ([regex]::Matches($companies, "<NAME>(.*?)</NAME>") |
            ForEach-Object { $_.Groups[1].Value } | Select-Object -First 1)
if (-not $company) {
    Write-Host "Could not read the open company. Is TallyPrime running with a company loaded?" -ForegroundColor Red
    return
}
Write-Host "Company: $company" -ForegroundColor Green

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
    Write-Host "Tally said: $($Matches[1])" -ForegroundColor Yellow
    return
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
