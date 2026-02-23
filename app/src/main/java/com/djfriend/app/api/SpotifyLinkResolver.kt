package com.djfriend.app.api

import kotlinx.coroutines.Dispatchers
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
 * No API key required. Rate limit: ≤ 1 request/second (enforced by the 1 s
 * delay between the two calls). Free for non-commercial use.
 *
 * Returns null if no Spotify URL is found in MusicBrainz for this recording,
 * in which case callers should fall back to copying "artist - track" instead.
 */
object SpotifyLinkResolver {

    private const val BASE_URL  = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT = "DJFriend/1.0 ( https://github.com/sanicki/dj-friend )"

    suspend fun resolve(artist: String, track: String): String? = withContext(Dispatchers.IO) {
        val mbid = findRecordingMbid(artist, track) ?: return@withContext null
        // MusicBrainz asks clients to stay under 1 req/s — honour that between the two calls
        Thread.sleep(1_000)
        fetchSpotifyUrlForMbid(mbid)
    }

    /**
     * Step 1 — search for a recording matching the artist + track name.
     * Returns the MBID of the highest-scored result that is likely correct,
     * or null if nothing suitable is found.
     *
     * We request up to 5 candidates and return the first MBID; the lookup in
     * step 2 will either have a Spotify URL or not — if not, the whole resolve()
     * returns null and the caller falls back gracefully.
     */
    private fun findRecordingMbid(artist: String, track: String): String? {
        return try {
            // Lucene query: both fields must match
            val query   = URLEncoder.encode(
                "recording:\"${track.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\"",
                "UTF-8"
            )
            val url = URL("$BASE_URL/recording?query=$query&limit=5&fmt=json")
            val json = fetch(url)
            val recordings = json.optJSONArray("recordings") ?: return null
            if (recordings.length() == 0) return null
            // Take the top result — MusicBrainz search results are score-sorted
            recordings.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    /**
     * Step 2 — look up the recording by MBID with url-rels included.
     * Scans the returned relations for a Spotify track URL.
     */
    private fun fetchSpotifyUrlForMbid(mbid: String): String? {
        return try {
            val url  = URL("$BASE_URL/recording/$mbid?inc=url-rels&fmt=json")
            val json = fetch(url)
            val relations = json.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val rel        = relations.getJSONObject(i)
                val targetType = rel.optString("target-type")
                if (targetType != "url") continue
                val href = rel.optJSONObject("url")?.optString("resource") ?: continue
                if (href.startsWith("https://open.spotify.com/track/")) return href
            }
            null
        } catch (e: Exception) { null }
    }

    private fun fetch(url: URL): JSONObject {
        val conn = url.openConnection() as HttpURLConnection
        // MusicBrainz requires a meaningful User-Agent identifying the application
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }
}
