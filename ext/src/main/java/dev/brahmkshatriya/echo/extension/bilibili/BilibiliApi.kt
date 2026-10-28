package dev.brahmkshatriya.echo.extension.bilibili

import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BilibiliApi {
    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val REFERER = "https://www.bilibili.com/"

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29,
            28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25,
            54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
        )
    }

    var settings: Settings? = null

    private val cookieStore = ConcurrentHashMap<String, String>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                if (c.name.isNotEmpty() && c.value.isNotEmpty()) {
                    cookieStore[c.name] = c.value
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val list = mutableListOf<Cookie>()
            val host = url.host
            for ((k, v) in cookieStore) {
                list.add(
                    Cookie.Builder()
                        .name(k)
                        .value(v)
                        .domain(if (host.contains("bilibili.com")) "bilibili.com" else host)
                        .path("/")
                        .build()
                )
            }
            return list
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    @Volatile
    private var cachedMixinKey: String? = null
    @Volatile
    private var mixinKeyFetchedAt: Long = 0L

    fun getCookieHeader(): String {
        val userCookie = settings?.getString("bili_cookie")
        if (!userCookie.isNullOrBlank()) {
            return userCookie
        }
        val sb = StringBuilder()
        for ((k, v) in cookieStore) {
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(k).append("=").append(v)
        }
        return sb.toString()
    }

    fun setCookies(raw: String) {
        val parts = raw.split(";")
        for (p in parts) {
            val trimmed = p.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                cookieStore[k] = v
            }
        }
    }

    fun syncUserCookies(cookieHeader: String) {
        setCookies(cookieHeader)
        settings?.putString("bili_cookie", cookieHeader)
    }

    private fun ensureGuestIdentity() {
        if (!cookieStore.containsKey("buvid3")) {
            try {
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/frontend/finger/spi")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use
                    val json = JSONObject(body)
                    if (json.optInt("code") == 0) {
                        val data = json.optJSONObject("data")
                        val b3 = data?.optString("b_3")
                        val b4 = data?.optString("b_4")
                        if (!b3.isNullOrEmpty()) cookieStore["buvid3"] = b3
                        if (!b4.isNullOrEmpty()) cookieStore["buvid4"] = b4
                    }
                }
            } catch (e: Exception) {
                // ignore, fall back to whatever is available
            }
        }
    }

    @Synchronized
    private fun getMixinKey(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedMixinKey != null && now - mixinKeyFetchedAt < 3600_000L) {
            return cachedMixinKey!!
        }
        ensureGuestIdentity()
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/nav")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty nav response")
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: throw IOException("Nav has no data: $body")
            val wbi = data.optJSONObject("wbi_img") ?: throw IOException("Nav has no wbi_img: $body")
            val imgUrl = wbi.optString("img_url")
            val subUrl = wbi.optString("sub_url")
            val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
            val subKey = subUrl.substringAfterLast('/').substringBefore('.')
            val raw = imgKey + subKey
            if (raw.length < 64) throw IOException("Combined key too short: $raw")
            val mixed = StringBuilder()
            for (i in 0 until 32) {
                mixed.append(raw[MIXIN_KEY_ENC_TAB[i]])
            }
            val key = mixed.toString()
            cachedMixinKey = key
            mixinKeyFetchedAt = now
            return key
        }
    }

    fun signWbi(params: Map<String, String>, forceRefreshKey: Boolean = false): String {
        val mixinKey = getMixinKey(forceRefreshKey)
        val sortedParams = sortedMapOf<String, String>()
        sortedParams.putAll(params)
        sortedParams["wts"] = (System.currentTimeMillis() / 1000).toString()

        val sb = StringBuilder()
        for ((k, v) in sortedParams) {
            val cleanVal = v.filter { it !in "!'()*" }
            if (sb.isNotEmpty()) sb.append("&")
            sb.append(URLEncoder.encode(k, "UTF-8"))
            sb.append("=")
            sb.append(URLEncoder.encode(cleanVal, "UTF-8"))
        }
        val queryStr = sb.toString()
        val toHash = queryStr + mixinKey
        val md5 = MessageDigest.getInstance("MD5")
            .digest(toHash.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$queryStr&w_rid=$md5"
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: throw IOException("Empty response from $url")
            return JSONObject(body)
        }
    }

    // 1. Search Videos
    fun searchVideos(keyword: String, page: Int = 1, order: String = "totalrank"): JSONObject {
        ensureGuestIdentity()
        val params = mapOf(
            "keyword" to keyword,
            "search_type" to "video",
            "page" to page.toString(),
            "order" to order
        )
        val query = signWbi(params)
        val url = "https://api.bilibili.com/x/web-interface/wbi/search/type?$query"
        return getJson(url)
    }

    // 2. Music Ranking (rid=3 is Music partition on Bilibili)
    fun getMusicRanking(): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/web-interface/ranking/v2?rid=3&type=all"
        return getJson(url)
    }

    // 3. Popular / Trending Videos
    fun getPopularVideos(page: Int = 1, pageSize: Int = 20): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/web-interface/popular?ps=$pageSize&pn=$page"
        return getJson(url)
    }

    // 4. Video Details (View)
    fun getVideoView(bvid: String): JSONObject {
        ensureGuestIdentity()
        val params = mapOf("bvid" to bvid)
        val query = signWbi(params)
        val url = "https://api.bilibili.com/x/web-interface/wbi/view?$query"
        return getJson(url)
    }

    // 5. Playurl (Audio & Video Streams)
    fun getPlayUrl(bvid: String, cid: Long, fnval: Int = 4048): JSONObject {
        ensureGuestIdentity()
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to fnval.toString(),
            "fnver" to "0",
            "qn" to "80"
        )
        val query = signWbi(params)
        val url = "https://api.bilibili.com/x/player/wbi/playurl?$query"
        return getJson(url)
    }

    // 6. Related Videos (Radio & Suggestions)
    fun getRelatedVideos(bvid: String): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/web-interface/archive/related?bvid=$bvid"
        return getJson(url)
    }

    // 7. UP Loader (Artist) Info
    fun getUpInfo(mid: Long): JSONObject {
        ensureGuestIdentity()
        val params = mapOf("mid" to mid.toString())
        val query = signWbi(params)
        val url = "https://api.bilibili.com/x/space/wbi/acc/info?$query"
        return getJson(url)
    }

    // 8. UP Loader (Artist) Videos
    fun getUpVideos(mid: Long, page: Int = 1, pageSize: Int = 30): JSONObject {
        ensureGuestIdentity()
        val params = mapOf(
            "mid" to mid.toString(),
            "pn" to page.toString(),
            "ps" to pageSize.toString(),
            "order" to "pubdate"
        )
        val query = signWbi(params)
        val url = "https://api.bilibili.com/x/space/wbi/arc/search?$query"
        return getJson(url)
    }

    // 9. User Favorite Folders (Playlists)
    fun getUserFavoriteFolders(mid: Long): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid"
        return getJson(url)
    }

    // 10. Favorite Folder Items
    fun getFavoriteFolderItems(mediaId: Long, page: Int = 1, pageSize: Int = 20): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=$pageSize&platform=web&order=mtime&type=0"
        return getJson(url)
    }

    // 11. Subtitles / CC Lyrics
    fun getSubtitles(bvid: String, cid: Long): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/player/v2?cid=$cid&bvid=$bvid"
        return getJson(url)
    }

    // 12. Current Logged-in User Profile
    fun getUserProfile(): JSONObject {
        ensureGuestIdentity()
        val url = "https://api.bilibili.com/x/web-interface/nav"
        return getJson(url)
    }
}
