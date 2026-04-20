package com.obs.yl

import android.content.Context
import java.io.File

object LastUrlCache {

    private const val FILE_NAME = "last.txt"

    fun save(context: Context, url: String) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME)
                .writeText(url.trim())
        }
    }

    fun read(context: Context): String? {
        return runCatching {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return null
            val content = file.readText().trim()
            if (content.isBlank()) null else content
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        }
    }
}
