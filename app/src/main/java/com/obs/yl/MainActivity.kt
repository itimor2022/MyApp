package com.obs.yl

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.drake.net.utils.TipUtils
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private var lastExitTime: Long = 0

    private lateinit var wb: WebView
    private lateinit var tvReload: TextView
    private lateinit var llError: LinearLayout

    protected var mSwipeBackHelper: SwipeBackHelper? = null

    // 文件上传相关
    private var uploadMessage: ValueCallback<Uri>? = null
    private var uploadMessageAboveL: ValueCallback<Array<Uri>>? = null
    private val FILECHOOSER_RESULTCODE = 1
    private val REQUEST_SELECT_FILES = 2

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

    // 白屏熔断器：主流程加载超过该时长仍未标记为成功，强制弹错误页
    private val mainHandler = Handler(Looper.getMainLooper())
    private var blankTimeoutToken: Any? = null
    private var blankTimeoutArmed = false
    private val blankTimeoutMs: Long = 15_000L
    private val blankProbeRunnable = Runnable {
        if (!bootResolved && !isFinishing && !isDestroyed) {
            LogUtil.w("MainActivity", "blank-timeout fired, force show error")
            mainFrameFailed = true
            switching = false
            probeJob?.cancel()
            showErrorState()
        }
    }

    private fun armBlankTimeout() {
        cancelBlankTimeout()
        blankTimeoutArmed = true
        blankTimeoutToken = Any().also { mainHandler.postDelayed(blankProbeRunnable, blankTimeoutMs) }
    }

    private fun cancelBlankTimeout() {
        blankTimeoutArmed = false
        blankTimeoutToken?.let { mainHandler.removeCallbacks(blankProbeRunnable) }
        blankTimeoutToken = null
    }

    // 文件选择结果处理
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (uploadMessageAboveL != null) {
            handleFileUploadResult(result.resultCode, result.data, uploadMessageAboveL)
            uploadMessageAboveL = null
        } else if (uploadMessage != null) {
            handleFileUploadResultLegacy(result.resultCode, result.data, uploadMessage)
            uploadMessage = null
        }
    }

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
            LogUtil.d("MainActivity", "tv_reload clicked url=$currentUrl domains=${domains.size} idx=$currentIndex switching=$switching bootResolved=$bootResolved")
            llError.visibility = View.GONE
            // 关键修复：重置所有锁和探测协程，否则会被 switching=true 卡住"无反应"
            switching = false
            bootResolved = false
            mainFrameFailed = false
            probeJob?.cancel()
            probeJob = null
            cancelBlankTimeout()
            // 数据全空时回退到上次成功的 url（不再陷入 else showErrorState 死循环）
            if (currentUrl.isBlank() && domains.isEmpty()) {
                val cached = runCatching { ConfigCache.readLastGoodUrl(this) }.getOrDefault("")
                if (cached.isNotBlank()) {
                    currentUrl = cached
                    currentIndex = -1
                    LogUtil.d("MainActivity", "retry fallback to lastGoodUrl=$currentUrl")
                }
            }
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
        setting.allowFileAccess = true  // 允许文件访问
        setting.allowContentAccess = true  // 允许内容访问
        // 设置文件上传相关
        setting.setSupportZoom(false)
        setting.builtInZoomControls = false
        setting.displayZoomControls = false
        // 启用文本选择/复制
        setting.javaScriptCanOpenWindowsAutomatically = true
        wb.isLongClickable = true
        wb.isHorizontalScrollBarEnabled = false
        wb.isVerticalScrollBarEnabled = false
        wb.isLongClickable = true
        wb.isHapticFeedbackEnabled = true
        wb.isFocusable = true
        wb.isFocusableInTouchMode = true
        wb.webChromeClient = object : WebChromeClient() {

            // Android 3.0+
            fun openFileChooser(uploadMsg: ValueCallback<Uri>) {
                uploadMessage = uploadMsg
                openFileChooser()
            }

            // Android 3.0+
            fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String) {
                uploadMessage = uploadMsg
                openFileChooser()
            }

            // Android 4.1+
            fun openFileChooser(uploadMsg: ValueCallback<Uri>, acceptType: String, capture: String) {
                uploadMessage = uploadMsg
                openFileChooser()
            }

            // Android 5.0+
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                uploadMessageAboveL = filePathCallback
                openFileChooser()
                return true
            }
        }

        wb.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                LogUtil.d("MainActivity", "onPageStarted url=$url currentUrl=$currentUrl")
                if (url == currentUrl) {
                    mainFrameFailed = false
                    llError.visibility = View.GONE
                }
                // 每一次主 frame 开始加载都重新计时，避免上次残留超时提前引爆
                armBlankTimeout()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                LogUtil.d("MainActivity", "onPageFinished url=$url mainFrameFailed=$mainFrameFailed bootResolved=$bootResolved")

                // 关键：一旦到达 onPageFinished，至少主 frame 网络层没报错，
                // 取消本次白屏熔断器，由下面的内容判定来决定是否重新点着
                cancelBlankTimeout()

                if (mainFrameFailed) return

                val finishedUrl = url.orEmpty()
                if (finishedUrl.isBlank()) return

                view?.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var title = document.title || '';
                            var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
                            bodyText = (bodyText || '').substring(0, 2000);
                            var bodyLen = (bodyText || '').trim().length;

                            // DOM 节点数量（剥掉 html/head/meta 后还剩多少）
                            var allTags = document.getElementsByTagName('*');
                            var nodeCount = allTags ? allTags.length : 0;

                            var bodyChildren = (document.body && document.body.children) ? document.body.children.length : 0;

                            // 视口高度
                            var docHeight = Math.max(
                                document.body ? document.body.scrollHeight : 0,
                                document.documentElement ? document.documentElement.scrollHeight : 0
                            );

                            var hasContent = bodyLen > 30 || nodeCount > 5 || docHeight > 100;

                            return JSON.stringify({
                                title: title,
                                body: bodyText,
                                bodyLen: bodyLen,
                                nodeCount: nodeCount,
                                bodyChildren: bodyChildren,
                                docHeight: docHeight,
                                hasContent: hasContent,
                                blankReason: hasContent ? '' : (bodyLen <= 0 ? 'empty_body' : 'too_short')
                            });
                        } catch (e) {
                            return JSON.stringify({error: String(e), hasContent: false});
                        }
                    })();
                    """.trimIndent()
                ) { value ->
                    val raw = value.orEmpty()
                    LogUtil.d("MainActivity", "onPageFinished js result len=${raw.length}")

                    val parsed = runCatching { JSONObject(raw) }.getOrNull()
                    val hasContent = parsed?.optBoolean("hasContent", true) ?: true
                    val blankReason = parsed?.optString("blankReason", "").orEmpty()

                    val text = raw.lowercase()
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

                    // B 节修复：仅当主 frame URL 与 currentUrl 一致时，才认为是"当前要校验的页面"
                    // 否则可能是页面内重定向到登录页/中间页，主 frame 仍可能 200 但不属于当前目标
                    val isOurFrame = finishedUrl == currentUrl

                    if (isOurFrame && !bootResolved && (hitErrorKeyword || !hasContent)) {
                        LogUtil.w(
                            "MainActivity",
                            "page considered failed: hitKeyword=$hitErrorKeyword hasContent=$hasContent reason=$blankReason url=$finishedUrl"
                        )
                        mainFrameFailed = true
                        switchToNextDomainOrFallback()
                        return@evaluateJavascript
                    }

                    if (!mainFrameFailed && !bootResolved && isOurFrame) {
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
                cancelBlankTimeout()

                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: -1
                    LogUtil.w("MainActivity", "onReceivedHttpError code=$code url=${request.url}")
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
                cancelBlankTimeout()

                if (request.isForMainFrame) {
                    LogUtil.w(
                        "MainActivity",
                        "onReceivedError url=${request.url} code=${error?.errorCode} desc=${error?.description}"
                    )
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
                cancelBlankTimeout()
                LogUtil.w("MainActivity", "onReceivedSslError err=$error")
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

    // 打开文件选择器
    private fun openFileChooser() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"  // 可以修改为 "*/*" 允许所有类型，或 "image/*,video/*" 等
        filePickerLauncher.launch(i)
    }

    // Android 5.0+ 处理文件上传结果
    private fun handleFileUploadResult(resultCode: Int, data: Intent?, uploadMessage: ValueCallback<Array<Uri>>?) {
        if (uploadMessage == null) return

        var results: Array<Uri>? = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            val dataString = data.dataString
            val clipData = data.clipData

            if (clipData != null) {
                results = Array(clipData.itemCount) { i ->
                    clipData.getItemAt(i).uri
                }
            } else if (dataString != null) {
                results = arrayOf(Uri.parse(dataString))
            }
        }

        uploadMessage.onReceiveValue(results)
    }

    // 低版本处理文件上传结果
    private fun handleFileUploadResultLegacy(resultCode: Int, data: Intent?, uploadMessage: ValueCallback<Uri>?) {
        if (uploadMessage == null) return

        var result: Uri? = null

        if (resultCode == Activity.RESULT_OK && data != null) {
            val dataString = data.dataString
            if (dataString != null) {
                result = Uri.parse(dataString)
            }
        }

        uploadMessage.onReceiveValue(result)
    }

    private fun retryBootFlow() {
        bootResolved = false
        mainFrameFailed = false
        switching = false
        probeJob?.cancel()
        cancelBlankTimeout()

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
        armBlankTimeout()

        probeJob = uiScope.launch {
            try {
                val ok = repository.probeLandingUrl(url)
                if (loadToken != currentLoadToken) return@launch

                if (ok) {
                    currentIndex = index
                    currentUrl = url
                    loadUrlDirect(url)
                } else {
                    switchToNextDomainOrFallback()
                }
            } catch (t: Throwable) {
                LogUtil.e("MainActivity", "verifyThenLoad error url=$url", t)
                if (loadToken == currentLoadToken) {
                    showErrorState()
                }
            } finally {
                // 关键修复：保证 switching 在异常路径下也被释放，避免卡死
                switching = false
            }
        }
    }

    private fun loadUrlDirect(url: String) {
        currentUrl = url
        mainFrameFailed = false
        llError.visibility = View.GONE
        // 每次正式加载都重新启动白屏熔断器
        armBlankTimeout()
        wb.stopLoading()
        wb.loadUrl(url)
    }

    private fun switchToNextDomainOrFallback() {
        if (switching) return
        switching = true

        try {
            // ✅ 兜底防护：当前 domains 列表已经是单个兜底域名（RemoteConfigRepository 已兜底过一次），
            //     没有别的可切了，直接显示错误页，避免 fetchRuntimeFallbackPlan 返回的兜底再次触发探测循环
            if (domains.size <= 1 && (domains.isEmpty() || isFallbackDomain(domains[0].url))) {
                LogUtil.w("MainActivity", "already on fallback domain, stop probing")
                showErrorState()
                return
            }

            val nextIndex = (currentIndex + 1).coerceAtLeast(0)

            if (nextIndex in domains.indices) {
                loadDomainAt(nextIndex)
                return
            }

            probeJob?.cancel()
            probeJob = uiScope.launch {
                try {
                    val excluded = domains.map { it.url }.toSet()
                    val fallbackPlan = repository.fetchRuntimeFallbackPlan(excluded)
                    if (fallbackPlan != null && fallbackPlan.selectedIndex in fallbackPlan.domains.indices) {
                        // 如果 remote 又返回了兜底单域名 → 不要无脑覆盖，否则会陷入探测循环
                        if (fallbackPlan.domains.size == 1 && isFallbackDomain(fallbackPlan.domains[0].url)) {
                            LogUtil.w("MainActivity", "remote returned fallback only, stop probing")
                            showErrorState()
                            return@launch
                        }
                        domains.clear()
                        domains.addAll(fallbackPlan.domains)
                        currentIndex = fallbackPlan.selectedIndex
                        currentUrl = fallbackPlan.selectedUrl
                        loadDomainAt(currentIndex)
                    } else {
                        showErrorState()
                    }
                } catch (t: Throwable) {
                    LogUtil.e("MainActivity", "fetchRuntimeFallbackPlan error", t)
                    showErrorState()
                } finally {
                    // 关键修复：保证 switching 在异常路径下也被释放
                    switching = false
                }
            }
        } catch (t: Throwable) {
            LogUtil.e("MainActivity", "switchToNextDomainOrFallback sync error", t)
            switching = false
            showErrorState()
        }
    }

    private fun isFallbackDomain(url: String): Boolean {
        // 委托给 RemoteConfigRepository 的 host 比较，避免此处硬编码域名
        return RemoteConfigRepository.isFallbackDomain(url)
    }

    private fun showErrorState() {
        llError.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        mSwipeBackHelper?.dispatchTouchEvent(ev) {
            super.dispatchTouchEvent(ev)
        } ?: super.dispatchTouchEvent(ev)

    override fun onDestroy() {
        // 清理文件上传回调
        uploadMessage?.onReceiveValue(null)
        uploadMessageAboveL?.onReceiveValue(null)
        uploadMessage = null
        uploadMessageAboveL = null

        cancelBlankTimeout()
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