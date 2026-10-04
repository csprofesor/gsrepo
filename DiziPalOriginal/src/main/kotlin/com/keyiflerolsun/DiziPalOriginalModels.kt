// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

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
