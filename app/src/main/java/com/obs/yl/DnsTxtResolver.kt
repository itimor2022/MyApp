package com.obs.yl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * DNS TXT 解析器
 *
 * 优化点（兼容中国大陆网络环境）：
 *  1. DNS 服务器列表重排：大陆 DNS 优先，境外 DNS 兜底，最后回退系统 DNS
 *  2. 并发探测：同一时间向多个 DNS 发请求，取最先返回且非空的结果
 *  3. UDP -> TCP 自动降级：部分运营商对 UDP/53 限速，超时后切到 TCP/53
 *  4. 60 秒结果缓存：同一域名短时间内复用，避免重复查询拖慢启动
 *  5. 单服务器超时：3 秒（并发场景下整体仍快于原串行 4 秒 * 4）
 */
object DnsTxtResolver {

    private const val TAG = "DNS_FLOW"

    /** 单个 DNS 服务器超时（秒） */
    private const val SINGLE_DNS_TIMEOUT_SEC = 3L

    /** 整体解析超时（秒）：即使所有并发都慢，也要在此时返回 */
    private const val OVERALL_TIMEOUT_SEC = 6L

    /** TXT 结果缓存时长（毫秒） */
    private const val CACHE_TTL_MS = 60_000L

    /**
     * DNS 服务器列表
     * 顺序：大陆主流 -> 境外公共 -> 系统 DNS（null）
     * 并发场景下顺序不再关键，但保持大陆优先便于日志观察
     */
    private val dnsServers = listOf(
        // 阿里 DNS：大陆访问最快
        "223.5.5.5",
        // DNSPod：腾讯，南方访问快
        "119.29.29.29",
        // 114DNS：覆盖面广，电信/移动通用
        "114.114.114.114",
        // 百度 DNS
        "180.76.76.76",
        // Google：大陆可能慢但有时可达，作为兜底
        "8.8.8.8",
        // Cloudflare：境外兜底
        "1.1.1.1",
        // 最后回退到系统 DNS
        null
    )

    /** 缓存：domain -> (timestamp, result) */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val timestamp: Long,
        val records: List<String>
    )

    /**
     * 解析指定域名的 TXT 记录。
     *
     * @param domain 要解析的域名
     * @return TXT 记录字符串列表（已合并多段、trim、去重）；失败返回空列表
     */
    suspend fun resolve(domain: String): List<String> = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext emptyList()

        val target = domain.trim().removeSuffix(".")

        // 1. 查缓存
        val cached = cache[target]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            LogUtil.e(TAG, "resolve cache-hit domain=$target records=${cached.records.size}")
            return@withContext cached.records
        }

        // 2. 并发向所有 DNS 探测，取最先成功的
        val result = queryWithFallback(target)

        // 3. 写入缓存（即使是空结果也缓存一段时间，避免失败风暴）
        cache[target] = CacheEntry(System.currentTimeMillis(), result)

        if (result.isEmpty()) {
            LogUtil.e(TAG, "resolve failed domain=$target all dns empty")
        } else {
            LogUtil.e(TAG, "resolve success domain=$target txtCount=${result.size}")
        }
        result
    }

    /**
     * 并发向所有 DNS 探测：UDP 优先，超时降级 TCP。
     * 任一返回非空 TXT 即视为成功，整体受 OVERALL_TIMEOUT_SEC 约束。
     */
    private suspend fun queryWithFallback(target: String): List<String> {
        return withTimeoutOrNull(OVERALL_TIMEOUT_SEC * 1000L) {
            coroutineScope {
                dnsServers.map { server ->
                    async(Dispatchers.IO) {
                        server to querySingleWithTcpFallback(target, server)
                    }
                }.awaitAll()
                    // 取第一个非空结果；保留顺序便于排查
                    .firstOrNull { (_, records) -> records.isNotEmpty() }
                    ?.second
                    ?: emptyList()
            }
        } ?: emptyList()
    }

    /**
     * 单个 DNS 服务器查询：先 UDP，超时/失败降级到 TCP。
     */
    private fun querySingleWithTcpFallback(target: String, server: String?): List<String> {
        val label = server ?: "system"
        // 先尝试 UDP
        val udpResult = runQuery(target, server, useTcp = false)
        if (udpResult.isNotEmpty()) {
            LogUtil.e(TAG, "resolve udp-ok domain=$target dns=$label")
            return udpResult
        }
        // UDP 无结果 -> 降级 TCP
        LogUtil.e(TAG, "resolve udp-empty domain=$target dns=$label, fallback to TCP")
        return runQuery(target, server, useTcp = true)
    }

    /**
     * 在指定 DNS 服务器上执行一次 TXT 查询。
     */
    private fun runQuery(target: String, server: String?, useTcp: Boolean): List<String> {
        val label = server ?: "system"
        try {
            val lookup = Lookup(target, Type.TXT)

            if (!server.isNullOrBlank()) {
                val resolver = SimpleResolver(server)
                resolver.setTimeout(SINGLE_DNS_TIMEOUT_SEC.toInt())
                if (useTcp) resolver.setTCP(true)
                lookup.setResolver(resolver)
            }

            lookup.setCache(null)
            val records = lookup.run()

            LogUtil.e(
                TAG,
                "resolve done domain=$target dns=$label tcp=$useTcp " +
                        "result=${lookup.result} error=${lookup.errorString}"
            )

            return records
                ?.mapNotNull { record -> (record as? TXTRecord)?.strings?.joinToString(separator = "") }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: emptyList()
        } catch (e: Exception) {
            LogUtil.e(
                TAG,
                "resolve error domain=$target dns=$label tcp=$useTcp msg=${e.message}",
                e
            )
            return emptyList()
        }
    }
}
