package com.trustyyellowcabs.driver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.trustyyellowcabs.driver.MainActivity
import com.trustyyellowcabs.driver.R
import com.trustyyellowcabs.driver.network.FirebaseManager
import com.trustyyellowcabs.driver.network.FirestoreTrip
import com.trustyyellowcabs.driver.util.AlertSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class TaxiFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "TaxiFCMService"
        private const val CHANNEL_ID = TaxiDispatchService.ALERT_CHANNEL_ID
        private const val NOTIFICATION_ID = 4004
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Registration Token: $token")
        val prefs = getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("driver_fcm_token", token).apply()

        serviceScope.launch {
            FirebaseManager.updateDriverFcmToken(applicationContext, token)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}, data: ${remoteMessage.data}")

        val data = remoteMessage.data
        val tripDocId = data["doc_id"] 
            ?: (data["trip_doc_id"] 
            ?: (data["trip_id"] 
            ?: (data["id"] ?: "")))

        val actionType = (data["action"] ?: (data["type"] ?: (data["event"] ?: "NEW_TRIP"))).uppercase(Locale.ROOT)

        if (tripDocId.isNotBlank()) {
            val pickup = data["pickup_location"] ?: (data["pickup"] ?: data["from_location"])
            if (!pickup.isNullOrBlank()) {
                val directTrip = FirebaseManager.mapDocumentToTrip(tripDocId, data)
                if (directTrip != null) {
                    Log.i(TAG, "⚡ Fast-path: Parsed trip directly from FCM data payload!")
                    handleTripFromPush(directTrip, actionType)
                }
            }

            serviceScope.launch {
                // Fetch single trip document once (Requirement 5)
                val trip = FirebaseManager.fetchSingleTrip(applicationContext, tripDocId)
                if (trip != null) {
                    handleTripFromPush(trip, actionType)
                } else if (actionType == "CANCEL" || actionType == "CANCELLED" || actionType == "UNASSIGN" || actionType == "UNASSIGNED" || actionType == "COMPLETED") {
                    val active = TaxiDispatchServiceState.activeTrip.value
                    if (active != null && (active.doc_id == tripDocId || active.trip_id == tripDocId)) {
                        Log.i(TAG, "Active trip $tripDocId was cancelled/unassigned via FCM. Clearing immediately!")
                        TaxiDispatchServiceState.setActiveTrip(null)
                        TaxiDispatchService.activeInstance?.stopLoudAlert()
                        if (actionType != "COMPLETED") {
                            AlertSoundPlayer.playTripCancelledAlert(applicationContext)
                        }
                    }
                }
            }
        } else {
            // Check for notification payload
            val title = remoteMessage.notification?.title ?: "Trusty Yellow Cab"
            val body = remoteMessage.notification?.body ?: "New dispatch notification"
            showGenericNotification(title, body)
        }
    }

    private fun handleTripFromPush(trip: FirestoreTrip, actionType: String) {
        val myDriverId = FirebaseManager.getDriverId(applicationContext).trim()
        val tripStatus = trip.status.uppercase(Locale.ROOT)
        val isAssignedToMe = trip.driver_id?.trim()?.equals(myDriverId, ignoreCase = true) == true

        if (tripStatus in listOf("CANCELLED", "COMPLETED", "REJECTED", "UNASSIGNED") || (!isAssignedToMe && tripStatus != "OPEN")) {
            val currentActive = TaxiDispatchServiceState.activeTrip.value
            if (currentActive != null && (currentActive.doc_id == trip.doc_id || currentActive.trip_id == trip.trip_id)) {
                Log.i(TAG, "Trip ${trip.trip_id} status is $tripStatus. Clearing active trip view immediately!")
                TaxiDispatchServiceState.setActiveTrip(null)
                TaxiDispatchService.activeInstance?.stopLoudAlert()
                if (tripStatus != "COMPLETED") {
                    AlertSoundPlayer.playTripCancelledAlert(applicationContext)
                }
            }
            return
        }

        if (isAssignedToMe && (tripStatus == "ACCEPTED" || tripStatus == "IN_PROGRESS")) {
            Log.i(TAG, "Push: Trip ${trip.trip_id} assigned to me. Setting as active trip.")
            TaxiDispatchServiceState.setActiveTrip(trip)
            TaxiDispatchService.activeInstance?.stopLoudAlert()
            return
        }

        if (tripStatus == "OPEN") {
            val hasOngoing = TaxiDispatchServiceState.activeTrip.value != null
            val meterStatus = com.trustyyellowcabs.driver.service.TaxiMeterService.tripState.value.status
            val isMeterRunning = meterStatus == com.trustyyellowcabs.driver.service.TripStatus.RUNNING ||
                                 meterStatus == com.trustyyellowcabs.driver.service.TripStatus.PAUSED

            if (!hasOngoing && !isMeterRunning) {
                val service = TaxiDispatchService.activeInstance
                val isEligible = service == null || service.isTripEligibleForThisDriver(trip)
                if (isEligible) {
                    // Update open trips list instantly so trip appears immediately on screen
                    val currentOpen = TaxiDispatchServiceState.openTrips.value.toMutableList()
                    val key = trip.doc_id.ifBlank { trip.trip_id }
                    if (currentOpen.none { (it.doc_id.ifBlank { it.trip_id }) == key }) {
                        currentOpen.add(0, trip)
                        TaxiDispatchServiceState.setOpenTrips(currentOpen)
                    }

                    if (service != null) {
                        service.triggerLoudAlert(trip)
                    } else {
                        TaxiDispatchServiceState.triggerNewTripAlert(trip)
                        showTripNotification(trip)
                    }
                }
            }
        }
    }

    private fun showTripNotification(trip: FirestoreTrip) {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("OPEN_DISPATCH_ALERT", true)
            putExtra("TRIP_ID", trip.trip_id)
            putExtra("DOC_ID", trip.doc_id)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 201, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (trip.estimated_fare > 0.0) "NEW TRIP: ₹${trip.estimated_fare.toInt()}" else ""
        val fareDetail = if (trip.estimated_fare > 0.0) "\nEstimated Fare: ₹${trip.estimated_fare.toInt()}" else ""
        val categoryText = if (!trip.vehicle_category.isNullOrBlank()) "[${trip.vehicle_category}] " else ""

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("$categoryText${trip.pickup_location.take(28)} ➔ ${trip.drop_location.take(28)}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("New Trip Request Available!\n\nPickup: ${trip.pickup_location}\nDrop: ${trip.drop_location}$fareDetail\nVehicle: ${trip.vehicle_category}")
            )
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
            notificationManager?.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun showGenericNotification(title: String, message: String) {
        createNotificationChannel()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 202, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            notificationManager?.notify(NOTIFICATION_ID + 1, builder.build())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "New Trip Dispatch Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority instant alerts and heads-up banners for passenger trip bookings."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 450, 200, 450)
                enableLights(true)
                lightColor = android.graphics.Color.YELLOW
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }
}
