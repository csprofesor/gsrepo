package com.gsrepo

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class CizgiMax : MainAPI() {
    override var mainUrl = "https://cizgimax.online"
    override var name = "ÇizgiMax"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Anime, TvType.Movie, TvType.Cartoon)

    // Set to track distinct URLs across main page categories so movies do not repeat across categories
    private val seenUrls = ConcurrentHashMap.newKeySet<String>()

    private val mapper = ObjectMapper().registerKotlinModule().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Son Eklenenler",
        "${mainUrl}/diziler/cizgi-film/" to "Çizgi Filmler",
        "${mainUrl}/diziler/anime/" to "Animeler",
        "${mainUrl}/diziler/dizi/" to "Diziler",
        "${mainUrl}/film/" to "Filmler",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/fantezi/" to "Fantezi",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/gizem/" to "Gizem"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1 && request.name == mainPage.first().name) {
            seenUrls.clear()
        }

        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(url).document
        val items = doc.select(".film-item")

        val list = mutableListOf<SearchResponse>()

        for (item in items) {
            val res = item.toSearchResult() ?: continue
            val normUrl = fixUrl(res.url)
            if (!seenUrls.contains(normUrl)) {
                seenUrls.add(normUrl)
                list.add(res)
            }
        }

        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val rawHref = a.attr("href")
        if (rawHref.isBlank() || rawHref == "#") return null
        val href = fixUrl(rawHref)

        val img = selectFirst("img")
        val rawPoster = (img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")?.ifBlank { null })?.trim()
        val poster = if (!rawPoster.isNullOrBlank() && !rawPoster.startsWith("data:image")) fixUrl(rawPoster) else null

        val titleEl = selectFirst(".title, .name, h2, h3, h4")
        val title = titleEl?.text()?.trim()
            ?: a.attr("title").ifBlank { null }
            ?: img?.attr("alt")?.ifBlank { null }
            ?: a.text().trim()

        if (title.isBlank()) return null

        val tvType = when {
            href.contains("/anime/") || href.contains("anime") -> TvType.Anime
            href.contains("cizgi-film") -> TvType.Cartoon
            href.contains("/film/") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, tvType) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${mainUrl}/ara/?q=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(url).document
        return doc.select(".film-item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.replace(" - ÇizgiMax", "")?.trim()
            ?: "Bilinmeyen Başlık"

        val poster = doc.select("img").mapNotNull { img ->
            val src = (img.attr("data-src").ifBlank { null } ?: img.attr("src")).trim()
            if (src.isBlank()) return@mapNotNull null
            val srcLower = src.lowercase()
            if (srcLower.contains("logo") || srcLower.contains("maskot") || srcLower.contains("avatar") || srcLower.contains("footer") || srcLower.contains("discord")) {
                return@mapNotNull null
            }
            if (srcLower.contains("/locandine/") || srcLower.contains("http") || srcLower.contains("poster") || srcLower.contains("cover")) {
                fixUrl(src)
            } else {
                null
            }
        }.firstOrNull()

        val plot = doc.selectFirst(".desc, .synopsis, .summary, .overview, .text, .plot, .info-desc")?.text()?.trim()
        val genres = doc.select("a[href*=/tur/]").map { it.text().trim() }.distinct()

        val episodeElements = doc.select("a[href*=-bolum-izle/], a[href*=-sezon-], a[href*=/episode-]")
        val episodes = mutableListOf<Episode>()

        for (el in episodeElements) {
            val href = el.attr("href")
            if (href.isBlank() || href == url || href.startsWith("#")) continue
            val epUrl = fixUrl(href)

            val m1 = Regex("""-(\d+)-sezon-(\d+)-bolum-izle""").find(epUrl)
            val m2 = Regex("""/episode-\d+-s(\d+)e(\d+)""").find(epUrl)
            val m3 = Regex("""-s(\d+)e(\d+)""").find(epUrl)

            val seasonNum = m1?.groupValues?.get(1)?.toIntOrNull()
                ?: m2?.groupValues?.get(1)?.toIntOrNull()
                ?: m3?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            val epNum = m1?.groupValues?.get(2)?.toIntOrNull()
                ?: m2?.groupValues?.get(2)?.toIntOrNull()
                ?: m3?.groupValues?.get(2)?.toIntOrNull()
                ?: el.text().trim().toIntOrNull()
                ?: 1

            episodes.add(
                newEpisode(epUrl) {
                    this.name = "${seasonNum}. Sezon ${epNum}. Bölüm"
                    this.season = seasonNum
                    this.episode = epNum
                }
            )
        }

        val sortedEpisodes = episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (sortedEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = "$mainUrl/")
        val html = response.text

        val serversList = mutableListOf<Map<String, Any?>>()

        val mServers = Regex("""var servers\s*=\s*JSON\.parse\(atob\((".*?")\)\)""").find(html)
        if (mServers != null) {
            runCatching {
                val b64Str = mapper.readValue(mServers.groupValues[1], String::class.java)
                val jsonStr = String(java.util.Base64.getDecoder().decode(b64Str))
                val list: List<Map<String, Any?>> = mapper.readValue(jsonStr, mapper.typeFactory.constructCollectionType(List::class.java, Map::class.java))
                serversList.addAll(list)
            }
        }

        val mLang = Regex("""var serversByLang\s*=\s*JSON\.parse\(atob\((".*?")\)\)""").find(html)
        if (mLang != null) {
            runCatching {
                val b64Str = mapper.readValue(mLang.groupValues[1], String::class.java)
                val jsonStr = String(java.util.Base64.getDecoder().decode(b64Str))
                val dict: Map<String, List<Map<String, Any?>>> = mapper.readValue(jsonStr, mapper.typeFactory.constructMapType(Map::class.java, String::class.java, List::class.java))
                dict.values.forEach { serversList.addAll(it) }
            }
        }

        val uniqueServers = serversList.distinctBy {
            it["qualitiesUrl"] as? String ?: it["resolveUrl"] as? String ?: it["src"] as? String ?: it["streamUrl"] as? String ?: it.toString()
        }

        for (server in uniqueServers) {
            val qualitiesUrl = server["qualitiesUrl"] as? String
            val resolveUrl = server["resolveUrl"] as? String
            val src = server["src"] as? String
            val streamUrl = server["streamUrl"] as? String
            val label = server["label"] as? String ?: "ÇizgiMax"

            if (!qualitiesUrl.isNullOrEmpty()) {
                val qFull = fixUrl(qualitiesUrl)
                runCatching {
                    val qRes = app.get(qFull, referer = data).text
                    val node = mapper.readTree(qRes)
                    val qualitiesArr = node.get("qualities")
                    if (qualitiesArr != null && qualitiesArr.isArray) {
                        for (qItem in qualitiesArr) {
                            val rawUrl = qItem.get("url")?.asText() ?: continue
                            val qLabel = qItem.get("label")?.asText() ?: qItem.get("q")?.asText() ?: "Auto"
                            callback.invoke(
                                newExtractorLink(
                                    source = "ÇizgiMax ($label)",
                                    name = "ÇizgiMax ($label - $qLabel)",
                                    url = fixUrl(rawUrl),
                                    type = INFER_TYPE
                                ) {
                                    headers = mapOf("Referer" to data)
                                    quality = getQualityFromName(qLabel)
                                }
                            )
                        }
                    }
                }
            } else if (!resolveUrl.isNullOrEmpty()) {
                val resolveFull = fixUrl(resolveUrl)
                runCatching {
                    val res = app.get(resolveFull, referer = data).text
                    val node = mapper.readTree(res)
                    val embedId = node.get("id")?.asText()
                    if (!embedId.isNullOrEmpty()) {
                        val tauUrl = "https://tau-video.xyz/api/video/$embedId"
                        val tauRes = app.get(tauUrl).text
                        val tauNode = mapper.readTree(tauRes)
                        val urls = tauNode.get("urls")
                        if (urls != null && urls.isArray) {
                            for (u in urls) {
                                val videoUrl = u.get("url")?.asText() ?: continue
                                val qLabel = u.get("label")?.asText() ?: "Auto"
                                callback.invoke(
                                    newExtractorLink(
                                        source = "ÇizgiMax ($label)",
                                        name = "ÇizgiMax ($label - $qLabel)",
                                        url = videoUrl,
                                        type = INFER_TYPE
                                    ) {
                                        headers = mapOf("Referer" to "https://tau-video.xyz/")
                                        quality = getQualityFromName(qLabel)
                                    }
                                )
                            }
                        }
                    }
                }
            } else if (!src.isNullOrEmpty()) {
                val srcFull = fixUrl(src)
                runCatching {
                    val oynatRes = app.get(srcFull, referer = data)
                    val redirectedUrl = oynatRes.url
                    val oynatHtml = oynatRes.text

                    // Delegate redirected URL to registered extractors (Sibnet, DzenRu, StreamRuby, Voe, etc.)
                    if (!redirectedUrl.contains("cizgimax.online")) {
                        loadExtractor(redirectedUrl, data, subtitleCallback, callback)
                    }

                    val unpacked = try { getAndUnpack(oynatHtml) } catch (_: Exception) { "" }
                    val searchScope = if (unpacked.isNotBlank()) "$unpacked\n$oynatHtml" else oynatHtml

                    // Search for m3u8 links
                    val m3u8Matches = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(searchScope)
                    for (m in m3u8Matches) {
                        M3u8Helper.generateM3u8(
                            source = "ÇizgiMax ($label)",
                            streamUrl = m.value,
                            referer = redirectedUrl
                        ).forEach(callback)
                    }

                    // Search for okcdn direct mp4 / hls links
                    val okcdnMatches = Regex("""https?://vd\d+\.okcdn\.ru/[^\s"'<>]+""").findAll(searchScope)
                    for (m in okcdnMatches) {
                        val linkUrl = m.value
                        if (linkUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = "ÇizgiMax ($label)",
                                streamUrl = linkUrl,
                                referer = "https://cizgimax.online/"
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = "ÇizgiMax ($label)",
                                    name = "ÇizgiMax ($label)",
                                    url = linkUrl,
                                    type = INFER_TYPE
                                ) {
                                    headers = mapOf("Referer" to "https://cizgimax.online/")
                                    quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }

                    // Search for dzen embed
                    val dzenMatch = Regex("""https?://dzen\.ru/embed/[^\s"'<>]+""").find(searchScope)
                    if (dzenMatch != null) {
                        loadExtractor(dzenMatch.value, srcFull, subtitleCallback, callback)
                    }

                    // Search for other external iframe URLs
                    val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(searchScope)
                    if (iframeMatch != null) {
                        val iframeUrl = fixUrl(iframeMatch.groupValues[1])
                        if (!iframeUrl.contains("cizgimax")) {
                            loadExtractor(iframeUrl, srcFull, subtitleCallback, callback)
                        }
                    }
                }
            } else if (!streamUrl.isNullOrEmpty()) {
                val fullStream = fixUrl(streamUrl)
                callback.invoke(
                    newExtractorLink(
                        source = "ÇizgiMax ($label)",
                        name = "ÇizgiMax ($label)",
                        url = fullStream,
                        type = INFER_TYPE
                    ) {
                        headers = mapOf("Referer" to data)
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
