# Getting access to Tally

Ranked by what actually unblocks the build fastest, with the least exposure.

## First, the one hard rule

**Tally's XML gateway on port 9000 has no authentication of any kind.** Anyone
who can reach that port can read the entire company — every ledger, every party,
every price — and post vouchers into it. There is no password, no token, no
audit of who asked.

So: **never port-forward 9000, never expose it to the internet, never put it on
a public IP.** That is also precisely why the production design has the
connector dial *outward* over WSS instead of anything dialling in.

---

## Option A — Run a script, send back the output (recommended, do this now)

**No remote access, no network changes, nothing installed.** You run one
PowerShell script on the Tally machine; it asks Tally for its schema and saves
the replies to a zip on your Desktop.

    scripts/tally-probe.ps1

Everything it sends is an *export*. Nothing is created, altered or deleted.

### What to do first

The most valuable part is not the script, it's two vouchers you key by hand
before running it. Exporting a voucher **Tally itself created** is the only
reliable way to learn the exact field names — everything the connector emits
today is a template written from documentation, and templates are where
integrations like this go wrong.

In a **test company** (File > New Company, or restore a backup of the real one):

1. A **Receipt Note** with **one stock item** carrying **three different
   batches** (box numbers), different quantities on each, and a godown set on
   each batch. Add a reference and a narration.
2. A **Sales Order** for one item, then a **Delivery Note raised against it**,
   delivering only *part* of the ordered quantity from a named batch.

Then run the script and send the zip.

### What it answers

| Question | Where the answer lands |
|---|---|
| Exact XML for a multi-batch Receipt Note | `STT_DayBook.xml` |
| How a Delivery Note links to a Sales Order (`ORDERNO` / `TRACKINGNUMBER`) | `STT_DayBook.xml` |
| Whether per-line description is `BASICUSERDESCRIPTION` | `STT_DayBook.xml` |
| Which batch-balance query works | `STT_BatchBalances_A/B/C.xml` |
| How your stock items are named, and whether batch-wise is on | `STT_StockItems.xml` |
| Godown names, voucher type names | `STT_Godowns.xml`, `STT_VoucherTypes.xml` |
| Tally version and edition | `00-environment.json` |

**Check the files before sending.** They contain real item names, party names
and quantities from whichever company was open.

---

## Option B — LAN access, for iterating

Once the templates are right, being able to hit real Tally repeatedly makes
everything faster. Only worth doing if the Tally machine is on the same trusted
LAN as the dev box.

On the Tally machine:

1. TallyPrime > `F1` Help > Settings > Connectivity > Client/Server configuration
   - `TallyPrime acts as` = **Both**
   - `Port` = **9000**
2. Allow the port on the local network **only**:

       New-NetFirewallRule -DisplayName "Tally XML (LAN only)" `
         -Direction Inbound -LocalPort 9000 -Protocol TCP `
         -Action Allow -Profile Private -RemoteAddress LocalSubnet

3. Find the machine's address: `ipconfig`

From the dev box, confirm and point the tests at it:

    curl -m 5 http://<tally-ip>:9000
    STT_TALLY_URL=http://<tally-ip>:9000 go test ./internal/tally/ -run Integration -v

**Remove the rule when finished:**

    Remove-NetFirewallRule -DisplayName "Tally XML (LAN only)"

Use a **test company** for this. The integration tests post real vouchers.

---

## Option C — Remote desktop

For the parts that need Tally's UI — enabling the gateway, keying the two test
vouchers, checking a report — a screen-sharing session works: RustDesk (open
source), AnyDesk or TeamViewer. You drive, guided step by step.

Slower than Option A, but useful the first time if the Tally settings screens
are unfamiliar.

---

## Option D — Our own Tally instance

TallyPrime in **Educational mode** is free and the XML gateway works fully.
Its one restriction is that vouchers may only be dated the **1st, 2nd or last
day of a month** — irrelevant for schema validation, which is all we need it
for.

Two catches:

- Tally is Windows-only, so it needs a Windows VM.
- **Not on the current dev box.** It has 14 GB total with ~4 GB free and swap
  already three-quarters used; a Windows VM would thrash it. Put it on the LAN
  machine instead.

Best combined with a **backup of the real company** restored into it — that
gives real item names and real sales orders to develop against, with no risk to
the live book.

---

## Recommended path

1. **Option A today.** It settles the schema questions and needs nothing but a
   script run. This is the true blocker.
2. **Option B next**, if the Tally machine shares a LAN with the dev box.
3. **Option D** on the LAN machine if you want a permanent test instance.

The simulator (`tools/tallysim`) covers everything else in the meantime: it
holds real batch balances, applies vouchers to them, and injects the failure
modes that matter — company closed, Tally busy, negative stock, unknown item.
The whole stack is buildable and testable without touching Tally. What the
simulator *cannot* tell us is whether our XML matches your Tally, because it was
written from the same assumptions. Only Option A settles that.
