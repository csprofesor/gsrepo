// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

data class FullHDFilmSearchItem(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
    val type: String? = null,
    val thumb_url: String? = null,
    val year: Int? = null,
    val imdb_rating: String? = null
)

data class FullHDFilmPart(
    val id: Long? = null,
    val video_id: Long? = null,
    val episode_id: Long? = null,
    val name: String? = null,
    val lang: String? = null,
    val data: String? = null
)

data class FullHDFilmTrackFx(
    val d: List<Int>? = null,
    val k: String? = null
)

data class FullHDFilmTrack(
    val kind: String? = null,
    val label: String? = null,
    val language: String? = null,
    val file: String? = null,
    val fx: FullHDFilmTrackFx? = null
)

class FullHDFilm : MainAPI() {
    override var mainUrl              = "https://www.hdfilmizle.live"
    override var name                 = "FullHDFilm"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/turkce-altyazili-film-izle-3" to "Altyazılı Filmler",
        "${mainUrl}/tur/yerli-film-izle-1"            to "Yerli Film",
        "${mainUrl}/yabanci-dizi-izle-3"              to "Yabancı Diziler",
        "${mainUrl}/en-cok-izlenen-filmler-hd-2"       to "En Çok İzlenenler",
        "${mainUrl}/category/aksiyon-2"               to "Aksiyon",
        "${mainUrl}/category/animasyon-1"             to "Animasyon",
        "${mainUrl}/category/bilim-kurgu-1"           to "Bilim Kurgu",
        "${mainUrl}/category/dram-1"                  to "Dram",
        "${mainUrl}/category/fantastik-1"             to "Fantastik",
        "${mainUrl}/category/gerilim-1"               to "Gerilim",
        "${mainUrl}/category/komedi-1"                to "Komedi",
        "${mainUrl}/category/korku-1"                 to "Korku",
        "${mainUrl}/category/macera-1"                to "Macera",
        "${mainUrl}/category/romantik-1"              to "Romantik",
        "${mainUrl}/category/savas-1"                 to "Savaş",
        "${mainUrl}/category/suc-1"                   to "Suç"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/${page}"
        val document = app.get(url).document
        val movieBoxes = document.select("a.poster")
        val home = movieBoxes.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.attr("title").ifBlank { this.selectFirst("h2.title")?.text() } ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        val isTv      = href.contains("/dizi/")

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val response = app.post(
                "${mainUrl}/search/",
                data = mapOf("query" to query),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            )
            val searchItems = tryParseJson<List<FullHDFilmSearchItem>>(response.text) ?: emptyList()
            searchItems.mapNotNull { item ->
                val title = item.name ?: return@mapNotNull null
                val slug = item.slug ?: return@mapNotNull null
                val type = item.type ?: "film"
                val isTv = type == "dizi" || slug.contains("dizi/")

                val href = when {
                    slug.startsWith("http") -> slug
                    slug.startsWith("/") -> "${mainUrl}${slug}"
                    isTv && !slug.startsWith("dizi/") -> "${mainUrl}/dizi/${slug}/"
                    else -> "${mainUrl}/${slug}/"
                }

                val posterUrl = fixUrlNull(item.thumb_url)

                if (isTv) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
                }
            }
        } catch (e: Exception) {
            Log.e("FHDF", "Search error", e)
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text() ?: document.selectFirst("meta[property='og:title']")?.attr("content") ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.poster img")?.attr("data-src") ?: document.selectFirst("div.poster img")?.attr("src"))
        val description = document.selectFirst("div.film")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: document.selectFirst("div.poster-desc")?.text()?.trim()
        val tags        = document.select("div.tur.info a, div.poster-meta a").map { it.text() }
        val year        = Regex("""(\d{4})""").find(
            document.selectFirst("div.yayin-tarihi.info")?.text()?.trim()
                ?: document.selectFirst("div.poster-year")?.text()?.trim()
                ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()
        val actors      = document.selectFirst("div.oyuncular")?.ownText()?.split(",")?.map { Actor(it.trim()) } ?: emptyList()

        val isSeries = url.lowercase().contains("/dizi/") || tags.any { it.lowercase().contains("dizi") }

        if (isSeries) {
            val epElements = document.select("div.tab-content a[href*='/sezon-'], div.card-list-item a[href*='/sezon-'], a[href*='/bolum-']")
            val episodes = epElements.mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val text = a.text().trim()
                val sMatch = Regex("""sezon-(\d+)""").find(href) ?: Regex("""(\d+)\.\s*Sezon""").find(text)
                val eMatch = Regex("""bolum-(\d+)""").find(href) ?: Regex("""(\d+)\.\s*Bölüm""").find(text)

                val s = sMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val e = eMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epName = text.split("\n").firstOrNull()?.trim() ?: "Bölüm $e"

                newEpisode(href) {
                    this.name = epName
                    this.season = s
                    this.episode = e
                }
            }.distinctBy { it.data }

            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(newEpisode(url) {
                    this.name = title
                    this.season = 1
                    this.episode = 1
                })
            } else episodes

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors.map { ActorData(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.actors = actors.map { ActorData(it) }
        }
    }

    private fun rot13(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            when (c) {
                in 'a'..'z' -> sb.append(((c - 'a' + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> sb.append(((c - 'A' + 13) % 26 + 'A'.code).toChar())
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun eeDd(encoded: String): String? {
        return try {
            var s = encoded.replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) {
                s += "="
            }
            val decodedBytes = Base64.decode(s, Base64.DEFAULT)
            val decodedStr = String(decodedBytes, Charsets.UTF_8)
            val afterRot13 = rot13(decodedStr)
            afterRot13.reversed()
        } catch (e: Exception) {
            null
        }
    }

    private fun xorDecrypt(d: List<Int>, k: String): String? {
        return try {
            val sb = StringBuilder()
            for (i in d.indices) {
                val charCode = d[i] xor k[i % k.length].code xor ((i * 17 + 13) and 0xFF)
                sb.append(charCode.toChar())
            }
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun processIframe(
        iframeUrl: String,
        sourceName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
                "Referer" to mainUrl
            )
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text

            val extractedUrls = mutableSetOf<String>()

            // 1. EE.dd matches
            val eeMatches = Regex("""EE\.dd\(["']([^"']+)["']\)""").findAll(html)
            for (match in eeMatches) {
                val encoded = match.groupValues[1]
                val dec = eeDd(encoded)
                if (!dec.isNullOrBlank() && (dec.startsWith("http") || dec.startsWith("//"))) {
                    extractedUrls.add(dec)
                }
            }

            // 2. XOR cipher matches: (function(d,k){...})([array], "key")
            val xorMatches = Regex("""\[([0-9,\s]+)]\s*,\s*["']([^"']+)["']""").findAll(html)
            for (match in xorMatches) {
                val arrStr = match.groupValues[1]
                val key = match.groupValues[2]
                val d = arrStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (d.isNotEmpty() && key.isNotBlank()) {
                    val dec = xorDecrypt(d, key)
                    if (!dec.isNullOrBlank() && (dec.startsWith("http") || dec.startsWith("//")) && dec != "[]") {
                        extractedUrls.add(dec)
                    }
                }
            }

            // 3. Subtitles in configs.tracks or tracks
            val tracksMatch = Regex("""configs\.tracks\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)
                ?: Regex("""tracks\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(html)

            if (tracksMatch != null) {
                val tracksJson = tracksMatch.groupValues[1]
                val tracks = tryParseJson<List<FullHDFilmTrack>>(tracksJson)
                tracks?.forEach { track ->
                    var subUrl: String? = null
                    if (track.fx != null && track.fx.d != null && !track.fx.k.isNullOrEmpty()) {
                        subUrl = xorDecrypt(track.fx.d, track.fx.k)
                    } else if (!track.file.isNullOrEmpty()) {
                        subUrl = if (track.file.startsWith("http") || track.file.startsWith("/")) {
                            track.file
                        } else {
                            eeDd(track.file)
                        }
                    }

                    if (!subUrl.isNullOrBlank()) {
                        val fixedSubUrl = when {
                            subUrl.startsWith("http") -> subUrl
                            subUrl.startsWith("//") -> "https:$subUrl"
                            subUrl.startsWith("/") -> {
                                val base = iframeUrl.split("/vr/").firstOrNull()?.split("/ml/")?.firstOrNull()?.removeSuffix("/") ?: mainUrl
                                "$base$subUrl"
                            }
                            else -> fixUrlNull(subUrl)
                        }
                        if (fixedSubUrl != null) {
                            val label = track.label ?: track.language ?: "Türkçe"
                            @Suppress("DEPRECATION")
                            subtitleCallback(SubtitleFile(label, fixedSubUrl))
                        }
                    }
                }
            }

            // 4. Send extracted video URLs
            for (vUrl in extractedUrls) {
                val fixedVUrl = fixUrl(vUrl)
                callback(
                    newExtractorLink(
                        sourceName,
                        sourceName,
                        fixedVUrl,
                        type = if (fixedVUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                    }
                )
                found = true
            }

            // Also load standard extractors if iframeUrl matches known ones
            if (!found || iframeUrl.contains("vidmoly") || iframeUrl.contains("ok.ru") || iframeUrl.contains("dood")) {
                if (loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                }
            }
        } catch (e: Exception) {
            Log.e("FHDF", "Error processing iframe: $iframeUrl", e)
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
            "Referer" to mainUrl
        )

        val mainDoc = app.get(data, headers = headers).document
        val html = mainDoc.html()

        val iframeList = mutableListOf<Pair<String, String>>()

        // 1. Check for JS `let parts = [...]`
        val partsMatch = Regex("""let\s+parts\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (partsMatch != null) {
            val partsJson = partsMatch.groupValues[1]
            val parts = tryParseJson<List<FullHDFilmPart>>(partsJson)
            parts?.forEach { part ->
                val partData = part.data ?: return@forEach
                val iframeSrc = Regex("""src=["']([^"']+)["']""").find(partData)?.groupValues?.get(1) ?: return@forEach
                val name = part.name ?: "Server"
                val lang = part.lang ?: ""
                val sourceName = if (lang.isNotBlank()) "$name ($lang)" else name
                iframeList.add(sourceName to iframeSrc)
            }
        }

        // 2. Fallback to direct iframes on the page
        if (iframeList.isEmpty()) {
            mainDoc.select("iframe.vpx, iframe[data-src], iframe[src]").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("data-src").ifBlank { iframe.attr("src") })
                if (src != null && !src.contains("about:blank")) {
                    iframeList.add("Server" to src)
                }
            }
        }

        Log.d("FHDF", "Found ${iframeList.size} iframes to process")
        var foundLinks = false

        for ((sourceName, iframeUrl) in iframeList) {
            if (processIframe(iframeUrl, sourceName, subtitleCallback, callback)) {
                foundLinks = true
            }
        }

        return foundLinks
    }
}
