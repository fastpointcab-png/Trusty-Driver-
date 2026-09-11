package com.trustyyellowcabs.driver.network

import android.content.Context
import android.util.Log
import com.trustyyellowcabs.driver.data.Trip
import com.trustyyellowcabs.driver.data.TripRepository
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GoogleSheetsTripRequest(
    val type: String = "trip", // "login" or "trip" or "get_profile"
    val timestamp: Long = 0L,
    val tripIdCode: String = "",
    val driverName: String = "",
    val vehicleNumber: String = "",
    val vehicleCategory: String = "",
    val vehicleModel: String = "",
    val driverMobile: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val distance: Double = 0.0,
    val durationSeconds: Long = 0L,
    val totalFare: Double = 0.0,
    val baseFare: Double = 0.0,
    val perKmFare: Double = 0.0,
    val waitingChargePerMin: Double = 0.0,
    val minimumFare: Double = 0.0,
    val nightChargePercent: Double = 0.0,
    val dateStr: String = "",
    val startLocation: String = "",
    val endLocation: String = "",
    val ccCommission: Double = 0.0,
    val customerMobile: String = "",
    val isPackageMeter: Boolean = false,
    val packageName: String = "",
    val packageBaseFare: Double = 0.0,
    val includedKm: Double = 0.0,
    val includedMinutes: Int = 0,
    val extraKmRate: Double = 0.0,
    val extraTimeRate: Double = 0.0,
    val packageWaitingChargePerMin: Double = 0.0,
    val waitingSeconds: Long = 0L,
    val deviceId: String = ""
)

@JsonClass(generateAdapter = true)
data class GoogleSheetsResponse(
    val status: String,
    val message: String? = null,
    val userCount: Int? = null,
    val driverName: String? = null,
    val vehicleNumber: String? = null,
    val vehicleCategory: String? = null,
    val vehicleModel: String? = null
)

interface GoogleSheetsSyncApi {
    @POST
    suspend fun syncTripToSheets(
        @Url url: String,
        @Body body: GoogleSheetsTripRequest
    ): retrofit2.Response<GoogleSheetsResponse>
}

object GoogleSheetsSyncManager {
    private const val TAG = "GoogleSheetsSync"
    private const val PREFS_NAME = "TrustyYellowCabPrefs"
    private const val KEY_SHEETS_URL = "google_sheets_webapp_url"

