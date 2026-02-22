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
 *   1. MusicBrainz recording search  → get up to 5 candidate MBIDs
 *   2. MusicBrainz recording lookup  → get url-rels, extract the Spotify URL
 *      (repeated for each candidate in order until a Spotify URL is found)
 *
 * No API key required. Rate limit: ≤ 1 request/second, honoured by a coroutine
 * delay between each network call. Free for non-commercial use.
 *
 * Returns null if no Spotify URL is found across all candidates, in which case
 * callers should fall back to copying "artist - track" to clipboard instead.
 */
object SpotifyLinkResolver {

    private const val BASE_URL   = "https://musicbrainz.org/ws/2"
    private const val USER_AGENT = "DJFriend/1.0 ( https://github.com/sanicki/dj-friend )"

    // MusicBrainz can be slow on cold requests; 20 s gives enough headroom.
    private const val TIMEOUT_MS = 20_000

    suspend fun resolve(artist: String, track: String): String? {
        // Step 1: search for candidate recording MBIDs
        val mbids = withContext(Dispatchers.IO) { findRecordingMbids(artist, track) }
        if (mbids.isEmpty()) return null

        // Step 2: look up each candidate in order until we find a Spotify URL.
        // Each lookup is preceded by a 1 s delay to honour MusicBrainz rate policy.
        for (mbid in mbids) {
            delay(1_000)
            val spotifyUrl = withContext(Dispatchers.IO) { fetchSpotifyUrlForMbid(mbid) }
            if (spotifyUrl != null) return spotifyUrl
        }
        return null
    }

    /**
     * Step 1 — search recordings and return the MBIDs of the top matches.
     * We fetch up to 5 and try them all in step 2, since the top result
     * may be a recording that has no Spotify URL linked yet in MusicBrainz.
     */
    private fun findRecordingMbids(artist: String, track: String): List<String> {
        return try {
            val query = URLEncoder.encode(
                "recording:\"${track.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\"",
                "UTF-8"
            )
            val json       = fetch(URL("$BASE_URL/recording?query=$query&limit=5&fmt=json"))
            val recordings = json.optJSONArray("recordings") ?: return emptyList()
            (0 until recordings.length())
                .mapNotNull { i ->
                    recordings.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }
                }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Step 2 — look up a recording by MBID with url-rels.
     *
     * MusicBrainz url-rels JSON shape:
     *   { "relations": [ { "url": { "resource": "https://open.spotify.com/track/..." } }, ... ] }
     *
     * We do NOT filter by "target-type" — its presence and exact value varies
     * across MusicBrainz API responses and it is not reliably set on url-rels.
     * Filtering on it causes every relation to be silently skipped, which is
     * why resolve() was returning null immediately without any network failure.
     * Instead we check every relation's url.resource directly.
     */
    private fun fetchSpotifyUrlForMbid(mbid: String): String? {
        return try {
            val json      = fetch(URL("$BASE_URL/recording/$mbid?inc=url-rels&fmt=json"))
            val relations = json.optJSONArray("relations") ?: return null
            for (i in 0 until relations.length()) {
                val href = relations.getJSONObject(i)
                    .optJSONObject("url")
                    ?.optString("resource")
                    ?: continue
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
