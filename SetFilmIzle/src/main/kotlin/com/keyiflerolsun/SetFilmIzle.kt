// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.json.JSONObject

class SetFilmIzle : MainAPI() {
    override var mainUrl              = "https://www.setfilmizle.ltd"
    override var name                 = "SetFilmIzle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/"                       to "Son Eklenenler",
        "${mainUrl}/turkce-dublaj-filmler/" to "Türkçe Dublaj",
        "${mainUrl}/yerli-filmler/"         to "Yerli Filmler",
        "${mainUrl}/tur/aile/"              to "Aile",
        "${mainUrl}/tur/aksiyon/"           to "Aksiyon",
        "${mainUrl}/tur/animasyon/"         to "Animasyon",
        "${mainUrl}/tur/belgesel/"          to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/"       to "Bilim-Kurgu",
        "${mainUrl}/tur/biyografi/"         to "Biyografi",
        "${mainUrl}/tur/dini/"              to "Dini",
        "${mainUrl}/tur/dram/"              to "Dram",
        "${mainUrl}/tur/fantastik/"         to "Fantastik",
        "${mainUrl}/tur/genclik/"           to "Gençlik",
        "${mainUrl}/tur/gerilim/"           to "Gerilim",
        "${mainUrl}/tur/gizem/"             to "Gizem",
        "${mainUrl}/tur/komedi/"            to "Komedi",
        "${mainUrl}/tur/korku/"             to "Korku",
        "${mainUrl}/tur/macera/"            to "Macera",
        "${mainUrl}/tur/mini-dizi/"         to "Mini Dizi",
        "${mainUrl}/tur/muzik/"             to "Müzik",
        "${mainUrl}/tur/program/"           to "Program",
        "${mainUrl}/tur/romantik/"          to "Romantik",
        "${mainUrl}/tur/savas/"             to "Savaş",
        "${mainUrl}/tur/spor/"              to "Spor",
        "${mainUrl}/tur/suc/"               to "Suç",
        "${mainUrl}/tur/tarih/"             to "Tarih",
        "${mainUrl}/tur/western/"           to "Western"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        val home     = document.select("a.card-link, article.card, div.items article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a") ?: this.parent()
        val href = fixUrlNull(aTag?.attr("href") ?: this.attr("href")) ?: return null
        val title = this.selectFirst("h2.card-ad, h3.card-ad, h2, h3, .hcard-title")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: aTag?.attr("title")?.trim()
            ?: return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("src")?.takeIf { it.isNotEmpty() } ?: img?.attr("data-src"))

        val score = this.selectFirst("span.badge-imdb, span.hcard-puan, .badge-imdb, .imdb, .rating")?.text()?.replace("IMDb", "")?.trim()

        return if (href.contains("/dizi/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score     = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val search = app.get("${mainUrl}/wp-admin/admin-ajax.php?action=stf_live_search&keyword=${query}").text
        val results = mutableListOf<SearchResponse>()
        try {
            val json = JSONObject(search)
            json.keys().forEach { id ->
                val item = json.optJSONObject(id) ?: return@forEach
                val title = item.optString("title")
                val href = fixUrlNull(item.optString("url")) ?: return@forEach
                val rawImg = item.optString("img")
                val posterUrl = fixUrlNull(if (rawImg.isNotEmpty()) rawImg.replace("-50x50", "") else null)

                val extra = item.optJSONObject("extra")
                val imdb = extra?.optString("imdb")

                if (href.contains("/dizi/")) {
                    results.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.score     = Score.from10(imdb)
                    })
                } else {
                    results.add(newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.score     = Score.from10(imdb)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("STF", "Search error: ${e.message}")
        }
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title           = document.selectFirst("h1")?.text()?.split("(")?.firstOrNull()?.replace(" izle", "")?.trim() ?: return null
        val poster          = fixUrlNull(document.selectFirst(".fbox-cover img, .player-poster img, div.poster img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })
        val description     = document.selectFirst("div.fbox-story, div.wp-content p, p.hcard-ozet, div.description")?.text()?.trim()
        val year            = document.selectFirst("a[href*='/yil/'], div.extra span.C a")?.text()?.trim()?.toIntOrNull()
        val tags            = document.select("div.fbox-info a[href*='/tur/'], div.sgeneros a, dd.tumu a").map { it.text().trim() }.distinct()
        val duration        = document.selectFirst("div.fbox-info span:contains(Süre)")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            ?: document.selectFirst("span.runtime, div#info span:containsOwn(Dakika)")?.text()?.split(" ")?.first()?.trim()?.toIntOrNull()
        val recommendations = document.select("a.card-link").mapNotNull { it.toSearchResult() }.distinctBy { it.url }.filter { it.url != url }
        val actors          = document.select("a[href*='/oyuncu/']").map { a ->
            val name = a.selectFirst("img")?.attr("alt")?.ifEmpty { null } ?: a.text().trim()
            val image = fixUrlNull(a.selectFirst("img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } })
            Actor(name, image)
        }.distinctBy { it.name }
        val trailer         = Regex("""embed/(.*)\?rel""").find(document.html())?.groupValues?.get(1)?.let { "https://www.youtube.com/embed/$it" }
        val score           = document.selectFirst("b.imdb-score, span.badge-imdb, span.hcard-puan, .imdb")?.text()?.replace("IMDb", "")?.trim()

        if (url.contains("/dizi/")) {
            val episodes = document.select("a[href*='/bolum/'], div#episodes ul.episodios li a").mapNotNull { element ->
                val epHref = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
                val match = Regex("""(\d+)-sezon-(\d+)-bolum""").find(epHref)
                val epSeason = match?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Sezon""").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1
                val epEpisode = match?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("""(\d+)\.\s*Bölüm""").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val epName = element.selectFirst(".title, .ep-name, .name")?.text()?.trim()
                    ?: element.ownText().trim().takeIf { it.isNotEmpty() }
                    ?: "$epSeason. Sezon $epEpisode. Bölüm"

                newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                }
            }.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.plot            = description
                this.year            = year
                this.tags            = tags
                this.duration        = duration
                this.score           = Score.from10(score)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.plot            = description
            this.year            = year
            this.tags            = tags
            this.duration        = duration
            this.score           = Score.from10(score)
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("STF", "data » $data")
        val document = app.get(data).document

        val nonce = Regex("""video:\s*"([^"]+)"""").find(document.html())?.groupValues?.get(1)
            ?: document.selectFirst("div#playex")?.attr("data-nonce")
            ?: ""

        val defaultPostId = document.selectFirst("div.fplayer")?.attr("data-post-id") ?: ""

        val buttons = document.select(".fsrc-list button, button.fsrc, button[data-player-name], nav.player a")

        val playerRequests = if (buttons.isNotEmpty()) {
            buttons.map { element ->
                val sourceId = element.attr("data-post-id").ifEmpty { defaultPostId }
                val name = element.attr("data-player-name").ifEmpty { element.attr("data-name") }.ifEmpty { element.text().trim() }
                val partKey = element.attr("data-part-key").takeIf { it.isNotEmpty() }
                Triple(name, sourceId, partKey)
            }.distinct()
        } else if (defaultPostId.isNotEmpty()) {
            listOf(Triple("SetPlay", defaultPostId, null))
        } else {
            emptyList()
        }

        playerRequests.forEach { (name, sourceId, partKey) ->
            if (sourceId.contains("event")) return@forEach
            if (sourceId.isEmpty()) return@forEach

            val sourceBody = try {
                app.post(
                    url = "${mainUrl}/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "get_video_url",
                        "nonce" to nonce,
                        "post_id" to sourceId,
                        "player_name" to name,
                        "part_key" to (partKey ?: "")
                    ),
                    headers = mapOf(
                        "Referer" to data,
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
            } catch (e: Exception) {
                Log.e("STF", "Ajax error: ${e.message}")
                ""
            }

            val json = try { JSONObject(sourceBody) } catch (_: Exception) { null }
            val dataObj = json?.optJSONObject("data")
            val streamObj = dataObj?.optJSONObject("stream")
            val sourceIframe = streamObj?.optString("url")
                ?: dataObj?.optString("url")
                ?: return@forEach

            Log.d("STF", "iframe » $sourceIframe")

            val finalUrl = if (sourceIframe.contains("setplay") || sourceIframe.contains("fastplay")) {
                sourceIframe
            } else {
                if (partKey != null) "$sourceIframe?partKey=$partKey" else sourceIframe
            }

            loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
