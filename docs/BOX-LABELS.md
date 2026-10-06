# Box labels — Android 1.15.0

Home → **Create labels / Reprint** creates labels for previously unlabelled
boxes. Enter a part number; its description is filled from the phone's synced
Tally bindings/item master or catalogue. Unknown descriptions can be entered
manually. Enter the quantity **inside each box** and number of boxes (1–100).
For different quantities, create separate batches.

Generate saves the batch before printing is offered. Each box receives an
independent UUID-derived ID (ST plus 26 base-32 characters). The full QR payload
is `part|box|quantity|`, accepted by the existing Android, relay and connector
parsers. The ST prefix prevents inference of a fictitious manufacturing date.
Incoming's Read label camera decodes complete barcodes before falling back to
OCR. It offers all three fields from one barcode together for confirmation;
multiple different complete barcodes in the same frame clear the offered fields.
Quantities remain positive integers, at most 10,000, like existing carton scans.

Printing creates no receipt, item or stock movement. Attach the label, then
scan it through Incoming. Unknown products still follow the existing Incoming
new-product confirmation. A reprint preserves its original identity and quantity;
never put copies of the same box label on different physical boxes.

## Printer setup

- Zebra ZD421t or compatible ZPL printer, **203 dpi**.
- **100 mm wide × 50 mm high**, one label per box; compatible ribbon for
  thermal-transfer stock. Load/calibrate media using the printer's controls.
- Wi-Fi/LAN: printer and phone on a reachable local network; enter the
  printer IP/hostname. Direct TCP port 9100; relay and Windows are not involved.
- Bluetooth: pair in Android Settings, allow Nearby devices when asked,
  Show paired devices, select the printer. Requires **Bluetooth Classic SPP**;
  setup-only BLE is not supported. No Bluetooth discovery/location permission.
- Check preview, then Print all or Print this label only. The confirmation
  reminds the operator that reprints represent the same physical boxes.

The app renders an 800 × 400 pixel image. Text and QR in preview and ZPL raster
output are identical. Text is rasterized, so description characters cannot
inject ZPL commands. Other label sizes/resolutions, USB, portable printers and
non-ZPL printers are not supported in this version.

## Persistence and uncertain printing

Label batches are atomically saved to private `files/box-labels/*.json`, scoped
to company. This is separate from the existing Room database; no schema change
or stock/draft deletion is required. The latest 100 batches for the current
company are displayed. Older files are retained. Uninstalling/clearing Android
app data removes label history; labels already attached remain scannable.

A batch records its last print attempt, time, destination, scope and state.
SENDING is persisted before connecting; SENT means bytes were sent, **not**
that a physical label printed. Interrupted/failed attempts are uncertain;
SENDING remaining after process death is also displayed as unconfirmed.
There is no automatic retry or background print outbox. Check the physical
output and reprint only missing/damaged labels. A socket watchdog bounds
connect/write hangs to two minutes. Only one send runs at a time in the app.

## Validation

- Shared barcode fixture verifies compatibility across Kotlin/TypeScript/Go.
- QR encode/decode and parser round trips, maximum field lengths/quantities,
  unique identities across batches, invalid inputs, durable company-scoped
  persistence and unchanged reprint identities.
- Android native bitmap preview and reconstructed ZPL raster decoded back to
  the saved payload; command-looking text remains image data.
- Loopback TCP test checks actual transmitted bytes; no stock is posted.
- **Physical Bluetooth pairing, printer media calibration, adhesive durability
  and print-and-rescan on the purchased printer still require a hardware test.**

References: [Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions),
[Android RFCOMM connections](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices),
[Zebra ZPL commands](https://docs.zebra.com/us/en/printers/software/zpl-pg/c-zpl-zpl-commands.html).
