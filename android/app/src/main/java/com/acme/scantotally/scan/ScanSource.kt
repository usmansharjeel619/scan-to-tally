package com.acme.scantotally.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * One scan, before any interpretation.
 *
 * Symbology travels with the payload because parser selection uses it: routing
 * on (symbology, pattern) rather than pattern alone is what makes supporting a
 * second label format painless later.
 */
data class RawScan(
    val data: String,
    val symbology: String,
    val source: Source,
) {
    enum class Source { HARDWARE, CAMERA, MANUAL }
}

/**
 * Where scans come from.
 *
 * Deliberately an interface with three implementations. The rugged hardware is
 * what ships, but the camera path is what makes the app testable on an ordinary
 * phone with no Zebra to hand, and manual entry is a first-class tab because
 * the labels sit under packing tape and torn ones are a certainty.
 */
interface ScanSource {
    val name: String
    fun isAvailable(context: Context): Boolean
    fun scans(context: Context): Flow<RawScan>
}

/**
 * Zebra DataWedge via INTENT output.
 *
 * Intent output rather than keystroke output, deliberately: the decoded data
 * AND the symbology arrive cleanly at a broadcast receiver, with no hidden
 * EditText to keep focused, no soft keyboard to suppress, and no trailing
 * Enter to debounce. All of the keyboard-wedge ugliness simply does not exist.
 *
 * The matching DataWedge profile ships as res/raw/datawedge_profile.db so
 * devices provision themselves instead of being hand-configured one by one.
 */
object DataWedgeScanSource : ScanSource {
    override val name = "DataWedge"

    const val ACTION_SCAN = "com.acme.scantotally.SCAN"
    private const val EXTRA_DATA = "com.symbol.datawedge.data_string"
    private const val EXTRA_LABEL_TYPE = "com.symbol.datawedge.label_type"
    private const val DATAWEDGE_ACTION = "com.symbol.datawedge.api.ACTION"

    /**
     * Zebra and Honeywell devices both report a manufacturer we can match on.
     * A false negative here is harmless -- the camera source takes over -- so
     * this stays a cheap check rather than a capability probe.
     */
    override fun isAvailable(context: Context): Boolean {
        val m = Build.MANUFACTURER.lowercase()
        return m.contains("zebra") || m.contains("symbol") ||
            m.contains("motorola solutions") || m.contains("honeywell") ||
            m.contains("urovo") || m.contains("chainway")
    }

    override fun scans(context: Context): Flow<RawScan> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val data = intent?.getStringExtra(EXTRA_DATA) ?: return
                if (data.isEmpty()) return
                val label = intent.getStringExtra(EXTRA_LABEL_TYPE).orEmpty()
                trySend(RawScan(data, normaliseSymbology(label), RawScan.Source.HARDWARE))
            }
        }

        val filter = IntentFilter(ACTION_SCAN).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        configureProfile(context)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Creates the DataWedge profile at runtime, scoped to this app.
     *
     * Only Code 128 and Code 39 are enabled. Fewer enabled symbologies means
     * fewer misreads, and nothing on a Simplex carton uses anything else.
     */
    fun configureProfile(context: Context) {
        val profileName = "ScanToTally"
        runCatching {
            context.sendBroadcast(Intent(DATAWEDGE_ACTION).apply {
                putExtra("com.symbol.datawedge.api.CREATE_PROFILE", profileName)
            })

            val config = android.os.Bundle().apply {
                putString("PROFILE_NAME", profileName)
                putString("PROFILE_ENABLED", "true")
                putString("CONFIG_MODE", "CREATE_IF_NOT_EXIST")

                putParcelableArray("APP_LIST", arrayOf(android.os.Bundle().apply {
                    putString("PACKAGE_NAME", context.packageName)
                    putStringArray("ACTIVITY_LIST", arrayOf("*"))
                }))

                putParcelableArray("PLUGIN_CONFIG", arrayOf(
                    android.os.Bundle().apply {
                        putString("PLUGIN_NAME", "BARCODE")
                        putString("RESET_CONFIG", "true")
                        putBundle("PARAM_LIST", android.os.Bundle().apply {
                            putString("scanner_selection", "auto")
                            putString("decoder_code128", "true")
                            putString("decoder_code39", "true")
                            // Everything else off: fewer symbologies, fewer misreads.
                            putString("decoder_ean13", "false")
                            putString("decoder_ean8", "false")
                            putString("decoder_upca", "false")
                            putString("decoder_upce0", "false")
                            putString("decoder_i2of5", "false")
                            // Needed for the setup code. A QR is different enough
                            // from Code 128 that enabling it costs nothing in
                            // misreads, and the parser rejects anything odd.
                            putString("decoder_qrcode", "true")
                        })
                    },
                    android.os.Bundle().apply {
                        putString("PLUGIN_NAME", "INTENT")
                        putString("RESET_CONFIG", "true")
                        putBundle("PARAM_LIST", android.os.Bundle().apply {
                            putString("intent_output_enabled", "true")
                            putString("intent_action", ACTION_SCAN)
                            putString("intent_delivery", "2") // broadcast
                        })
                    },
                    android.os.Bundle().apply {
                        putString("PLUGIN_NAME", "KEYSTROKE")
                        putBundle("PARAM_LIST", android.os.Bundle().apply {
                            // Deliberately LEFT ON. If the intent profile fails
                            // to apply -- which it silently can -- disabling
                            // keystrokes too would leave the scanner with no
                            // working path at all, which is exactly what
                            // happened on the first real device. The app reads
                            // both; a duplicate is refused anyway.
                            putString("keystroke_output_enabled", "true")
                        })
                    },
                ))
            }

            context.sendBroadcast(Intent(DATAWEDGE_ACTION).apply {
                putExtra("com.symbol.datawedge.api.SET_CONFIG", config)
            })
        }.onFailure { Log.w("DataWedge", "profile setup failed", it) }
    }

    /** Maps vendor label-type strings onto the names the parsers expect. */
    private fun normaliseSymbology(labelType: String): String = when {
        labelType.contains("code128", true) -> "CODE128"
        labelType.contains("code39", true) -> "CODE39"
        labelType.contains("ean13", true) -> "EAN13"
        labelType.contains("qr", true) -> "QRCODE"
        labelType.isEmpty() -> "UNKNOWN"
        else -> labelType.removePrefix("LABEL-TYPE-").uppercase()
    }
}

/** Manual entry, routed through the identical parser as a real scan. */
object ManualScanSource {
    fun scan(text: String) = RawScan(text, "MANUAL", RawScan.Source.MANUAL)
}
