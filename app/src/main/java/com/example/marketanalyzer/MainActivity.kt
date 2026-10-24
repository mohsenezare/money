package com.example.marketanalyzer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.WebViewAssetLoader
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var preferences: SharedPreferences? = null
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
        .build()

    private var socket: WebSocket? = null
    private var socketGeneration = 0
    private var historyGeneration = 0
    private var reconnectCount = 0
    private var closed = false
    private var selectedSymbol = "XAUUSD"
    private var pageReady = false
    private var reconnectTask: Runnable? = null
    private var freeFeedTask: Runnable? = null
    private var freePollCount = 0
    private val freeFeedBase = "https://biquote.io/api/"
    private val freeFeedIntervalMs = 12000L
    private var freeFeedGeneration = 0
    private var freeQuoteCall: Call? = null
    private var freeHistoryCall: Call? = null
    private val freeLastHistoryMs = mutableMapOf<String, Long>()
    private var appInForeground = false

    private val logicalSymbols = listOf("XAUUSD", "EURUSD", "BTCUSD")
    private val officialTerminalUrl = "https://my.litefinance.org/trading"
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Alerts still display in-page when notification permission is denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            preferences = EncryptedSharedPreferences.create(
                this,
                "market_analyzer_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            Toast.makeText(this, "خطای ذخیره امن اطلاعات حساب", Toast.LENGTH_LONG).show()
        }
        createNotificationChannel()
        webView = findViewById(R.id.webView)
        setupWebView()
    }

    private fun apiKey(): String = preferences?.getString("api_key", null).orEmpty().trim()
    private fun accountId(): String = preferences?.getString("account_id", null).orEmpty().trim()
    private fun isConfigured(): Boolean =
        preferences?.getBoolean("api_enabled", false) == true &&
            apiKey().isNotEmpty() && accountId().isNotEmpty()

    private fun brokerSymbol(logical: String): String =
        preferences?.getString("symbol_$logical", logical)?.trim().takeUnless { it.isNullOrBlank() } ?: logical

    private fun updateBrokerSymbols() {
        if (!pageReady) return
        val mapping = logicalSymbols.associateWith { brokerSymbol(it) }
        callJs("onBrokerSymbols", gson.toJson(mapping))
    }

    private fun callJs(method: String, argument: String) {
        if (closed) return
        handler.post {
            if (pageReady && !closed) {
                webView.evaluateJavascript("window.$method(${gson.toJson(argument)});", null)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.orEmpty().startsWith("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/")) return
                pageReady = true
                updateBrokerSymbols()
                if (isConfigured()) {
                    stopFreeFeed()
                    requestAlertPermissionIfNeeded()
                    callJs("onAppMode", "api")
                    refreshAll()
                    connectWebSocket()
                } else {
                    callJs("onAppMode", "free")
                    startFreeFeed()
                }
            }
        }
        webView.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
    }

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun configure() = runOnUiThread { showConfigDialog() }

        @android.webkit.JavascriptInterface
        fun openLiteFinance() = runOnUiThread { openOfficialWebTerminal() }

        @android.webkit.JavascriptInterface
        fun useFreeMode() = runOnUiThread { activateFreeMode() }

        @android.webkit.JavascriptInterface
        fun selectSymbol(symbol: String) = runOnUiThread {
            if (symbol !in logicalSymbols) return@runOnUiThread
            selectedSymbol = symbol
            if (isConfigured()) {
                refreshAll()
            } else {
                freeLastHistoryMs.remove(symbol)
                fetchFreeHistory(symbol)
                fetchFreeQuote(symbol)
            }
        }

        @android.webkit.JavascriptInterface
        fun refresh() = runOnUiThread {
            if (isConfigured()) refreshAll()
            else {
                freeLastHistoryMs.remove(selectedSymbol)
                fetchFreeHistory(selectedSymbol)
                fetchFreeQuote(selectedSymbol)
            }
        }

        @android.webkit.JavascriptInterface
        fun onTrendBreak(symbol: String, trendType: String, count: Int) {
            runOnUiThread {
                if (symbol in logicalSymbols && count in 1..10) {
                    showNotification("شکست خط روند - $symbol", "$count خط روند $trendType شکسته شد.")
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun onRsiBreak(symbol: String, level: String) {
            runOnUiThread {
                if (symbol in logicalSymbols) showNotification("RSI - $symbol", "عبور RSI از $level")
            }
        }
    }

    /**
     * Open the official broker site in the user's browser session, not our own
     * JavaScript-enabled WebView. This isolates LiteFinance credentials and
     * cookies from AndroidBridge / analytics / JavaScript in Market Analyzer.
     */
    private fun openOfficialWebTerminal() {
        val uri = Uri.parse(officialTerminalUrl)
        if (uri.scheme != "https" || uri.host != "my.litefinance.org") {
            Toast.makeText(this, "آدرس وب‌ترمینال معتبر نیست", Toast.LENGTH_LONG).show()
            return
        }
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: Exception) {
                Toast.makeText(this, "مرورگر مطمئن روی گوشی پیدا نشد", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "امکان باز کردن وب‌ترمینال وجود ندارد", Toast.LENGTH_LONG).show()
        }
    }

    private fun activateFreeMode() {
        preferences?.edit()?.putBoolean("api_enabled", false)?.apply()
        socketGeneration++
        historyGeneration++
        reconnectTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
        socket?.close(1000, "Switch to official LiteFinance web terminal")
        socket = null
        callJs("onAppMode", "free")
        startFreeFeed()
    }

    private fun showConfigDialog() {
        if (isFinishing || isDestroyed || preferences == null) return
        val context = this
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val d = (16 * resources.displayMetrics.density).toInt()
            setPadding(d, d / 2, d, d / 2)
        }
        fun field(hint: String, value: String, password: Boolean = false): EditText {
            return EditText(context).apply {
                this.hint = hint
                setSingleLine()
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
                setText(value)
                stack.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val key = field("API key (در صورت ذخیره قبلی خالی بگذارید)", "", true)
        val account = field("Account ID", accountId())
        val gold = field("نماد طلا در بروکر", brokerSymbol("XAUUSD"))
        val eur = field("نماد یورو در بروکر", brokerSymbol("EURUSD"))
        val btc = field("نماد بیت‌کوین در بروکر", brokerSymbol("BTCUSD"))
        val scroll = ScrollView(context).apply { addView(stack) }

        AlertDialog.Builder(context)
            .setTitle("تنظیمات اتصال MetaTrader API")
            .setMessage("کلید فقط روی همین گوشی ذخیره می‌شود؛ آن را در گیت‌هاب منتشر نکنید.")
            .setView(scroll)
            .setNegativeButton("بعداً", null)
            .setPositiveButton("ذخیره و اتصال") { _, _ ->
                val newKey = key.text.toString().trim().ifEmpty { apiKey() }
                val newAccount = account.text.toString().trim()
                if (newKey.isBlank() || newAccount.isBlank()) {
                    callJs("onConnectionState", "کلید یا حساب وارد نشده")
                    Toast.makeText(this, "API key و Account ID لازم است", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                preferences?.edit()
                    ?.putBoolean("api_enabled", true)
                    ?.putString("api_key", newKey)
                    ?.putString("account_id", newAccount)
                    ?.putString("symbol_XAUUSD", gold.text.toString().trim().ifEmpty { "XAUUSD" })
                    ?.putString("symbol_EURUSD", eur.text.toString().trim().ifEmpty { "EURUSD" })
                    ?.putString("symbol_BTCUSD", btc.text.toString().trim().ifEmpty { "BTCUSD" })
                    ?.apply()
                updateBrokerSymbols()
                stopFreeFeed()
                callJs("onAppMode", "api")
                requestAlertPermissionIfNeeded()
                reconnectCount = 0
                connectWebSocket()
                refreshAll()
            }.show()
    }

    // Public market-data fallback. Not connected to the user's LiteFinance account.
    // Prices must be labelled as public data: broker execution quotes may differ.
    private fun startFreeFeed() {
        if (!pageReady || closed || !appInForeground || isConfigured()) return
        stopFreeFeed()
        val generation = freeFeedGeneration
        freePollCount = 0
        requestAlertPermissionIfNeeded()
        callJs("onConnectionState", "دریافت داده عمومی...")
        fetchFreeHistory(selectedSymbol)
        fetchFreeQuote(selectedSymbol)
        fun runSchedule() {
            val task = Runnable {
                if (closed || !appInForeground || isConfigured() ||
                    generation != freeFeedGeneration) return@Runnable
                freePollCount++
                fetchFreeQuote(selectedSymbol)
                if (freePollCount % 5 == 0) fetchFreeHistory(selectedSymbol)
                runSchedule()
            }
            freeFeedTask = task
            handler.postDelayed(task, freeFeedIntervalMs)
        }
        runSchedule()
    }

    private fun stopFreeFeed() {
        freeFeedGeneration++
        freeFeedTask?.let { handler.removeCallbacks(it) }
        freeFeedTask = null
        freeQuoteCall?.cancel()
        freeQuoteCall = null
        freeHistoryCall?.cancel()
        freeHistoryCall = null
    }

    private fun fetchFreeHistory(logical: String) {
        if (logical !in logicalSymbols || isConfigured() || !appInForeground) return
        val generation = freeFeedGeneration
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        // Avoid overlapping repeated history requests.
        val recentAt = freeLastHistoryMs[logical] ?: 0L
        if (requestedAt - recentAt < 45000L) return
        freeLastHistoryMs[logical] = requestedAt
        val url = (freeFeedBase + logical + "/ohlc").toHttpUrl().newBuilder()
            .addQueryParameter("interval", "1m")
            .addQueryParameter("limit", "400")
            .build()
        val request = Request.Builder().get().url(url).build()
        freeHistoryCall?.cancel()
        freeHistoryCall = http.newCall(request)
        freeHistoryCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (generation == freeFeedGeneration && !call.isCanceled() && logical == selectedSymbol) {
                    freeLastHistoryMs.remove(logical)
                    callJs("onHistoryError", "تاریخچه قیمت عمومی در دسترس نیست")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (generation != freeFeedGeneration || logical != selectedSymbol || isConfigured()) return
                    if (!response.isSuccessful) {
                        freeLastHistoryMs.remove(logical)
                        callJs("onHistoryError", "خطای دریافت کندل: HTTP ${response.code}")
                        return
                    }
                    val raw = response.body?.string().orEmpty()
                    try {
                        val doc = JsonParser.parseString(raw).asJsonObject
                        val bars = doc.getAsJsonArray("bars")
                        if (bars == null || bars.size() == 0) {
                            freeLastHistoryMs.remove(logical)
                            callJs("onHistoryError", "کندل معتبر دریافت نشد")
                            return
                        }
                        val envelope = gson.toJson(mapOf("symbol" to logical, "history" to raw))
                        callJs("onMarketHistory", envelope)
                    } catch (_: Exception) {
                        freeLastHistoryMs.remove(logical)
                        callJs("onHistoryError", "قالب تاریخچه معتبر نیست")
                    }
                }
            }
        })
    }

    private fun fetchFreeQuote(logical: String) {
        if (logical !in logicalSymbols || isConfigured() || !appInForeground) return
        val generation = freeFeedGeneration
        val url = (freeFeedBase + logical).toHttpUrl()
        val request = Request.Builder().get().url(url).build()
        freeQuoteCall?.cancel()
        freeQuoteCall = http.newCall(request)
        freeQuoteCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (generation == freeFeedGeneration && !call.isCanceled() && logical == selectedSymbol) {
                    callJs("onConnectionState", "داده عمومی در دسترس نیست")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (generation != freeFeedGeneration || logical != selectedSymbol || isConfigured()) return
                    if (!response.isSuccessful) {
                        callJs("onConnectionState", "خطای قیمت: HTTP ${response.code}")
                        return
                    }
                    try {
                        val quote = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                        val bid = quote.get("bid")?.asDouble ?: return
                        val ask = quote.get("ask")?.asDouble ?: return
                        if (!bid.isFinite() || !ask.isFinite() || bid <= 0 || ask <= 0) return
                        quote.addProperty("symbol", logical)
                        val frame = com.google.gson.JsonObject()
                        frame.addProperty("type", "QUOTE")
                        frame.add("data", quote)
                        callJs("onMarketData", gson.toJson(frame))
                        val state = quote.get("marketState")?.asString
                        val stale = quote.get("stale")?.asBoolean ?: false
                        callJs("onConnectionState", if (state == "closed") "بازار بسته" else if (stale)
                            "آخرین قیمت (غیرلحظه‌ای)" else "متصل")
                    } catch (_: Exception) {
                        callJs("onConnectionState", "پاسخ قیمت عمومی نامعتبر است")
                    }
                }
            }
        })
    }

    private fun authenticatedRequest(url: okhttp3.HttpUrl): Request =
        Request.Builder().url(url).header("x-api-key", apiKey()).get().build()

    private fun refreshAll() {
        if (!isConfigured()) {
            callJs("onConnectionState", "تنظیم نشده")
            return
        }
        callJs("onRefreshStarted", "")
        loadHistory(selectedSymbol)
        loadQuote(selectedSymbol)
        if (socket == null) connectWebSocket()
    }

    private fun loadHistory(logical: String) {
        val id = accountId()
        val actual = brokerSymbol(logical)
        val generation = ++historyGeneration
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        val url = "https://api.metatraderapi.net/PriceHistory".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .addQueryParameter("symbol", actual)
            .addQueryParameter("timeFrame", "1")
            .addQueryParameter("from", formatter.format(Date(now - 4L * 24 * 60 * 60 * 1000)))
            .addQueryParameter("to", formatter.format(Date(now)))
            .build()
        http.newCall(authenticatedRequest(url)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (generation == historyGeneration) callJs("onHistoryError", "تاریخچه در دسترس نیست")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (generation != historyGeneration) return
                    if (!response.isSuccessful) {
                        callJs("onHistoryError", "خطای تاریخچه: HTTP ${response.code}")
                        return
                    }
                    val body = response.body?.string().orEmpty()
                    val envelope = gson.toJson(mapOf("symbol" to logical, "history" to body))
                    callJs("onMarketHistory", envelope)
                }
            }
        })
    }

    private fun loadQuote(logical: String) {
        val url = "https://api.metatraderapi.net/GetQuote".toHttpUrl().newBuilder()
            .addQueryParameter("id", accountId())
            .addQueryParameter("symbol", brokerSymbol(logical))
            .addQueryParameter("cached", "0")
            .build()
        http.newCall(authenticatedRequest(url)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { /* WebSocket may remain connected. */ }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) return
                    val body = response.body?.string() ?: return
                    try {
                        val frame = com.google.gson.JsonObject()
                        frame.addProperty("type", "QUOTE")
                        frame.add("data", JsonParser.parseString(body))
                        callJs("onMarketData", gson.toJson(frame))
                    } catch (_: Exception) { /* Do not invent price data. */ }
                }
            }
        })
    }

    private fun connectWebSocket() {
        if (!isConfigured() || closed) return
        reconnectTask?.let { handler.removeCallbacks(it) }
        reconnectTask = null
        socketGeneration++
        val generation = socketGeneration
        val previous = socket
        socket = null
        previous?.close(1000, "Reconnecting")
        callJs("onConnectionState", "در حال اتصال...")
        val url = "https://api.metatraderapi.net/v1/stream".toHttpUrl().newBuilder()
            .addQueryParameter("id", accountId()).build().toString()
            .replaceFirst("https://", "wss://")
        val request = Request.Builder().url(url).header("x-api-key", apiKey()).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != socketGeneration) return
                reconnectCount = 0
                callJs("onConnectionState", "متصل")
                logicalSymbols.forEach { logical ->
                    val message = gson.toJson(mapOf(
                        "action" to "subscribe",
                        "symbol" to brokerSymbol(logical),
                        "timeframe" to "M1"
                    ))
                    webSocket.send(message)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation == socketGeneration) callJs("onMarketData", text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration || closed) return
                socket = null
                callJs("onConnectionState", if (response?.code == 401 || response?.code == 403)
                    "خطای دسترسی به API" else "ارتباط قطع شد")
                if (response?.code != 401 && response?.code != 403) scheduleReconnect(generation)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != socketGeneration || closed) return
                socket = null
                callJs("onConnectionState", "ارتباط قطع شد")
                scheduleReconnect(generation)
            }
        })
    }

    private fun scheduleReconnect(generation: Int) {
        val seconds = (5L * (1L shl reconnectCount.coerceAtMost(3))).coerceAtMost(60L)
        reconnectCount++
        val task = Runnable {
            if (!closed && generation == socketGeneration && isConfigured()) connectWebSocket()
        }
        reconnectTask = task
        handler.postDelayed(task, seconds * 1000)
    }

    private fun requestAlertPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("trend_alert", "Technical Alerts",
                NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, "trend_alert")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    override fun onResume() {
        super.onResume()
        appInForeground = true
        if (pageReady && !isConfigured()) startFreeFeed()
    }

    override fun onPause() {
        appInForeground = false
        stopFreeFeed()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        stopFreeFeed()
        socketGeneration++
        historyGeneration++
        reconnectTask?.let { handler.removeCallbacks(it) }
        socket?.close(1000, "Activity destroyed")
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
