package com.trustyyellowcabs.driver.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object NetworkTimeHelper {
    private const val TAG = "NetworkTimeHelper"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    @Volatile
    private var timeOffsetMs: Long? = null

    /**
     * Synthetically queries a highly reliable web host to read the server's correct HTTP Date header.
     * This calculates the offset relative to the local system clock to ensure pinpoint overall accuracy.
     */
    suspend fun syncTimeOffset() {
        withContext(Dispatchers.IO) {
            try {
                // Fetch header from a highly available, robust server
                val request = Request.Builder()
                    .url("https://www.google.com")
                    .head()
                    .build()
                client.newCall(request).execute().use { response ->
                    val dateStr = response.header("Date")
                    if (dateStr != null) {
                        // HTTP date format is typically: EEE, dd MMM yyyy HH:mm:ss z (e.g. Fri, 19 Jun 2026 21:03:00 GMT)
                        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                        val serverDate = format.parse(dateStr)
                        if (serverDate != null) {
                            val systemTime = System.currentTimeMillis()
                            val networkTime = serverDate.time
                            timeOffsetMs = networkTime - systemTime
                            Log.d(TAG, "Successfully synced network time. Calculated system time offset: $timeOffsetMs ms")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing Date header from server for timezone/clock synchronization", e)
            }
        }
    }

    /**
     * Computes the current time in milliseconds with precision compensation if synced, otherwise falls back to system.
     */
    fun getCurrentTimeMillis(): Long {
        val offset = timeOffsetMs
        return if (offset != null) {
            System.currentTimeMillis() + offset
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * Helper to retrieve SimpleDateFormat instances tied exactly to Asia/Kolkata TimeZone.
     */
    fun getAsiaKolkataFormatter(pattern: String): SimpleDateFormat {
        return SimpleDateFormat(pattern, Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
    }
}
