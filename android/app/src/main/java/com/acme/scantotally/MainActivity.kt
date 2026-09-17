package com.acme.scantotally

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.acme.scantotally.scan.DataWedgeScanSource
import com.acme.scantotally.scan.KeyboardInputGuard
import com.acme.scantotally.scan.KeystrokeScanner
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.ui.screens.HomeScreen
import com.acme.scantotally.ui.screens.IncomingScreen
import com.acme.scantotally.ui.screens.OutgoingScreen
import com.acme.scantotally.ui.screens.ReceiptsScreen
import com.acme.scantotally.ui.screens.SalesOrderPickerScreen
import com.acme.scantotally.ui.screens.SetupScreen
import com.acme.scantotally.ui.screens.StockCheckScreen
import com.acme.scantotally.ui.screens.StockLookupScreen
import com.acme.scantotally.ui.theme.ScanToTallyTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Single activity, launchMode singleTask.
 *
 * singleTask matters: a DataWedge scan arriving while the app is backgrounded
 * must reach the SAME screen the operator left, not start a second copy of the
 * session they were halfway through.
 */
class MainActivity : ComponentActivity() {

    /**
     * Scans are broadcast app-wide rather than wired per screen.
     *
     * The hardware trigger does not know which screen is open, so the screen
     * that cares subscribes and every other one ignores it. replay = 0 so a
     * screen opening does not immediately consume the previous box.
     */
    private val scans = MutableSharedFlow<RawScan>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * What the scanner has actually delivered, regardless of what the app made
     * of it. Shown on the home screen so "nothing happens" can be told apart
     * from "it arrived and the parser rejected it" without a round trip.
     */
    companion object {
        var rawSeen by mutableStateOf(0)
        var lastRaw by mutableStateOf("")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DataWedgeScanSource.configureProfile(this)
        handleScanIntent(intent)

        setContent {
            ScanToTallyTheme {
                val app = LocalContext.current.applicationContext as ScanToTallyApp
                val nav = rememberNavController()
                var provisioned by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    provisioned = app.config.isProvisioned()
                    if (provisioned == true) app.syncInBackground()
                }

                // Hardware scans from DataWedge, merged with anything the
                // activity received as an intent while backgrounded.
                LaunchedEffect(Unit) {
                    DataWedgeScanSource.scans(this@MainActivity).collect { scans.emit(it) }
                }

                // Rugged scanners default to typing the barcode into whatever
                // has focus. This catches that, alongside the intent path.
                KeystrokeScanner(enabled = !KeyboardInputGuard.suspended) { payload ->
                    rawSeen++
                    lastRaw = payload
                    scans.tryEmit(RawScan(payload, "KEYSTROKE", RawScan.Source.HARDWARE))
                }

                Scaffold { inner ->
                    NavHost(
                        navController = nav,
                        startDestination = if (provisioned == false) "setup" else "home",
                        modifier = Modifier.padding(inner),
                    ) {
                        composable("setup") { SetupScreen(nav, scans) }
                        composable("home") { HomeScreen(nav) }
                        // The session id is optional on each of these: absent
                        // starts a new one, present picks up an unsaved receipt
                        // from the queue rather than stranding it.
                        composable(
                            "incoming?session={session}",
                            arguments = listOf(resumeArg()),
                        ) { entry ->
                            IncomingScreen(nav, scans, entry.arguments?.getString("session"))
                        }
                        composable("orders") { SalesOrderPickerScreen(nav) }
                        composable(
                            "outgoing/{order}?session={session}",
                            arguments = listOf(resumeArg()),
                        ) { entry ->
                            OutgoingScreen(
                                nav, scans,
                                entry.arguments?.getString("order").orEmpty(),
                                entry.arguments?.getString("session"),
                            )
                        }
                        // Asks a question and changes nothing, so it takes no
                        // session id: there is no receipt to resume.
                        composable("lookup") { StockLookupScreen(nav, scans) }
                        composable(
                            "stockcheck?session={session}",
                            arguments = listOf(resumeArg()),
                        ) { entry ->
                            StockCheckScreen(nav, scans, entry.arguments?.getString("session"))
                        }
                        composable("receipts") { ReceiptsScreen(nav) }
                    }
                }
            }
        }
    }

    /** An optional session id on a scan route: null means start a fresh one. */
    private fun resumeArg() = navArgument("session") {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScanIntent(intent)
    }

    /** A scan that arrived as an intent because the app was not foregrounded. */
    private fun handleScanIntent(intent: Intent?) {
        if (intent?.action != DataWedgeScanSource.ACTION_SCAN) return
        val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
        if (data.isBlank()) return
        val label = intent.getStringExtra("com.symbol.datawedge.label_type").orEmpty()
        scans.tryEmit(RawScan(data, label.ifEmpty { "UNKNOWN" }, RawScan.Source.HARDWARE))
    }
}
