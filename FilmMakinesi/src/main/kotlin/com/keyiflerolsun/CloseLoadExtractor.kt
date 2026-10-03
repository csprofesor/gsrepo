package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

open class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    private class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val body     = response.peekBody(1024 * 1024).string()
            val doc      = Jsoup.parse(body)

            if (response.code == 403 || response.code == 503 ||
                response.header("cf-mitigated") != null ||
                body.contains("Just a moment", ignoreCase = true) ||
                body.contains("Checking your browser", ignoreCase = true) ||
                body.contains("cf-challenge", ignoreCase = true) ||
                body.contains("turnstile", ignoreCase = true) ||
                doc.title().contains("Just a moment", ignoreCase = true) ||
                doc.title().contains("Attention Required", ignoreCase = true)
            ) {
                synchronized(cloudflareKiller) {
                    val checkResp = chain.proceed(request)
                    val checkBody = checkResp.peekBody(1024 * 1024).string()
                    val checkDoc  = Jsoup.parse(checkBody)

                    if (checkResp.code == 403 || checkResp.code == 503 ||
                        checkResp.header("cf-mitigated") != null ||
                        checkBody.contains("Just a moment", ignoreCase = true) ||
                        checkBody.contains("Checking your browser", ignoreCase = true) ||
                        checkBody.contains("cf-challenge", ignoreCase = true) ||
                        checkBody.contains("turnstile", ignoreCase = true) ||
                        checkDoc.title().contains("Just a moment", ignoreCase = true) ||
                        checkDoc.title().contains("Attention Required", ignoreCase = true)
                    ) {
                        return cloudflareKiller.intercept(chain)
                    }
                    return checkResp
                }
            }

            return response
        }
    }

    private fun isValidVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return false
        val lower = url.lowercase()
        if (lower.contains("f9gx1m12bwc")) return false
        if (lower.contains(".vtt") || lower.contains(".srt") || lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".webp")) return false
        if (lower.contains("embed/?") || lower.contains("video/embed") || lower.contains("<!doctype")) return false
        return true
    }

    private fun findBase64VideoUrl(text: String): String? {
        val encodedUrls = Regex("""aHR0(?:cHM6Ly|cDovL)[A-Za-z0-9+/_=-]+""")
        return encodedUrls.findAll(text).firstNotNullOfOrNull { match ->
            safeBase64Decode(match.value)
                .trim()
                .takeIf { isValidVideoUrl(it) }
        }
    }

    private fun safeBase64Decode(input: String): String {
        return try {
            val clean = input.replace("\n", "").replace("\r", "").replace(" ", "").trim()
            val formatted = clean.replace("-", "+").replace("_", "/")
            val mod = formatted.length % 4
            val padded = if (mod != 0) formatted + "=".repeat(4 - mod) else formatted

            val bytes = try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Exception) {
                try {
                    Base64.decode(padded, Base64.URL_SAFE)
                } catch (_: Exception) {
                    try {
                        Base64.decode(padded, Base64.NO_WRAP)
                    } catch (_: Exception) {
                        Base64.decode(padded, Base64.CRLF)
                    }
                }
            }
            String(bytes, Charsets.ISO_8859_1)
        } catch (_: Exception) {
            input
        }
    }

    private fun decryptNative(html: String): String? {
        return try {
            val regexKey = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*["']([a-zA-Z0-9]{15,35})["']""")
            val regexOps = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*["']([a-zA-Z]{2,8})["']""")
            val regexArr = Regex("""\(\[((?:["'][^"']+["'],?\s*)+)\]\)""")

            val doc = Jsoup.parse(html)
            val scriptScopeList = mutableListOf<String>()

            doc.select("script").forEach { script ->
                val text = script.data().ifEmpty { script.html() }
                if (text.isNotBlank()) {
                    scriptScopeList.add(text)
                    if (text.contains("eval(function(p,a,c,k,e,d)")) {
                        try {
                            getAndUnpack(text)?.let { unpacked ->
                                scriptScopeList.add(unpacked)
                                Log.d("CLOSELOAD_UNPACKED", unpacked.take(500))
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val combinedJs = scriptScopeList.joinToString("\n;\n")
            scriptScopeList.add(combinedJs)

            for (jsScope in scriptScopeList) {
                val matchKeys = regexKey.findAll(jsScope).toList()
                val matchOpss = regexOps.findAll(jsScope).toList()
                val matchArrs = regexArr.findAll(jsScope).toList()

                if (matchKeys.isEmpty() || matchOpss.isEmpty() || matchArrs.isEmpty()) continue

                for (matchKey in matchKeys) {
                    for (matchOps in matchOpss) {
                        for (matchArr in matchArrs) {
                            try {
                                val key = matchKey.groupValues[1]
                                val ops = matchOps.groupValues[1]
                                val rawArrStr = matchArr.groupValues[1]

                                val arrList = rawArrStr.split(",")
                                    .map { it.trim().replace("\"", "").replace("'", "").replace("\\/", "/") }

                                var str = arrList.joinToString("")

                                var h1 = 0
                                var h2 = 0
                                for (i in 0 until key.length) {
                                    val charCode = key[i].code
                                    h1 = (h1 * 31 + charCode) % 251
                                    h2 = ((charCode + i) xor h2) and 255
                                }

                                val seed = (h1 + h2) % 256
                                val shift = (h1 % 13) + 3
                                var currentNum = (h1 * 256 + h2) % 65521 + 1

                                for (i in ops.length - 1 downTo 0) {
                                    when (ops[i]) {
                                        'v', 'V' -> {
                                            str = str.reversed()
                                        }
                                        'b', 'B' -> {
                                            str = safeBase64Decode(str)
                                        }
                                        // Non-'b'/non-'v' characters in ops (e.g. 'I', 'T', 'R', 'N', 'G', 'K', 'X')
                                        // are obfuscation dummy noise in JS and must be ignored.
                                    }
                                }

                                val strPermutedInit = str

                                // Check if ops loop alone decoded the valid URL (CloseLoad new format)
                                if (isValidVideoUrl(strPermutedInit)) {
                                    Log.d("Kekik_$name", "Decoded REAL URL (ops only): $strPermutedInit")
                                    return strPermutedInit
                                }

                                val len = str.length
                                val perm = IntArray(len)
                                for (i in len - 1 downTo 1) {
                                    currentNum = (currentNum * 75 + 74) % 65537
                                    perm[i] = currentNum % (i + 1)
                                }

                                val charArray = str.toCharArray()
                                for (i in 1 until len) {
                                    val idx = perm[i]
                                    val tmp = charArray[i]
                                    charArray[i] = charArray[idx]
                                    charArray[idx] = tmp
                                }

                                val strPermuted = String(charArray)

                                val result = StringBuilder(strPermuted.length)
                                var k = seed
                                for (i in 0 until strPermuted.length) {
                                    val c = strPermuted[i].code
                                    val nextK = (k + shift) % 256
                                    val decChar = (c xor nextK).toChar()
                                    result.append(decChar)
                                    k = (nextK + c) % 256
                                }

                                val decodedUrl = result.toString()
                                Log.d("Kekik_$name", "Candidate decoded: $decodedUrl")
                                if (isValidVideoUrl(decodedUrl)) {
                                    Log.d("Kekik_$name", "Decoded REAL URL: $decodedUrl")
                                    return decodedUrl
                                }
                            } catch (e: Exception) {
                                Log.d("Kekik_$name", "Candidate evaluation failed: ${e.message}")
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("Kekik_$name", "Deşifre hatası: ${e.message}")
            null
        }
    }

    private suspend fun processSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            val tracksMatch = Regex("""tracks\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
            tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
                val jsonObjects = Regex("""\{[^}]*\}""").findAll(tracksStr)
                jsonObjects.forEach { objMatch ->
                    val objStr = objMatch.value
                    val fileMatch = Regex(""""file"\s*:\s*"([^"]+)"""").find(objStr)
                    val labelMatch = Regex(""""label"\s*:\s*"([^"]+)"""").find(objStr)

                    val subUrl = fileMatch?.groupValues?.get(1)?.replace("\\/", "/")
                    val subLabel = labelMatch?.groupValues?.get(1) ?: "Altyazı"

                    if (!subUrl.isNullOrBlank()) {
                        val fullSubUrl = if (subUrl.startsWith("http")) subUrl else mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                        subtitleCallback.invoke(newSubtitleFile(subLabel, fullSubUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_$name", "Altyazı hatası: ${e.message}")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        val requestHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer?.takeIf { it.isNotBlank() } ?: "${mainUrl}/")
        )

        val response = try {
            app.get(url, headers = requestHeaders, interceptor = interceptor)
        } catch (e: Exception) {
            Log.e(name, "Embed GET hatası: ${e.message}")
            return
        }

        val rawHtml = response.text
        val cookies = try {
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (_: Exception) { "" }

        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        val unpackedJs = try { getAndUnpack(rawHtml) } catch (_: Exception) { null }
        val searchScope = (unpackedJs ?: "") + "\n" + rawHtml
        var authResponseText = ""

        // Send CloseLoad authentication POST request (/ah/) if present to activate the stream token on server
        val ahPath = Regex("""url\s*:\s*["']([^"']*/ah/?)["']""").find(searchScope)?.groupValues?.get(1)
            ?: Regex("""["'](/video/embed/[^"']+/ah/?)["']""").find(searchScope)?.groupValues?.get(1)
        val hashVal = Regex("""hash\s*:\s*["']([a-f0-9]{32})["']""").find(searchScope)?.groupValues?.get(1)

        if (!ahPath.isNullOrBlank() && !hashVal.isNullOrBlank()) {
            try {
                val ahUrl = if (ahPath.startsWith("http")) ahPath else mainUrl.trimEnd('/') + (if (ahPath.startsWith("/")) "" else "/") + ahPath
                authResponseText = app.post(
                    ahUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl,
                        if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                    ).filter { it.key.isNotBlank() },
                    data = mapOf("hash" to hashVal),
                    interceptor = interceptor
                ).text
                Log.d(name, "CloseLoad ah auth POST sent: $ahUrl, hash=$hashVal")
            } catch (e: Exception) {
                Log.e(name, "CloseLoad ah auth POST error: ${e.message}")
            }
        }

        var videoUrl = decryptNative(rawHtml)
        val sourceScope = "$searchScope\n$authResponseText"

        if (!isValidVideoUrl(videoUrl)) {
            val directFileMatch = Regex("""(?i)(?:["']?file["']?)\s*:\s*["'](https?://[^"']+)["']""")
                .find(sourceScope)

            if (directFileMatch != null) {
                val candidate = directFileMatch.groupValues[1].replace("\\/", "/")
                if (isValidVideoUrl(candidate)) {
                    videoUrl = candidate
                }
            }

            if (!isValidVideoUrl(videoUrl)) {
                val urlMatch = Regex("""(https?://[^"'\s]+\.(?:m3u8|txt|mp4)[^"'\s]*)""").find(sourceScope)
                if (urlMatch != null) {
                    val candidate = urlMatch.groupValues[1].replace("\\/", "/")
                    if (isValidVideoUrl(candidate)) {
                        videoUrl = candidate
                    }
                }
            }

            if (!isValidVideoUrl(videoUrl)) {
                videoUrl = findBase64VideoUrl(sourceScope)
            }
        }

        if (!isValidVideoUrl(videoUrl)) {
            Log.e(name, "CloseLoad URL deşifre edilemedi.")
        } else {
            val finalVideoUrl = videoUrl!!
            val linkType = if (finalVideoUrl.contains(".m3u8", ignoreCase = true) ||
                finalVideoUrl.contains(".txt", ignoreCase = true) ||
                finalVideoUrl.contains("/hls/", ignoreCase = true) ||
                finalVideoUrl.contains("playlist", ignoreCase = true)
            ) {
                ExtractorLinkType.M3U8
            } else {
                INFER_TYPE
            }

            val linkHeaders = mapOf(
                "Referer" to url,
                "Origin" to mainUrl,
                "User-Agent" to userAgent,
                if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
            ).filter { it.key.isNotBlank() }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalVideoUrl,
                    type = linkType
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = linkHeaders
                }
            )
            Log.d(name, "ExtractorLink eklendi: $finalVideoUrl")
        }

        processSubtitles(rawHtml, subtitleCallback)
    }
}

class CloseLoadTo : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
}

class CloseLoadFilm : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.film"
}

class CloseLoadDe : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.de"
}

class CloseLoadTv : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.tv"
}

class CloseLoadSh : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.filmmakinesi.sh"
}

class CloseLoadCom : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.com"
}

class CloseLoadNet : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.net"
}

class CloseLoadOrg : CloseLoadExtractor() {
    override val mainUrl = "https://closeload.org"
}
