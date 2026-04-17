package com.bilibili.bld101

import android.content.Context
import com.google.gson.Gson

object ConfigCache {

    private const val SP_NAME = "remote_config_cache"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_CONFIG_SHA256 = "config_sha256"
    private const val KEY_LAST_GOOD_URL = "last_good_url"

    private val gson by lazy { Gson() }

    fun save(context: Context, config: RemoteConfig, plainText: String) {
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG_JSON, gson.toJson(config))
            .putString(KEY_CONFIG_SHA256, CryptoManager.sha256(plainText))
            .apply()
    }

    fun read(context: Context): RemoteConfig? {
        val sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_CONFIG_JSON, null) ?: return null
        return runCatching {
            gson.fromJson(raw, RemoteConfig::class.java)
        }.getOrNull()
    }

    fun saveLastGoodUrl(context: Context, url: String) {
        if (url.isBlank()) return
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GOOD_URL, url)
            .apply()
    }

    fun readLastGoodUrl(context: Context): String {
        return context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_GOOD_URL, "")
            .orEmpty()
    }
}