package sk.yawsp.android.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.w3c.dom.Element
import sk.yawsp.android.crypto.Md5Crypt
import sk.yawsp.android.model.*
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebshareRepository {
    companion object {
        private const val BASE = "https://webshare.cz"
        private const val API = "$BASE/api/"
        private const val REALM = ":Webshare:"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var token: String = ""
    private val deviceUuid: String = UUID.randomUUID().toString()

    fun setToken(savedToken: String) {
        token = savedToken
    }

    fun getToken(): String = token

    private suspend fun postApi(endpoint: String, params: Map<String, String>): Element? =
        withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                params.forEach { (k, v) -> form.add(k, v) }
                val request = Request.Builder()
                    .url("$API$endpoint/")
                    .header("User-Agent", UA)
                    .header("Referer", BASE)
                    .post(form.build())
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: return@withContext null
                    val factory = DocumentBuilderFactory.newInstance()
                    val builder = factory.newDocumentBuilder()
                    val doc = builder.parse(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                    doc.documentElement
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun Element?.tagText(tag: String): String? {
        if (this == null) return null
        val nodes = getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent
    }

    private fun Element?.isOk(): Boolean = this.tagText("status") == "OK"

    suspend fun login(username: String, password: String): Result<String> {
        val saltXml = postApi("salt", mapOf("username_or_email" to username))
        if (!saltXml.isOk()) return Result.failure(Exception("Nepodarilo sa získať salt"))

        val salt = saltXml.tagText("salt") ?: return Result.failure(Exception("Neplatný používateľ"))
        val crypted = Md5Crypt.unixMd5Crypt(password, salt)
        val encryptedPass = Md5Crypt.sha1Hex(crypted)
        val digest = Md5Crypt.md5Hex("$username$REALM$encryptedPass")

        val loginXml = postApi(
            "login",
            mapOf(
                "username_or_email" to username,
                "password" to encryptedPass,
                "digest" to digest,
                "keep_logged_in" to "1"
            )
        )
        if (loginXml.isOk()) {
            val newToken = loginXml.tagText("token")
            if (!newToken.isNullOrBlank()) {
                token = newToken
                return Result.success(newToken)
            }
        }
        return Result.failure(Exception("Nesprávne meno alebo heslo"))
    }

    suspend fun checkUserStatus(username: String): UserStatus {
        if (token.isBlank()) return UserStatus(isLoggedIn = false)
        val xml = postApi("user_data", mapOf("wst" to token))
        if (!xml.isOk()) return UserStatus(isLoggedIn = false)
        val isVip = xml.tagText("vip") == "1"
        val vipDays = xml.tagText("vip_days") ?: "0"
        return UserStatus(isLoggedIn = true, username = username, isVip = isVip, vipDays = vipDays)
    }

    suspend fun searchFiles(
        query: String,
        category: String = "video",
        sort: String = "",
        limit: Int = 35,
        offset: Int = 0
    ): List<WebshareFile> {
        val effectiveQuery = if (query.isBlank()) "mkv OR mp4 OR avi" else query
        val xml = postApi(
            "search",
            mapOf(
                "what" to effectiveQuery,
                "category" to category,
                "sort" to sort,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "wst" to token,
                "maybe_removed" to "true"
            )
        )
        if (!xml.isOk() || xml == null) return emptyList()

        val result = mutableListOf<WebshareFile>()
        val fileNodes = xml.getElementsByTagName("file")
        for (i in 0 until fileNodes.length) {
            val el = fileNodes.item(i) as? Element ?: continue
            val ident = el.tagText("ident") ?: continue
            val name = el.tagText("name") ?: continue
            result.add(
                WebshareFile(
                    ident = ident,
                    name = name,
                    size = el.tagText("size")?.toLongOrNull() ?: 0L,
                    width = el.tagText("width")?.toIntOrNull() ?: 0,
                    height = el.tagText("height")?.toIntOrNull() ?: 0,
                    duration = el.tagText("length")?.toIntOrNull() ?: 0,
                    positiveVotes = el.tagText("positive_votes")?.toIntOrNull() ?: 0,
                    negativeVotes = el.tagText("negative_votes")?.toIntOrNull() ?: 0,
                    isProtected = el.tagText("password") == "1",
                    imgUrl = el.tagText("img")
                )
            )
        }
        return result
    }

    suspend fun searchSeasonArchives(showName: String, seasonNumber: Int): List<WebshareFile> {
        val sCode = "S%02d".format(seasonNumber)
        val queries = listOf(
            "$showName $sCode",
            "$showName $seasonNumber seria",
            "$showName S$seasonNumber"
        )
        val found = LinkedHashMap<String, WebshareFile>()

        for (q in queries) {
            val archFiles = searchFiles(query = q, category = "archives", sort = "largest", limit = 25)
            for (f in archFiles) {
                val up = f.name.uppercase()
                val matchesSeason = up.contains(sCode) ||
                    Regex("""(?:^|[^A-Z0-9])(S0?$seasonNumber|0?$seasonNumber\.?\s*SERI[AE]|SEASON\s*0?$seasonNumber)(?:$|[^A-Z0-9])""").containsMatchIn(up)
                val isSingleEpisode = Regex("""S\d{1,2}E\d{1,2}|\d{1,2}x\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(up)
                if (f.isArchive && matchesSeason && !isSingleEpisode) {
                    found[f.ident] = f
                }
            }
            if (found.isNotEmpty()) break
        }
        return found.values.sortedByDescending { it.size }
    }

    suspend fun getFileLink(ident: String, downloadType: String = "video_stream"): String? {
        val xml = postApi(
            "file_link",
            mapOf(
                "ident" to ident,
                "wst" to token,
                "download_type" to downloadType,
                "device_uuid" to deviceUuid
            )
        )
        if (xml.isOk()) {
            return xml.tagText("link")
        }
        return null
    }

    suspend fun findSubtitlesForVideo(videoName: String): List<String> {
        val baseName = videoName.substringBeforeLast('.')
        val cleaned = baseName.replace(
            Regex("""(?:^|[^A-Z0-9])(2160p|1080p|720p|480p|4K|UHD|FHD|HD|WEB[-.]?DL|WEBRip|BluRay|BDRip|x264|x265|HEVC|CZ|SK|EN|DABING).*""", RegexOption.IGNORE_CASE),
            ""
        ).replace(Regex("""[._-]+"""), " ").trim()

        if (cleaned.length < 3) return emptyList()
        val files = searchFiles(query = cleaned, category = "docs", limit = 12)
        val links = mutableListOf<String>()
        for (f in files) {
            if (f.name.lowercase().endsWith(".srt") || f.name.lowercase().endsWith(".vtt")) {
                val link = getFileLink(f.ident, "file_download")
                if (link != null) {
                    links.add(link)
                    if (links.size >= 3) break
                }
            }
        }
        return links
    }

    suspend fun searchTvShows(query: String): List<TvShow> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://api.tvmaze.com/search/shows?q=$encoded")
                .header("User-Agent", UA)
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val shows = mutableListOf<TvShow>()

            for (i in 0 until minOf(arr.length(), 8)) {
                val showObj = arr.getJSONObject(i).getJSONObject("show")
                val id = showObj.getInt("id")
                val name = showObj.getString("name")
                val premiered = showObj.optString("premiered", "").take(4)
                val status = showObj.optString("status", "")
                val summary = showObj.optString("summary", "").replace(Regex("<[^>]+>"), "")
                val imgObj = showObj.optJSONObject("image")
                val img = imgObj?.optString("medium")?.replace("http://", "https://")
                    ?: imgObj?.optString("original")?.replace("http://", "https://")

                var seasonsCount = 0
                var totalEps = 0
                try {
                    val sReq = Request.Builder()
                        .url("https://api.tvmaze.com/shows/$id/seasons")
                        .header("User-Agent", UA)
                        .build()
                    val sBody = client.newCall(sReq).execute().use { it.body?.string() }
                    if (sBody != null) {
                        val sArr = JSONArray(sBody)
                        for (j in 0 until sArr.length()) {
                            val sObj = sArr.getJSONObject(j)
                            if (sObj.optInt("number", 0) > 0) {
                                seasonsCount++
                                totalEps += sObj.optInt("episodeOrder", 0)
                            }
                        }
                    }
                } catch (_: Exception) { }

                shows.add(TvShow(id, name, premiered, status, summary, img, seasonsCount, totalEps))
            }
            shows
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getShowSeasonsWithArchives(showId: Int, showName: String): List<TvSeason> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.tvmaze.com/shows/$showId/seasons")
                .header("User-Agent", UA)
                .build()
            val body = client.newCall(req).execute().use { it.body?.string() } ?: return@withContext emptyList()
            val arr = JSONArray(body)
            val list = mutableListOf<TvSeason>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val num = obj.optInt("number", -1)
                if (num <= 0) continue

                val archives = searchSeasonArchives(showName, num)

                list.add(
                    TvSeason(
                        number = num,
                        episodeOrder = obj.optInt("episodeOrder", 0),
                        premiereYear = obj.optString("premiereDate", "").take(4),
                        imageUrl = obj.optJSONObject("image")?.optString("medium")?.replace("http://", "https://"),
                        archiveFiles = archives
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
