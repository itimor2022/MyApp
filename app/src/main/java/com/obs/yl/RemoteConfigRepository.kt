package com.obs.yl

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RemoteConfigRepository(
    context: Context,
    client: OkHttpClient = defaultHttpClient(),
    private val gson: Gson = Gson()
) {

    private val appContext = context.applicationContext

    private val fetchClient: OkHttpClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val probeClient: OkHttpClient = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build()

    companion object {
        private const val TAG = "DNS_FLOW"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36"

        private val OSS_URLS = listOf(
            "https://cx.njxw2.one/aoa/config.json",
            "https://xf.njxw61.one/aoa/config.json",
            "https://x1.njxw8.one/aoa/config.json",
            "https://cf.njxw4.one/aoa/config.json",
            "https://ic.njxw2.one/aoa/config.json",
            "https://ic.njxw4.one/aoa/config.json",
            "https://ic.njxw8.one/aoa/config.json",
            "https://js.kixkb.icu/aoa/config.json",
            "https://xk.kixkb.icu/aoa/config.json",
            "https://ic.kixkb.icu/aoa/config.json",
            "https://ic.t39m0.icu/aoa/config.json",
            "https://mk.t39m0.icu/aoa/config.json",
        )

        private val DNS_TXT_DOMAINS = listOf(
            "cfg.66362.xyz",
            "cfg.66325.xyz",
            "cfg.66215.xyz"
        )

        private val LOCAL_FALLBACK_DOMAINS = listOf(
            DomainItem("https://66.212.58.222", 100),
            DomainItem("https://147.92.38.233", 95),
        )

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    suspend fun fetchAvailableConfig(): RemoteConfigResult = withContext(Dispatchers.IO) {

        // ══════════════════════════════════════════════════════
        // 阶段1：优先读 last.txt，直接 probe，通了就返回，极速启动
        // ══════════════════════════════════════════════════════
        val lastUrl = LastUrlCache.read(appContext)
        if (!lastUrl.isNullOrBlank()) {
            log("last.txt 命中，探测 lastUrl=$lastUrl")
            val alive = runCatching { checkLandingUrl(lastUrl) }.getOrDefault(false)
            if (alive) {
                log("last.txt 域名存活，直接返回 lastUrl=$lastUrl")
                val cachedConfig = ConfigCache.read(appContext) ?: localFallbackConfig()
                val mergedDomains = mergeDomains(
                    primary = listOf(DomainItem(lastUrl, weight = 999)),
                    backup = cachedConfig.data.domains,
                    preferredUrl = lastUrl
                )
                return@withContext RemoteConfigResult.Success(
                    config = cachedConfig,
                    source = "LAST_URL_CACHE",
                    launchPlan = LaunchPlan(
                        domains = mergedDomains,
                        selectedIndex = 0,
                        selectedUrl = lastUrl
                    )
                )
            } else {
                log("last.txt 域名失效，清除并走全链路 lastUrl=$lastUrl")
                LastUrlCache.clear(appContext)
            }
        }

        // ══════════════════════════════════════════════════════
        // 阶段2：全链路拉取 — 逻辑与原来完全一致，成功后写 last.txt
        // ══════════════════════════════════════════════════════
        val lastGoodUrl = ConfigCache.readLastGoodUrl(appContext)
        val sources = mutableListOf<SourceConfig>()

        val cached = ConfigCache.read(appContext)
        if (cached != null && !isExpired(cached)) {
            log("add source CACHE")
            sources += SourceConfig("CACHE", cached)
        }

        for (url in OSS_URLS) {
            val config = runCatching { fetchConfigFromUrl(url) }.getOrNull()
            if (config != null) {
                log("add source OSS url=$url")
                sources += SourceConfig("OSS", config)
            }
        }

        for (domain in DNS_TXT_DOMAINS) {
            val config = runCatching { fetchConfigFromDns(domain) }.getOrNull()
            if (config != null) {
                log("add source DNS_TXT domain=$domain")
                sources += SourceConfig("DNS_TXT", config)
            }
        }

        sources += SourceConfig("LOCAL", localFallbackConfig())

        val triedDomainUrls = linkedSetOf<String>()

        for ((index, source) in sources.withIndex()) {
            log("try source=${source.source}, index=$index")

            val primaryPlan = buildLaunchPlan(
                domains = source.config.data.domains,
                preferredUrl = lastGoodUrl,
                excludedUrls = triedDomainUrls
            )

            if (primaryPlan != null) {
                // ✅ 找到可用域名，写入 last.txt
                LastUrlCache.save(appContext, primaryPlan.selectedUrl)
                log("写入 last.txt url=${primaryPlan.selectedUrl}")

                val remainingBackupDomains = sources
                    .drop(index + 1)
                    .flatMap { it.config.data.domains }

                val mergedDomains = mergeDomains(
                    primary = primaryPlan.domains,
                    backup = remainingBackupDomains,
                    preferredUrl = lastGoodUrl
                )

                val finalSelectedIndex =
                    mergedDomains.indexOfFirst { it.url == primaryPlan.selectedUrl }

                return@withContext RemoteConfigResult.Success(
                    config = source.config,
                    source = source.source,
                    launchPlan = LaunchPlan(
                        domains = mergedDomains,
                        selectedIndex = finalSelectedIndex.coerceAtLeast(0),
                        selectedUrl = primaryPlan.selectedUrl
                    )
                )
            }

            triedDomainUrls += normalizeDomains(source.config.data.domains).map { it.url }
        }

        // ══════════════════════════════════════════════════════
        // 阶段3：全部失败
        // ══════════════════════════════════════════════════════
        RemoteConfigResult.Error("当前线路不可用，请稍后重试")
    }


    suspend fun fetchRuntimeFallbackPlan(excludedUrls: Set<String>): LaunchPlan? =
        withContext(Dispatchers.IO) {
            val lastGoodUrl = ConfigCache.readLastGoodUrl(appContext)
            val fallbackSources = mutableListOf<SourceConfig>()

            for (domain in DNS_TXT_DOMAINS) {
                val config = runCatching { fetchConfigFromDns(domain) }.getOrNull()
                if (config != null) fallbackSources += SourceConfig("DNS_TXT", config)
            }

            val cached = ConfigCache.read(appContext)
            if (cached != null && !isExpired(cached)) {
                fallbackSources += SourceConfig("CACHE", cached)
            }

            fallbackSources += SourceConfig("LOCAL", localFallbackConfig())

            val allDomains = fallbackSources.flatMap { it.config.data.domains }

            buildLaunchPlan(
                domains = allDomains,
                preferredUrl = lastGoodUrl,
                excludedUrls = excludedUrls
            )
        }

    suspend fun probeLandingUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        checkLandingUrl(url)
    }

    private fun fetchConfigFromUrl(url: String): RemoteConfig? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .build()

            fetchClient.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val body = response.body?.string().orEmpty().trim()
                if (body.isBlank()) return null
                parseEncryptedEnvelope(body)
            }
        }.getOrNull()
    }

    private fun fetchConfigFromDns(domain: String): RemoteConfig? {
        val txtList = DnsTxtResolver.resolve(domain)
        if (txtList.isEmpty()) return null

        for (rawTxt in txtList) {
            if (rawTxt.isBlank()) continue

            // ✅ 修复：先尝试解包 JSON 数组格式 [{...}]，再按原逻辑处理
            val unwrappedList = tryUnwrapJsonArray(rawTxt)
            val targets = if (unwrappedList.isNotEmpty()) unwrappedList else listOf(rawTxt)

            for (target in targets) {
                val candidates = expandDnsTxtCandidates(target)
                for (txt in candidates) {
                    if (txt.isBlank()) continue

                    parseEncryptedEnvelope(txt)?.let { return it }

                    parseDnsPayload(txt)?.let { payload ->
                        if (payload.backupConfigUrl.isNotBlank()) {
                            fetchConfigFromUrl(payload.backupConfigUrl)?.let { return it }
                        }

                        if (payload.domains.isNotEmpty()) {
                            val config = RemoteConfig(
                                version = 1,
                                timestamp = System.currentTimeMillis() / 1000,
                                expireAt = payload.expireAt,
                                data = RemoteConfigData(
                                    domains = normalizeDomains(payload.domains)
                                )
                            )
                            if (!isExpired(config)) return config
                        }
                    }

                    val plainDomains = parsePlainDomainsFromTxt(txt)
                    if (plainDomains.isNotEmpty()) {
                        return RemoteConfig(
                            version = 1,
                            timestamp = System.currentTimeMillis() / 1000,
                            expireAt = 0L,
                            data = RemoteConfigData(
                                domains = normalizeDomains(plainDomains)
                            )
                        )
                    }
                }
            }
        }
        return null
    }

    // ✅ 新增：解包 JSON 数组，支持 [{...}] 和 ["..."] 两种格式
    private fun tryUnwrapJsonArray(raw: String): List<String> {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return emptyList()

        // 情况一：字符串数组 ["item1", "item2"]
        runCatching {
            val arr = gson.fromJson(trimmed, Array<String>::class.java)
            if (!arr.isNullOrEmpty()) return arr.filter { it.isNotBlank() }
        }

        // 情况二：对象数组 [{...}, {...}]
        runCatching {
            val arr = gson.fromJson(trimmed, JsonArray::class.java)
            if (arr != null && arr.size() > 0) {
                return arr.map { it.toString() }.filter { it.isNotBlank() }
            }
        }

        return emptyList()
    }

    private fun expandDnsTxtCandidates(raw: String): List<String> {
        val result = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            val v = value.orEmpty().trim()
            if (v.isNotBlank()) result += v
        }

        val rawTrim = raw.trim()
        addCandidate(rawTrim)

        val stripOuterQuotes = stripOuterQuotes(rawTrim)
        addCandidate(stripOuterQuotes)

        val unescaped1 = unescapeDnsText(stripOuterQuotes)
        addCandidate(unescaped1)

        val unescaped2 = unescapeDnsText(unescaped1)
        addCandidate(unescaped2)

        val jsonDecoded = runCatching {
            gson.fromJson(rawTrim, String::class.java)
        }.getOrNull()
        addCandidate(jsonDecoded)

        val jsonDecoded2 = runCatching {
            gson.fromJson(stripOuterQuotes, String::class.java)
        }.getOrNull()
        addCandidate(jsonDecoded2)

        return result.toList()
    }

    private fun stripOuterQuotes(input: String): String {
        var text = input.trim()
        if (text.length >= 2) {
            if ((text.startsWith("\"") && text.endsWith("\"")) ||
                (text.startsWith("'") && text.endsWith("'"))
            ) {
                text = text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    private fun unescapeDnsText(input: String): String {
        return input
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "")
            .trim()
    }

    private fun parseEncryptedEnvelope(raw: String): RemoteConfig? {
        val envelope = runCatching {
            gson.fromJson(raw, EncryptedEnvelope::class.java)
        }.getOrNull() ?: run {
            log("parseEncryptedEnvelope: JSON解析失败")
            return null
        }

        if (envelope.iv.isBlank() || envelope.data.isBlank() || envelope.sign.isBlank()) {
            log("parseEncryptedEnvelope: envelope字段为空 iv=${envelope.iv.length} data=${envelope.data.length} sign=${envelope.sign.length}")
            return null
        }

        val verified = runCatching {
            CryptoManager.verifyHmac(envelope.data, envelope.ts, envelope.sign)
        }.getOrDefault(false)
        if (!verified) {
            log("parseEncryptedEnvelope: HMAC验证失败")
            return null
        }

        val plainText = runCatching {
            CryptoManager.decryptAesCbc(envelope.iv, envelope.data)
        }.getOrNull() ?: run {
            log("parseEncryptedEnvelope: AES解密失败")
            return null
        }
        log("parseEncryptedEnvelope: 解密明文=${plainText.take(200)}")

        val config = runCatching {
            gson.fromJson(plainText, RemoteConfig::class.java)
        }.getOrNull() ?: run {
            log("parseEncryptedEnvelope: config JSON解析失败")
            return null
        }

        val normalized = config.copy(
            data = config.data.copy(domains = normalizeDomains(config.data.domains))
        )

        if (normalized.data.domains.isEmpty()) {
            log("parseEncryptedEnvelope: domains为空")
            return null
        }
        if (isExpired(normalized)) {
            log("parseEncryptedEnvelope: 配置已过期 expireAt=${normalized.expireAt}")
            return null
        }

        // ✅ 版本号校验已移除
        // val local = ConfigCache.read(appContext)
        // if (local != null && local.version > normalized.version) return null

        ConfigCache.save(appContext, normalized, plainText)
        return normalized
    }

    private fun parseDnsPayload(raw: String): DnsTxtPayload? {
        val envelope = runCatching {
            gson.fromJson(raw, EncryptedEnvelope::class.java)
        }.getOrNull() ?: return null

        if (envelope.iv.isBlank() || envelope.data.isBlank() || envelope.sign.isBlank()) {
            return null
        }

        val verified = runCatching {
            CryptoManager.verifyHmac(envelope.data, envelope.ts, envelope.sign)
        }.getOrDefault(false)
        if (!verified) return null

        val plainText = runCatching {
            CryptoManager.decryptAesCbc(envelope.iv, envelope.data)
        }.getOrNull() ?: return null

        return runCatching {
            val payload = gson.fromJson(plainText, DnsTxtPayload::class.java)
            payload.copy(domains = normalizeDomains(payload.domains))
        }.getOrNull()
    }

    private fun parsePlainDomainsFromTxt(raw: String): List<DomainItem> {
        return raw
            .split(",", "\n", "|", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .mapIndexed { index, url ->
                DomainItem(url = url, weight = 100 - index)
            }
    }

    private suspend fun buildLaunchPlan(
        domains: List<DomainItem>,
        preferredUrl: String,
        excludedUrls: Set<String> = emptySet()
    ): LaunchPlan? = coroutineScope {
        val ordered = prioritizeDomains(
            domains = normalizeDomains(domains),
            preferredUrl = preferredUrl,
            excludedUrls = excludedUrls
        )
        if (ordered.isEmpty()) return@coroutineScope null

        val probeResults = ordered.map { item ->
            async {
                val result = checkLandingUrl(item.url)
                log("probe url=${item.url} result=$result")  // ✅ 新增探测日志
                item.url to result
            }
        }.awaitAll().toMap()

        val selectedIndex = ordered.indexOfFirst { probeResults[it.url] == true }
        if (selectedIndex < 0) {
            log("buildLaunchPlan: 所有域名探测失败 domains=${ordered.map { it.url }}")
            null
        } else {
            LaunchPlan(
                domains = ordered,
                selectedIndex = selectedIndex,
                selectedUrl = ordered[selectedIndex].url
            )
        }
    }

    private fun checkLandingUrl(url: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()

            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code != 200) {
                    log("checkLandingUrl: url=$url code=$code")
                    return false
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    log("checkLandingUrl: url=$url body为空")
                    return false
                }

                val text = body.lowercase()

                val hitErrorKeyword =
                    text.contains("404 not found") ||
                            text.contains(">404<") ||
                            text.contains("not found") ||
                            text.contains("403 forbidden") ||
                            text.contains("forbidden") ||
                            text.contains("502 bad gateway") ||
                            text.contains("503 service unavailable") ||
                            text.contains("系统维护") ||
                            text.contains("页面不存在") ||
                            text.contains("访问被拒绝") ||
                            text.contains("网站暂时无法访问")

                if (hitErrorKeyword) {
                    log("checkLandingUrl: url=$url 命中错误关键词")
                    return false
                }

                val passed = body.length > 200
                if (!passed) log("checkLandingUrl: url=$url body长度不足 length=${body.length}")
                passed
            }
        }.getOrDefault(false)
    }

    private fun localFallbackConfig(): RemoteConfig {
        return RemoteConfig(
            version = 1,
            timestamp = System.currentTimeMillis() / 1000,
            expireAt = 0L,
            data = RemoteConfigData(
                domains = normalizeDomains(LOCAL_FALLBACK_DOMAINS)
            )
        )
    }

    private fun normalizeDomains(domains: List<DomainItem>): List<DomainItem> {
        return domains.asSequence()
            .map { it.copy(url = it.url.trim()) }
            .filter { it.url.isNotBlank() }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.url }
            .sortedByDescending { it.weight }
            .toList()
    }

    private fun prioritizeDomains(
        domains: List<DomainItem>,
        preferredUrl: String,
        excludedUrls: Set<String>
    ): List<DomainItem> {
        val filtered = domains.filter { it.url !in excludedUrls }
        if (filtered.isEmpty()) return emptyList()
        if (preferredUrl.isBlank()) return filtered

        val preferred = filtered.firstOrNull { it.url == preferredUrl } ?: return filtered

        return buildList {
            add(preferred)
            addAll(filtered.filterNot { it.url == preferredUrl })
        }
    }

    private fun mergeDomains(
        primary: List<DomainItem>,
        backup: List<DomainItem>,
        preferredUrl: String
    ): List<DomainItem> {
        val merged = (primary + backup)
            .asSequence()
            .map { it.copy(url = it.url.trim()) }
            .filter { it.url.isNotBlank() }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.url }
            .toList()

        return prioritizeDomains(merged, preferredUrl, emptySet())
    }

    private fun isExpired(config: RemoteConfig): Boolean {
        if (config.expireAt <= 0) return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return nowSeconds > config.expireAt
    }

    private fun log(msg: String) {
        Log.e(TAG, msg)
    }

    private data class SourceConfig(
        val source: String,
        val config: RemoteConfig
    )
}

sealed class RemoteConfigResult {
    data class Success(
        val config: RemoteConfig,
        val source: String,
        val launchPlan: LaunchPlan
    ) : RemoteConfigResult()

    data class Error(val message: String) : RemoteConfigResult()
}
