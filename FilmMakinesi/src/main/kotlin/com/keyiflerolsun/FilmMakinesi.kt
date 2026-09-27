package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/" to "Son Eklenen Filmler",
        "${mainUrl}/tur/aksiyon-fmy54y/film/" to "Aksiyon Filmleri",
        "${mainUrl}/yabanci-dizi-izle-1/" to "Diziler",
        "${mainUrl}/yil/2026-fmfbkb/film/" to "2026 Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/sayfa/$page/"
        val doc = app.get(url, referer = "${mainUrl}/", interceptor = interceptor).document
        val home = parseHomePage(doc)
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun parseHomePage(doc: Document): List<SearchResponse> {
        return doc.select("a.item, a.slide, div.item-relative a.item")
            .mapNotNull { parseSearchElement(it) }
            .distinctBy { it.url }
    }

    private fun parseSearchElement(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = element.attr("data-title").ifEmpty { null }
            ?: element.selectFirst(".title, .item-title, h4")?.text()?.trim()
            ?: link.attr("title").ifEmpty { null }
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = element.selectFirst("img")?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            fixUrlNull(src)
        }

        val score = element.attr("data-score").ifEmpty { null }
            ?: element.selectFirst(".rating, .imdb-score span")?.text()?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("${mainUrl}/arama/?s=$query", referer = "${mainUrl}/", interceptor = interceptor).document
            doc.select("a.item, div.item-relative a.item")
                .mapNotNull { parseSearchElement(it) }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = "${mainUrl}/", interceptor = interceptor).document

        val title = doc.selectFirst("h1, meta[property='og:title']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }?.replace(" - FilmMakinesi", "")?.replace(" izle", "")?.trim() ?: return null

        val poster = fixUrlNull(doc.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = doc.selectFirst("meta[property='og:description'], div.description, div.info-content .description")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text().trim()
        }
        val year = doc.selectFirst("div.info span:first-child, span.year")?.text()?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
        val score = doc.selectFirst("div.imdb-score span, .rating")?.text()?.trim()

        if (url.contains("/dizi/")) {
            val episodes = doc.select("a[href*='bolum'], div.episodes a").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epTitle = a.text().trim()
                val season = Regex("""(\d+)\.\s*Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = Regex("""(\d+)\.\s*B[öo]l[üu]m""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/bolum-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                newEpisode(epHref) {
                    name = epTitle
                    this.season = season
                    this.episode = episode
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.score = Score.from10(score)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.score = Score.from10(score)
        }
    }

    private fun decodeCloseLoadSource(encoded: String): String? {
        return try {
            val parts = encoded.split("#").toMutableList()
            if (parts.size < 3) return null

            val d26e = parts.size - 2
            val g7kx = d26e % 7
            val g87 = 8 + (d26e % 5)

            if (g87 !in parts.indices) return null
            val qg00 = parts.removeAt(g87)

            if (g7kx !in parts.indices) return null
            val e217w = parts.removeAt(g7kx)

            var mam2j = parts.joinToString("")

            var p1m2w = 0
            var pv4 = 0
            for (i in e217w.indices) {
                val r2s = e217w[i].code
                p1m2w = (p1m2w * 37 + r2s) % 241
                pv4 = (pv4 + ((r2s shl 1) xor i)) and 255
            }

            val lr7 = (p1m2w * 3 + pv4) % 256
            val w70 = (pv4 % 11) + 5
            var w4l0 = ((pv4 * 251 + p1m2w) % 65519) + 1

            fun shiftLetters(value: String, key: Int): String {
                return buildString(value.length) {
                    value.forEach { ch ->
                        when {
                            ch in 'A'..'Z' -> append(((ch.code - 'A'.code + key) % 26 + 'A'.code).toChar())
                            ch in 'a'..'z' -> append(((ch.code - 'a'.code + key) % 26 + 'a'.code).toChar())
                            else -> append(ch)
                        }
                    }
                }
            }

            for (i in qg00.indices.reversed()) {
                when (val op = qg00[i]) {
                    '7' -> mam2j = String(
                        android.util.Base64.decode(mam2j, android.util.Base64.DEFAULT),
                        Charsets.ISO_8859_1
                    )
                    '3' -> mam2j = mam2j.reversed()
                    else -> {
                        val key = (26 - ((op.code - 96) % 26)) % 26
                        mam2j = shiftLetters(mam2j, key)
                    }
                }
            }

            if (e217w.length > 4096) {
                mam2j = String(
                    android.util.Base64.decode(mam2j, android.util.Base64.DEFAULT),
                    Charsets.ISO_8859_1
                )
            }

            val n = mam2j.length
            val rrx31 = IntArray(n)
            for (i in n - 1 downTo 1) {
                w4l0 = (w4l0 * 97 + 41) % 65519
                rrx31[i] = w4l0 % (i + 1)
            }

            val chars = mam2j.toCharArray()
            for (i in 1 until n) {
                val j = rrx31[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }

            var wqoz1 = lr7
            return buildString(n) {
                chars.forEach { ch ->
                    val r2s = ch.code
                    wqoz1 = (wqoz1 * 5 + w70) % 256
                    append((r2s xor wqoz1).toChar())
                    wqoz1 = (wqoz1 + r2s) % 256
                }
            }
        } catch (e: Exception) {
            Log.d(name, "CloseLoad decode failed: " + e.message)
            null
        }
    }

    private suspend fun loadCloseLoad(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val html = app.get(
                embedUrl,
                referer = mainUrl,
                interceptor = interceptor
            ).text

            val encoded = Regex(
                """var\s+m97\s*=\s*ag9br\("([^"]+)"\.split\("#"\)\)"""
            ).find(html)?.groupValues?.getOrNull(1)

            val sourceUrl = encoded?.let(::decodeCloseLoadSource)
            if (sourceUrl.isNullOrBlank()) {
                Log.d(name, "CloseLoad: m97 source not found")
                false
            } else {
                callback(
                    newExtractorLink(
                        source = "CloseLoad",
                        name = "CloseLoad",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = embedUrl
                        quality = Qualities.Unknown.value
                    }
                )

                Regex(
                    """"file"\s*:\s*"(https?://[^"]+\.vtt)"[^}]*?"label"\s*:\s*"([^"]+)""""
                ).findAll(html).forEach { match ->
                    subtitleCallback(
                        newSubtitleFile(
                            match.groupValues[2],
                            match.groupValues[1].replace("\\/", "/")
                        )
                    )
                }

                Log.d(name, "CloseLoad source: " + sourceUrl)
                true
            }
        } catch (e: Exception) {
            Log.d(name, "CloseLoad failed: " + e.message)
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl, interceptor = interceptor).document
        val candidates = doc.select("iframe").mapNotNull {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            fixUrlNull(src)?.takeUnless { it.contains("youtube.com") || it.contains("youtu.be") }
        }.distinct().toMutableList()

        doc.select(".video-parts a[data-video_url]").forEach {
            val src = fixUrlNull(it.attr("data-video_url"))
            if (src != null && !candidates.contains(src)) candidates.add(0, src)
        }

        var found = false
        candidates.forEach { embedUrl ->
            try {
                if (embedUrl.contains("closeload.filmmakinesi.to", ignoreCase = true)) {
                    if (loadCloseLoad(embedUrl, subtitleCallback, callback)) found = true
                } else {
                    loadExtractor(embedUrl, data, subtitleCallback) { link ->
                        found = true
                        callback(link)
                    }
                }
            } catch (e: Exception) {
                Log.d(name, "Extractor failed: " + embedUrl + " - " + e.message)
            }
        }
        return found
    }
}
