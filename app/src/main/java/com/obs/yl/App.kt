package com.obs.yl

import android.app.Application
import com.drake.net.NetConfig
import com.drake.net.interfaces.NetErrorHandler
import com.drake.net.okhttp.setErrorHandler
import com.drake.net.okhttp.trustSSLCertificate
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    companion object {
        @JvmStatic
        lateinit var application: App
            private set

        @JvmStatic
        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this

        NetConfig.initialize {
            connectTimeout(8, TimeUnit.SECONDS)
            readTimeout(8, TimeUnit.SECONDS)
            writeTimeout(8, TimeUnit.SECONDS)
            trustSSLCertificate()
            setErrorHandler(NetErrorHandler)
        }
    }
}