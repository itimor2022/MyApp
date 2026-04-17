package com.obs.yl

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.drake.net.utils.TipUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var lastExitTime: Long = 0

    private lateinit var wb: WebView
    private lateinit var tvReload: TextView
    private lateinit var llError: LinearLayout

    protected var mSwipeBackHelper: SwipeBackHelper? = null

    private val gson by lazy { Gson() }
    private val repository by lazy { RemoteConfigRepository(applicationContext, App.httpClient) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var domains: MutableList<DomainItem> = mutableListOf()
    private var currentIndex: Int = -1
    private var currentUrl: String = ""

    private var bootResolved = false
    private var switching = false
    private var mainFrameFailed = false
    private var currentLoadToken = 0L

    private var probeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        mSwipeBackHelper = SwipeBackHelper(this)

        wb = findViewById(R.id.web)
        tvReload = findViewById(R.id.tv_reload)
        llError = findViewById(R.id.ll_error)

        parseIntentData()
        initWebView()

        if (domains.isNotEmpty() && currentIndex in domains.indices) {
            loadDomainAt(currentIndex)
        } else if (currentUrl.isNotBlank()) {
            loadUrlDirect(currentUrl)
        } else {
            showErrorState()
        }

        onBackPressedDispatcher.addCallback {
            if (wb.canGoBack()) {
                wb.goBack()
                return@addCallback
            }

            if (System.currentTimeMillis() - lastExitTime > 2000) {
                TipUtils.toast("再按一次返回键退出")
                lastExitTime = System.currentTimeMillis()
            } else {
                finish()
            }
        }

        tvReload.setOnClickListener {
            llError.visibility = View.GONE
            retryBootFlow()
        }
    }

    private fun parseIntentData() {
        currentUrl = intent.getStringExtra("url").orEmpty().trim()
        currentIndex = intent.getIntExtra("selected_index", -1)

        val domainsJson = intent.getStringExtra("domains_json").orEmpty()
        if (domainsJson.isNotBlank()) {
            val parsed = runCatching {
                gson.fromJson(domainsJson, Array<DomainItem>::class.java)
                    ?.toList()
                    .orEmpty()
            }.getOrDefault(emptyList())

            domains.clear()
            domains.addAll(parsed)
        }

        if (currentIndex !in domains.indices && domains.isNotEmpty()) {
            currentIndex = 0
            currentUrl = domains[0].url
        }
    }

    private fun initWebView() {
        val setting = wb.settings
        setting.javaScriptEnabled = true
        setting.domStorageEnabled = true
        setting.loadsImagesAutomatically = true
        setting.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        setting.cacheMode = WebSettings.LOAD_DEFAULT
        setting.useWideViewPort = true
        setting.loadWithOverviewMode = true
        setting.allowFileAccess = false
        setting.allowContentAccess = false
        setting.setSupportZoom(false)
        setting.builtInZoomControls = false
        setting.displayZoomControls = false

        wb.isHorizontalScrollBarEnabled = false
        wb.isVerticalScrollBarEnabled = false
        wb.webChromeClient = WebChromeClient()

        wb.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url == currentUrl) {
                    mainFrameFailed = false
                    llError.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (mainFrameFailed) return

                val finishedUrl = url.orEmpty()
                if (finishedUrl.isBlank()) return

                /**
                 * 关键修复：
                 * 有些站点返回 200，但标题/正文写的是 404
                 * 这里再做一次前端内容检测
                 */
                view?.evaluateJavascript(
                    """
                    (function() {
                        var title = document.title || '';
                        var body = (document.body && document.body.innerText) ? document.body.innerText : '';
                        body = body.substring(0, 2000);
                        return JSON.stringify({title:title, body:body});
                    })();
                    """.trimIndent()
                ) { value ->
                    val text = value.orEmpty().lowercase()

                    val hitErrorKeyword =
                        text.contains("404 not found") ||
                                text.contains("not found") ||
                                text.contains("403 forbidden") ||
                                text.contains("forbidden") ||
                                text.contains("502 bad gateway") ||
                                text.contains("503 service unavailable") ||
                                text.contains("系统维护") ||
                                text.contains("页面不存在") ||
                                text.contains("访问被拒绝") ||
                                text.contains("网站暂时无法访问")

                    if (hitErrorKeyword && !bootResolved) {
                        mainFrameFailed = true
                        switchToNextDomainOrFallback()
                        return@evaluateJavascript
                    }

                    if (!mainFrameFailed && !bootResolved) {
                        bootResolved = true
                        if (currentUrl.isNotBlank()) {
                            ConfigCache.saveLastGoodUrl(this@MainActivity, currentUrl)
                        }
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)

                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: -1
                    if (code != 200) {
                        mainFrameFailed = true
                        if (!bootResolved) {
                            switchToNextDomainOrFallback()
                        } else {
                            showErrorState()
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)

                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    if (!bootResolved) {
                        switchToNextDomainOrFallback()
                    } else {
                        showErrorState()
                    }
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                mainFrameFailed = true

                if (!bootResolved) {
                    switchToNextDomainOrFallback()
                } else {
                    showErrorState()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                val targetUrl = request.url.toString()
                return if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
        }
    }

    private fun retryBootFlow() {
        bootResolved = false
        mainFrameFailed = false

        if (domains.isNotEmpty()) {
            loadDomainAt(currentIndex.coerceAtLeast(0))
        } else if (currentUrl.isNotBlank()) {
            loadUrlDirect(currentUrl)
        } else {
            showErrorState()
        }
    }

    private fun loadDomainAt(index: Int) {
        if (index !in domains.indices) {
            showErrorState()
            return
        }

        currentIndex = index
        currentUrl = domains[index].url
        verifyThenLoad(index, currentUrl)
    }

    private fun verifyThenLoad(index: Int, url: String) {
        probeJob?.cancel()

        val loadToken = System.nanoTime()
        currentLoadToken = loadToken
        switching = true
        llError.visibility = View.GONE

        probeJob = uiScope.launch {
            val ok = repository.probeLandingUrl(url)
            if (loadToken != currentLoadToken) return@launch

            if (ok) {
                currentIndex = index
                currentUrl = url
                switching = false
                loadUrlDirect(url)
            } else {
                switching = false
                switchToNextDomainOrFallback()
            }
        }
    }

    private fun loadUrlDirect(url: String) {
        currentUrl = url
        mainFrameFailed = false
        llError.visibility = View.GONE
        wb.stopLoading()
        wb.loadUrl(url)
    }

    private fun switchToNextDomainOrFallback() {
        if (switching) return
        switching = true

        val nextIndex = (currentIndex + 1).coerceAtLeast(0)

        if (nextIndex in domains.indices) {
            switching = false
            loadDomainAt(nextIndex)
            return
        }

        probeJob?.cancel()
        probeJob = uiScope.launch {
            val excluded = domains.map { it.url }.toSet()
            val fallbackPlan = repository.fetchRuntimeFallbackPlan(excluded)
            if (fallbackPlan != null && fallbackPlan.selectedIndex in fallbackPlan.domains.indices) {
                domains.clear()
                domains.addAll(fallbackPlan.domains)
                currentIndex = fallbackPlan.selectedIndex
                currentUrl = fallbackPlan.selectedUrl
                switching = false
                loadDomainAt(currentIndex)
            } else {
                switching = false
                showErrorState()
            }
        }
    }

    private fun showErrorState() {
        llError.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        mSwipeBackHelper?.dispatchTouchEvent(ev) {
            super.dispatchTouchEvent(ev)
        } ?: super.dispatchTouchEvent(ev)

    override fun onDestroy() {
        probeJob?.cancel()
        uiScope.cancel()
        runCatching {
            wb.stopLoading()
            wb.loadUrl("about:blank")
            wb.clearHistory()
            wb.removeAllViews()
            wb.destroy()
        }
        super.onDestroy()
    }
}

fun String.showToast() {
    if (this.isEmpty()) return
    Toast.makeText(App.application, this, Toast.LENGTH_SHORT).show()
}