package com.davipassos.redecanais

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class RedeCanaisProvider : MainAPI() {
    override var mainUrl = "https://redecanais.forum"
    override var name = "RedeCanais"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    // ============================================================
    // HOME
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document

        val homeSections = mutableListOf<HomePageList>()

        val heroItems = document.select("section.v104-hero article.v104-slide").mapNotNull {
            it.toSearchResult()
        }
        if (heroItems.isNotEmpty()) {
            homeSections.add(HomePageList("Destaques", heroItems, isHorizontalImages = true))
        }

        document.select("section.v9-section.v9-rail-section").forEach { section ->
            val title = section.selectFirst(".v9-section-head h2")?.text()?.trim()
                ?: section.selectFirst(".v9-section-kicker")?.text()?.trim()
                ?: return@forEach

            val items = section.select(".v9-rail article.v9-card").mapNotNull {
                it.toSearchResult()
            }

            if (items.isNotEmpty()) {
                homeSections.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homeSections)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a[href]") ?: return null
        val href = linkEl.attr("href").let {
            if (it.startsWith("http")) it else mainUrl + it
        }

        if (!href.contains("/assistir/")) return null

        val title = this.selectFirst(".v9-card-copy strong")?.text()?.trim()
            ?: linkEl.attr("aria-label").removePrefix("Abrir ").trim()
            ?: return null

        val poster = this.selectFirst(".v9-card-media img")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        val typeText = this.selectFirst(".v9-card-tags span")?.text()?.trim() ?: ""
        val isSeries = typeText.contains("Série", ignoreCase = true) ||
                typeText.contains("Serie", ignoreCase = true) ||
                href.contains("/assistir/serie/")

        val year = this.select(".v9-card-tags span")
            .map { it.text().trim() }
            .firstOrNull { it.matches(Regex("\\d{4}")) }
            ?.toIntOrNull()

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ============================================================
    // BUSCA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/buscar?search=${Uri.encode(query)}&type=all"
        val document = app.get(url).document

        return document.select(".v92-search-results article.v9-card, .v9-rail article.v9-card")
            .mapNotNull { it.toSearchResult() }
    }

    // ============================================================
    // DETALHES
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst(".v10-eyebrow")?.text()
                ?.replace(Regex("SÉRIE|FILME|•|REDECANAIS"), "")?.trim()
            ?: document.title().substringBefore("–").trim()

        val poster = document.selectFirst(".v10-title-poster img")?.attr("src")
        val backdrop = document.selectFirst(".v10-title-bg img")?.attr("src")
        val plot = document.selectFirst(".v10-overview")?.text()?.trim()

        val metaSpans = document.select(".v10-meta span").map { it.text().trim() }
        val year = metaSpans.firstOrNull { it.matches(Regex("\\d{4}")) }?.toIntOrNull()

            ?.toInt()

        val genres = document.select(".v10-genre-line a").map { it.text().trim() }

        val isSeries = url.contains("/assistir/serie/") ||
                document.selectFirst("section.v10-episodes") != null

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val seasonsScript = document.select("script")
                .firstOrNull { it.data().contains("window.seasonsData") }?.data()

            if (seasonsScript != null) {
                val jsonStart = seasonsScript.indexOf("window.seasonsData")
                if (jsonStart >= 0) {
                    val start = seasonsScript.indexOf("{", jsonStart)
                    val end = findJsonEnd(seasonsScript, start)
                    if (start >= 0 && end > start) {
                        val jsonStr = seasonsScript.substring(start, end + 1)

                        val seasonRegex = Regex("\"(\\d+)\":\\{")
                        for (seasonMatch in seasonRegex.findAll(jsonStr)) {
                            val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: continue
                            val sStart = seasonMatch.range.last
                            val sEnd = findJsonEnd(jsonStr, sStart)
                            if (sEnd <= sStart) continue
                            val seasonBlock = jsonStr.substring(sStart, sEnd + 1)

                            val epRegex = Regex(
                                "\"(\\d+)\":\\{\"name\":\"([^\"]*)\",\"time\":(\\d+),\"img\":\"([^\"]*)\",\"fallback\":(\\d+),\"desc\":\"([^\"]*)\",\"air\":\"([^\"]*)\",\"vote\":\"([^\"]*)\",\"quality\":\"([^\"]*)\"\\}"
                            )
                            for (ep in epRegex.findAll(seasonBlock)) {
                                val epNum = ep.groupValues[1].toIntOrNull() ?: continue
                                val epName = unescapeJson(ep.groupValues[2])
                                val epImg = unescapeJson(ep.groupValues[4])
                                val epDesc = unescapeJson(ep.groupValues[6])

                                episodes.add(
                                    newEpisode("$url#s${seasonNum}e$epNum") {
                                        this.name = epName
                                        this.season = seasonNum
                                        this.episode = epNum
                                        this.posterUrl = epImg
                                        this.description = epDesc
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                document.select(".v8-episode-card").forEach { card ->
                    val s = card.attr("data-season").toIntOrNull() ?: 1
                    val e = card.attr("data-episode").toIntOrNull() ?: return@forEach
                    val epTitle = card.selectFirst(".v8-episode-title-row strong")?.text()?.trim()
                        ?: "Episódio $e"
                    val epImg = card.selectFirst(".v8-episode-art img")?.attr("src")
                    val epDesc = card.selectFirst(".v8-episode-desc")?.text()?.trim()
                    episodes.add(
                        newEpisode("$url#s${s}e$e") {
                            this.name = epTitle
                            this.season = s
                            this.episode = e
                            this.posterUrl = epImg
                            this.description = epDesc
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = genres
        }
    }

    // ============================================================
    // LINKS DE VÍDEO
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.substringBefore("#")
        val hash = data.substringAfter("#", "")
        var season = 1
        var episode = 1

        if (hash.isNotEmpty()) {
            val m = Regex("s(\\d+)e(\\d+)").find(hash)
            if (m != null) {
                season = m.groupValues[1].toIntOrNull() ?: 1
                episode = m.groupValues[2].toIntOrNull() ?: 1
            }
        }

        val doc = app.get(cleanUrl).document
        val seasonsScript = doc.select("script")
            .firstOrNull { it.data().contains("window.seasonsData") }?.data() ?: ""

        val tmdb = Regex("\"tmdb\":(\\d+)").find(seasonsScript)?.groupValues?.get(1)
            ?: Regex("data-tmdb=\"(\\d+)\"").find(doc.html())?.groupValues?.get(1)

        val isSeries = cleanUrl.contains("/assistir/serie/") ||
                doc.selectFirst("section.v10-episodes") != null

        if (tmdb == null) return false

        val playerApi = "$mainUrl/wp-json/api/v1/player"

        val resp = app.post(
            playerApi,
            data = mapOf(
                "type" to if (isSeries) "episode" else "movie",
                "tmdb" to tmdb,
                "season" to season.toString(),
                "episode" to episode.toString(),
            ),
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to cleanUrl,
                "Accept" to "application/json, text/plain, */*",
            )
        ).text

        val mp4Regex = Regex("https?://[^\"'\\s\\\\]+?\\.mp4[^\"'\\s\\\\]*")
        val mp4Match = mp4Regex.find(resp)

        if (mp4Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = mp4Match.value.replace("\\/", "/"),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val iframe = doc.selectFirst("iframe[src]")?.attr("src")
        if (iframe != null) {
            loadExtractor(iframe, mainUrl, subtitleCallback, callback)
            return true
        }

        return false
    }

    // ============================================================
    // HELPERS
    // ============================================================
    private fun unescapeJson(s: String): String {
        return s.replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\u00e1", "á")
            .replace("\\u00e9", "é")
            .replace("\\u00ed", "í")
            .replace("\\u00f3", "ó")
            .replace("\\u00fa", "ú")
            .replace("\\u00e3", "ã")
            .replace("\\u00f5", "õ")
            .replace("\\u00e7", "ç")
            .replace("\\u00c1", "Á")
            .replace("\\u00c9", "É")
    }

    private fun findJsonEnd(str: String, startIdx: Int): Int {
        if (startIdx < 0 || startIdx >= str.length) return -1
        var depth = 0
        var i = startIdx
        while (i < str.length) {
            when (str[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
