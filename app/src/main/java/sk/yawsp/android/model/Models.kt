package sk.yawsp.android.model

data class WebshareFile(
    val ident: String,
    val name: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val positiveVotes: Int = 0,
    val negativeVotes: Int = 0,
    val isProtected: Boolean = false,
    val imgUrl: String? = null
) {
    val formattedSize: String
        get() {
            if (size <= 0) return "?"
            val kb = size / 1024.0
            if (kb < 1024) return "${kb.toInt()} KB"
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.2f MB", mb)
            val gb = mb / 1024.0
            return String.format("%.2f GB", gb)
        }

    val isArchive: Boolean
        get() {
            val low = name.lowercase()
            return low.endsWith(".rar") || low.endsWith(".zip") || low.endsWith(".7z")
        }

    val badges: List<String>
        get() {
            val list = mutableListOf<String>()
            val up = name.uppercase()
            if (isArchive) {
                list.add("RAR/ZIP")
            }
            if (height >= 2100 || Regex("""(?:^|[^A-Z0-9])(4K|2160P|UHD)(?:$|[^A-Z0-9])""").containsMatchIn(up)) {
                list.add("4K")
            } else if (height >= 1000 || Regex("""(?:^|[^A-Z0-9])(1080P|FHD|FULLHD)(?:$|[^A-Z0-9])""").containsMatchIn(up)) {
                list.add("1080p")
            } else if (height >= 700 || Regex("""(?:^|[^A-Z0-9])(720P|HD)(?:$|[^A-Z0-9])""").containsMatchIn(up)) {
                list.add("720p")
            }

            val hasCz = Regex("""(?:^|[^A-Z0-9])(CZ|CZE|CZECH|DABING|DAB)(?:$|[^A-Z0-9])""").containsMatchIn(up)
            val hasSk = Regex("""(?:^|[^A-Z0-9])(SK|SVK|SLOVAK)(?:$|[^A-Z0-9])""").containsMatchIn(up)
            val hasTit = Regex("""(?:^|[^A-Z0-9])(TIT|TITULKY|SUB|SUBS)(?:$|[^A-Z0-9])""").containsMatchIn(up)

            when {
                hasCz && hasSk -> list.add("CZ/SK")
                hasCz -> list.add("CZ")
                hasSk -> list.add("SK")
                hasTit -> list.add("TIT")
            }

            if (Regex("""(?:^|[^A-Z0-9])(HDR|HDR10|DV|DOLBY[\s._-]?VISION)(?:$|[^A-Z0-9])""").containsMatchIn(up)) {
                list.add("HDR")
            }
            if (Regex("""(?:^|[^A-Z0-9])(HEVC|X265|H265)(?:$|[^A-Z0-9])""").containsMatchIn(up)) {
                list.add("HEVC")
            }
            return list
        }
}

data class UserStatus(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val isVip: Boolean = false,
    val vipDays: String = "0"
)

data class TvShow(
    val id: Int,
    val name: String,
    val year: String,
    val status: String,
    val summary: String,
    val imageUrl: String?,
    val seasonsCount: Int = 0,
    val totalEpisodes: Int = 0
)

data class TvSeason(
    val number: Int,
    val episodeOrder: Int,
    val premiereYear: String,
    val imageUrl: String?,
    val archiveFiles: List<WebshareFile> = emptyList()
)

data class TvEpisode(
    val season: Int,
    val number: Int,
    val name: String,
    val summary: String,
    val imageUrl: String?
) {
    val code: String
        get() = "S%02dE%02d".format(season, number)
}
