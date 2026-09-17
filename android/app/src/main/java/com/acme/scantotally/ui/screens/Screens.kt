package com.acme.scantotally.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.acme.scantotally.ScanToTallyApp
import com.acme.scantotally.data.Repository
import com.acme.scantotally.data.SalesOrderEntity
import com.acme.scantotally.data.SessionEntity
import com.acme.scantotally.data.SessionSummary
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.scan.SuspendScanCapture
import com.acme.scantotally.ui.theme.AcceptGreen
import com.acme.scantotally.ui.theme.LocalSemantics
import com.acme.scantotally.ui.theme.TouchTarget
import kotlinx.coroutines.flow.Flow
import com.acme.scantotally.ui.theme.FlagAmber
import com.acme.scantotally.ui.theme.RejectRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun rememberRepo(): Repository? {
    val app = LocalContext.current.applicationContext as ScanToTallyApp
    var repo by remember { mutableStateOf<Repository?>(null) }
    LaunchedEffect(Unit) { repo = app.repository() }
    return repo
}

// --- setup ------------------------------------------------------------------

/**
 * One-time device enrolment.
 *
 * Company and godown are set here and never asked again. Making an operator
 * pick the same godown thirty times a shift is friction that eventually gets
 * one of those picks wrong.
 */
/**
 * Decodes a provisioning code.
 *
 * Format: STT1:{"u":relay,"t":token,"g":godown,"o":operator}. Prefixed and
 * versioned so a stray warehouse barcode cannot be mistaken for one, and so a
 * later format can be told apart from this one.
 */
/**
 * Reads a settings file.
 *
 * Accepts the JSON the relay serves, and also the plain-text version, so
 * whichever file someone happens to have downloaded works. Fewer ways to get
 * this wrong is worth a few extra lines.
 */
private fun parseSetupFile(text: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    // JSON form: {"relayUrl":"...","token":"...","godown":"...","operator":"..."}
    Regex("\"(relayUrl|token|godown|operator)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        .findAll(text).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].replace("\\/", "/")
        }
    if (out.isNotEmpty()) return out

    // Plain-text form: a heading line, then its value on the next line.
    val lines = text.lines().map { it.trim() }
    lines.forEachIndexed { i, line ->
        val next = lines.getOrNull(i + 1)?.trim().orEmpty()
        if (next.isEmpty() || next.startsWith("-")) return@forEachIndexed
        when {
            line.equals("Relay address", true) -> out["relayUrl"] = next
            line.equals("Device token", true) -> out["token"] = next
            line.equals("Godown", true) -> out["godown"] = next
            line.equals("Operator name", true) -> out["operator"] = next
        }
    }
    return out
}

