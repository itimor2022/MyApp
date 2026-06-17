package com.obs.yl

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SplashActivity : AppCompatActivity() {

    private lateinit var imvBg: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvReload: TextView
    private lateinit var llError: LinearLayout

    private val repository by lazy { RemoteConfigRepository(applicationContext, App.httpClient) }
    private val gson by lazy { Gson() }

    private var splashJob: Job? = null
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        setContentView(R.layout.activity_splash)

        imvBg = findViewById(R.id.imv_bg)
        tvStatus = findViewById(R.id.tv)
        tvReload = findViewById(R.id.tv_reload)
        llError = findViewById(R.id.ll_error)

        imvBg.visibility = View.VISIBLE
        tvStatus.visibility = View.VISIBLE
        llError.visibility = View.GONE
        tvStatus.text = "正在加载线路，请稍等"

        tvReload.setOnClickListener {
            loadData()
        }

        if (!isNetworkConnected() && ConfigCache.read(this) == null) {
            llError.visibility = View.VISIBLE
            tvStatus.text = "线路加载失败，请检查网络后重试"
            return
        }

        loadData()
    }

    private fun loadData() {
        splashJob?.cancel()
        splashJob = uiScope.launch {
            llError.visibility = View.GONE
            tvReload.isEnabled = false
            tvStatus.text = "正在加载线路，请稍等"

            val result = withContext(Dispatchers.IO) {
                repository.fetchAvailableConfig()
            }

            tvReload.isEnabled = true

            when (result) {
                is RemoteConfigResult.Success -> {
                    showRoutePickerDialog(result)
                }

                is RemoteConfigResult.Error -> {
                    llError.visibility = View.VISIBLE
                    tvStatus.text = result.message
                }
            }
        }
    }

    private val latencyClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private fun measureLatencyMs(baseUrl: String): Long {
        val probeUrl = baseUrl.trimEnd('/') + "/static/icons/icon_star.png"
        return try {
            val request = Request.Builder()
                .url(probeUrl)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36")
                .build()
            val start = System.currentTimeMillis()
            latencyClient.newCall(request).execute().use { }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        }
    }

    private fun getLatencyColor(latencyMs: Long): Int {
        return when {
            latencyMs < 0L -> Color.parseColor("#888888")
            latencyMs < 100L -> Color.parseColor("#1F9D55")
            latencyMs <= 500L -> Color.parseColor("#D4A017")
            else -> Color.parseColor("#D93025")
        }
    }

    private fun showRoutePickerDialog(result: RemoteConfigResult.Success) {
        val domains = result.launchPlan.domains
        if (domains.isEmpty()) {
            gotoMain(
                selectedUrl = result.launchPlan.selectedUrl,
                selectedIndex = result.launchPlan.selectedIndex,
                domainsJson = gson.toJson(domains),
                source = result.source
            )
            return
        }

        val recommended = result.launchPlan.selectedIndex
        val routeNames = Array(domains.size) { i ->
            if (i == recommended) "线路${i + 1}  ★" else "线路${i + 1}"
        }
        val latencyTexts = Array(domains.size) { "检测中..." }
        val latencyValues = LongArray(domains.size) { -2L }
        val selected = intArrayOf(recommended.coerceIn(0, domains.lastIndex))

        val inflater = LayoutInflater.from(this)
        val adapter = object : ArrayAdapter<String>(this, 0, routeNames.toList()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(R.layout.item_route, parent, false)
                view.findViewById<RadioButton>(R.id.rb).isChecked = (position == selected[0])
                view.findViewById<TextView>(R.id.tv_route).text = routeNames[position]
                view.findViewById<TextView>(R.id.tv_latency).apply {
                    text = latencyTexts[position]
                    setTextColor(getLatencyColor(latencyValues[position]))
                }
                return view
            }
        }

        val listView = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                selected[0] = position
                adapter.notifyDataSetChanged()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("选择线路")
            .setView(listView)
            .setPositiveButton("确认") { _, _ ->
                gotoMain(
                    selectedUrl = domains[selected[0]].url,
                    selectedIndex = selected[0],
                    domainsJson = gson.toJson(domains),
                    source = result.source
                )
            }
            .setNegativeButton("取消") { _, _ ->
                gotoMain(
                    selectedUrl = result.launchPlan.selectedUrl,
                    selectedIndex = result.launchPlan.selectedIndex,
                    domainsJson = gson.toJson(domains),
                    source = result.source
                )
            }
            .setCancelable(false)
            .show()

        // 并发探测所有线路延迟，完成后统一刷新
        uiScope.launch {
            val results = domains.mapIndexed { index, item ->
                async(Dispatchers.IO) { index to measureLatencyMs(item.url) }
            }.awaitAll()
            results.forEach { (index, ms) ->
                latencyValues[index] = ms
                latencyTexts[index] = if (ms >= 0L) "${ms}ms" else "超时"
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun gotoMain(
        selectedUrl: String,
        selectedIndex: Int,
        domainsJson: String,
        source: String
    ) {
        if (selectedUrl.isBlank()) {
            llError.visibility = View.VISIBLE
            tvStatus.text = "当前线路不可用，请稍后重试"
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("url", selectedUrl)
                .putExtra("selected_index", selectedIndex)
                .putExtra("domains_json", domainsJson)
                .putExtra("config_source", source)
        )
        finish()
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE)
        if (connectivityManager is ConnectivityManager) {
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo?.isAvailable ?: false
        }
        return false
    }

    override fun onDestroy() {
        splashJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }
}