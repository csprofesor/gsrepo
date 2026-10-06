package com.gsrepo

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Request
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LocalSinemaTvAzServer {
    private var serverSocket: ServerSocket? = null
    private var activePort: Int = 0
    private val streamMap = ConcurrentHashMap<String, StreamInfo>()

    data class StreamInfo(
        val url: String,
        val filename: String,
        val headers: Map<String, String>
    )

    @Synchronized
    fun registerStream(id: String, streamInfo: StreamInfo): String {
        if (serverSocket == null || serverSocket!!.isClosed) {
            runCatching {
                serverSocket = ServerSocket(0)
                activePort = serverSocket!!.localPort
                Log.d("LocalSinemaTvAzServer", "SERVER_STARTED_PORT=$activePort")
                GlobalScope.launch(Dispatchers.IO) {
                    while (serverSocket != null && !serverSocket!!.isClosed) {
                        try {
                            val socket = serverSocket!!.accept()
                            GlobalScope.launch(Dispatchers.IO) {
                                handleClient(socket)
                            }
                        } catch (e: Exception) {
                            break
                        }
                    }
                }
            }
        }
        streamMap[id] = streamInfo
        return "http://127.0.0.1:$activePort/stream/$id.mp4"
    }

    private fun handleClient(socket: Socket) {
        runCatching {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine() ?: return

            var rangeHeader: String? = null
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            val path = requestLine.substringAfter("GET ").substringBefore(" HTTP")
            val id = path.substringAfter("/stream/").substringBefore(".mp4")
            val info = streamMap[id] ?: run {
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                socket.close()
                return
            }

            val md5Bytes = MessageDigest.getInstance("MD5").digest(info.filename.toByteArray(Charsets.UTF_8))
            val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }
            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val ivBytes = keyBytes.copyOfRange(0, 16)

            val builder = Request.Builder()
                .url(info.url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36")
                .header("Referer", info.headers["Referer"] ?: "")

            if (rangeHeader != null) {
                builder.header("Range", rangeHeader)
            }

            val response = app.baseClient.newCall(builder.build()).execute()
            val responseCode = response.code
            val responseBody = response.body
            val bodyStream = responseBody.byteStream()

            var startByte = 0L
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                startByte = rangeHeader.substringAfter("bytes=").substringBefore("-").toLongOrNull() ?: 0L
            }

            val blockIndex = startByte / 16
            val keystreamOffset = (startByte % 16).toInt()

            val counterIv = ivBytes.clone()
            var carry = blockIndex
            for (i in 15 downTo 0) {
                val sum = (counterIv[i].toInt() and 0xFF) + (carry and 0xFF).toInt()
                counterIv[i] = sum.toByte()
                carry = carry ushr 8
            }

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(counterIv)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            if (keystreamOffset > 0) {
                cipher.update(ByteArray(keystreamOffset))
            }

            val out = socket.getOutputStream()
            val statusLine = if (responseCode == 206) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n"
            val responseContentLength = response.header("Content-Length")
            val responseContentRange = response.header("Content-Range")

            val head = StringBuilder().apply {
                append(statusLine)
                append("Content-Type: video/mp4\r\n")
                if (responseContentLength != null) append("Content-Length: $responseContentLength\r\n")
                if (responseContentRange != null) append("Content-Range: $responseContentRange\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }.toString()

            out.write(head.toByteArray(Charsets.UTF_8))

            val buffer = ByteArray(16384)
            var bytesRead = bodyStream.read(buffer)

            while (bytesRead != -1) {
                val decrypted = cipher.update(buffer, 0, bytesRead)
                if (decrypted != null && decrypted.isNotEmpty()) {
                    out.write(decrypted)
                }
                bytesRead = bodyStream.read(buffer)
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes != null && finalBytes.isNotEmpty()) {
                out.write(finalBytes)
            }

            out.flush()
            bodyStream.close()
            responseBody.close()
            response.close()
            socket.close()
        }
    }
}

class SinemaTvAz : MainAPI() {
    override var mainUrl = "https://sinematv.az"
    override var name = "SinemaTvAz"
    override val hasMainPage = true
    override var lang = "az"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "az,tr,en-US;q=0.9,en;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/xarici-filmler/" to "Xarici Filmlər",
        "$mainUrl/hind-filmleri/" to "Hind Filmləri",
        "$mainUrl/serial/" to "Seriallar",
        "$mainUrl/rus-filmleri/" to "Rus Filmləri",
        "$mainUrl/mult/" to "Cizgi Filmləri",
        "$mainUrl/anime/" to "Anime",
        "$mainUrl/dorama/" to "Doramalar",
        "$mainUrl/turkce-filmler/" to "Türkçə Filmlər",
        "$mainUrl/film/" to "Filmlər",
        "$mainUrl/tvshow/" to "TV Şoular",
        "$mainUrl/boevik/" to "Döyüş",
        "$mainUrl/comedy/" to "Komediya",
        "$mainUrl/drama/" to "Dram",
        "$mainUrl/thriller/" to "Triller",
        "$mainUrl/horror/" to "Qorxu",
        "$mainUrl/fantasy/" to "Fantezi",
        "$mainUrl/fantastic/" to "Elmi-Kütləvi",
        "$mainUrl/detective/" to "Detektiv",
        "$mainUrl/adventures/" to "Macəra",
        "$mainUrl/semejnyj/" to "Ailə"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url, headers = browserHeaders).document
        val mainContent = document.selectFirst("#dle-content") ?: document
        val items = mainContent.select("a.poster-item.grid-item, .poster-item, .grid-item, div.shortstory, article.shortstory")
            .filterNot { it.parents().any { p -> p.hasClass("owl-carousel") || p.id() == "owl-popular" } }
        val home = items.mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = when {
            this.tagName() == "a" -> this.attr("href")
            else -> this.selectFirst("a.poster-item, div.shortstory-title a, h2.title a, a.shortstory-title, a.title, a")?.attr("href")
        } ?: return null

        val fixHref = fixUrl(href)

        val titleElement = this.selectFirst(".poster-item__title, div.shortstory-title, h2.title, span.title, a.title, .title")
        val title = (titleElement?.text()?.trim() ?: "").ifEmpty {
            this.attr("title")
        }.ifEmpty {
            this.selectFirst("img")?.attr("title")?.ifEmpty { this.selectFirst("img")?.attr("alt") } ?: ""
        }.ifEmpty {
            this.text().trim()
        }

        if (title.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val posterUrl = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") }
            ?.replace("/uploads/movies/", "/movies/")
            ?.let { fixUrl(it) }

        val isTvSeries = fixHref.contains("/serial/") || fixHref.contains("/mult/") || fixHref.contains("/anime/") || fixHref.contains("/dorama/") || fixHref.contains("/tvshow/") || title.contains("sezon", ignoreCase = true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, fixHref, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, fixHref, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search"
        val response = app.post(
            searchUrl,
            data = mapOf(
                "do" to "search",
                "subaction" to "search",
                "story" to query
            ),
            headers = browserHeaders
        ).document

        val mainContent = response.selectFirst("#dle-content") ?: response
        val items = mainContent.select("a.poster-item.grid-item, .poster-item, .grid-item, div.shortstory, article.shortstory")
            .filterNot { it.parents().any { p -> p.hasClass("owl-carousel") || p.id() == "owl-popular" } }

        return items.mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        val title = document.selectFirst("h1.title, h1.entry-title, h1")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.poster img, div.shortstory-poster img, div.story-poster img, .img-box img, img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.replace("/uploads/movies/", "/movies/")?.let { fixUrl(it) }

        val description = document.selectFirst("div.full-text, div.story-text, div.description, div.fdesc")?.text()?.trim()
        val year = document.selectFirst("div.info:contains(İl), span:contains(İl), div:contains(İl)")?.text()?.let {
            Regex("\\d{4}").find(it)?.value?.toIntOrNull()
        }

        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("div.episodes-list a, ul.episodes a, div.seasons-list a, .episodes a, .season-episodes a")
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEach { element ->
                val epTitle = element.text().trim()
                val epHref = fixUrl(element.attr("href"))
                val seasonNum = Regex("(\\d+)\\.\\s*Sezon", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("(\\d+)\\.\\s*Bölüm", RegexOption.IGNORE_CASE).find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
            }
        }

        val isTvSeries = url.contains("/serial/") || url.contains("/mult/") || url.contains("/anime/") || url.contains("/dorama/") || url.contains("/tvshow/") || title.contains("sezon", ignoreCase = true)

        return if (isTvSeries) {
            val finalEpisodes = if (episodes.isEmpty()) {
                listOf(
                    newEpisode(url) {
                        this.name = "1. Bölüm"
                        this.season = 1
                        this.episode = 1
                    }
                )
            } else {
                episodes
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
            }
        }
    }

    private suspend fun extractSinemaTvAzCdn(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val embedDoc = app.get(embedUrl, headers = mapOf("Referer" to referer)).text
            val datasMatch = Regex("""const\s+datas\s*=\s*"([^"]+)"""").find(embedDoc)?.groupValues?.get(1) ?: return false

            val base64Bytes = Base64.decode(datasMatch, Base64.DEFAULT)
            val jsonStr = String(base64Bytes, Charsets.ISO_8859_1)

            val json = JSONObject(jsonStr)
            val slug = json.optString("slug")
            val userId = json.opt("user_id")?.toString() ?: ""
            val md5Id = json.opt("md5_id")?.toString() ?: ""
            val mediaStr = json.optString("media")

            if (slug.isEmpty() || userId.isEmpty() || md5Id.isEmpty() || mediaStr.isEmpty()) return false

            val keyStr = "$userId:$slug:$md5Id"
            val md5Bytes = MessageDigest.getInstance("MD5").digest(keyStr.toByteArray(Charsets.UTF_8))
            val md5Hex = md5Bytes.joinToString("") { "%02x".format(it) }

            val keyBytes = md5Hex.toByteArray(Charsets.UTF_8)
            val counterBytes = keyBytes.copyOfRange(0, 16)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(counterBytes)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val mediaBytes = mediaStr.map { it.code.toByte() }.toByteArray()
            val decryptedBytes = cipher.doFinal(mediaBytes)
            val decryptedText = String(decryptedBytes, Charsets.UTF_8)

            val decryptedJson = JSONObject(decryptedText)
            val section = decryptedJson.optJSONObject("mp4") ?: decryptedJson.optJSONObject("hls") ?: return false
            val fristDatas = section.optJSONArray("fristDatas")

            val embedDomain = fixUrl(embedUrl).substringBefore("/?").trimEnd('/') + "/"

            var foundAny = false

            if (fristDatas != null) {
                for (i in 0 until fristDatas.length()) {
                    val item = fristDatas.getJSONObject(i)
                    val streamUrl = item.optString("url")
                    if (streamUrl.isNotEmpty()) {
                        val resId = item.optInt("res_id")
                        val codec = item.optString("codec")
                        val quality = when (resId) {
                            1 -> Qualities.P144.value
                            2 -> Qualities.P360.value
                            3 -> Qualities.P480.value
                            4 -> Qualities.P720.value
                            5 -> Qualities.P1080.value
                            7 -> Qualities.P1440.value
                            8 -> Qualities.P2160.value
                            else -> Qualities.Unknown.value
                        }
                        val codecLabel = if (codec.isNotEmpty()) " [$codec]" else ""
                        val filename = streamUrl.substringAfterLast('/')

                        val proxyUrl = LocalSinemaTvAzServer.registerStream(
                            id = "${slug}_${resId}_${codec.ifEmpty { "v" }}_$i",
                            streamInfo = LocalSinemaTvAzServer.StreamInfo(
                                url = streamUrl,
                                filename = filename,
                                headers = mapOf("Referer" to embedDomain)
                            )
                        )

                        callback.invoke(
                            newExtractorLink(
                                source = "SinemaTvAz",
                                name = "SinemaTvAz$codecLabel",
                                url = proxyUrl,
                                type = ExtractorLinkType.VIDEO,
                            ) {
                                this.quality = quality
                            }
                        )
                        foundAny = true
                    }
                }
            }

            return foundAny
        } catch (e: Exception) {
            return false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders, referer = "$mainUrl/").document
        val iframes = document.select("iframe")
        var foundAny = false

        for (iframe in iframes) {
            val src = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            val title = iframe.attr("title")

            if (title.contains("Трейлер", ignoreCase = true) || title.contains("Trailer", ignoreCase = true)) {
                continue
            }

            if (src.isEmpty() || src.contains("googletagmanager") || src.contains("yandex") || src.contains("facebook") || src.contains("/t?token=") || src.contains("allarknow") || src.contains("/t/")) {
                continue
            }

            val playerUrl = fixUrl(src)
            if (playerUrl.isEmpty()) continue

            if (playerUrl.contains("cdn.sinematv.az") || playerUrl.contains("cdn1.sinematv.az") || playerUrl.contains("v=")) {
                if (extractSinemaTvAzCdn(playerUrl, data, callback)) {
                    foundAny = true
                    continue
                }
            }

            if (loadExtractor(playerUrl, data, subtitleCallback, callback)) {
                foundAny = true
            }
        }

        if (!foundAny) {
            val context = SinemaTvAzPlugin.pluginContext
            if (context != null) {
                SinemaTvAzWebViewExtractor(context).getUrl(data, data, subtitleCallback, callback)
                return true
            }
        }

        return foundAny
    }
}
