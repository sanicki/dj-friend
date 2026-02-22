package com.djfriend.app.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {

    /**
     * Downloads the APK from [apkUrl] into the app cache, then triggers the system
     * package installer via a FileProvider URI.
     * Calls [onProgress] with 0–100 during download, then [onInstallReady] when done.
     * Calls [onError] on any failure.
     */
    suspend fun downloadAndInstall(
        context:        Context,
        apkUrl:         String,
        tagName:        String,
        onProgress:     (Int) -> Unit,
        onInstallReady: () -> Unit,
        onError:        (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "updates").also { it.mkdirs() }
            val apkFile  = File(cacheDir, "djfriend-${tagName}.apk")

            // Download with progress
            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "DJFriend-Android")
            conn.connect()
            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } >= 0) {
                        output.write(buffer, 0, bytes)
                        downloaded += bytes
                        if (total > 0) {
                            withContext(Dispatchers.Main) {
                                onProgress((downloaded * 100 / total).toInt())
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
                // Trigger system installer
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(install)
                onInstallReady()
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Download failed") }
        }
    }
}
