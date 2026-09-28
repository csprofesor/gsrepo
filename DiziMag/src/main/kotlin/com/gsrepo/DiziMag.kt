package com.gsrepo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

class DiziMag : MainAPI() {
    override var mainUrl = "https://dizimag.one"
    override var name = "DiziMag"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 250L
    override var sequentialMainPageScrollDelay = 250L

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/" to "Son Eklenen Bölümler",
        "${mainUrl}/asya-dizileri/" to "Asya Dizileri",
        "${mainUrl}/dizi-arsivi/?filtrele=trend&sirala=DESC" to "Trend Diziler",
        "${mainUrl}/dizi-arsivi/?filtrele=imdb&sirala=DESC" to "En Yüksek IMDb",
        "${mainUrl}/dizi-arsivi/?filtrele=tarih&sirala=DESC" to "Son Eklenen Diziler",

        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantezi/" to "Fantastik",
        "${mainUrl}/tur/gizem/" to "Gizem",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/suc/" to "Suç",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            if (base.contains("?")) {
                val path = base.substringBefore("?")
                val query = base.substringAfter("?")
                "$path/page/$page/?$query"
            } else {
                "$base/page/$page/"
            }
        }

        val document = app.get(url).document
        val home = mutableListOf<SearchResponse>()

        document.select("div.poster").forEach { posterDiv ->
            val aTag = posterDiv.selectFirst("a") ?: return@forEach
            val rawHref = aTag.attr("href")
            val href = fixUrlNull(rawHref) ?: return@forEach

            val imgTag = posterDiv.selectFirst("img")
            val title = imgTag?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: imgTag?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: aTag.text().trim().takeIf { it.isNotBlank() }
                ?: return@forEach

            val posterUrl = fixUrlNull(imgTag?.attr("src"))

            val isTvSeries = href.contains("/dizi/") || !href.contains("/film/")
            if (isTvSeries) {
                home.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            } else {
                home.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }
        }

        if (home.isEmpty()) {
            document.select("ul.alphabetical-category-list li a, div.list-series a").forEach { aTag ->
                val rawHref = aTag.attr("href")
                val href = fixUrlNull(rawHref) ?: return@forEach
                if (!href.contains("/dizi/") && !href.contains("/film/")) return@forEach

                val title = aTag.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: aTag.text().trim().takeIf { it.isNotBlank() }
                    ?: return@forEach

                val isTvSeries = href.contains("/dizi/")
                if (isTvSeries) {
                    home.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries))
                } else {
                    home.add(newMovieSearchResponse(title, href, TvType.Movie))
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url }, hasNext = home.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query}"
        val document = app.get(url).document

        val results = mutableListOf<SearchResponse>()

        document.select("div.poster").forEach { posterDiv ->
            val aTag = posterDiv.selectFirst("a") ?: return@forEach
            val rawHref = aTag.attr("href")
            val href = fixUrlNull(rawHref) ?: return@forEach

            val imgTag = posterDiv.selectFirst("img")
            val title = imgTag?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: imgTag?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: aTag.text().trim().takeIf { it.isNotBlank() }
                ?: return@forEach

            val posterUrl = fixUrlNull(imgTag?.attr("src"))

            val isTvSeries = href.contains("/dizi/")
            if (isTvSeries) {
                results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                })
            } else {
                results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                })
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val titleRaw = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null

        val title = titleRaw.substringBefore(" izle").substringBefore(" |").trim()

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("img")?.attr("src")
        )

        val description = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val rating = document.select("div.episode-date, div.imdb").text()
            .let { Regex("""\b(\d+(?:\.\d+)?)\b""").find(it)?.value }

        val isTvSeries = url.contains("/dizi/")

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()

            document.select("a[href*='-sezon-'], a[href*='-bolum-']").forEach { aTag ->
                val epHref = fixUrlNull(aTag.attr("href")) ?: return@forEach
                if (!epHref.contains("sezon") && !epHref.contains("bolum")) return@forEach

                val epText = aTag.text().trim()
                val seasonMatch = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE).find(epText)
                val episodeMatch = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE).find(epText)

                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episodeNum = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText
                        this.season = seasonNum
                        this.episode = episodeNum
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
                this.score = Score.from10(rating)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.score = Score.from10(rating)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document

        val iframes = document.select("iframe").mapNotNull { fixUrlNull(it.attr("src")) }
        var foundLinks = false

        for (iframe in iframes) {
            try {
                val iframeHtml = app.get(iframe, headers = mapOf("Referer" to "$mainUrl/")).text
                val sourceMatch = Regex("""var\s+SOURCE\s*=\s*["']([^"']+)["']""").find(iframeHtml)
                if (sourceMatch != null) {
                    val sourceUrl = sourceMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = sourceUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://ksdpictures.site/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            } catch (_: Exception) { }

            loadExtractor(iframe, "$mainUrl/", subtitleCallback, callback)
        }

        return foundLinks || iframes.isNotEmpty()
    }
}
