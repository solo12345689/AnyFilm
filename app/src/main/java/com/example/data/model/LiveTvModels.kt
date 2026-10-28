package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CountryItem(
    @Json(name = "name") val name: String,
    @Json(name = "code") val code: String,
    @Json(name = "flag") val flag: String? = null
)

data class UnifiedChannel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val category: String = "General",
    val streamUrl: String = "",
    val isJioTv: Boolean = false
)

@JsonClass(generateAdapter = true)
data class LiveChannelItem(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "category") val category: String? = null,
    @Json(name = "logo") val logo: String? = null
)

@JsonClass(generateAdapter = true)
data class LiveChannelListResponse(
    @Json(name = "count") val count: Int? = 0,
    @Json(name = "channels") val channels: List<LiveChannelItem>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class LiveChannelDetailsResponse(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "sources") val sources: List<String>? = emptyList(),
    @Json(name = "drm") val drm: LiveDrmInfo? = null
)

@JsonClass(generateAdapter = true)
data class LiveDrmInfo(
    @Json(name = "type") val type: String? = null,
    @Json(name = "keyId") val keyId: String? = null,
    @Json(name = "key") val key: String? = null,
    @Json(name = "clearKeys") val clearKeys: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class FallbackChannelKey(
    @Json(name = "id") val id: String,
    @Json(name = "keyId") val keyId: String? = null,
    @Json(name = "key") val key: String? = null,
    @Json(name = "keys") val keys: List<FallbackKeyPair>? = null
)

@JsonClass(generateAdapter = true)
data class FallbackKeyPair(
    @Json(name = "keyId") val keyId: String? = null,
    @Json(name = "key") val key: String? = null
)
