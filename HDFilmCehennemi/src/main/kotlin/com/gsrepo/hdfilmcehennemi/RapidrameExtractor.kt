package com.gsrepo.hdfilmcehennemi

import android.util.Base64
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.util.regex.Pattern

class RapidrameExtractor : ExtractorApi() {
    override val name = "Rapidrame"
    override val mainUrl = "https://hdfilmcehennemi.mobi"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(1024 * 1024).string()
            val doc = Jsoup.parse(body)

            if (doc.html().contains("Just a moment", ignoreCase = true)) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private fun evaluateRhinoJs(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)
            val scripts = doc.select("script").mapNotNull { s ->
                val text = s.data().trim()
                if (text.isEmpty() || text.startsWith("eval(")) return@mapNotNull null
                if (text.contains("jwplayer") || text.contains("giz1m") || text.contains("pjv7") || text.contains("b1la5") || text.contains("sources:")) text else null
            }
            if (scripts.isEmpty()) return null

            val mockHeader = """
                var window = globalThis || this;
                var b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                function atob(input) {
                    var str = String(input).replace(/=+$/, '');
                    var output = '';
                    if (str.length % 4 === 1) return '';
                    for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++);
                        ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0
                    ) {
                        buffer = b64chars.indexOf(buffer);
                    }
                    return output;
                }
                function btoa(input) {
                    var str = String(input);
                    var output = '';
                    for (var block, charCode, idx = 0, map = b64chars;
                        str.charAt(idx | 0) || (map = '=', idx % 1);
                        output += map.charAt(63 & block >> 8 - idx % 1 * 8)
                    ) {
                        charCode = str.charCodeAt(idx += 3 / 4);
                        if (charCode > 255) return '';
                        block = block << 8 | charCode;
                    }
                    return output;
                }
                var document = { getElementById: function() { return {}; }, cookie: "", addEventListener: function() {} };
                var ${'$'} = function() { return { ready: function(){}, prepend: function(){}, on: function(){} }; };
                ${'$'}.ajax = function(){};
                var extractedFile = null;
                function jwplayer() {
                    return {
                        setup: function(opts) {
                            if (opts && opts.sources && opts.sources.length > 0) {
                                extractedFile = opts.sources[0].file;
                            }
                        },
                        on: function(){},
                        once: function(){},
                        addButton: function(){}
                    };
                }
                jwplayer.key = "";
            """.trimIndent()

            val combinedJs = mockHeader + "\n" + scripts.joinToString("\n") + "\nextractedFile || (typeof b1la5 !== 'undefined' ? b1la5 : null);"

