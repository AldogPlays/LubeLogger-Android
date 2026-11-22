package app.lubelogger.wrapper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

class MainActivity : Activity() {
    private companion object {
        const val PREFS_NAME = "server_configuration"
        const val PREF_PRIMARY_URL = "primary_url"
        const val PREF_FALLBACK_URL = "fallback_url"
        const val FILE_CHOOSER_REQUEST = 42
        const val STATE_ACTIVE_BASE_URL = "active_base_url"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var activeBaseUrl: String? = null
    private var serverAddresses: ServerAddresses? = null
    private var connectionSession: ConnectionSession? = null
    private var resumed = false
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = connectionHint()
        override fun onLost(network: Network) = connectionHint()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = connectionHint()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val configured = ServerAddresses.parse(
            preferences.getString(PREF_PRIMARY_URL, "").orEmpty(),
            preferences.getString(PREF_FALLBACK_URL, null)
        )
        val addresses = (configured as? AddressConfiguration.Valid)?.addresses
        serverAddresses = addresses
        if (addresses == null) {
            showConfiguration((configured as? AddressConfiguration.Invalid)?.takeIf {
                preferences.contains(PREF_PRIMARY_URL)
            }?.errors)
            return
        }
        var restored: ServerAddress? = null
        val savedAddress = savedInstanceState?.getString(STATE_ACTIVE_BASE_URL)
            ?.let { ServerAddress.parse(it).getOrNull() }?.takeIf { it in addresses.ordered }
        if (savedInstanceState != null && savedAddress != null) {
            showWebView(null)
            val history = webView?.restoreState(savedInstanceState)
            if (history != null && webView?.url?.let(addresses::allowsNavigation) == true) {
                restored = savedAddress
                activeBaseUrl = savedAddress.url
            } else {
                webView?.destroy()
                webView = null
            }
        }
        startSession(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        outState.putString(STATE_ACTIVE_BASE_URL, activeBaseUrl)
        super.onSaveInstanceState(outState)
    }

    private fun startSession(restored: ServerAddress? = null) {
        connectionSession?.close()
        activeBaseUrl = restored?.url
        val addresses = serverAddresses ?: return
        connectionSession = ConnectionSession(
            addresses, AndroidConnectionScheduler(mainHandler), ExecutorConnectionProbe(mainHandler), restored
        ) { decision ->
            when (decision) {
                ConnectionDecision.Checking -> showChecking()
                ConnectionDecision.Unavailable -> showUnavailable()
                is ConnectionDecision.Selected -> {
                    val target = decision.previous?.equivalentPage(webView?.url, decision.address) ?: decision.address.url
                    activeBaseUrl = decision.address.url
                    if (decision.previous == null) showWebView(target) else webView?.loadUrl(target)
                }
            }
        }
        if (resumed) connectionSession?.resume()
    }

    private fun connectionHint() {
        mainHandler.post { connectionSession?.connectionChanged() }
    }

    private fun showConfiguration(errors: List<AddressError>? = null) {
        connectionSession?.close()
        connectionSession = null
        activeBaseUrl = null
        webView?.destroy()
        webView = null
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val primaryInput = EditText(this).apply {
            hint = "https://lubelogger.example.com"
            setText(preferences.getString(PREF_PRIMARY_URL, ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val fallbackInput = EditText(this).apply {
            hint = "Optional second URL"
            setText(preferences.getString(PREF_FALLBACK_URL, ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        errors?.forEach { error ->
            (if (error.field == AddressField.PREFERRED) primaryInput else fallbackInput).error = error.message
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            applySystemBarInsets(this, 48, 48, 48, 48)
            addView(TextView(this@MainActivity).apply {
                text = "Connect to LubeLogger"
                textSize = 24f
            })
            addView(TextView(this@MainActivity).apply {
                text = "Enter your preferred server URL and, optionally, a fallback. HTTPS is recommended. Private CAs installed in Android settings are supported; the certificate must match your server hostname."
                textSize = 16f
                setPadding(0, 16, 0, 20)
            })
            addView(TextView(this@MainActivity).apply { text = "Preferred URL" })
            addView(primaryInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                text = "Fallback URL (optional)"
                setPadding(0, 20, 0, 0)
            })
            addView(fallbackInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(this@MainActivity).apply {
                text = "Save and connect"
                setOnClickListener {
                    when (val result = ServerAddresses.parse(primaryInput.text.toString(), fallbackInput.text.toString())) {
                        is AddressConfiguration.Invalid -> result.errors.forEach { error ->
                            (if (error.field == AddressField.PREFERRED) primaryInput else fallbackInput).error = error.message
                        }
                        is AddressConfiguration.Valid -> {
                            serverAddresses = result.addresses
                            preferences.edit()
                                .putString(PREF_PRIMARY_URL, result.addresses.preferred.url)
                                .putString(PREF_FALLBACK_URL, result.addresses.fallback?.url.orEmpty())
                                .apply()
                            startSession()
                        }
                    }
                }
            })
        })
    }

    private fun showChecking() {
        activeBaseUrl = null
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
                setOnClickListener { connectionSession?.retry() }
            })
            addView(Button(this@MainActivity).apply {
                text = "Configure servers"
                setOnClickListener { showConfiguration() }
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
            // Keep WebView's default TLS-error cancellation. Never add certificate exceptions.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url
                return if (isAllowedOrigin(url)) false else true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) connectionHint()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    connectionHint()
                }
            }

            // Android only invokes this callback on API 27 and newer.
            @android.annotation.TargetApi(27)
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
        return serverAddresses?.allowsNavigation(uri.toString()) == true
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
        resumed = false
        connectionSession?.pause()
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        resumed = true
        connectionSession?.resume()
    }

    override fun onStart() {
        super.onStart()
        if (!networkCallbackRegistered) {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            try {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            } catch (_: RuntimeException) {
                // WebView failures and onResume checks still provide failover.
            }
        }
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            } catch (_: RuntimeException) {
                // Already unregistered by the system.
            }
            networkCallbackRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        connectionSession?.close()
        connectionSession = null
        mainHandler.removeCallbacksAndMessages(null)
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
