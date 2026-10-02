package com.keyiflerolsun

import android.util.Base64
import android.util.Log
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
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    private fun decryptNative(html: String): String? {
        return try {
            val regexKey = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*["']([a-zA-Z0-9]{15,35})["']""")
            val regexOps = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*["']([a-zA-Z]{2,8})["']""")
            val regexArr = Regex("""\(\[((?:["'][^"']+["'],?\s*)+)\]\)""")

            val matchKey = regexKey.find(html)
            val matchOps = regexOps.find(html)
            val matchArr = regexArr.find(html)

            if (matchKey == null || matchOps == null || matchArr == null) {
                Log.w("Kekik_$name", "Regex eşleşmedi: ahk=${matchKey != null}, uwkd=${matchOps != null}, arr=${matchArr != null}")
                return null
            }

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
                when (val op = ops[i]) {
                    'v' -> {
                        str = str.reversed()
                    }
                    'b' -> {
                        val mod = str.length % 4
                        if (mod != 0) {
                            str += "=".repeat(4 - mod)
                        }
                        val decodedBytes = Base64.decode(str, Base64.DEFAULT)
                        str = String(decodedBytes, Charsets.ISO_8859_1)
                    }
                    else -> {
                        val rot = (26 - ((op.code - 64) % 26)) % 26
                        val sb = StringBuilder(str.length)
                        for (j in 0 until str.length) {
                            val c = str[j]
                            when (c) {
                                in 'A'..'Z' -> sb.append(((c.code - 65 + rot) % 26 + 65).toChar())
                                in 'a'..'z' -> sb.append(((c.code - 97 + rot) % 26 + 97).toChar())
                                else -> sb.append(c)
                            }
                        }
                        str = sb.toString()
                    }
                }
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
            Log.d("Kekik_$name", "Decoded URL: $decodedUrl")
            decodedUrl
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

        val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: mainUrl
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "${mainUrl}/",
            "Origin" to mainUrl
        )

        val response = try {
            app.get(url, headers = headers, interceptor = interceptor)
        } catch (e: Exception) {
            Log.e(name, "Embed GET hatası: ${e.message}")
            return
        }

        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        val videoUrl = decryptNative(rawHtml)

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "CloseLoad URL deşifre edilemedi.")
        } else if (videoUrl.startsWith("http")) {
            val linkType = if (videoUrl.contains(".m3u8", ignoreCase = true) ||
                videoUrl.contains(".txt", ignoreCase = true) ||
                videoUrl.contains("/hls/", ignoreCase = true) ||
                videoUrl.contains("playlist", ignoreCase = true)
            ) {
                ExtractorLinkType.M3U8
            } else {
                INFER_TYPE
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = linkType
                ) {
                    this.referer = url
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url,
                        "Origin" to domain
                    )
                }
            )
            Log.d(name, "ExtractorLink eklendi: $videoUrl")
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
