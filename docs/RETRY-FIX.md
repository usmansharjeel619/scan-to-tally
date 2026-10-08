# Connector 1.15.1: receipts stuck in Sending

Two defects combined: retained-box validation compared godown names case
sensitively, and an explicit retry only changed relay state. The connector's
durable queue ignored the already-failed voucher identity and never ran again.
Those stuck POSTING sessions also prevented history reconciliation.

Godown comparisons in the retained-box guard and deletion reconciliation now
ignore casing and surrounding whitespace. Box identities remain exact.

An explicit Retry increments a relay attempt counter and supplies a distinct
delivery ID. The voucher/session identity is unchanged. The connector can
replace a failed job's payload and restart it for a new retry delivery. Replaying
the same delivery does not restart it again, and queued/running/completed jobs
cannot be overwritten. Terminal results are returned on redelivery, covering
results lost during disconnects.

The relay rejects retries on connectors older than 1.15.1, instead of claiming
work is in flight. Receipt merges for the same product and godown are serialized:
the next payload is built after the previous result updates the standing boxes.

Deployment requires the relay update and connector 1.15.1 on the Tally PC.
Existing failed/stuck scans should be preserved; no phone data reset or rescan.
The existing Android Retry action works once the connector is updated.

Validation includes durable retry/replay/completed-job protection, godown-case
reconciliation, and full simulator tests for refused import → Retry → POSTED,
unchanged voucher count, concurrent receipts retaining both boxes, and automatic
Tally deletion reconciliation. Physical warehouse posting still requires the
updated connector to be installed on the Tally PC.
