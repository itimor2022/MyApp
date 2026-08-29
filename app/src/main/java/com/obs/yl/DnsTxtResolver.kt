package com.obs.yl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import org.xbill.DNS.Flags
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DoH (DNS over HTTPS) TXT 解析器
 *
 * 协议遵循 RFC 8484（wireformat GET）：
 *   GET https://<endpoint>/dns-query?dns=<base64url(msg)>
 *   Accept: application/dns-message
 *
 * DoH 端点（顺序：大陆优先 -> 境外兑底）：
 *   1. https://dns.alidns.com/dns-query      阿里
 *   2. https://doh.pub/dns-query             腾讯 DNSPod
 *   3. https://cloudflare-dns.com/dns-query  Cloudflare
 *   4. https://dns.google/dns-query          Google
 */
object DnsTxtResolver {

    private const val TAG = "DNS_FLOW"
    private const val SINGLE_DOH_TIMEOUT_SEC = 4L
    private const val OVERALL_TIMEOUT_SEC = 8L
    private const val CACHE_TTL_MS = 60_000L

    private val dohEndpoints = listOf(
        "https://dns.alidns.com/dns-query",
        "https://doh.pub/dns-query",
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/dns-query",
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SINGLE_DOH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(SINGLE_DOH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(SINGLE_DOH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private data class CacheEntry(
        val timestamp: Long,
        val records: List<String>
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 通过 DoH 解析指定域名的 TXT 记录。
     */
    suspend fun resolve(domain: String): List<String> = withContext(Dispatchers.IO) {
        if (domain.isBlank()) return@withContext emptyList()

        val target = domain.trim().removeSuffix(".")

        val cached = cache[target]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            LogUtil.e(TAG, "resolve cache-hit domain=$target records=${cached.records.size}")
            return@withContext cached.records
        }

        val result = queryWithFallback(target)

        cache[target] = CacheEntry(System.currentTimeMillis(), result)

        if (result.isEmpty()) {
            LogUtil.e(TAG, "resolve failed domain=$target all doh empty")
        } else {
            LogUtil.e(TAG, "resolve success domain=$target txtCount=${result.size}")
        }
        result
    }



    /**
     * 并发向所有 DoH 端点探测，取最先返回且非空 TXT 的结果。
     */
    private suspend fun queryWithFallback(target: String): List<String> {
        return withTimeoutOrNull(OVERALL_TIMEOUT_SEC * 1000L) {
            coroutineScope {
                dohEndpoints.map { endpoint ->
                    async(Dispatchers.IO) {
                        endpoint to querySingleDoh(target, endpoint)
                    }
                }.awaitAll()
                    .firstOrNull { (_, records) -> records.isNotEmpty() }
                    ?.second
                    ?: emptyList()
            }
        } ?: emptyList()
    }

    /**
     * 向单个 DoH 端点查询 TXT 记录（RFC 8484 wireformat GET）。
     */
    private fun querySingleDoh(target: String, endpoint: String): List<String> {
        return try {
            // dnsjava 3.6.3: newQuery(Record)
            val qname = Name.fromString("$target.")
            val queryRecord = Record.newRecord(qname, Type.TXT, DClass.IN)
            val queryMsg = Message.newQuery(queryRecord).apply {
                header.setFlag(Flags.RD.toInt())
            }
            val dnsWire = queryMsg.toWire()
            val dnsB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(dnsWire)

            val url = "$endpoint?dns=$dnsB64".toHttpUrl()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .get()
                .build()

            LogUtil.e(TAG, "doh start domain=$target endpoint=$endpoint")

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogUtil.e(TAG, "doh http error domain=$target endpoint=$endpoint code=${response.code}")
                    return emptyList()
                }

                val body: ByteArray = response.body?.bytes() ?: ByteArray(0)
                if (body.isEmpty()) {
                    LogUtil.e(TAG, "doh empty body domain=$target endpoint=$endpoint")
                    return emptyList()
                }

                val respMsg = try {
                    Message(body)
                } catch (e: Exception) {
                    LogUtil.e(TAG, "doh parse error domain=$target endpoint=$endpoint msg=${e.message}", e)
                    return emptyList()
                }

                val rcode = respMsg.header.rcode
                val records: Array<Record> = respMsg.getSectionArray(Section.ANSWER)

                LogUtil.e(TAG, "doh done domain=$target endpoint=$endpoint rcode=$rcode answerCount=${records.size}")

                records.mapNotNull { record ->
                    (record as? TXTRecord)?.strings?.joinToString(separator = "")
                }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "doh error domain=$target endpoint=$endpoint msg=${e.message}", e)
            emptyList()
        }
    }
}

