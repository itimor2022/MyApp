package com.bilibili.btc101

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    gotoMain(
                        selectedUrl = result.launchPlan.selectedUrl,
                        selectedIndex = result.launchPlan.selectedIndex,
                        domainsJson = gson.toJson(result.launchPlan.domains),
                        source = result.source
                    )
                }

                is RemoteConfigResult.Error -> {
                    llError.visibility = View.VISIBLE
                    tvStatus.text = result.message
                }
            }
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