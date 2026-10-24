package app.momobile

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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

/**
 * Mo Mobile - a WebView around the Mo web app, plus a Bluetooth SPP writer.
 *
 * The web page calls MoPrinter.printBytes(base64) with a finished printer byte
 * stream fetched from the server. This app knows nothing about the printer
 * protocol; PrinterLink opens an RFCOMM socket and writes the bytes.
 *
 * Sharing (ShareService, ShareClient) lets friends print to the same printer
 * from their own phones through the Sharing server.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MoMobile"

        const val PREFS = "momobile"
        const val KEY_KEEP_RUNNING = "bg_keep_running"
        const val KEY_REVOKE_NOTIFICATIONS = "bg_revoke_notifications"
        /** Folder holding the cards in phone-only mode (internal storage or an SD card). */
        const val KEY_STORAGE = "storage_dir"
        private const val KEY_STORAGE_CACHE = "storage_cache"
        private const val NOTIFY_REQUEST = 1002
        private const val KEY_DEVICE = PrinterLink.KEY_DEVICE
        private const val KEY_SERVER = "server_url"
        private const val KEY_MODE = "mode"
        private const val MODE_LOCAL = "local"

        /** First-launch screen, bundled in the app (assets/welcome.html). */
        private const val WELCOME_URL = "file:///android_asset/welcome.html"

        /** Settings screen, bundled in the app (assets/settings.html). */
        private const val SETTINGS_URL = "file:///android_asset/settings.html"

        /** Sharing screen, bundled in the app (assets/sharing.html). */
        private const val SHARING_URL = "file:///android_asset/sharing.html"

        /** From the Sharing notification: open the print queue. */
        const val ACTION_OPEN_QUEUE = "app.momobile.OPEN_QUEUE"

        /** Where the built-in server listens in "Everything on this phone" mode. */
        private const val LOCAL_URL = "http://127.0.0.1:8421"

        private const val PERM_REQUEST = 1001

        /** Launcher shortcut action that opens the server dialog. */
        private const val ACTION_CHANGE_SERVER = "app.momobile.CHANGE_SERVER"

        /** Launcher shortcut action that opens the server's /setup page. */
        private const val ACTION_MANAGE_CARDS = "app.momobile.MANAGE_CARDS"

    }

    private lateinit var webView: WebView

    /** Open the print queue once the page has loaded (from the Sharing notification). */
    private var openQueueOnLoad = false

    /** The page additions (assets/mo_ui.js): header, roll-up windows, queue. */
    private val uiScript by lazy { assets.open("mo_ui.js").bufferedReader().use { it.readText() } }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val localMode: Boolean
        get() = prefs.getString(KEY_MODE, null) == MODE_LOCAL

    private val serverUrl: String
        get() = if (localMode) LOCAL_URL
            else prefs.getString(KEY_SERVER, BuildConfig.DEFAULT_SERVER_URL)
                ?: BuildConfig.DEFAULT_SERVER_URL

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
            addJavascriptInterface(Settings(), "MoSettings")
            addJavascriptInterface(Share(), "MoShare")
            addJavascriptInterface(Sharing(), "MoSharing")
        }

        openQueueOnLoad = intent?.action == ACTION_OPEN_QUEUE
        if (ShareClient(this).available) {
            pushToken()
            // Sharing was on but its service stopped (app updated, phone restarted): start it again.
            if (prefs.getBoolean(ShareClient.KEY_ON, false) && !ShareService.running) {
                try { ShareService.start(this) } catch (e: Exception) { Log.w(TAG, "couldn't restart Sharing", e) }
            }
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
        if (intent.action == ACTION_OPEN_QUEUE) {
            if (webView.url?.startsWith("file:///android_asset/") == false) {
                webView.evaluateJavascript("window.__moOpenQueue && __moOpenQueue()", null)
            } else {
                openQueueOnLoad = true
                openServer()
            }
        }
        if (intent.action == ACTION_CHANGE_SERVER) showServerDialog(firstRun = false)
        if (intent.action == ACTION_MANAGE_CARDS && serverUrl.isNotBlank()) webView.loadUrl("$serverUrl/setup")
        connectLinkServer(intent)?.let { confirmConnect(it) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to Settings or Sharing: show fresh values.
        if (::webView.isInitialized && (webView.url == SETTINGS_URL || webView.url == SHARING_URL)) webView.reload()
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

    private fun hasBluetoothPermission(): Boolean = PrinterLink.hasPermission(this)

    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<BluetoothDevice> = PrinterLink.pairedDevices(this)

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
                PrinterLink.print(this@MainActivity, payload).get()
                "ok"
            } catch (e: Exception) {
                val cause = e.cause ?: e
                Log.e(TAG, "print failed", cause)

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
            json.put("connected", PrinterLink.isConnected)
            json.put("deviceName", PrinterLink.connectedName ?: prefs.getString(KEY_DEVICE, null))
            json.put("hasPermission", hasBluetoothPermission())
            return json.toString()
        }

        /** Background download choices for the setup page (phone-only mode). */
        @JavascriptInterface
        fun backgroundOptions(): String {
            val json = JSONObject()
            json.put("keepRunning", prefs.getBoolean(KEY_KEEP_RUNNING, false))
            json.put("notify", Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            json.put("canRevoke", Build.VERSION.SDK_INT >= 33)
            json.put("revokeAfter", prefs.getBoolean(KEY_REVOKE_NOTIFICATIONS, false))
            return json.toString()
        }

        @JavascriptInterface
        fun setBackgroundOption(name: String, on: Boolean) {
            when (name) {
                "keepRunning" -> prefs.edit().putBoolean(KEY_KEEP_RUNNING, on).apply()
                "revokeAfter" -> prefs.edit().putBoolean(KEY_REVOKE_NOTIFICATIONS, on).apply()
                "notify" -> if (on && Build.VERSION.SDK_INT >= 33) runOnUiThread {
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
                }
            }
        }

        /** Called by the setup page when a download is running. */
        @JavascriptInterface
        fun downloadStarted() {
            if (localMode && prefs.getBoolean(KEY_KEEP_RUNNING, false)) {
                DownloadService.start(this@MainActivity)
            }
        }

        /** Keep the screen on while the on-phone card build runs. */
        @JavascriptInterface
        fun keepScreenOn(on: Boolean) {
            runOnUiThread {
                val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (on) window.addFlags(flag) else window.clearFlags(flag)
            }
        }

        /** The settings button in the page's title bar. */
        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { webView.loadUrl(SETTINGS_URL) }
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
                val dataDir = cardsRoot().apply { mkdirs() }
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

    /**
     * Switch to "Everything on this phone": start the built-in server, which
     * shows the card picker. With an SD card, ask where the cards should go first
     * (unless cards are already downloaded somewhere).
     */
    private fun confirmLocalMode(location: Int = -1) {
        val places = storagePlaces()
        if (location < 0 && places.size > 1 && cardsBytes(cardsRoot()) == 0L) {
            askStorageLocation(places) { confirmLocalMode(it) }
            return
        }
        if (location >= 0) prefs.edit().putString(KEY_STORAGE, places[location].dir.absolutePath).commit()
        prefs.edit().putString(KEY_MODE, MODE_LOCAL).apply()
        intent = Intent(this, MainActivity::class.java)
        recreate()
    }

    /** Point the app at a server; offer to delete cards left on the phone. */
    private fun switchToServer(url: String) {
        val apply = {
            prefs.edit().putString(KEY_SERVER, url).remove(KEY_MODE).commit()
            restartApp()  // stops the built-in server and loads the new one
        }
        val bytes = cardsBytes(cardsRoot())
        if (!localMode || bytes == 0L) {
            prefs.edit().putString(KEY_SERVER, url).remove(KEY_MODE).apply()
            // Rebuild the WebView client so the same-origin lock follows the new host.
            recreate()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Keep the cards on this phone?")
            .setMessage("They take ${formatSize(bytes)}. Keep them to switch back to " +
                "\"Everything on this phone\" later without downloading again, or delete them to free the space.")
            .setPositiveButton("Keep") { _, _ -> apply() }
            .setNegativeButton("Delete") { _, _ ->
                Thread {
                    cardsRoot().deleteRecursively()
                    runOnUiThread { apply() }
                }.start()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- storage

    data class StoragePlace(val label: String, val dir: java.io.File, val removable: Boolean)

    /** Where cards can live: internal storage, plus any SD card. */
    private fun storagePlaces(): List<StoragePlace> {
        val places = mutableListOf(StoragePlace("This phone", java.io.File(filesDir, "mo"), false))
        getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val removable = try { android.os.Environment.isExternalStorageRemovable(dir) } catch (e: Exception) { false }
            val mounted = android.os.Environment.getExternalStorageState(dir) == android.os.Environment.MEDIA_MOUNTED
            if (removable && mounted) places += StoragePlace("SD card", java.io.File(dir, "mo"), true)
        }
        return places
    }

    private fun cardsRoot(): java.io.File {
        prefs.getString(KEY_STORAGE, null)?.let { saved ->
            val dir = java.io.File(saved)
            if (hasCards(dir) || storagePlaces().none { hasCards(it.dir) }) return dir
        }
        // No cards where we expected them (first run, or a move that didn't
        // get saved): use whichever storage place actually has cards.
        val found = storagePlaces().firstOrNull { hasCards(it.dir) }?.dir
        if (found != null) {
            prefs.edit().putString(KEY_STORAGE, found.absolutePath).commit()
            return found
        }
        return java.io.File(filesDir, "mo")
    }

    private fun hasCards(dir: java.io.File) =
        java.io.File(dir, "app/data/.build_complete").exists() ||
            java.io.File(dir, "app/data/downloaded.json").exists()

    private fun freeBytes(dir: java.io.File): Long {
        var d: java.io.File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return try { android.os.StatFs((d ?: filesDir).path).availableBytes } catch (e: Exception) { 0L }
    }

    /** Size of the downloaded cards (images and card data). */
    private fun cardsBytes(dir: java.io.File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The app itself: its install plus its own data (Python, settings, caches), not the cards. */
    private fun appBytes(cards: java.io.File): Long {
        val apk = java.io.File(applicationInfo.sourceDir).length()
        val data = java.io.File(applicationInfo.dataDir)
        val own = data.walkTopDown()
            .onEnter { it.absolutePath != cards.absolutePath }
            .filter { it.isFile }.sumOf { it.length() }
        return apk + own
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1000} KB"
    }

    private fun askStorageLocation(places: List<StoragePlace>, chosen: (Int) -> Unit) {
        val labels = places.map { "${it.label} (${formatSize(freeBytes(it.dir))} free)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Where should the cards go?")
            .setItems(labels) { _, which -> chosen(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Restart the app's process, so the built-in server starts over with new settings. */
    private fun restartApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    private fun deleteCards() {
        val bytes = cardsBytes(cardsRoot())
        AlertDialog.Builder(this)
            .setTitle("Delete downloaded cards?")
            .setMessage("Frees ${formatSize(bytes)}. You can download them again any time.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    cardsRoot().deleteRecursively()
                    runOnUiThread { restartApp() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Move the cards to the other storage place (phone ↔ SD card). */
    private fun moveCards() {
        val places = storagePlaces()
        val current = cardsRoot().absolutePath
        val targets = places.filter { it.dir.absolutePath != current }
        if (targets.isEmpty()) return
        askStorageLocation(targets) { which ->
            val target = targets[which]
            val from = cardsRoot()
            val need = cardsBytes(from)
            if (need > freeBytes(target.dir)) {
                Toast.makeText(this, "Not enough space on ${target.label}.", Toast.LENGTH_LONG).show()
                return@askStorageLocation
            }
            val progress = AlertDialog.Builder(this)
                .setTitle("Moving cards to ${target.label}…")
                .setMessage("This can take a few minutes.")
                .setCancelable(false)
                .show()
            Thread {
                val ok = try {
                    if (from.exists()) {
                        from.copyRecursively(target.dir, overwrite = true)
                        from.deleteRecursively()
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "move failed", e)
                    target.dir.deleteRecursively()
                    false
                }
                runOnUiThread {
                    progress.dismiss()
                    if (ok) {
                        prefs.edit().putString(KEY_STORAGE, target.dir.absolutePath).commit()
                        restartApp()
                    } else {
                        Toast.makeText(this, "Couldn't move the cards. They're still where they were.", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
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
                // Drop the link so the recreated activity doesn't ask again.
                intent = Intent(this, MainActivity::class.java)
                switchToServer(url)
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
                dialog.dismiss()
                switchToServer(url)
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

    // ------------------------------------------------------------- settings

    /** Buttons on the bundled settings screen. Ignored from any other page. */
    inner class Settings {
        @JavascriptInterface
        fun info(): String {
            val json = JSONObject()
            json.put("mode", if (localMode) "local" else "server")
            json.put("server", if (localMode) "" else serverUrl)
            json.put("version", BuildConfig.VERSION_NAME)
            json.put("printer", PrinterLink.connectedName ?: "")
            json.put("sharing", prefs.getBoolean(ShareClient.KEY_ON, false))
            json.put("waiting", ShareService.waiting)
            json.put("shareAvailable", ShareClient(this@MainActivity).available)
            json.put("hasCards", hasCards(cardsRoot()))
            return json.toString()
        }

        /**
         * Storage numbers: returns the last known ones straight away (or "")
         * and works them out again in the background, then calls the page's
         * onStorage(json). Adding up thousands of card files can be slow,
         * especially on an SD card.
         */
        @JavascriptInterface
        fun storage(): String {
            Thread {
                val fresh = storageJson()
                prefs.edit().putString(KEY_STORAGE_CACHE, fresh).apply()
                runOnUiThread {
                    if (webView.url == SETTINGS_URL) {
                        webView.evaluateJavascript("window.onStorage && onStorage($fresh)", null)
                    }
                }
            }.start()
            return prefs.getString(KEY_STORAGE_CACHE, "") ?: ""
        }

        private fun storageJson(): String {
            val json = JSONObject()
            val root = cardsRoot()
            val places = storagePlaces()
            val place = places.firstOrNull { it.dir.absolutePath == root.absolutePath }
            val cards = cardsBytes(root)
            json.put("cardsBytes", cards)
            json.put("cardsSize", formatSize(cards))
            json.put("appSize", formatSize(appBytes(root)))
            json.put("location", place?.label ?: "This phone")
            json.put("freeSize", formatSize(freeBytes(root)))
            json.put("canMove", places.size > 1)
            return json.toString()
        }

        @JavascriptInterface
        fun action(name: String) {
            runOnUiThread {
                if (webView.url != SETTINGS_URL) return@runOnUiThread
                when (name) {
                    "home" -> openServer()
                    "cards" -> openServer("/setup")
                    "printer" -> showPrinterPicker()
                    "sharing" -> webView.loadUrl(SHARING_URL)
                    "server" -> showServerDialog(firstRun = false)
                    "local" -> confirmLocalMode()
                    "deleteCards" -> deleteCards()
                    "moveCards" -> moveCards()
                }
            }
        }
    }

    /**
     * Add the app's parts to the Mo page (assets/mo_ui.js): the "Mo Mobile"
     * header with the print queue and settings, roll-up windows and the page
     * dots. Added by the app, so it works with any Mo server.
     */
    private fun addAppUi(view: WebView, url: String?) {
        if (url == null || url.startsWith("file:///android_asset/")) return
        view.evaluateJavascript(uiScript, null)
        if (openQueueOnLoad) {
            openQueueOnLoad = false
            view.evaluateJavascript("setTimeout(function(){window.__moOpenQueue && __moOpenQueue()},300)", null)
        }
    }

    // -------------------------------------------------------------- sharing

    /** Ask Firebase for this phone's push address and hand it to the Sharing server. */
    private fun pushToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> PushService.sendToken(this, token) }
        } catch (e: Exception) {
            Log.w(TAG, "push unavailable", e)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
        }
    }

    /** Your name, shown to friends ("Sam's Printer"). Asked once, the first time Sharing goes on. */
    private fun askOwnerName(then: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Your name"
            setText(prefs.getString(ShareClient.KEY_OWNER, "") ?: "")
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Your name")
            .setMessage("Friends see it on your printer: \"Name's Printer\".")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().take(24)
                prefs.edit().putString(ShareClient.KEY_OWNER, name).apply()
                Thread { try { ShareClient(this).update(JSONObject().put("owner", name)) } catch (e: Exception) {} }.start()
                then()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fresh values for the Sharing screen: calls its onShare(json). */
    private fun refreshSharingPage(registerFirst: Boolean) {
        Thread {
            val client = ShareClient(this)
            var error = ""
            if (registerFirst) {
                try {
                    client.ensureRegistered()
                    prefs.getString(ShareClient.KEY_FCM, null)?.let { PushService.sendToken(this, it) }
                } catch (e: Exception) {
                    Log.w(TAG, "sharing register failed", e)
                    error = "Can't reach the Sharing server. Check your internet."
                }
            }
            val json = JSONObject()
                .put("sharing", prefs.getBoolean(ShareClient.KEY_ON, false))
                .put("push", client.push)
                .put("url", client.url)
                .put("error", error)
            runOnUiThread {
                if (webView.url == SHARING_URL) webView.evaluateJavascript("window.onShare && onShare($json)", null)
            }
        }.start()
    }

    /** The Mo page's side of Sharing: queue state, your prints into the queue. */
    inner class Share {
        @JavascriptInterface
        fun state(): String = JSONObject()
            .put("available", ShareClient(this@MainActivity).available)
            .put("sharing", ShareService.running && prefs.getBoolean(ShareClient.KEY_ON, false))
            .put("waiting", ShareService.waiting)
            .toString()

        /** Your own print while Sharing is on: into the queue, printed in turn. */
        @JavascriptInterface
        fun printJob(base64: String, path: String): String {
            return try {
                val payload = Base64.decode(base64, Base64.DEFAULT)
                ShareClient(this@MainActivity).addJob(path, payload)
                "ok"
            } catch (e: Exception) {
                Log.w(TAG, "couldn't queue; printing directly", e)
                Bridge().printBytes(base64)
            }
        }

        @JavascriptInterface
        fun requestQueue() = queueCall { it.queue() }

        @JavascriptInterface
        fun cancel(id: Long) = queueCall { it.cancel(id) }

        @JavascriptInterface
        fun clear() = queueCall { it.clear() }

        @JavascriptInterface
        fun openSharing() {
            runOnUiThread { webView.loadUrl(SHARING_URL) }
        }

        private fun queueCall(call: (ShareClient) -> String) {
            Thread {
                val body = try { call(ShareClient(this@MainActivity)) } catch (e: Exception) { "{}" }
                runOnUiThread { webView.evaluateJavascript("window.__moShareQueue && __moShareQueue($body)", null) }
            }.start()
        }
    }

    /** Buttons on the bundled Sharing screen. Ignored from any other page. */
    inner class Sharing {
        @JavascriptInterface
        fun info(): String {
            val client = ShareClient(this@MainActivity)
            // First visit: register in the background, then the page gets its code.
            runOnUiThread { refreshSharingPage(registerFirst = !client.registered || client.url.isBlank()) }
            return JSONObject()
                .put("sharing", prefs.getBoolean(ShareClient.KEY_ON, false))
                .put("push", client.push)
                .put("url", client.url)
                .toString()
        }

        @JavascriptInterface
        fun set(name: String, on: Boolean) {
            runOnUiThread {
                if (webView.url != SHARING_URL) return@runOnUiThread
                when (name) {
                    "sharing" -> if (on) {
                        val start = {
                            askNotifications()
                            if (!hasBluetoothPermission()) requestBluetoothPermissions()
                            ShareService.start(this@MainActivity)
                        }
                        if (prefs.getString(ShareClient.KEY_OWNER, "").isNullOrBlank()) {
                            askOwnerName(start)
                            refreshSharingPage(false)  // until the name is in, show it still off
                        } else start()
                    } else ShareService.stop(this@MainActivity)
                    "push" -> {
                        prefs.edit().putBoolean(ShareClient.KEY_PUSH, on).apply()
                        if (on) askNotifications()
                        Thread { try { ShareClient(this@MainActivity).update(JSONObject().put("push", on)) } catch (e: Exception) {} }.start()
                    }
                }
            }
        }

        @JavascriptInterface
        fun action(name: String) {
            runOnUiThread {
                if (webView.url != SHARING_URL) return@runOnUiThread
                when (name) {
                    "back" -> webView.loadUrl(SETTINGS_URL)
                    "newcode" -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("Make a new code?")
                        .setMessage("Stickers with the old code stop working. Print a new sticker afterwards.")
                        .setPositiveButton("New code") { _, _ ->
                            Thread {
                                try { ShareClient(this@MainActivity).newCode() } catch (e: Exception) {
                                    runOnUiThread { Toast.makeText(this@MainActivity, "Couldn't reach the Sharing server.", Toast.LENGTH_LONG).show() }
                                }
                                refreshSharingPage(false)
                            }.start()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    "sticker" -> Thread {
                        val message = try {
                            val bytes = ShareClient(this@MainActivity).sticker()
                            PrinterLink.print(this@MainActivity, bytes).get()
                            "Sticker printed."
                        } catch (e: Exception) {
                            val cause = e.cause ?: e
                            if ((cause.message ?: "").contains("No printer selected")) {
                                runOnUiThread { showPrinterPicker() }
                                "Pick your printer, then press Print sticker again."
                            } else "Couldn't print the sticker: ${cause.message ?: "printer not responding"}"
                        }
                        runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                    }.start()
                }
            }
        }
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

    /** Paired devices that look like printers: Bluetooth "imaging" class, or a known name. */
    @SuppressLint("MissingPermission")
    private fun looksLikePrinter(device: BluetoothDevice): Boolean {
        val major = device.bluetoothClass?.majorDeviceClass
        if (major == android.bluetooth.BluetoothClass.Device.Major.IMAGING) return true
        val name = (device.name ?: "").uppercase()
        return PrinterLink.NAME_HINTS.any { name.contains(it) }
    }

    @SuppressLint("MissingPermission")
    private fun showPrinterPicker(showAll: Boolean = false) {
        if (!hasBluetoothPermission()) {
            requestBluetoothPermissions()
            return
        }

        val paired = pairedDevices()
        val printers = paired.filter { looksLikePrinter(it) }
        // Some printers don't say what they are; fall back to everything.
        val devices = if (showAll || printers.isEmpty()) paired else printers
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "No paired Bluetooth devices. Pair the printer in Settings first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val labels = devices.map { "${it.name ?: "Unknown"}\n${it.address}" }.toMutableList()
        val canShowMore = !showAll && printers.isNotEmpty() && paired.size > printers.size
        if (canShowMore) labels += "Show all paired devices"

        AlertDialog.Builder(this)
            .setTitle("Select printer")
            .setItems(labels.toTypedArray()) { _, which ->
                if (canShowMore && which == devices.size) {
                    showPrinterPicker(showAll = true)
                    return@setItems
                }
                val chosen = devices[which]
                prefs.edit().putString(KEY_DEVICE, chosen.address).apply()
                PrinterLink.forget()
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

        override fun onPageFinished(view: WebView, url: String?) {
            addAppUi(view, url)
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
