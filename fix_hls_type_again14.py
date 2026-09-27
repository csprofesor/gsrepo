import re

def patch(filename):
    with open(filename, "r") as f:
        content = f.read()

    # The current referer is `url`, let's change to the root domain.
    # Also CloudStream requires `Origin` and `Referer` to not be rejected by 403.
    # The URL could be "https://closeload.filmmakinesi.to/xxx" so root domain is "https://closeload.filmmakinesi.to"
    
    # We will replace `this.referer = url` with `this.referer = "https://closeload.filmmakinesi.de/"` or similar
    # In Kotlin: `this.referer = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1)?.plus("/") ?: url`
    # Also add this.headers = mapOf("Origin" to ...)
    
    old_cb = """                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = url
                        }"""
    
    new_cb = """                        newExtractorLink(
                            source = name,
                            name = name,
                            url = streamUrl,
                            type = INFER_TYPE
                        ) {
                            val domain = Regex(\"\"\"(https?://[^/]+)\"\"\").find(url)?.groupValues?.get(1)
                            this.referer = domain?.plus("/") ?: url
                            this.headers = mapOf(
                                "Origin" to (domain ?: ""),
                                "Accept" to "*/*",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        }"""
    
    if "this.referer = url" in content:
        content = content.replace(old_cb, new_cb)
        
    with open(filename, "w") as f:
        f.write(content)

patch("FilmMakinesi/src/main/kotlin/com/keyiflerolsun/CloseLoadExtractor.kt")
