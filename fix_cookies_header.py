import re

def fix(filename):
    with open(filename, "r") as f:
        content = f.read()
        
    old_headers = """                this.headers = mapOf(
                    "Origin" to (domain ?: ""),
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )"""

    new_headers = """                this.headers = mapOf(
                    "Origin" to (domain ?: ""),
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    if (cookies.isNotBlank()) "Cookie" to cookies else "" to ""
                ).filter { it.key.isNotBlank() }"""

    content = content.replace(old_headers, new_headers)
    with open(filename, "w") as f:
        f.write(content)

fix("FilmMakinesi/src/main/kotlin/com/keyiflerolsun/CloseLoadExtractor.kt")
fix("FilmMakinesi/src/main/kotlin/com/keyiflerolsun/RapidExtractor.kt")
