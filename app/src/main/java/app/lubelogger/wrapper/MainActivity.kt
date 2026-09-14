package app.lubelogger.wrapper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private companion object {
        const val LAN_URL = "http://192.168.4.124:30035"
        const val TAILSCALE_URL = "https://truenas-scale.dwelf-degree.ts.net:30335"
        const val FILE_CHOOSER_REQUEST = 42
        const val TIMEOUT_MS = 2500
        const val MAX_HTML_BYTES = 512 * 1024
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val checkGeneration = AtomicInteger()
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            findServer()
        } else {
            showWebView(null)
            webView?.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    private fun findServer() {
        val generation = checkGeneration.incrementAndGet()
        showChecking()
        worker.execute {
            val selected = when {
                validatesAsLubeLogger(LAN_URL) -> LAN_URL
                validatesAsLubeLogger(TAILSCALE_URL) -> TAILSCALE_URL
                else -> null
            }
            runOnUiThread {
                if (generation != checkGeneration.get() || isFinishing || isDestroyed) return@runOnUiThread
                if (selected == null) showUnavailable() else showWebView(selected)
            }
        }
    }

    private fun validatesAsLubeLogger(address: String): Boolean {
        var current = URL(address)
        val requiredOrigin = URI(address).let { Triple(it.scheme, it.host, effectivePort(it)) }

        repeat(4) {
            var connection: HttpURLConnection? = null
            try {
                connection = current.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("User-Agent", "LubeLogger Android")

                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return false
                    val next = current.toURI().resolve(location)
                    if (Triple(next.scheme, next.host, effectivePort(next)) != requiredOrigin) return false
                    current = next.toURL()
                    return@repeat
                }
                if (status !in 200..299) return false

                val contentType = connection.contentType.orEmpty().lowercase()
                if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                    return false
                }
                val html = connection.inputStream.use(::readLimitedHtml)
                return Regex("(?is)<title[^>]*>.*?lube\\s*logger.*?</title>").containsMatchIn(html) ||
                    Regex("(?i)lube\\s*logger").containsMatchIn(html)
            } catch (_: Exception) {
                return false
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun readLimitedHtml(input: InputStream): String {
        val bytes = ByteArray(MAX_HTML_BYTES)
        var total = 0
        while (total < bytes.size) {
            val count = input.read(bytes, total, bytes.size - total)
            if (count < 0) break
            total += count
        }
        return String(bytes, 0, total, Charsets.UTF_8)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    private fun showChecking() {
        webView?.destroy()
        webView = null
        setContentView(centeredLayout().apply {
            addView(ProgressBar(this@MainActivity))
            addView(TextView(this@MainActivity).apply {
                text = "Connecting to LubeLogger…"
                textSize = 18f
                setPadding(0, 24, 0, 0)
            })
        })
    }

    private fun showUnavailable() {
        setContentView(centeredLayout().apply {
            addView(TextView(this@MainActivity).apply {
                text = "LubeLogger unavailable"
                textSize = 22f
            })
            addView(Button(this@MainActivity).apply {
                text = "Retry"
                setOnClickListener { findServer() }
            })
        })
    }

    private fun centeredLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
        applySystemBarInsets(this, 48, 48, 48, 48)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView(initialUrl: String?) {
        val view = WebView(this)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url
                return if (isAllowedOrigin(url)) false else true
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse
            ) {
                callback.backToSafety(true)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        applySystemBarInsets(container)
        setContentView(container)
        if (initialUrl != null) view.loadUrl(initialUrl)
    }

    private fun applySystemBarInsets(
        target: View,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0
    ) {
        target.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                left + insets.systemWindowInsetLeft,
                top + insets.systemWindowInsetTop,
                right + insets.systemWindowInsetRight,
                bottom + insets.systemWindowInsetBottom
            )
            insets
        }
        target.requestApplyInsets()
    }

    private fun isAllowedOrigin(uri: Uri): Boolean {
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        return (uri.scheme == "http" && uri.host == "192.168.4.124" && port == 30035) ||
            (uri.scheme == "https" && uri.host == "truenas-scale.dwelf-degree.ts.net" && port == 30335)
    }

    @Deprecated("Deprecated by Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        fileCallback?.onReceiveValue(result)
        fileCallback = null
    }

    @Deprecated("Deprecated by Android")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        checkGeneration.incrementAndGet()
        worker.shutdownNow()
        webView?.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
