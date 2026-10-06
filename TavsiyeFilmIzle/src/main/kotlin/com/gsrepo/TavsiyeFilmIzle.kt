package com.gsrepo

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class TavsiyeFilmIzle : MainAPI() {
    override var mainUrl         = "https://tavsiyefilmizle.net"
    override var name            = "TavsiyeFilmIzle"
    override var lang            = "tr"
    override val hasMainPage     = true
    override val supportedTypes  = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val baseHeaders = mapOf("User-Agent" to UA, "Accept" to "*/*")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/"                                        to "Son Eklenenler",
        "$mainUrl/category/tavsiye-filmler/"               to "Tavsiye Filmler",
        "$mainUrl/category/trendler/"                      to "Popüler Filmler",
        "$mainUrl/category/en-kaliteli-filmler/"          to "En Kaliteli Filmler",
        "$mainUrl/category/mutlaka-izlenmesi-gerekenler/"  to "Mutlaka İzlenmesi Gerekenler",
        "$mainUrl/category/aksiyon-filmleri/"              to "Aksiyon Filmleri",
        "$mainUrl/category/animasyon-filmleri/"            to "Animasyon Filmleri",
        "$mainUrl/category/bilim-kurgu-filmleri/"         to "Bilim Kurgu Filmleri",
        "$mainUrl/category/dram-filmleri-hd/"              to "Dram Filmleri",
        "$mainUrl/category/fantastik-filmler/"            to "Fantastik Filmler",
        "$mainUrl/category/gerilim-filmleri/"              to "Gerilim Filmleri",
        "$mainUrl/category/gizem-filmleri/"               to "Gizem Filmleri",
        "$mainUrl/category/komedi-filmleri/"              to "Komedi Filmleri",
        "$mainUrl/category/korku-filmleri/"               to "Korku Filmleri",
        "$mainUrl/category/macera-filmleri"              to "Macera Filmleri",
        "$mainUrl/category/romantik-filmler/"             to "Romantik Filmler",
        "$mainUrl/category/suc-filmleri/"                 to "Suç Filmleri",
        "$mainUrl/category/turkce-altyazili/"             to "Türkçe Altyazılı",
        "$mainUrl/category/turkce-dublaj/"                to "Türkçe Dublaj",
        "$mainUrl/category/yerli-filmler/"                to "Yerli Filmler",
        "$mainUrl/category/netflix-filmleri-izle/"        to "Netflix Filmleri",
        "$mainUrl/category/hint-filmleri/"                to "Hint Filmleri",
        "$mainUrl/category/dizi-izle/"                    to "Dizi İzle"
    )

    private fun pageUrl(base: String, page: Int): String =
        if (page <= 1) base else base.trimEnd('/') + "/page/$page/"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(pageUrl(request.data, page), headers = baseHeaders).document
        val items = doc.select("div.movie-preview").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/filmler10/]") ?: selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val rawTitle = selectFirst("img")?.attr("alt")?.ifEmpty { null }
            ?: selectFirst("span.movie-title")?.text()?.ifEmpty { null }
            ?: a.text().ifEmpty { null }
            ?: return null
        val title = rawTitle.replace(Regex("""\s*(film(i)?\s+)?izle\s*$""", RegexOption.IGNORE_CASE), "").trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src")?.ifEmpty { null } ?: selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = baseHeaders).document
        return doc.select("div.movie-preview").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = baseHeaders).document
        val rawTitle = (doc.selectFirst("h1")?.text() ?: doc.selectFirst("meta[property=og:title]")?.attr("content"))?.trim()
            ?: return null
        val title = rawTitle.replace(Regex("""\s*(film(i)?\s+)?izle\s*(\\|.*)?$""", RegexOption.IGNORE_CASE), "").trim()
            .takeIf { it.isNotBlank() } ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("div.poster img")?.attr("data-src")
                ?: doc.selectFirst("div.poster img")?.attr("src")
        )
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("hd izle") }
            ?: doc.selectFirst("div.single-content.detail p")?.text()?.trim()

        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
        val tags = doc.select("div.Breadcrumb a[href*=/category/], a[href*=/category/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.contains("20") }
            .distinct()

        val duration = Regex("""(\d{2,3})\s*(?:dk|dakika|min)\b""", RegexOption.IGNORE_CASE).find(doc.text())?.groupValues?.get(1)?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, headers = baseHeaders).document
        val embeds = page.select("iframe").mapNotNull { f ->
            listOf("data-litespeed-src", "data-src", "data-lazy-src", "src")
                .map { f.attr(it) }
                .firstOrNull { it.startsWith("http") || it.startsWith("//") }
        }.map { fixUrl(it) }.distinct()

        var found = false
        for (embed in embeds) {
            if (loadBePlayer(embed, data, subtitleCallback, callback)) {
                found = true
            } else if (loadExtractor(embed, data, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun loadBePlayer(
        embed: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(embed, referer = referer, headers = baseHeaders).text
            val m = Regex("""bePlayer\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"](\{[^}]+\})['"]\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                ?: return false

            val arg1 = m.groupValues[1]
            val json = m.groupValues[2].replace("\\/", "/")

            val plain = decryptBePlayer(arg1, json) ?: return false
            val o = JSONObject(plain)
            val master = o.optString("video_location").takeIf { it.isNotBlank() } ?: return false
            val origin = "https://" + URI(embed).host

            o.optJSONArray("strSubtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    if (s.isNull("file")) continue
                    val f = s.optString("file")
                    if (f.isBlank()) continue
                    val sub = if (f.startsWith("http")) f else origin + f
                    subtitleCallback.invoke(newSubtitleFile(s.optString("label", "Türkçe"), sub))
                }
            }

            val streamHeaders = mapOf(
                "User-Agent" to UA,
                "Accept" to "*/*",
                "Referer" to embed,
                "Origin" to origin
            )

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name HLS",
                    url = master,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                    this.headers = streamHeaders
                }
            )
            return true
        } catch (e: Exception) {
            Log.e("TavsiyeFilmIzle", "loadBePlayer error: ${e.message}", e)
            return false
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun evpKeyIv(pass: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var d = ByteArray(0)
        var prev = ByteArray(0)
        while (d.size < 48) {
            md.reset()
            prev = md.digest(prev + pass + salt)
            d += prev
        }
        return d.copyOfRange(0, 32) to d.copyOfRange(32, 48)
    }

    private fun decryptBePlayer(arg1: String, json: String): String? = try {
        val o = JSONObject(json)
        val passphrase = arg1
        val (key, iv) = if (o.has("iv") && !o.isNull("iv")) {
            val (k, _) = evpKeyIv(passphrase.toByteArray(Charsets.UTF_8), hexToBytes(o.getString("s")))
            k to hexToBytes(o.getString("iv"))
        } else {
            evpKeyIv(passphrase.toByteArray(Charsets.UTF_8), hexToBytes(o.getString("s")))
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(Base64.decode(o.getString("ct"), Base64.DEFAULT)), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("TavsiyeFilmIzle", "decryptBePlayer error: ${e.message}", e)
        null
    }
}
