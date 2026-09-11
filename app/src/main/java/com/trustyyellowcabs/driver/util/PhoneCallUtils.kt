package com.trustyyellowcabs.driver.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object PhoneCallUtils {
    const val OFFICE_PHONE = "+914223596446"
    const val OFFICE_PHONE_DISPLAY = "+91 422 359 6446"

    /**
     * Formats raw phone input into a standard dialable Indian phone format (+91XXXXXXXXXX).
     */
    fun formatDialablePhoneNumber(rawPhone: String): String {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) return ""

        // If it starts with +, preserve digits after +
        if (trimmed.startsWith("+")) {
            val digits = trimmed.substring(1).filter { it.isDigit() }
            return "+$digits"
        }

        // Clean digits
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        return when {
            // Already includes 91 country code (12 digits e.g. 919876543210)
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            // Standard 10 digit Indian mobile number (e.g. 9876543210)
            digits.length == 10 -> "+91$digits"
            // Starts with 0 (e.g. 09876543210 or landline 04223596446)
            digits.startsWith("0") && digits.length >= 11 -> "+91${digits.substring(1)}"
            // Landline (e.g. 4223596446)
            digits.length == 10 && digits.startsWith("422") -> "+91$digits"
            // Default 10-digit extraction
            digits.length >= 10 -> "+91${digits.takeLast(10)}"
            else -> digits
        }
    }

    fun makeCall(context: Context, rawPhone: String, errorMessage: String = "Unable to launch call dialer") {
        val dialNumber = formatDialablePhoneNumber(rawPhone)
        if (dialNumber.isBlank()) {
            return
        }

        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(dialNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Silently ignore or log without popup toast
        }
    }

    fun callOffice(context: Context) {
        makeCall(context, OFFICE_PHONE, "Unable to dial office support")
    }
}
