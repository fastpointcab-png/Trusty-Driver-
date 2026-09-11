package com.trustyyellowcabs.driver.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.Ringtone
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.trustyyellowcabs.driver.MainActivity
import com.trustyyellowcabs.driver.network.FirebaseManager
import com.trustyyellowcabs.driver.network.FirestoreTrip
import com.trustyyellowcabs.driver.util.AlertSoundPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object TaxiDispatchServiceState {
    private val _isOnline = MutableStateFlow(false)
    val isOnline = _isOnline.asStateFlow()

    private val _openTrips = MutableStateFlow<List<FirestoreTrip>>(emptyList())
    val openTrips = _openTrips.asStateFlow()

    private val _activeTrip = MutableStateFlow<FirestoreTrip?>(null)
    val activeTrip = _activeTrip.asStateFlow()

    private val _latestTripAlert = MutableStateFlow<FirestoreTrip?>(null)
    val latestTripAlert = _latestTripAlert.asStateFlow()

    private val _ignoredTrips = MutableStateFlow<Map<String, Long>>(emptyMap())
    val ignoredTrips = _ignoredTrips.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(false)
    val isAppInForeground = _isAppInForeground.asStateFlow()

    fun setAppInForeground(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    fun setOnline(online: Boolean) {
        _isOnline.value = online
    }

    fun setOpenTrips(list: List<FirestoreTrip>) {
        _openTrips.value = list
    }

    fun setActiveTrip(trip: FirestoreTrip?) {
        _activeTrip.value = trip
    }

    fun triggerNewTripAlert(trip: FirestoreTrip?) {
        _latestTripAlert.value = trip
    }

    fun ignoreTrip(tripId: String) {
        val updated = _ignoredTrips.value.toMutableMap()
        updated[tripId] = System.currentTimeMillis()
        _ignoredTrips.value = updated
    }
}

class TaxiDispatchService : Service() {
    companion object {
        const val TAG = "TaxiDispatchService"
        const val CHANNEL_ID = "TaxiDispatchAlertChannel_v5"
        const val SERVICE_CHANNEL_ID = "TaxiDispatchServiceChannel"
        const val ALERT_CHANNEL_ID = "TaxiDispatchAlertChannel_v5"
        const val NOTIFICATION_ID = 2002
        const val Notification_ID_Alert = 3003

        var activeInstance: TaxiDispatchService? = null

        fun startService(context: Context) {
            TaxiDispatchServiceState.setOnline(true)
            val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_dispatch_online", true).apply()
            val intent = Intent(context, TaxiDispatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            TaxiDispatchServiceState.setOnline(false)
            val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
            val driverId = prefs.getString("unique_driver_id", "") ?: ""
            prefs.edit().putBoolean("is_dispatch_online", false).apply()

            CoroutineScope(Dispatchers.IO).launch {
                FirebaseManager.setDriverOnlineStatus(context.applicationContext, isOnline = false, targetDriverId = driverId)
            }

            val intent = Intent(context, TaxiDispatchService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var locationManager: LocationManager? = null
    private var isGpsListening = false
    var lastKnownLocation: Location? = null

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            Log.d(TAG, "Driver location updated: ${location.latitude}, ${location.longitude}")
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        
        // Initialize vibrator
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting TaxiDispatchService (Online mode)...")
        
        // Register foreground notification immediately to satisfy OS requirements
        val notification = createServiceNotification("Trusty Yellow Cab Online", "Duty active • Awaiting trips...")
        startForeground(NOTIFICATION_ID, notification)

        // Acquire persistent CPU wake lock to survive screen sleep, locks, calls, background states
        try {
            if (cpuWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                cpuWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "TrustyYellowCab:DispatchCpuWakeLock"
                )?.apply {
                    acquire()
                }
                Log.d(TAG, "Dispatch CPU WakeLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed acquiring cpuWakeLock", e)
        }

        TaxiDispatchServiceState.setOnline(true)

        // Write online status ONCE when driver goes online (Requirement 6)
        serviceScope.launch(Dispatchers.IO) {
            val lat = lastKnownLocation?.latitude
            val lng = lastKnownLocation?.longitude
            FirebaseManager.setDriverOnlineStatus(applicationContext, isOnline = true, lat = lat, lng = lng)
        }

        // 1. Start GPS location listening & Firestore driver coordinates push loop (every 30s)
        startGpsListening()
        startCoordinatesUploadLoop()

        // 2. Observe active assigned trip to manage single document listener (Requirement 1 & 2)
        startActiveTripObservation()

        // 3. Start Instant Realtime Trips Listener for incoming trips
        startTripsRealtimeListener()

        // 4. Initial database pull to view current OPEN trips
        pullOpenTripsSilently()

        // 5. Periodic poll fallback to verify trip states
        startPeriodicTripPoller()

        // 6. Observe open trips flow to stop loud alerts if the ringing trip is removed or closed
        startOpenTripsObserver()

        return START_STICKY
    }

    private fun startGpsListening() {
        if (isGpsListening) return

        // Explicitly check for location permissions before requesting updates
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start GPS listening: location permissions not granted")
            return
        }

        try {
            locationManager?.let { mgr ->
                var registered = false
                // Register for both GPS and network to ensure high availability (30s interval)
                if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    mgr.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000L, 2f, locationListener)
                    registered = true
                }
                if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    mgr.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000L, 2f, locationListener)
                    registered = true
                }
                lastKnownLocation = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER) 
                    ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                isGpsListening = registered
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location tracking registers", e)
        }
    }

    private fun startCoordinatesUploadLoop() {
        serviceScope.launch {
            var loopCount = 0
            while (isActive) {
                if (FirebaseManager.isBackendSyncEnabled.value && !FirebaseManager.isSyncCutoff(applicationContext)) {
                    val lat = lastKnownLocation?.latitude
                    val lng = lastKnownLocation?.longitude
                    // Safely update only live location and presence without wiping profile fields
                    FirebaseManager.updateDriverLocationAndPresence(applicationContext, lat, lng, isOnline = true)
                } else {
                    Log.d(TAG, "Backend admin cutoff active: skipping driver GPS coordinates push to backend.")
                }

                loopCount++
                // Every ~60 seconds (every 3 iterations), sweep stale uninstalled drivers in Firestore
                if (loopCount % 3 == 0) {
                    launch(Dispatchers.IO) {
                        FirebaseManager.cleanStaleOfflineDrivers(applicationContext)
                    }
                }

                delay(20000) // update presence & heartbeat every 20 seconds
            }
        }
    }

    /**
     * Complete eligibility verification: Status == OPEN, Vehicle Category matches, and Pickup within Radius.
     */
    fun isTripEligibleForThisDriver(trip: FirestoreTrip): Boolean {
        if (trip.status != "OPEN") return false

        // 1. Vehicle category mapping
        val prefs = getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        val driverCat = prefs.getString("vehicle_category", "Mini") ?: "Mini"
        val categoryEligible = FirebaseManager.isVehicleCategoryEligible(trip.vehicle_category, driverCat)
        if (!categoryEligible) {
            Log.d(TAG, "Trip ${trip.trip_id} (${trip.vehicle_category}) filtered out for Driver Category ($driverCat)")
            return false
        }

        // 2. Radius calculation
        val radiusEligible = isTripWithinRadius(trip)
        if (!radiusEligible) {
            Log.d(TAG, "Trip ${trip.trip_id} filtered out by radius: ${trip.radius_kms} km")
            return false
        }

        return true
    }

    private fun isTripWithinRadius(trip: FirestoreTrip): Boolean {
        return FirebaseManager.isTripWithinRadius(
            trip = trip,
            driverLat = lastKnownLocation?.latitude,
            driverLng = lastKnownLocation?.longitude
        )
    }

    fun forceRefreshTrips() {
        serviceScope.launch {
            pullOpenTripsSilently()
        }
    }

    private fun startActiveTripObservation() {
        serviceScope.launch {
            TaxiDispatchServiceState.activeTrip.collect { active ->
                if (active != null && active.doc_id.isNotBlank()) {
                    Log.i(TAG, "Active assigned trip detected (${active.doc_id}). Attaching single trip document listener...")
                    FirebaseManager.listenToAssignedTrip(applicationContext, active.doc_id) { updatedTrip, reason ->
                        if (updatedTrip == null) {
                            Log.i(TAG, "Assigned trip was cancelled/completed/unassigned on backend (reason=$reason). Clearing view immediately.")
                            TaxiDispatchServiceState.setActiveTrip(null)
                            stopLoudAlert()
                            if (reason != "COMPLETED") {
                                AlertSoundPlayer.playTripCancelledAlert(applicationContext)
                            }
                        } else {
                            TaxiDispatchServiceState.setActiveTrip(updatedTrip)
                        }
                    }
                } else {
                    FirebaseManager.stopListeningToAssignedTrip()
                }
            }
        }
    }

    private fun startOpenTripsObserver() {
        serviceScope.launch {
            TaxiDispatchServiceState.openTrips.collect { openTrips ->
                val currentAlert = TaxiDispatchServiceState.latestTripAlert.value
                if (currentAlert != null) {
                    val stillOpen = openTrips.any { it.doc_id == currentAlert.doc_id || it.trip_id == currentAlert.trip_id }
                    if (!stillOpen) {
                        Log.d(TAG, "OpenTripsObserver: Current alerting trip ${currentAlert.trip_id} is no longer in open list. Stopping loud alert!")
                        stopLoudAlert()
                    }
                }
            }
        }
    }

    private fun startTripsRealtimeListener() {
        Log.i(TAG, "⚡ Initializing instant realtime snapshot listener for incoming trips...")
        FirebaseManager.startListeningToRealtime(applicationContext) { updatedTrips ->
            serviceScope.launch {
                handleIncomingTripsUpdate(updatedTrips)
            }
        }
    }

    private fun handleIncomingTripsUpdate(allTrips: List<FirestoreTrip>) {
        if (!FirebaseManager.isBackendSyncEnabled.value || FirebaseManager.isSyncCutoff(applicationContext)) {
            TaxiDispatchServiceState.setOpenTrips(emptyList())
            TaxiDispatchServiceState.triggerNewTripAlert(null)
            stopLoudAlert()
            return
        }

        val previousActiveTrip = TaxiDispatchServiceState.activeTrip.value
        val myId = FirebaseManager.getDriverId(applicationContext).trim()
        val activeOne = allTrips.find { 
            (it.status == "ACCEPTED" || it.status == "IN_PROGRESS") && 
            it.driver_id?.trim()?.equals(myId, ignoreCase = true) == true 
        }

        if (activeOne != null) {
            TaxiDispatchServiceState.setActiveTrip(activeOne)
        } else {
            val meterStatus = com.trustyyellowcabs.driver.service.TaxiMeterService.tripState.value.status
            val meterRunning = meterStatus == com.trustyyellowcabs.driver.service.TripStatus.RUNNING ||
                               meterStatus == com.trustyyellowcabs.driver.service.TripStatus.PAUSED
            if (!meterRunning) {
                if (previousActiveTrip != null) {
                    val prevKey = previousActiveTrip.doc_id.ifBlank { previousActiveTrip.trip_id }
                    val matchInAll = allTrips.find { (it.doc_id.ifBlank { it.trip_id }) == prevKey }
                    val isUnassignedOrCancelled = matchInAll == null ||
                            matchInAll.status in listOf("CANCELLED", "UNASSIGNED", "REJECTED") ||
                            (matchInAll.driver_id?.trim()?.equals(myId, ignoreCase = true) != true)
                    if (isUnassignedOrCancelled && matchInAll?.status != "COMPLETED") {
                        Log.i(TAG, "Active trip was unassigned or cancelled in realtime update. Playing cancel sound.")
                        AlertSoundPlayer.playTripCancelledAlert(applicationContext)
                    }
                }
                TaxiDispatchServiceState.setActiveTrip(null)
            }
        }

        val ongoingDisp = activeOne != null || TaxiDispatchServiceState.activeTrip.value != null
        val meterStatus = com.trustyyellowcabs.driver.service.TaxiMeterService.tripState.value.status
        val meterRunning = meterStatus == com.trustyyellowcabs.driver.service.TripStatus.RUNNING ||
                           meterStatus == com.trustyyellowcabs.driver.service.TripStatus.PAUSED
        val isOngoing = ongoingDisp || meterRunning

        if (isOngoing) {
            TaxiDispatchServiceState.setOpenTrips(emptyList())
            TaxiDispatchServiceState.triggerNewTripAlert(null)
            stopLoudAlert()
        } else {
            val eligibleOpenTrips = allTrips.filter { isTripEligibleForThisDriver(it) }
            val previousOpenTrips = TaxiDispatchServiceState.openTrips.value
            val previousKeys = previousOpenTrips.map { it.doc_id.ifBlank { it.trip_id } }.toSet()

            // Update open trips list instantly so screen displays the trip card with ZERO delay!
            TaxiDispatchServiceState.setOpenTrips(eligibleOpenTrips)

            // Check if there is a newly posted eligible trip
            val newlyArrivedTrip = eligibleOpenTrips.firstOrNull { trip ->
                val key = trip.doc_id.ifBlank { trip.trip_id }
                !previousKeys.contains(key)
            }

            if (newlyArrivedTrip != null) {
                val ignoredTime = TaxiDispatchServiceState.ignoredTrips.value[newlyArrivedTrip.doc_id.ifBlank { newlyArrivedTrip.trip_id }] ?: 0L
                if ((System.currentTimeMillis() - ignoredTime) >= 60000L) {
                    Log.i(TAG, "⚡ INSTANT DISPATCH ALERT: Newly posted trip arrived: ${newlyArrivedTrip.trip_id}")
                    triggerLoudAlert(newlyArrivedTrip)
                }
            } else {
                val currentAlert = TaxiDispatchServiceState.latestTripAlert.value
                if (currentAlert != null) {
                    val stillOpen = eligibleOpenTrips.any { it.doc_id == currentAlert.doc_id || it.trip_id == currentAlert.trip_id }
                    if (!stillOpen) {
                        Log.d(TAG, "Alerting trip ${currentAlert.trip_id} is no longer open in Firestore. Stopping alert.")
                        stopLoudAlert()
                    }
                } else if (eligibleOpenTrips.isNotEmpty()) {
                    // Check if there's any eligible trip whose ignore timeout has passed
                    val tripToAlert = eligibleOpenTrips.firstOrNull { trip ->
                        val key = trip.doc_id.ifBlank { trip.trip_id }
                        val ignoredTime = TaxiDispatchServiceState.ignoredTrips.value[key] ?: 0L
                        (System.currentTimeMillis() - ignoredTime) >= 60000L
                    }
                    if (tripToAlert != null && !AlertSoundPlayer.isPlaying()) {
                        triggerLoudAlert(tripToAlert)
                    }
                }
            }
        }
    }

    fun pullOpenTripsSilently() {
        serviceScope.launch {
            if (!FirebaseManager.isBackendSyncEnabled.value || FirebaseManager.isSyncCutoff(applicationContext)) {
                TaxiDispatchServiceState.setOpenTrips(emptyList())
                return@launch
            }
            val allTrips = FirebaseManager.fetchAllTrips(applicationContext)
            handleIncomingTripsUpdate(allTrips)
        }
    }

    fun triggerLoudAlert(trip: FirestoreTrip) {
        // Stop any previous playing alerts
        stopLoudAlert()

        // 1. Play custom MP3 sound alert (looping)
        try {
            AlertSoundPlayer.playTripAlert(applicationContext, loop = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing custom MP3 alert sound", e)
        }

        // 2. Alert vibration pulse pattern
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 450, 200, 450), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 450, 200, 450), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed executing vibration", e)
        }

        // 3. Wake screen so driver notices incoming trip immediately even if screen is off or locked
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "TrustyYellowCab:TripAlertWakeLock"
            )?.apply {
                acquire(20000L) // 20 seconds
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire screen wakeLock: ${e.message}")
        }

        // 4. Update memory flow to display stylish in-app alert to user instantly
        TaxiDispatchServiceState.triggerNewTripAlert(trip)

        // 5. Send Android System Heads-Up Notification immediately (both foreground and background)
        sendHeadsUpNotification(trip)
    }

    fun stopLoudAlert() {
        try {
            AlertSoundPlayer.stopTripAlert()
            ringtone?.stop()
            ringtone = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio alert", e)
        }

        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(Notification_ID_Alert)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling alert notification", e)
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration", e)
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakeLock", e)
        }

        TaxiDispatchServiceState.triggerNewTripAlert(null)
    }

    private fun sendHeadsUpNotification(trip: FirestoreTrip) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_DISPATCH_ALERT", true)
            putExtra("TRIP_ID", trip.trip_id)
            putExtra("DOC_ID", trip.doc_id)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 101, clickIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (trip.estimated_fare > 0.0) "NEW TRIP: ₹${trip.estimated_fare.toInt()}" else ""
        val fareDetail = if (trip.estimated_fare > 0.0) "\nEstimated Fare: ₹${trip.estimated_fare.toInt()}" else ""
        val categoryText = if (!trip.vehicle_category.isNullOrBlank()) "[${trip.vehicle_category}] " else ""

        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(com.trustyyellowcabs.driver.R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("$categoryText${trip.pickup_location.take(28)} ➔ ${trip.drop_location.take(28)}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("New Trip Available!\n\nPickup: ${trip.pickup_location}\nDrop: ${trip.drop_location}$fareDetail\nVehicle: ${trip.vehicle_category}"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(
                android.R.drawable.ic_menu_view,
                "VIEW TRIP",
                pendingIntent
            )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager?.notify(Notification_ID_Alert, builder.build())
        }
    }

    private fun startPeriodicTripPoller() {
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(15000) // Fallback check every 15 seconds
                try {
                    val ongoingDisp = TaxiDispatchServiceState.activeTrip.value != null
                    val meterStatus = com.trustyyellowcabs.driver.service.TaxiMeterService.tripState.value.status
                    val meterRunning = meterStatus == com.trustyyellowcabs.driver.service.TripStatus.RUNNING || meterStatus == com.trustyyellowcabs.driver.service.TripStatus.PAUSED
                    val isOngoing = ongoingDisp || meterRunning

                    if (!isOngoing) {
                        if (!FirebaseManager.isBackendSyncEnabled.value || FirebaseManager.isSyncCutoff(applicationContext)) {
                            // Backend Cutoff: do not query Firestore trips or trigger alerts
                            TaxiDispatchServiceState.setOpenTrips(emptyList())
                            TaxiDispatchServiceState.triggerNewTripAlert(null)
                            stopLoudAlert()
                            continue
                        }

                        // Driver is available for new trips! Fetch all trips from Firestore once as fallback
                        val allTrips = FirebaseManager.fetchAllTrips(applicationContext)
                        handleIncomingTripsUpdate(allTrips)
                    } else {
                        // Squelch any active alerts and clear open trips list if driver has an ongoing accepted trip or meter running
                        TaxiDispatchServiceState.setOpenTrips(emptyList())
                        TaxiDispatchServiceState.triggerNewTripAlert(null)
                        stopLoudAlert()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Trip poller waiting for network: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Destroying TaxiDispatchService (OFFLINE mode)...")
        if (activeInstance == this) {
            activeInstance = null
        }
        stopLoudAlert()

        // 1. Offload GPS position coordinates updates
        if (isGpsListening) {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing location registration on destroy", e)
            } finally {
                isGpsListening = false
            }
        }

        // 2. Shut down background active scopes
        serviceJob.cancel()

        // Update local state immediately
        TaxiDispatchServiceState.setOnline(false)
        val prefs = getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_dispatch_online", false).apply()

        // 3. Set driver offline in database to hide from map & unsubscribe listeners (Requirement 2 & 6)
        val driverId = prefs.getString("unique_driver_id", "") ?: ""
        runBlocking {
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    withTimeoutOrNull(3000L) {
                        FirebaseManager.setDriverOnlineStatus(applicationContext, isOnline = false, targetDriverId = driverId)
                        FirebaseManager.stopListeningToAssignedTrip()
                        FirebaseManager.stopListeningToRealtime()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting offline status on service destroy: ${e.message}")
                }
            }
        }

        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
            }
            cpuWakeLock = null
            Log.d(TAG, "Dispatch CPU WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing cpuWakeLock", e)
        }

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved called in TaxiDispatchService")
        // Only reschedule if the driver is actively supposed to be online
        val prefs = getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        val isSupposedToBeOnline = TaxiDispatchServiceState.isOnline.value && prefs.getBoolean("is_dispatch_online", false)
        if (isSupposedToBeOnline) {
            val restartServiceIntent = Intent(applicationContext, TaxiDispatchService::class.java).apply {
                `package` = packageName
            }
            val restartServicePendingIntent = PendingIntent.getService(
                this, 2, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmService?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        } else {
            // Driver is offline: ensure backend has offline status set
            val driverId = prefs.getString("unique_driver_id", "") ?: ""
            CoroutineScope(Dispatchers.IO).launch {
                FirebaseManager.setDriverOnlineStatus(applicationContext, isOnline = false, targetDriverId = driverId)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createServiceNotification(title: String, text: String): Notification {
        val clickIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 100, clickIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.trustyyellowcabs.driver.R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            // 1. Channel for background foreground duty service
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Driver Duty Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent indicator showing driver online duty status."
                setShowBadge(false)
            }
            manager?.createNotificationChannel(serviceChannel)

            // 2. High-priority instant alert channel for incoming rides
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "New Trip Dispatch Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority instant alerts and heads-up banners for passenger trip bookings."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 200, 450)
                enableLights(true)
                lightColor = android.graphics.Color.YELLOW
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            manager?.createNotificationChannel(alertChannel)
        }
    }
}
