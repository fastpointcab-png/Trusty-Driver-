package com.trustyyellowcabs.driver

import android.app.Application
import android.content.Context
import android.util.Log

class TaxiDriverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Enforce FCM auto-init disablement immediately at process launch
            val fcmPrefs = getSharedPreferences("com.google.firebase.messaging", Context.MODE_PRIVATE)
            fcmPrefs.edit().putBoolean("auto_init", false).apply()
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
        } catch (e: Throwable) {
            Log.d("TaxiDriverApplication", "FCM auto-init suppression in application: ${e.message}")
        }
    }
}
