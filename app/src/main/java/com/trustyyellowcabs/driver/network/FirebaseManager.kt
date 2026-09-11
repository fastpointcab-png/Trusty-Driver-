package com.trustyyellowcabs.driver.network

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.trustyyellowcabs.driver.service.TaxiDispatchService
import com.trustyyellowcabs.driver.service.TaxiDispatchServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class FirestoreDriver(
    val driver_id: String = "",
    val driver_name: String = "",
    val mobile_number: String = "",
    val vehicle_number: String = "",
    val vehicle_category: String = "Mini",
    val status: String = "ACTIVE",
    val is_blocked: Boolean = false,
    val is_active: Boolean = true,
    val block_reason: String = "",
    val is_online: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photo_url: String = "",
    val last_seen: Long? = null,
    val expiry_date: String = "2027-12-31",
    val photo_version: Long = 1L,
    val last_sync_time: Long = 0L,
    val device_id: String = "",
    val last_heartbeat: Long? = null
) {
    val isBlocked: Boolean
        get() {
            val s = status.trim().uppercase(Locale.ROOT)
            val blockedStatusList = listOf(
                "BLOCKED", "SUSPENDED", "INACTIVE", "DISABLED",
                "BLOCK", "BANNED", "DEACTIVATED", "REJECTED", "SUSPEND"
            )
            return is_blocked || !is_active || s in blockedStatusList
        }

    val isActivelyOnline: Boolean
        get() {
            if (!is_online) return false
            val hb = last_heartbeat ?: last_seen
            if (hb != null && hb > 0L) {
                val age = System.currentTimeMillis() - hb
                if (age > 90_000L) { // 90 seconds timeout for uninstalled / dead apps
                    return false
                }
            }
            return true
        }

    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "driver_id" to driver_id,
            "status" to status,
            "is_blocked" to is_blocked,
            "is_active" to is_active,
            "is_online" to is_online,
            "expiry_date" to expiry_date,
            "photo_version" to photo_version,
            "isOnline" to FieldValue.delete(),
            "duty_status" to FieldValue.delete(),
            "online_status" to FieldValue.delete(),
            "driver_status" to FieldValue.delete(),
            "last_sync_time" to FieldValue.delete()
        )
        if (last_heartbeat != null && last_heartbeat > 0L) {
            map["last_heartbeat"] = last_heartbeat
        }
        if (last_seen != null && last_seen > 0L) {
            map["last_seen"] = last_seen
        }
        if (driver_name.isNotBlank()) {
            map["driver_name"] = driver_name
        }
        if (mobile_number.isNotBlank() && mobile_number != "N/A") {
            map["mobile_number"] = mobile_number
        }
        if (vehicle_number.isNotBlank()) {
            map["vehicle_number"] = vehicle_number
        }
        if (vehicle_category.isNotBlank()) {
            map["vehicle_category"] = vehicle_category
        }
        if (photo_url.isNotBlank()) {
            map["photo_url"] = photo_url
        }
        if (latitude != null) {
            map["latitude"] = latitude
        }
        if (longitude != null) {
            map["longitude"] = longitude
        }
        if (device_id.isNotBlank()) {
            map["device_id"] = device_id
        }
        return map
    }
}

data class FirestoreTrip(
    val doc_id: String = "",
    val trip_id: String = "",
    val customer_name: String = "Customer",
    val customer_phone: String = "",
    val pickup_location: String = "",
    val drop_location: String = "",
    val pickup_lat: Double? = null,
    val pickup_lng: Double? = null,
    val drop_lat: Double? = null,
    val drop_lng: Double? = null,
    val status: String = "OPEN", // OPEN, ACCEPTED, IN_PROGRESS, COMPLETED, CANCELLED
    val driver_id: String? = null,
    val driver_name: String? = null,
    val driver_phone: String? = null,
    val vehicle_number: String? = null,
    val vehicle_category: String? = null,
    val estimated_fare: Double = 0.0,
    val final_fare: Double? = null,
    val notes: String = "",
    val created_at: Long = 0L,
    val updated_at: Long = 0L,
    val accepted_at: Long? = null,
    val started_at: Long? = null,
    val completed_at: Long? = null,
    val trip_type: String = "REGULAR", // REGULAR or PACKAGE
    val base_fare: Double? = null,
    val kms_fare: Double? = null,
    val hour_fare: Double? = null,
    val is_package: Boolean = false,
    val dispatch_type: String = "BROADCAST", // BROADCAST or RADIUS
    val radius_kms: Double = 10.0,
    val otp: String? = null
) {
    fun toMap(): Map<String, Any?> {
        return mapOf(
            "trip_id" to trip_id,
            "customer_name" to customer_name,
            "customer_phone" to customer_phone,
            "pickup_location" to pickup_location,
            "drop_location" to drop_location,
            "pickup_lat" to pickup_lat,
            "pickup_lng" to pickup_lng,
            "drop_lat" to drop_lat,
            "drop_lng" to drop_lng,
            "status" to status,
            "driver_id" to driver_id,
            "driver_name" to driver_name,
            "driver_phone" to driver_phone,
            "vehicle_number" to vehicle_number,
            "vehicle_category" to vehicle_category,
            "estimated_fare" to estimated_fare,
            "final_fare" to final_fare,
            "notes" to notes,
            "created_at" to created_at,
            "updated_at" to updated_at,
            "accepted_at" to accepted_at,
            "started_at" to started_at,
            "completed_at" to completed_at,
            "trip_type" to if (is_package || (hour_fare != null && hour_fare > 0.0)) "PACKAGE" else "REGULAR",
            "base_fare" to base_fare,
            "kms_fare" to kms_fare,
            "hour_fare" to hour_fare,
            "is_package" to (is_package || (hour_fare != null && hour_fare > 0.0)),
            "dispatch_type" to dispatch_type,
            "radius_kms" to radius_kms,
            "otp" to otp
        )
    }
}

