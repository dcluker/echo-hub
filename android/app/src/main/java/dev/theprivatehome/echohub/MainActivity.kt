package dev.theprivatehome.echohub

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var errorView: View? = null
    private var dashView: WebView? = null      // Home Assistant "living room" page; built on demand, destroyed on leave (RAM)
    private var dashChip: TextView? = null
    private var retries = 0
    private var lanReauthTried = false
    private var mainFrameFailed = false
    private var lastRetryAt = 0L

    private var startUrl = Net.myflixBase + "/echo"
    private val myflixHost: String = Uri.parse(Net.myflixBase).host ?: ""
    private var lanFetched = false

    private fun isMyflixHost(h: String?) = h != null && (h == myflixHost || Net.isLanHost(h))
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lastCookieRefresh = 0L
    private var authFailed = false
    private var goHomePending = false
    private var pendingMic: android.webkit.PermissionRequest? = null

    companion object {
        private const val DOORBELL_MS = 60_000L   // how long the doorbell camera stays up before returning to the library
        // The CF_Authorization cookie lasts ~2 days; renew well inside that so page fetches never
        // run on an expired session (only the main-frame load carries the service-token headers).
        private const val COOKIE_REFRESH_MS = 2 * 60 * 60 * 1000L

        /** Launch extra (`adb shell am start -n .../.Home --ez clear_cache true`): see [clearCachesOnly]. */
        const val EXTRA_CLEAR_CACHE = "clear_cache"
    }

    /**
     * Dev/test aid, triggered by the [EXTRA_CLEAR_CACHE] launch extra. Deletes the contents of cacheDir
     * and codeCacheDir (this includes the WebView HTTP cache, so stale JS/CSS is dropped). Cookies,
     * localStorage/WebStorage, shared preferences and the pairing credentials are never touched.
     * Run before the WebView exists so Chromium is not using the files; [clearWebViewCache] follows.
     */
    private fun clearCachesOnly() {
        listOfNotNull(cacheDir, codeCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    private fun clearWebViewCache() {
        webView.clearCache(true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Net.paired) {
            PairActivity.start(this)
            finish()
            return
        }
        val clearCache = savedInstanceState == null && intent?.getBooleanExtra(EXTRA_CLEAR_CACHE, false) == true
        if (clearCache) clearCachesOnly()
        // Inspectable over ADB (chrome://inspect) in debug builds only.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgentString + " MyFlixEcho"
            }
            webViewClient = Client()
            webChromeClient = object : android.webkit.WebChromeClient() {
                // Voice search: the /echo page may use the microphone; the Android permission is asked once, on first use.
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    main.post {
                        val fromMyflix = isMyflixHost(request.origin?.host)
                        val wantsAudio = request.resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        if (!fromMyflix || !wantsAudio) { request.deny(); return@post }
                        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingMic = request
                            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
                        }
                    }
                }
            }
            addJavascriptInterface(Bridge(), "EchoHub")
        }
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        if (clearCache) clearWebViewCache()
        applyImmersive()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else loadStart()
        watchNetwork()
        webView.requestFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val req = pendingMic ?: return
        pendingMic = null
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        else req.deny()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ---- Follow the network: re-pick LAN vs public when the internet drops or returns ----
    private var netCb: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastValidated: Boolean? = null
    private var lastRebase = 0L

    /** Re-probe and reload only if the best base changed (a page loaded from a dead origin never recovers on its own). */
    private fun rebaseIfNeeded() {
        io.execute {
            Net.resetProbe()
            val nb = Net.activeBase()
            main.post { if (!isDestroyed && !startUrl.startsWith(nb)) loadStart() }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
                val ok = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val was = lastValidated
                lastValidated = ok
                if (was != null && was != ok) main.post {
                    if (ok && !Net.hasLanSecret) lanFetched = false   // back online: fetch the missing LAN secret
                    rebaseIfNeeded()
                }
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCb = cb } catch (_: Exception) { }
    }

    private fun stopWatchingNetwork() {
        val cb = netCb ?: return
        netCb = null
        try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) { }
    }

    private fun loadStart() {
        mainFrameFailed = false
        lastCookieRefresh = android.os.SystemClock.elapsedRealtime()
        io.execute {
            if (Net.paired && !lanFetched) {
                Net.refreshLanCredential()
                lanFetched = Net.hasLanSecret   // offline with no secret yet: try again on the next load
            }
            val url = Net.activeBase() + "/echo"
            main.post {
                if (isDestroyed) return@post
                startUrl = url
                webView.loadUrl(url, Net.authHeadersFor(url))
            }
        }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onResume() {
        super.onResume()
        if (!::webView.isInitialized) return
        webView.onResume()
        if (goHomePending) goHomeNow()
        if (android.os.SystemClock.elapsedRealtime() - lastCookieRefresh > 30 * 60 * 1000L) refreshCookie()
        main.removeCallbacks(cookieTick)
        main.postDelayed(cookieTick, COOKIE_REFRESH_MS)
    }

    /** The remote's Home key (also fires when this app starts an activity without NO_USER_ACTION). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        android.util.Log.i("MyFlix", "onUserLeaveHint -> go home on return")
        goHomePending = true
    }

    private fun goHomeNow() {
        goHomePending = false
        webView.evaluateJavascript("window.__myflixHome&&window.__myflixHome()", null)
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            main.removeCallbacks(cookieTick)
            webView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopWatchingNetwork()
        if (::webView.isInitialized) {
            CookieManager.getInstance().flush()
            webView.destroy()
        }
        io.shutdown()
        super.onDestroy()
    }

    // ---- Access session cookie ----

    private val cookieTick = object : Runnable {
        override fun run() {
            refreshCookie()
            main.postDelayed(this, COOKIE_REFRESH_MS)
        }
    }

    /** Renews CF_Authorization using the service-token headers; the page itself is untouched. */
    private fun refreshCookie() {
        lastCookieRefresh = android.os.SystemClock.elapsedRealtime()
        if (Net.lanActive) return   // LAN session is a server cookie
        io.execute {
            val cookies = try { Net.fetchAccessCookies() } catch (_: Exception) { return@execute }
            main.post {
                if (isDestroyed) return@post
                val cm = CookieManager.getInstance()
                cookies.forEach { cm.setCookie(Net.myflixBase, it) }
                cm.flush()
            }
        }
    }

    // ---- Menu: update / re-pair ----

    private fun showMenu() {
        val c = Net.creds
        val who = if (c != null && c.name.isNotEmpty()) "paired as ${c.name}" else "paired"
        AlertDialog.Builder(this)
            .setTitle("Echo Hub ${BuildConfig.VERSION_NAME} ($who)")
            .setItems(arrayOf("Reload", "Re-pair / sign out")) { _, which ->
                if (which == 0) loadStart() else confirmSignOut()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of MyFlix?")
            .setMessage("This removes the pairing from this device. You will need a new code to connect again." +
                if (Net.lanActive || lastValidated == false) "\n\nPairing needs the internet. Without it the Echo can't connect again until the internet is back." else "")
            .setPositiveButton("Sign out") { _, _ -> signOut() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOut() {
        Net.setCreds(null)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData() // drops the saved profile choice
        webView.clearCache(true)
        PairActivity.start(this)
        finish()
    }

    // ---- Retry / error handling ----

    private fun retryOrFail() {
        if (retries < 2) {
            retries++
            lastRetryAt = android.os.SystemClock.elapsedRealtime()
            loadStart()
        } else {
            showError()
        }
    }

    private fun showError() {
        if (errorView != null) return
        val msg = TextView(this).apply {
            text = if (authFailed) "Echo Hub wasn't accepted.\nIt may have been removed. Try again, or re-pair it with a new code."
            else "Can't reach MyFlix.\nCheck the network connection and try again."
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val btn = Button(this).apply {
            text = "Retry"
            isFocusable = true
            setOnClickListener {
                removeError()
                retries = 0
                authFailed = false
                loadStart()
            }
        }
        val repair = Button(this).apply {
            text = "Re-pair / sign out"
            isFocusable = true
            setOnClickListener { confirmSignOut() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(msg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(btn, LinearLayout.LayoutParams(480, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 48 })
            addView(repair, LinearLayout.LayoutParams(480, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
        }
        errorView = box
        root.addView(box, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        btn.requestFocus()
    }

    private fun removeError() {
        // Only hand focus back when an error screen actually had it: requestFocus() on a WebView
        // that is already focused makes Chromium jump to the page's first control, and this runs
        // on every in-page navigation (opening or closing a title).
        val view = errorView ?: return
        root.removeView(view)
        errorView = null
        webView.requestFocus()
    }

    // ---- Living-room dashboard (Home Assistant) ----

    /** Exposed to the MyFlix /echo page only: off-site navigation is blocked, so nothing else can reach it. */
    private inner class Bridge {
        @JavascriptInterface
        fun openDashboard() { main.post { showDashboard(BuildConfig.DASH_URL, 0L) } }

        /** Doorbell pressed (the page polls the server): wake the screen and show the camera tab for a minute. Silent. */
        @JavascriptInterface
        fun showDoorbell() { main.post { showDashboard(BuildConfig.DOORBELL_URL, DOORBELL_MS) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun showDashboard(url: String, autoCloseMs: Long) {
        if (url.isBlank()) return   // ECHOHUB_DASH_URL / ECHOHUB_DOORBELL_URL not configured: the Room view is off
        main.removeCallbacks(autoClose)
        if (autoCloseMs > 0) {
            // Wake the screen for the camera (the activity is the foreground launcher with KEEP_SCREEN_ON; this covers a dimmed screen).
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            main.postDelayed(autoClose, autoCloseMs)
        }
        val open = dashView
        if (open != null) { open.loadUrl(url); return }   // already in Room: just switch it to this page
        val dash = WebView(this).apply {
            setBackgroundColor(0xFF111318.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // Home Assistant keeps its login here; it survives restarts
                mediaPlaybackRequiresUserGesture = true   // the camera feed stays silent: no autoplay audio
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != Uri.parse(BuildConfig.DASH_URL).host   // stay on the Home Assistant host
            }
            loadUrl(url)
        }
        val chip = TextView(this).apply {
            text = "‹ Library"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xCC000000.toInt()); setStroke(dp(1), 0x66FFFFFF) }
            isClickable = true
            setOnClickListener { hideDashboard() }
        }
        root.addView(dash, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(chip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(10), dp(10))
        })
        dashView = dash
        dashChip = chip
        webView.visibility = View.GONE   // not paused: the library page keeps polling for the doorbell
        dash.requestFocus()
    }

    private val autoClose = Runnable { hideDashboard() }

    private fun hideDashboard() {
        val dash = dashView ?: return
        root.removeView(dash)
        dashChip?.let { root.removeView(it) }
        CookieManager.getInstance().flush()
        dash.destroy()   // frees the heavy Home Assistant frontend; the login is kept in storage
        dashView = null
        dashChip = null
        main.removeCallbacks(autoClose)
        webView.visibility = View.VISIBLE
        webView.requestFocus()
    }

    // ---- Back key ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) showMenu()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled) {
            if (dashView != null) hideDashboard()
            return true   // launcher: Back otherwise does nothing
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---- WebViewClient ----

    private inner class Client : WebViewClient() {
        private fun isAccessLogin(host: String?) =
            host != null && (host == "cloudflareaccess.com" || host.endsWith(".cloudflareaccess.com"))

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val host = uri.host
            if (isAccessLogin(host)) {
                retryOrFail()
                return true
            }
            if (!isMyflixHost(host)) return true // block everything off-site
            val path = uri.path ?: ""
            if (path.startsWith("/play/")) return true   // the Echo never plays video locally
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (isAccessLogin(Uri.parse(url ?: "").host)) {
                view.stopLoading()
                retryOrFail()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            CookieManager.getInstance().flush()
            val settled = android.os.SystemClock.elapsedRealtime() - lastRetryAt > 2000
            if (!mainFrameFailed && settled && isMyflixHost(Uri.parse(url ?: "").host)) {
                retries = 0
                lanReauthTried = false
                authFailed = false
                removeError()
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            val denied = errorResponse.statusCode == 401 || errorResponse.statusCode == 403
            if (request.isForMainFrame && errorResponse.statusCode >= 500 && request.url.host == myflixHost) {
                rebaseIfNeeded()   // Cloudflare up but the tunnel / origin is not (502/530): try the LAN server
                return
            }
            if (request.isForMainFrame && denied) {
                if (Net.isLanHost(request.url.host)) {
                    if (!lanReauthTried) {   // a reload carries only the cookie: log in again with the device header
                        lanReauthTried = true
                        lastRetryAt = android.os.SystemClock.elapsedRealtime()
                        loadStart()
                        return
                    }
                    Net.dropLan()   // the header was refused too: try the public URL (secret kept)
                    lanFetched = false
                }
                mainFrameFailed = true
                authFailed = true
                retryOrFail()
            } else if (denied && request.url.host == myflixHost && !Net.lanActive &&
                android.os.SystemClock.elapsedRealtime() - lastCookieRefresh > 60_000L
            ) {
                // A page request lost its Access session: renew the cookie so the next one succeeds.
                refreshCookie()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            if (failingUrl == view.url || failingUrl == startUrl) {
                mainFrameFailed = true
                showError()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame && request.url.host == myflixHost && !Net.lanActive &&
                android.os.SystemClock.elapsedRealtime() - lastRebase > 15_000L
            ) {
                lastRebase = android.os.SystemClock.elapsedRealtime()
                rebaseIfNeeded()
            }
            if (request.isForMainFrame) {
                mainFrameFailed = true
                showError()
            }
        }
    }
}
