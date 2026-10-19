package com.gsrepo

import android.util.Base64
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SineWix : MainAPI() {
    override var mainUrl = "https://ydfvfdizipanel.ru/api"
    override var name = "SineWix"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiKey = "9174121c-b15d-4d6b-9e64-5cee5389bfe9"
    private val sigCert = "308202c3308201aba0030201020204075cec01300d06092a864886f70d01010b050030123110300e0603550403130753696e65776978301e1" +
            "70d3231303932313233333334395a170d3436303931353233333334395a30123110300e0603550403130753696e6577697830820122300d06092a864" +
            "886f70d01010105000382010f003082010a0282010100b0a2a1bc5c3f16f19c3b2456cfd0a6128ced9f5e2e2c4cca1a100e17b07b86256258f372e76" +
            "a95a17e9e4a1c048e364835723a95e8ef6d5bdfb5694b50277c65a64f7b012fdf164e5dc93629561f6ca29b7dc82ebb3d6f3c8e8fc6795847fe331ad" +
            "4a13ed6c059a83804c43d3747526d769580f3a4153752eb22dac66dd15f1582caa43305dc49f55ac7b1b89013e654d2ca8c94c30956659674cc67325" +
            "6c04208f09118bae14cdd72d78f9ee2aece958084a8c2e315deff45726d4fc1f18ec39569ff1abe4f36a8d01090e5f68c07c28763513b88208bcac1a" +
            "6e1941f6fd8bfdd52f832098ddb2154c8f565bc5d58c7106a19e03787e75c7f34997000e3bcf30203010001a321301f301d0603551d0e04160414b54" +
            "5fc18e74a791d9402b53940ae38b96e9e209c300d06092a864886f70d01010b05000382010100a8a64d9e7c8b5db102af15d3caf94ff8d3e9be9008b" +
            "b0021117ca2f0762e68583354b126a041bb1fb6e6308e421e4b5a71f779cde63e5d2fc5976bff966c3c4034e852c077d8e74458fbae2ec1db74b1f40" +
            "82e188bf8ef7c42a44e3fbfb693bb00ee2a727096b42360ddce1bdcd3536f50c8693bcc62a7b7204bcefe2ecf1f7c820bcd63e1d7a6acc8bf6163086" +
            "915fc5f607cf51bc7a8635f98bb4c65a8f24b7b5a82c7b06868f565cb0d6ac4775c4aac777536ddd1a565f990fd8cbe539185fa7aab610b7855a687a" +
            "00f4e55536d72873444552c50fd10727dbf298a9be6ed6ae62148dd1de365f3729915dd31975e28a472d752ac14db3db548405cc31e1e"
    private val hash256 = "f0a3245335bb305f17062b135a424b9f4b423a92a919cdde7f3b32b2b3e7bb04"

    private val headers = mapOf(
        "signature" to sigCert,
        "hash256" to hash256,
        "Accept" to "application/json",
        "User-Agent" to "EasyPlex (Android 13; SM-A546E; samsung; tr)",
        "X-App-Id" to "sinewix-mobile",
        "X-Platform" to "android",
        "packagename" to "com.wxsmash.game",
        "X-Device-Id" to "android_1234567890abcdef"
    )

    private data class CachedToken(val accessToken: String, val sessionId: String, val expiresAt: Long)
    @Volatile
    private var cachedToken: CachedToken? = null

    companion object {
        private val globalSeenIds = Collections.synchronizedSet(HashSet<Int>())
    }

    override val mainPage = mainPageOf(
        "/api/genres/topteen/all/$apiKey" to "Top 10 Listesi",
        "/api/genres/latestmovies/all/$apiKey" to "Son Eklenen Filmler",
        "/api/genres/latestseries/all/$apiKey" to "Son Eklenen Diziler",
        "/api/genres/latestanimes/all/$apiKey" to "Son Eklenen Animeler",
        "/api/genres/movies/show/28/$apiKey" to "Aksiyon Filmleri",
        "/api/genres/movies/show/35/$apiKey" to "Komedi Filmleri",
        "/api/genres/movies/show/27/$apiKey" to "Korku Filmleri",
        "/api/genres/movies/show/878/$apiKey" to "Bilim Kurgu Filmleri",
        "/api/genres/movies/show/12/$apiKey" to "Macera Filmleri",
        "/api/genres/series/show/18/$apiKey" to "Dram Dizileri",
        "/api/genres/animes/show/16/$apiKey" to "Animasyon",
    )

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun decryptGcmResponse(
        ciphertextBase64: String,
        ivBase64: String,
        tagBase64: String,
        aadBase64: String,
        sessionId: String,
        nonce: String,
        timestamp: String
    ): String {
        val iv = Base64.decode(ivBase64, Base64.DEFAULT)
        val tag = Base64.decode(tagBase64, Base64.DEFAULT)
        val aad = Base64.decode(aadBase64, Base64.DEFAULT)
        val cipherBytes = Base64.decode(ciphertextBase64, Base64.DEFAULT)

        val keyString = "easyplex-response-v1|$sessionId|$nonce|$timestamp"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("827EBD30AF15ADAAE62FDAF02731B08978B8398CED65927B732380BF159E659F".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val aesKeyBytes = mac.doFinal(keyString.toByteArray(Charsets.UTF_8))

        val combinedCiphertext = ByteArray(cipherBytes.size + tag.size)
        System.arraycopy(cipherBytes, 0, combinedCiphertext, 0, cipherBytes.size)
        System.arraycopy(tag, 0, combinedCiphertext, cipherBytes.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        val decryptedBytes = cipher.doFinal(combinedCiphertext)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private suspend fun apiCall(urlPath: String, method: String = "GET", formData: Map<String, String>? = null): String? {
        val fullUrl = when {
            urlPath.startsWith("http") -> urlPath
            urlPath.startsWith("/api/") -> "https://ydfvfdizipanel.ru/public$urlPath"
            else -> "https://ydfvfdizipanel.ru/public/api$urlPath"
        }

        val cleanPath = if (urlPath.contains("?")) urlPath.substringBefore("?") else urlPath
        val apiPath = if (cleanPath.startsWith("/api/")) cleanPath else "/api$cleanPath"

        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(24).apply { SecureRandom().nextBytes(this) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP or Base64.URL_SAFE)

        val bodyBytes: ByteArray
        val contentType: String?
        if (formData != null) {
            val formWithNonce = formData.toMutableMap()
            formWithNonce["nonce"] = nonce
            formWithNonce["timestamp"] = timestamp
            val formStr = formWithNonce.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            bodyBytes = formStr.toByteArray(Charsets.UTF_8)
            contentType = "application/x-www-form-urlencoded"
        } else {
            bodyBytes = ByteArray(0)
            contentType = null
        }

        val md = MessageDigest.getInstance("SHA-256")
        val bodyHash = md.digest(bodyBytes).joinToString("") { "%02x".format(it) }

        val stringToSign = "$method\n$apiPath\n$timestamp\n$nonce\n$bodyHash"
        val hmacSig = hmacSha256("32FD53071AAF9D36080510C617DCD08B7B19A1AABA8502C1484FC4961BD630DB", stringToSign)

        val reqHeaders = headers.toMutableMap()
        reqHeaders["X-Timestamp"] = timestamp
        reqHeaders["X-Nonce"] = nonce
        reqHeaders["X-Signature"] = "sha256=$hmacSig"
        reqHeaders["X-Encrypt-Response"] = "1"

        if (contentType != null) {
            reqHeaders["Content-Type"] = contentType
        }

        if (!urlPath.contains("/tokens/mobile/generate")) {
            val token = getOrFetchToken()
            if (token != null) {
                reqHeaders["Authorization"] = "Bearer ${token.accessToken}"
                if (token.sessionId.isNotBlank()) {
                    reqHeaders["X-Session-Id"] = token.sessionId
                }
            }
        }

        val response = if (method == "POST") {
            val mediaType = (contentType ?: "application/json").toMediaTypeOrNull()
            app.post(fullUrl, headers = reqHeaders, requestBody = bodyBytes.toRequestBody(mediaType))
        } else {
            app.get(fullUrl, headers = reqHeaders)
        }

        val encHeader = response.headers.toMap().entries.firstOrNull { it.key.equals("X-Content-Encrypted", ignoreCase = true) }?.value
        val respSessionId = response.headers.toMap().entries.firstOrNull { it.key.equals("X-Session-Id", ignoreCase = true) }?.value ?: (cachedToken?.sessionId ?: "")
        val rawText = response.text

        if (encHeader == "AES-256-GCM") {
            val jsonNode = tryParseJson<SineEncryptedResponse>(rawText)
            val iv = jsonNode?.iv ?: return rawText
            val tag = jsonNode.tag ?: return rawText
            val aad = jsonNode.aad ?: return rawText
            val ciphertext = jsonNode.ciphertext ?: return rawText
            return try {
                decryptGcmResponse(ciphertext, iv, tag, aad, respSessionId, nonce, timestamp)
            } catch (e: Exception) {
                rawText
            }
        }

        return rawText
    }

    private val tokenMutex = Mutex()

    private suspend fun getOrFetchToken(): CachedToken? {
        tokenMutex.withLock {
            val now = System.currentTimeMillis() / 1000
            val curr = cachedToken
            if (curr != null && curr.expiresAt > now + 60) {
                return curr
            }
            val form = mapOf(
                "signature" to hash256,
                "package_name" to "com.wxsmash.game",
                "device_id" to "android_1234567890abcdef",
                "nonce" to "",
                "timestamp" to "",
                "app_version" to "2.5.3",
                "app_id" to "sinewix-mobile",
                "platform" to "android"
            )
            val resText = apiCall("/api/tokens/mobile/generate", "POST", form) ?: return null
            val tokenObj = tryParseJson<SineTokenResponse>(resText) ?: return null
            val accessToken = tokenObj.accessToken ?: return null
            val sessionId = tokenObj.sessionId ?: ""
            val expiresIn = tokenObj.expiresIn ?: 86400
            val newToken = CachedToken(accessToken, sessionId, now + expiresIn)
            cachedToken = newToken
            return newToken
        }
    }

    private fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.equals("null", true) || url.equals("none", true)) return null
        val clean = url.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        if (clean.startsWith("/")) return "https://image.tmdb.org/t/p/w500$clean"
        return "https://image.tmdb.org/t/p/w500/$clean"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1 && request.name == "Top 10 Listesi") {
            globalSeenIds.clear()
        }

        val baseUrlPath = request.data
        val itemsList = mutableListOf<SearchResponse>()
        var currentPage = page
        val maxPagesToFetch = if (page == 1) 3 else 1

        while (itemsList.size < 12 && currentPage <= page + maxPagesToFetch - 1) {
            val urlPath = if (baseUrlPath.startsWith("/api/")) "$baseUrlPath?page=$currentPage" else "/api$baseUrlPath?page=$currentPage"
            val resText = try {
                apiCall(urlPath, "GET")
            } catch (_: Throwable) {
                null
            }

            val rawItems = if (!resText.isNullOrBlank()) {
                tryParseJson<SineSonuc>(resText)?.data
                    ?: tryParseJson<SineSearch>(resText)?.results
                    ?: tryParseJson<SineSearch>(resText)?.search
                    ?: tryParseJson<List<SineData>>(resText)
                    ?: emptyList()
            } else emptyList()

            if (rawItems.isEmpty()) break

            for (item in rawItems) {
                val searchResp = item.toSearchResponse(globalSeenIds)
                if (searchResp != null) {
                    itemsList.add(searchResp)
                    if (itemsList.size >= 12 && page == 1) break
                }
            }
            currentPage++
        }

        return newHomePageResponse(request.name, itemsList)
    }

    private fun SineData.toSearchResponse(rowSeenIds: MutableSet<Int>? = null): SearchResponse? {
        val title = this.title ?: this.name ?: return null
        val id = when (val i = this.id) {
            is Number -> i.toInt()
            is String -> i.toIntOrNull()
            else -> null
        } ?: return null

        val rawPoster = this.posterPath?.trim()?.ifBlank { null }
            ?: this.backdropPath?.trim()?.ifBlank { null }
            ?: return null

        val posterUrl = fixUrl(rawPoster) ?: return null

        if (rowSeenIds != null) {
            if (!rowSeenIds.add(id)) {
                return null
            }
        }

        val score = when (val v = this.vote) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }

        val mediaType = this.type?.lowercase()
        val isTv = mediaType == "serie" || mediaType == "series" || mediaType == "tv"
        val isAnime = mediaType == "anime" || mediaType == "animes"

        return if (isTv) {
            val href = "$mainUrl/series/show/$id/$apiKey"
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (score != null) this.score = Score.from10(score)
            }
        } else if (isAnime) {
            val href = "$mainUrl/animes/show/$id/$apiKey"
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                if (score != null) this.score = Score.from10(score)
            }
        } else {
            val href = "$mainUrl/media/detail/$id/$apiKey"
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (score != null) this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val urlPath = "/api/search/$query/$apiKey?limit=20&page=1"
        val resText = try {
            apiCall(urlPath, "GET")
        } catch (_: Throwable) {
            null
        } ?: return emptyList()

        val result = tryParseJson<SineSearch>(resText)

        val items = result?.results ?: result?.search ?: emptyList()
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/media/detail/")) {
            movieDetail(url)
        } else if (url.contains("/series/show/") || url.contains("/animes/show/")) {
            serieDetail(url)
        } else {
            null
        }
    }

    private suspend fun movieDetail(url: String): LoadResponse? {
        val id = url.substringAfter("/media/detail/").substringBefore("/").substringBefore("?").toIntOrNull() ?: return null
        val resText = try {
            apiCall("/api/media/detail/$id/$apiKey", "GET")
        } catch (_: Throwable) {
            null
        } ?: return null

        val result = tryParseJson<SineMovie>(resText) ?: return null

        val name = result.title ?: result.name ?: ""
        val title = name
        val poster = fixUrl(result.posterPath ?: result.backdropPath)
        val description = result.overview
        val year = result.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val tags = result.genres?.mapNotNull { it.name }
        val rating = when (val v = result.vote) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        val duration = when (val r = result.runtime) {
            is Number -> r.toInt()
            is String -> r.toIntOrNull()
            else -> null
        }
        val actors = result.cast?.mapNotNull { if (it.name != null) Actor(it.name, fixUrl(it.profilePath)) else null }
        val trailer = result.trailer

        val videosList = result.videos ?: result.servers ?: emptyList()
        val videoLinksJson = videosList.toJson()

        return newMovieLoadResponse(title, url, TvType.Movie, videoLinksJson) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            if (rating != null) this.score = Score.from10(rating)
            this.duration = duration
            addActors(actors)
            if (!trailer.isNullOrBlank()) {
                addTrailer("https://www.youtube.com/embed/$trailer")
            }
        }
    }

    private suspend fun serieDetail(url: String): LoadResponse? {
        val isAnime = url.contains("/animes/show/")
        val endpoint = if (isAnime) "animes" else "series"
        val id = url.substringAfter("/$endpoint/show/").substringBefore("/").substringBefore("?").toIntOrNull() ?: return null

        val resText = try {
            apiCall("/api/$endpoint/show/$id/$apiKey", "GET")
        } catch (_: Throwable) {
            null
        } ?: return null

        val result = tryParseJson<SineSerie>(resText) ?: return null

        val name = result.name ?: result.title ?: ""
        val title = name
        val poster = fixUrl(result.posterPath ?: result.backdropPath)
        val description = result.overview
        val year = result.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
        val rating = when (val v = result.vote) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        val actors = result.cast?.mapNotNull { if (it.name != null) Actor(it.name, fixUrl(it.profilePath)) else null }
        val trailer = result.trailer

        val episodes = mutableListOf<Episode>()
        result.seasons?.forEach { seasonItem ->
            val seasonNumber = seasonItem.seasonNumber ?: 1
            seasonItem.episodes?.forEach { ep ->
                val epNumber = ep.episodeNumber ?: 1
                val videosList = ep.videos ?: ep.servers ?: emptyList()
                val videoData = videosList.toJson()
                episodes.add(newEpisode(videoData) {
                    this.name = ep.name ?: ""
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.description = ep.overview
                    this.posterUrl = fixUrl(ep.stillPath)
                })
            }
        }

        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title.trim(), url, tvType, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            if (rating != null) this.score = Score.from10(rating)
            addActors(actors)
            if (!trailer.isNullOrBlank()) {
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.trim()

        if (cleanData.startsWith("[")) {
            val videosList = tryParseJson<List<SineVideo>>(cleanData) ?: emptyList()

            for (video in videosList) {
                val videoUrl = video.link ?: video.url ?: video.embedUrl ?: continue
                val serverName = video.server ?: video.serverName ?: video.name ?: "SineWix"
                val isHls = video.hls == 1 || videoUrl.contains(".m3u8")

                if (videoUrl.contains("mediafire.com") || video.supHosts == 1) {
                    try {
                        loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                    } catch (_: Throwable) {
                        callback.invoke(
                            newExtractorLink(
                                source = "SineWix - $serverName",
                                name = "SineWix - $serverName",
                                url = videoUrl,
                                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = "SineWix - $serverName",
                            name = "SineWix - $serverName",
                            url = videoUrl,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                }
            }
            return true
        }

        if (cleanData.startsWith("http://") || cleanData.startsWith("https://")) {
            val isHls = cleanData.contains(".m3u8")
            if (cleanData.contains("mediafire.com")) {
                try {
                    loadExtractor(cleanData, mainUrl, subtitleCallback, callback)
                } catch (_: Throwable) {
                    callback.invoke(
                        newExtractorLink(
                            source = "SineWix",
                            name = "SineWix",
                            url = cleanData,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "SineWix",
                        name = "SineWix",
                        url = cleanData,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
            }
            return true
        }

        return true
    }
}
