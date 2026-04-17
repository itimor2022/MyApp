package com.bilibili.btc101

import android.util.Log

object LogUtil {

    private val ENABLE_LOG = BuildConfig.DEBUG

    fun e(tag: String, msg: String) {
        if (ENABLE_LOG) {
            Log.e(tag, msg)
        }
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (ENABLE_LOG) {
            Log.e(tag, msg, tr)
        }
    }

    fun d(tag: String, msg: String) {
        if (ENABLE_LOG) {
            Log.d(tag, msg)
        }
    }

    fun i(tag: String, msg: String) {
        if (ENABLE_LOG) {
            Log.i(tag, msg)
        }
    }

    fun w(tag: String, msg: String) {
        if (ENABLE_LOG) {
            Log.w(tag, msg)
        }
    }
}