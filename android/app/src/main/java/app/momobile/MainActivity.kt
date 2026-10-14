package app.momobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Mo Print - a WebView around the Mo web app, plus a Bluetooth SPP writer.
 *
 * The web page calls MoPrinter.printBytes(base64) with a finished printer byte
 * stream fetched from the server. This app knows nothing about the printer
 * protocol; it opens an RFCOMM socket and writes the bytes.
 *
 * Lessons already paid for, encoded here:
 *  - SPP (UUID 00001101), not BLE. BLE drops the link partway through a
 *    full-height image no matter how writes are paced.
 *  - Write in chunks with a flush after each, not one giant write.
 *  - Hold the socket open after the last write; closing immediately discards
 *    whatever the printer has not yet consumed, truncating the tail.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MoMobile"

        /** Serial Port Profile. The printer's real data path. */
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val PREFS = "momobile"
        private const val KEY_DEVICE = "printer_mac"
        private const val KEY_SERVER = "server_url"
        private const val KEY_MODE = "mode"
        private const val MODE_LOCAL = "local"

        /** First-launch screen, bundled in the app (assets/welcome.html). */
        private const val WELCOME_URL = "file:///android_asset/welcome.html"

        /** Where the built-in server listens in "Everything on this phone" mode. */
        private const val LOCAL_URL = "http://127.0.0.1:8421"

        /** Bytes per write. Matches the chunking proven on the desktop driver. */
        private const val CHUNK = 1024

        /** Base hold time after the last write, milliseconds. */
        private const val DRAIN_BASE_MS = 3000L

        /** Extra hold per kilobyte sent. */
        private const val DRAIN_PER_KB_MS = 250L

        private const val PERM_REQUEST = 1001

        /** Launcher shortcut action that opens the server dialog. */
        private const val ACTION_CHANGE_SERVER = "app.momobile.CHANGE_SERVER"

        /** Launcher shortcut action that opens the server's /setup page. */
        private const val ACTION_MANAGE_CARDS = "app.momobile.MANAGE_CARDS"

        /** Name fragments that identify this printer among paired devices. */
        private val NAME_HINTS = listOf("S1-", "IDPRT", "HPRT")
    }

    private lateinit var webView: WebView
    private val io = Executors.newSingleThreadExecutor()

    private var socket: BluetoothSocket? = null
    private var connectedName: String? = null

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val localMode: Boolean
        get() = prefs.getString(KEY_MODE, null) == MODE_LOCAL

    private val serverUrl: String
        get() = if (localMode) LOCAL_URL
            else prefs.getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER_URL)
                ?: BuildConfig.DEFAULT_SERVER_URL

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // ------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBluetoothPermissions()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = SameOriginClient(serverUrl)
            addJavascriptInterface(Bridge(), "MoPrinter")
            addJavascriptInterface(Welcome(), "MoWelcome")
        }

        setContentView(webView)

        // Opened from a server's "Connect" link: confirm, then switch.
        val linked = connectLinkServer(intent)
        if (linked != null) {
            if (serverUrl.isNotBlank()) openServer() else showModeChooser()
            confirmConnect(linked)
        } else if (intent?.action == ACTION_CHANGE_SERVER) {
            if (serverUrl.isNotBlank()) openServer()
            showServerDialog(firstRun = serverUrl.isBlank())
        // No server baked in, none saved, and not on-phone mode: ask first.
        } else if (serverUrl.isBlank()) {
            showModeChooser()
        } else {
            openServer(if (intent?.action == ACTION_MANAGE_CARDS) "/setup" else "")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CHANGE_SERVER) showServerDialog(firstRun = false)
        if (intent.action == ACTION_MANAGE_CARDS && serverUrl.isNotBlank()) webView.loadUrl("$serverUrl/setup")
        connectLinkServer(intent)?.let { confirmConnect(it) }
    }

    override fun onDestroy() {
        closeSocket()
        io.shutdown()
        super.onDestroy()
    }

    @Deprecated("Back navigation inside the WebView")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ---------------------------------------------------------- permissions

    private fun requestBluetoothPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_REQUEST)
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ------------------------------------------------------------ bluetooth

    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun ensureSocket(): BluetoothSocket {
        socket?.let { if (it.isConnected) return it }
        closeSocket()

        if (!hasBluetoothPermission()) {
            throw IOException("Bluetooth permission not granted")
        }

        val adapter = bluetoothAdapter ?: throw IOException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw IOException("Bluetooth is off")

        val mac = prefs.getString(KEY_DEVICE, null)
            ?: autoSelectPrinter()
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
    private fun autoSelectPrinter(): String? {
        val matches = pairedDevices().filter { device ->
            val name = (device.name ?: "").uppercase()
            NAME_HINTS.any { name.contains(it) }
        }

        if (matches.size != 1) return null

        val chosen = matches.first()
        prefs.edit().putString(KEY_DEVICE, chosen.address).apply()
        Log.i(TAG, "auto-selected printer ${chosen.name} (${chosen.address})")
        return chosen.address
    }

    private fun closeSocket() {
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
    private fun writeJob(payload: ByteArray) {
        val sock = ensureSocket()
        val out = sock.outputStream

        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + CHUNK, payload.size)
            out.write(payload, offset, end - offset)
            out.flush()
            offset = end
        }

        val drain = DRAIN_BASE_MS + DRAIN_PER_KB_MS * (payload.size / 1024L)
        Thread.sleep(drain)
    }

    // ------------------------------------------------------------ JS bridge

    inner class Bridge {

        /**
         * Print a base64-encoded byte stream. Returns "ok" or an error string.
         * Called from the web page; runs the socket work off the UI thread but
         * blocks the caller so the page can report success accurately.
         */
        @JavascriptInterface
        fun printBytes(base64: String): String {
            return try {
                val payload = Base64.decode(base64, Base64.DEFAULT)
                val task = io.submit { writeJob(payload) }
                task.get()
                "ok"
            } catch (e: Exception) {
                val cause = e.cause ?: e
                Log.e(TAG, "print failed", cause)
                closeSocket()

                val message = cause.message ?: "Print failed"

                // A missing selection is fixable right here: show the picker
                // rather than leaving the user with a dead error string.
                if (message.contains("No printer selected")) {
                    runOnUiThread { showPrinterPicker() }
                    return "Pick your printer, then press Print again"
                }
                if (message.contains("Bluetooth is off")) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "Turn Bluetooth on, then press Print again",
                            Toast.LENGTH_LONG).show()
                    }
                }

                message
            }
        }

        /** Current bridge state, as JSON, for the page's status indicator. */
        @JavascriptInterface
        fun status(): String {
            val json = JSONObject()
            json.put("connected", socket?.isConnected == true)
            json.put("deviceName", connectedName ?: prefs.getString(KEY_DEVICE, null))
            json.put("hasPermission", hasBluetoothPermission())
            return json.toString()
        }

        /** Keep the screen on while the on-phone card build runs. */
        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            runOnUiThread {
                val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (on) window.addFlags(flag) else window.clearFlags(flag)
            }
        }

        /** Open the paired-device picker. */
        @JavascriptInterface
        fun choosePrinter() {
            runOnUiThread { showPrinterPicker() }
        }

    }

    // --------------------------------------------------------------- server

    /** Load the server, starting the built-in one first in on-phone mode. */
    private fun openServer(path: String = "") {
        if (!localMode) {
            webView.loadUrl(serverUrl + path)
            return
        }
        // Python takes a moment to start; do it off the UI thread.
        Thread {
            val error = try {
                if (!com.chaquo.python.Python.isStarted()) {
                    com.chaquo.python.Python.start(
                        com.chaquo.python.android.AndroidPlatform(applicationContext))
                }
                val dataDir = java.io.File(filesDir, "mo").apply { mkdirs() }
                com.chaquo.python.Python.getInstance().getModule("mo_local")
                    .callAttr("start", dataDir.absolutePath)
                null
            } catch (e: Exception) {
                Log.e(TAG, "built-in server failed", e)
                e.message ?: e.toString()
            }
            runOnUiThread {
                if (error == null) webView.loadUrl(serverUrl + path)
                else AlertDialog.Builder(this)
                    .setTitle("Couldn't start Mo on this phone")
                    .setMessage(error)
                    .setPositiveButton("Use a server instead") { _, _ -> showServerDialog(firstRun = true) }
                    .show()
            }
        }.start()
    }

    /** First launch with nothing set up: a welcome screen with the two choices. */
    private fun showModeChooser() {
        webView.loadUrl(WELCOME_URL)
    }

    /** Switch to "Everything on this phone": start the built-in server, which shows the card picker. */
    private fun confirmLocalMode() {
        prefs.edit().putString(KEY_MODE, MODE_LOCAL).apply()
        intent = Intent(this, MainActivity::class.java)
        recreate()
    }

    /** Accepts "192.168.1.20:8420", "http://...", "https://..."; trims a trailing slash. */
    private fun normalizeServer(raw: String): String? {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return if (Uri.parse(url).host.isNullOrEmpty()) null else url
    }

    /** Server address from a momobile://connect?server=... link, or null. */
    private fun connectLinkServer(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "momobile" || data.host != "connect") return null
        return data.getQueryParameter("server")?.let { normalizeServer(it) }
    }

    /**
     * A link can come from any web page, so never switch silently: the app
     * only ever talks to the server the user agreed to.
     */
    private fun confirmConnect(url: String) {
        if (url == serverUrl) {
            Toast.makeText(this, "Already connected to $url", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to this Mo server?")
            .setMessage(url)
            .setPositiveButton("Connect") { _, _ ->
                prefs.edit().putString(KEY_SERVER, url).remove(KEY_MODE).apply()
                // Drop the link so the recreated activity doesn't ask again.
                intent = Intent(this, MainActivity::class.java)
                recreate()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (serverUrl.isBlank()) showModeChooser()
            }
            .setCancelable(false)
            .show()
    }

    /** Ask for the Mo server address, save it, and reload the app on it. */
    private fun showServerDialog(firstRun: Boolean, message: String? = null) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://192.168.1.20:8420"
            setText(if (localMode) "" else serverUrl)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Mo server address")
            .setMessage(message ?: "The address of the Mo server this app prints from.")
            .setView(box)
            .setCancelable(!firstRun)
            .setPositiveButton("Save", null)
            .apply { if (!firstRun) setNegativeButton("Cancel", null) }
            .apply {
                // Offered when changing servers; on first launch the welcome screen has it.
                if (!localMode && serverUrl.isNotBlank()) {
                    setNeutralButton("On this phone") { _, _ -> confirmLocalMode() }
                }
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalizeServer(input.text.toString())
                if (url == null) {
                    input.error = "Enter an address like 192.168.1.20:8420"
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_SERVER, url).remove(KEY_MODE).apply()
                dialog.dismiss()
                // Rebuild the WebView client so the same-origin lock follows the new host.
                recreate()
            }
        }
        dialog.show()
    }

    /** The server didn't answer: offer to retry or point the app somewhere else. */
    private fun showUnreachable(description: String) {
        AlertDialog.Builder(this)
            .setTitle("Can't reach the Mo server")
            .setMessage("$serverUrl\n\n$description")
            .setPositiveButton("Retry") { _, _ -> webView.loadUrl(serverUrl) }
            .setNegativeButton("Change server") { _, _ -> showServerDialog(firstRun = false) }
            .show()
    }

    // -------------------------------------------------------------- welcome

    /** Buttons on the bundled welcome screen. Ignored from any other page. */
    inner class Welcome {
        @JavascriptInterface
        fun choose(mode: String) {
            runOnUiThread {
                if (webView.url != WELCOME_URL) return@runOnUiThread
                if (mode == "local") confirmLocalMode() else showServerDialog(firstRun = false)
            }
        }
    }

    // --------------------------------------------------------------- picker

    @SuppressLint("MissingPermission")
    private fun showPrinterPicker() {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissions()
            return
        }

        val devices = pairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "No paired Bluetooth devices. Pair the printer in Settings first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select printer")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                prefs.edit().putString(KEY_DEVICE, chosen.address).apply()
                closeSocket()
                Toast.makeText(this, "Printer: ${chosen.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------------------------------------------------- web client

    /**
     * Keep the WebView on our own server. addJavascriptInterface exposes real
     * device capability to whatever page is loaded, so any other origin must
     * go to the system browser instead.
     */
    private inner class SameOriginClient(serverUrl: String) : WebViewClient() {

        private val allowedHost: String? = Uri.parse(serverUrl).host

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val host = request.url.host
            if (host != null && host == allowedHost) return false

            return try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, request.url)
                view.context.startActivity(intent)
                true
            } catch (e: Exception) {
                true
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame) showUnreachable(error.description.toString())
        }
    }
}
