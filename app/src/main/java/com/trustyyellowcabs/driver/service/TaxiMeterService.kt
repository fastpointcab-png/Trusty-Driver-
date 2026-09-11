package com.trustyyellowcabs.driver.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.os.SystemClock
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import java.util.Locale
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationAvailability
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.core.app.NotificationCompat
import com.trustyyellowcabs.driver.MainActivity
import com.trustyyellowcabs.driver.util.GeocoderHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class TripStatus {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED
}

data class LiveTripState(
    val status: TripStatus = TripStatus.IDLE,
    val driverName: String = "",
    val vehicleNumber: String = "",
    val vehicleCategory: String = "Mini",
    val vehicleModel: String = "",
    val startTime: Long = 0,
    val endTime: Long = 0,
    val distanceKm: Double = 0.0,
    val durationSeconds: Long = 0,
    val waitingSeconds: Long = 0,
    val currentFare: Double = 0.0,
    val speedKmH: Double = 0.0,
    
    // Settings applied
    val baseFare: Double = 0.0,
    val perKmFare: Double = 0.0,
    val waitingChargePerMin: Double = 0.0,
    val minimumFare: Double = 0.0,
    val nightChargePercent: Double = 0.0,
    val isNightMode: Boolean = false,
    
    // Package meter fields
    val isPackageMeter: Boolean = false,
    val packageName: String = "",
    val packageBaseFare: Double = 0.0,
    val includedKm: Double = 0.0,
    val includedMinutes: Int = 0,
    val extraKmRate: Double = 0.0,
    val extraTimeRate: Double = 0.0,
    val packageWaitingChargePerMin: Double = 0.0,
    val packagePerHourRate: Double = 0.0,
    val packagePerKmRate: Double = 0.0,
    
    // Overlay enabled state
    val isOverlayActive: Boolean = false,

    // Location recording state
    val startLocation: String = "Awaiting GPS...",
    val endLocation: String = "Awaiting GPS...",
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,

    // NEW PRODUCTION ENGINE FIELDS
    val isMockGps: Boolean = false,
    val gpsSignalStatus: String = "EXCELLENT", // "EXCELLENT", "GOOD", "POOR", "NO_SIGNAL"
    val isStationary: Boolean = false,
    val routePathPoints: String = ""
)

class TaxiMeterService : Service() {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var isFusedLocationListening = false
    private var lastLocation: Location? = null
    private var timerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    private var startLocationGeocoded = false
    private var lastEndGeocodeTime = 0L

    private val recentLocations = java.util.Collections.synchronizedList(mutableListOf<Location>())

    // Advanced modular GPS engine classes
    private val locationValidator = LocationValidator()
    private val kalmanFilter = KalmanFilter()
    private val motionDetector = MotionDetector()
    private val distanceEngine = DistanceEngine()
    private val waitingDetector = WaitingDetector()
    private var currentIntervalMillis = 1000L
    private var continuousStationarySeconds = 0L

    private var lastSmoothedLat: Double? = null
    private var lastSmoothedLng: Double? = null
    private var lastSmoothedAccuracy: Double? = null

    private fun updateState(newState: LiveTripState) {
        _tripState.value = newState
        saveTripState(this, newState)
    }

    private fun checkIfStationary(): Boolean {
        val lastLoc = lastLocation ?: return true
        val currentSpeedKmH = if (lastLoc.hasSpeed()) lastLoc.speed * 3.6 else 0.0
        
        if (_tripState.value.isStationary) {
            return true
        }

        val timeSinceLastUpdateMs = System.currentTimeMillis() - lastLoc.time
        if (timeSinceLastUpdateMs > 10000L) {
            return true
        }

        return currentSpeedKmH < 1.2
    }

