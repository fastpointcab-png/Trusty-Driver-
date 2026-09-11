package com.trustyyellowcabs.driver.util

import android.content.Context
import android.media.MediaPlayer
import android.util.Log

object TtsAnnouncer {
    private const val TAG = "TtsAnnouncer"
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Pre-initialization is no longer required as TTS has been completely removed.
     * We keep this method as a lightweight stub so that existing lifecycle hooks do not break.
     */
    fun preInitialize(context: Context) {
        Log.d(TAG, "TTS completely removed. Running in pure pre-recorded audio mode.")
    }

    /**
     * Plays pre-recorded Tamil audio files from the raw resources folder if they exist.
     * If the corresponding MP3 file does not exist, no sound will be played.
     */
    fun speakTamil(context: Context, text: String = "உங்கள் பயணத்திற்கு நன்றி. இறங்குவதற்கு முன் உடமைகளைச் சரிபார்த்துக் கொள்ளவும். இந்த நாள் இனிய நாளாக அமையட்டும்.") {
        val rawResName = when {
            text.contains("சீட் பெல்ட்") || text.contains("பாதுகாப்பாக") -> "trip_start"
            text.contains("நன்றி") || text.contains("உடமைகளை") -> "trip_end"
            else -> null
        }

        if (rawResName != null) {
            val resId = context.resources.getIdentifier(rawResName, "raw", context.packageName)
            if (resId != 0) {
                Log.d(TAG, "Pre-recorded raw audio '$rawResName' found. Playing via MediaPlayer...")
                playRawAudio(context, resId)
            } else {
                Log.d(TAG, "Pre-recorded raw audio '$rawResName' NOT found in res/raw/. No fallback audio played.")
            }
        } else {
            Log.d(TAG, "No matching audio mapped for text: $text")
        }
    }

    private fun playRawAudio(context: Context, resId: Int): Boolean {
        return try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer.create(context.applicationContext, resId)
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer == mp) {
                    mediaPlayer = null
                }
            }
            mediaPlayer?.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing raw MP3 audio file: ${e.message}", e)
            false
        }
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
