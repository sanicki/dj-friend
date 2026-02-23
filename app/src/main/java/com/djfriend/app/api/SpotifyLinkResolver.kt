package com.djfriend.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a Spotify URL for a track using:
 *   1. iTunes Search API  → get trackViewUrl
 *   2. Odesli / song.link → get Spotify URL from the iTunes URL
 *
 * Returns null if either step fails or no Spotify link is found.
 */
object SpotifyLinkResolver {

    suspend fun resolve(artist: String, track: String): String? = withContext(Dispatchers.IO) {
        val itunesUrl = fetchItunesUrl(artist, track) ?: return@withContext null
        fetchSpotifyUrl(itunesUrl)
    }

    private fun fetchItunesUrl(artist: String, track: String): String? {
        return try {
            val term = URLEncoder.encode("$artist $track", "UTF-8")
            val url  = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) DJFriend/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val results = json.getJSONArray("results")
            if (results.length() == 0) return null
            results.getJSONObject(0).getString("trackViewUrl")
        } catch (e: Exception) { null }
    }

    private fun fetchSpotifyUrl(itunesUrl: String): String? {
        return try {
            val encoded = URLEncoder.encode(itunesUrl, "UTF-8")
            // Use api.song.link — the primary stable domain for the Odesli API.
            // api.odesli.co is an alias that has proven unreliable.
            val url  = URL("https://api.song.link/v1-alpha.1/links?url=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) DJFriend/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 8_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())

            json.optJSONObject("linksByPlatform")
                ?.optJSONObject("spotify")
                ?.optString("url")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }
}
