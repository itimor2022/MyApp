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

        private val DNS_TXT_DOMAINS = listOf(
            "cfg.65572.top",
            "cfg.qlzgd.one",
            "cfg.qlzg2.one",
            "cfg.111819.it.com",
            "cfg.11822.it.com",
        )

        /**
         * OSS TXT 域名列表（补充/兜底域名源）。
         * 文件内容为纯文本域名列表，每行一条：
         *   线路1
         *   线路2
         * 要求每行以 http:// 或 https:// 开头，否则会被 parsePlainDomainsFromTxt 过滤。
         */
        private val OSS_TXT_URLS = listOf(
            "https://gz-1398539102.cos.ap-guangzhou.myqcloud.com/oss/oss.txt",
            "https://nj-1398539102.cos.ap-nanjing.myqcloud.com/oss/oss.txt",
            "https://dl.zzgz1.com/pkgs/oss.txt",
            "https://csh.xo418.cn/pkgs/oss.txt",
        )

        /**
         * 兜底域名。
         * DNS TXT 解析失败 / 远程配置不可用 / 备用探测全部失败时使用。
         * 要求：
         *  - HTTPS、长期可用、与现网业务兼容（页面加载后能正常使用）
         *  - 不依赖 Cookie/登录态
         *  - 返回 HTML（非空文档）
         * 建议替换为真实兜底域名。
         */
        private const val FALLBACK_DOMAIN = "http://ccs.ugfgzf.cn/sv002"

        /** 公开暴露的兜底域名 host，便于外部模块做循环探测防护 */
        val FALLBACK_DOMAIN_HOST: String =
            runCatching { java.net.URI(FALLBACK_DOMAIN).host }.getOrDefault("")

        /** 判断给定 url 是否是兜底域名（按 host 比较，忽略协议/路径/大小写） */
        fun isFallbackDomain(url: String): Boolean {
            if (url.isBlank() || FALLBACK_DOMAIN_HOST.isBlank()) return false
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
            return host.equals(FALLBACK_DOMAIN_HOST, ignoreCase = true)
        }

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

        /**
         * 生成兜底 LaunchPlan：当 DNS / 远程配置 / 全部域名探测都失败时使用。
         * 这样下游永远能拿到一个可访问的 url，避免在错误页面上反复重试。
         */
        internal fun fallbackLaunchPlan(reason: String): LaunchPlan {
            Log.e(TAG, "fallbackLaunchPlan reason=$reason domain=$FALLBACK_DOMAIN")
            val item = DomainItem(url = FALLBACK_DOMAIN, weight = Int.MAX_VALUE)
            return LaunchPlan(
                domains = listOf(item),
                selectedIndex = 0,
                selectedUrl = FALLBACK_DOMAIN
            )
        }

        /**
         * 构造兜底场景下使用的合成 RemoteConfig（用于 Success 包装）。
         */
        internal fun fallbackRemoteConfig(): RemoteConfig {
            return RemoteConfig(
                version = 0,
                timestamp = System.currentTimeMillis() / 1000,
                expireAt = 0L,
                data = RemoteConfigData(
                    domains = listOf(DomainItem(url = FALLBACK_DOMAIN, weight = Int.MAX_VALUE))
                )
            )
        }
    }

    suspend fun fetchAvailableConfig(): RemoteConfigResult = withContext(Dispatchers.IO) {
        // ══════════════════════════════════════════════════════
        // 全链路拉取：启动顺序优先 OSS，DNS TXT 作为兜底补充
        //   1) 并行拉取所有 OSS TXT URL，按 OSS_TXT_URLS 列表顺序
        //      选择第一个成功的作为主线路（"第一条不能访问就用第二条，依次往下"）；
        //   2) 所有 OSS 全部失败时，再降级到 DNS TXT（5 个域名）；
        //   3) 所有源都失败时 → 兜底域名。
        // ══════════════════════════════════════════════════════
        val sources = mutableListOf<SourceConfig>()
        var ossResolved = false

        // ✅ OSS 优先：并行拉取所有 OSS，按 OSS_TXT_URLS 顺序选取第一个成功的
        //    语义：第 1 条不能访问就用第 2 条，依次往下；只有所有 OSS 都失败才走 DNS
        val ossResults: List<Pair<String, RemoteConfig?>> = coroutineScope {
            OSS_TXT_URLS.map { url ->
                async {
                    val config = runCatching { fetchConfigFromOssTxt(url) }.getOrNull()
                    url to config
                }
            }.awaitAll()
        }
        // 按 OSS_TXT_URLS 原始顺序遍历，取第一个成功的结果
        for ((url, config) in ossResults) {
            if (config != null) {
                log("add source OSS_TXT url=$url domains=${config.data.domains.size}")
                sources += SourceConfig("OSS_TXT", config)
                ossResolved = true
                break   // 找到第一个可用的 OSS 后立即停止，去探测业务域名
            } else {
                log("OSS unreachable url=$url, try next OSS")
            }
        }

        // 兜底：OSS 全部"链接本身打不开"时（ossResolved == false）才去请求 DNS TXT
        if (!ossResolved) {
            log("OSS all unreachable, fallback to DNS TXT")
            for (domain in DNS_TXT_DOMAINS) {
                val config = runCatching { fetchConfigFromDns(domain) }.getOrNull()
                if (config != null) {
                    log("add source DNS_TXT domain=$domain")
                    sources += SourceConfig("DNS_TXT", config)
                }
            }
        }

        // ✅ 兜底：DNS + OSS 全部解析失败 → 不返回 Error，直接用兜底域名
        if (sources.isEmpty()) {
            log("fetchAvailableConfig: 所有 DNS TXT / OSS TXT 解析失败，使用兜底域名")
            return@withContext RemoteConfigResult.Success(
                config = fallbackRemoteConfig(),
                source = "FALLBACK",
                launchPlan = fallbackLaunchPlan("dns_all_failed")
            )
        }

        val triedDomainUrls = linkedSetOf<String>()

        for ((index, source) in sources.withIndex()) {
            log("try source=${source.source}, index=$index")

            val primaryPlan = buildLaunchPlan(
                domains = source.config.data.domains,
                preferredUrl = "",
                excludedUrls = triedDomainUrls
            )

            if (primaryPlan != null) {
                // ✅ OSS 拉成功 → 不再合并 DNS 的业务域名，直接用 OSS 自己的
                //    只有在 OSS 完全没拉到、降到 DNS 路径时，才把后续 DNS 的域名合并进来
                val remainingBackupDomains = if (source.source == "OSS_TXT") {
                    emptyList()
                } else {
                    sources.drop(index + 1).flatMap { it.config.data.domains }
                }

                val mergedDomains = mergeDomains(
                    primary = primaryPlan.domains,
                    backup = remainingBackupDomains,
                    preferredUrl = ""
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

            // ✅ OSS 拿到了但业务域名全失败 → 不再尝试 DNS，直接走兜底
            if (source.source == "OSS_TXT") {
                log("OSS got config but all business domains probe failed, skip DNS, use fallback")
                return@withContext RemoteConfigResult.Success(
                    config = fallbackRemoteConfig(),
                    source = "FALLBACK",
                    launchPlan = fallbackLaunchPlan("oss_probe_all_failed")
                )
            }

            triedDomainUrls += normalizeDomains(source.config.data.domains).map { it.url }
        }

        // ✅ 兜底：所有 source 的域名探测都失败 → 走兜底域名
        log("fetchAvailableConfig: 所有域名探测失败，使用兜底域名")
        RemoteConfigResult.Success(
            config = fallbackRemoteConfig(),
            source = "FALLBACK",
            launchPlan = fallbackLaunchPlan("all_probes_failed")
        )
    }


    suspend fun fetchRuntimeFallbackPlan(excludedUrls: Set<String>): LaunchPlan? =
        withContext(Dispatchers.IO) {
            val fallbackSources = mutableListOf<SourceConfig>()
            var ossResolved = false

            // ✅ 与 fetchAvailableConfig 一致：并行拉取所有 OSS，按 OSS_TXT_URLS 顺序
            //    选取第一个可用的 OSS（第 1 条不能访问就用第 2 条，依次往下）
            val ossResults: List<Pair<String, RemoteConfig?>> = coroutineScope {
                OSS_TXT_URLS.map { url ->
                    async {
                        val config = runCatching { fetchConfigFromOssTxt(url) }.getOrNull()
                        url to config
                    }
                }.awaitAll()
            }
            for ((url, config) in ossResults) {
                if (config != null) {
                    fallbackSources += SourceConfig("OSS_TXT", config)
                    ossResolved = true
                    break
                } else {
                    log("OSS unreachable url=$url, try next OSS")
                }
            }

            // OSS 全部打不开时回退 DNS
            if (!ossResolved) {
                for (domain in DNS_TXT_DOMAINS) {
                    val config = runCatching { fetchConfigFromDns(domain) }.getOrNull()
                    if (config != null) fallbackSources += SourceConfig("DNS_TXT", config)
                }
            }

            val allDomains = fallbackSources.flatMap { it.config.data.domains }

            val plan = buildLaunchPlan(
                domains = allDomains,
                preferredUrl = "",
                excludedUrls = excludedUrls
            )

            // ✅ 兜底：远程备用计划仍失败 → 返回兜底域名 LaunchPlan
            if (plan == null) {
                log("fetchRuntimeFallbackPlan: 备用探测全部失败，使用兜底域名")
                return@withContext fallbackLaunchPlan("runtime_fallback_failed")
            }
            plan
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

    /**
     * 从 OSS TXT URL 拉取纯文本域名列表（如 https://xxx/pkgs/oss.txt）。
     * 文件格式为每行一个域名（或以 , \n | ; 等分隔），复用 parsePlainDomainsFromTxt 解析。
     * 仅返回每行以 http(s):// 开头的条目，自动去重并按出现顺序赋递减权重。
     * 解析到至少 1 个域名时返回 RemoteConfig，否则返回 null。
     */
    private fun fetchConfigFromOssTxt(url: String): RemoteConfig? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .build()

            fetchClient.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    log("fetchConfigFromOssTxt: url=$url code=${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty().trim()
                if (body.isBlank()) {
                    log("fetchConfigFromOssTxt: url=$url body为空")
                    return null
                }
                val plainDomains = parsePlainDomainsFromTxt(body)
                if (plainDomains.isEmpty()) {
                    log("fetchConfigFromOssTxt: url=$url 解析后无可用域名 body=${body.take(200)}")
                    return null
                }
                RemoteConfig(
                    version = 1,
                    timestamp = System.currentTimeMillis() / 1000,
                    expireAt = 0L,
                    data = RemoteConfigData(
                        domains = normalizeDomains(plainDomains)
                    )
                )
            }
        }.getOrNull()
    }

    private suspend fun fetchConfigFromDns(domain: String): RemoteConfig? {
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
