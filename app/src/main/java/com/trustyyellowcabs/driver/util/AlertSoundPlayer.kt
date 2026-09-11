package com.trustyyellowcabs.driver.util

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File

object AlertSoundPlayer {
    private const val TAG = "AlertSoundPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var cancelMediaPlayer: MediaPlayer? = null
    private var isPlayingAlert = false

    /**
     * Plays the custom MP3 trip notification sound instead of default mobile ringtone.
     * Looks first for custom mp3 in app internal storage, then falls back to res/raw/trip_alert.mp3.
     */
    @Synchronized
    fun playTripAlert(context: Context, loop: Boolean = true) {
        stopTripAlert()

        try {
            val appContext = context.applicationContext
            val customFile = File(appContext.filesDir, "trip_alert.mp3")

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val player = MediaPlayer()
            player.setAudioAttributes(audioAttributes)

            var initialized = false
            if (customFile.exists() && customFile.length() > 0) {
                Log.d(TAG, "Playing trip alert from custom internal storage file: ${customFile.absolutePath}")
                try {
                    player.setDataSource(appContext, Uri.fromFile(customFile))
                    player.isLooping = loop
                    player.prepare()
                    initialized = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed initializing custom file dataSource: ${e.message}")
                }
            }

            if (!initialized) {
                val rawId = appContext.resources.getIdentifier("trip_alert", "raw", appContext.packageName)
                if (rawId != 0) {
                    Log.d(TAG, "Playing trip alert from raw resource ID: $rawId")
                    val afd: AssetFileDescriptor? = appContext.resources.openRawResourceFd(rawId)
                    if (afd != null) {
                        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        player.isLooping = loop
                        player.prepare()
                        initialized = true
                    }
                }
            }

            if (initialized) {
                player.setVolume(1.0f, 1.0f)
                player.start()
                mediaPlayer = player
                isPlayingAlert = true
                Log.d(TAG, "Custom MP3 trip sound playback started successfully")
            } else {
                player.release()
                Log.w(TAG, "No audio source could be prepared for AlertSoundPlayer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing custom MP3 trip alert sound", e)
            isPlayingAlert = false
            mediaPlayer = null
        }
    }

    /**
     * Stops and releases the trip alert MP3 player immediately.
     */
    @Synchronized
    fun stopTripAlert() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MP3 trip alert", e)
        } finally {
            mediaPlayer = null
            isPlayingAlert = false
        }
    }

    @Synchronized
    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Plays custom MP3 audio sound when an assigned trip is cancelled or unassigned.
     * Supports:
     * - Internal filesDir: trip_cancelled.mp3, trip_cancel.mp3, trip_unassigned.mp3, trip_unassign.mp3, cancel.mp3, unassign.mp3
     * - External files: Android/data/.../files/
     * - res/raw: trip_cancelled, trip_cancel, trip_unassigned, trip_unassign, cancel, unassign
     * - assets: trip_cancelled.mp3, trip_cancel.mp3, etc.
     * - Fallback: System notification alert tone.
     */
    @Synchronized
    fun playTripCancelledAlert(context: Context) {
        stopTripAlert()
        stopTripCancelledAlert()

        try {
            val appContext = context.applicationContext
            val candidateNames = listOf(
                "trip_cancelled",
                "trip_cancel",
                "trip_unassigned",
                "trip_unassign",
                "cancelled",
                "cancel",
                "unassigned",
                "unassign"
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val player = MediaPlayer()
            player.setAudioAttributes(audioAttributes)

            var initialized = false

            // 1. Check internal storage filesDir
            for (name in candidateNames) {
                val file = File(appContext.filesDir, "$name.mp3")
                if (file.exists() && file.length() > 0) {
                    try {
                        player.setDataSource(appContext, Uri.fromFile(file))
                        player.prepare()
                        initialized = true
                        Log.i(TAG, "Loaded cancelled/unassigned MP3 from filesDir: ${file.absolutePath}")
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed loading file ${file.name}: ${e.message}")
                    }
                }
            }

            // 2. Check external storage files
            if (!initialized) {
                val extDir = appContext.getExternalFilesDir(null)
                if (extDir != null) {
                    for (name in candidateNames) {
                        val file = File(extDir, "$name.mp3")
                        if (file.exists() && file.length() > 0) {
                            try {
                                player.setDataSource(appContext, Uri.fromFile(file))
                                player.prepare()
                                initialized = true
                                Log.i(TAG, "Loaded cancelled/unassigned MP3 from extDir: ${file.absolutePath}")
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed loading ext file ${file.name}: ${e.message}")
                            }
                        }
                    }
                }
            }

            // 3. Check res/raw
            if (!initialized) {
                for (name in candidateNames) {
                    val rawId = appContext.resources.getIdentifier(name, "raw", appContext.packageName)
                    if (rawId != 0) {
                        try {
                            val afd: AssetFileDescriptor? = appContext.resources.openRawResourceFd(rawId)
                            if (afd != null) {
                                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                                player.prepare()
                                initialized = true
                                Log.i(TAG, "Loaded cancelled/unassigned MP3 from res/raw/$name")
                                break
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed reading raw audio $name: ${e.message}")
                        }
                    }
                }
            }

            // 4. Check assets
            if (!initialized) {
                try {
                    val assetList = appContext.assets.list("") ?: emptyArray()
                    for (name in candidateNames) {
                        val fileName = "$name.mp3"
                        if (assetList.contains(fileName)) {
                            val afd = appContext.assets.openFd(fileName)
                            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                            player.prepare()
                            initialized = true
                            Log.i(TAG, "Loaded cancelled/unassigned MP3 from assets/$fileName")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Asset check error: ${e.message}")
                }
            }

            // 5. Fallback: System notification / alarm alert tone
            if (!initialized) {
                try {
                    val defaultToneUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    if (defaultToneUri != null) {
                        player.setDataSource(appContext, defaultToneUri)
                        player.prepare()
                        initialized = true
                        Log.i(TAG, "Falling back to system notification sound for trip cancellation")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "System tone fallback error: ${e.message}")
                }
            }

            if (initialized) {
                player.isLooping = false
                player.setVolume(1.0f, 1.0f)
                player.setOnCompletionListener { mp ->
                    try {
                        mp.stop()
                        mp.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error on cancel mp release: ${e.message}")
                    }
                    synchronized(this) {
                        if (cancelMediaPlayer == mp) {
                            cancelMediaPlayer = null
                        }
                    }
                }
                player.start()
                cancelMediaPlayer = player
                Log.i(TAG, "Trip cancelled/unassigned audio playback started successfully")
            } else {
                player.release()
                Log.w(TAG, "No audio source could be prepared for trip cancellation sound")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing trip cancelled audio alert", e)
            cancelMediaPlayer = null
        }
    }

    @Synchronized
    fun stopTripCancelledAlert() {
        try {
            cancelMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping trip cancelled alert", e)
        } finally {
            cancelMediaPlayer = null
        }
    }
}