    // SECURELY MASKED & DECRYPTED GOOGLE SHEETS WEB APP URL
    val HARDCODED_SHEETS_URL: String
        get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureSheetsWebhookUrl()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    // Add logging and custom timeout to follow redirects properly and handle high load
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://script.google.com/") // Place-holder base URL, will be overridden by full dynamic @Url
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(GoogleSheetsSyncApi::class.java)

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
            Log.d(TAG, "Generated new unique device ID: $deviceId")
        }
        return deviceId
    }

    fun getGoogleSheetsUrl(context: Context): String {
        if (HARDCODED_SHEETS_URL.isNotEmpty() && HARDCODED_SHEETS_URL != "YOUR_GOOGLE_SHEETS_WEB_APP_URL_HERE") {
            return HARDCODED_SHEETS_URL.trim()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SHEETS_URL, "") ?: ""
    }

    fun saveGoogleSheetsUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SHEETS_URL, url.trim()).apply()
    }

    /**
     * Sends a single trip row to Google Sheets Web App.
     */
    suspend fun syncTrip(context: Context, trip: Trip, repository: TripRepository): Boolean {
        if (!FirebaseManager.isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync is CUTOFF. Trip ${trip.tripIdCode} saved in local mobile Room database only.")
            return false
        }
        val customUrl = getGoogleSheetsUrl(context)
        if (customUrl.isEmpty()) {
            Log.w(TAG, "Sync failed: Google Sheets Web App URL is not configured.")
            return false
        }

        val timeFormatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm:ss a")
        val formattedStartTime = if (trip.startTime > 0) timeFormatter.format(java.util.Date(trip.startTime)) else "N/A"
        val formattedEndTime = if (trip.endTime > 0) timeFormatter.format(java.util.Date(trip.endTime)) else "N/A"

        val request = GoogleSheetsTripRequest(
            type = "trip",
            tripIdCode = trip.tripIdCode,
            driverName = trip.driverName,
            vehicleNumber = trip.vehicleNumber,
            vehicleCategory = trip.vehicleCategory,
            vehicleModel = trip.vehicleModel,
            driverMobile = trip.driverMobile,
            startTime = formattedStartTime,
            endTime = formattedEndTime,
            distance = trip.distance,
            durationSeconds = trip.durationSeconds,
            totalFare = trip.totalFare,
            baseFare = trip.baseFare,
            perKmFare = trip.perKmFare,
            waitingChargePerMin = trip.waitingChargePerMin,
            minimumFare = trip.minimumFare,
            nightChargePercent = trip.nightChargePercent,
            dateStr = trip.dateStr,
            startLocation = trip.startLocation,
            endLocation = trip.endLocation,
            ccCommission = trip.ccCommission,
            customerMobile = trip.customerMobile,
            isPackageMeter = trip.isPackageMeter,
            packageName = trip.packageName,
            packageBaseFare = trip.packageBaseFare,
            includedKm = trip.includedKm,
            includedMinutes = trip.includedMinutes,
            extraKmRate = trip.extraKmRate,
            extraTimeRate = trip.extraTimeRate,
            packageWaitingChargePerMin = trip.packageWaitingChargePerMin,
            waitingSeconds = trip.waitingSeconds,
            deviceId = getOrCreateDeviceId(context)
        )

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Syncing trip ${trip.tripIdCode} to Sheets...")
                val response = api.syncTripToSheets(customUrl, request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && (body.status == "success" || body.status == "OK")) {
                        Log.i(TAG, "Successfully synced trip ${trip.tripIdCode} to Google Sheets!")
                        // Update Room entity flag
                        val updatedTrip = trip.copy(isSyncedToSheets = true)
                        repository.updateTrip(updatedTrip)
                        true
                    } else {
                        Log.e(TAG, "Sheets app returned error: ${body?.message ?: "Unknown error"}")
                        false
                    }
                } else {
                    Log.e(TAG, "Network response code: ${response.code()}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during network sync to Google Sheets: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Sends a driver login/configuration row to Google Sheets Web App.
     * Google Sheets is separate and ALWAYS works.
     */
    fun syncDriverLogin(context: Context, driverName: String, vehicleNumber: String, vehicleCategory: String, vehicleModel: String, driverMobile: String) {
        val customUrl = getGoogleSheetsUrl(context)
        if (customUrl.isEmpty()) {
            Log.w(TAG, "Driver login sync skipped: Google Sheets Web App URL is not configured.")
            return
        }

        val formatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm:ss a")
        val currentDateStr = formatter.format(java.util.Date(com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis()))

        val request = GoogleSheetsTripRequest(
            type = "login",
            timestamp = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis(),
            dateStr = currentDateStr,
            driverName = driverName,
            vehicleNumber = vehicleNumber,
            vehicleCategory = vehicleCategory,
            vehicleModel = vehicleModel,
            driverMobile = driverMobile,
            deviceId = getOrCreateDeviceId(context)
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Syncing driver login ($driverName) to Sheets...")
                val response = api.syncTripToSheets(customUrl, request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && (body.status == "success" || body.status == "OK")) {
                        Log.i(TAG, "Successfully synced driver login ($driverName) to Google Sheets!")
                    } else {
                        Log.e(TAG, "Sheets app returned login error: ${body?.message ?: "Unknown error"}")
                    }
                } else {
                    Log.e(TAG, "Network response code for login sync: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during driver login network sync to Google Sheets: ${e.message}", e)
            }
        }
    }

    /**
     * Sends a driver logout/status row update to Google Sheets Web App.
     * Google Sheets is separate and ALWAYS works.
     */
    fun syncDriverLogout(context: Context, driverName: String, vehicleNumber: String, vehicleCategory: String, vehicleModel: String, driverMobile: String) {
        val customUrl = getGoogleSheetsUrl(context)
        if (customUrl.isEmpty()) {
            Log.w(TAG, "Driver logout sync skipped: Google Sheets Web App URL is not configured.")
            return
        }

        val formatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm:ss a")
        val currentDateStr = formatter.format(java.util.Date(com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis()))

        val request = GoogleSheetsTripRequest(
            type = "logout",
            timestamp = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis(),
            dateStr = currentDateStr,
            driverName = driverName,
            vehicleNumber = vehicleNumber,
            vehicleCategory = vehicleCategory,
            vehicleModel = vehicleModel,
            driverMobile = driverMobile,
            deviceId = getOrCreateDeviceId(context)
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Syncing driver logout ($driverName) to Sheets...")
                val response = api.syncTripToSheets(customUrl, request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null && (body.status == "success" || body.status == "OK")) {
                        Log.i(TAG, "Successfully synced driver logout ($driverName) to Google Sheets!")
                    } else {
                        Log.e(TAG, "Sheets app returned logout error: ${body?.message ?: "Unknown error"}")
                    }
                } else {
                    Log.e(TAG, "Network response code for logout sync: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during driver logout network sync to Google Sheets: ${e.message}", e)
            }
        }
    }

    /**
     * Fetches user count and profile info from Google Sheets Web App.
     */
    fun fetchUserCountAndProfile(
        context: Context,
        driverName: String = "",
        vehicleNumber: String = "",
        vehicleCategory: String = "",
        vehicleModel: String = "",
        driverMobile: String = "",
        onResult: (userCount: Int, profile: GoogleSheetsResponse?) -> Unit
    ) {
        val customUrl = getGoogleSheetsUrl(context)
        if (customUrl.isEmpty()) {
            onResult(0, null)
            return
        }

        val formatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm:ss a")
        val currentDateStr = formatter.format(java.util.Date(com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis()))

        val request = GoogleSheetsTripRequest(
            type = "get_profile",
            deviceId = getOrCreateDeviceId(context),
            timestamp = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis(),
            dateStr = currentDateStr,
            driverName = driverName,
            vehicleNumber = vehicleNumber,
            vehicleCategory = vehicleCategory,
            vehicleModel = vehicleModel,
            driverMobile = driverMobile
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Fetching user count and profile from Sheets...")
                val response = api.syncTripToSheets(customUrl, request)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val count = body.userCount ?: 0
                        withContext(Dispatchers.Main) {
                            onResult(count, body)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onResult(0, null)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onResult(0, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching user count: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult(0, null)
                }
            }
        }
    }

    /**
     * Grabs all unsynced trips from database and uploads them in the background.
     */
    fun syncAllUnsyncedTrips(context: Context, repository: TripRepository, onComplete: (() -> Unit)? = null) {
        val customUrl = getGoogleSheetsUrl(context)
        if (customUrl.isEmpty()) return

        CoroutineScope(Dispatchers.Main).launch {
            val unsynced = withContext(Dispatchers.IO) {
                repository.getUnsyncedTrips()
            }
            if (unsynced.isEmpty()) {
                onComplete?.invoke()
                return@launch
            }

            Log.i(TAG, "Found ${unsynced.size} unsynced trips. Initiating batch upload...")
            var successCount = 0
            withContext(Dispatchers.IO) {
                for (trip in unsynced) {
                    val success = syncTrip(context, trip, repository)
                    if (success) {
                        successCount++
                    }
                }
            }

            if (successCount > 0) {
                Log.i(TAG, "$successCount historical trips synced to Google Sheets!")
            }
            onComplete?.invoke()
        }
    }
}
