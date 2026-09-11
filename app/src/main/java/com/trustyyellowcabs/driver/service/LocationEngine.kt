package com.trustyyellowcabs.driver.service

import android.location.Location
import android.os.Build
import android.util.Log
import java.util.LinkedList
import kotlin.math.abs

/**
 * High-performance, production-grade 2D Kalman Filter for smoothing GPS coordinates.
 * Features:
 * - Reduce aggressive smoothing to preserve true travel paths.
 * - Dynamic process noise (Q) adapted on the fly based on vehicle speed and signal accuracy.
 * - Restores state on reconstruction (latitude, longitude, variance, timestamp) to prevent distance jumps.
 */
class KalmanFilter(private val defaultProcessNoise: Double = 1.0) {
    companion object {
        private const val TAG = "KalmanFilter"
    }

    private var lastTimeNanos: Long = 0L
    private var lat: Double = 0.0
    private var lng: Double = 0.0
    private var variance: Double = -1.0 // Uninitialized

    @Synchronized
    fun process(measuredLat: Double, measuredLng: Double, accuracyMeters: Double, timestampNanos: Long): Pair<Double, Double> {
        val r = if (accuracyMeters <= 0.0) 3.0 else accuracyMeters
        val rVar = r * r

        if (variance < 0.0) {
            // Initialize filter state with first valid reading
            this.lat = measuredLat
            this.lng = measuredLng
            this.variance = rVar
            this.lastTimeNanos = timestampNanos
            Log.d(TAG, "Initialized Kalman state with lat=$measuredLat, lng=$measuredLng, variance=$variance")
            return Pair(lat, lng)
        }

        val elapsedNanos = timestampNanos - lastTimeNanos
        if (elapsedNanos <= 0) {
            return Pair(lat, lng)
        }

        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        lastTimeNanos = timestampNanos

        // Calculate distance from previous state to adapt process noise
        val results = FloatArray(1)
        Location.distanceBetween(this.lat, this.lng, measuredLat, measuredLng, results)
        val calculatedSpeedMps = results[0] / elapsedSeconds

        // Adapt process noise based on speed:
        // - At highway/driving speed, we want high process noise to track turns and avoid cutting corners.
        // - At stationary/slow speed, we want minimal process noise to suppress GPS drift and oscillation.
        val speedFactor = when {
            calculatedSpeedMps > 25.0 -> 8.0  // Highway (>90 km/h)
            calculatedSpeedMps > 13.8 -> 5.0  // Medium speed (50-90 km/h)
            calculatedSpeedMps > 5.0  -> 3.0  // Low speed (18-50 km/h)
            calculatedSpeedMps > 1.5  -> 1.5  // Crawling/city traffic (5-18 km/h)
            else                      -> 0.1  // Stationary/idle (<5 km/h)
        }

        // Scale process noise based on measurement accuracy to avoid skewing state on poor accuracy spikes
        val accuracyScaler = when {
            accuracyMeters > 75.0 -> 0.25
            accuracyMeters > 50.0 -> 0.4
            accuracyMeters > 25.0 -> 0.6
            else -> 1.0
        }
        val adaptiveNoise = defaultProcessNoise * speedFactor * accuracyScaler

        // Grow variance over time based on adaptive uncertainty
        val q = adaptiveNoise * adaptiveNoise * elapsedSeconds
        variance += q

        // Kalman Gain calculation
        val k = variance / (variance + rVar)

        // State update
        lat += k * (measuredLat - lat)
        lng += k * (measuredLng - lng)

        // Covariance update
        variance *= (1.0 - k)

        Log.d(TAG, "Kalman Update: speed=${calculatedSpeedMps * 3.6} km/h, noise=$adaptiveNoise, Gain=$k, Var=$variance, Smoothed=($lat, $lng)")

        return Pair(lat, lng)
    }