data class OfficePaymentInfo(
    val amount: Double = 0.0,
    val paymentStatus: String = "CLEARED", // DUE, PENDING, CLEARED, SUBMITTED
    val paymentNote: String = "Office Service Fee",
    val upiId: String = "",
    val payeeName: String = "",
    val officePhone: String = "",
    val lastUpdated: Long = 0L,
    val lastPaidUtr: String = "",
    val lastPaidAmount: Double = 0.0,
    val lastPaidTimestamp: Long = 0L
)

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private const val PREFS_NAME = "TrustyFirebasePrefs"
    private const val KEY_PROJECT_ID = "firebase_project_id"
    private const val KEY_APP_ID = "firebase_app_id"
    private const val KEY_API_KEY = "firebase_api_key"
    private const val KEY_STORAGE_BUCKET = "firebase_storage_bucket"

    // Default configuration for Trusty Yellow Cab (Secured and masked against decompilation)
    val DEFAULT_PROJECT_ID: String get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureFirebaseProjectId()
    val DEFAULT_APP_ID: String get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureFirebaseAppId()
    val DEFAULT_API_KEY: String get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureFirebaseApiKey()
    val DEFAULT_STORAGE_BUCKET: String get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureFirebaseStorageBucket()
    val DEFAULT_GCM_SENDER_ID: String get() = com.trustyyellowcabs.driver.security.AppSecurityShield.getSecureFirebaseProjectNumber()

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tripFlow = MutableSharedFlow<FirestoreTrip>(extraBufferCapacity = 64)
    val realTimeTripFlow = tripFlow.asSharedFlow()

    private val _isAdminOnline = MutableStateFlow(true)
    val isAdminOnline = _isAdminOnline.asStateFlow()

    private val _isBackendSyncEnabled = MutableStateFlow(true)
    val isBackendSyncEnabled = _isBackendSyncEnabled.asStateFlow()

    private val _backendStatusMessage = MutableStateFlow("Admin Online • Live Sync Active")
    val backendStatusMessage = _backendStatusMessage.asStateFlow()

    private val _globalOfficeUpiId = MutableStateFlow("")
    val globalOfficeUpiId = _globalOfficeUpiId.asStateFlow()

    private val _officePaymentInfo = MutableStateFlow(OfficePaymentInfo())
    val officePaymentInfo: StateFlow<OfficePaymentInfo> = _officePaymentInfo.asStateFlow()

    private var tripsListenerRegistration: ListenerRegistration? = null
    private var systemControlListeners = mutableListOf<ListenerRegistration>()
    private var isInitialized = false
    private var cachedAppContext: Context? = null

    fun loadCachedOfficePaymentInfo(context: Context) {
        try {
            val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
            val amt = prefs.getFloat("cached_office_payment_amount", 0.0f).toDouble()
            val status = prefs.getString("cached_office_payment_status", "CLEARED") ?: "CLEARED"
            val note = prefs.getString("cached_office_payment_note", "Office Service Fee") ?: "Office Service Fee"
            val upi = prefs.getString("cached_office_upi", "") ?: ""
            val payee = prefs.getString("cached_office_payee", "") ?: ""
            val phone = prefs.getString("cached_office_phone", "") ?: ""
            val lastUpdated = prefs.getLong("cached_office_last_updated", 0L)
            val utr = prefs.getString("cached_office_paid_utr", "") ?: ""
            val paidAmt = prefs.getFloat("cached_office_paid_amount", 0.0f).toDouble()
            val paidTs = prefs.getLong("cached_office_paid_ts", 0L)

            // If legacy hardcoded upi was stored, reset it to blank so backend control is absolute
            val cleanUpi = if (upi == "9600403032@okaxis") "" else upi.trim()

            _officePaymentInfo.value = OfficePaymentInfo(
                amount = amt,
                paymentStatus = status,
                paymentNote = note,
                upiId = cleanUpi,
                payeeName = payee,
                officePhone = phone,
                lastUpdated = lastUpdated,
                lastPaidUtr = utr,
                lastPaidAmount = paidAmt,
                lastPaidTimestamp = paidTs
            )
            Log.d(TAG, "Loaded cached office payment info locally: amount=$amt, status=$status, upi=$cleanUpi")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading cached office payment info: ${e.message}")
        }
    }

    private fun persistOfficePaymentInfoLocally(context: Context, info: OfficePaymentInfo) {
        try {
            val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("cached_office_payment_amount", info.amount.toFloat())
                .putString("cached_office_payment_status", info.paymentStatus)
                .putString("cached_office_payment_note", info.paymentNote)
                .putString("cached_office_upi", info.upiId)
                .putString("cached_office_payee", info.payeeName)
                .putString("cached_office_phone", info.officePhone)
                .putLong("cached_office_last_updated", info.lastUpdated)
                .putString("cached_office_paid_utr", info.lastPaidUtr)
                .putFloat("cached_office_paid_amount", info.lastPaidAmount.toFloat())
                .putLong("cached_office_paid_ts", info.lastPaidTimestamp)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Error saving office payment info locally: ${e.message}")
        }
    }

    fun getDriverId(context: Context): String {
        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        return prefs.getString("unique_driver_id", "") ?: ""
    }

    fun saveDriverId(context: Context, driverId: String) {
        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("unique_driver_id", driverId.trim()).apply()
    }

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("unique_device_id", "") ?: ""
        if (deviceId.isBlank()) {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            deviceId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                UUID.randomUUID().toString()
            }
            prefs.edit().putString("unique_device_id", deviceId).apply()
        }
        return deviceId
    }

    fun getProjectId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PROJECT_ID, DEFAULT_PROJECT_ID) ?: DEFAULT_PROJECT_ID
    }

    fun saveFirebaseConfig(context: Context, projectId: String, appId: String, apiKey: String, storageBucket: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROJECT_ID, projectId.trim())
            .putString(KEY_APP_ID, appId.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_STORAGE_BUCKET, storageBucket.trim())
            .apply()
    }

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val projectId = prefs.getString(KEY_PROJECT_ID, DEFAULT_PROJECT_ID) ?: DEFAULT_PROJECT_ID
                val appId = prefs.getString(KEY_APP_ID, DEFAULT_APP_ID) ?: DEFAULT_APP_ID
                val apiKey = prefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
                val storageBucket = prefs.getString(KEY_STORAGE_BUCKET, DEFAULT_STORAGE_BUCKET) ?: DEFAULT_STORAGE_BUCKET

                val options = FirebaseOptions.Builder()
                    .setProjectId(projectId)
                    .setApplicationId(appId)
                    .setApiKey(apiKey)
                    .setStorageBucket(storageBucket)
                    .setGcmSenderId(DEFAULT_GCM_SENDER_ID)
                    .build()

                FirebaseApp.initializeApp(context.applicationContext, options)
                Log.i(TAG, "Firebase initialized with project: $projectId")
            } else {
                Log.i(TAG, "Firebase already initialized by system")
            }

            // Enable offline persistence settings for Firestore
            try {
                val firestore = FirebaseFirestore.getInstance()
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .build()
                firestore.firestoreSettings = settings
            } catch (e: Exception) {
                Log.w(TAG, "Firestore settings adjustment notice: ${e.message}")
            }

            isInitialized = true

            // Automatically start listening to Backend Admin / System Status
            startSystemControlListener(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firebase: ${e.message}", e)
        }
    }

    private fun getFirestore(context: Context): FirebaseFirestore {
        cachedAppContext = context.applicationContext
        ensureInitialized(context)
        return FirebaseFirestore.getInstance()
    }

    private fun getStorage(context: Context): FirebaseStorage {
        ensureInitialized(context)
        return FirebaseStorage.getInstance()
    }

    private fun parseFlexibleBoolean(value: Any?): Boolean? {
        if (value == null) return null
        if (value is Boolean) return value
        if (value is Number) return value.toInt() != 0
        if (value is String) {
            val s = value.trim().lowercase(Locale.ROOT)
            if (s == "true" || s == "1" || s == "yes" || s == "on" || s == "online" || s == "enabled" || s == "active") return true
            if (s == "false" || s == "0" || s == "no" || s == "off" || s == "offline" || s == "disabled" || s == "cutoff" || s == "cut_off") return false
        }
        return null
    }

    private fun evaluateCutoff(data: Map<String, Any?>): Boolean {
        // Look for boolean or string flags in case-insensitive and flexible key names
        val normalized = data.mapKeys { it.key.lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_") }

        val adminOnline = parseFlexibleBoolean(normalized["admin_online"])
            ?: parseFlexibleBoolean(normalized["adminonline"])
            ?: parseFlexibleBoolean(normalized["sync_enabled"])
            ?: parseFlexibleBoolean(normalized["syncenabled"])
            ?: parseFlexibleBoolean(normalized["is_online"])
            ?: parseFlexibleBoolean(normalized["isonline"])
            ?: parseFlexibleBoolean(normalized["online"])
            ?: parseFlexibleBoolean(normalized["enabled"])
            ?: parseFlexibleBoolean(normalized["active"])

        val statusStr = (normalized["status"] as? String 
            ?: normalized["state"] as? String 
            ?: normalized["admin_status"] as? String 
            ?: normalized["mode"] as? String
            ?: "").trim().uppercase(Locale.ROOT)

        val isStatusCutoff = statusStr in listOf("OFFLINE", "OFF", "CUTOFF", "CUT_OFF", "DISABLED", "INACTIVE", "STOPPED", "FALSE", "0")
        val isStatusOnline = statusStr in listOf("ONLINE", "ON", "ACTIVE", "ENABLED", "RUNNING", "TRUE", "1")

        if (adminOnline == false || isStatusCutoff) {
            return true
        }
        if (adminOnline == true || isStatusOnline) {
            return false
        }
        return false
    }

    private val docCutoffMap = mutableMapOf<String, Boolean>()

    fun isSyncCutoff(context: Context): Boolean {
        if (!_isBackendSyncEnabled.value) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean("is_backend_sync_enabled", true)
    }

    private fun persistBackendSyncState(context: Context, enabled: Boolean) {
        _isBackendSyncEnabled.value = enabled
        _isAdminOnline.value = enabled
        _backendStatusMessage.value = if (enabled) "Admin Online • Live Sync Active" else "Backend Cutoff • Offline Standalone Active"
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_backend_sync_enabled", enabled).apply()

        if (!enabled) {
            // Cutoff activated: Immediately detach real-time listener and clear in-memory trips/alerts
            stopListeningToRealtime()
            TaxiDispatchServiceState.setOpenTrips(emptyList())
            TaxiDispatchServiceState.triggerNewTripAlert(null)
            TaxiDispatchService.activeInstance?.stopLoudAlert()
        }
    }

    /**
     * Listens to Backend Admin online/offline control documents in Firestore.
     * Listens to all standard collections and documents.
     */
    fun startSystemControlListener(context: Context) {
        // Initialize from cached SharedPreferences first
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val initialEnabled = prefs.getBoolean("is_backend_sync_enabled", true)
        _isBackendSyncEnabled.value = initialEnabled
        _isAdminOnline.value = initialEnabled
        _backendStatusMessage.value = if (initialEnabled) "Admin Online • Live Sync Active" else "Backend Cutoff • Offline Standalone Active"

        if (systemControlListeners.isNotEmpty()) return
        try {
            val db = FirebaseFirestore.getInstance()
            val paths = listOf(
                Pair("system_settings", "dispatch_control"),
                Pair("admin_settings", "dispatch_control"),
                Pair("settings", "dispatch_control"),
                Pair("system_settings", "admin_control"),
                Pair("dispatch_control", "status"),
                Pair("system_settings", "settings"),
                Pair("admin_settings", "office_payment"),
                Pair("system_settings", "payment_settings"),
                Pair("settings", "office_payment")
            )

            for ((coll, docName) in paths) {
                val key = "$coll/$docName"
                val listener = db.collection(coll).document(docName)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "System control listener note ($key): ${error.message}")
                            return@addSnapshotListener
                        }

                        if (snapshot != null && snapshot.exists()) {
                            val data = snapshot.data ?: emptyMap()

                            // Check for global office UPI ID or global fee in admin settings
                            val hasGlobalUpi = data.containsKey("office_upi_id") ||
                                    data.containsKey("upi_id") ||
                                    data.containsKey("payment_upi") ||
                                    data.containsKey("vpa")
                            val confUpi = ((data["office_upi_id"] as? String)
                                ?: (data["upi_id"] as? String)
                                ?: (data["payment_upi"] as? String)
                                ?: (data["vpa"] as? String))?.trim()
                            val confPhone = ((data["office_phone"] as? String)
                                ?: (data["billing_phone"] as? String)
                                ?: (data["enquiry_phone"] as? String)
                                ?: (data["phone"] as? String))?.trim()
                            if (hasGlobalUpi) {
                                val resolvedUpi = confUpi.orEmpty()
                                _globalOfficeUpiId.value = resolvedUpi
                                _officePaymentInfo.value = _officePaymentInfo.value.copy(upiId = resolvedUpi)
                                persistOfficePaymentInfoLocally(context, _officePaymentInfo.value)
                            }
                            if (confPhone != null) {
                                _officePaymentInfo.value = _officePaymentInfo.value.copy(officePhone = confPhone)
                                persistOfficePaymentInfoLocally(context, _officePaymentInfo.value)
                            }
                            val docIsCutoff = evaluateCutoff(data)
                            docCutoffMap[key] = docIsCutoff
                            Log.i(TAG, "System control event from $key: isCutoff=$docIsCutoff (data=$data)")

                            // If ANY monitored control doc says CUTOFF, cutoff is active!
                            val overallCutoff = docCutoffMap.values.any { it }
                            if (overallCutoff) {
                                persistBackendSyncState(context, false)
                                stopListeningToRealtime()
                                Log.i(TAG, "🚨 ADMIN BACKEND CUTOFF ACTIVATED. All driver data transfer to backend is BLOCKED.")
                            } else {
                                val wasCutoff = !_isBackendSyncEnabled.value
                                persistBackendSyncState(context, true)
                                Log.i(TAG, "🟢 ADMIN IS ONLINE. Resuming live synchronization & pushing app data to backend.")
                                startListeningToRealtime(context)
                                managerScope.launch {
                                    syncAllAppDataToBackend(context)
                                }
                            }
                        }
                    }
                systemControlListeners.add(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start system control listener: ${e.message}")
        }
    }

    /**
     * Immediately synchronizes all local app data (driver profile, current presence, device binding) to the backend.
     */
    suspend fun syncAllAppDataToBackend(context: Context): Boolean {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Sync cutoff: Cannot push app data to backend.")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val driverId = getDriverId(context)
                if (driverId.isBlank()) return@withContext false
                val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                val name = prefs.getString("driver_name", "") ?: ""
                val mobile = prefs.getString("driver_mobile", "") ?: ""
                val vNum = prefs.getString("vehicle_number", "") ?: ""
                val vCat = prefs.getString("vehicle_category", "Mini") ?: "Mini"
                val photoUrl = prefs.getString("driver_photo_url", "")?.ifEmpty { prefs.getString("driver_selfie_path", "") } ?: ""
                val deviceId = getOrCreateDeviceId(context)
                val now = System.currentTimeMillis()

                val lastLoc = TaxiDispatchService.activeInstance?.lastKnownLocation
                val isServiceOnline = com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.isOnline.value

                val driverProfile = FirestoreDriver(
                    driver_id = driverId,
                    driver_name = name,
                    mobile_number = mobile,
                    vehicle_number = vNum,
                    vehicle_category = vCat,
                    status = "ACTIVE",
                    is_online = isServiceOnline,
                    latitude = lastLoc?.latitude,
                    longitude = lastLoc?.longitude,
                    photo_url = photoUrl,
                    device_id = deviceId
                )
                upsertDriver(context, driverProfile)
                Log.i(TAG, "Full App Data Sync to backend completed for Driver $driverId")

                // Trigger immediate trips refresh
                TaxiDispatchService.activeInstance?.forceRefreshTrips()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncAllAppDataToBackend: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Allows toggling Admin Backend Sync locally or updating the backend document directly.
     */
    suspend fun setAdminBackendSync(context: Context, online: Boolean): Boolean {
        _isAdminOnline.value = online
        _isBackendSyncEnabled.value = online
        _backendStatusMessage.value = if (online) "Admin Online • Live Sync Active" else "Backend Cutoff • Offline Standalone Active"
        if (online) {
            startListeningToRealtime(context)
            managerScope.launch {
                syncAllAppDataToBackend(context)
            }
        } else {
            stopListeningToRealtime()
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                db.collection("system_settings").document("dispatch_control").set(
                    mapOf(
                        "admin_online" to online,
                        "sync_enabled" to online,
                        "status" to if (online) "ONLINE" else "OFFLINE",
                        "updated_at" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
                Log.d(TAG, "Admin backend control updated to online=$online")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating backend dispatch_control document: ${e.message}")
                true // Local state updated anyway
            }
        }
    }

    // ==========================================
    // DRIVER PROFILE OPERATIONS
    // ==========================================

    suspend fun upsertDriver(context: Context, driver: FirestoreDriver): Boolean {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync is currently CUTOFF. Skipping driver profile upload to backend.")
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val docRef = db.collection("drivers").document(driver.driver_id)
                docRef.set(driver.toMap(), SetOptions.merge()).await()
                Log.d(TAG, "Driver profile successfully synced to Firestore: ${driver.driver_id}")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing driver profile: ${e.message}")
                false
            }
        }
    }

    suspend fun fetchDriver(context: Context, driverId: String, forceRemote: Boolean = false): FirestoreDriver? {
        val cleanId = driverId.trim()
        if (cleanId.isEmpty()) return null

        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        val savedId = prefs.getString("unique_driver_id", "") ?: ""
        val savedName = prefs.getString("driver_name", "") ?: ""
        val savedVNum = prefs.getString("vehicle_number", "") ?: ""
        val savedCat = prefs.getString("vehicle_category", "Mini") ?: "Mini"
        val savedMobile = prefs.getString("driver_mobile", "") ?: ""
        val savedPhoto = prefs.getString("driver_photo_url", "") ?: ""

        val localFallbackDriver = if (savedId.equals(cleanId, ignoreCase = true) && savedName.isNotBlank() && savedVNum.isNotBlank()) {
            FirestoreDriver(
                driver_id = savedId,
                driver_name = savedName,
                vehicle_number = savedVNum,
                vehicle_category = savedCat,
                mobile_number = savedMobile,
                photo_url = savedPhoto,
                status = "ACTIVE",
                is_online = com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.isOnline.value
            )
        } else null

        // If not forcing remote and we already have a full valid local driver profile cached, use it immediately!
        if (!forceRemote && localFallbackDriver != null) {
            Log.d(TAG, "Driver profile loaded locally from SharedPreferences (0 Firestore reads).")
            return localFallbackDriver
        }

        // Driver login or initial sync queries Firestore backend directly
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                var foundDriver: FirestoreDriver? = null

                // 1. Try direct document ID matching
                val snapshot = db.collection("drivers").document(cleanId).get().await()
                if (snapshot.exists()) {
                    foundDriver = mapDocumentToDriver(snapshot.id, snapshot.data ?: emptyMap())
                }

                // 2. Try uppercase document ID
                if (foundDriver == null) {
                    val upperSnapshot = db.collection("drivers").document(cleanId.uppercase(Locale.ROOT)).get().await()
                    if (upperSnapshot.exists()) {
                        foundDriver = mapDocumentToDriver(upperSnapshot.id, upperSnapshot.data ?: emptyMap())
                    }
                }

                // 3. Try whereEqualTo on driver_id field
                if (foundDriver == null) {
                    val querySnapshot = db.collection("drivers")
                        .whereEqualTo("driver_id", cleanId)
                        .limit(1)
                        .get()
                        .await()
                    if (!querySnapshot.isEmpty) {
                        val doc = querySnapshot.documents[0]
                        foundDriver = mapDocumentToDriver(doc.id, doc.data ?: emptyMap())
                    }
                }

                // 4. Try uppercase whereEqualTo on driver_id field
                if (foundDriver == null) {
                    val queryUpperSnapshot = db.collection("drivers")
                        .whereEqualTo("driver_id", cleanId.uppercase(Locale.ROOT))
                        .limit(1)
                        .get()
                        .await()
                    if (!queryUpperSnapshot.isEmpty) {
                        val doc = queryUpperSnapshot.documents[0]
                        foundDriver = mapDocumentToDriver(doc.id, doc.data ?: emptyMap())
                    }
                }

                if (foundDriver != null) {
                    // Cache fetched profile into SharedPreferences locally
                    prefs.edit()
                        .putString("unique_driver_id", foundDriver.driver_id)
                        .putString("driver_name", foundDriver.driver_name)
                        .putString("vehicle_number", foundDriver.vehicle_number)
                        .putString("vehicle_category", foundDriver.vehicle_category)
                        .putString("driver_mobile", foundDriver.mobile_number)
                        .putString("driver_photo_url", foundDriver.photo_url)
                        .apply()
                    foundDriver
                } else {
                    localFallbackDriver
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching driver online, falling back to local cached profile: ${e.message}")
                localFallbackDriver
            }
        }
    }

    suspend fun setDriverOnlineStatus(
        context: Context,
        isOnline: Boolean,
        lat: Double? = null,
        lng: Double? = null,
        targetDriverId: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                cachedAppContext = context.applicationContext
                val driverId = (targetDriverId ?: getDriverId(context)).trim()
                if (driverId.isBlank()) return@withContext false
                val db = getFirestore(context)
                val now = System.currentTimeMillis()
                val updates = mutableMapOf<String, Any?>(
                    "is_online" to isOnline,
                    "last_heartbeat" to if (isOnline) now else 0L,
                    "last_seen" to now,
                    "isOnline" to FieldValue.delete(),
                    "duty_status" to FieldValue.delete(),
                    "online_status" to FieldValue.delete(),
                    "driver_status" to FieldValue.delete(),
                    "last_sync_time" to FieldValue.delete()
                )
                if (lat != null && lng != null) {
                    updates["latitude"] = lat
                    updates["longitude"] = lng
                }
                db.collection("drivers").document(driverId).set(updates, SetOptions.merge()).await()

                val upperId = driverId.uppercase(Locale.ROOT)
                if (upperId != driverId) {
                    try {
                        db.collection("drivers").document(upperId).set(updates, SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "Driver $driverId status successfully updated to backend: is_online=$isOnline")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting online status: ${e.message}")
                false
            }
        }
    }

    private var lastUploadedLat: Double? = null
    private var lastUploadedLng: Double? = null
    private var lastLocationUploadTimestamp: Long = 0L

    /**
     * Safely updates only GPS coordinates, presence, and last seen without overwriting profile details.
     * ONLY updates if the driver is currently ONLINE. Offline drivers do NOT update location.
     * Throttles repeated updates to at least 25-30 seconds apart unless substantial position change.
     */
    suspend fun updateDriverLocationAndPresence(
        context: Context,
        lat: Double?,
        lng: Double?,
        isOnline: Boolean = true
    ): Boolean {
        // Only update online driver location. Offline drivers do NOT update location.
        if (!isOnline) {
            return false
        }
        if (!_isBackendSyncEnabled.value) return true

        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastLocationUploadTimestamp

        // Throttle: don't update more frequently than 20 seconds unless moved more than 15 meters
        if (lastLocationUploadTimestamp > 0L && timeSinceLast < 20000L && lat != null && lng != null) {
            val prevLat = lastUploadedLat
            val prevLng = lastUploadedLng
            if (prevLat != null && prevLng != null) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(prevLat, prevLng, lat, lng, results)
                if (results[0] < 15.0f) {
                    return true // Position practically unchanged within short interval, skip repeat write
                }
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                cachedAppContext = context.applicationContext
                val driverId = getDriverId(context).trim()
                if (driverId.isBlank()) return@withContext false
                val db = getFirestore(context)
                val updates = mutableMapOf<String, Any?>(
                    "is_online" to true,
                    "last_heartbeat" to now,
                    "last_seen" to now,
                    "device_id" to getOrCreateDeviceId(context)
                )
                if (lat != null && lng != null) {
                    updates["latitude"] = lat
                    updates["longitude"] = lng
                    lastUploadedLat = lat
                    lastUploadedLng = lng
                }
                db.collection("drivers").document(driverId).set(updates, SetOptions.merge()).await()
                val upperId = driverId.uppercase(Locale.ROOT)
                if (upperId != driverId) {
                    try {
                        db.collection("drivers").document(upperId).set(updates, SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }
                lastLocationUploadTimestamp = now
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating driver location: ${e.message}")
                false
            }
        }
    }

    /**
     * Periodically sweeps Firestore drivers to automatically mark uninstalled or abruptly terminated drivers as OFFLINE.
     * If a driver is flagged online but hasn't sent a heartbeat in >75 seconds, their status is self-healed to offline.
     */
    suspend fun cleanStaleOfflineDrivers(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                cachedAppContext = context.applicationContext
                val db = getFirestore(context)
                val cutoff = System.currentTimeMillis() - 75000L // 75 seconds threshold
                val snapshot = db.collection("drivers")
                    .whereEqualTo("is_online", true)
                    .get()
                    .await()

                for (doc in snapshot.documents) {
                    val data = doc.data ?: continue
                    val rawHb = (data["last_heartbeat"] as? Number)?.toLong()
                        ?: ((data["last_seen"] as? Number)?.toLong() ?: 0L)
                    if (rawHb in 1 until cutoff) {
                        val docId = doc.id
                        Log.i(TAG, "Self-healing uninstalled/dead driver to OFFLINE: $docId")
                        val updates = mapOf(
                            "is_online" to false,
                            "last_heartbeat" to 0L
                        )
                        doc.reference.set(updates, SetOptions.merge())
                        val upper = docId.uppercase(Locale.ROOT)
                        if (upper != docId) {
                            try {
                                db.collection("drivers").document(upper).set(updates, SetOptions.merge())
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanStaleOfflineDrivers notice: ${e.message}")
            }
        }
    }

    /**
     * Updates the driver's FCM registration token in Firestore so backend can send targeted push notifications.
     */
    suspend fun updateDriverFcmToken(context: Context, fcmToken: String): Boolean {
        if (fcmToken.isBlank()) return false
        val driverId = getDriverId(context)
        if (driverId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                db.collection("drivers").document(driverId).set(
                    mapOf(
                        "fcm_token" to fcmToken
                    ),
                    SetOptions.merge()
                ).await()
                Log.d(TAG, "Driver $driverId FCM token updated in Firestore")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating FCM token in Firestore: ${e.message}")
                false
            }
        }
    }

    /**
     * Binds the current device to the driver's Firestore record upon successful login.
     * Enforces single-device policy: only binds if device_id in Firestore is empty or matches this device.
     */
    suspend fun bindDriverDeviceSession(context: Context, driverId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val currentDeviceId = getOrCreateDeviceId(context)
                val db = getFirestore(context)
                val docRef = db.collection("drivers").document(driverId)
                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    val remoteDeviceId = snapshot.getString("device_id") ?: ""
                    if (remoteDeviceId.isNotBlank() && remoteDeviceId != currentDeviceId) {
                        Log.w(TAG, "Driver $driverId is already bound to another device ($remoteDeviceId vs $currentDeviceId)")
                        return@withContext false
                    }
                }
                val updates = mapOf(
                    "device_id" to currentDeviceId,
                    "isOnline" to FieldValue.delete(),
                    "duty_status" to FieldValue.delete(),
                    "online_status" to FieldValue.delete(),
                    "driver_status" to FieldValue.delete(),
                    "last_seen" to FieldValue.delete(),
                    "last_sync_time" to FieldValue.delete()
                )
                docRef.set(updates, SetOptions.merge()).await()
                val upperId = driverId.uppercase(Locale.ROOT)
                if (upperId != driverId) {
                    try {
                        db.collection("drivers").document(upperId).set(updates, SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "Driver $driverId bound to device $currentDeviceId")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed binding device session to driver $driverId: ${e.message}")
                false
            }
        }
    }

    /**
     * Unbinds the device ID from Firestore when the driver logs out, freeing the ID for other devices,
     * and sets driver offline in the backend.
     */
    suspend fun clearDriverDeviceSession(context: Context, driverId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                cachedAppContext = context.applicationContext
                if (driverId.isBlank()) return@withContext false
                val db = getFirestore(context)
                val now = System.currentTimeMillis()
                val updates = mapOf(
                    "device_id" to "",
                    "is_online" to false,
                    "last_heartbeat" to 0L,
                    "last_seen" to now,
                    "isOnline" to FieldValue.delete(),
                    "duty_status" to FieldValue.delete(),
                    "online_status" to FieldValue.delete(),
                    "driver_status" to FieldValue.delete(),
                    "last_sync_time" to FieldValue.delete()
                )
                db.collection("drivers").document(driverId).set(updates, SetOptions.merge()).await()
                val upperId = driverId.uppercase(Locale.ROOT)
                if (upperId != driverId) {
                    try {
                        db.collection("drivers").document(upperId).set(updates, SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }
                Log.d(TAG, "Driver $driverId device session cleared & set offline on logout")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing device session for $driverId: ${e.message}")
                false
            }
        }
    }

    private var driverSessionListenerRegistration: ListenerRegistration? = null

    /**
     * Listens in real-time to the logged-in driver's document.
     * If the driver is blocked/suspended/logged out by Admin or logged in on another device, triggers onSessionTerminated callback.
     */
    fun startListeningToDriverSession(context: Context, driverId: String, onSessionTerminated: (reason: String) -> Unit) {
        ensureInitialized(context)
        if (driverId.isBlank()) return
        
        stopListeningToDriverSession()

        try {
            val db = getFirestore(context)
            val currentDeviceId = getOrCreateDeviceId(context)
            Log.d(TAG, "Starting driver session listener for driver $driverId with local deviceId: $currentDeviceId")

            driverSessionListenerRegistration = db.collection("drivers").document(driverId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Driver session listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val data = snapshot.data ?: emptyMap()
                        val driver = mapDocumentToDriver(snapshot.id, data)

                        // 1. Check if Driver is Blocked / Suspended / Logged Out by Admin
                        val isLoggedOutByAdmin = data["is_logged_out"] == true ||
                                data["logout_requested"] == true ||
                                (data["status"] as? String)?.uppercase(Locale.ROOT) in listOf("LOGOUT", "LOGGED_OUT")

                        if (driver.isBlocked) {
                            Log.w(TAG, "Driver $driverId has been blocked/suspended by admin! Status: ${driver.status}")
                            val reason = if (driver.block_reason.isNotBlank()) {
                                "Account Suspended: ${driver.block_reason}"
                            } else {
                                "Your account has been suspended/blocked by Admin. Please contact dispatch office."
                            }
                            onSessionTerminated(reason)
                            return@addSnapshotListener
                        } else if (isLoggedOutByAdmin) {
                            Log.w(TAG, "Driver $driverId was logged out by Admin!")
                            onSessionTerminated("Your account has been logged out by the administrator.")
                            return@addSnapshotListener
                        }

                        // 2. Single-Device Enforcement Check
                        val remoteDeviceId = snapshot.getString("device_id") ?: ""
                        if (remoteDeviceId.isNotBlank() && remoteDeviceId != currentDeviceId) {
                            Log.w(TAG, "Session invalidated: logged in on another device ($remoteDeviceId vs $currentDeviceId)")
                            onSessionTerminated("Your account has been logged in on another device. You have been logged out.")
                            return@addSnapshotListener
                        }

                        // 3. Real-time Office Payment / Fee typed by Admin in Admin Panel
                        try {
                            val parsedAmount = parseFlexibleDouble(data["payment_amount"])
                                ?: parseFlexibleDouble(data["payment"])
                                ?: parseFlexibleDouble(data["office_fee"])
                                ?: parseFlexibleDouble(data["office_payment"])
                                ?: parseFlexibleDouble(data["fee_amount"])
                                ?: parseFlexibleDouble(data["due_amount"])
                                ?: parseFlexibleDouble(data["pending_amount"])
                                ?: parseFlexibleDouble(data["fee"])
                                ?: parseFlexibleDouble(data["amount"])
                                ?: parseFlexibleDouble(data["dues"])
                                ?: 0.0

                            val parsedStatus = (data["payment_status"] as? String)
                                ?: (data["fee_status"] as? String)
                                ?: (data["status_payment"] as? String)
                                ?: if (parsedAmount > 0.0) "DUE" else "CLEARED"

                            val parsedNote = (data["payment_note"] as? String)
                                ?: (data["fee_note"] as? String)
                                ?: (data["note"] as? String)
                                ?: (data["purpose"] as? String)
                                ?: "Office Service Fee"

                            val hasDriverUpi = data.containsKey("upi_id") ||
                                    data.containsKey("office_upi_id") ||
                                    data.containsKey("payment_upi") ||
                                    data.containsKey("vpa")
                            val parsedUpi = ((data["upi_id"] as? String)
                                ?: (data["office_upi_id"] as? String)
                                ?: (data["payment_upi"] as? String)
                                ?: (data["vpa"] as? String))?.trim()

                            val effectiveUpi = if (hasDriverUpi) {
                                parsedUpi.orEmpty()
                            } else {
                                _globalOfficeUpiId.value.trim()
                            }

                            val parsedPayee = (data["payee_name"] as? String)
                                ?: (data["office_name"] as? String)
                                ?: (data["payee"] as? String)
                                ?: ""

                            val parsedPhone = (data["office_phone"] as? String)
                                ?: (data["billing_phone"] as? String)
                                ?: (data["phone"] as? String)
                                ?: ""

                            val parsedPaidUtr = (data["driver_payment_utr"] as? String) ?: ""
                            val parsedPaidAmount = parseFlexibleDouble(data["driver_paid_amount"]) ?: 0.0
                            val parsedPaidTimestamp = (data["driver_payment_timestamp"] as? Long)
                                ?: (data["driver_payment_timestamp"] as? Number)?.toLong() ?: 0L

                            val updatedInfo = OfficePaymentInfo(
                                amount = parsedAmount,
                                paymentStatus = parsedStatus,
                                paymentNote = parsedNote,
                                upiId = effectiveUpi,
                                payeeName = parsedPayee.trim(),
                                officePhone = if (parsedPhone.isNotBlank()) parsedPhone.trim() else _officePaymentInfo.value.officePhone,
                                lastUpdated = System.currentTimeMillis(),
                                lastPaidUtr = parsedPaidUtr,
                                lastPaidAmount = parsedPaidAmount,
                                lastPaidTimestamp = parsedPaidTimestamp
                            )
                            val current = _officePaymentInfo.value
                            val hasChanged = current.amount != updatedInfo.amount ||
                                    current.paymentStatus != updatedInfo.paymentStatus ||
                                    current.paymentNote != updatedInfo.paymentNote ||
                                    current.upiId != updatedInfo.upiId ||
                                    current.lastPaidUtr != updatedInfo.lastPaidUtr
                            if (hasChanged) {
                                _officePaymentInfo.value = updatedInfo
                                persistOfficePaymentInfoLocally(context, updatedInfo)
                                Log.d(TAG, "Office payment info updated & saved locally for driver $driverId: amount=$parsedAmount, status=$parsedStatus, upi=${updatedInfo.upiId}")
                            }
                        } catch (pe: Exception) {
                            Log.w(TAG, "Error parsing payment info for driver $driverId: ${pe.message}")
                        }
                    } else if (snapshot != null && !snapshot.exists()) {
                        Log.w(TAG, "Driver $driverId document does not exist or was removed by Admin.")
                        onSessionTerminated("Your driver profile was not found or was removed by Admin.")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start driver session listener: ${e.message}", e)
        }
    }

    fun stopListeningToDriverSession() {
        driverSessionListenerRegistration?.remove()
        driverSessionListenerRegistration = null
    }

    /**
     * Records a payment submission (e.g. UTR / Ref ID) from the driver to the admin panel.
     */
    suspend fun recordDriverPaymentSubmission(
        context: Context,
        driverId: String,
        driverName: String,
        vehicleNumber: String,
        amount: Double,
        utr: String,
        note: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (driverId.isBlank()) return@withContext false
        try {
            val db = getFirestore(context)
            val now = System.currentTimeMillis()

            // 1. Update driver record in 'drivers' collection
            val driverUpdates = hashMapOf<String, Any?>(
                "driver_payment_status" to "SUBMITTED",
                "driver_paid_amount" to amount,
                "driver_payment_utr" to utr,
                "driver_payment_timestamp" to now,
                "last_payment_note" to note
            )
            db.collection("drivers").document(driverId)
                .set(driverUpdates, SetOptions.merge())
                .await()

            // 2. Also log to 'office_payments' collection for admin panel records
            val paymentRecord = hashMapOf<String, Any?>(
                "driver_id" to driverId,
                "driver_name" to driverName,
                "vehicle_number" to vehicleNumber,
                "amount" to amount,
                "utr" to utr,
                "note" to note,
                "timestamp" to now,
                "status" to "SUBMITTED"
            )
            db.collection("office_payments").document("${driverId}_$now")
                .set(paymentRecord, SetOptions.merge())
                .await()

            val submittedInfo = _officePaymentInfo.value.copy(
                lastPaidUtr = utr,
                lastPaidAmount = amount,
                lastPaidTimestamp = now,
                paymentStatus = "SUBMITTED"
            )
            _officePaymentInfo.value = submittedInfo
            persistOfficePaymentInfoLocally(context, submittedInfo)
            Log.d(TAG, "Driver payment submission recorded for $driverId: amount=$amount, utr=$utr")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record driver payment submission: ${e.message}", e)
            false
        }
    }

    suspend fun uploadDriverPhoto(context: Context, localPhotoPath: String, driverId: String): String? {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync CUTOFF: Skipping photo upload to Firebase Storage.")
            return localPhotoPath
        }
        return withContext(Dispatchers.IO) {
            try {
                val file = File(localPhotoPath)
                if (!file.exists()) return@withContext null

                val storageRef = getStorage(context).reference.child("driver_photos/${driverId}.jpg")
                val uri = Uri.fromFile(file)
                storageRef.putFile(uri).await()
                val downloadUrl = storageRef.downloadUrl.await().toString()

                // Update photo_url in driver document
                val db = getFirestore(context)
                db.collection("drivers").document(driverId).set(
                    mapOf(
                        "photo_url" to downloadUrl,
                        "photo_version" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()

                Log.d(TAG, "Driver photo uploaded successfully: $downloadUrl")
                downloadUrl
            } catch (e: Exception) {
                Log.w(TAG, "Failed uploading driver photo: ${e.message}")
                null
            }
        }
    }

    // ==========================================
    // TRIPS OPERATIONS
    // ==========================================

    suspend fun fetchAllTrips(context: Context): List<FirestoreTrip> {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync is CUTOFF. Returning empty trips list.")
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val snapshot = db.collection("trips").get().await()
                snapshot.documents.mapNotNull { doc ->
                    mapDocumentToTrip(doc.id, doc.data ?: emptyMap())
                }.sortedByDescending { it.created_at }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching all trips: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun postTrip(context: Context, trip: FirestoreTrip): String? {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync CUTOFF: Skipping posting trip to Firestore.")
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val docRef = if (trip.doc_id.isNotBlank()) {
                    db.collection("trips").document(trip.doc_id)
                } else {
                    db.collection("trips").document()
                }
                val now = if (trip.created_at > 0) trip.created_at else System.currentTimeMillis()
                val tripToSave = trip.copy(
                    doc_id = docRef.id,
                    trip_id = if (trip.trip_id.isNotBlank()) trip.trip_id else "TRIP${(1000..9999).random()}",
                    created_at = now,
                    updated_at = now
                )
                docRef.set(tripToSave.toMap()).await()
                Log.d(TAG, "Trip posted with ID: ${docRef.id}")
                docRef.id
            } catch (e: Exception) {
                Log.e(TAG, "Failed posting trip: ${e.message}", e)
                null
            }
        }
    }

    sealed class AcceptTripResult {
        data class Success(val updatedTrip: FirestoreTrip) : AcceptTripResult()
        data class AlreadyTaken(val message: String = "This trip has already been accepted by another driver.") : AcceptTripResult()
        data class Error(val message: String) : AcceptTripResult()
    }

    /**
     * Atomically accept a trip using Firestore Transaction.
     * Guarantees that only ONE driver (the first to accept) is assigned the trip.
     * Prevents race conditions and crashes if multiple drivers accept concurrently.
     */
    suspend fun acceptTrip(context: Context, tripDocId: String, driverId: String): AcceptTripResult {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync CUTOFF: Driver accepting trip in standalone mode.")
            return AcceptTripResult.Error("Backend sync is disabled.")
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val tripRef = db.collection("trips").document(tripDocId)

                // Read driver details comprehensively from SharedPreferences
                val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                val driverName = prefs.getString("driver_name", "")?.ifEmpty { "Driver" } ?: "Driver"
                val driverVehicle = prefs.getString("vehicle_number", "")?.ifEmpty { "N/A" } ?: "N/A"
                val driverCategory = prefs.getString("vehicle_category", "Mini") ?: "Mini"
                
                var resolvedPhone = prefs.getString("driver_mobile", "") ?: ""
                if (resolvedPhone.isBlank() || resolvedPhone == "N/A") {
                    resolvedPhone = prefs.getString("driver_phone", "") ?: ""
                }
                if (resolvedPhone.isBlank() || resolvedPhone == "N/A") {
                    resolvedPhone = prefs.getString("mobile_number", "") ?: ""
                }
                if (resolvedPhone.isBlank() || resolvedPhone == "N/A") {
                    resolvedPhone = prefs.getString("phone", "") ?: ""
                }

                // If still empty, fetch from driver's profile doc in Firestore
                if (resolvedPhone.isBlank() || resolvedPhone == "N/A") {
                    try {
                        val drvSnap = db.collection("drivers").document(driverId).get().await()
                        if (drvSnap.exists()) {
                            resolvedPhone = drvSnap.getString("mobile_number")
                                ?: (drvSnap.getString("driver_mobile")
                                ?: (drvSnap.getString("driver_phone")
                                ?: (drvSnap.getString("phone") ?: "")))
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Could not fetch driver doc phone: ${e.message}")
                    }
                }
                val finalDriverPhone = resolvedPhone.ifBlank { "N/A" }
                val now = System.currentTimeMillis()

                var assignedTrip: FirestoreTrip? = null

                db.runTransaction { transaction ->
                    val snapshot = transaction.get(tripRef)
                    if (!snapshot.exists()) {
                        throw IllegalStateException("TRIP_NOT_FOUND")
                    }

                    val currentStatus = snapshot.getString("status") ?: "OPEN"
                    val currentDriverId = snapshot.getString("driver_id")

                    val isStatusOpen = currentStatus.equals("OPEN", ignoreCase = true)
                    val isDriverAssignedToMe = currentDriverId.isNullOrBlank() || currentDriverId.equals(driverId, ignoreCase = true)

                    // First-accept-wins rule: if already taken by another driver or no longer open, abort transaction
                    if (!isStatusOpen || !isDriverAssignedToMe) {
                        throw IllegalStateException("TRIP_ALREADY_TAKEN")
                    }

                    val updateMap = mutableMapOf<String, Any?>(
                        "status" to "ACCEPTED",
                        "driver_id" to driverId,
                        "driver_name" to driverName,
                        "driver_phone" to finalDriverPhone,
                        "vehicle_number" to driverVehicle,
                        "vehicle_category" to driverCategory,
                        "accepted_at" to now,
                        "updated_at" to now
                    )

                    transaction.update(tripRef, updateMap)

                    val mergedData = snapshot.data?.toMutableMap() ?: mutableMapOf()
                    mergedData.putAll(updateMap)
                    assignedTrip = mapDocumentToTrip(tripDocId, mergedData)
                }.await()

                val resultTrip = assignedTrip ?: mapDocumentToTrip(tripDocId, mapOf(
                    "status" to "ACCEPTED",
                    "driver_id" to driverId,
                    "driver_name" to driverName,
                    "driver_phone" to finalDriverPhone,
                    "vehicle_number" to driverVehicle,
                    "vehicle_category" to driverCategory
                ))

                if (resultTrip != null) {
                    Log.d(TAG, "Trip $tripDocId successfully accepted by first driver $driverId")
                    AcceptTripResult.Success(resultTrip)
                } else {
                    AcceptTripResult.Error("Could not parse accepted trip.")
                }
            } catch (t: Throwable) {
                val errorMsg = t.message.orEmpty()
                Log.w(TAG, "Could not accept trip $tripDocId: $errorMsg")
                if (errorMsg.contains("TRIP_ALREADY_TAKEN", ignoreCase = true) ||
                    errorMsg.contains("TRIP_NOT_FOUND", ignoreCase = true) ||
                    errorMsg.contains("already", ignoreCase = true) ||
                    errorMsg.contains("Status: ACCEPTED", ignoreCase = true) ||
                    errorMsg.contains("no longer available", ignoreCase = true)) {
                    AcceptTripResult.AlreadyTaken("This trip has already been accepted by another driver.")
                } else {
                    AcceptTripResult.Error(t.localizedMessage ?: "Unable to accept trip. Please check connection.")
                }
            }
        }
    }

    suspend fun updateTripStatus(
        context: Context,
        tripDocId: String,
        status: String,
        extraData: Map<String, Any?> = emptyMap()
    ): Boolean {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync CUTOFF: Skipping remote status update for $tripDocId.")
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val tripRef = db.collection("trips").document(tripDocId)
                val now = System.currentTimeMillis()
                val updateMap = mutableMapOf<String, Any?>(
                    "status" to status,
                    "updated_at" to now
                )
                updateMap.putAll(extraData)
                tripRef.update(updateMap).await()
                Log.d(TAG, "Trip $tripDocId status updated to $status")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating trip $tripDocId status: ${e.message}")
                false
            }
        }
    }

    suspend fun completeTrip(
        context: Context,
        tripDocId: String,
        finalFare: Double,
        distanceKm: Double = 0.0,
        durationSeconds: Long = 0L,
        waitingSeconds: Long = 0L,
        startLocation: String = "",
        endLocation: String = "",
        startLat: Double? = null,
        startLng: Double? = null,
        endLat: Double? = null,
        endLng: Double? = null,
        routePathPoints: String = ""
    ): Boolean {
        if (!_isBackendSyncEnabled.value) {
            Log.d(TAG, "Backend sync CUTOFF: Trip completed locally in Room database only.")
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val tripRef = db.collection("trips").document(tripDocId)
                val now = System.currentTimeMillis()

                val updateMap = mutableMapOf<String, Any?>(
                    "status" to "COMPLETED",
                    "final_fare" to finalFare,
                    "completed_at" to now,
                    "updated_at" to now,
                    "distance_km" to distanceKm,
                    "distance" to distanceKm,
                    "duration_seconds" to durationSeconds,
                    "waiting_seconds" to waitingSeconds,
                    "start_location" to startLocation,
                    "end_location" to endLocation
                )
                if (startLat != null) updateMap["start_lat"] = startLat
                if (startLng != null) updateMap["start_lng"] = startLng
                if (endLat != null) updateMap["end_lat"] = endLat
                if (endLng != null) updateMap["end_lng"] = endLng
                if (routePathPoints.isNotBlank()) updateMap["route_path_points"] = routePathPoints

                tripRef.update(updateMap).await()
                Log.d(TAG, "Trip $tripDocId marked as COMPLETED in Firestore with fare: ₹$finalFare, distance: ${distanceKm}km")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed completing trip in Firestore: ${e.message}", e)
                false
            }
        }
    }

    // ==========================================
    // SINGLE TRIP DOCUMENT LISTENER & FETCH (OPTIMIZED)
    // ==========================================

    private var assignedTripListenerRegistration: ListenerRegistration? = null
    private var currentListeningTripDocId: String? = null

    /**
     * Fetches a single trip document once (Requirement 5).
     */
    suspend fun fetchSingleTrip(context: Context, tripDocId: String): FirestoreTrip? {
        val cleanDocId = tripDocId.trim()
        if (cleanDocId.isBlank()) return null
        ensureInitialized(context)
        if (!_isBackendSyncEnabled.value) return null

        return withContext(Dispatchers.IO) {
            try {
                val db = getFirestore(context)
                val snapshot = db.collection("trips").document(cleanDocId).get().await()
                if (snapshot.exists()) {
                    mapDocumentToTrip(snapshot.id, snapshot.data ?: emptyMap())
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching single trip $cleanDocId: ${e.message}")
                null
            }
        }
    }

    /**
     * Requirement 1: Listen to a single trip document (assigned_trip only).
     * Don't listen to entire trips collection.
     * When cancellations, status changes, or completions are performed on backend,
     * immediately notifies and clears/updates the view.
     */
    fun listenToAssignedTrip(context: Context, tripDocId: String, onTripUpdated: (FirestoreTrip?, String?) -> Unit) {
        val cleanDocId = tripDocId.trim()
        if (cleanDocId.isBlank()) {
            stopListeningToAssignedTrip()
            return
        }

        if (currentListeningTripDocId == cleanDocId && assignedTripListenerRegistration != null) {
            return
        }

        stopListeningToAssignedTrip()
        ensureInitialized(context)

        try {
            val db = getFirestore(context)
            val myDriverId = getDriverId(context).trim()
            currentListeningTripDocId = cleanDocId
            Log.i(TAG, "Attaching single snapshot listener to assigned trip document: $cleanDocId")

            assignedTripListenerRegistration = db.collection("trips").document(cleanDocId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Assigned trip listener error for $cleanDocId: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshot == null || !snapshot.exists()) {
                        Log.i(TAG, "Assigned trip document $cleanDocId was deleted/removed on backend. Clearing view immediately.")
                        onTripUpdated(null, "UNASSIGNED")
                        stopListeningToAssignedTrip()
                        return@addSnapshotListener
                    }

                    val trip = mapDocumentToTrip(snapshot.id, snapshot.data ?: emptyMap())
                    if (trip == null) {
                        onTripUpdated(null, "DELETED")
                        stopListeningToAssignedTrip()
                        return@addSnapshotListener
                    }

                    val status = trip.status.uppercase(Locale.ROOT)
                    val tripDriverId = trip.driver_id?.trim().orEmpty()
                    val isMyTrip = tripDriverId.isEmpty() || myDriverId.isEmpty() || tripDriverId.equals(myDriverId, ignoreCase = true)

                    // If status is CANCELLED, COMPLETED, REJECTED, or assigned to another driver, clear active trip immediately!
                    if (status in listOf("CANCELLED", "COMPLETED", "REJECTED", "UNASSIGNED") || !isMyTrip) {
                        val reason = if (!isMyTrip) "UNASSIGNED" else status
                        Log.i(TAG, "Assigned trip $cleanDocId status is now '$status' (isMyTrip=$isMyTrip, reason=$reason). Clearing view immediately!")
                        onTripUpdated(null, reason)
                        stopListeningToAssignedTrip()
                    } else {
                        // Live update for active ongoing trip
                        Log.d(TAG, "Assigned trip $cleanDocId updated on backend: status=$status")
                        onTripUpdated(trip, null)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach assigned trip listener: ${e.message}", e)
        }
    }

    /**
     * Requirement 2: Remove unnecessary snapshot listeners. Unsubscribe when not active.
     */
    fun stopListeningToAssignedTrip() {
        try {
            assignedTripListenerRegistration?.remove()
            assignedTripListenerRegistration = null
            currentListeningTripDocId = null
            Log.d(TAG, "Assigned trip snapshot listener detached")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping assigned trip listener: ${e.message}", e)
        }
    }

    // ==========================================
    // REALTIME SNAPSHOT LISTENERS FOR TRIPS
    // ==========================================

    private var realtimeTripsCallback: ((List<FirestoreTrip>) -> Unit)? = null

    fun setRealtimeTripsCallback(callback: ((List<FirestoreTrip>) -> Unit)?) {
        realtimeTripsCallback = callback
    }

    fun startListeningToRealtime(context: Context, onTripsUpdated: ((List<FirestoreTrip>) -> Unit)? = null) {
        if (onTripsUpdated != null) {
            realtimeTripsCallback = onTripsUpdated
        }
        if (!_isBackendSyncEnabled.value || isSyncCutoff(context)) {
            Log.d(TAG, "Backend sync is CUTOFF: Not listening to trips.")
            return
        }
        try {
            tripsListenerRegistration?.remove()
            val db = getFirestore(context)
            Log.i(TAG, "⚡ Attaching instant realtime snapshot listener to Firestore 'trips' collection...")
            tripsListenerRegistration = db.collection("trips")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Trips snapshot listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val trips = snapshot.documents.mapNotNull { doc ->
                            mapDocumentToTrip(doc.id, doc.data ?: emptyMap())
                        }.sortedByDescending { it.created_at }
                        Log.d(TAG, "⚡ Realtime trips snapshot received: ${trips.size} trips in database")
                        realtimeTripsCallback?.invoke(trips)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening to trips: ${e.message}", e)
        }
    }

    fun stopListeningToRealtime() {
        try {
            tripsListenerRegistration?.remove()
            tripsListenerRegistration = null
            realtimeTripsCallback = null
            stopListeningToAssignedTrip()
            Log.d(TAG, "Firestore snapshot listeners detached")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Firestore realtime listener: ${e.message}", e)
        }
    }

    // ==========================================
    // MAPPER & FILTERING HELPERS
    // ==========================================

    fun parseFlexibleDouble(value: Any?): Double? {
        if (value == null) return null
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
    }

    /**
     * Vehicle Category Matching Rules:
     * 1. Mini trip -> Shows to Mini and Sedan drivers
     * 2. Sedan trip -> Shows to Sedan drivers only
     * 3. SUV trip -> Shows to SUV drivers only
     * 4. SUV+ trip -> Shows to SUV+ and Innova drivers
     * 5. Innova trip -> Shows to Innova and SUV+ drivers
     * 6. Other vehicle name -> Shows to that specific vehicle driver
     * 7. Blank / ALL / ANY -> Shows to all drivers
     */
    fun isVehicleCategoryEligible(tripCategory: String?, driverCategory: String?): Boolean {
        val tripCat = tripCategory?.trim()?.uppercase(Locale.ROOT) ?: ""
        val driverCat = driverCategory?.trim()?.uppercase(Locale.ROOT) ?: ""

        // If trip does not specify category or specifies ALL/ANY, show to all drivers
        if (tripCat.isEmpty() || tripCat == "ALL" || tripCat == "ANY" || tripCat == "ANY_VEHICLE" || tripCat == "GENERAL" || tripCat == "ANY VEHICLE") {
            return true
        }

        // If driver category is not set, allow viewing to avoid blocking
        if (driverCat.isEmpty()) {
            return true
        }

        // Exact match
        if (tripCat == driverCat) {
            return true
        }

        val miniList = listOf("MINI", "HATCHBACK", "MICRO", "SMALL", "MINI CAB", "MINI TAXI")
        val sedanList = listOf("SEDAN", "PRIME SEDAN", "SEDAN CAB", "SEDAN TAXI", "DZIRE", "ETIOS")
        val suvList = listOf("SUV", "COMPACT SUV", "SUV CAB", "SUV TAXI", "BREZZA", "CRETA", "ERTIGA")
        val suvPlusList = listOf("SUV+", "SUV +", "SUV_PLUS", "SUV PLUS", "SUV-PLUS", "INNOVA", "INNOVA CRYSTA", "INNOVA HYCROSS", "7 SEATER", "7-SEATER", "MUV", "MAXI CAB")
        val innovaList = listOf("INNOVA", "INNOVA CRYSTA", "INNOVA HYCROSS")

        val isTripMini = tripCat in miniList
        val isDriverMini = driverCat in miniList
        val isDriverSedan = driverCat in sedanList
        val isTripSedan = tripCat in sedanList
        val isTripSuv = tripCat in suvList
        val isDriverSuv = driverCat in suvList
        val isTripSuvPlus = tripCat in suvPlusList
        val isDriverSuvPlus = driverCat in suvPlusList

        // 1. Mini vehicle trip post -> show to Mini and Sedan
        if (isTripMini) {
            return isDriverMini || isDriverSedan
        }

        // 2. Sedan vehicle trip post -> show to Sedan only
        if (isTripSedan) {
            return isDriverSedan
        }

        // 3. SUV vehicle trip post -> show to SUV only
        if (isTripSuv) {
            return isDriverSuv
        }

        // 4. SUV+ / Innova vehicle trip post -> show to SUV+ and Innova
        if (isTripSuvPlus || tripCat in innovaList) {
            return isDriverSuvPlus || driverCat in innovaList
        }

        // 5. Other custom vehicle name (e.g. Auto, Bike, Tempo, Luxury) -> exact normalized match
        val cleanTrip = tripCat.replace(" ", "").replace("_", "").replace("-", "")
        val cleanDriver = driverCat.replace(" ", "").replace("_", "").replace("-", "")
        return cleanTrip == cleanDriver
    }

    /**
     * Checks if trip pickup location is within driver radius.
     */
    fun isTripWithinRadius(
        trip: FirestoreTrip,
        driverLat: Double?,
        driverLng: Double?
    ): Boolean {
        val dispatchType = trip.dispatch_type.trim().uppercase(Locale.ROOT)
        if (dispatchType != "RADIUS" && dispatchType != "RADIUS_WISE" && dispatchType != "RADIUSWISE" && dispatchType != "GEO") {
            return true
        }
        val pickupLat = trip.pickup_lat ?: return true
        val pickupLng = trip.pickup_lng ?: return true
        if (driverLat == null || driverLng == null) {
            return true // driver GPS not yet locked, show trip
        }
        val radiusKms = if (trip.radius_kms <= 0.0) 15.0 else trip.radius_kms
        val results = FloatArray(1)
        return try {
            android.location.Location.distanceBetween(driverLat, driverLng, pickupLat, pickupLng, results)
            val distanceKms = results[0] / 1000.0
            val within = distanceKms <= radiusKms
            Log.d(TAG, "Trip ${trip.trip_id} distance: $distanceKms km vs radius $radiusKms km -> within=$within")
            within
        } catch (e: Exception) {
            Log.w(TAG, "Distance calculation fallback: ${e.message}")
            true
        }
    }

    fun mapDocumentToDriver(docId: String, data: Map<String, Any?>): FirestoreDriver {
        val parsedMobile = data["mobile_number"]?.toString()
            ?: (data["driver_mobile"]?.toString()
            ?: (data["phone"]?.toString()
            ?: (data["phone_number"]?.toString()
            ?: (data["mobile"]?.toString()
            ?: (data["driver_phone"]?.toString()
            ?: (data["contact"]?.toString()
            ?: (data["contact_number"]?.toString()
            ?: (data["phone_no"]?.toString()
            ?: (data["mobile_no"]?.toString() ?: "")))))))))

        val parsedName = data["driver_name"]?.toString()
            ?: (data["name"]?.toString()
            ?: (data["driverName"]?.toString()
            ?: (data["fullName"]?.toString() ?: "")))

        val parsedVNum = data["vehicle_number"]?.toString()
            ?: (data["vehicle_no"]?.toString()
            ?: (data["vehicleNumber"]?.toString()
            ?: (data["car_number"]?.toString()
            ?: (data["plate_number"]?.toString() ?: ""))))

        val parsedVCat = data["vehicle_category"]?.toString()
            ?: (data["vehicle_type"]?.toString()
            ?: (data["vehicleCategory"]?.toString()
            ?: (data["vehicleType"]?.toString()
            ?: (data["category"]?.toString() ?: "Mini"))))

        val parsedPhoto = data["photo_url"]?.toString()
            ?: (data["photoUrl"]?.toString()
            ?: (data["driver_photo"]?.toString()
            ?: (data["photo"]?.toString()
            ?: (data["image"]?.toString()
            ?: (data["avatar"]?.toString() ?: "")))))

        val rawStatus = data["status"]?.toString()
            ?: (data["driver_status"]?.toString()
            ?: (data["account_status"]?.toString() ?: "ACTIVE"))

        val isBlockedFlag = parseFlexibleBoolean(data["is_blocked"])
            ?: (parseFlexibleBoolean(data["blocked"])
            ?: (parseFlexibleBoolean(data["isBlocked"]) ?: false))

        val isActiveFlag = parseFlexibleBoolean(data["is_active"])
            ?: (parseFlexibleBoolean(data["active"])
            ?: (parseFlexibleBoolean(data["isActive"]) ?: true))

        val blockReason = data["block_reason"]?.toString()
            ?: (data["blockReason"]?.toString()
            ?: (data["reason"]?.toString()
            ?: (data["remarks"]?.toString() ?: "")))

        val rawHeartbeat = (data["last_heartbeat"] as? Number)?.toLong()
            ?: ((data["last_seen"] as? Number)?.toLong())
        val rawOnline = parseFlexibleBoolean(data["is_online"]) ?: (parseFlexibleBoolean(data["online_status"]) ?: false)

        // Heartbeat check: If marked online in Firestore but heartbeat is older than 90s (app uninstalled or dead)
        val computedOnline = if (rawOnline && rawHeartbeat != null && rawHeartbeat > 0L) {
            val age = System.currentTimeMillis() - rawHeartbeat
            if (age > 90_000L) {
                // Background self-healing update: make offline in Firestore so dispatchers/admins see offline
                val parsedDriverId = data["driver_id"]?.toString() ?: docId
                val ctx = cachedAppContext
                if (parsedDriverId.isNotBlank() && ctx != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            getFirestore(ctx).collection("drivers").document(parsedDriverId)
                                .set(
                                    mapOf(
                                        "is_online" to false,
                                        "last_heartbeat" to 0L
                                    ),
                                    SetOptions.merge()
                                )
                        } catch (_: Exception) {}
                    }
                }
                false
            } else {
                true
            }
        } else {
            rawOnline
        }

        return FirestoreDriver(
            driver_id = data["driver_id"]?.toString() ?: docId,
            driver_name = parsedName,
            mobile_number = parsedMobile,
            vehicle_number = parsedVNum,
            vehicle_category = parsedVCat,
            status = rawStatus,
            is_blocked = isBlockedFlag,
            is_active = isActiveFlag,
            block_reason = blockReason,
            expiry_date = data["expiry_date"]?.toString() ?: "2027-12-31",
            photo_url = parsedPhoto,
            photo_version = (data["photo_version"] as? Number)?.toLong() ?: 1L,
            last_sync_time = (data["last_sync_time"] as? Number)?.toLong() ?: 0L,
            is_online = computedOnline,
            latitude = parseFlexibleDouble(data["latitude"]),
            longitude = parseFlexibleDouble(data["longitude"]),
            last_seen = (data["last_seen"] as? Number)?.toLong(),
            device_id = data["device_id"]?.toString() ?: "",
            last_heartbeat = rawHeartbeat
        )
    }

    fun mapDocumentToTrip(docId: String, data: Map<String, Any?>): FirestoreTrip? {
        try {
            val tripId = data["trip_id"]?.toString() ?: docId
            val customerName = data["customer_name"]?.toString() ?: (data["customerName"]?.toString() ?: (data["name"]?.toString() ?: "Customer"))
            val customerPhone = data["customer_phone"]?.toString()
                ?: (data["customer_mobile"]?.toString()
                ?: (data["customerMobile"]?.toString()
                ?: (data["phone"]?.toString()
                ?: (data["mobile"]?.toString() ?: ""))))
            val pickupLocation = data["pickup_location"]?.toString()
                ?: (data["pickupLocation"]?.toString()
                ?: (data["from"]?.toString()
                ?: (data["pickup"]?.toString()
                ?: (data["pickup_address"]?.toString() ?: ""))))
            val dropLocation = data["drop_location"]?.toString()
                ?: (data["dropLocation"]?.toString()
                ?: (data["to"]?.toString()
                ?: (data["drop"]?.toString()
                ?: (data["drop_address"]?.toString() ?: ""))))
            val pickupLat = parseFlexibleDouble(data["pickup_lat"])
                ?: parseFlexibleDouble(data["pickup_latitude"])
                ?: parseFlexibleDouble(data["pickupLat"])
                ?: parseFlexibleDouble(data["latitude"])
                ?: parseFlexibleDouble(data["lat"])
                ?: parseFlexibleDouble(data["pick_lat"])
                ?: parseFlexibleDouble(data["from_lat"])
            val pickupLng = parseFlexibleDouble(data["pickup_lng"])
                ?: parseFlexibleDouble(data["pickup_longitude"])
                ?: parseFlexibleDouble(data["pickupLng"])
                ?: parseFlexibleDouble(data["longitude"])
                ?: parseFlexibleDouble(data["lng"])
                ?: parseFlexibleDouble(data["pick_lng"])
                ?: parseFlexibleDouble(data["from_lng"])
            val dropLat = parseFlexibleDouble(data["drop_lat"])
                ?: parseFlexibleDouble(data["drop_latitude"])
                ?: parseFlexibleDouble(data["dropLat"])
                ?: parseFlexibleDouble(data["to_lat"])
            val dropLng = parseFlexibleDouble(data["drop_lng"])
                ?: parseFlexibleDouble(data["drop_longitude"])
                ?: parseFlexibleDouble(data["dropLng"])
                ?: parseFlexibleDouble(data["to_lng"])
            val status = data["status"]?.toString()?.uppercase(Locale.ROOT) ?: "OPEN"
            val driverId = data["driver_id"]?.toString()
            val driverName = data["driver_name"]?.toString()
            val driverPhone = data["driver_phone"]?.toString() ?: (data["driver_mobile"]?.toString() ?: (data["driverPhone"]?.toString()))
            val vehicleNumber = data["vehicle_number"]?.toString() ?: (data["driver_vehicle_number"]?.toString() ?: (data["vehicleNumber"]?.toString()))
            val vehicleCategory = data["vehicle_category"]?.toString()
                ?: (data["vehicle_type"]?.toString()
                ?: (data["vehicleCategory"]?.toString()
                ?: (data["vehicleType"]?.toString()
                ?: (data["category"]?.toString() ?: "Mini"))))
            val estimatedFare = parseFlexibleDouble(data["estimated_fare"])
                ?: parseFlexibleDouble(data["estimatedFare"])
                ?: parseFlexibleDouble(data["fare"])
                ?: parseFlexibleDouble(data["total_fare"])
                ?: 0.0
            val finalFare = parseFlexibleDouble(data["final_fare"])
                ?: parseFlexibleDouble(data["finalFare"])
            val notes = data["notes"]?.toString() ?: (data["remarks"]?.toString() ?: "")
            val createdAt = (data["created_at"] as? Number)?.toLong() ?: ((data["createdAt"] as? Number)?.toLong() ?: 0L)
            val updatedAt = (data["updated_at"] as? Number)?.toLong() ?: ((data["updatedAt"] as? Number)?.toLong() ?: 0L)
            val acceptedAt = (data["accepted_at"] as? Number)?.toLong()
            val startedAt = (data["started_at"] as? Number)?.toLong()
            val completedAt = (data["completed_at"] as? Number)?.toLong()

            // Fare configuration parsing from backend
            val baseFare = parseFlexibleDouble(data["base_fare"])
                ?: parseFlexibleDouble(data["baseFare"])
                ?: parseFlexibleDouble(data["base_price"])
                ?: parseFlexibleDouble(data["base_rate"])

            val kmsFare = parseFlexibleDouble(data["kms_fare"])
                ?: parseFlexibleDouble(data["kmsFare"])
                ?: parseFlexibleDouble(data["per_km_fare"])
                ?: parseFlexibleDouble(data["perKmFare"])
                ?: parseFlexibleDouble(data["km_rate"])
                ?: parseFlexibleDouble(data["per_km_rate"])

            val hourFare = parseFlexibleDouble(data["hour_fare"])
                ?: parseFlexibleDouble(data["per_hour_fare"])
                ?: parseFlexibleDouble(data["per_hour_rate"])
                ?: parseFlexibleDouble(data["perHourRate"])
                ?: parseFlexibleDouble(data["hour_rate"])
                ?: parseFlexibleDouble(data["hourly_rate"])

            // Detect if Package or Regular
            val rawType = data["trip_type"]?.toString()?.uppercase(Locale.ROOT)
                ?: (data["trip_category"]?.toString()?.uppercase(Locale.ROOT) ?: "REGULAR")
            val isPackageFinal = (data["is_package"] as? Boolean == true) || 
                                 rawType == "PACKAGE" || 
                                 rawType == "RENTAL" || 
                                 (hourFare != null && hourFare > 0.0)

            val finalTripType = if (isPackageFinal) "PACKAGE" else "REGULAR"

            val dispatchType = (data["dispatch_type"]?.toString()
                ?: (data["dispatchType"]?.toString()
                ?: (data["dispatch_mode"]?.toString()
                ?: (data["dispatchMode"]?.toString()
                ?: (data["mode"]?.toString() ?: "BROADCAST"))))).trim().uppercase(Locale.ROOT)

            val radiusKms = parseFlexibleDouble(data["radius_kms"])
                ?: parseFlexibleDouble(data["radius_km"])
                ?: parseFlexibleDouble(data["radius"])
                ?: parseFlexibleDouble(data["radiusKms"])
                ?: parseFlexibleDouble(data["search_radius"])
                ?: parseFlexibleDouble(data["distance_radius"])
                ?: parseFlexibleDouble(data["radius_in_km"])
                ?: 15.0

            val tripOtp = data["otp"]?.toString()
                ?: (data["start_otp"]?.toString()
                ?: (data["expected_otp"]?.toString()
                ?: (data["ride_otp"]?.toString())))

            return FirestoreTrip(
                doc_id = docId,
                trip_id = tripId,
                customer_name = customerName,
                customer_phone = customerPhone,
                pickup_location = pickupLocation,
                drop_location = dropLocation,
                pickup_lat = pickupLat,
                pickup_lng = pickupLng,
                drop_lat = dropLat,
                drop_lng = dropLng,
                status = status,
                driver_id = driverId,
                driver_name = driverName,
                driver_phone = driverPhone,
                vehicle_number = vehicleNumber,
                vehicle_category = vehicleCategory,
                estimated_fare = estimatedFare,
                final_fare = finalFare,
                notes = notes,
                created_at = createdAt,
                updated_at = updatedAt,
                accepted_at = acceptedAt,
                started_at = startedAt,
                completed_at = completedAt,
                trip_type = finalTripType,
                base_fare = baseFare,
                kms_fare = kmsFare,
                hour_fare = hourFare,
                is_package = isPackageFinal,
                dispatch_type = dispatchType,
                radius_kms = radiusKms,
                otp = tripOtp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error mapping document to FirestoreTrip: ${e.message}", e)
            return null
        }
    }
}
