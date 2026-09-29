package com.gsrepo.hdfilmcehennemi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.util.Base64
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

            if (response.code == 403 || response.code == 503 ||
                response.header("cf-mitigated") != null ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("cf-challenge", ignoreCase = true) ||
                body.contains("turnstile", ignoreCase = true) ||
                doc.title().contains("Just a moment", ignoreCase = true) ||
                doc.title().contains("Attention Required", ignoreCase = true)
            ) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private fun evaluateRhinoJs(html: String): String? {
        return try {
            val doc = Jsoup.parse(html)
            val scripts = doc.select("script").mapNotNull { s ->
                val text = s.data()
                if (text.contains("jwplayer") || text.contains("function") || text.contains("var ")) text else null
            }
            if (scripts.isEmpty()) return null

            val mockHeader = """
                var window = globalThis;
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

            val combinedJs = mockHeader + "\n" + scripts.joinToString("\n") + "\nextractedFile;"

            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            try {
                val scope: ScriptableObject = rhino.initStandardObjects()
                val result = rhino.evaluateString(scope, combinedJs, "JavaScript", 1, null)
                result?.toString()?.takeIf { it.startsWith("http") && !it.equals("null", true) }
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
                    value = String(Base64.getDecoder().decode(padded), Charsets.ISO_8859_1)
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(
            url,
            referer = referer ?: "https://www.hdfilmcehennemi.nl/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            ),
            interceptor = interceptor
        ).text

        var streamFound = false

        // 1. Evaluate JS code with Rhino
        val rhinoStream = evaluateRhinoJs(html)
        if (rhinoStream != null) {
            val isM3u8 = rhinoStream.contains(".m3u8") || rhinoStream.contains("master.txt") || rhinoStream.contains("/hls/") || rhinoStream.contains("/txt/")
            callback(newExtractorLink(name, name, rhinoStream, if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE) {
                this.referer = url
            })
            streamFound = true
        }

        // 2. Fallback: Direct regex match
        if (!streamFound) {
            val directMatch = Regex("""https?://[^\s'"\\]+?(?:\.m3u8|\.txt|/hls/)[^\s'"\\]*""").find(html)?.value
            if (directMatch != null) {
                val isM3u8 = directMatch.contains(".m3u8") || directMatch.contains("master.txt") || directMatch.contains("/hls/")
                callback(newExtractorLink(name, name, directMatch, if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE) {
                    this.referer = url
                })
                streamFound = true
            }
        }

        // 3. Fallback: Legacy JS Array decode
        if (!streamFound) {
            val matcher = Pattern.compile("\\[\\s*\"[^\\]]+\"\\s*\\]").matcher(html)
            while (matcher.find()) {
                val arr = matcher.group(0) ?: continue
                if (arr.contains(".jpg") || arr.contains(".png") || arr.contains(".webp")) continue
                try {
                    val stream = decodeLegacy(html, arr)
                    if (stream.contains(".m3u8") || stream.contains(".txt") || stream.contains("/hls/")) {
                        val isM3u8 = stream.contains(".m3u8") || stream.contains("master.txt") || stream.contains("/hls/")
                        callback(newExtractorLink(name, name, stream, if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE) {
                            this.referer = url
                        })
                        break
                    }
                } catch (_: Exception) {}
            }
        }

        // Subtitles extraction
        Regex("""\{"file":"(https?:[^"]+\.vtt)"[^}]*?"label":"([^"]+)"""").findAll(html).forEach {
            val subUrl = it.groupValues[1].replace("""\/""", "/")
            val lang = it.groupValues[2]
            subtitleCallback(SubtitleFile(lang, subUrl))
        }
    }
}
