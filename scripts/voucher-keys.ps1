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
    [int]    $DaysBack = 30
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
$sv = @"
  <SVEXPORTFORMAT>`$`$SysName:XML</SVEXPORTFORMAT>
  <SVCURRENTCOMPANY>$company</SVCURRENTCOMPANY>
  <SVFROMDATE>$from</SVFROMDATE><SVTODATE>$to</SVTODATE>
"@

function Try-Report([string] $Label, [string] $Xml) {
    Write-Host ("  {0,-28}" -f $Label) -NoNewline
    try { $r = Send-Tally $Xml } catch {
        Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red; return ""
    }
    if ($r -match "<LINEERROR>(.*?)</LINEERROR>") {
        Write-Host "Tally: $($Matches[1])" -ForegroundColor DarkGray; return ""
    }
    $n = ([regex]::Matches($r, "<VOUCHER\b[^>]*>")).Count
    if ($n -gt 0) { Write-Host "$n voucher(s)" -ForegroundColor Green }
    else { Write-Host ("no vouchers ({0:N0} bytes back)" -f $r.Length) -ForegroundColor DarkGray }
    if ($n -gt 0) { return $r }
    # Kept anyway: if nothing works, the last answer is the evidence.
    $script:lastEmpty = $r
    return ""
}

Write-Host ""
Write-Host "Asking for vouchers dated $from to $to" -ForegroundColor Cyan

$lastEmpty = ""
$daybook = ""

# Three ways of asking the same question. Builds differ on which report id
# they answer to, and asking one way and giving up is how this came back
# empty from a company that had vouchers in it.
foreach ($attempt in @(
    @{ Label = "DayBook report";  Xml = @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Data</TYPE><ID>DayBook</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>$sv</STATICVARIABLES></DESC></BODY>
</ENVELOPE>
"@ },
    @{ Label = "Day Book report";  Xml = @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Data</TYPE><ID>Day Book</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>$sv</STATICVARIABLES>
  <TDL><TDLMESSAGE><REPORT NAME="Day Book" ISMODIFY="No">
   <SET>Explodeflag : Yes</SET></REPORT></TDLMESSAGE></TDL></DESC></BODY>
</ENVELOPE>
"@ },
    @{ Label = "Voucher collection"; Xml = @"
<ENVELOPE>
 <HEADER><VERSION>1</VERSION><TALLYREQUEST>Export</TALLYREQUEST>
  <TYPE>Collection</TYPE><ID>STT_Vouchers</ID></HEADER>
 <BODY><DESC><STATICVARIABLES>$sv</STATICVARIABLES>
  <TDL><TDLMESSAGE><COLLECTION NAME="STT_Vouchers" ISMODIFY="No">
   <TYPE>Voucher</TYPE>
   <NATIVEMETHOD>MASTERID</NATIVEMETHOD>
   <NATIVEMETHOD>ALTERID</NATIVEMETHOD>
   <NATIVEMETHOD>VOUCHERNUMBER</NATIVEMETHOD>
   <NATIVEMETHOD>VOUCHERTYPENAME</NATIVEMETHOD>
   <NATIVEMETHOD>DATE</NATIVEMETHOD>
   <NATIVEMETHOD>NARRATION</NATIVEMETHOD>
  </COLLECTION></TDLMESSAGE></TDL></DESC></BODY>
</ENVELOPE>
"@ }
)) {
    $daybook = Try-Report $attempt.Label $attempt.Xml
    if ($daybook) { break }
}

if (-not $daybook) {
    # THE ANSWER IS NOT THROWN AWAY. Coming back "0 vouchers" and discarding
    # what Tally said leaves nothing to work out why -- an empty day book and
    # an export in a shape this did not recognise look identical from here.
    $raw = Join-Path ([Environment]::GetFolderPath("Desktop")) "tally-answer.xml"
    Set-Content -Path $raw -Value $lastEmpty -Encoding UTF8
    Write-Host ""
    Write-Host "No vouchers came back from any of the three." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "If the day book in Tally really is empty for the last $DaysBack days," -ForegroundColor Yellow
    Write-Host "post ONE receipt from the app and run this again -- there has to be a" -ForegroundColor Yellow
    Write-Host "voucher to read before we can see how Tally names one." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Tally's actual answer was saved to:" -ForegroundColor Cyan
    Write-Host "  $raw"
    Write-Host ""
    Write-Host "--- the first part of it ---" -ForegroundColor DarkGray
    if ($lastEmpty) { Write-Host $lastEmpty.Substring(0, [Math]::Min(900, $lastEmpty.Length)) }
    else { Write-Host "(nothing at all came back)" }
    Write-Host ""
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
