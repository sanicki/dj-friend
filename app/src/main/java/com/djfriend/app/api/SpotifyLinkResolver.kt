package com.djfriend.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a canonical Spotify track URL for a given artist + track name using:
 *   1. MusicBrainz recording search  → get the best-matching recording MBID
 *   2. MusicBrainz recording lookup  → get url-rels, extract the Spotify URL
 *
 * No API key required. Rate limit: ≤ 1 request/second (enforced by a coroutine
 * delay between the two calls). Free for non-commercial use.
 *
 * Returns null if no Spotify URL is found in MusicBrainz for this recording,
 * in which case callers should fall back to copying "artist - track" instead.
 */
object SpotifyLinkResolver {

    private const val BASE_URL   = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT = "DJFriend/1.0 ( https://github.com/sanicki/dj-friend )"

    // MusicBrainz can respond slowly, especially on cold requests or under load.
    // 20 s gives enough headroom without leaving the user waiting indefinitely.
    private const val TIMEOUT_MS = 20_000

    suspend fun resolve(artist: String, track: String): String? {
        val mbid = withContext(Dispatchers.IO) { findRecordingMbid(artist, track) }
            ?: return null
        // Honour MusicBrainz's ≤ 1 req/s policy between the two calls.
        // delay() suspends the coroutine without blocking any thread.
        delay(1_000)
        return withContext(Dispatchers.IO) { fetchSpotifyUrlForMbid(mbid) }
    }

    /**
     * Step 1 — search for a recording matching the artist + track name.
     * Returns the MBID of the top-scored result, or null if nothing found.
     *
     * limit=5 gives us a small buffer in case the first result lacks URL rels,
     * but we only use the top result here since MusicBrainz sorts by relevance
     * and the lookup either has a Spotify URL or it doesn't.
     */
    private fun findRecordingMbid(artist: String, track: String): String? {
        return try {
            val query = URLEncoder.encode(
                "recording:\"${track.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\"",
                "UTF-8"
            )
            val json       = fetch(URL("$BASE_URL/recording?query=$query&limit=5&fmt=json"))
            val recordings = json.optJSONArray("recordings") ?: return null
            if (recordings.length() == 0) return null
            recordings.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /**
     * Step 2 — look up the recording by MBID with url-rels included.
     * Scans the returned relations for a Spotify track URL.
     */
    private fun fetchSpotifyUrlForMbid(mbid: String): String? {
        return try {
            val json      = fetch(URL("$BASE_URL/recording/$mbid?inc=url-rels&fmt=json"))
            val relations = json.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val rel = relations.getJSONObject(i)
                if (rel.optString("target-type") != "url") continue
                val href = rel.optJSONObject("url")?.optString("resource") ?: continue
                if (href.startsWith("https://open.spotify.com/track/")) return href
            }
            null
        } catch (e: Exception) { null }
    }

    private fun fetch(url: URL): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }
}
