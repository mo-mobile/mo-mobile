package app.momobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The one Bluetooth connection to the printer, shared by the app screen and
 * the Sharing service so they never fight over it (the printer takes one
 * connection at a time). Every write goes through one thread, in order.
 *
 * Lessons already paid for, encoded here:
 *  - SPP (UUID 00001101), not BLE. BLE drops the link partway through a
 *    full-height image no matter how writes are paced.
 *  - Write in chunks with a flush after each, not one giant write.
 *  - Hold the socket open after the last write; closing immediately discards
 *    whatever the printer has not yet consumed, truncating the tail.
 */
object PrinterLink {

    private const val TAG = "MoMobile"

    /** Serial Port Profile. The printer's real data path. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val KEY_DEVICE = "printer_mac"

    /** Bytes per write. Matches the chunking proven on the desktop driver. */
    private const val CHUNK = 1024

    /** Base hold time after the last write, milliseconds. */
    private const val DRAIN_BASE_MS = 3000L

    /** Extra hold per kilobyte sent. */
    private const val DRAIN_PER_KB_MS = 250L

    /** Name fragments that identify this printer among paired devices. */
    val NAME_HINTS = listOf("S1-", "IDPRT", "HPRT")

    private val io = Executors.newSingleThreadExecutor()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile var connectedName: String? = null
        private set

    val isConnected: Boolean get() = socket?.isConnected == true

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        return adapter(context)?.bondedDevices?.toList() ?: emptyList()
    }

    /** Print one job on the printer thread. The future fails with the reason. */
    fun print(context: Context, payload: ByteArray): Future<*> {
        val app = context.applicationContext
        return io.submit {
            try {
                writeJob(app, payload)
            } catch (e: Exception) {
                close()
                throw e
            }
        }
    }

    fun forget() {
        io.submit { close() }
    }

    @SuppressLint("MissingPermission")
    private fun ensureSocket(context: Context): BluetoothSocket {
        socket?.let { if (it.isConnected) return it }
        close()

        if (!hasPermission(context)) throw IOException("Bluetooth permission not granted")

        val adapter = adapter(context) ?: throw IOException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")

        val mac = prefs(context).getString(KEY_DEVICE, null)
            ?: autoSelectPrinter(context)
            ?: throw IOException("No printer selected")

        val device = adapter.getRemoteDevice(mac)
        val fresh = device.createRfcommSocketToServiceRecord(SPP_UUID)

        // Stopping discovery makes the connection faster and more reliable, but
        // it needs BLUETOOTH_SCAN, which this app has no other use for. Asking
        // for a scan permission just to print would be the wrong trade, so this
        // is best-effort: if it is denied, connect anyway.
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.d(TAG, "cancelDiscovery not permitted; connecting anyway")
        }

        fresh.connect()

        socket = fresh
        connectedName = device.name
        return fresh
    }

    /**
     * Pick a paired printer without asking, when the choice is obvious.
     *
     * Looks for a bonded device whose name matches the known printer naming
     * (S1-xxxx, iDPRT, HPRT). Only auto-selects when exactly one matches, so
     * we never silently pick the wrong device.
     */
    @SuppressLint("MissingPermission")
    private fun autoSelectPrinter(context: Context): String? {
        val matches = pairedDevices(context).filter { device ->
            val name = (device.name ?: "").uppercase()
            NAME_HINTS.any { name.contains(it) }
        }
        if (matches.size != 1) return null
        val chosen = matches.first()
        prefs(context).edit().putString(KEY_DEVICE, chosen.address).apply()
        Log.i(TAG, "auto-selected printer ${chosen.name} (${chosen.address})")
        return chosen.address
    }

    private fun close() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "closing socket", e)
        }
        socket = null
        connectedName = null
    }

    /**
     * Write one job. Chunked with a flush per chunk, then hold the socket open
     * so the printer can drain before anything closes it.
     */
    private fun writeJob(context: Context, payload: ByteArray) {
        val out = ensureSocket(context).outputStream
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + CHUNK, payload.size)
            out.write(payload, offset, end - offset)
            out.flush()
            offset = end
        }
        Thread.sleep(DRAIN_BASE_MS + DRAIN_PER_KB_MS * (payload.size / 1024L))
    }
}
