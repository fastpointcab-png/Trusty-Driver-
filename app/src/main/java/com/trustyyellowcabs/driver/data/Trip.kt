package com.trustyyellowcabs.driver.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val driverName: String,
    val vehicleNumber: String,
    val vehicleCategory: String,
    val vehicleModel: String,
    val driverMobile: String = "",
    val startTime: Long,
    val endTime: Long,
    val distance: Double, // in kilometers
    val durationSeconds: Long, // in seconds
    val totalFare: Double, // calculated rupees
    val baseFare: Double,
    val perKmFare: Double,
    val waitingChargePerMin: Double,
    val minimumFare: Double,
    val nightChargePercent: Double,
    val dateStr: String,
    val startLocation: String = "Unknown",
    val endLocation: String = "Unknown",
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val ccCommission: Double = 0.0,
    val tripIdCode: String = "",
    val isSyncedToSheets: Boolean = false,
    val customerMobile: String = "",
    val isPackageMeter: Boolean = false,
    val packageName: String = "",
    val packageBaseFare: Double = 0.0,
    val includedKm: Double = 0.0,
    val includedMinutes: Int = 0,
    val extraKmRate: Double = 0.0,
    val extraTimeRate: Double = 0.0,
    val packageWaitingChargePerMin: Double = 0.0,
    val waitingSeconds: Long = 0,
    val routePathPoints: String? = null
)
