package dev.theprivatehome.echohub

import android.content.Context
import android.net.Uri
import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class App : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        Net.init(this)
    }
}

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()
    lateinit var store: Store
        private set

    @Volatile
    var creds: Credentials? = null
        private set

    fun init(ctx: Context) {
        store = Store(ctx.applicationContext)
        creds = store.load()
        loadLan()
    }

    fun setCreds(c: Credentials?) {
        if (c == null) store.clear() else store.save(c)
        creds = c
    }

    val paired: Boolean get() = creds != null

    // ---- LAN fallback (works with the internet down) ----
    // Unlike the TV app this prefers the public https URL while it answers: the /echo page needs a secure
    // context for the microphone (voice search, wake word), which plain-http LAN is not.
    @Volatile private var lanBase: String = ""
    @Volatile private var lanSecret: String = ""
    @Volatile private var probeAt = 0L
    @Volatile private var lanChosen = false

    private fun loadLan() {
        lanBase = store.lanBase
        lanSecret = store.lanSecret
    }

    fun lanHeadersFor(url: String): Map<String, String> {
        val c = creds ?: return emptyMap()
        val b = lanBase
        if (b.isEmpty() || lanSecret.isEmpty() || !(url == b || url.startsWith("$b/"))) return emptyMap()
        return mapOf("X-MyFlix-Device" to "${c.clientId}:$lanSecret")
    }

    /** LAN header when [url] is the LAN server, else the Cloudflare service-token headers. */
    fun authHeadersFor(url: String): Map<String, String> = lanHeadersFor(url).ifEmpty { cfHeadersFor(url) }

    fun isLanHost(host: String?): Boolean = lanBase.isNotEmpty() && host != null && host == Uri.parse(lanBase).host

    val lanActive: Boolean get() = lanChosen

    /** True when [url] (a /healthz) answers 200 "ok" in time. A Cloudflare 502/530 or login redirect means the
     *  public path is broken even though the edge answers, so it does not count. */
    private fun reaches(url: String, ms: Long): Boolean {
        val c = client.newBuilder().callTimeout(ms, TimeUnit.MILLISECONDS).connectTimeout(ms, TimeUnit.MILLISECONDS)
            .followRedirects(false).build()
        return try {
            c.newCall(Request.Builder().url(url).cf(url).build()).execute().use { r ->
                r.code == 200 && r.body?.string()?.trim() == "ok"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Public URL while the Cloudflare edge answers within 1.5 s; otherwise the LAN server if it answers /healthz.
     * Blocking; call off the main thread. Cached for 20 s.
     */
    fun activeBase(): String {
        val b = lanBase
        if (b.isEmpty() || lanSecret.isEmpty() || !Hosts.isPrivateLanBase(b)) return myflixBase
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - probeAt < 20_000L) return if (lanChosen) b else myflixBase
        lanChosen = !reaches("$myflixBase/healthz", 1500) && reaches("$b/healthz", 1000)
        probeAt = now
        return if (lanChosen) b else myflixBase
    }

    /** Forget the cached base choice so the next [activeBase] re-probes (network changed). */
    fun resetProbe() { probeAt = 0L }

    /** The LAN server rejected us (secret rotated / device revoked): use the public URL until re-fetched. */
    fun dropLan() {   // secret kept: a transient LAN 401 must not strand the Echo while the internet is down
        lanChosen = false
        probeAt = android.os.SystemClock.elapsedRealtime()
    }

    val hasLanSecret: Boolean get() = lanSecret.isNotEmpty()

    /** While online: fetch the LAN address + this device's LAN secret. Best effort. */
    fun refreshLanCredential() {
        val base = myflixBase
        if (base.isEmpty()) return
        val url = "$base/api/lan-credential"
        try {
            client.newCall(Request.Builder().url(url).cf(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return
                val j = JSONObject(r.body?.string().orEmpty())
                val lb = j.optString("lanBase").trimEnd('/')
                val ls = j.optString("lanSecret")
                if (lb.isEmpty() || ls.isEmpty() || !Hosts.isPrivateLanBase(lb)) return
                store.lanBase = lb
                store.lanSecret = ls
                loadLan()
                probeAt = 0L
            }
        } catch (_: Exception) {
        }
    }

    val myflixBase: String get() = creds?.myflixBase.orEmpty()
    val deviceLabel: String get() = store.deviceLabel.ifEmpty { Build.MODEL }

    fun pairClient() = PairClient(
        client, BuildConfig.TV_BASE, BuildConfig.HOST_SUFFIX, BuildConfig.DEBUG_ALLOW_HOST,
        BuildConfig.PUBLIC_MYFLIX_BASE, BuildConfig.PUBLIC_JELLYFIN_BASE,
    )

    /**
     * Cloudflare Access service-token headers, only for https hosts under the configured domain
     * (plus the debug-only mock host). Plain-http LAN requests never get them.
     */
    fun cfHeadersFor(url: String): Map<String, String> {
        val c = creds ?: return emptyMap()
        if (!Hosts.cfEligible(url, BuildConfig.HOST_SUFFIX, if (BuildConfig.DEBUG) BuildConfig.DEBUG_ALLOW_HOST else "")) {
            return emptyMap()
        }
        return mapOf(
            "CF-Access-Client-Id" to c.clientId,
            "CF-Access-Client-Secret" to c.clientSecret,
        )
    }

    private fun Request.Builder.cf(url: String): Request.Builder {
        authHeadersFor(url).forEach { (k, v) -> header(k, v) }
        return this
    }

    /** GET /api/playinfo/<id> for the given profile; returns raw JSON text. Throws on failure. */
    fun fetchPlayInfo(itemId: String, profileUserId: String): String {
        val url = "${activeBase()}/api/playinfo/$itemId"
        val req = Request.Builder()
            .url(url)
            .cf(url)
            .header("X-MyFlix-Profile", profileUserId)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw PlayInfoException(r.code, body)
            return body
        }
    }

    /** Image bytes (the title logo shown over the player), or null on any failure. */
    fun fetchImage(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).cf(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    /** Returns the Jellyfin server Id answering at [lanBase], or null (<=800 ms, any failure). */
    fun probeLan(lanBase: String): String? {
        if (!Hosts.isPrivateLanBase(lanBase)) return null
        val c = client.newBuilder().callTimeout(800, TimeUnit.MILLISECONDS)
            .connectTimeout(800, TimeUnit.MILLISECONDS).build()
        return try {
            c.newCall(Request.Builder().url(lanBase.trimEnd('/') + "/System/Info/Public").build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()).optString("Id").ifEmpty { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun jellyfinAuth(token: String): String {
        fun esc(s: String) = s.replace("\"", "").replace("\\", "")
        return "MediaBrowser Client=\"MyFlix TV\", Device=\"${esc(Build.MODEL)}\", " +
            "DeviceId=\"${store.deviceId}\", Version=\"${BuildConfig.VERSION_NAME}\", Token=\"$token\""
    }

    /** Jellyfin report (start/progress/stop); failures are swallowed by the caller. */
    fun postJellyfin(base: String, path: String, token: String, body: String) {
        val url = base + path
        val req = Request.Builder()
            .url(url)
            .cf(url)
            .header("Authorization", jellyfinAuth(token))
            .post(body.toRequestBody(json))
            .build()
        client.newCall(req).execute().use { /* ignore body */ }
    }

    /**
     * Fetches a fresh CF_Authorization cookie with the service-token headers and returns the raw
     * Set-Cookie lines. The WebView keeps working from that cookie between main-frame loads.
     */
    fun fetchAccessCookies(): List<String> {
        val url = "$myflixBase/?tv=1"
        val req = Request.Builder().url(url).cf(url).header("Accept", "text/html").build()
        client.newCall(req).execute().use { r ->
            return r.headers("Set-Cookie").filter { it.startsWith("CF_Authorization=") }
        }
    }
}

class PlayInfoException(val code: Int, body: String) : Exception("HTTP $code") {
    val detail: String = body.take(200)

    /** The server's machine-readable `{"error": "..."}` code, when present. */
    val errorCode: String? = try {
        JSONObject(body).optString("error").ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