    @Synchronized
    fun reset() {
        variance = -1.0
        lat = 0.0
        lng = 0.0
        lastTimeNanos = 0L
        Log.d(TAG, "Kalman Filter state reset successfully")
    }

    @Synchronized
    fun getState(): State {
        return State(lat, lng, variance, lastTimeNanos)
    }

    @Synchronized
    fun setState(lat: Double, lng: Double, variance: Double, lastTimeNanos: Long) {
        this.lat = lat
        this.lng = lng
        this.variance = variance
        this.lastTimeNanos = lastTimeNanos
        Log.d(TAG, "Restored Kalman Filter state: lat=$lat, lng=$lng, variance=$variance, lastTimeNanos=$lastTimeNanos")
    }

    data class State(val lat: Double, val lng: Double, val variance: Double, val lastTimeNanos: Long)
}

/**
 * Production-ready Location Validator with spoofing detection and spike rejection.
 */
class LocationValidator {
    companion object {
        private const val TAG = "LocationValidator"
        private const val MAX_SPEED_KMH = 180.0 // Increased to 180 km/h to capture fast highways
        private const val MAX_ACCURATE_METERS = 100.0 // Increased to 100 meters to prevent distance loss under urban cover or dense areas
    }

    fun isMockLocation(location: Location): Boolean {
        // 1. Android official API mock detection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (location.isFromMockProvider) {
                Log.w(TAG, "Spoofing Detected: Location is from mock provider")
                return true
            }
        }
        // 2. Mock provider check in extras
        val extras = location.extras
        if (extras != null && extras.getBoolean("mockLocation", false)) {
            Log.w(TAG, "Spoofing Detected: extras.mockLocation flag is true")
            return true
        }
        // 3. Mock provider name check
        if (location.provider == "mock") {
            Log.w(TAG, "Spoofing Detected: provider name is mock")
            return true
        }
        return false
    }

    fun isValid(location: Location, lastValidLocation: Location?): Boolean {
        // 1. Coordinates check
        if (location.latitude == 0.0 || location.longitude == 0.0) {
            Log.w(TAG, "Rejected location: Zero coordinates (0.0, 0.0)")
            return false
        }

        // 2. Accuracy check (must accept up to 50 meters)
        if (location.accuracy > MAX_ACCURATE_METERS) {
            Log.d(TAG, "Rejected location: poor accuracy: ${location.accuracy}m (Limit: $MAX_ACCURATE_METERS m)")
            return false
        }

        // 3. Spoofing / Mock location detection
        if (isMockLocation(location)) {
            Log.w(TAG, "Rejected location: Mock GPS detected at (${location.latitude}, ${location.longitude})")
            return false
        }

        val last = lastValidLocation ?: return true

        // 4. Chronological order check
        val timeDeltaMs = location.time - last.time
        if (timeDeltaMs <= 0) {
            Log.d(TAG, "Rejected location: non-positive time delta: $timeDeltaMs ms")
            return false
        }

        val timeDeltaSecs = timeDeltaMs / 1000.0

        // 5. Jump and impossible teleportation validation
        val distanceMeters = last.distanceTo(location)
        val calculatedSpeedMps = distanceMeters / timeDeltaSecs
        val calculatedSpeedKmh = calculatedSpeedMps * 3.6

        // Reject GPS jumps over 500 meters within 5 seconds
        if (timeDeltaSecs <= 5.0 && distanceMeters > 500.0) {
            Log.w(TAG, "Rejected location: impossible jump: $distanceMeters meters within $timeDeltaSecs seconds")
            return false
        }

        // Reject speed spikes exceeding 180 km/h
        if (calculatedSpeedKmh > MAX_SPEED_KMH) {
            Log.w(TAG, "Rejected location: impossible speed: $calculatedSpeedKmh km/h (distance: $distanceMeters m over $timeDeltaSecs s, Limit: $MAX_SPEED_KMH km/h)")
            return false
        }

        Log.d(TAG, "Accepted location: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m, speed=${calculatedSpeedKmh}km/h")
        return true
    }
}

