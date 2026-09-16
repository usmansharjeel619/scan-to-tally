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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.acme.scantotally.ScanToTallyApp
import com.acme.scantotally.data.ApiFailedSession
import com.acme.scantotally.data.ApiItem
import com.acme.scantotally.data.ApiUnresolvedPid
import com.acme.scantotally.data.BindRequest
import com.acme.scantotally.data.RelayApi
import com.acme.scantotally.data.ReviewResponse
import com.acme.scantotally.ui.theme.AcceptGreen
import com.acme.scantotally.ui.theme.FlagAmber
import com.acme.scantotally.ui.theme.FlagAmberBg
import com.acme.scantotally.ui.theme.RejectRed
import com.acme.scantotally.ui.theme.RejectRedBg
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Everything the dock deferred.
 *
 * This is the other half of "never block the operator": the work does not
 * disappear, it lands here. Two queues -- products waiting to be mapped, and
 * postings Tally refused -- with Tally's own words shown verbatim, because
 * paraphrasing an accounting error helps nobody diagnose it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as ScanToTallyApp
    val scope = rememberCoroutineScope()

    var api by remember { mutableStateOf<RelayApi?>(null) }
    var review by remember { mutableStateOf<ReviewResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var binding by remember { mutableStateOf<ApiUnresolvedPid?>(null) }

    suspend fun refresh() {
        loading = true
        error = null
        runCatching {
            val a = api ?: RelayApi(app.config.relayUrl.first(), app.config.token.first()).also { api = it }
            review = a.review()
        }.onFailure {
            error = "Could not reach the relay. Supervisor work needs a connection."
        }
        loading = false
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Supervisor") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {

            error?.let {
                Card(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = RejectRedBg),
                ) { Text(it, Modifier.padding(14.dp), color = RejectRed) }
            }

            if (loading && review == null) {
                Text("Loading…", Modifier.padding(top = 24.dp))
                return@Column
            }

            val r = review ?: return@Column

            LazyColumn {
                if (r.unresolvedPids.isNotEmpty()) {
                    item {
                        SectionLabel("Products to map (${r.unresolvedPids.size})", FlagAmber)
                        Text(
                            "These were scanned and counted. The product is usually already in Tally " +
                                "-- it is the barcode mapping that is missing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    items(r.unresolvedPids) { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { binding = p },
                            colors = CardDefaults.cardColors(containerColor = FlagAmberBg),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    p.pid,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${p.lines} line(s) · ${fmtQty(p.qty)} total · tap to map",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (r.failed.isNotEmpty()) {
                    item {
                        SectionLabel("Tally refused these (${r.failed.size})", RejectRed)
                    }
                    items(r.failed) { f -> FailedCard(f) { scope.launch { api?.retry(f.id); refresh() } } }
                }

                if (r.unresolvedPids.isEmpty() && r.failed.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Nothing to review.", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Every scan resolved and every posting went through.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    binding?.let { p ->
        BindPidDialog(
            pid = p.pid,
            api = api,
            onDismiss = { binding = null },
            onBound = { scope.launch { binding = null; refresh() } },
        )
    }
}

@Composable
private fun FailedCard(f: ApiFailedSession, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = RejectRedBg),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(f.kind, style = MaterialTheme.typography.titleMedium)
                Text(
                    f.errorCode,
                    style = MaterialTheme.typography.labelMedium,
                    color = RejectRed,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Tally's exact wording. Whoever fixes this needs the real message.
            Text(f.errorMessage, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRetry) { Text("Retry now") }
        }
    }
}

/**
 * Binds a scanned PID to an item that already exists in Tally.
 *
 * Deliberately a SEARCH over the real item master, not a free-text description
 * box. Typing a description is how a Tally item master ends up holding
 * "SSD SENSOR BASE", "SSD Sensor Base" and "SSD SENSR BASE" inside a month.
 */
@Composable
private fun BindPidDialog(
    pid: String,
    api: RelayApi?,
    onDismiss: () -> Unit,
    onBound: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ApiItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        results = runCatching { api?.searchItems(query) ?: emptyList() }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map $pid") },
        text = {
            Column {
                Text(
                    "Find the Tally item this product already is. Every line waiting on " +
                        "$pid is resolved the moment you pick one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search the Tally item master") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                warning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = FlagAmber, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(results) { item ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(enabled = !busy) {
                                scope.launch {
                                    busy = true
                                    val resp = runCatching {
                                        api?.bind(BindRequest(pid, item.name, item.name))
                                    }.getOrNull()
                                    busy = false
                                    if (resp?.warning != null) warning = resp.warning
                                    else onBound()
                                }
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                if (item.partNo.isNotEmpty() || item.hasBatches == 0) {
                                    Text(
                                        buildString {
                                            if (item.partNo.isNotEmpty()) append("part ${item.partNo}")
                                            if (item.hasBatches == 0) {
                                                if (isNotEmpty()) append("  ·  ")
                                                append("NOT batch-wise")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (item.hasBatches == 0) RejectRed
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
                if (query.length >= 2 && results.isEmpty()) {
                    Text(
                        "No match. If this really is a new product, it has to be created in " +
                            "Tally first -- this app will not create stock items.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
