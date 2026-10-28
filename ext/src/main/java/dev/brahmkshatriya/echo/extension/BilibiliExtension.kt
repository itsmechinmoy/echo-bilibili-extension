package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.bilibili.BilibiliApi
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

open class BilibiliExtension : ExtensionClient, SearchFeedClient, HomeFeedClient,
    TrackClient, AlbumClient, PlaylistClient, ArtistClient, RadioClient,
    ShareClient, LyricsClient, LibraryFeedClient, LoginClient.WebView, LoginClient.CustomInput {

    val api by lazy { BilibiliApi() }
    lateinit var setting: Settings

    override fun setSettings(settings: Settings) {
        setting = settings
        api.settings = settings
        val savedCookie = settings.getString("bili_cookie")
        if (!savedCookie.isNullOrBlank()) {
            api.setCookies(savedCookie)
        }
    }

    override suspend fun getSettingItems() = listOf(
        SettingSwitch(
            "Prefer Video",
            "prefer_video",
            "Play video stream by default instead of audio only",
            preferVideo
        ),
        SettingSwitch(
            "Include Video Variant",
            "show_video_variant",
            "Include Music Video variant in track streams",
            showVideoVariant
        )
    )

    private val preferVideo get() = setting.getBoolean("prefer_video") ?: false
    private val showVideoVariant get() = setting.getBoolean("show_video_variant") ?: true

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("<.*?>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    private fun normalizeCover(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun parseDuration(raw: Any?): Long? {
        if (raw == null) return null
        if (raw is Number) return raw.toLong() * 1000L
        val str = raw.toString().trim()
        val parts = str.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> str.toLongOrNull()?.times(1000L)
        }
    }

    private fun toTrack(json: JSONObject): Track {
        val bvid = json.optString("bvid")
        val title = cleanTitle(json.optString("title", "Unknown Title"))
        val pic = normalizeCover(json.optString("pic", json.optString("cover")))
        val authorName = cleanTitle(
            json.optString("author", json.optJSONObject("owner")?.optString("name") ?: "Bilibili Artist")
        )
        val mid = json.optLong("mid", json.optJSONObject("owner")?.optLong("mid") ?: 0L)
        val duration = parseDuration(json.opt("duration"))

        val artist = Artist(
            id = mid.toString(),
            name = authorName,
            cover = normalizeCover(json.optJSONObject("owner")?.optString("face"))?.toImageHolder()
        )

        return Track(
            id = bvid,
            title = title,
            type = Track.Type.Song,
            cover = pic?.toImageHolder(),
            artists = listOf(artist),
            duration = duration,
            plays = json.optLong("play", json.optJSONObject("stat")?.optLong("view") ?: 0L)
        )
    }

    // --- SearchFeedClient ---
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        val paged = PagedData.Continuous<Shelf> { cont ->
            val page = cont?.toIntOrNull() ?: 1
            val json = api.searchVideos(query, page)
            val data = json.optJSONObject("data")
            val results = data?.optJSONArray("result") ?: JSONArray()
            val tracks = mutableListOf<Track>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("type", item.optString("result_type")) == "video" || item.has("bvid")) {
                    tracks.add(toTrack(item))
                }
            }
            val hasMore = tracks.isNotEmpty() && page < 20
            val shelf = Shelf.Lists.Items(
                id = "search_$page",
                title = "Search Results - Page $page",
                list = tracks
            )
            Page(listOf(shelf), if (hasMore) (page + 1).toString() else null)
        }
        return paged.toFeed()
    }

    // --- HomeFeedClient ---
    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("ranking", "Music Ranking"),
            Tab("popular", "Popular & Trending")
        )
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "popular" -> {
                    val paged = PagedData.Continuous<Shelf> { cont ->
                        val page = cont?.toIntOrNull() ?: 1
                        val json = api.getPopularVideos(page)
                        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                        val tracks = mutableListOf<Track>()
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            tracks.add(toTrack(item))
                        }
                        val shelf = Shelf.Lists.Items("popular_$page", "Trending", tracks)
                        val hasMore = tracks.isNotEmpty() && page < 10
                        Page(listOf(shelf), if (hasMore) (page + 1).toString() else null)
                    }
                    paged.toFeedData()
                }
                else -> {
                    val paged = PagedData.Single<Shelf> {
                        val json = api.getMusicRanking()
                        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
                        val tracks = mutableListOf<Track>()
                        for (i in 0 until list.length()) {
                            val item = list.optJSONObject(i) ?: continue
                            tracks.add(toTrack(item))
                        }
                        listOf(Shelf.Lists.Items("ranking", "Top Music Ranking", tracks))
                    }
                    paged.toFeedData()
                }
            }
        }
    }

    // --- TrackClient ---
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val view = api.getVideoView(track.id)
        val data = view.optJSONObject("data") ?: return track
        val title = cleanTitle(data.optString("title", track.title))
        val pic = normalizeCover(data.optString("pic")) ?: track.cover?.let { "" }
        val owner = data.optJSONObject("owner")
        val artist = Artist(
            id = (owner?.optLong("mid") ?: 0L).toString(),
            name = cleanTitle(owner?.optString("name") ?: "Bilibili Artist"),
            cover = normalizeCover(owner?.optString("face"))?.toImageHolder()
        )
        val duration = data.optLong("duration") * 1000L
        val pages = data.optJSONArray("pages") ?: JSONArray()
        val defaultCid = if (pages.length() > 0) pages.getJSONObject(0).optLong("cid") else 0L

        val streamables = mutableListOf<Streamable>()
        // Audio stream qualities:
        streamables.add(
            Streamable(
                id = "${track.id}:$defaultCid:30251",
                quality = 1000,
                type = Streamable.MediaType.Server,
                title = "Hi-Res Lossless"
            )
        )
        streamables.add(
            Streamable(
                id = "${track.id}:$defaultCid:30280",
                quality = 800,
                type = Streamable.MediaType.Server,
                title = "High Quality (192k/320k)"
            )
        )
        streamables.add(
            Streamable(
                id = "${track.id}:$defaultCid:30232",
                quality = 500,
                type = Streamable.MediaType.Server,
                title = "Standard (132k)"
            )
        )
        streamables.add(
            Streamable(
                id = "${track.id}:$defaultCid:30216",
                quality = 300,
                type = Streamable.MediaType.Server,
                title = "Data Saver (64k)"
            )
        )
        if (showVideoVariant) {
            streamables.add(
                Streamable(
                    id = "${track.id}:$defaultCid:video",
                    quality = 700,
                    type = Streamable.MediaType.Server,
                    title = "Music Video"
                )
            )
        }

        val album = if (pages.length() > 1) {
            Album(
                id = track.id,
                title = title,
                cover = pic?.toImageHolder(),
                artists = listOf(artist),
                trackCount = pages.length().toLong()
            )
        } else null

        return track.copy(
            title = title,
            cover = pic?.toImageHolder(),
            artists = listOf(artist),
            duration = duration,
            album = album,
            streamables = streamables
        )
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean
    ): Streamable.Media {
        val parts = streamable.id.split(":")
        val bvid = parts[0]
        val cid = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val requestedQuality = parts.getOrNull(2)

        val playData = api.getPlayUrl(bvid, cid).optJSONObject("data")
            ?: throw IllegalStateException("Failed to load stream URLs for $bvid")

        val dash = playData.optJSONObject("dash")
        val headers = mapOf(
            "User-Agent" to BilibiliApi.USER_AGENT,
            "Referer" to BilibiliApi.REFERER
        )

        // Video variant requested
        if (requestedQuality == "video") {
            val videoArray = dash?.optJSONArray("video")
            if (videoArray != null && videoArray.length() > 0) {
                val videoObj = videoArray.getJSONObject(0)
                val videoUrl = videoObj.optString("baseUrl", videoObj.optString("base_url"))
                if (videoUrl.isNotEmpty()) {
                    return Streamable.Source.Http(
                        request = videoUrl.toGetRequest(headers),
                        type = Streamable.SourceType.Progressive,
                        isVideo = true
                    ).toMedia()
                }
            }
        }

        // Audio streams
        val audioList = mutableListOf<JSONObject>()
        val flac = dash?.optJSONObject("flac")?.optJSONObject("audio")
        if (flac != null) audioList.add(flac)

        val dolby = dash?.optJSONObject("dolby")?.optJSONArray("audio")
        if (dolby != null) {
            for (i in 0 until dolby.length()) {
                audioList.add(dolby.getJSONObject(i))
            }
        }

        val standardAudios = dash?.optJSONArray("audio")
        if (standardAudios != null) {
            for (i in 0 until standardAudios.length()) {
                audioList.add(standardAudios.getJSONObject(i))
            }
        }

        // Try exact requested ID
        val targetId = requestedQuality?.toIntOrNull()
        var selected = if (targetId != null) {
            audioList.firstOrNull { it.optInt("id") == targetId }
        } else null

        // Fallback to highest bandwidth audio available
        if (selected == null) {
            selected = audioList.maxByOrNull { it.optLong("bandwidth") }
        }

        val audioUrl = selected?.optString("baseUrl", selected.optString("base_url"))
        if (!audioUrl.isNullOrEmpty()) {
            return Streamable.Source.Http(
                request = audioUrl.toGetRequest(headers),
                type = Streamable.SourceType.Progressive,
                isVideo = false
            ).toMedia()
        }

        // Final fallback: durl progressive mixed stream
        val durlArray = playData.optJSONArray("durl")
        if (durlArray != null && durlArray.length() > 0) {
            val durl = durlArray.getJSONObject(0).optString("url")
            if (durl.isNotEmpty()) {
                return Streamable.Source.Http(
                    request = durl.toGetRequest(headers),
                    type = Streamable.SourceType.Progressive,
                    isVideo = false
                ).toMedia()
            }
        }

        throw IllegalStateException("No playable audio stream returned by Bilibili")
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        return try {
            val json = api.getRelatedVideos(track.id)
            val data = json.optJSONArray("data") ?: JSONArray()
            val tracks = mutableListOf<Track>()
            for (i in 0 until data.length()) {
                tracks.add(toTrack(data.getJSONObject(i)))
            }
            if (tracks.isEmpty()) null
            else listOf(Shelf.Lists.Items("related", "Recommended / Similar", tracks)).toFeed()
        } catch (e: Exception) {
            null
        }
    }

    // --- AlbumClient (For Multi-part Videos / Compilations) ---
    override suspend fun loadAlbum(album: Album): Album {
        val view = api.getVideoView(album.id)
        val data = view.optJSONObject("data") ?: return album
        val title = cleanTitle(data.optString("title", album.title))
        val pic = normalizeCover(data.optString("pic"))
        val pages = data.optJSONArray("pages") ?: JSONArray()
        return album.copy(
            title = title,
            cover = pic?.toImageHolder(),
            trackCount = pages.length().toLong()
        )
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val view = api.getVideoView(album.id)
        val data = view.optJSONObject("data") ?: return null
        val pages = data.optJSONArray("pages") ?: return null
        val pic = normalizeCover(data.optString("pic"))
        val owner = data.optJSONObject("owner")
        val artist = Artist(
            id = (owner?.optLong("mid") ?: 0L).toString(),
            name = cleanTitle(owner?.optString("name") ?: "Bilibili Artist"),
            cover = normalizeCover(owner?.optString("face"))?.toImageHolder()
        )
        val list = mutableListOf<Track>()
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i)
            val cid = p.optLong("cid")
            val partTitle = cleanTitle(p.optString("part", "Part ${i + 1}"))
            val pageNum = p.optLong("page", (i + 1).toLong())
            val duration = p.optLong("duration") * 1000L
            list.add(
                Track(
                    id = "${album.id}:$cid",
                    title = partTitle,
                    cover = pic?.toImageHolder(),
                    artists = listOf(artist),
                    album = album,
                    duration = duration,
                    albumOrderNumber = pageNum
                )
            )
        }
        return list.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // --- PlaylistClient (For Favorite Folders) ---
    override suspend fun loadPlaylist(playlist: Playlist): Playlist = playlist

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val mediaId = playlist.id.toLongOrNull() ?: 0L
        val paged = PagedData.Continuous<Track> { cont ->
            val page = cont?.toIntOrNull() ?: 1
            val json = api.getFavoriteFolderItems(mediaId, page)
            val medias = json.optJSONObject("data")?.optJSONArray("medias") ?: JSONArray()
            val tracks = mutableListOf<Track>()
            for (i in 0 until medias.length()) {
                val m = medias.getJSONObject(i)
                tracks.add(toTrack(m))
            }
            val hasMore = json.optJSONObject("data")?.optBoolean("has_more") ?: false
            Page(tracks, if (hasMore) (page + 1).toString() else null)
        }
        return paged.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    // --- ArtistClient (For UP Loaders) ---
    override suspend fun loadArtist(artist: Artist): Artist {
        val mid = artist.id.toLongOrNull() ?: return artist
        return try {
            val json = api.getUpInfo(mid)
            val data = json.optJSONObject("data") ?: return artist
            artist.copy(
                name = cleanTitle(data.optString("name", artist.name)),
                cover = normalizeCover(data.optString("face"))?.toImageHolder(),
                bio = data.optString("sign")
            )
        } catch (e: Exception) {
            artist
        }
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val mid = artist.id.toLongOrNull() ?: 0L
        val paged = PagedData.Continuous<Shelf> { cont ->
            val page = cont?.toIntOrNull() ?: 1
            val json = api.getUpVideos(mid, page)
            val vlist = json.optJSONObject("data")?.optJSONObject("list")?.optJSONArray("vlist") ?: JSONArray()
            val tracks = mutableListOf<Track>()
            for (i in 0 until vlist.length()) {
                tracks.add(toTrack(vlist.getJSONObject(i)))
            }
            val shelf = Shelf.Lists.Items("up_videos_$page", "Uploads - Page $page", tracks)
            val count = json.optJSONObject("data")?.optJSONObject("page")?.optInt("count") ?: 0
            val hasMore = tracks.isNotEmpty() && page * 30 < count
            Page(listOf(shelf), if (hasMore) (page + 1).toString() else null)
        }
        return paged.toFeed()
    }

    // --- RadioClient ---
    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val json = api.getRelatedVideos(radio.id)
        val data = json.optJSONArray("data") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        for (i in 0 until data.length()) {
            tracks.add(toTrack(data.getJSONObject(i)))
        }
        return tracks.toFeed()
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        return Radio(
            id = item.id,
            title = "${item.title} Radio",
            cover = item.cover
        )
    }

    // --- ShareClient ---
    override suspend fun onShare(item: EchoMediaItem): String {
        val bvid = item.id.substringBefore(":")
        return "https://www.bilibili.com/video/$bvid"
    }

    // --- LyricsClient ---
    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val lyricsItem = Lyrics(
            id = track.id,
            title = track.title,
            subtitle = track.artists.firstOrNull()?.name
        )
        return listOf(lyricsItem).toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val bvid = lyrics.id.substringBefore(":")
        val view = api.getVideoView(bvid)
        val cid = view.optJSONObject("data")?.optLong("cid") ?: 0L
        if (cid == 0L) return lyrics

        val subJson = api.getSubtitles(bvid, cid)
        val subtitles = subJson.optJSONObject("data")?.optJSONObject("subtitle")?.optJSONArray("subtitles")
        if (subtitles == null || subtitles.length() == 0) return lyrics

        val subUrl = normalizeCover(subtitles.getJSONObject(0).optString("subtitle_url"))
            ?: return lyrics

        val req = Request.Builder()
            .url(subUrl)
            .header("User-Agent", BilibiliApi.USER_AGENT)
            .header("Referer", BilibiliApi.REFERER)
            .build()
        val body = api.client.newCall(req).execute().body?.string() ?: return lyrics
        val bodyJson = JSONObject(body)
        val bodyArray = bodyJson.optJSONArray("body") ?: JSONArray()

        val items = mutableListOf<Lyrics.Item>()
        for (i in 0 until bodyArray.length()) {
            val item = bodyArray.getJSONObject(i)
            val fromSec = item.optDouble("from", 0.0)
            val toSec = item.optDouble("to", fromSec + 2.0)
            val text = item.optString("content", "")
            if (text.isNotBlank()) {
                items.add(
                    Lyrics.Item(
                        text = text,
                        startTime = (fromSec * 1000).toLong(),
                        endTime = (toSec * 1000).toLong()
                    )
                )
            }
        }

        return lyrics.copy(lyrics = Lyrics.Timed(items, fillTimeGaps = true))
    }

    // --- LibraryFeedClient ---
    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val nav = api.getUserProfile()
        val data = nav.optJSONObject("data")
        val isLogin = data?.optBoolean("isLogin") ?: false
        if (!isLogin) {
            return listOf(
                Shelf.Lists.Items(
                    id = "login_prompt",
                    title = "Login to Bilibili to view your favorite playlists",
                    list = emptyList()
                )
            ).toFeed()
        }
        val mid = data.optLong("mid")
        val foldersJson = api.getUserFavoriteFolders(mid)
        val list = foldersJson.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        val playlists = mutableListOf<Playlist>()
        for (i in 0 until list.length()) {
            val f = list.getJSONObject(i)
            val fid = f.optLong("id")
            val ftitle = cleanTitle(f.optString("title", "Favorites"))
            val count = f.optLong("media_count")
            playlists.add(
                Playlist(
                    id = fid.toString(),
                    title = ftitle,
                    isEditable = false,
                    trackCount = count
                )
            )
        }
        return listOf(Shelf.Lists.Items("user_favorites", "My Favorite Playlists", playlists)).toFeed()
    }

    // --- LoginClient.WebView ---
    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val dontCache = false
        override val initialUrl = "https://passport.bilibili.com/login".toGetRequest(
            mapOf("User-Agent" to BilibiliApi.USER_AGENT)
        )
        override val stopUrlRegex =
            Regex("(https://passport\\.bilibili\\.com/login/exit.*)|(https://www\\.bilibili\\.com/?.*)|(https://m\\.bilibili\\.com/?.*)")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            api.syncUserCookies(cookie)
            val nav = api.getUserProfile()
            val data = nav.optJSONObject("data") ?: throw Exception("Failed to get profile from Bilibili")
            val mid = data.optLong("mid").toString()
            val uname = data.optString("uname", "Bilibili User")
            val face = normalizeCover(data.optString("face"))
            val user = User(id = mid, name = uname, cover = face?.toImageHolder())
            currentUser = user
            return listOf(user)
        }
    }

    // --- LoginClient.CustomInput ---
    override val forms = listOf(
        LoginClient.Form(
            key = "cookie_login",
            label = "Login via SESSDATA Cookie",
            icon = LoginClient.InputField.Type.Password,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "sessdata",
                    label = "SESSDATA Cookie Value",
                    isRequired = true
                )
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val sessdata = data["sessdata"]?.trim() ?: throw Exception("SESSDATA is required")
        val cookie = "SESSDATA=$sessdata"
        api.syncUserCookies(cookie)
        val nav = api.getUserProfile()
        val navData = nav.optJSONObject("data") ?: throw Exception("Invalid SESSDATA or failed to get profile")
        if (!navData.optBoolean("isLogin")) {
            throw Exception("Bilibili rejected this SESSDATA token")
        }
        val mid = navData.optLong("mid").toString()
        val uname = navData.optString("uname", "Bilibili User")
        val face = normalizeCover(navData.optString("face"))
        val user = User(id = mid, name = uname, cover = face?.toImageHolder())
        currentUser = user
        return listOf(user)
    }

    private var currentUser: User? = null

    override fun setLoginUser(user: User?) {
        currentUser = user
    }

    override suspend fun getCurrentUser(): User? {
        return currentUser ?: runCatching {
            val nav = api.getUserProfile()
            val data = nav.optJSONObject("data")
            if (data?.optBoolean("isLogin") == true) {
                val mid = data.optLong("mid").toString()
                val uname = data.optString("uname", "Bilibili User")
                val face = normalizeCover(data.optString("face"))
                User(id = mid, name = uname, cover = face?.toImageHolder())
            } else null
        }.getOrNull()
    }
}