/**
 * Tracks movement history, bearing stability, and produces a motion confidence score.
 */
class MotionDetector {
    private val speedHistory = LinkedList<Double>()
    private val bearingHistory = LinkedList<Float>()
    private val maxHistorySize = 10

    @Synchronized
    fun recordMovement(speedKmH: Double, bearing: Float?) {
        speedHistory.add(speedKmH)
        if (speedHistory.size > maxHistorySize) {
            speedHistory.removeFirst()
        }

        if (bearing != null) {
            bearingHistory.add(bearing)
            if (bearingHistory.size > maxHistorySize) {
                bearingHistory.removeFirst()
            }
        }
    }

    @Synchronized
    fun getRecentAverageSpeedKmH(): Double {
        if (speedHistory.isEmpty()) return 0.0
        return speedHistory.average()
    }

    @Synchronized
    fun getBearingStabilityScore(): Double {
        if (bearingHistory.size < 3) return 1.0 // Assume stable if not enough historical records
        
        var totalDiff = 0.0
        for (i in 0 until bearingHistory.size - 1) {
            val diff = abs(bearingHistory[i + 1] - bearingHistory[i])
            val shortestDiff = if (diff > 180f) 360f - diff else diff
            totalDiff += shortestDiff
        }
        val avgDiff = totalDiff / (bearingHistory.size - 1)
        
        // Dynamic scaling: an average direction change of 45+ degrees is typical of GPS stationary noise
        return (1.0 - (avgDiff / 45.0)).coerceIn(0.0, 1.0)
    }

    @Synchronized
    fun getMotionConfidence(): Double {
        val avgSpeed = getRecentAverageSpeedKmH()
        val bearingStability = getBearingStabilityScore()

        return when {
            avgSpeed < 1.0 -> 0.0 // Extremely low speed is guaranteed static
            avgSpeed < 5.0 -> {
                // Distinguish crawling traffic from stationary GPS walk using bearing stability
                val speedRatio = (avgSpeed - 1.0) / 4.0
                speedRatio * bearingStability
            }
            else -> 1.0 // Unquestionable high-speed movement
        }
    }

    @Synchronized
    fun clear() {
        speedHistory.clear()
        bearingHistory.clear()
    }
}

/**
 * Advanced Distance Engine to accumulate distance while filtering signal oscillations,
 * zig-zags, and stationary drift.
 */
class DistanceEngine {
    companion object {
        private const val TAG = "DistanceEngine"
        private const val MIN_MOTION_DISTANCE_METERS = 0.2 // Reduced to 0.2m to capture slow crawling traffic and avoid distance loss
        private const val ACCURACY_CONFIDENCE_THRESHOLD = 100.0 // Allow tracking up to 100m accuracy and trust Kalman filtering to smooth it
    }

    fun processDistanceIncrement(
        currentSmoothedLat: Double,
        currentSmoothedLng: Double,
        lastSmoothedLat: Double?,
        lastSmoothedLng: Double?,
        currentAccuracy: Double,
        lastAccuracy: Double?,
        isStationary: Boolean
    ): Double {
        if (lastSmoothedLat == null || lastSmoothedLng == null || isStationary) {
            return 0.0
        }

        // Avoid adding distance if accuracy is too low to prevent fake distance accumulation
        if (currentAccuracy > ACCURACY_CONFIDENCE_THRESHOLD || (lastAccuracy != null && lastAccuracy > ACCURACY_CONFIDENCE_THRESHOLD)) {
            Log.d(TAG, "No distance added: accuracy exceeds confidence threshold (current=$currentAccuracy, last=$lastAccuracy)")
            return 0.0
        }

        val results = FloatArray(1)
        Location.distanceBetween(
            lastSmoothedLat, lastSmoothedLng,
            currentSmoothedLat, currentSmoothedLng,
            results
        )
        val distanceDelta = results[0].toDouble()

        // Filter out small GPS jitter while stationary (GPS oscillation)
        if (distanceDelta < MIN_MOTION_DISTANCE_METERS) {
            Log.d(TAG, "No distance added: displacement $distanceDelta m is less than motion threshold $MIN_MOTION_DISTANCE_METERS m")
            return 0.0
        }

        val incrementKm = distanceDelta / 1000.0
        Log.d(TAG, "Distance Increment Diagnostics: +$distanceDelta meters (+$incrementKm km). Path: ($lastSmoothedLat, $lastSmoothedLng) -> ($currentSmoothedLat, $currentSmoothedLng)")
        return incrementKm
    }
}