private fun parseSetupCode(raw: String): Map<String, String>? {
    val body = raw.trim().removePrefix("STT1:").takeIf { it != raw.trim() } ?: return null
    return runCatching {
        val out = mutableMapOf<String, String>()
        Regex("\"([uUtTgGoO])\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(body).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].replace("\\/", "/")
            }
        out.takeIf { it.containsKey("u") && it.containsKey("t") }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(nav: NavController, scans: Flow<RawScan>? = null) {
    val app = LocalContext.current.applicationContext as ScanToTallyApp
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var godown by remember { mutableStateOf("Main Store") }
    var operator by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SuspendScanCapture()
    var error by remember { mutableStateOf<String?>(null) }

    var scanned by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    // Reading the downloaded settings file is the least effort path: it is
    // already on the device, and nobody has to retype a 32-character token.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        }.onSuccess { text ->
            val cfg = parseSetupFile(text)
            if (cfg["relayUrl"].isNullOrBlank() || cfg["token"].isNullOrBlank()) {
                loadError = "That file does not look like a settings file."
            } else {
                url = cfg["relayUrl"] ?: url
                token = cfg["token"] ?: token
                godown = cfg["godown"]?.takeIf { it.isNotBlank() } ?: godown
                operator = cfg["operator"]?.takeIf { it.isNotBlank() } ?: operator
                scanned = true
                loadError = null
            }
        }.onFailure { loadError = "Could not read that file." }
    }

    LaunchedEffect(Unit) {
        url = app.config.relayUrl.first()
        godown = app.config.godown.first()
        operator = app.config.operator.first()
    }

    // A whole handset configured by pulling the trigger once, rather than
    // typing a 32-character token off another screen.
    LaunchedEffect(scans) {
        scans?.collect { s ->
            parseSetupCode(s.data)?.let { cfg ->
                url = cfg["u"] ?: url
                token = cfg["t"] ?: token
                godown = cfg["g"] ?: godown
                operator = cfg["o"] ?: operator
                scanned = true
                app.feedback.play(com.acme.scantotally.feedback.Beep.ACCEPT)
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Set up this device") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "These are set once, when the handset is issued. The operator is never asked again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (scanned) LocalSemantics.current.accept.bg
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (scanned) "Settings loaded" else "Fill these in automatically",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (scanned) AcceptGreen else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (scanned) "Check them below, then save."
                        else "Pick the settings file you downloaded, or scan the setup code.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            loadError = null
                            picker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Choose settings file") }
                    loadError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = RejectRed, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Relay address") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Device token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = godown, onValueChange = { godown = it },
                label = { Text("Godown") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = operator, onValueChange = { operator = it },
                label = { Text("Operator name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(it, color = RejectRed, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        app.config.save(url, token, godown, operator)
                        app.invalidateRepository()
                        val ok = runCatching { app.repository().syncMasters() }.getOrDefault(false)
                        busy = false
                        if (ok) {
                            nav.navigate("home") { popUpTo("setup") { inclusive = true } }
                        } else {
                            error = "Could not reach the relay, or the token was rejected. " +
                                "The settings are saved; you can continue offline and sync later."
                        }
                    }
                },
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (busy) "Connecting…" else "Save and sync") }

            OutlinedButton(
                onClick = { nav.navigate("home") { popUpTo("setup") { inclusive = true } } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Skip for now") }
        }
    }
}

// --- home -------------------------------------------------------------------

@Composable
private fun BigAction(
    title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(40.dp))
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as ScanToTallyApp
    val repo = rememberRepo()
    val scope = rememberCoroutineScope()

    var health by remember { mutableStateOf("UNKNOWN") }
    val pending by (repo?.pendingCountFlow()?.collectAsState(0) ?: remember { mutableStateOf(0) })
    val failed by (repo?.failedCountFlow()?.collectAsState(0) ?: remember { mutableStateOf(0) })
    var godown by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { godown = app.config.godown.first() }

    // Poll the relay for connector health.
    //
    // This was declared and never wired up, so the banner sat on "Checking
    // Tally..." forever -- the one thing on screen whose whole job is to be
    // honest about the connection.
    LaunchedEffect(repo) {
        while (repo != null) {
            val r = runCatching { app.repository() }.getOrNull()
            if (r != null) {
                health = r.tallyHealth() ?: "OFFLINE"
                r.refreshPending()
            }
            delay(10_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan to Tally") },
                actions = {
                    Text(
                        godown,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ConnectionBanner(health, pending, failed)

            Column(
                // Scrollable: five actions plus the footer do not fit a rugged
                // handset's screen, and the fifth was simply unreachable.
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigAction("Incoming", "Scan boxes off a delivery", Icons.Default.MoveToInbox) {
                    nav.navigate("incoming")
                }
                BigAction("Outgoing", "Pick against a sales order", Icons.Default.LocalShipping) {
                    nav.navigate("orders")
                }
                BigAction("Check stock", "Scan a box, see what Tally has", Icons.Default.Search) {
                    nav.navigate("lookup")
                }
                BigAction("Stock take", "Count the shelf and correct Tally", Icons.Default.Inventory) {
                    nav.navigate("stockcheck")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { nav.navigate("receipts") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = if (failed > 0) {
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = LocalSemantics.current.reject.fg,
                        )
                    } else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(
                        when {
                            failed > 0 -> "Receipts ($failed did not save)"
                            pending > 0 -> "Receipts ($pending sending)"
                            else -> "Receipts"
                        },
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch { app.repository().syncMasters() }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Sync now") }

                // Proof the trigger is reaching the app at all. Pull it here
                // and the count moves even if nothing else does.
                Spacer(Modifier.height(4.dp))
                Text(
                    if (com.acme.scantotally.MainActivity.rawSeen == 0)
                        "Scanner: nothing received yet — pull the trigger to test"
                    else
                        "Scanner: ${com.acme.scantotally.MainActivity.rawSeen} read · " +
                            com.acme.scantotally.MainActivity.lastRaw.take(40),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Which build is actually on this handset. Settles the question
                // in one glance instead of a round trip.
                Text(
                    "Build ${com.acme.scantotally.BuildConfig.BUILD_STAMP}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// --- sales order picker -----------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesOrderPickerScreen(nav: NavController) {
    val repo = rememberRepo()
    val orders by (repo?.ordersFlow()?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SalesOrderEntity>()) })
    var filter by remember { mutableStateOf("") }
    SuspendScanCapture()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a sales order") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                label = { Text("Filter by customer or order number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            val shown = orders.filter {
                filter.isBlank() ||
                    it.partyName.contains(filter, true) ||
                    it.voucherNumber.contains(filter, true)
            }

            if (shown.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (orders.isEmpty()) "No open sales orders have synced yet."
                        else "Nothing matches that filter.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                items(shown) { order ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                            .clickable { nav.navigate("outgoing/${order.voucherNumber}") },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(order.partyName, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${order.voucherNumber}  ·  ${order.orderDate}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- receipts ---------------------------------------------------------------

/**
 * When it was scanned, in the terms the question is asked in.
 *
 * "This morning's delivery" is how an operator thinks about it, so today shows
 * a time and anything older shows a date.
 */
fun whenScanned(millis: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) "HH:mm" else "d MMM HH:mm"
    return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(millis))
}

/**
 * Every receipt this device has made.
 *
 * Saved ones included, with the Tally voucher number against them -- that is
 * the thing an operator actually comes here to check, and a list that only
 * ever shows problems cannot answer it. A receipt that failed to save never
 * vanishes either: stock that quietly goes wrong is the worst outcome there is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptsScreen(nav: NavController) {
    val repo = rememberRepo()
    val sessions by (repo?.recentSessionsFlow()?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionEntity>()) })
    val summaries by (repo?.lineSummariesFlow()?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionSummary>()) })
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(repo) {
        while (repo != null) {
            repo.refreshPending()
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receipts") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (sessions.any { it.state != "POSTED" }) {
                        TextButton(onClick = { confirmClearAll = true }) { Text("Clear") }
                    }
                },
            )
        },
    ) { pad ->
        if (sessions.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing scanned yet.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Receipts appear here as soon as you scan the first box.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(pad).padding(16.dp)) {
            note?.let { n ->
                item {
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LocalSemantics.current.accept.bg,
                        ),
                    ) {
                        Text(
                            n,
                            Modifier.padding(14.dp),
                            color = LocalSemantics.current.onCard,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            items(sessions) { s ->
                val sem = LocalSemantics.current
                val colour = when (s.state) {
                    "POSTED" -> sem.accept.fg
                    "FAILED" -> sem.reject.fg
                    else -> sem.review.fg
                }
                val summary = summaries.firstOrNull { it.sessionId == s.id }
                Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                when (s.kind) {
                                    "INCOMING" -> "Incoming"
                                    "OUTGOING" -> "Outgoing ${s.salesOrder}"
                                    else -> "Stock take"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                // "DRAFT" and "POSTED" are warehouse-meaningless.
                                // Say what actually happened to it.
                                when (s.state) {
                                    "DRAFT" -> "NOT SAVED"
                                    "QUEUED", "POSTING" -> "SENDING"
                                    "POSTED" -> "SAVED"
                                    "FAILED" -> "DID NOT SAVE"
                                    else -> s.state
                                },
                                color = colour,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        // What is actually in it. A queue entry that does not
                        // say how many boxes it holds tells the operator
                        // nothing they can act on.
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (if (summary == null || summary.boxes == 0) "No boxes scanned"
                            else "${summary.boxes} ${if (summary.boxes == 1) "box" else "boxes"}" +
                                "  ·  ${fmtQty(summary.totalQty)} total") +
                                "  ·  ${whenScanned(s.createdAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (s.errorMessage.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            // Tally's own words, verbatim. Paraphrasing an
                            // accounting error helps nobody diagnose it.
                            Text(
                                s.errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = sem.reject.fg,
                            )
                        }
                        if (s.tallyVoucherId.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tally voucher ${s.tallyVoucherId}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // An unsaved receipt must be reachable from here. It was
                        // not, and a session you cannot open or save is just a
                        // line of text telling you something is wrong.
                        if (s.state == "DRAFT") {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        nav.navigate(
                                            when (s.kind) {
                                                "INCOMING" -> "incoming?session=${s.id}"
                                                "OUTGOING" -> "outgoing/${s.salesOrder}?session=${s.id}"
                                                else -> "stockcheck?session=${s.id}"
                                            },
                                        )
                                    },
                                    modifier = Modifier.weight(1f).height(TouchTarget),
                                ) { Text("Open") }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            busy = s.id
                                            val r = repo?.submit(s.id)
                                            busy = null
                                            note = when {
                                                r == null ->
                                                    "Saved on the phone. It will reach Tally when there is signal."
                                                r.message.isNotEmpty() -> r.message
                                                r.ok -> "Sent to Tally."
                                                else -> "Tally would not accept this."
                                            }
                                        }
                                    },
                                    enabled = busy == null && (summary?.boxes ?: 0) > 0,
                                    modifier = Modifier.weight(1f).height(TouchTarget),
                                ) { Text(if (busy == s.id) "Saving…" else "Save to Tally") }
                            }
                        }

                        if (s.state != "POSTED") {
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = { confirmDiscard = s.id },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text("Discard", color = sem.reject.fg)
                            }
                        }

                        if (s.state == "FAILED") {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        busy = s.id
                                        val ok = repo?.retry(s.id) == true
                                        busy = null
                                        note = if (ok) "Sent to Tally again."
                                        else "Could not reach Tally. It will go again by itself."
                                    }
                                },
                                enabled = busy == null,
                                modifier = Modifier.fillMaxWidth().height(TouchTarget),
                            ) { Text(if (busy == s.id) "Retrying…" else "Try again") }
                        }
                    }
                }
            }
        }
    }

    // Discarding throws away real scans, so it is always asked -- but only ever
    // offered for a receipt Tally never saw.
    confirmDiscard?.let { id ->
        val boxes = summaries.firstOrNull { it.sessionId == id }?.boxes ?: 0
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text("Discard this receipt?") },
            text = {
                Text(
                    if (boxes == 0) "Nothing was scanned on it."
                    else "$boxes scanned ${if (boxes == 1) "box" else "boxes"} will be thrown " +
                        "away. Tally never received this, so nothing there changes.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        repo?.discard(id)
                        confirmDiscard = null
                        note = "Receipt discarded."
                    }
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text("Keep") }
            },
        )
    }

    if (confirmClearAll) {
        val unsaved = sessions.count { it.state != "POSTED" }
        val boxes = sessions.filter { it.state != "POSTED" }
            .sumOf { s -> summaries.firstOrNull { it.sessionId == s.id }?.boxes ?: 0 }
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear everything not saved?") },
            text = {
                Text(
                    "$unsaved ${if (unsaved == 1) "receipt" else "receipts"} and $boxes scanned " +
                        "${if (boxes == 1) "box" else "boxes"} will be thrown away. Receipts " +
                        "already saved in Tally are kept.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val n = repo?.discardAllUnsaved() ?: 0
                        confirmClearAll = false
                        note = "Cleared $n ${if (n == 1) "receipt" else "receipts"}."
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Keep") }
            },
        )
    }
}
