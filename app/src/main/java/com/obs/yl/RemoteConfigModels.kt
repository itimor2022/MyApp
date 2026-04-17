package com.bilibili.btc101

import com.google.gson.annotations.SerializedName

data class EncryptedEnvelope(
    @SerializedName("ts")
    val ts: Long = 0L,
    @SerializedName("iv")
    val iv: String = "",
    @SerializedName("data")
    val data: String = "",
    @SerializedName("sign")
    val sign: String = ""
)

data class DomainItem(
    @SerializedName("url")
    val url: String = "",
    @SerializedName("weight")
    val weight: Int = 1
)

data class RemoteConfig(
    @SerializedName("version")
    val version: Int = 0,
    @SerializedName("timestamp")
    val timestamp: Long = 0L,
    @SerializedName("expire_at")
    val expireAt: Long = 0L,
    @SerializedName("data")
    val data: RemoteConfigData = RemoteConfigData()
)

data class RemoteConfigData(
    @SerializedName("domains")
    val domains: List<DomainItem> = emptyList(),
    @SerializedName("feature_x")
    val featureX: Boolean = false,
    @SerializedName("gray_ratio")
    val grayRatio: Int = 0
)

data class DnsTxtPayload(
    @SerializedName("backup_config_url")
    val backupConfigUrl: String = "",
    @SerializedName("domains")
    val domains: List<DomainItem> = emptyList(),
    @SerializedName("expire_at")
    val expireAt: Long = 0L
)

data class LaunchPlan(
    val domains: List<DomainItem> = emptyList(),
    val selectedIndex: Int = -1,
    val selectedUrl: String = ""
)