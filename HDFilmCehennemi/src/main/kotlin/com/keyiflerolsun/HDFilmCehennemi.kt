package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    // Özel CloudflareInterceptor kaldırıldı!
    // Artık Cloudstream'in kendi native (güncel) CF Bypass (WebView vb.) sistemine devrediyoruz.
    // Bu sayede eklenti "bağlantı hatası" vermeyecek ve CF güncellemelerinden etkilenmeyecek.

    override val mainPage = mainPageOf(
        "${mainUrl}/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        "${mainUrl}/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "${mainUrl}/load/page/sayfano/categories/tavsiye-filmler-izle3/"           to "Tavsiye Filmler",
        "${mainUrl}/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "${mainUrl}/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "${mainUrl}/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("sayfano", page.toString())
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*", 
            "X-Requested-With" to "fetch"
        )
        
        return try {
            val res = app.get(url, headers = headers, referer = mainUrl)
            if (res.isSuccessful && !res.text.contains("Sayfa Bulunamadı") && !res.text.contains("Just a moment")) {
                val jsonText = res.text
                val aa = AppUtils.tryParseJson<HDFC>(jsonText)
                
                if (aa != null && aa.html.isNotEmpty()) {
                    val document = Jsoup.parse(aa.html)
                    val home = document.select("a").mapNotNull { it.toSearchResult() }
                    newHomePageResponse(request.name, home)
                } else {
                    newHomePageResponse(request.name, emptyList())
                }
            } else {
                newHomePageResponse(request.name, emptyList())
            }
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifEmpty { this.text() }
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)
            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src") ?: document.selectFirst("img")?.attr("data-src"))

            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src") ?: document.select("aside.post-info-poster img").lastOrNull()?.attr("src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val actors      = document.select("div.post-info-cast a").mapNotNull {
            val name = it.selectFirst("strong")?.text() ?: return@mapNotNull null
            val pic = fixUrlNull(it.select("img").attr("data-src"))
            Actor(name, pic)
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src"))

            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                this.posterUrl = recPosterUrl
            }
        }

        val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\.\s*Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\.\s*Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // Video extraction
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val responseText = document.outerHtml()

        // Gelecekteki veya test aşamasındaki JSON (videoPlayerData) sistemine geçiş kontrolü
        val dataRegex = Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)""")
        val match = dataRegex.find(responseText)
        
        if (match != null) {
            val jsonString = match.groupValues[1].replace("\\u0022", "\"").replace("\\/", "/")
            val videoMap = AppUtils.tryParseJson<Map<String, List<VideoData>>>(jsonString)
            videoMap?.forEach { (lang, videos) ->
                val langName = when (lang) {
                    "tr" -> "Türkçe Dublaj"
                    "en", "orjinal" -> "Türkçe Altyazılı"
                    "dual" -> "Dual"
                    else -> lang
                }
                videos.forEach { video ->
                    val link = video.link ?: return@forEach
                    val templateBase64 = video.template ?: return@forEach
                    val templateDecoded = String(Base64.decode(templateBase64, Base64.DEFAULT), Charsets.UTF_8)
                    val iframeDoc = Jsoup.parse(templateDecoded)
                    val iframeSrc = iframeDoc.select("iframe").attr("data-src").ifEmpty { iframeDoc.select("iframe").attr("src") }
                    val iframe = iframeSrc.replace("{url}", link).let { if (it.startsWith("//")) "https:$it" else it }
                    
                    processIframe(iframe, "${video.serviceName} $langName", data, subtitleCallback, callback)
                }
            }
            return true
        }

        // Mevcut site (alternative-links) yapısı çözümlenmesi
        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                try {
                    val apiGet = app.get(
                        "${mainUrl}/video/$videoID/", 
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch"
                        ),
                        referer = data
                    ).text
                    
                    val hdfc = AppUtils.tryParseJson<HDFC>(apiGet)
                    val iframeHtml = hdfc?.html.takeIf { !it.isNullOrEmpty() } ?: apiGet
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val iframe = fixUrlNull(iframeDoc.selectFirst("iframe")?.attr("data-src") ?: iframeDoc.selectFirst("iframe")?.attr("src"))

                    if (iframe != null) {
                        processIframe(iframe, source, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e("HDCH", "Error fetching video endpoint: ${e.message}")
                }
            }
        }
        return true
    }

    private suspend fun processIframe(iframeSrc: String, source: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var iframe = iframeSrc
        if (iframe.contains("rapidrame")) {
            iframe = "${mainUrl}/rplayer/" + iframe.substringAfter("?rapidrame_id=")
        }
        if (iframe.contains("mobi")) {
            val iframeDoc = app.get(iframe, referer = referer).document
            iframe = fixUrlNull(iframeDoc.selectFirst("iframe")?.attr("data-src") ?: iframeDoc.selectFirst("iframe")?.attr("src")) ?: iframe
        }
        
        Log.d("HDCH", "Processing iframe: $iframe for source $source")

        if (iframe.contains("vidload")) {
            loadExtractor(iframe, referer, subtitleCallback, callback)
        } else if (iframe.contains(mainUrl) && iframe.contains("/rplayer/")) {
            invokeLocalSource(source, iframe, subtitleCallback, callback)
        } else {
            // Vidmoly, StreamSB ve alternatif diğer sunucular için Native Cloudstream Extractor'a devredildi
            loadExtractor(iframe, referer, subtitleCallback, callback)
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        try {
            val script = app.get(url, referer = "${mainUrl}/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
            val unpackedScript = getAndUnpack(script)
            val decryptedUrl = decryptLocalUrl(unpackedScript) ?: return
            val lastUrl = decryptedUrl.substringAfter("https").let { "https$it" }
            
            val subData = script.substringAfter("tracks: [", "").substringBefore("]", "")
            if (subData.isNotEmpty()) {
                AppUtils.tryParseJson<List<SubSource>>("[$subData]")?.filter { it.kind == "captions"}?.forEach {
                    val subtitleUrl = if (it.file?.startsWith("http") == true) it.file else "${mainUrl}${it.file}/"
                    subtitleCallback(newSubtitleFile(it.label ?: it.language.toString(), subtitleUrl))
                }
            }
            callback.invoke(
                newExtractorLink(
                    source  = source,
                    name    = source,
                    url     = lastUrl,
                    type    = ExtractorLinkType.M3U8
                ) {
                    headers = mapOf("Referer" to "${mainUrl}/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e("HDCH", "invokeLocalSource failed: ${e.message}")
        }
    }

    data class DecOp(val name: String, val rotShift: Int = 0)

    private fun decryptLocalUrl(unpackedScript: String): String? {
        try {
            val partsMatch = """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)""".toRegex().find(unpackedScript)
            val parts = partsMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('\'', '"').replace("\\/", "/") 
            } ?: return null

            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(unpackedScript)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            val funcBody = unpackedScript.substringAfter("function dc_").substringBefore("function d1x")
            val operations = mutableListOf<Pair<Int, DecOp>>()

            var index = funcBody.indexOf("atob(")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("atob")))
                index = funcBody.indexOf("atob(", index + 1)
            }

            index = funcBody.indexOf("reverse")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("reverse")))
                index = funcBody.indexOf("reverse", index + 1)
            }

            index = funcBody.indexOf("replace")
            while (index >= 0) {
                val block = funcBody.substring(index, minOf(index + 300, funcBody.length))
                var shift = 13
                val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(block)
                if (rotShiftMatch != null) {
                    shift = rotShiftMatch.groupValues[1].toInt()
                } else {
                    val rotShiftMatch2 = """o\s*-\s*base\s*([+-])\s*(\d+)""".toRegex().find(block)
                    if (rotShiftMatch2 != null) {
                        val sign = rotShiftMatch2.groupValues[1]
                        val num = rotShiftMatch2.groupValues[2].toInt()
                        shift = if (sign == "-") (26 - num) % 26 else num
                    }
                }
                operations.add(Pair(index, DecOp("rot", shift)))
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }
            var result = parts.joinToString("")

            for (op in operations) {
                when (op.second.name) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) {
                            paddedResult += "="
                        }
                        result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        val rotShift = op.second.rotShift
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()
        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: ${e.message}")
            return null
        }
    }

    private data class SubSource(
        @JsonProperty("file")    val file: String?  = null,
        @JsonProperty("label")   val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind")    val kind: String?  = null
    )

    data class VideoData(
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("service_name") val serviceName: String? = null,
        @JsonProperty("template") val template: String? = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
    
    data class HDFC(
        @JsonProperty("html") val html: String = "",
        @JsonProperty("meta") val meta: Meta? = null
    )

    data class Meta(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("canonical") val canonical: String? = null,
        @JsonProperty("keywords") val keywords: Boolean? = null
    )
}
