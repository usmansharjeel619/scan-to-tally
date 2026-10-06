package com.acme.scantotally.ui.screens

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.acme.scantotally.labels.*
import com.acme.scantotally.scan.SuspendScanCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLabelsScreen(nav: NavController) {
    SuspendScanCapture()
    val app = rememberApp()
    val scope = rememberCoroutineScope()
    val store = remember { LabelStore(File(app.filesDir, "box-labels")) }
    val prefs = remember { app.getSharedPreferences("label-printer", 0) }
    var company by remember { mutableStateOf("") }
    var part by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("1") }
    var bluetooth by rememberSaveable { mutableStateOf(prefs.getBoolean("bluetooth", false)) }
    var host by rememberSaveable { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var mac by rememberSaveable { mutableStateOf(prefs.getString("mac", "").orEmpty()) }
    var printers by remember { mutableStateOf(emptyList<PairedPrinter>()) }
    var history by remember { mutableStateOf(emptyList<LabelBatch>()) }
    var current by remember { mutableStateOf<LabelBatch?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<BoxLabel>?>(null) }

    suspend fun reload() {
        history = withContext(Dispatchers.IO) { store.recent(company) }
    }
    fun loadPaired() {
        runCatching { ZebraPrinter.paired(app) }.onSuccess { printers = it }
            .onFailure { note = it.message }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loadPaired() else note = "Nearby devices permission is required for Bluetooth printing. Wi-Fi is still available."
    }
    LaunchedEffect(Unit) {
        try {
            company = app.config.company.first()
            reload()
            ready = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            note = "Could not load saved labels: ${e.message}"
        }
    }
    LaunchedEffect(current?.id, index) {
        preview = null
        current?.labels?.getOrNull(index)?.let { label ->
            try { preview = withContext(Dispatchers.Default) { LabelRenderer.render(label) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                note = e.message
            }
        }
    }
    LaunchedEffect(part) {
        if (part.isBlank()) return@LaunchedEffect
        delay(400)
        try {
            val product = app.repository().labelProduct(part.trim())
            if (description.isBlank()) description = product?.first.orEmpty()
            unit = product?.second.orEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            note = "Could not autofill this part. You can enter its description manually."
        }
    }

    fun send(labels: List<BoxLabel>) {
        val batch = current ?: return
        val destination = if (bluetooth) mac else host.trim()
        val useBluetooth = bluetooth
        busy = true
        note = "Sending ${labels.size} label(s)…"
        scope.launch {
            var attempted = false
            var result = batch
            try {
                check(app.config.company.first() == batch.company) { "Company changed. Reopen Create labels." }
                require(destination.isNotBlank()) { "Choose your printer first." }
                val detail = "${labels.size} label(s) to $destination" +
                    if (labels.size == 1) " · ${labels.first().boxNumber}" else " · whole batch"
                result = batch.copy(lastPrintState = "SENDING", lastPrintAt = System.currentTimeMillis(), lastPrintDetail = detail)
                withContext(Dispatchers.IO) { store.save(result) }
                current = result
                attempted = true
                prefs.edit().putBoolean("bluetooth", useBluetooth).putString("host", host.trim()).putString("mac", mac).apply()
                ZebraPrinter.send(app, useBluetooth, destination, labels)
                result = result.copy(lastPrintState = "SENT")
                note = "Sent to printer. Check the physical labels before reprinting."
            } catch (e: Exception) {
                if (attempted) result = result.copy(lastPrintState = "UNCERTAIN")
                note = if (attempted) "Printing not confirmed. Check the printer before retrying. ${e.message.orEmpty()}"
                    else e.message
                if (e is CancellationException) throw e
            } finally {
                withContext(NonCancellable) {
                    if (attempted) {
                        try { withContext(Dispatchers.IO) { store.save(result) }; current = result; reload() }
                        catch (e: Exception) { note = "Could not save print status. Check the printer before reprinting." }
                    }
                    busy = false
                }
            }
        }
    }

    confirm?.let { labels ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Print ${labels.size} label(s)?") },
            text = { Text("Use 100 × 50 mm labels in a 203 dpi Zebra ZPL printer. Reprints use the same box numbers: attach each number to only one physical box. If a previous print was interrupted, check which labels already printed.") },
            confirmButton = { TextButton(onClick = { confirm = null; send(labels) }) { Text("Print") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Create labels") }, navigationIcon = {
            IconButton(onClick = { nav.popBackStack() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(company.ifBlank { "Sync with Tally first to load your company." })
            Text("For boxes without labels. Printing does not receive stock; scan the attached label through Incoming.")
            OutlinedTextField(part, {
                part = it; description = ""; unit = ""
            }, label = { Text("Part number") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    try {
                        val product = app.repository().labelProduct(part.trim())
                        description = product?.first.orEmpty(); unit = product?.second.orEmpty()
                        note = if (product == null) "No saved description for this part. Enter it below, or sync from Home and try again."
                            else "Description loaded from saved product data."
                    } catch (e: Exception) { if (e is CancellationException) throw e; note = e.message }
                    finally { busy = false }
                }
            }, enabled = !busy && part.isNotBlank()) { Text("Fill description") }
            OutlinedTextField(description, { description = it }, label = { Text("Description") },
                supportingText = { Text("${description.length}/160 characters") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity inside each box${if (unit.isBlank()) "" else " ($unit)"}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(count, { count = it }, label = { Text("Number of boxes (1–100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                enabled = !busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                busy = true
                scope.launch {
                    try {
                        check(app.config.company.first() == company) { "Company changed. Reopen Create labels." }
                        val generated = BoxLabels.generate(company, part, description, quantity.toIntOrNull() ?: 0, count.toIntOrNull() ?: 0, unit)
                        withContext(Dispatchers.IO) {
                            // Validate physical layout before saving any new identities.
                            LabelRenderer.render(generated.labels.first()).recycle()
                            store.save(generated)
                        }
                        current = generated; index = 0; reload()
                        note = "Saved ${generated.labels.size} new box label(s). Preview below, then print."
                    } catch (e: Exception) { if (e is CancellationException) throw e; note = e.message }
                    finally { busy = false }
                }
            }, enabled = ready && !busy && company.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Generate new box labels") }

            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Zebra printer · 100 × 50 mm · 203 dpi", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !bluetooth, onClick = { bluetooth = false }, label = { Text("Wi-Fi / LAN") }, enabled = !busy)
                FilterChip(selected = bluetooth, onClick = { bluetooth = true }, label = { Text("Bluetooth") }, enabled = !busy)
            }
            if (!bluetooth) {
                OutlinedTextField(host, { host = it }, label = { Text("Printer IP address") },
                    supportingText = { Text("Phone and printer must share a network. Printing uses port 9100.") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Pair the printer in Android Bluetooth Settings first. Requires Bluetooth Classic, not setup-only BLE.")
                TextButton(onClick = {
                    app.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, enabled = !busy) { Text("Open Bluetooth Settings") }
                OutlinedButton(onClick = {
                    if (ZebraPrinter.hasBluetoothPermission(app)) loadPaired()
                    else permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }, enabled = !busy) { Text("Show paired devices") }
                if (mac.isNotBlank()) Text("Selected: $mac")
                printers.forEach { printer ->
                    FilterChip(selected = mac == printer.address, onClick = { mac = printer.address },
                        label = { Text("${printer.name} · ${printer.address}") }, enabled = !busy)
                }
            }
            current?.let { batch ->
                HorizontalDivider()
                Text("Saved label ${index + 1} of ${batch.labels.size}", style = MaterialTheme.typography.titleMedium)
                preview?.let { Image(it.asImageBitmap(), "Label preview", Modifier.fillMaxWidth().aspectRatio(2f)) }
                Text("Box: ${batch.labels[index].boxNumber}", style = MaterialTheme.typography.bodySmall)
                Text("QR contains part number, box number and quantity.", style = MaterialTheme.typography.bodySmall)
                Text(when (batch.lastPrintState) {
                    "SENT" -> "Last attempt sent; check physical output. ${batch.lastPrintDetail}"
                    "SENDING", "UNCERTAIN" -> "Last print not confirmed. Check the printer before reprinting. ${batch.lastPrintDetail}"
                    else -> "Not sent to printer yet."
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { index-- }, enabled = !busy && index > 0) { Text("Previous") }
                    TextButton(onClick = { index++ }, enabled = !busy && index + 1 < batch.labels.size) { Text("Next") }
                }
                Button(onClick = { confirm = batch.labels }, enabled = !busy && preview != null && (if (bluetooth) mac else host).isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("Print all ${batch.labels.size} labels") }
                if (batch.labels.size > 1) OutlinedButton(onClick = { confirm = listOf(batch.labels[index]) },
                    enabled = !busy && preview != null && (if (bluetooth) mac else host).isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Print this label only") }
            }
            HorizontalDivider()
            Text("Saved batches (latest 100)", style = MaterialTheme.typography.titleMedium)
            Text("Open an existing batch to reprint its box numbers. Generating again creates different boxes.", style = MaterialTheme.typography.bodySmall)
            history.forEach { batch ->
                OutlinedButton(onClick = { current = batch; index = 0 }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("${batch.labels.first().partNumber} · ${batch.labels.size} boxes · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(batch.createdAt))}")
                }
            }
        }
    }
}
