# Tally deletion reconciliation (1.14.0)

Tally is the source for posted inventory history. A deleted voucher, or a box
removed from an existing inventory voucher, is removed from the relay's scan
history and duplicate index after two successful exports confirm its absence.
Unrelated boxes, drafts, unsent receipts, audit records and device pairing stay.
An existing voucher with no exported batch detail is not evidence of deletion.

The connector exports voucher identities and inventory batches for 1900–2099
on its normal sync interval (two minutes by default). It validates the XML,
success status, collection structure and configured company before sending a
snapshot. Errors retain the previous history and appear as syncError. The relay
rejects malformed, replayed, stale and wrong-company snapshots, skips recent
postings, and postpones reconciliation while a company has work in flight.
At least 30 seconds must separate the two confirming snapshots.

The relay persists removal records per device/session/item/box. APK 1.14.0
applies them transactionally to synced lines of posted receipts, removing a
receipt only when it has no lines left. It refreshes every 30 seconds while the
app process is running; offline phones catch up on a successful later sync.
Expected delay with the default connector settings is roughly 2–5 minutes,
plus time spent offline or waiting for pending jobs to finish.

Zero available stock is not treated as deletion: a sold-out carton still has
history. Before merging another receipt, the connector also checks that all
previously posted boxes remain in the Tally voucher. If somebody changed it
before reconciliation completed, the new receipt fails visibly and can be
retried after history sync; deleted stock is not automatically recreated.

Both connector and APK must be updated. Older connectors send no history
snapshot and cannot trigger removals. Older APKs receive the corrected received
box cache but do not apply the precise receipt-line removal records.

Validation: Go suites, relay tests (whole/partial deletion, replay, bad exports,
wrong company, pending/new postings and reappearance), Android build/unit tests,
and the full connector/relay/simulator workflow including deletion and mobile
sync removals. A read-only live Tally query confirmed the successful empty
collection response shape. Deletion propagation on the live installation still
needs verification after installing both 1.14.0 updates.