            val rhino = Context.enter()
            @Suppress("DEPRECATION")
            rhino.optimizationLevel = -1
            try {
                val scope: ScriptableObject = rhino.initStandardObjects()
                val result = rhino.evaluateString(scope, combinedJs, "JavaScript", 1, null)
                result?.toString()?.takeIf { it.startsWith("http") && !it.equals("null", ignoreCase = true) }
            } finally {
                Context.exit()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeLegacy(jsCode: String, arrStr: String): String {
        val matcher = Pattern.compile("\"([^\"]+)\"").matcher(arrStr)
        val sb = StringBuilder()
        while (matcher.find()) sb.append(matcher.group(1))
        var value = sb.toString()

        val keys = Pattern.compile("var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";\\s*var\\s+[a-zA-Z0-9_]+\\s*=\\s*\"([^\"]+)\";").matcher(jsCode)
        if (!keys.find()) return ""
        val key1 = keys.group(1) ?: return ""
        val key2 = keys.group(2) ?: return ""

        var o0v = 0
        var rbc = 0
        for (i in key1.indices) {
            val c = key1[i].code
            o0v = (o0v * 31 + c) % 251
            rbc = (rbc xor (c + i)) and 255
        }

        val lbxe = (o0v + rbc) % 256
        val u2u5r = (o0v % 13) + 3
        var d6en9 = ((o0v * 256 + rbc) % 65521) + 1

        for (i in key2.length - 1 downTo 0) {
            when (key2[i]) {
                'b' -> {
                    var padded = value
                    val missing = padded.length % 4
                    if (missing != 0) padded += "=".repeat(4 - missing)
                    value = String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
                }
                'v' -> value = value.reversed()
                else -> {
                    val shift = (26 - ((key2[i].code - 64) % 26)) % 26
                    value = value.map { c ->
                        if (c.isLetter()) {
                            val base = if (c.code <= 90) 65 else 97
                            ((c.code - base + shift) % 26 + base).toChar()
                        } else c
                    }.joinToString("")
                }
            }
        }

        val shuffle = IntArray(value.length)
        for (i in value.length - 1 downTo 1) {
            d6en9 = (d6en9 * 75 + 74) % 65537
            shuffle[i] = d6en9 % (i + 1)
        }
        val chars = value.toCharArray()
        for (i in 1 until value.length) {
            val j = shuffle[i]
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        value = String(chars)

        var tds = lbxe
        val out = StringBuilder()
        for (c in value) {
            val wlv = c.code
            tds = (tds + u2u5r) % 256
            out.append((wlv xor tds).toChar())
            tds = (tds + wlv) % 256
        }
        return out.toString()
    }

    private fun isValidVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("embed/?") || lower.contains("video/embed") || lower.contains("<!doctype") || lower.contains("<html")) return false
        if (lower.contains("playmix.uno") && !lower.contains(".m3u8") && !lower.contains(".mp4") && !lower.contains(".txt") && !lower.contains("/hls/")) return false
        if (lower.contains(".vtt") || lower.contains(".srt")) return false
        if (lower.contains(".jpg") || lower.contains(".png") || lower.contains(".webp") || lower.contains(".jpeg") || lower.contains(".svg") || lower.contains(".gif")) return false
        if (lower.contains("intro") || lower.contains("fragman") || lower.contains("promo") || lower.contains("sample") || lower.contains("trailer") || lower.contains("preview") || lower.contains("advert") || lower.contains("preroll") || lower.contains("reklam") || lower.contains("credit") || lower.contains("card") || lower.contains("demo") || lower.contains("dummy")) return false
        return lower.contains(".m3u8") || lower.contains(".txt") || lower.contains("/hls/") || lower.contains(".mp4") || lower.contains(".mpd") || lower.contains(".m3u")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl

        val response = try {
            app.get(
                url,
                referer = referer ?: "https://www.hdfilmcehennemi.nl/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                ),
                interceptor = interceptor
            )
        } catch (_: Exception) {
            return
        }

        val rawHtml = response.text
        var cookies = try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (_: Exception) {
            response.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        val unpackedHtml = try { getAndUnpack(rawHtml) } catch (_: Exception) { null }
        val searchHtml = unpackedHtml ?: rawHtml
        var videoUrl: String? = null

        // 1. Direct JWPlayer/JSON source match for M3U8/HLS in unpacked JS or raw HTML
        val directMatch = Regex("""(?i)(?:["']?file["']?|["']?url["']?|["']?source["']?|["']?contentUrl["']?)\s*:\s*["'](https?://[^"']+\.(?:m3u8|txt)[^"']*)["']""")
            .find(searchHtml) ?: Regex("""(?i)(?:["']?file["']?|["']?url["']?|["']?source["']?|["']?contentUrl["']?)\s*:\s*["'](https?://[^"']+\.(?:m3u8|txt)[^"']*)["']""")
            .find(rawHtml)

        if (directMatch != null) {
            val candidate = directMatch.groupValues[1].replace("\\/", "/")
            if (isValidVideoUrl(candidate)) {
                videoUrl = candidate
            }
        }

        // 2. AJAX Hash authorization & Video URL retrieval
        if (!isValidVideoUrl(videoUrl)) {
            val ajaxHash = Regex(""""hash"\s*:\s*"([^"]+)"""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex("""hash\s*:\s*['"]([a-zA-Z0-9]{32})['"]""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex(""""hash"\s*:\s*"([^"]+)"""").find(rawHtml)?.groupValues?.get(1)

            val ajaxPath = Regex(""""url"\s*:\s*"([^"]+ah/)"\s*""").find(searchHtml)?.groupValues?.get(1)
                ?: Regex("""url\s*:\s*['"]([^'"]+ah/)['"]""").find(searchHtml)?.groupValues?.get(1)
                ?: "/video/ah/"

