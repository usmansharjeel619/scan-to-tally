# Voucher merge fix — 24 September 2026

Connector 1.13.0 uses Tally's documented alteration selector:
`TAGNAME="MASTER ID" TAGVALUE="<verified master ID>" DATE="<original date>"`.
Source: https://help.tallysolutions.com/scenario-2/

The earlier approach using Tally's exported REMOTEID failed against the real
installation: it created voucher 73 instead of altering 72. Both test vouchers
remain in Tally at the user's request. Do not delete test vouchers.

The connector still reads the day book first and requires the recorded ownership
marker and Physical Stock type. Missing/invalid selectors cannot be imported.
It keeps the original voucher date and narration marker, and sends every batch
that the standing voucher should retain. It rejects a create response to an alter.

The simulator now requires the documented selector instead of treating REMOTEID
as an alteration key. All Go suites and scripts/e2e.sh passed on 24 September,
including merging successive receipts without increasing voucher count,
retaining prior boxes, and recovery after company closure.

The Windows binary is dist/connector-1.13.0.exe, published and confirmed running
on the laptop on 24 September. Real-Tally verification passed: three successive
receipts and a repeat submit used voucher 74. DayBook readback confirmed exactly
one additional voucher (2 -> 3), with test boxes A, B and C each holding 1 Nos.
Test batch prefix: STT-MID-1790234732907. The initial readback assertion missed
Tally's whitespace in `<MASTERID> 74</MASTERID>`; a whitespace-tolerant readback
verified the contents without creating further vouchers.

Merging is now enabled on the relay. Existing test vouchers, including 72, 73
and 74, remain in Tally at the user's request. Temporary probe device/session
records were removed and the product's original relay mapping restored. No APK
update was needed.
