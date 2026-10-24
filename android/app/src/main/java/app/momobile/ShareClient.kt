package app.momobile

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Sharing server (see app/routes/share.py). Blocking calls: use
 * them off the main thread.
 *
 * The phone registers once and gets a host id and secret; every later call
 * carries both. The printer's code (what the QR sticker points at) belongs to
 * that registration and can be replaced with newCode().
 */
class ShareClient(context: Context) {

    companion object {
        const val KEY_HOST = "share_host"
        const val KEY_SECRET = "share_secret"
        const val KEY_CODE = "share_code"
        const val KEY_URL = "share_url"
        const val KEY_ON = "share_on"
        const val KEY_PUSH = "share_push"
        const val KEY_OWNER = "share_owner"
        const val KEY_FCM = "share_fcm"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
    private val base = BuildConfig.SHARE_SERVER_URL.trimEnd('/')

    /** False when this build has no Sharing server: Sharing stays hidden. */
    val available: Boolean get() = base.isNotEmpty()

    val registered: Boolean get() = prefs.getString(KEY_HOST, null) != null
    val sharing: Boolean get() = prefs.getBoolean(KEY_ON, false)
    val push: Boolean get() = prefs.getBoolean(KEY_PUSH, true)
    val owner: String get() = prefs.getString(KEY_OWNER, "") ?: ""
    val code: String get() = prefs.getString(KEY_CODE, "") ?: ""
    val url: String get() = prefs.getString(KEY_URL, "") ?: ""

    /** Register with the server the first time Sharing is used. */
    fun ensureRegistered() {
        if (registered) return
        val body = JSONObject()
            .put("owner", owner)
            .put("fcm", prefs.getString(KEY_FCM, "") ?: "")
        val r = JSONObject(String(request("POST", "/api/share/register", body, auth = false)!!))
        prefs.edit()
            .putString(KEY_HOST, r.getString("host"))
            .putString(KEY_SECRET, r.getString("secret"))
            .putString(KEY_CODE, r.getString("code"))
            .putString(KEY_URL, r.getString("url"))
            .apply()
    }

    /** Send settings (any of sharing, push, fcm, owner) and keep the answer. */
    fun update(values: JSONObject): JSONObject {
        ensureRegistered()
        val r = JSONObject(String(request("POST", "/api/share/host", values)!!))
        prefs.edit()
            .putString(KEY_CODE, r.getString("code"))
            .putString(KEY_URL, r.getString("url"))
            .apply()
        return r
    }

    fun newCode() {
        ensureRegistered()
        val r = JSONObject(String(request("POST", "/api/share/host/newcode", JSONObject())!!))
        prefs.edit().putString(KEY_CODE, r.getString("code")).putString(KEY_URL, r.getString("url")).apply()
    }

    /** The next job to print, or null when none came within [wait] seconds. */
    fun next(wait: Int): JSONObject? {
        val data = request("GET", "/api/share/host/next?wait=$wait", null, timeoutMs = (wait + 15) * 1000)
            ?: return null
        return JSONObject(String(data))
    }

    fun bytes(id: Long): ByteArray =
        request("GET", "/api/share/host/job/$id/bytes", null) ?: throw IOException("No print data")

    fun done(id: Long, ok: Boolean, error: String = "") {
        request("POST", "/api/share/host/job/$id/done", JSONObject().put("ok", ok).put("error", error))
    }

    /** Your own print, into the same line as everyone else's. */
    fun addJob(path: String, payload: ByteArray): JSONObject {
        val body = JSONObject()
            .put("path", path)
            .put("bytes", Base64.encodeToString(payload, Base64.NO_WRAP))
        return JSONObject(String(request("POST", "/api/share/host/jobs", body)!!))
    }

    fun queue(): String = String(request("GET", "/api/share/host/queue", null)!!)

    fun cancel(id: Long): String = String(request("POST", "/api/share/host/job/$id/cancel", JSONObject())!!)

    fun clear(): String = String(request("POST", "/api/share/host/clear", JSONObject())!!)

    fun sticker(): ByteArray = request("GET", "/api/share/host/sticker", null) ?: throw IOException("No sticker")

    /** Returns the body, or null for 204 No Content. Throws on errors. */
    private fun request(
        method: String, path: String, body: JSONObject?, auth: Boolean = true, timeoutMs: Int = 20000
    ): ByteArray? {
        if (!available) throw IOException("Sharing isn't set up in this build")
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = timeoutMs
            if (auth) {
                val host = prefs.getString(KEY_HOST, "") ?: ""
                val secret = prefs.getString(KEY_SECRET, "") ?: ""
                conn.setRequestProperty("X-Share-Host", "$host:$secret")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            if (status == 204) return null
            if (status == 401) {
                // The server forgot this phone (or it was reset): register again next time.
                prefs.edit().remove(KEY_HOST).remove(KEY_SECRET).apply()
                throw IOException("Sharing server didn't recognize this phone")
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            if (status !in 200..299) {
                val message = try { JSONObject(String(out.toByteArray())).optString("error") } catch (e: Exception) { "" }
                throw IOException(message.ifBlank { "Sharing server error $status" })
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
