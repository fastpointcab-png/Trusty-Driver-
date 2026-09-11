package com.trustyyellowcabs.driver.util

import android.content.Context
import android.location.Geocoder
import android.util.Log
import java.util.Locale

object GeocoderHelper {
    private const val TAG = "GeocoderHelper"

    /**
     * Normalizes address spacing while preserving all precise details, plus codes, and highways.
     */
    fun cleanAddress(address: String): String {
        if (address.isBlank()) return address
        return address.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Resolves latitude and longitude coordinates into a friendly, precise, and correct street address.
     * Incorporates full postal address and plus code if they are available.
     * Returns null if resolution fails, allowing graceful fallback to formatted coordinates.
     */
    fun getAddressFromLatLng(context: Context, latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder is not present on this device/emulator.")
            return null
        }

        val geocoder = Geocoder(context, Locale.getDefault())
        return try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                
                // Get the complete main address line 0 (this represents the full postal address in Android)
                val fullAddress = address.getAddressLine(0) ?: ""
                
                // Retrieve the postal code
                val postalCode = address.postalCode ?: ""
                
                // Try to find a plus code in the fields or lines
                var plusCode = ""
                if (address.featureName != null && address.featureName.contains("+")) {
                    plusCode = address.featureName
                } else {
                    val lineCount = address.maxAddressLineIndex
                    for (i in 0..lineCount) {
                        val line = address.getAddressLine(i)
                        if (!line.isNullOrBlank() && line.contains("+")) {
                            val tokens = line.split(" ")
                            val found = tokens.find { it.contains("+") }
                            if (found != null) {
                                plusCode = found.trim().removeSuffix(",")
                                break
                            }
                        }
                    }
                }
                
                // Construct a highly precise address
                var finalAddress = if (fullAddress.isNotBlank()) {
                    fullAddress
                } else {
                    // Fallback to building parts if address line 0 is empty
                    val parts = mutableListOf<String>()
                    val featureName = address.featureName
                    val subLocality = address.subLocality
                    val locality = address.locality
                    val adminArea = address.adminArea
                    val country = address.countryName
                    
                    if (!featureName.isNullOrBlank()) parts.add(featureName)
                    if (!subLocality.isNullOrBlank()) parts.add(subLocality)
                    if (!locality.isNullOrBlank()) parts.add(locality)
                    if (!adminArea.isNullOrBlank()) parts.add(adminArea)
                    if (!postalCode.isNullOrBlank()) parts.add(postalCode)
                    if (!country.isNullOrBlank()) parts.add(country)
                    
                    parts.joinToString(", ")
                }
                
                // Format nicely: Ensure postal code is clearly represented at the end if it's available but missing
                if (postalCode.isNotBlank() && !finalAddress.contains(postalCode)) {
                    finalAddress = "$finalAddress, $postalCode"
                }
                
                // Ensure plus code is represented at the beginning if available but missing
                if (plusCode.isNotBlank() && !finalAddress.contains(plusCode)) {
                    finalAddress = "$plusCode, $finalAddress"
                }
                
                cleanAddress(finalAddress)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error geocoding lat/lng ($latitude, $longitude): ${e.localizedMessage}", e)
            null
        }
    }
}
