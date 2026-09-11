package com.trustyyellowcabs.driver.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

object GoogleDriveUtils {

    /**
     * Extracts the Google Drive file ID from various URL formats.
     * Supported formats:
     * - https://drive.google.com/file/d/FILE_ID/view...
     * - https://drive.google.com/open?id=FILE_ID
     * - https://drive.google.com/uc?id=FILE_ID
     * - https://lh3.googleusercontent.com/d/FILE_ID
     * - Plain file ID
     */
    fun extractDriveFileId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 1. /file/d/{id} pattern
        val patternFileD = Pattern.compile("/file/d/([a-zA-Z0-9_-]+)")
        val matcherFileD = patternFileD.matcher(trimmed)
        if (matcherFileD.find()) {
            return matcherFileD.group(1)
        }

        // 2. id={id} pattern
        val patternIdParam = Pattern.compile("[?&]id=([a-zA-Z0-9_-]+)")
        val matcherIdParam = patternIdParam.matcher(trimmed)
        if (matcherIdParam.find()) {
            return matcherIdParam.group(1)
        }

        // 3. /d/{id} pattern (e.g. googleusercontent)
        val patternD = Pattern.compile("/d/([a-zA-Z0-9_-]+)")
        val matcherD = patternD.matcher(trimmed)
        if (matcherD.find()) {
            return matcherD.group(1)
        }

        // 4. If it's just a raw ID without slashes or dots
        if (!trimmed.contains("/") && !trimmed.contains(".") && trimmed.length >= 20) {
            return trimmed
        }

        return null
    }

    /**
     * Converts a Google Drive link, file ID, or standard URL to a direct-view image URL
     * using the high-speed Google UserContent / thumbnail CDN.
     */
    fun toDirectImageUrl(urlOrId: String): String {
        val trimmed = urlOrId.trim()
        if (trimmed.isEmpty()) return ""
        
        // If it's already an HTTP image url not from Drive (e.g. Firebase storage), return directly
        if (trimmed.startsWith("http", ignoreCase = true) && 
            !trimmed.contains("drive.google.com", ignoreCase = true) && 
            !trimmed.contains("docs.google.com", ignoreCase = true)) {
            return trimmed
        }

        val fileId = extractDriveFileId(trimmed) ?: return trimmed
        return "https://lh3.googleusercontent.com/d/$fileId"
    }

    fun toThumbnailUrl(urlOrId: String): String {
        val trimmed = urlOrId.trim()
        val fileId = extractDriveFileId(trimmed) ?: return toDirectImageUrl(trimmed)
        return "https://drive.google.com/thumbnail?id=$fileId&sz=w1000"
    }

    /**
     * Downloads an image from a Google Drive direct URL or standard URL and saves it locally
     * to the app's internal files directory as driver_selfie.jpg for meter and PDF receipts.
     */
    suspend fun downloadAndSaveDriverPhoto(context: Context, driveUrlOrId: String, forceRedownload: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            val trimmed = driveUrlOrId.trim()
            if (trimmed.isEmpty()) return@withContext null

            val photoFile = File(context.filesDir, "driver_selfie.jpg")
            val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
            val savedUrl = prefs.getString("driver_photo_url", "") ?: ""

            // If the local file already exists on disk and photo URL hasn't changed, reuse local file immediately!
            if (!forceRedownload && photoFile.exists() && photoFile.length() > 500L && savedUrl == trimmed) {
                val path = photoFile.absolutePath
                if (prefs.getString("driver_selfie_path", "") != path) {
                    prefs.edit().putString("driver_selfie_path", path).apply()
                }
                return@withContext path
            }

            val candidateUrls = mutableListOf<String>()
            val directUrl = toDirectImageUrl(trimmed)
            if (directUrl.isNotEmpty()) candidateUrls.add(directUrl)

            val thumbUrl = toThumbnailUrl(trimmed)
            if (thumbUrl.isNotEmpty() && !candidateUrls.contains(thumbUrl)) candidateUrls.add(thumbUrl)

            val fileId = extractDriveFileId(trimmed)
            if (fileId != null) {
                val ucUrl = "https://drive.google.com/uc?export=view&id=$fileId"
                if (!candidateUrls.contains(ucUrl)) candidateUrls.add(ucUrl)
            }

            if (trimmed.startsWith("http", ignoreCase = true) && !candidateUrls.contains(trimmed)) {
                candidateUrls.add(trimmed)
            }

            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            for (targetUrl in candidateUrls) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()

                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                FileOutputStream(photoFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }

                                val path = photoFile.absolutePath
                                prefs.edit()
                                    .putString("driver_selfie_path", path)
                                    .putString("driver_photo_url", trimmed)
                                    .apply()
                                return@withContext path
                            }
                        }
                    }
                } catch (ignored: Exception) {
                    // Try next candidate url
                }
            }
            if (photoFile.exists() && photoFile.length() > 500L) {
                photoFile.absolutePath
            } else {
                null
            }
        }
    }
}
