package com.acme.scantotally.ui.screens

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
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
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
import com.acme.scantotally.ui.theme.AcceptGreen
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as ScanToTallyApp
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var godown by remember { mutableStateOf("Main Store") }
    var operator by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        url = app.config.relayUrl.first()
        godown = app.config.godown.first()
        operator = app.config.operator.first()
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

    // Poll the relay for connector health. Failure just leaves it UNKNOWN,
    // which the banner shows honestly rather than pretending all is well.
    LaunchedEffect(repo) {
        while (repo != null) {
            runCatching {
                val api = app.repository()
                api.refreshPending()
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
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BigAction("Incoming", "Scan boxes off a delivery", Icons.Default.MoveToInbox) {
                    nav.navigate("incoming")
                }
                BigAction("Outgoing", "Pick against a sales order", Icons.Default.LocalShipping) {
                    nav.navigate("orders")
                }
                BigAction("Inventory check", "Count what is on the shelf", Icons.Default.Inventory) {
                    nav.navigate("stockcheck")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { nav.navigate("queue") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (pending > 0) "Queue ($pending waiting)" else "Queue")
                }
                OutlinedButton(
                    onClick = { nav.navigate("supervisor") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    colors = if (failed > 0) {
                        ButtonDefaults.outlinedButtonColors(contentColor = RejectRed)
                    } else ButtonDefaults.outlinedButtonColors(),
                ) {
                    Text(if (failed > 0) "Supervisor ($failed need review)" else "Supervisor")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch { app.repository().syncMasters() }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Sync now") }
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

// --- queue ------------------------------------------------------------------

/**
 * Everything not yet in Tally.
 *
 * Mandatory, not optional. Operators must be able to see "3 receipts waiting to
 * sync" -- a queue that works silently is a queue nobody trusts, and a failed
 * post that vanishes is stock that quietly goes wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(nav: NavController) {
    val repo = rememberRepo()
    val sessions by (repo?.openSessionsFlow()?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionEntity>()) })
    val scope = rememberCoroutineScope()

    LaunchedEffect(repo) {
        while (repo != null) {
            repo.refreshPending()
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                Text("Nothing waiting.", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Everything scanned has reached Tally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(pad).padding(16.dp)) {
            items(sessions) { s ->
                val colour = when (s.state) {
                    "POSTED" -> AcceptGreen
                    "FAILED" -> RejectRed
                    else -> FlagAmber
                }
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
                                    else -> "Stock check"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(s.state, color = colour, style = MaterialTheme.typography.labelLarge)
                        }
                        if (s.errorMessage.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            // Tally's own words, verbatim. Paraphrasing an
                            // accounting error helps nobody diagnose it.
                            Text(
                                s.errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RejectRed,
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
                    }
                }
            }
        }
    }
}
