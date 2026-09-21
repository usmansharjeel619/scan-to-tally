package com.acme.scantotally.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.acme.scantotally.data.Repository
import com.acme.scantotally.data.StockLookup
import com.acme.scantotally.feedback.Beep
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.ui.theme.LocalSemantics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Check stock: scan a box, see what Tally holds for that product.
 *
 * Asks a question and changes nothing. No session is opened, so it leaves no
 * receipt behind -- which is the whole difference between this and a stock
 * take. Nothing is typed, and the answer comes from figures already on the
 * phone, so it works in an aisle with no signal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockLookupScreen(nav: NavController, scans: Flow<RawScan>) {
    val app = rememberApp()
    var repo by remember { mutableStateOf<Repository?>(null) }
    var godown by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<StockLookup?>(null) }

    LaunchedEffect(Unit) {
        repo = app.repository()
        godown = app.config.godown.first()
    }

    LaunchedEffect(repo, godown) {
        val r = repo ?: return@LaunchedEffect
        if (godown.isEmpty()) return@LaunchedEffect
        scans.collect { scan ->
            val look = r.lookupStock(godown, scan)
            result = look
            app.feedback.play(if (look.found) Beep.ACCEPT else Beep.FLAGGED)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Check stock")
                        Text(
                            godown,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        val look = result
        if (look == null) {
            Box(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Scan a product", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The part number barcode, or the long one -- either works. " +
                            "It shows every box of that product and the total. " +
                            "Nothing is recorded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        if (!look.found) {
            val sem = LocalSemantics.current
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp)) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = sem.review.bg),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            look.message,
                            style = MaterialTheme.typography.titleLarge,
                            color = sem.onCard,
                        )
                        if (look.scannedBox.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Box ${tail(look.scannedBox)}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                color = sem.onCard.copy(alpha = 0.75f),
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(pad).padding(16.dp)) {
            item {
                Text(look.description, style = MaterialTheme.typography.headlineSmall)
                Text(
                    look.pid,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // The answer to the question actually asked, in the largest
                // type on the screen.
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "In ${look.godown}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${fmtQty(look.total)} ${look.unit}",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "across ${look.boxes.size} ${if (look.boxes.size == 1) "box" else "boxes"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        // A receipt has gone in that these figures predate, so
                        // say so rather than presenting a stale number -- and
                        // especially rather than presenting zero, which reads
                        // as "none in stock" when it means "not counted yet".
                        if (look.awaitingSync) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "An entry for this product has not reached these " +
                                    "figures yet. They refresh within two minutes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel("Boxes")
            }

            items(look.boxes) { b ->
                val isScanned = b.batchName == look.scannedBox
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        // The box in the operator's hand, called out: it is the
                        // one they can check against by eye.
                        tail(b.batchName) + if (isScanned) "  ← scanned" else "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isScanned) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        fmtQty(b.closingQty),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isScanned) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                HorizontalDivider()
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    // Never presented as live. A figure whose age is hidden is
                    // one an operator cannot judge.
                    if (look.asOf == 0L) "Figures not synced yet"
                    else "Tally figures as of ${whenScanned(look.asOf)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