/**
 * Determines waiting / stationary status based on speed, motion confidence, and GPS accuracy.
 */
class WaitingDetector {
    companion object {
        private const val TAG = "WaitingDetector"
        private const val STATIONARY_SPEED_LIMIT_KMH = 1.5 // Speed below 1.5 km/h is a potential wait status
        private const val STATIONARY_CONFIRMATION_COUNT = 5 // Requires 5 consecutive confirmations to prevent false triggers
    }

    private var consecutiveStationaryTicks = 0

    @Synchronized
    fun isStationary(
        currentSpeedKmH: Double,
        avgSpeedKmH: Double,
        motionConfidence: Double,
        gpsAccuracy: Float
    ): Boolean {
        val speedStationary = currentSpeedKmH < STATIONARY_SPEED_LIMIT_KMH
        val avgSpeedStationary = avgSpeedKmH < STATIONARY_SPEED_LIMIT_KMH
        
        // If GPS accuracy is poor, we tolerate slight speed ticks as stationary to avoid dropping waiting billing
        val lowConfidence = motionConfidence < 0.15 || gpsAccuracy > 20.0f
        val isConfirmedStatic = speedStationary && avgSpeedStationary && lowConfidence

        if (isConfirmedStatic) {
            consecutiveStationaryTicks++
            Log.d(TAG, "Stationary Candidate: speed=$currentSpeedKmH, avgSpeed=$avgSpeedKmH, confidence=$motionConfidence, ticks=$consecutiveStationaryTicks")
        } else {
            if (consecutiveStationaryTicks > 0) {
                Log.d(TAG, "Stationary Interrupted: speed=$currentSpeedKmH, avgSpeed=$avgSpeedKmH, confidence=$motionConfidence. Resetting consecutive ticks.")
            }
            consecutiveStationaryTicks = 0
        }

        val isStationaryState = consecutiveStationaryTicks >= STATIONARY_CONFIRMATION_COUNT
        if (isStationaryState) {
            Log.d(TAG, "STATIONARY CONFIRMED: Active waiting status engaged (Ticks: $consecutiveStationaryTicks)")
        }
        return isStationaryState
    }

    @Synchronized
    fun reset() {
        consecutiveStationaryTicks = 0
        Log.d(TAG, "Waiting detector reset successfully")
    }
}

/**
 * Assesses the quality of the current GPS connection.
 */
object LocationConfidence {
    private const val TAG = "LocationConfidence"

    fun getSignalStatus(accuracyMeters: Float, timeDeltaSecs: Double): String {
        val status = when {
            accuracyMeters <= 0.0f || timeDeltaSecs > 15.0 -> "NO_SIGNAL"
            accuracyMeters <= 5.0f && timeDeltaSecs <= 2.0 -> "EXCELLENT"
            accuracyMeters <= 12.0f && timeDeltaSecs <= 3.0 -> "GOOD"
            accuracyMeters <= 25.0f && timeDeltaSecs <= 5.0 -> "FAIR"
            accuracyMeters <= 50.0f -> "POOR"
            else -> "NO_SIGNAL"
        }
        Log.d(TAG, "GPS Signal Status classified: $status (Accuracy: $accuracyMeters m, Interval: $timeDeltaSecs s)")
        return status
    }
}
