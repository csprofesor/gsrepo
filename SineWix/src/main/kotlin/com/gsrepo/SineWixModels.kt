package com.gsrepo

import com.fasterxml.jackson.annotation.JsonProperty

data class SineSonuc(
    @JsonProperty("data") val data: List<SineData>? = null
)

data class SineSearch(
    @JsonProperty("results") val results: List<SineData>? = null,
    @JsonProperty("search") val search: List<SineData>? = null
)

data class SineEncryptedResponse(
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("tag") val tag: String? = null,
    @JsonProperty("aad") val aad: String? = null,
    @JsonProperty("ciphertext") val ciphertext: String? = null,
)

data class SineData(
    @JsonProperty("id") val id: Any? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("vote_average") val vote: Any? = null,
)

data class SineMovie(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("tmdb_id") val tmdb: Any? = null,
    @JsonProperty("imdb_external_id") val imdb: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val vote: Any? = null,
    @JsonProperty("runtime") val runtime: Any? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("preview_path") val trailer: String? = null,
    @JsonProperty("casterslist") val cast: List<SineCast>? = null,
    @JsonProperty("genres") val genres: List<SineGenre>? = null,
    @JsonProperty("videos") val videos: List<SineVideo>? = null,
    @JsonProperty("servers") val servers: List<SineVideo>? = null,
)

data class SineSerie(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("tmdb_id") val tmdb: Any? = null,
    @JsonProperty("imdb_external_id") val imdb: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("vote_average") val vote: Any? = null,
    @JsonProperty("first_air_date") val releaseDate: String? = null,
    @JsonProperty("preview_path") val trailer: String? = null,
    @JsonProperty("casterslist") val cast: List<SineCast>? = null,
    @JsonProperty("seasons") val seasons: List<SineSeason>? = null,
)

data class SineCast(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("profile_path") val profilePath: String? = null,
)

data class SineGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
)

data class SineSeason(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("episodes") val episodes: List<SineEpisode>? = null,
)

data class SineEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("still_path") val stillPath: String? = null,
    @JsonProperty("videos") val videos: List<SineVideo>? = null,
    @JsonProperty("servers") val servers: List<SineVideo>? = null,
)

data class SineVideo(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("server") val server: String? = null,
    @JsonProperty("server_name") val serverName: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("hls") val hls: Int? = null,
    @JsonProperty("embed") val embed: Int? = null,
    @JsonProperty("supported_hosts") val supHosts: Int? = null,
)

data class SineTokenResponse(
    @JsonProperty("access_token") val accessToken: String? = null,
    @JsonProperty("session_id") val sessionId: String? = null,
    @JsonProperty("expires_in") val expiresIn: Long? = null,
)
