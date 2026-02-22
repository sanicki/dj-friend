package com.djfriend.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName:    String,   // e.g. "v1.2.3"
    val apkUrl:     String,   // direct download URL of the APK asset
    val versionName: String   // stripped tag, e.g. "1.2.3"
)

object UpdateChecker {

    /**
     * Queries the GitHub Releases API for the latest release of [repo] ("owner/repo").
     * Returns a [ReleaseInfo] if a newer version than [currentVersion] exists, or null.
     */
    suspend fun checkForUpdate(repo: String, currentVersion: String): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            if (repo.isBlank() || !repo.contains("/")) return@withContext null
            try {
                val url  = URL("https://api.github.com/repos/$repo/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "DJFriend-Android")
                conn.connectTimeout = 8_000
                conn.readTimeout    = 8_000

                if (conn.responseCode != 200) return@withContext null

                val json    = JSONObject(conn.inputStream.bufferedReader().readText())
                val tagName = json.optString("tag_name") ?: return@withContext null

                // Find the first .apk asset
                val assets  = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl  = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isBlank()) return@withContext null

                val latestVersion  = tagName.trimStart('v')
                val currentCleaned = currentVersion.trimStart('v')

                if (isNewer(latestVersion, currentCleaned)) {
                    ReleaseInfo(tagName, apkUrl, latestVersion)
                } else null

            } catch (e: Exception) { null }
        }

    /** Returns true if [candidate] is a higher semver than [current]. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val v = current.split(".").map    { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, v.size)) {
            val ci = c.getOrElse(i) { 0 }
            val vi = v.getOrElse(i) { 0 }
            if (ci != vi) return ci > vi
        }
        return false
    }
}
