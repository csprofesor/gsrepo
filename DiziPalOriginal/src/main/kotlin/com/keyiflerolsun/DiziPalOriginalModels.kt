// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("results") val results: List<DizipalSearchResultItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchResultItem(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Any? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("rating") val rating: Any? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalPlayerConfigResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("enc") val enc: DizipalEncData? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalEncData(
    @JsonProperty("c") val c: String? = null,
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("k1") val k1: String? = null,
    @JsonProperty("k2") val k2: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DizipalSearchResult(
    @JsonProperty("object_id")
    val id: Any? = null,

    @JsonProperty("object_name")
    val title: String? = null,

    @JsonProperty("object_poster_url")
    val poster: String? = null,

    @JsonProperty("object_back_url")
    val backUrl: String? = null,

    @JsonProperty("object_related_imdb_point")
    val imdb: Any? = null,

    @JsonProperty("used_type")
    val type: String? = null,

    @JsonProperty("used_slug")
    val slug: String? = null
)
