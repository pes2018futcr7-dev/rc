version = 1

cloudstream {
    language = "pt-BR"
    description = "RedeCanais - Filmes e Séries em Português"
    authors = listOf("pes2018futcr7-dev")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Cartoon",
    )

    iconUrl = "https://redecanais.forum/wp-content/themes/vizer-v10-6-2/assets/svg/favicon-redecanais.png"
}
