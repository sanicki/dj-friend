package com.djfriend.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName:     String,  // e.g. "v1.0.0"
    val apkUrl:      String,  // direct download URL of the APK asset
    val versionName: String   // stripped tag, e.g. "1.0.0"
)

object UpdateChecker {

    /**
     * Queries the GitHub Releases API for the latest non-prerelease release of [repo].
     * Tries /releases/latest first; if that returns 404 (no releases yet or all are
     * pre-releases) falls back to /releases which lists all releases and picks the
     * highest semver non-prerelease entry manually.
     * Returns a [ReleaseInfo] if a newer version than [currentVersion] exists, else null.
     */
    suspend fun checkForUpdate(repo: String, currentVersion: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            if (repo.isBlank() || !repo.contains("/")) return@withContext null
            try {
                val release = fetchLatest(repo) ?: fetchFromList(repo)
                    ?: return@withContext null

                val latestVersion  = release.versionName
                val currentCleaned = currentVersion.trimStart('v')

                if (isNewer(latestVersion, currentCleaned)) release else null

            } catch (e: Exception) { null }
        }

    /** Tries /releases/latest — returns null on 404 or missing APK asset. */
    private fun fetchLatest(repo: String): ReleaseInfo? {
        val conn = openGitHubConnection("https://api.github.com/repos/$repo/releases/latest")
        if (conn.responseCode != 200) return null
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        return parseRelease(json)
    }

    /**
     * Falls back to /releases?per_page=20, finds the highest-versioned
     * non-prerelease non-draft release that has an APK asset.
     */
    private fun fetchFromList(repo: String): ReleaseInfo? {
        val conn = openGitHubConnection(
            "https://api.github.com/repos/$repo/releases?per_page=20"
        )
        if (conn.responseCode != 200) return null
        val array = JSONArray(conn.inputStream.bufferedReader().readText())
        var best: ReleaseInfo? = null
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.optBoolean("prerelease") || obj.optBoolean("draft")) continue
            val candidate = parseRelease(obj) ?: continue
            if (best == null || isNewer(candidate.versionName, best.versionName)) {
                best = candidate
            }
        }
        return best
    }

    /** Parses a single release JSON object into a ReleaseInfo, or null if no APK asset. */
    private fun parseRelease(json: JSONObject): ReleaseInfo? {
        val tagName = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assets  = json.optJSONArray("assets") ?: return null
        var apkUrl  = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }
        if (apkUrl.isBlank()) return null
        return ReleaseInfo(
            tagName     = tagName,
            apkUrl      = apkUrl,
            versionName = tagName.trimStart('v')
        )
    }

    private fun openGitHubConnection(urlString: String): HttpURLConnection {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "DJFriend-Android")
        conn.connectTimeout = 8_000
        conn.readTimeout    = 8_000
        return conn
    }

    /** Returns true if [candidate] is a higher semver than [current]. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.trimStart('v').split(".").map { it.toIntOrNull() ?: 0 }
        val v = current.trimStart('v').split(".").map   { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, v.size)) {
            val ci = c.getOrElse(i) { 0 }
            val vi = v.getOrElse(i) { 0 }
            if (ci != vi) return ci > vi
        }
        return false
    }
}
