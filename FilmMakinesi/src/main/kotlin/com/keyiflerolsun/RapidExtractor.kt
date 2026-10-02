package com.keyiflerolsun

open class RapidExtractor : CloseLoadExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
}

class RapidTo : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
}

class RapidFilm : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.film"
}

class RapidDe : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.de"
}

class RapidTv : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.tv"
}

class RapidSh : RapidExtractor() {
    override val mainUrl = "https://rapid.filmmakinesi.sh"
}

class RapidNet : RapidExtractor() {
    override val mainUrl = "https://rapid.net"
}

class RapidCom : RapidExtractor() {
    override val mainUrl = "https://rapid.com"
}