    private fun adjustUpdateInterval(speedKmH: Double) {
        val targetInterval = when {
            speedKmH < 1.0 -> 5000L // Stopped
            speedKmH < 15.0 -> 2000L // Slow
            speedKmH < 60.0 -> 1000L // Driving
            else -> 500L // Highway
        }

        if (targetInterval != currentIntervalMillis) {
            currentIntervalMillis = targetInterval
            serviceScope.launch(Dispatchers.Main) {
                try {
                    val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, targetInterval).apply {
                        setMinUpdateIntervalMillis(targetInterval / 2)
                        setMinUpdateDistanceMeters(0.5f)
                    }.build()
                    if (isFusedLocationListening) {
                        locationCallback?.let {
                            fusedLocationClient?.requestLocationUpdates(locationRequest, it, Looper.getMainLooper())
                            Log.d("TaxiMeterService", "Adapted GPS update interval to ${targetInterval}ms for speed ${speedKmH}km/h")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TaxiMeterService", "Error adapting GPS update interval: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "TrustyYellowCabChannel"
        private const val NOTIFICATION_ID = 1011

        private val _tripState = MutableStateFlow(LiveTripState())
        val tripState = _tripState.asStateFlow()

        fun saveTripState(context: Context, state: LiveTripState) {
            val prefs = context.getSharedPreferences("ActiveTripPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("status", state.status.name)
                putString("driverName", state.driverName)
                putString("vehicleNumber", state.vehicleNumber)
                putString("vehicleCategory", state.vehicleCategory)
                putString("vehicleModel", state.vehicleModel)
                putLong("startTime", state.startTime)
                putLong("endTime", state.endTime)
                putFloat("distanceKm", state.distanceKm.toFloat())
                putLong("durationSeconds", state.durationSeconds)
                putLong("waitingSeconds", state.waitingSeconds)
                putFloat("currentFare", state.currentFare.toFloat())
                putFloat("speedKmH", state.speedKmH.toFloat())
                
                putFloat("baseFare", state.baseFare.toFloat())
                putFloat("perKmFare", state.perKmFare.toFloat())
                putFloat("waitingChargePerMin", state.waitingChargePerMin.toFloat())
                putFloat("minimumFare", state.minimumFare.toFloat())
                putFloat("nightChargePercent", state.nightChargePercent.toFloat())
                putBoolean("isNightMode", state.isNightMode)
                
                putBoolean("isPackageMeter", state.isPackageMeter)
                putString("packageName", state.packageName)
                putFloat("packageBaseFare", state.packageBaseFare.toFloat())
                putFloat("includedKm", state.includedKm.toFloat())
                putInt("includedMinutes", state.includedMinutes)
                putFloat("extraKmRate", state.extraKmRate.toFloat())
                putFloat("extraTimeRate", state.extraTimeRate.toFloat())
                putFloat("packageWaitingChargePerMin", state.packageWaitingChargePerMin.toFloat())
                putFloat("packagePerHourRate", state.packagePerHourRate.toFloat())
                putFloat("packagePerKmRate", state.packagePerKmRate.toFloat())
                
                putBoolean("isOverlayActive", state.isOverlayActive)
                putString("startLocation", state.startLocation)
                putString("endLocation", state.endLocation)
                
                if (state.startLatitude != null) putFloat("startLatitude", state.startLatitude.toFloat()) else remove("startLatitude")
                if (state.startLongitude != null) putFloat("startLongitude", state.startLongitude.toFloat()) else remove("startLongitude")
                if (state.endLatitude != null) putFloat("endLatitude", state.endLatitude.toFloat()) else remove("endLatitude")
                if (state.endLongitude != null) putFloat("endLongitude", state.endLongitude.toFloat()) else remove("endLongitude")
                
                putBoolean("isMockGps", state.isMockGps)
                putString("gpsSignalStatus", state.gpsSignalStatus)
                putBoolean("isStationary", state.isStationary)
                putString("routePathPoints", state.routePathPoints)
                
                apply()
            }
        }

        fun loadTripState(context: Context): LiveTripState? {
            val prefs = context.getSharedPreferences("ActiveTripPrefs", Context.MODE_PRIVATE)
            val statusStr = prefs.getString("status", null) ?: return null
            val status = try { TripStatus.valueOf(statusStr) } catch(e: Exception) { TripStatus.IDLE }
            if (status == TripStatus.IDLE || status == TripStatus.FINISHED) return null
            
            val startLat = if (prefs.contains("startLatitude")) prefs.getFloat("startLatitude", 0f).toDouble() else null
            val startLng = if (prefs.contains("startLongitude")) prefs.getFloat("startLongitude", 0f).toDouble() else null
            val endLat = if (prefs.contains("endLatitude")) prefs.getFloat("endLatitude", 0f).toDouble() else null
            val endLng = if (prefs.contains("endLongitude")) prefs.getFloat("endLongitude", 0f).toDouble() else null

            return LiveTripState(
                status = status,
                driverName = prefs.getString("driverName", "") ?: "",
                vehicleNumber = prefs.getString("vehicleNumber", "") ?: "",
                vehicleCategory = prefs.getString("vehicleCategory", "Mini") ?: "Mini",
                vehicleModel = prefs.getString("vehicleModel", "") ?: "",
                startTime = prefs.getLong("startTime", 0L),
                endTime = prefs.getLong("endTime", 0L),
                distanceKm = prefs.getFloat("distanceKm", 0f).toDouble(),
                durationSeconds = prefs.getLong("durationSeconds", 0L),
                waitingSeconds = prefs.getLong("waitingSeconds", 0L),
                currentFare = prefs.getFloat("currentFare", 0f).toDouble(),
                speedKmH = prefs.getFloat("speedKmH", 0f).toDouble(),
                baseFare = prefs.getFloat("baseFare", 0f).toDouble(),
                perKmFare = prefs.getFloat("perKmFare", 0f).toDouble(),
                waitingChargePerMin = prefs.getFloat("waitingChargePerMin", 0f).toDouble(),
                minimumFare = prefs.getFloat("minimumFare", 0f).toDouble(),
                nightChargePercent = prefs.getFloat("nightChargePercent", 0f).toDouble(),
                isNightMode = prefs.getBoolean("isNightMode", false),
                isPackageMeter = prefs.getBoolean("isPackageMeter", false),
                packageName = prefs.getString("packageName", "") ?: "",
                packageBaseFare = prefs.getFloat("packageBaseFare", 0f).toDouble(),
                includedKm = prefs.getFloat("includedKm", 0f).toDouble(),
                includedMinutes = prefs.getInt("includedMinutes", 0),
                extraKmRate = prefs.getFloat("extraKmRate", 0f).toDouble(),
                extraTimeRate = prefs.getFloat("extraTimeRate", 0f).toDouble(),
                packageWaitingChargePerMin = prefs.getFloat("packageWaitingChargePerMin", 0f).toDouble(),
                packagePerHourRate = prefs.getFloat("packagePerHourRate", 0f).toDouble(),
                packagePerKmRate = prefs.getFloat("packagePerKmRate", 0f).toDouble(),
                isOverlayActive = prefs.getBoolean("isOverlayActive", false),
                startLocation = prefs.getString("startLocation", "Awaiting GPS...") ?: "Awaiting GPS...",
                endLocation = prefs.getString("endLocation", "Awaiting GPS...") ?: "Awaiting GPS...",
                startLatitude = startLat,
                startLongitude = startLng,
                endLatitude = endLat,
                endLongitude = endLng,
                isMockGps = prefs.getBoolean("isMockGps", false),
                gpsSignalStatus = prefs.getString("gpsSignalStatus", "EXCELLENT") ?: "EXCELLENT",
                isStationary = prefs.getBoolean("isStationary", false),
                routePathPoints = prefs.getString("routePathPoints", "") ?: ""
            )
        }

        fun clearSavedTripState(context: Context) {
            val prefs = context.getSharedPreferences("ActiveTripPrefs", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        }

        fun saveLastValidLocation(context: Context, location: Location?) {
            val prefs = context.getSharedPreferences("ActiveTripPrefs", Context.MODE_PRIVATE)
            if (location != null) {
                prefs.edit().apply {
                    putLong("last_valid_lat_bits", java.lang.Double.doubleToRawLongBits(location.latitude))
                    putLong("last_valid_lng_bits", java.lang.Double.doubleToRawLongBits(location.longitude))
                    putFloat("last_valid_acc", location.accuracy)
                    putLong("last_valid_time", location.time)
                    apply()
                }
            } else {
                prefs.edit().apply {
                    remove("last_valid_lat_bits")
                    remove("last_valid_lng_bits")
                    remove("last_valid_acc")
                    remove("last_valid_time")
                    apply()
                }
            }
        }

        fun loadLastValidLocation(context: Context): Location? {
            val prefs = context.getSharedPreferences("ActiveTripPrefs", Context.MODE_PRIVATE)
            val latBits = prefs.getLong("last_valid_lat_bits", 0L)
            val lngBits = prefs.getLong("last_valid_lng_bits", 0L)
            val lastTime = prefs.getLong("last_valid_time", 0L)
            val lastAcc = prefs.getFloat("last_valid_acc", 0f)
            if (latBits != 0L && lngBits != 0L && lastTime != 0L) {
                return Location("FusedLocation").apply {
                    latitude = java.lang.Double.longBitsToDouble(latBits)
                    longitude = java.lang.Double.longBitsToDouble(lngBits)
                    accuracy = lastAcc
                    time = lastTime
                }
            }
            return null
        }

        // Control triggers
        fun startTrip(
            context: Context,
            driverName: String,
            vehicleNumber: String,
            category: String,
            model: String,
            baseFare: Double,
            perKmFare: Double,
            waitingCharge: Double,
            minFare: Double,
            nightPercent: Double,
            isNightMode: Boolean,
            isOverlayActive: Boolean,
            isPackageMeter: Boolean = false,
            packageName: String = "",
            packageBaseFare: Double = 0.0,
            includedKm: Double = 0.0,
            includedMinutes: Int = 0,
            extraKmRate: Double = 0.0,
            extraTimeRate: Double = 0.0,
            packageWaitingChargePerMin: Double = 0.0,
            packagePerHourRate: Double = 0.0,
            packagePerKmRate: Double = 0.0
        ) {
            val newState = LiveTripState(
                status = TripStatus.RUNNING,
                driverName = driverName,
                vehicleNumber = vehicleNumber,
                vehicleCategory = category,
                vehicleModel = model,
                startTime = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getCurrentTimeMillis(),
                baseFare = baseFare,
                perKmFare = perKmFare,
                waitingChargePerMin = if (!isPackageMeter && (waitingCharge <= 0.0 || kotlin.math.abs(waitingCharge - 0.5) < 0.001)) 1.0 else waitingCharge,
                minimumFare = minFare,
                nightChargePercent = nightPercent,
                isNightMode = isNightMode,
                currentFare = if (isPackageMeter) 0.0 else (if (isNightMode) (baseFare + baseFare * (nightPercent / 100.0)) else baseFare),
                isOverlayActive = isOverlayActive,
                isPackageMeter = isPackageMeter,
                packageName = packageName,
                packageBaseFare = packageBaseFare,
                includedKm = includedKm,
                includedMinutes = includedMinutes,
                extraKmRate = extraKmRate,
                extraTimeRate = extraTimeRate,
                packageWaitingChargePerMin = packageWaitingChargePerMin,
                packagePerHourRate = packagePerHourRate,
                packagePerKmRate = packagePerKmRate
            )
            _tripState.value = newState
            saveTripState(context, newState)
            saveLastValidLocation(context, null)

            val serviceIntent = Intent(context, TaxiMeterService::class.java).apply {
                action = "START_TRIP"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun pauseTrip(context: Context) {
            val newState = _tripState.value.copy(status = TripStatus.PAUSED)
            _tripState.value = newState
            saveTripState(context, newState)
            val serviceIntent = Intent(context, TaxiMeterService::class.java).apply {
                action = "PAUSE_TRIP"
            }
            context.startService(serviceIntent)
        }

        fun resumeTrip(context: Context) {
            val newState = _tripState.value.copy(status = TripStatus.RUNNING)
            _tripState.value = newState
            saveTripState(context, newState)
            val serviceIntent = Intent(context, TaxiMeterService::class.java).apply {
                action = "RESUME_TRIP"
            }
            context.startService(serviceIntent)
        }

        fun endTrip(context: Context) {
            val newState = _tripState.value.copy(
                status = TripStatus.FINISHED,
                endTime = System.currentTimeMillis()
            )
            _tripState.value = newState
            clearSavedTripState(context)
            val serviceIntent = Intent(context, TaxiMeterService::class.java).apply {
                action = "END_TRIP"
            }
            context.startService(serviceIntent)
        }

        fun toggleOverlay(context: Context, enable: Boolean) {
            // Draggable floating widget overlay removed per configuration
        }

        fun resetMeter() {
            _tripState.value = LiveTripState()
        }

        fun formatDuration(seconds: Long): String {
            val hrs = seconds / 3600
            val mins = (seconds % 3600) / 60
            val secs = seconds % 60
            return String.format("%02d:%02d:%02d", hrs, mins, secs)
        }

        fun formatCoordinates(lat: Double, lng: Double): String {
            val latDir = if (lat >= 0) "N" else "S"
            val lngDir = if (lng >= 0) "E" else "W"
            return String.format(Locale.ROOT, "%.4f° %s, %.4f° %s", Math.abs(lat), latDir, Math.abs(lng), lngDir)
        }
    }

    private fun processLocationUpdate(location: Location) {
        if (_tripState.value.status != TripStatus.RUNNING) return

        val isMock = locationValidator.isMockLocation(location)
        val lastLoc = lastLocation
        val timeDeltaMs = if (lastLoc != null) location.time - lastLoc.time else 1000L
        val timeDeltaSecs = timeDeltaMs / 1000.0
        val signalStatus = LocationConfidence.getSignalStatus(location.accuracy, timeDeltaSecs)

        // Keep local copy of current state
        val currentState = _tripState.value

        if (!locationValidator.isValid(location, lastLoc)) {
            // Update signal and mock GPS state even if point is invalid
            val newState = currentState.copy(
                isMockGps = isMock,
                gpsSignalStatus = signalStatus
            )
            updateState(newState)
            return
        }

        // Apply Kalman filter smoothing
        val (smoothedLat, smoothedLng) = kalmanFilter.process(
            location.latitude,
            location.longitude,
            location.accuracy.toDouble(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) location.elapsedRealtimeNanos else System.nanoTime()
        )

        // Retrieve raw hardware or calculated speed
        var speedKmHVal = if (location.hasSpeed()) location.speed.toDouble() * 3.6 else 0.0
        if (!location.hasSpeed() && lastLoc != null && timeDeltaSecs > 0.0) {
            speedKmHVal = (lastLoc.distanceTo(location) / timeDeltaSecs) * 3.6
        }

        // Update motion history
        val bearingVal = if (location.hasBearing()) location.bearing else null
        motionDetector.recordMovement(speedKmHVal, bearingVal)

        // Stationary & motion confidence detection
        val avgSpeedKmH = motionDetector.getRecentAverageSpeedKmH()
        val motionConfidence = motionDetector.getMotionConfidence()
        val isStationary = waitingDetector.isStationary(
            speedKmHVal,
            avgSpeedKmH,
            motionConfidence,
            location.accuracy
        )

        // Process distance accumulation
        val distanceDeltaKm = distanceEngine.processDistanceIncrement(
            smoothedLat,
            smoothedLng,
            lastSmoothedLat,
            lastSmoothedLng,
            location.accuracy.toDouble(),
            lastSmoothedAccuracy,
            isStationary
        )

        // Update geocoding strings
        val currentLocStr = formatCoordinates(smoothedLat, smoothedLng)
        val isStartAwaiting = currentState.startLocation.isEmpty() || 
                             currentState.startLocation.startsWith("Awaiting") ||
                             currentState.startLatitude == null
                             
        val updatedStart = if (isStartAwaiting) currentLocStr else currentState.startLocation
        val updatedEnd = currentLocStr
        
        val updatedStartLat = if (isStartAwaiting) smoothedLat else currentState.startLatitude
        val updatedStartLng = if (isStartAwaiting) smoothedLng else currentState.startLongitude
        val updatedEndLat = smoothedLat
        val updatedEndLng = smoothedLng

        // Trigger background geocoding for start location
        if (isStartAwaiting || !startLocationGeocoded) {
            startLocationGeocoded = true
            serviceScope.launch(Dispatchers.IO) {
                val resolved = GeocoderHelper.getAddressFromLatLng(applicationContext, smoothedLat, smoothedLng)
                if (resolved != null) {
                    withContext(Dispatchers.Main) {
                        val newState = _tripState.value.copy(startLocation = resolved)
                        updateState(newState)
                    }
                } else {
                    startLocationGeocoded = false
                }
            }
        }

        // Trigger periodic background geocoding for end location
        val now = System.currentTimeMillis()
        if (now - lastEndGeocodeTime > 20000L) {
            lastEndGeocodeTime = now
            serviceScope.launch(Dispatchers.IO) {
                val resolved = GeocoderHelper.getAddressFromLatLng(applicationContext, smoothedLat, smoothedLng)
                if (resolved != null) {
                    withContext(Dispatchers.Main) {
                        val newState = _tripState.value.copy(endLocation = resolved)
                        updateState(newState)
                    }
                }
            }
        }

        // Adaptive interval adjustment (Battery Optimization)
        adjustUpdateInterval(speedKmHVal)

        // Cap speed display to a reasonable max
        val cappedSpeedKmH = Math.min(110.0, if (isStationary) 0.0 else speedKmHVal)

        // Append to routePathPoints if smoothedLat and smoothedLng are not null
        val updatedRoutePoints = if (currentState.routePathPoints.isEmpty()) {
            "$smoothedLat,$smoothedLng"
        } else {
            val points = currentState.routePathPoints.split(";")
            val lastPointStr = points.lastOrNull()
            var shouldAppend = true
            if (lastPointStr != null) {
                val lastCoords = lastPointStr.split(",")
                if (lastCoords.size == 2) {
                    val lastLat = lastCoords[0].toDoubleOrNull()
                    val lastLng = lastCoords[1].toDoubleOrNull()
                    if (lastLat != null && lastLng != null) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(lastLat, lastLng, smoothedLat, smoothedLng, results)
                        if (results[0] < 10.0) { // less than 10 meters change, don't write to avoid bloat
                            shouldAppend = false
                        }
                    }
                }
            }
            if (shouldAppend) {
                "${currentState.routePathPoints};$smoothedLat,$smoothedLng"
            } else {
                currentState.routePathPoints
            }
        }

        // Build new state
        val updatedDist = currentState.distanceKm + distanceDeltaKm
        val newState = currentState.copy(
            distanceKm = updatedDist,
            speedKmH = cappedSpeedKmH,
            startLocation = updatedStart,
            endLocation = updatedEnd,
            startLatitude = updatedStartLat,
            startLongitude = updatedStartLng,
            endLatitude = updatedEndLat,
            endLongitude = updatedEndLng,
            isMockGps = isMock,
            gpsSignalStatus = signalStatus,
            isStationary = isStationary,
            routePathPoints = updatedRoutePoints
        )

        updateState(newState)
        recalculateFare()

        // Advance state tracking pointers
        lastLocation = location
        saveLastValidLocation(this, location)
        lastSmoothedLat = smoothedLat
        lastSmoothedLng = smoothedLng
        lastSmoothedAccuracy = location.accuracy.toDouble()
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        // Restore active trip state on service recreation (process death or background restart)
        val savedState = loadTripState(this)
        if (savedState != null) {
            _tripState.value = savedState
            lastLocation = loadLastValidLocation(this)
            if (lastLocation != null) {
                val loc = lastLocation!!
                lastSmoothedLat = loc.latitude
                lastSmoothedLng = loc.longitude
                lastSmoothedAccuracy = loc.accuracy.toDouble()
                
                // Restore Kalman filter state to prevent process death startup jump
                val initialVariance = (loc.accuracy * loc.accuracy).toDouble().coerceAtLeast(1.0)
                kalmanFilter.setState(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    variance = initialVariance,
                    lastTimeNanos = System.nanoTime()
                )
            }
            Log.d("TaxiMeterService", "Successfully restored active trip state on service onCreate: ${savedState.status}, distance = ${savedState.distanceKm} km")
            
            // Resume timers, GPS updates, and foreground service if it was running
            if (savedState.status == TripStatus.RUNNING) {
                acquireWakeLock()
                startLocationUpdates()
                startTripTimer()
                startForegroundCompat()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "DEFAULT"
        Log.d("TaxiMeterService", "Action received: $action")

        when (action) {
            "START_TRIP" -> {
                synchronized(recentLocations) { recentLocations.clear() }
                continuousStationarySeconds = 0L
                lastLocation = null
                saveLastValidLocation(this, null)
                startLocationGeocoded = false
                lastEndGeocodeTime = 0L
                kalmanFilter.reset()
                motionDetector.clear()
                waitingDetector.reset()
                lastSmoothedLat = null
                lastSmoothedLng = null
                lastSmoothedAccuracy = null
                acquireWakeLock()
                startLocationUpdates()
                startTripTimer()
                startForegroundCompat()
            }
            "PAUSE_TRIP" -> {
                synchronized(recentLocations) { recentLocations.clear() }
                continuousStationarySeconds = 0L
                updateNotification()
            }
            "RESUME_TRIP" -> {
                // Preserve the last valid GPS point for seamless continuation of distance tracking
                updateNotification()
            }
            "END_TRIP" -> {
                synchronized(recentLocations) { recentLocations.clear() }
                stopLocationUpdates()
                stopTripTimer()
                releaseWakeLock()
                val newState = _tripState.value.copy(isOverlayActive = false)
                updateState(newState)
                updateNotification()
                stopSelf()
            }
            "SHOW_OVERLAY", "HIDE_OVERLAY" -> {
                // No-op (floating widget removed)
            }
            "DEFAULT" -> {
                // Just in case service restarts
                if (_tripState.value.status == TripStatus.RUNNING && timerJob == null) {
                    acquireWakeLock()
                    startLocationUpdates()
                    startTripTimer()
                    startForegroundCompat()
                }
            }
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (isFusedLocationListening) return

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
            Log.w("TaxiMeterService", "Cannot start location updates: location permissions not granted")
            return
        }

        try {
            if (lastLocation == null) {
                lastLocation = loadLastValidLocation(this)
            }

            // Instantly fetch and display the best last-known location to bypass UI coordinate delay
            fusedLocationClient?.lastLocation?.addOnSuccessListener { bestLoc ->
                if (bestLoc != null) {
                    Log.d("TaxiMeterService", "Quick loading best last known location from FusedLocation for display only: ${bestLoc.latitude}, ${bestLoc.longitude}")
                    if (_tripState.value.status == TripStatus.RUNNING) {
                        val latitude = bestLoc.latitude
                        val longitude = bestLoc.longitude
                        val currentLocStr = formatCoordinates(latitude, longitude)
                        
                        val isStartAwaiting = _tripState.value.startLocation.isEmpty() || 
                                             _tripState.value.startLocation.startsWith("Awaiting") ||
                                             _tripState.value.startLatitude == null
                        
                        if (isStartAwaiting) {
                            val updatedStart = currentLocStr
                            val updatedStartLat = latitude
                            val updatedStartLng = longitude
                            
                            val newState = _tripState.value.copy(
                                startLocation = updatedStart,
                                endLocation = currentLocStr,
                                startLatitude = updatedStartLat,
                                startLongitude = updatedStartLng,
                                endLatitude = latitude,
                                endLongitude = longitude
                            )
                            updateState(newState)
                            
                            // Start geocoding background process
                            startLocationGeocoded = true
                            serviceScope.launch(Dispatchers.IO) {
                                val resolved = GeocoderHelper.getAddressFromLatLng(applicationContext, latitude, longitude)
                                if (resolved != null) {
                                    withContext(Dispatchers.Main) {
                                        val resolvedState = _tripState.value.copy(
                                            startLocation = resolved,
                                            endLocation = resolved
                                        )
                                        updateState(resolvedState)
                                    }
                                } else {
                                    startLocationGeocoded = false
                                }
                            }
                        }
                    }
                }
            }

            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).apply {
                setMinUpdateIntervalMillis(500L)
                setMinUpdateDistanceMeters(1.0f)
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    for (location in locationResult.locations) {
                        if (_tripState.value.status == TripStatus.RUNNING) {
                            processLocationUpdate(location)
                        }
                    }
                }

                override fun onLocationAvailability(locationAvailability: LocationAvailability) {
                    super.onLocationAvailability(locationAvailability)
                    val isAvailable = locationAvailability.isLocationAvailable
                    Log.d("TaxiMeterService", "GPS tracking location availability changed: isLocationAvailable = $isAvailable")
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            isFusedLocationListening = true
            Log.d("TaxiMeterService", "FusedLocationProviderClient started")
        } catch (e: Exception) {
            Log.e("TaxiMeterService", "Error starting FusedLocation updates: ${e.message}")
        }
    }

    private fun stopLocationUpdates() {
        if (!isFusedLocationListening) return
        try {
            locationCallback?.let {
                fusedLocationClient?.removeLocationUpdates(it)
            }
            locationCallback = null
            isFusedLocationListening = false
        } catch (e: Exception) {
            Log.e("TaxiMeterService", "Error removing FusedLocation updates: ${e.message}")
        }
    }

    private fun startTripTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                val currentState = _tripState.value
                if (currentState.status == TripStatus.RUNNING) {
                    val updatedDur = currentState.durationSeconds + 1
                    
                    // Dynamic GPS signal tracking via timer timeout
                    val lastLoc = lastLocation
                    val nowMs = System.currentTimeMillis()
                    val timeSinceLastUpdateMs = if (lastLoc != null) nowMs - lastLoc.time else (nowMs - currentState.startTime)
                    val signalStatus = when {
                        timeSinceLastUpdateMs > 15000L -> "NO_SIGNAL"
                        timeSinceLastUpdateMs > 8000L -> "POOR"
                        else -> currentState.gpsSignalStatus
                    }

                    // Standby Waiting Charge accumulation:
                    // Only calculate standby waiting charge when stationary. If running, do not calculate.
                    // If vehicle stops, only start calculating waiting time after 1 minute (60 seconds) of stopping.
                    val isWaiting = checkIfStationary()

                    if (isWaiting) {
                        continuousStationarySeconds += 1
                    } else {
                        continuousStationarySeconds = 0L
                    }

                    val updatedWait = if (isWaiting && continuousStationarySeconds > 60L) {
                        currentState.waitingSeconds + 1
                    } else {
                        currentState.waitingSeconds
                    }

                    val newState = currentState.copy(
                        durationSeconds = updatedDur,
                        waitingSeconds = updatedWait,
                        speedKmH = if (isWaiting) 0.0 else currentState.speedKmH,
                        isStationary = isWaiting,
                        gpsSignalStatus = signalStatus
                    )
                    updateState(newState)
                    
                    recalculateFare()
                    updateNotification()
                }
            }
        }
    }

    private fun stopTripTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun recalculateFare() {
        val state = _tripState.value
        var dynamicName = state.packageName
        var dynamicIncludedKm = state.includedKm
        var dynamicIncludedMinutes = state.includedMinutes
        var dynamicPackageBaseFare = state.packageBaseFare

        var calculatedFare = if (state.isPackageMeter) {
            val totalMinutes = state.durationSeconds.toDouble() / 60.0
            val packageHours = maxOf(1, kotlin.math.floor(totalMinutes / 60.0).toInt())
            
            dynamicIncludedKm = packageHours * 10.0
            dynamicIncludedMinutes = packageHours * 60
            dynamicPackageBaseFare = packageHours * state.packagePerHourRate
            dynamicName = "$packageHours Hour / ${dynamicIncludedKm.toInt()} KM"

            val packageFare = packageHours * state.packagePerHourRate
            val extraKm = maxOf(0.0, state.distanceKm - dynamicIncludedKm)
            val extraKmFare = extraKm * state.packagePerKmRate
            
            val extraMinutes = maxOf(0.0, totalMinutes - (packageHours * 60.0))
            val calculatedExtraTimeRate = 2.0833
            val extraTimeFare = extraMinutes * calculatedExtraTimeRate
            
            packageFare + extraKmFare + extraTimeFare
            
        } else {
            val distanceFare = state.distanceKm * state.perKmFare
            val waitingMinutes = state.waitingSeconds.toDouble() / 60.0
            val effectiveWaitRate = if (state.waitingChargePerMin <= 0.0 || kotlin.math.abs(state.waitingChargePerMin - 0.5) < 0.001) 1.0 else state.waitingChargePerMin
            val waitingFare = waitingMinutes * effectiveWaitRate
            var fare = state.baseFare + distanceFare + waitingFare
            if (state.isNightMode) {
                fare += fare * (state.nightChargePercent / 100.0)
            }
            fare
        }
        
        // Exact 2 decimal precision
        val roundedFare = (calculatedFare * 100.0).roundToInt() / 100.0
        val newState = _tripState.value.copy(
            currentFare = roundedFare,
            packageName = dynamicName,
            includedKm = dynamicIncludedKm,
            includedMinutes = dynamicIncludedMinutes,
            packageBaseFare = dynamicPackageBaseFare
        )
        updateState(newState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TRUSTY YELLOW CAB SERVICE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active Taxi Trip Status Tracking"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: LiveTripState): Notification {
        val statusText = when (state.status) {
            TripStatus.RUNNING -> "RUNNING"
            TripStatus.PAUSED -> "PAUSED"
            TripStatus.FINISHED -> "FINISHED"
            else -> "STANDBY"
        }
        
        val fareText = String.format(Locale.US, "₹%.2f", state.currentFare)
        val distText = String.format(Locale.US, "%.2f km", state.distanceKm)
        val durText = formatDuration(state.durationSeconds)
        val speedText = "${state.speedKmH.toInt()} km/h"
        val gpsStatus = state.gpsSignalStatus
        
        val contentText = "Fare: $fareText | Dist: $distText | Time: $durText"
        val subText = "Speed: $speedText | GPS: $gpsStatus | Status: $statusText"
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trusty Yellow Cab")
            .setContentText(contentText)
            .setSubText(subText)
            .setSmallIcon(com.trustyyellowcabs.driver.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setColor(0xFFE60000.toInt())
            .setOngoing(state.status == TripStatus.RUNNING || state.status == TripStatus.PAUSED)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(_tripState.value))
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TrustyYellowCab::TaxiMeterWakeLock"
            ).apply {
                acquire()
            }
            Log.d("TaxiMeterService", "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                }
            } catch (e: Exception) {
                Log.e("TaxiMeterService", "Error releasing WakeLock: ${e.message}")
            }
            wakeLock = null
            Log.d("TaxiMeterService", "WakeLock released")
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(_tripState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopTripTimer()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("TaxiMeterService", "onTaskRemoved called - Scheduling service restart if active")
        // Only reschedule if the trip was actively running or paused
        val currentState = _tripState.value
        if (currentState.status == TripStatus.RUNNING || currentState.status == TripStatus.PAUSED) {
            val restartServiceIntent = Intent(applicationContext, TaxiMeterService::class.java).apply {
                action = "DEFAULT"
                `package` = packageName
            }
            val restartServicePendingIntent = PendingIntent.getService(
                this, 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmService?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartServicePendingIntent
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
