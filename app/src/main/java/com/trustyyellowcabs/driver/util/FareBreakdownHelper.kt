package com.trustyyellowcabs.driver.util

import com.trustyyellowcabs.driver.data.Trip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class FareBreakdownItem(
    val label: String,
    val amount: Double,
    val detail: String = ""
)

object FareBreakdownHelper {

    fun calculateBreakdown(trip: Trip): List<FareBreakdownItem> {
        val items = mutableListOf<FareBreakdownItem>()

        if (trip.isPackageMeter) {
            val totalMinutes = trip.durationSeconds.toDouble() / 60.0
            val packageHours = max(1, kotlin.math.floor(totalMinutes / 60.0).toInt())
            
            val pkgIncludedKm = if (trip.includedKm > 0.0) trip.includedKm else (packageHours * 10.0)
            val pkgExtraKmRate = if (trip.extraKmRate > 0.0) trip.extraKmRate else trip.perKmFare
            val effectiveTimeRate = if (trip.extraTimeRate > 0.0) trip.extraTimeRate else 2.0833
            val packageFare = if (trip.packageBaseFare > 0.0) trip.packageBaseFare else trip.baseFare

            val extraKm = max(0.0, trip.distance - pkgIncludedKm)
            val extraKmFare = extraKm * pkgExtraKmRate

            val extraMinutes = max(0.0, totalMinutes - (packageHours * 60.0))
            val extraTimeFare = extraMinutes * effectiveTimeRate

            // 1. Package Base Fare
            val pkgHoursDetail = if (trip.packageName.isNotBlank() && (trip.packageName.contains("Hour", ignoreCase = true) || trip.packageName.contains("KM", ignoreCase = true))) {
                trip.packageName
            } else {
                "$packageHours Hours / ${pkgIncludedKm.toInt()} KM"
            }

            items.add(
                FareBreakdownItem(
                    label = "Package Base Fare",
                    amount = packageFare,
                    detail = pkgHoursDetail
                )
            )

            // 2. Extra Distance (Always shown, e.g. "0 KM @ ₹25/KM")
            val extraKmStr = if (extraKm == 0.0) "0" else if (extraKm % 1.0 == 0.0) extraKm.toInt().toString() else String.format(Locale.US, "%.2f", extraKm)
            val kmRateStr = if (pkgExtraKmRate % 1.0 == 0.0) pkgExtraKmRate.toInt().toString() else String.format(Locale.US, "%.2f", pkgExtraKmRate).trimEnd('0').trimEnd('.')
            val kmDetail = "$extraKmStr KM @ ₹$kmRateStr/KM"

            items.add(
                FareBreakdownItem(
                    label = "Extra Distance",
                    amount = extraKmFare,
                    detail = kmDetail
                )
            )

            // 3. Extra Duration (Always shown, e.g. "0 Mins @ ₹2.083/Min")
            val extraMinStr = if (extraMinutes == 0.0) "0" else if (extraMinutes % 1.0 == 0.0) extraMinutes.toInt().toString() else String.format(Locale.US, "%.1f", extraMinutes)
            val timeRateStr = if (abs(effectiveTimeRate - 2.0833) < 0.001 || abs(effectiveTimeRate - 2.083) < 0.001) {
                "2.083"
            } else if (effectiveTimeRate % 1.0 == 0.0) {
                effectiveTimeRate.toInt().toString()
            } else {
                String.format(Locale.US, "%.3f", effectiveTimeRate).trimEnd('0').trimEnd('.')
            }
            val timeDetail = "$extraMinStr Mins @ ₹$timeRateStr/Min"

            items.add(
                FareBreakdownItem(
                    label = "Extra Duration",
                    amount = extraTimeFare,
                    detail = timeDetail
                )
            )

            // 4. Standby Waiting Time (if applicable)
            val waitingMinutes = trip.waitingSeconds.toDouble() / 60.0
            val waitingFare = if (trip.packageWaitingChargePerMin > 0.0) waitingMinutes * trip.packageWaitingChargePerMin else 0.0
            if (waitingFare > 0.0) {
                val waitRateStr = if (trip.packageWaitingChargePerMin % 1.0 == 0.0) trip.packageWaitingChargePerMin.toInt().toString() else String.format(Locale.US, "%.2f", trip.packageWaitingChargePerMin)
                items.add(
                    FareBreakdownItem(
                        label = "Standby Waiting Charge",
                        amount = waitingFare,
                        detail = "${String.format(Locale.US, "%.1f", waitingMinutes)} Mins @ ₹$waitRateStr/Min"
                    )
                )
            }

            // Check if minimum package bounding was applied to totalFare
            val sumRaw = packageFare + extraKmFare + extraTimeFare + waitingFare
            if (trip.totalFare > sumRaw + 0.01) {
                val diff = trip.totalFare - sumRaw
                items.add(
                    FareBreakdownItem(
                        label = "Fare Rounding / Adjustment",
                        amount = diff
                    )
                )
            }

        } else {
            // Standard Meter Trip
            val baseFare = trip.baseFare
            val distanceFare = trip.distance * trip.perKmFare
            val waitMins = trip.waitingSeconds.toDouble() / 60.0
            val effectiveWaitRate = if (trip.waitingChargePerMin <= 0.0 || abs(trip.waitingChargePerMin - 0.5) < 0.001) 1.0 else trip.waitingChargePerMin
            val waitFare = waitMins * effectiveWaitRate
            val rawSubtotal = baseFare + distanceFare + waitFare

            // 1. Base Fare
            items.add(
                FareBreakdownItem(
                    label = "Base Fare Minimum",
                    amount = baseFare
                )
            )

            // 2. Distance Fare (e.g. "0.00 KM @ ₹15/KM")
            val kmRateStr = if (trip.perKmFare % 1.0 == 0.0) trip.perKmFare.toInt().toString() else String.format(Locale.US, "%.2f", trip.perKmFare).trimEnd('0').trimEnd('.')
            items.add(
                FareBreakdownItem(
                    label = "Distance Fare",
                    amount = distanceFare,
                    detail = "${String.format(Locale.US, "%.2f", trip.distance)} KM @ ₹$kmRateStr/KM"
                )
            )

            // 3. Standby Waiting Fare (Always visible, e.g. "0.0 Mins @ ₹1/Min")
            val waitRateStr = if (effectiveWaitRate % 1.0 == 0.0) effectiveWaitRate.toInt().toString() else String.format(Locale.US, "%.2f", effectiveWaitRate).trimEnd('0').trimEnd('.')
            items.add(
                FareBreakdownItem(
                    label = "Standby Waiting Fare",
                    amount = waitFare,
                    detail = "${String.format(Locale.US, "%.1f", waitMins)} Mins @ ₹$waitRateStr/Min"
                )
            )

            // 4. Minimum Fare Adjustment
            val effectiveMinFare = if (trip.minimumFare > 0.0) trip.minimumFare else if (trip.totalFare > rawSubtotal + 0.01) trip.totalFare else 0.0
            if (effectiveMinFare > 0.0 && rawSubtotal < effectiveMinFare) {
                val adjustment = effectiveMinFare - rawSubtotal
                items.add(
                    FareBreakdownItem(
                        label = "Minimum Fare Adjustment",
                        amount = adjustment,
                        detail = ""
                    )
                )
            }

            // 5. Night Surcharge Premium
            if (trip.nightChargePercent > 0.0) {
                val nightPct = trip.nightChargePercent
                val baseForNight = if (rawSubtotal < effectiveMinFare && effectiveMinFare > 0.0) effectiveMinFare else rawSubtotal
                val calculatedSurcharge = baseForNight * (nightPct / 100.0)
                val surcharge = if (trip.totalFare > baseForNight) {
                    trip.totalFare - baseForNight
                } else {
                    calculatedSurcharge
                }
                items.add(
                    FareBreakdownItem(
                        label = "Night Surcharge Premium",
                        amount = surcharge,
                        detail = "${nightPct.roundToInt()}% Night Rate"
                    )
                )
            }
        }

        return items
    }
}
