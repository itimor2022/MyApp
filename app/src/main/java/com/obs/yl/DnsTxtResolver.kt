package com.bilibili.btc101

import org.xbill.DNS.Lookup
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type

object DnsTxtResolver {

    private const val TAG = "DNS_FLOW"

    /**
     * 公共 DNS 优先，系统 DNS 放最后
     * 避免某些本地运营商 DNS 返回 ANSWER:0
     */
    private val dnsServers = listOf(
        "8.8.8.8",
        "1.1.1.1",
        "9.9.9.9",
        null
    )

    fun resolve(domain: String): List<String> {
        if (domain.isBlank()) return emptyList()

        val target = domain.trim().removeSuffix(".")

        for (server in dnsServers) {
            try {
                LogUtil.e(TAG, "resolve start domain=$target dns=${server ?: "system"}")

                val lookup = Lookup(target, Type.TXT)

                if (!server.isNullOrBlank()) {
                    val resolver = SimpleResolver(server)
                    resolver.setTimeout(4)
                    lookup.setResolver(resolver)
                }

                lookup.setCache(null)
                val records = lookup.run()

                LogUtil.e(
                    TAG,
                    "resolve done domain=$target dns=${server ?: "system"} result=${lookup.result} error=${lookup.errorString}"
                )

                val list = records
                    ?.mapNotNull { record ->
                        (record as? TXTRecord)?.strings?.joinToString(separator = "")
                    }
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?: emptyList()

                LogUtil.e(
                    TAG,
                    "resolve parsed domain=$target dns=${server ?: "system"} txtCount=${list.size}"
                )

                if (list.isNotEmpty()) {
                    LogUtil.e(
                        TAG,
                        "resolve success domain=$target dns=${server ?: "system"} txtList=$list"
                    )
                    return list
                }
            } catch (e: Exception) {
                LogUtil.e(
                    TAG,
                    "resolve error domain=$target dns=${server ?: "system"} msg=${e.message}",
                    e
                )
            }
        }

        LogUtil.e(TAG, "resolve failed domain=$target all dns empty")
        return emptyList()
    }
}