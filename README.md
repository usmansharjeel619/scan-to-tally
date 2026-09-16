# Scan to Tally

Android barcode scanning -> Tally Prime inventory vouchers.

| Component | Path | Stack |
|---|---|---|
| Domain contracts & shared test vectors | `contracts/` | JSON |
| On-prem connector (Windows service) | `connector/` | Go 1.23 |
| Relay server | `relay/` | Node 22 / TypeScript / Postgres |
| Android app | `android/` | Kotlin / Compose |
| Tally simulator (dev only) | `tools/tallysim/` | Go 1.23 |

Spec: see `docs/` and the build spec artifact.

## Toolchain
    export PATH=/mnt/4TB_Storage/toolchains/go/bin:$PATH
    export ANDROID_HOME=/mnt/4TB_Storage/android-sdk