            if (ajaxHash != null) {
                val fullAjaxUrl = if (ajaxPath.startsWith("http")) ajaxPath else domain.trimEnd('/') + "/" + ajaxPath.trimStart('/')
                try {
                    val ajaxRes = app.post(
                        url = fullAjaxUrl,
                        data = mapOf("hash" to ajaxHash),
                        headers = mapOf(
                            "Referer" to url,
                            "Origin" to domain,
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                            if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                        ).filter { it.key.isNotBlank() },
                        interceptor = interceptor
                    )
                    val newCookies = ajaxRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (newCookies.isNotBlank()) {
                        cookies = if (cookies.isNotBlank()) "$cookies; $newCookies" else newCookies
                    }

                    val ajaxText = ajaxRes.text
                    val jsonMatches = Regex(""""(?:file|url|hls|source|securedLink)"\s*:\s*"([^"]+)"""").findAll(ajaxText)
                    for (match in jsonMatches) {
                        val candidate = match.groupValues[1].replace("\\/", "/")
                        if (isValidVideoUrl(candidate)) {
                            videoUrl = candidate
                            break
                        }
                    }
                    if (!isValidVideoUrl(videoUrl)) {
                        val urlMatches = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4|m3u)[^"'\s]*)""").findAll(ajaxText)
                        for (match in urlMatches) {
                            val candidate = match.groupValues[1].replace("\\/", "/")
                            if (isValidVideoUrl(candidate)) {
                                videoUrl = candidate
                                break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Rhino JS Evaluation
        if (!isValidVideoUrl(videoUrl)) {
            val rhinoStream = evaluateRhinoJs(searchHtml) ?: evaluateRhinoJs(rawHtml)
            if (isValidVideoUrl(rhinoStream)) {
                videoUrl = rhinoStream
            }
        }

        // 4. Legacy JS Array decode
        if (!isValidVideoUrl(videoUrl)) {
            for (pageHtml in listOf(searchHtml, rawHtml).distinct()) {
                val matcher = Pattern.compile("\\[\\s*\"[^\\]]+\"\\s*\\]").matcher(pageHtml)
                while (matcher.find()) {
                    val arr = matcher.group(0) ?: continue
                    if (arr.contains(".jpg") || arr.contains(".png") || arr.contains(".webp")) continue
                    try {
                        val stream = decodeLegacy(pageHtml, arr)
                        if (isValidVideoUrl(stream) && (stream.contains(".m3u8") || stream.contains(".txt") || stream.contains("/hls/") || stream.contains(".mp4"))) {
                            videoUrl = stream
                            break
                        }
                    } catch (_: Exception) {}
                }
                if (isValidVideoUrl(videoUrl)) break
            }
        }

        // 5. Direct regex match
        if (!isValidVideoUrl(videoUrl)) {
            val cleanHtml = searchHtml.replace(Regex("""<script type="application/ld\+json">.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            val directRegMatch = Regex("""https?://[^\s'"\\]+?(?:\.m3u8|\.txt|/hls/|\.mp4)[^\s'"\\]*""").find(cleanHtml)?.value
                ?: Regex("""https?://[^\s'"\\]+?(?:\.m3u8|\.txt|/hls/|\.mp4)[^\s'"\\]*""").find(rawHtml)?.value
            if (isValidVideoUrl(directRegMatch)) {
                videoUrl = directRegMatch
            }
        }

        // 6. Base64 / atob decode
        if (!isValidVideoUrl(videoUrl)) {
            val atobMatches = Regex("""aHR0[0-9a-zA-Z+/=]+""").findAll(searchHtml) + Regex("""aHR0[0-9a-zA-Z+/=]+""").findAll(rawHtml)
            for (atobMatch in atobMatches) {
                var atob = atobMatch.value
                val padding = atob.length % 4
                if (padding != 0) atob += "=".repeat(4 - padding)
                try {
                    val decoded = String(Base64.decode(atob, Base64.DEFAULT), Charsets.UTF_8)
                    if (isValidVideoUrl(decoded)) {
                        videoUrl = decoded
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        // Finalize video URL
        val finalUrl = when {
            videoUrl.isNullOrBlank() -> null
            videoUrl.startsWith("//") -> "https:$videoUrl"
            videoUrl.startsWith("http") -> videoUrl
            else -> domain.trimEnd('/') + "/" + videoUrl.trimStart('/')
        }

        if (isValidVideoUrl(finalUrl)) {
            val lower = finalUrl!!.lowercase()
            val isMp4 = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
            val isM3u8 = !isMp4

            val mainSiteReferer = referer?.takeIf { it.startsWith("http") } ?: "https://www.hdfilmcehennemi.nl/"

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainSiteReferer
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                        "Referer" to mainSiteReferer
                    )
                }
            )
        }

        // Subtitles extraction
        for (pageHtml in listOf(searchHtml, rawHtml).distinct()) {
            Regex("""\{"file":"(https?:[^"]+\.vtt)"[^}]*?"label":"([^"]+)"""").findAll(pageHtml).forEach {
                val subUrl = it.groupValues[1].replace("""\/""", "/")
                val lang = it.groupValues[2]
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
            Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(pageHtml)?.groupValues?.get(1)?.let { tracksStr ->
                Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(tracksStr).forEach { match ->
                    var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                    val subLabel = match.groupValues[2]
                    if (!subUrl.startsWith("http")) {
                        subUrl = domain.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                    }
                    subtitleCallback(newSubtitleFile(subLabel, subUrl))
                }
            }
        }
    }
}
