// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchItem(
    @JsonProperty("object_id")
    val id: Any? = null,

    @JsonProperty("object_name")
    val title: String = "",

    @JsonProperty("object_alternative_name")
    val trTitle: String? = null,

    @JsonProperty("object_poster_url")
    val poster: String? = null,

    @JsonProperty("object_categories")
    val genres: String? = null,

    @JsonProperty("object_related_imdb_point")
    val imdb: Any? = null,

    @JsonProperty("object_release_year")
    val year: Any? = null,

    @JsonProperty("used_type")
    val type: String? = null,

    @JsonProperty("used_slug")
    val slug: String? = null
)
