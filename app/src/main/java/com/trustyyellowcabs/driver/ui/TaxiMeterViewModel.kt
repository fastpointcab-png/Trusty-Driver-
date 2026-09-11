package com.trustyyellowcabs.driver.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trustyyellowcabs.driver.data.Trip
import com.trustyyellowcabs.driver.data.TripRepository
import com.trustyyellowcabs.driver.service.LiveTripState
import com.trustyyellowcabs.driver.service.TaxiMeterService
import com.trustyyellowcabs.driver.service.TaxiDispatchService
import com.trustyyellowcabs.driver.service.TaxiDispatchServiceState
import com.trustyyellowcabs.driver.service.TripStatus
import com.trustyyellowcabs.driver.network.FirebaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.trustyyellowcabs.driver.util.GeocoderHelper
import java.util.Date
import java.util.Locale

class TaxiMeterViewModel(private val repository: TripRepository, context: Context) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)

    // Flow of historical trips
    val tripHistory: StateFlow<List<Trip>> = repository.allTrips
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current screen navigation state inside MVVM
    // Screens: "DISPATCH" (Home dispatch & radar), "FORM" (Street hail meter standby), "LIVE" (Running/Paused), "SUMMARY" (Invoice/UPI Receipt), "HISTORY" (Past trips list), "SETTINGS" (Rates edit)
    private val _currentScreen = MutableStateFlow("DISPATCH")
    val currentScreen = _currentScreen.asStateFlow()

    // Active customer mobile number for the current trip
    private val _customerMobileNum = MutableStateFlow("")
    val customerMobileNum = _customerMobileNum.asStateFlow()

    // For viewing invoice details
    private val _selectedTripForInvoice = MutableStateFlow<Trip?>(null)
    val selectedTripForInvoice = _selectedTripForInvoice.asStateFlow()

    // Origin screen for invoice back navigation ("HISTORY" vs "FORM")
    private val _invoiceOriginScreen = MutableStateFlow("FORM")
    val invoiceOriginScreen = _invoiceOriginScreen.asStateFlow()

    // Driver configurations state (Persistently stored locally)
    private val _isDriverLoggedIn = MutableStateFlow(
        prefs.getBoolean("is_driver_logged_in", false) &&
        (prefs.getString("unique_driver_id", "") ?: "").isNotBlank() &&
        (prefs.getString("driver_name", "") ?: "").isNotBlank()
    )
    val isDriverLoggedIn = _isDriverLoggedIn.asStateFlow()

    private val _driverId = MutableStateFlow(prefs.getString("unique_driver_id", "") ?: "")
    val driverId = _driverId.asStateFlow()

    private val _driverName = MutableStateFlow(prefs.getString("driver_name", "") ?: "")
    val driverName = _driverName.asStateFlow()

    private val _vehicleNumber = MutableStateFlow(prefs.getString("vehicle_number", "") ?: "")
    val vehicleNumber = _vehicleNumber.asStateFlow()

    private val _driverMobile = MutableStateFlow(prefs.getString("driver_mobile", "") ?: "")
    val driverMobile = _driverMobile.asStateFlow()

    private val _vehicleCategory = MutableStateFlow(prefs.getString("vehicle_category", "Mini") ?: "Mini")
    val vehicleCategory = _vehicleCategory.asStateFlow()

    private val _vehicleModel = MutableStateFlow(prefs.getString("vehicle_model", "") ?: "")
    val vehicleModel = _vehicleModel.asStateFlow()

    private val _driverSelfiePath = MutableStateFlow(prefs.getString("driver_selfie_path", "") ?: "")
    val driverSelfiePath = _driverSelfiePath.asStateFlow()

    private val _driverPhotoUrl = MutableStateFlow(prefs.getString("driver_photo_url", "") ?: "")
    val driverPhotoUrl = _driverPhotoUrl.asStateFlow()

    // Real-time Office Payment & Fees from Admin Panel
    val officePaymentInfo = FirebaseManager.officePaymentInfo

    fun submitOfficePayment(
        context: Context,
        amount: Double,
        utr: String,
        note: String,
        onComplete: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            val success = FirebaseManager.recordDriverPaymentSubmission(
                context = context,
                driverId = _driverId.value,
                driverName = _driverName.value,
                vehicleNumber = _vehicleNumber.value,
                amount = amount,
                utr = utr,
                note = note
            )
            onComplete(success)
        }
    }

    init {
        // Load offline cached office payment info immediately (0 reads)
        FirebaseManager.loadCachedOfficePaymentInfo(appContext)

        // Sweep uninstalled/stale online drivers in background
        viewModelScope.launch(Dispatchers.IO) {
            FirebaseManager.cleanStaleOfflineDrivers(appContext)
        }

        // If driver has a photo URL in prefs/backend but local cache is missing, download it in background
        val currentPhotoUrl = _driverPhotoUrl.value
        val currentSelfie = _driverSelfiePath.value
        if (currentPhotoUrl.isNotBlank() && (currentSelfie.isBlank() || !java.io.File(currentSelfie).exists())) {
            viewModelScope.launch {
                val savedPath = com.trustyyellowcabs.driver.util.GoogleDriveUtils.downloadAndSaveDriverPhoto(appContext, currentPhotoUrl)
                if (savedPath != null) {
                    _driverSelfiePath.value = savedPath
                }
            }
        }
    }

    fun saveDriverSelfiePath(path: String) {
        _driverSelfiePath.value = path
        prefs.edit().putString("driver_selfie_path", path).apply()
    }

    fun saveDriverPhotoUrl(url: String) {
        _driverPhotoUrl.value = url
        prefs.edit().putString("driver_photo_url", url).apply()
        if (url.isNotBlank()) {
            viewModelScope.launch {
                val savedPath = com.trustyyellowcabs.driver.util.GoogleDriveUtils.downloadAndSaveDriverPhoto(appContext, url)
                if (savedPath != null) {
                    _driverSelfiePath.value = savedPath
                }
            }
        }
    }

    fun setDriverLoggedIn(
        driverId: String,
        name: String,
        vNumber: String,
        category: String = "Mini",
        model: String = "",
        mobile: String = "",
        photoUrl: String = ""
    ) {
        val uppercaseDriverId = driverId.uppercase(Locale.ROOT).trim()
        val uppercaseName = name.uppercase(Locale.ROOT).trim()
        val uppercaseVNumber = vNumber.uppercase(Locale.ROOT).trim()
        val trimmedMobile = mobile.trim()
        val trimmedPhoto = photoUrl.trim()

        _isDriverLoggedIn.value = true
        _driverId.value = uppercaseDriverId
        _driverName.value = uppercaseName
        _vehicleNumber.value = uppercaseVNumber
        _vehicleCategory.value = category
        _vehicleModel.value = model
        _driverMobile.value = trimmedMobile
        _driverPhotoUrl.value = trimmedPhoto

        prefs.edit().apply {
            putBoolean("is_driver_logged_in", true)
            putString("unique_driver_id", uppercaseDriverId)
            putString("driver_name", uppercaseName)
            putString("vehicle_number", uppercaseVNumber)
            putString("vehicle_category", category)
            putString("vehicle_model", model)
            putString("driver_mobile", trimmedMobile)
            putString("driver_photo_url", trimmedPhoto)
            putBoolean("terms_and_conditions_accepted", true)
            apply()
        }

        if (trimmedPhoto.isNotBlank()) {
            viewModelScope.launch {
                val savedPath = com.trustyyellowcabs.driver.util.GoogleDriveUtils.downloadAndSaveDriverPhoto(appContext, trimmedPhoto)
                if (savedPath != null) {
                    _driverSelfiePath.value = savedPath
                }
            }
        }
    }

    // Meter Fare Configurations (editable in Settings)
    private val _baseFare = MutableStateFlow(
        prefs.getFloat("base_fare", DEFAULT_REGULAR_BASE_FARE.toFloat()).toDouble()
    )
    val baseFare = _baseFare.asStateFlow()

    private val _perKmFare = MutableStateFlow(
        prefs.getFloat("per_km_fare", DEFAULT_REGULAR_PER_KM_FARE.toFloat()).toDouble()
    )
    val perKmFare = _perKmFare.asStateFlow()

    private val _waitingChargePerMin = MutableStateFlow(
        prefs.getFloat("waiting_charge", 1.0f).toDouble()
    )
    val waitingChargePerMin = _waitingChargePerMin.asStateFlow()

    private val _minimumFare = MutableStateFlow(
        prefs.getFloat("minimum_fare", 0.0f).toDouble()
    )
    val minimumFare = _minimumFare.asStateFlow()

    private val _nightChargePercent = MutableStateFlow(prefs.getFloat("night_charge", 0.0f).toDouble())
    val nightChargePercent = _nightChargePercent.asStateFlow()

    private val _isNightModeActive = MutableStateFlow(prefs.getBoolean("is_night_mode_active", false))
    val isNightModeActive = _isNightModeActive.asStateFlow()

    private val _isOverlayEnabled = MutableStateFlow(prefs.getBoolean("is_overlay_enabled", false))
    val isOverlayEnabled = _isOverlayEnabled.asStateFlow()

    private val _isAdvancedUnlocked = MutableStateFlow(prefs.getBoolean("is_advanced_unlocked", false))
    val isAdvancedUnlocked = _isAdvancedUnlocked.asStateFlow()

    fun setAdvancedUnlocked(unlocked: Boolean) {
        _isAdvancedUnlocked.value = unlocked
        prefs.edit().putBoolean("is_advanced_unlocked", unlocked).apply()
    }

    private val _networkUserCount = MutableStateFlow(prefs.getInt("network_user_count", 0))
    val networkUserCount = _networkUserCount.asStateFlow()

    fun checkNetworkUserCountAndProfile(context: Context) {
        val currentName = _driverName.value.trim()
        val currentVNum = _vehicleNumber.value.trim()
        val currentCat = _vehicleCategory.value.trim()
        val currentModel = _vehicleModel.value.trim()
        val currentMobile = _driverMobile.value.trim()

        com.trustyyellowcabs.driver.network.GoogleSheetsSyncManager.fetchUserCountAndProfile(
            context = context,
            driverName = currentName,
            vehicleNumber = currentVNum,
            vehicleCategory = currentCat,
            vehicleModel = currentModel,
            driverMobile = currentMobile
        ) { count, profile ->
            if (count > 0) {
                _networkUserCount.value = count
                prefs.edit().putInt("network_user_count", count).apply()
            }
            if (profile != null && profile.status == "success") {
                if (currentName.isEmpty() && currentVNum.isEmpty() && !profile.driverName.isNullOrBlank()) {
                    val pName = profile.driverName.uppercase(Locale.ROOT).trim()
                    val pVNum = (profile.vehicleNumber ?: "").uppercase(Locale.ROOT).trim()
                    val pCat = profile.vehicleCategory ?: "Mini"
                    val pModel = profile.vehicleModel ?: ""
                    
                    saveDriverDetails(
                        name = pName,
                        vNumber = pVNum,
                        category = pCat,
                        model = pModel,
                        isNight = false
                    )
                    Log.i("TaxiMeterViewModel", "Auto-restored profile from Google Sheets sync: $pName - $pVNum")
                }
            }
        }
    }

    private val _defaultMeterMode = MutableStateFlow("REGULAR")
    val defaultMeterMode = _defaultMeterMode.asStateFlow()

    fun setDefaultMeterMode(mode: String) {
        _defaultMeterMode.value = mode
    }

    // Package configuration details (editable in Settings or a separate section)
    private val _packageName = MutableStateFlow(prefs.getString("pkg_name", "1 Hour / 10 KM") ?: "1 Hour / 10 KM")
    val packageName = _packageName.asStateFlow()

    private val _packageBaseFare = MutableStateFlow(
        prefs.getFloat("pkg_base_fare", DEFAULT_PACKAGE_PER_HOUR_FARE.toFloat()).toDouble()
    )
    val packageBaseFare = _packageBaseFare.asStateFlow()

    private val _packagePerHourRate = MutableStateFlow(
        prefs.getFloat("pkg_per_hour_rate", DEFAULT_PACKAGE_PER_HOUR_FARE.toFloat()).toDouble()
    )
    val packagePerHourRate = _packagePerHourRate.asStateFlow()

    private val _packagePerKmRate = MutableStateFlow(
        prefs.getFloat("pkg_per_km_rate", DEFAULT_PACKAGE_PER_KM_FARE.toFloat()).toDouble()
    )
    val packagePerKmRate = _packagePerKmRate.asStateFlow()

    private val _packageIncludedKm = MutableStateFlow(prefs.getFloat("pkg_included_km", 10.0f).toDouble())
    val packageIncludedKm = _packageIncludedKm.asStateFlow()

    private val _packageIncludedMinutes = MutableStateFlow(prefs.getInt("pkg_included_minutes", 60))
    val packageIncludedMinutes = _packageIncludedMinutes.asStateFlow()

    private val _packageExtraKmRate = MutableStateFlow(
        prefs.getFloat("pkg_extra_km_rate", DEFAULT_PACKAGE_PER_KM_FARE.toFloat()).toDouble()
    )
    val packageExtraKmRate = _packageExtraKmRate.asStateFlow()

    private val _packageExtraTimeRate = MutableStateFlow(2.0833)
    val packageExtraTimeRate = _packageExtraTimeRate.asStateFlow()

    private val _packageWaitingChargePerMin = MutableStateFlow(prefs.getFloat("pkg_waiting_charge", 0.0f).toDouble())
    val packageWaitingChargePerMin = _packageWaitingChargePerMin.asStateFlow()

    fun updatePackageSettings(
        name: String,
        baseFare: Double,
        includedKm: Double,
        includedMinutes: Int,
        extraKmRate: Double,
        extraTimeRate: Double,
        waitingCharge: Double,
        perHourRate: Double = _packagePerHourRate.value,
        perKmRate: Double = _packagePerKmRate.value
    ) {
        _packageName.value = name
        _packageBaseFare.value = baseFare
        _packageIncludedKm.value = includedKm
        _packageIncludedMinutes.value = includedMinutes
        _packageExtraKmRate.value = extraKmRate
        val calculatedExtraTimeRate = 2.0833
        _packageExtraTimeRate.value = calculatedExtraTimeRate
        _packageWaitingChargePerMin.value = waitingCharge
        _packagePerHourRate.value = perHourRate
        _packagePerKmRate.value = perKmRate

        prefs.edit().apply {
            putString("pkg_name", name)
            putFloat("pkg_base_fare", baseFare.toFloat())
            putFloat("pkg_included_km", includedKm.toFloat())
            putInt("pkg_included_minutes", includedMinutes)
            putFloat("pkg_extra_km_rate", extraKmRate.toFloat())
            putFloat("pkg_extra_time_rate", calculatedExtraTimeRate.toFloat())
            putFloat("pkg_waiting_charge", waitingCharge.toFloat())
            putFloat("pkg_per_hour_rate", perHourRate.toFloat())
            putFloat("pkg_per_km_rate", perKmRate.toFloat())
            apply()
        }
    }

    // Service status observer
    val serviceState: StateFlow<LiveTripState> = TaxiMeterService.tripState

    init {
        // Preserve driver profile and persistent local login across app updates and daily launches
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            prefs.edit().putLong("saved_version_code", currentVersionCode).apply()
        } catch (e: Exception) {
            Log.e("TaxiMeterViewModel", "Error checking/saving package version code: ${e.message}")
        }

        // Initialize default meter rates: Regular (Base fare ₹80, KMS fare ₹28), Package (Per hour ₹350, KMS fare ₹25)
        if (!prefs.getBoolean("meter_default_fares_v1", false) || (_baseFare.value <= 0.0 && _perKmFare.value <= 0.0)) {
            resetToDefaultFares()
            prefs.edit().putBoolean("meter_default_fares_v1", true).apply()
        }

        // Synchronize accurate network time offset from internet for India timezone/clock safety
        viewModelScope.launch {
            com.trustyyellowcabs.driver.util.NetworkTimeHelper.syncTimeOffset()
        }

        // Auto-recover stale active trips that were never ended (e.g., started > 12 hours ago)
        viewModelScope.launch {
            try {
                val savedState = TaxiMeterService.loadTripState(context)
                if (savedState != null && (savedState.status == TripStatus.RUNNING || savedState.status == TripStatus.PAUSED)) {
                    val nowMs = System.currentTimeMillis()
                    val diffHours = (nowMs - savedState.startTime) / (1000 * 60 * 60)
                    if (diffHours >= 12) {
                        Log.i("TaxiMeterViewModel", "Auto-finalizing stale active trip from $diffHours hours ago to prevent data loss")
                        
                        // Apply minimum fare bounding on total fare
                        var finalFare = savedState.currentFare
                        val minFareBound = if (savedState.minimumFare > 0.0) savedState.minimumFare else 198.0
                        if (savedState.isPackageMeter) {
                            if (savedState.packageBaseFare > 0.0 && finalFare < savedState.packageBaseFare) {
                                finalFare = savedState.packageBaseFare
                            }
                        } else {
                            if (finalFare < minFareBound) {
                                finalFare = minFareBound
                            }
                        }

                        // Generate Trip ID: last 4 of vehicle number + random 4-digit number
                        val rawVNo = savedState.vehicleNumber
                        val cleanVNo = rawVNo.replace("\\s".toRegex(), "").filter { it.isLetterOrDigit() }
                        val last4 = if (cleanVNo.length >= 4) cleanVNo.takeLast(4) else if (cleanVNo.isNotEmpty()) cleanVNo else "9999"
                        val rNum = (1000..9999).random()
                        val generatedTripId = "${last4.uppercase()}$rNum"
                        val recoveredBackendTripId = prefs.getString("active_dispatch_trip_id", null)
                        val effectiveTripIdCode = if (!recoveredBackendTripId.isNullOrBlank()) recoveredBackendTripId else generatedTripId

                        val ccComm = when {
                            finalFare >= 200.0 && finalFare <= 300.0 -> 50.0
                            finalFare > 300.0 && finalFare <= 750.0 -> 75.0
                            finalFare > 750.0 -> finalFare * 0.10
                            else -> 0.0
                        }

                        val formatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm a")
                        val currentDateStr = formatter.format(java.util.Date(savedState.startTime))

                        // Resolve address placeholders or get from geocoder
                        val sLat = savedState.startLatitude
                        val sLng = savedState.startLongitude
                        val eLat = savedState.endLatitude
                        val eLng = savedState.endLongitude

                        val finalStart = withContext(Dispatchers.IO) {
                            if (sLat != null && sLng != null) {
                                GeocoderHelper.getAddressFromLatLng(context, sLat, sLng)
                            } else null
                        } ?: savedState.startLocation.ifEmpty { "Unknown" }

                        val finalEnd = withContext(Dispatchers.IO) {
                            if (eLat != null && eLng != null) {
                                GeocoderHelper.getAddressFromLatLng(context, eLat, eLng)
                            } else null
                        } ?: savedState.endLocation.ifEmpty { "Unknown" }

                        val finalTrip = Trip(
                            driverName = savedState.driverName.ifEmpty { "N/A" },
                            vehicleNumber = savedState.vehicleNumber.ifEmpty { "N/A" },
                            vehicleCategory = savedState.vehicleCategory,
                            vehicleModel = savedState.vehicleModel.ifEmpty { "N/A" },
                            driverMobile = _driverMobile.value,
                            startTime = savedState.startTime,
                            endTime = nowMs,
                            distance = savedState.distanceKm,
                            durationSeconds = savedState.durationSeconds,
                            totalFare = finalFare,
                            baseFare = if (savedState.isPackageMeter) savedState.packageBaseFare else savedState.baseFare,
                            perKmFare = if (savedState.isPackageMeter) savedState.extraKmRate else savedState.perKmFare,
                            waitingChargePerMin = if (savedState.isPackageMeter) 0.0 else savedState.waitingChargePerMin,
                            minimumFare = if (savedState.isPackageMeter) 0.0 else minFareBound,
                            nightChargePercent = if (!savedState.isPackageMeter && savedState.isNightMode) savedState.nightChargePercent else 0.0,
                            dateStr = currentDateStr,
                            startLocation = finalStart,
                            endLocation = finalEnd,
                            startLatitude = sLat,
                            startLongitude = sLng,
                            endLatitude = eLat,
                            endLongitude = eLng,
                            ccCommission = ccComm,
                            tripIdCode = effectiveTripIdCode,
                            customerMobile = "",
                            isPackageMeter = savedState.isPackageMeter,
                            packageName = savedState.packageName,
                            packageBaseFare = savedState.packageBaseFare,
                            includedKm = savedState.includedKm,
                            includedMinutes = savedState.includedMinutes,
                            extraKmRate = savedState.extraKmRate,
                            extraTimeRate = savedState.extraTimeRate,
                            packageWaitingChargePerMin = if (savedState.isPackageMeter) 0.0 else savedState.packageWaitingChargePerMin,
                            waitingSeconds = savedState.waitingSeconds,
                            routePathPoints = savedState.routePathPoints
                        )

                        withContext(Dispatchers.IO) {
                            repository.insertTrip(finalTrip)
                        }

                        TaxiMeterService.clearSavedTripState(context)
                        prefs.edit().remove("active_dispatch_trip_id").apply()
                    }
                }
            } catch (e: Exception) {
                Log.e("TaxiMeterViewModel", "Error recovering stale active trip: ${e.message}", e)
            } finally {
                // Silent background sync catchup for any offline trips on startup
                com.trustyyellowcabs.driver.network.GoogleSheetsSyncManager.syncAllUnsyncedTrips(context, repository)
            }
        }

        // Evaluate navigation screen on boot in case service was restarted in background
        viewModelScope.launch {
            serviceState.collect { live ->
                if (live.status == TripStatus.RUNNING || live.status == TripStatus.PAUSED) {
                    _currentScreen.value = "LIVE"
                }
            }
        }
    }

    fun setNavigation(screen: String) {
        _currentScreen.value = screen
    }

    fun viewInvoice(trip: Trip) {
        _selectedTripForInvoice.value = trip
        _invoiceOriginScreen.value = "HISTORY"
        _currentScreen.value = "SUMMARY"
    }

    // Settings actions
    fun updateBaseAndPerKmFare(base: Double, perKm: Double) {
        _baseFare.value = base
        _perKmFare.value = perKm
        prefs.edit().apply {
            putFloat("base_fare", base.toFloat())
            putFloat("per_km_fare", perKm.toFloat())
            apply()
        }
    }

    fun saveRates(
        base: Double,
        perKm: Double,
        waiting: Double,
        min: Double,
        nightPercent: Double
    ) {
        _baseFare.value = base
        _perKmFare.value = perKm
        _waitingChargePerMin.value = waiting
        _minimumFare.value = min
        _nightChargePercent.value = nightPercent

        prefs.edit().apply {
            putFloat("base_fare", base.toFloat())
            putFloat("per_km_fare", perKm.toFloat())
            putFloat("waiting_charge", waiting.toFloat())
            putFloat("minimum_fare", min.toFloat())
            putFloat("night_charge", nightPercent.toFloat())
            apply()
        }
    }

    // Driver Details Saving
    fun saveDriverDetails(
        name: String,
        vNumber: String,
        category: String,
        model: String = "",
        isNight: Boolean,
        mobile: String = ""
    ) {
        val uppercaseName = name.uppercase(Locale.ROOT).trim()
        val uppercaseVNumber = vNumber.uppercase(Locale.ROOT).trim()
        val trimmedMobile = mobile.trim()

        _driverName.value = uppercaseName
        _vehicleNumber.value = uppercaseVNumber
        _vehicleCategory.value = category
        _vehicleModel.value = model
        _isNightModeActive.value = isNight
        _driverMobile.value = trimmedMobile

        if (uppercaseName.isNotBlank() && uppercaseVNumber.isNotBlank()) {
            _isDriverLoggedIn.value = true
        }

        prefs.edit().apply {
            if (uppercaseName.isNotBlank() && uppercaseVNumber.isNotBlank()) {
                putBoolean("is_driver_logged_in", true)
            }
            putString("driver_name", uppercaseName)
            putString("vehicle_number", uppercaseVNumber)
            putString("vehicle_category", category)
            putString("vehicle_model", model)
            putBoolean("is_night_mode_active", isNight)
            putString("driver_mobile", trimmedMobile)
            apply()
        }

        // Trigger Google Sheets sync if details changed, preventing duplicate spamming
        if (uppercaseName.isNotBlank() && uppercaseVNumber.isNotBlank()) {
            val currentSignature = "$uppercaseName|$uppercaseVNumber|$category|$model|$trimmedMobile"
            val lastSyncedSignature = prefs.getString("last_synced_driver_signature", "") ?: ""
            if (currentSignature != lastSyncedSignature) {
                prefs.edit().putString("last_synced_driver_signature", currentSignature).apply()
                com.trustyyellowcabs.driver.network.GoogleSheetsSyncManager.syncDriverLogin(
                    context = appContext,
                    driverName = uppercaseName,
                    vehicleNumber = uppercaseVNumber,
                    vehicleCategory = category,
                    vehicleModel = model,
                    driverMobile = trimmedMobile
                )
            }
        } else {
            prefs.edit().remove("last_synced_driver_signature").apply()
        }
    }

    /**
     * Gracefully logs out the active driver, syncing their offline state to backend and Google Sheets before clearing.
     */
    fun logoutDriver(context: Context) {
        // Stop dispatch service and set offline state immediately
        TaxiDispatchService.stopService(context)
        TaxiDispatchServiceState.setOnline(false)
        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_dispatch_online", false).apply()

        val currentName = _driverName.value
        val currentVNum = _vehicleNumber.value
        val currentCat = _vehicleCategory.value
        val currentModel = _vehicleModel.value
        val currentMobile = _driverMobile.value
        val currentDriverId = _driverId.value.ifEmpty {
            prefs.getString("unique_driver_id", "") ?: ""
        }

        if (currentName.isNotBlank() && currentVNum.isNotBlank()) {
            com.trustyyellowcabs.driver.network.GoogleSheetsSyncManager.syncDriverLogout(
                context = context,
                driverName = currentName,
                vehicleNumber = currentVNum,
                vehicleCategory = currentCat,
                vehicleModel = currentModel,
                driverMobile = currentMobile
            )
        }

        // Clear device binding in Firestore so the driver can log in on another device, and mark offline
        if (currentDriverId.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                FirebaseManager.setDriverOnlineStatus(context.applicationContext, isOnline = false, targetDriverId = currentDriverId)
                FirebaseManager.clearDriverDeviceSession(context.applicationContext, currentDriverId)
            }
        }
        FirebaseManager.stopListeningToDriverSession()

        saveDriverDetails(
            name = "",
            vNumber = "",
            category = "Mini",
            model = "",
            isNight = false,
            mobile = ""
        )

        _isDriverLoggedIn.value = false
        _driverId.value = ""
        _driverName.value = ""
        _vehicleNumber.value = ""
        _vehicleCategory.value = "Mini"
        _vehicleModel.value = ""
        _driverMobile.value = ""
        _driverSelfiePath.value = ""
        _driverPhotoUrl.value = ""

        prefs.edit()
            .putBoolean("is_driver_logged_in", false)
            .putString("unique_driver_id", "")
            .putString("driver_name", "")
            .putString("vehicle_number", "")
            .putString("vehicle_category", "Mini")
            .putString("vehicle_model", "")
            .putString("driver_mobile", "")
            .putString("driver_selfie_path", "")
            .putString("driver_photo_url", "")
            .remove("last_synced_driver_signature")
            .apply()
    }

    // Taxi Operation Initiators
    fun startTaxiTrip(
        context: Context,
        customerMobile: String,
        customBaseFare: Double? = null,
        customKmsFare: Double? = null,
        isPackage: Boolean = false,
        pkgName: String = "",
        pkgBaseFare: Double = 0.0,
        pkgIncludedKm: Double = 0.0,
        pkgIncludedMinutes: Int = 0,
        pkgExtraKmRate: Double = 0.0,
        pkgExtraTimeRate: Double = 0.0,
        pkgWaitingCharge: Double = 0.0,
        pkgPerHourRate: Double = 0.0,
        pkgPerKmRate: Double = 0.0,
        dispatchDocId: String? = null,
        dispatchTripId: String? = null
    ) {
        _customerMobileNum.value = customerMobile
        _isOverlayEnabled.value = false
        val prefsEditor = prefs.edit().putBoolean("is_overlay_enabled", false)
        if (!dispatchDocId.isNullOrBlank()) {
            prefsEditor.putString("active_dispatch_doc_id", dispatchDocId)
            val effectiveTripId = if (!dispatchTripId.isNullOrBlank()) {
                dispatchTripId
            } else {
                com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.activeTrip.value?.trip_id?.ifBlank { dispatchDocId } ?: dispatchDocId
            }
            prefsEditor.putString("active_dispatch_trip_id", effectiveTripId)
        } else {
            prefsEditor.remove("active_dispatch_doc_id")
            prefsEditor.remove("active_dispatch_trip_id")
        }
        prefsEditor.apply()
        
        if (isPackage) {
            _defaultMeterMode.value = "PACKAGE"
            prefs.edit().putString("default_meter_mode", "PACKAGE").apply()
        }
        
        val finalBaseFare = customBaseFare ?: (if (_baseFare.value > 0.0) _baseFare.value else DEFAULT_REGULAR_BASE_FARE)
        val finalPerKmFare = customKmsFare ?: (if (_perKmFare.value > 0.0) _perKmFare.value else DEFAULT_REGULAR_PER_KM_FARE)
        val finalPkgPerHour = if (pkgPerHourRate > 0.0) pkgPerHourRate else (if (_packagePerHourRate.value > 0.0) _packagePerHourRate.value else DEFAULT_PACKAGE_PER_HOUR_FARE)
        val finalPkgPerKm = if (pkgPerKmRate > 0.0) pkgPerKmRate else (if (_packagePerKmRate.value > 0.0) _packagePerKmRate.value else DEFAULT_PACKAGE_PER_KM_FARE)
        val finalPkgBaseFare = if (pkgBaseFare > 0.0) pkgBaseFare else (if (_packageBaseFare.value > 0.0) _packageBaseFare.value else finalPkgPerHour)
        val finalPkgExtraKmRate = if (pkgExtraKmRate > 0.0) pkgExtraKmRate else finalPkgPerKm
        
        val isUnlocked = _isAdvancedUnlocked.value
        val finalWaitingCharge = if (isUnlocked && _waitingChargePerMin.value > 0.0) _waitingChargePerMin.value else 1.0
        val finalMinFare = if (isUnlocked) _minimumFare.value else 198.0
        val finalNightPercent = if (isUnlocked) _nightChargePercent.value else 0.0
        val finalNightMode = if (isUnlocked) _isNightModeActive.value else false

        TaxiMeterService.startTrip(
            context = context,
            driverName = _driverName.value,
            vehicleNumber = _vehicleNumber.value,
            category = _vehicleCategory.value,
            model = _vehicleModel.value,
            baseFare = finalBaseFare,
            perKmFare = finalPerKmFare,
            waitingCharge = finalWaitingCharge,
            minFare = finalMinFare,
            nightPercent = finalNightPercent,
            isNightMode = finalNightMode,
            isOverlayActive = false,
            isPackageMeter = isPackage,
            packageName = pkgName,
            packageBaseFare = finalPkgBaseFare,
            includedKm = pkgIncludedKm,
            includedMinutes = pkgIncludedMinutes,
            extraKmRate = finalPkgExtraKmRate,
            extraTimeRate = pkgExtraTimeRate,
            packageWaitingChargePerMin = pkgWaitingCharge,
            packagePerHourRate = finalPkgPerHour,
            packagePerKmRate = finalPkgPerKm
        )
        if (dispatchDocId.isNullOrBlank()) {
            _currentScreen.value = "LIVE"
        } else {
            _currentScreen.value = "DISPATCH"
        }
        com.trustyyellowcabs.driver.util.TtsAnnouncer.speakTamil(context, "தயவுசெய்து சீட் பெல்ட் அணிந்து பாதுகாப்பாக பயணிக்கவும்.")
    }

    fun pauseTaxiTrip(context: Context) {
        TaxiMeterService.pauseTrip(context)
    }

    fun resumeTaxiTrip(context: Context) {
        TaxiMeterService.resumeTrip(context)
    }

    fun endTaxiTrip(context: Context, explicitTripId: String? = null) {
        val currentState = serviceState.value
        if (currentState.status == TripStatus.RUNNING || currentState.status == TripStatus.PAUSED) {
            if (currentState.isPackageMeter) {
                setDefaultMeterMode("REGULAR")
            }
            TaxiMeterService.endTrip(context)
            com.trustyyellowcabs.driver.util.TtsAnnouncer.speakTamil(context)

            // Capture backend dispatch trip info synchronously before any other async operations or state changes
            val activeBackendTrip = com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.activeTrip.value
            val activeDocId = activeBackendTrip?.doc_id
                ?: prefs.getString("active_dispatch_doc_id", null)
            val backendAssignedTripId = explicitTripId?.ifBlank { null }
                ?: activeBackendTrip?.trip_id?.ifBlank { activeBackendTrip.doc_id }
                ?: prefs.getString("active_dispatch_trip_id", null)
                ?: (if (!activeDocId.isNullOrBlank()) activeDocId else null)
            
            // Save the trip to the database
            viewModelScope.launch {
                val sLat = currentState.startLatitude
                val sLng = currentState.startLongitude
                val eLat = currentState.endLatitude
                val eLng = currentState.endLongitude

                // Fetch real geo names on thread pool before committing to DB
                val finalStart = withContext(Dispatchers.IO) {
                    if (sLat != null && sLng != null) {
                        GeocoderHelper.getAddressFromLatLng(context, sLat, sLng)
                    } else null
                } ?: currentState.startLocation

                val finalEnd = withContext(Dispatchers.IO) {
                    if (eLat != null && eLng != null) {
                        GeocoderHelper.getAddressFromLatLng(context, eLat, eLng)
                    } else null
                } ?: currentState.endLocation

                val formatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm a")
                val currentDateStr = formatter.format(Date(currentState.startTime))

                // Apply minimum fare bounding on total fare after trip is ended
                var finalFare = currentState.currentFare
                val minFareBound = if (currentState.minimumFare > 0.0) currentState.minimumFare else 198.0
                val effectivePkgBase = if (currentState.packageBaseFare > 0.0) currentState.packageBaseFare else (if (currentState.packagePerHourRate > 0.0) currentState.packagePerHourRate else 0.0)
                if (currentState.isPackageMeter) {
                    if (effectivePkgBase > 0.0 && finalFare < effectivePkgBase) {
                        finalFare = effectivePkgBase
                    }
                } else {
                    if (finalFare < minFareBound) {
                        finalFare = minFareBound
                    }
                }

                // Generate Trip ID:
                // For local meter: last 4 of vehicle number + random 4-digit number
                val rawVNo = currentState.vehicleNumber
                val cleanVNo = rawVNo.replace("\\s".toRegex(), "").filter { it.isLetterOrDigit() }
                val last4 = if (cleanVNo.length >= 4) cleanVNo.takeLast(4) else if (cleanVNo.isNotEmpty()) cleanVNo else "9999"
                val rNum = (1000..9999).random()
                val generatedLocalTripId = "${last4.uppercase()}$rNum"

                // If trip was assigned in backend, use backend trip ID in app and bill;
                // Otherwise for local meter, use app trip ID as usual.
                val finalTripIdCode = if (!backendAssignedTripId.isNullOrBlank()) {
                    backendAssignedTripId
                } else {
                    generatedLocalTripId
                }

                // Calculate CC commission:
                // If total fare is between ₹200 to ₹300 -> CC = ₹50
                // If total fare is above ₹300 to ₹750 -> CC = ₹75
                // If total fare is above ₹750 -> CC = 10% of total fare
                val ccComm = when {
                    finalFare >= 200.0 && finalFare <= 300.0 -> 50.0
                    finalFare > 300.0 && finalFare <= 750.0 -> 75.0
                    finalFare > 750.0 -> finalFare * 0.10
                    else -> 0.0
                }

                val finalTrip = Trip(
                    driverName = currentState.driverName.ifEmpty { "N/A" },
                    vehicleNumber = currentState.vehicleNumber.ifEmpty { "N/A" },
                    vehicleCategory = currentState.vehicleCategory,
                    vehicleModel = currentState.vehicleModel.ifEmpty { "N/A" },
                    driverMobile = _driverMobile.value,
                    startTime = currentState.startTime,
                    endTime = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis(),
                    distance = currentState.distanceKm,
                    durationSeconds = currentState.durationSeconds,
                    totalFare = finalFare,
                    baseFare = if (currentState.isPackageMeter) currentState.packageBaseFare else currentState.baseFare,
                    perKmFare = if (currentState.isPackageMeter) currentState.extraKmRate else currentState.perKmFare,
                    waitingChargePerMin = if (currentState.isPackageMeter) currentState.packageWaitingChargePerMin else currentState.waitingChargePerMin,
                    minimumFare = if (currentState.isPackageMeter) 0.0 else minFareBound,
                    nightChargePercent = if (!currentState.isPackageMeter && currentState.isNightMode) currentState.nightChargePercent else 0.0,
                    dateStr = currentDateStr,
                    startLocation = finalStart,
                    endLocation = finalEnd,
                    startLatitude = currentState.startLatitude,
                    startLongitude = currentState.startLongitude,
                    endLatitude = currentState.endLatitude,
                    endLongitude = currentState.endLongitude,
                    ccCommission = ccComm,
                    tripIdCode = finalTripIdCode,
                    customerMobile = _customerMobileNum.value,
                    isPackageMeter = currentState.isPackageMeter,
                    packageName = currentState.packageName,
                    packageBaseFare = currentState.packageBaseFare,
                    includedKm = currentState.includedKm,
                    includedMinutes = currentState.includedMinutes,
                    extraKmRate = currentState.extraKmRate,
                    extraTimeRate = currentState.extraTimeRate,
                    packageWaitingChargePerMin = currentState.packageWaitingChargePerMin,
                    waitingSeconds = currentState.waitingSeconds,
                    routePathPoints = currentState.routePathPoints
                )

                // Insert into db
                val insertedId = repository.insertTrip(finalTrip)
                val tripToSync = finalTrip.copy(id = insertedId)
                
                // Set the current trip as selected for view invoice screen
                _selectedTripForInvoice.value = tripToSync
                TaxiMeterService.resetMeter()
                _invoiceOriginScreen.value = "FORM"
                _currentScreen.value = "SUMMARY"

                // Check and update backend for active dispatch trip
                if (!activeDocId.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        val backendUpdated = com.trustyyellowcabs.driver.network.FirebaseManager.completeTrip(
                            context = context,
                            tripDocId = activeDocId,
                            finalFare = finalFare,
                            distanceKm = currentState.distanceKm,
                            durationSeconds = currentState.durationSeconds,
                            waitingSeconds = currentState.waitingSeconds,
                            startLocation = finalStart,
                            endLocation = finalEnd,
                            startLat = currentState.startLatitude,
                            startLng = currentState.startLongitude,
                            endLat = currentState.endLatitude,
                            endLng = currentState.endLongitude,
                            routePathPoints = currentState.routePathPoints
                        )
                        if (backendUpdated) {
                            Log.d("TaxiMeterViewModel", "Backend trip $activeDocId marked as COMPLETED successfully!")
                        }
                    }
                    com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.setActiveTrip(null)
                    prefs.edit()
                        .remove("active_dispatch_doc_id")
                        .remove("active_dispatch_trip_id")
                        .apply()
                } else {
                    prefs.edit().remove("active_dispatch_trip_id").apply()
                }

                // Automatically reset rates to default fare set for next trip on stop/conclude
                resetToDefaultFares()

                // Sync the ended trip to Google Sheets in a non-blocking background task
                viewModelScope.launch {
                    com.trustyyellowcabs.driver.network.GoogleSheetsSyncManager.syncTrip(context, tripToSync, repository)
                }
            }
        }
    }

    fun resetToDefaultFares() {
        _baseFare.value = DEFAULT_REGULAR_BASE_FARE
        _perKmFare.value = DEFAULT_REGULAR_PER_KM_FARE
        _packageBaseFare.value = DEFAULT_PACKAGE_PER_HOUR_FARE
        _packagePerHourRate.value = DEFAULT_PACKAGE_PER_HOUR_FARE
        _packagePerKmRate.value = DEFAULT_PACKAGE_PER_KM_FARE
        _packageExtraKmRate.value = DEFAULT_PACKAGE_PER_KM_FARE
        _defaultMeterMode.value = "REGULAR"
        prefs.edit().apply {
            putFloat("base_fare", DEFAULT_REGULAR_BASE_FARE.toFloat())
            putFloat("per_km_fare", DEFAULT_REGULAR_PER_KM_FARE.toFloat())
            putFloat("pkg_base_fare", DEFAULT_PACKAGE_PER_HOUR_FARE.toFloat())
            putFloat("pkg_per_hour_rate", DEFAULT_PACKAGE_PER_HOUR_FARE.toFloat())
            putFloat("pkg_per_km_rate", DEFAULT_PACKAGE_PER_KM_FARE.toFloat())
            putFloat("pkg_extra_km_rate", DEFAULT_PACKAGE_PER_KM_FARE.toFloat())
            putString("default_meter_mode", "REGULAR")
            apply()
        }
    }

    companion object {
        const val DEFAULT_REGULAR_BASE_FARE = 80.0
        const val DEFAULT_REGULAR_PER_KM_FARE = 28.0
        const val DEFAULT_PACKAGE_PER_HOUR_FARE = 350.0
        const val DEFAULT_PACKAGE_PER_KM_FARE = 25.0
    }

    fun toggleSystemOverlay(context: Context, enable: Boolean) {
        _isOverlayEnabled.value = false
        prefs.edit().putBoolean("is_overlay_enabled", false).apply()
    }

    fun clearTripHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}

class TaxiMeterViewModelFactory(private val repository: TripRepository, private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaxiMeterViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TaxiMeterViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
