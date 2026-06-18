package com.obs.yl

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
            latencyMs == -2L -> Color.parseColor("#7FA6D8")
            latencyMs < 0L -> Color.parseColor("#B8C7DB")
            latencyMs < 100L -> Color.parseColor("#4DD3FF")
            latencyMs <= 500L -> Color.parseColor("#8BB8FF")
            else -> Color.parseColor("#FF8AA1")
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
                val isChecked = (position == selected[0])

                view.isSelected = isChecked
                view.findViewById<RadioButton>(R.id.rb).isChecked = isChecked

                view.findViewById<TextView>(R.id.tv_route).apply {
                    text = routeNames[position]
                    setTextColor(if (isChecked) Color.parseColor("#EEF5FF") else Color.parseColor("#B6C9E8"))
                }

                view.findViewById<TextView>(R.id.tv_latency).apply {
                    text = latencyTexts[position]
                    setTextColor(getLatencyColor(latencyValues[position]))
                }
                return view
            }
        }

        val dialogView = inflater.inflate(R.layout.dialog_route_picker, null, false)
        val listView = dialogView.findViewById<ListView>(R.id.lv_routes).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                selected[0] = position
                adapter.notifyDataSetChanged()
            }
        }
        applyRouteListHeight(listView, domains.size)
        dialogView.findViewById<TextView>(R.id.tv_route_hint).text =
            "已为你推荐线路${recommended.coerceIn(0, domains.lastIndex) + 1}，你也可以手动切换"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(false)

        dialogView.findViewById<TextView>(R.id.tv_confirm).setOnClickListener {
            gotoMain(
                selectedUrl = domains[selected[0]].url,
                selectedIndex = selected[0],
                domainsJson = gson.toJson(domains),
                source = result.source
            )
            dialog.dismiss()
        }

        dialogView.findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
            gotoMain(
                selectedUrl = result.launchPlan.selectedUrl,
                selectedIndex = result.launchPlan.selectedIndex,
                domainsJson = gson.toJson(domains),
                source = result.source
            )
            dialog.dismiss()
        }

        dialog.show()

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

    private fun applyRouteListHeight(listView: ListView, routeCount: Int) {
        val perItemHeight = dpToPx(64f)
        val desiredHeight = (routeCount * perItemHeight) + dpToPx(4f)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        val finalHeight = desiredHeight.coerceAtMost(maxHeight)

        listView.layoutParams = listView.layoutParams.apply {
            height = finalHeight
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
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