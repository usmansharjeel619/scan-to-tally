package com.acme.scantotally.labels

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class PairedPrinter(val name: String, val address: String)

object ZebraPrinter {
    private val printing = Mutex()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "label-printer-timeout").apply { isDaemon = true }
    }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun hasBluetoothPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<PairedPrinter> {
        check(hasBluetoothPermission(context)) { "Allow Nearby devices permission to use Bluetooth." }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("This phone has no Bluetooth adapter. Use Wi-Fi printing.")
        check(adapter.isEnabled) { "Turn on Bluetooth and pair the printer in Android Settings." }
        return adapter.bondedDevices.map { PairedPrinter(it.name ?: "Paired device", it.address) }
            .sortedBy { it.name }
    }

    /** Sends once. A successful write is not proof that a physical label emerged. */
    @SuppressLint("MissingPermission")
    suspend fun send(context: Context, bluetooth: Boolean, address: String, labels: List<BoxLabel>, port: Int = 9100) {
        check(printing.tryLock()) { "Another print job is still sending. Wait for it to finish." }
        try {
            withContext(Dispatchers.IO) {
                require(labels.isNotEmpty() && labels.size <= BoxLabels.MAX_LABELS)
                if (bluetooth) {
                    check(hasBluetoothPermission(context)) { "Allow Nearby devices permission first." }
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                        ?: error("Bluetooth is unavailable.")
                    check(adapter.isEnabled) { "Turn on Bluetooth." }
                    require(address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) { "Select a paired printer." }
                    val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(spp)
                    guarded(socket) { socket.connect(); write(socket.outputStream, labels) }
                } else {
                    require(address.isNotBlank() && address.length <= 253 &&
                        address.matches(Regex("[a-zA-Z0-9.-]+"))) { "Enter the printer IP address or hostname, without http:// or a port." }
                    val socket = Socket()
                    guarded(socket) {
                        require(port in 1..65535)
                        socket.connect(InetSocketAddress(address, port), 10_000)
                        write(socket.getOutputStream(), labels)
                    }
                }
            }
        } finally { printing.unlock() }
    }

    private suspend fun guarded(socket: Closeable, block: suspend () -> Unit) {
        // Bluetooth connect and socket writes can block; closing the socket
        // gives them a hard bound even if the OS ignores thread interrupts.
        val timeout = watchdog.schedule({ runCatching { socket.close() } }, 120, TimeUnit.SECONDS)
        try { socket.use { block() } } finally { timeout.cancel(false) }
    }

    private suspend fun write(output: OutputStream, labels: List<BoxLabel>) {
        for (label in labels) {
            coroutineContext.ensureActive()
            output.write(LabelRenderer.zpl(label))
            output.flush()
        }
    }
}
