package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonProperty

data class SearchResult(
    @JsonProperty("animes") val animes: List<SearchAnime>? = null
)

data class SearchAnime(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("poster") val poster: String?
)

data class ServerItem(
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("resolveUrl") val resolveUrl: String? = null,
    @JsonProperty("streamUrl") val streamUrl: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("embedId") val embedId: Any? = null
)

data class ResolveResponse(
    @JsonProperty("id") val id: String?
)

data class TauResponse(
    @JsonProperty("urls") val urls: List<TauUrl>? = null
)

data class TauUrl(
    @JsonProperty("label") val label: String?,
    @JsonProperty("url") val url: String
)