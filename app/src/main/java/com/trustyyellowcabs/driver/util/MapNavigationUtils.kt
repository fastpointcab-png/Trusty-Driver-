package com.trustyyellowcabs.driver.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object MapNavigationUtils {
    fun openGoogleMaps(context: Context, address: String) {
        if (address.isBlank()) {
            return
        }
        val encoded = Uri.encode(address.trim())
        // Try direct Google Maps navigation intent
        val navigationUri = Uri.parse("google.navigation:q=$encoded")
        val mapIntent = Intent(Intent.ACTION_VIEW, navigationUri).apply {
            setPackage("com.google.android.apps.maps")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            if (mapIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mapIntent)
                return
            }
        } catch (ignored: Exception) {}

        // Fallback to geo URI
        try {
            val geoUri = Uri.parse("geo:0,0?q=$encoded")
            val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            try {
                val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$encoded")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
            } catch (ex: Exception) {
                // Ignore fallback failure gracefully
            }
        }
    }
}
