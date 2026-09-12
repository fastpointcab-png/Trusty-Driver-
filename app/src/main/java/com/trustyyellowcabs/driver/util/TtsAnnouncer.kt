package com.trustyyellowcabs.driver.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object TtsAnnouncer {
    private const val TAG = "TtsAnnouncer"
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Pre-initialization stub.
     */
    fun preInitialize(context: Context) {
        Log.d(TAG, "TtsAnnouncer initialized. Ready for pre-recorded raw audio playback.")
    }

    /**
     * Plays pre-recorded Tamil audio files from the raw resources folder, assets, or internal storage.
     * Supports resilient playback on all Android versions and Play Store App Bundle (.aab) installations.
     */
    fun speakTamil(context: Context, text: String = "உங்கள் பயணத்திற்கு நன்றி. இறங்குவதற்கு முன் உடமைகளைச் சரிபார்த்துக் கொள்ளவும். இந்த நாள் இனிய நாளாக அமையட்டும்.") {
        val rawResName = when {
            text.contains("சீட் பெல்ட்") || text.contains("பாதுகாப்பாக") -> "trip_start"
            text.contains("நன்றி") || text.contains("உடமைகளை") -> "trip_end"
            else -> null
        }

        if (rawResName != null) {
            val appContext = context.applicationContext
            // 1. Resolve raw resource identifier across package variations
            var resId = appContext.resources.getIdentifier(rawResName, "raw", appContext.packageName)
            if (resId == 0) {
                resId = appContext.resources.getIdentifier(rawResName, "raw", "com.trustyyellowcab.driver")
            }

            if (resId != 0) {
                Log.d(TAG, "Pre-recorded raw audio '$rawResName' found (resId: $resId). Playing via MediaPlayer...")
                val played = playRawAudio(appContext, resId, rawResName)
                if (!played) {
                    playFromAssetsOrFiles(appContext, rawResName)
                }
            } else {
                Log.d(TAG, "Raw audio '$rawResName' not in resId, checking assets and internal files...")
                playFromAssetsOrFiles(appContext, rawResName)
            }
        } else {
            Log.d(TAG, "No matching audio mapped for text: $text")
        }
    }

    private fun playRawAudio(context: Context, resId: Int, name: String): Boolean {
        return try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            val appContext = context.applicationContext
            var player: MediaPlayer? = null

            // Strategy 1: Standard MediaPlayer.create
            try {
                player = MediaPlayer.create(appContext, resId)
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer.create failed for $name: ${e.message}")
            }

            // Strategy 2: Open Raw Resource FileDescriptor
            if (player == null) {
                try {
                    val afd = appContext.resources.openRawResourceFd(resId)
                    if (afd != null) {
                        val p = MediaPlayer()
                        p.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .build()
                        )
                        p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        p.prepare()
                        player = p
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "openRawResourceFd failed for $name (may be compressed in AAB): ${e.message}")
                }
            }

            // Strategy 3: Stream extraction to cache (unconditionally works in Play Store AAB even if compressed)
            if (player == null) {
                try {
                    val cacheFile = File(appContext.cacheDir, "raw_voice_${name}_$resId.mp3")
                    if (!cacheFile.exists() || cacheFile.length() == 0L) {
                        appContext.resources.openRawResource(resId).use { input ->
                            FileOutputStream(cacheFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        val p = MediaPlayer()
                        p.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .build()
                        )
                        p.setDataSource(cacheFile.absolutePath)
                        p.prepare()
                        player = p
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Stream cache extraction failed for $name: ${e.message}")
                }
            }

            if (player != null) {
                player.setVolume(1.0f, 1.0f)
                player.setOnCompletionListener { mp ->
                    try {
                        mp.release()
                    } catch (ignored: Exception) {}
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                }
                player.start()
                mediaPlayer = player
                Log.d(TAG, "Raw audio '$name' started playing successfully.")
                true
            } else {
                Log.e(TAG, "All raw strategies failed to prepare MediaPlayer for '$name'")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing raw audio file '$name': ${e.message}", e)
            false
        }
    }

    private fun playFromAssetsOrFiles(context: Context, name: String): Boolean {
        try {
            val appContext = context.applicationContext
            val internalFile = File(appContext.filesDir, "$name.mp3")
            if (internalFile.exists() && internalFile.length() > 0L) {
                val p = MediaPlayer()
                p.setDataSource(internalFile.absolutePath)
                p.prepare()
                p.start()
                p.setOnCompletionListener { it.release() }
                mediaPlayer = p
                Log.d(TAG, "Playing '$name' from internal filesDir: ${internalFile.absolutePath}")
                return true
            }

            val assetList = appContext.assets.list("") ?: emptyArray()
            val assetName = "$name.mp3"
            if (assetList.contains(assetName)) {
                val afd = appContext.assets.openFd(assetName)
                val p = MediaPlayer()
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                p.prepare()
                p.start()
                p.setOnCompletionListener { it.release() }
                mediaPlayer = p
                Log.d(TAG, "Playing '$name' from assets/$assetName")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset/file playback fallback failed for $name: ${e.message}")
        }
        return false
    }

    /**
     * Clean up media player resources when active components are destroyed.
     */
    fun shutdown() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            Log.d(TAG, "Audio player shut down successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down audio player: ${e.message}", e)
        }
    }
}
