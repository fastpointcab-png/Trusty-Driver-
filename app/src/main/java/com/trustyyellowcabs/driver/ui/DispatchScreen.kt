package com.trustyyellowcabs.driver.ui

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustyyellowcabs.driver.R
import com.trustyyellowcabs.driver.network.FirebaseManager
import com.trustyyellowcabs.driver.network.FirestoreTrip
import com.trustyyellowcabs.driver.service.TaxiDispatchService
import com.trustyyellowcabs.driver.service.TaxiDispatchServiceState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

// Color constants
val BrandRed = Color(0xFFE60000)
val BrandWhite = Color(0xFFFFFFFF)
val GraySlate = Color(0xFF2C3E50)
val SoftGrayBg = Color(0xFFF8F9FA)
val DividerGray = Color(0xFFE2E8F0)
val OnlineGreen = Color(0xFF2ECC71)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DispatchScreen(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val isOnline by TaxiDispatchServiceState.isOnline.collectAsState()
    val openTrips by TaxiDispatchServiceState.openTrips.collectAsState()
    val activeTrip by TaxiDispatchServiceState.activeTrip.collectAsState()

    // Pull open trips silently on screen launch / online (Requirement 5)
    LaunchedEffect(isOnline) {
        if (isOnline) {
            TaxiDispatchService.activeInstance?.pullOpenTripsSilently()
        }
    }

    val latestAlert by TaxiDispatchServiceState.latestTripAlert.collectAsState()
    val ignoredTimeMap by TaxiDispatchServiceState.ignoredTrips.collectAsState()
    
    val scope = rememberCoroutineScope()

    // Step by step flow for active trip: 1 = Navigation / Pickup, 2 = Verification / OTP, 3 = Meter In Progress
    var activeTripStep by rememberSaveable(activeTrip?.doc_id) { mutableStateOf(1) }
    var dispatchEnteredOtp by remember { mutableStateOf("") }
    var acceptingTripDocId by remember { mutableStateOf<String?>(null) }
    var tripAlreadyTakenMessage by remember { mutableStateOf<String?>(null) }

    // Auto-dismiss the already taken banner after 6 seconds
    LaunchedEffect(tripAlreadyTakenMessage) {
        if (tripAlreadyTakenMessage != null) {
            kotlinx.coroutines.delay(6000L)
            tripAlreadyTakenMessage = null
        }
    }

    val serviceState by viewModel.serviceState.collectAsState()
    val isMeterRunning = serviceState.status == com.trustyyellowcabs.driver.service.TripStatus.RUNNING ||
                         serviceState.status == com.trustyyellowcabs.driver.service.TripStatus.PAUSED

    LaunchedEffect(isMeterRunning, activeTrip?.doc_id) {
        if (isMeterRunning) {
            activeTripStep = 3
        } else if (activeTripStep == 3 && activeTrip != null) {
            activeTripStep = 1
        }
        if (activeTrip == null && !isMeterRunning) {
            activeTripStep = 1
            dispatchEnteredOtp = ""
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current

    val expectedTripOtp = remember(activeTrip?.otp, activeTrip?.customer_phone, activeTrip?.trip_id) {
        val backendOtp = activeTrip?.otp?.trim()
        if (!backendOtp.isNullOrEmpty() && backendOtp.all { it.isDigit() } && backendOtp.length == 4) {
            backendOtp
        } else {
            val phone = activeTrip?.customer_phone.orEmpty()
            val sanitized = phone.filter { it.isDigit() }
            val baseDigits = if (sanitized.length >= 4) {
                sanitized.takeLast(4)
            } else {
                val digits = activeTrip?.trip_id.orEmpty().filter { it.isDigit() }
                if (digits.length >= 4) digits.takeLast(4) else "1234"
            }
            val reversed = baseDigits.reversed()
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
            val todayDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val otpBuilder = StringBuilder()
            for (char in reversed) {
                val digit = char.digitToIntOrNull() ?: 0
                val incremented = (digit + todayDay) % 10
                otpBuilder.append(incremented)
            }
            otpBuilder.toString()
        }
    }

    val isDispatchOtpCorrect = dispatchEnteredOtp.length == 4 && (
        expectedTripOtp.isNotEmpty() && dispatchEnteredOtp == expectedTripOtp
    )

    val isOngoing = activeTrip != null || isMeterRunning

    val officePaymentInfo by viewModel.officePaymentInfo.collectAsState()
    val hasOfficeDue = officePaymentInfo.amount > 0.0 && officePaymentInfo.paymentStatus != "CLEARED"

    var currentTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(5000L)
            currentTick = System.currentTimeMillis()
        }
    }

    // Stop loud sound alert automatically if an ongoing trip starts or completes
    if (isOngoing && latestAlert != null) {
        TaxiDispatchService.activeInstance?.stopLoudAlert()
        TaxiDispatchServiceState.triggerNewTripAlert(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SoftGrayBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // --- 0. CLEAN CORPORATE PENDING DUE ALERT BANNER ---
        if (hasOfficeDue) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("office_due_alert_banner"),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFFDE68A))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFD97706))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.alert_office_due_title),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E),
                                letterSpacing = 0.5.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Due: ₹${String.format(java.util.Locale.US, "%.0f", officePaymentInfo.amount)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF78350F),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.setNavigation("OFFICE_PAYMENT") },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF78350F),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color(0xFF78350F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("btn_alert_pay_due")
                    ) {
                        Text(
                            text = stringResource(R.string.alert_pay_now),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // --- 1. ONLINE STATUS SWITCH CARD ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = BrandWhite),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) OnlineGreen else BrandRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isOnline) stringResource(R.string.online) else stringResource(R.string.offline),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isOnline) OnlineGreen else Color.DarkGray
                        )
                    }
                    Text(
                        text = if (isOnline) stringResource(R.string.duty_online_desc) else stringResource(R.string.duty_offline_desc),
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Switch(
                    checked = isOnline,
                    onCheckedChange = { online ->
                        if (online) {
                            TaxiDispatchService.startService(context)
                        } else {
                            TaxiDispatchService.stopService(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrandWhite,
                        checkedTrackColor = OnlineGreen,
                        uncheckedThumbColor = BrandWhite,
                        uncheckedTrackColor = Color.LightGray
                    ),
                    modifier = Modifier.testTag("online_toggle")
                )
            }
        }

        // --- 2. ACTIVE ACCEPTED TRIP SCREEN (STEP-BY-STEP WORKFLOW) ---
        if (isOngoing) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (activeTrip != null) {
                    val trip = activeTrip!!

                    // Step Progress Header
                    TripStepProgressHeader(currentStep = activeTripStep)

                    when (activeTripStep) {
                        1 -> {
                            // STEP 1: TRIP ACCEPTED / GO TO PICKUP
                            ActiveTripPickupStep(
                                trip = trip,
                                onProceedToVerification = {
                                    activeTripStep = 2
                                },
                                onCancelTrip = {
                                    // Direct call to office to cancel without wiping backend
                                    com.trustyyellowcabs.driver.util.PhoneCallUtils.callOffice(context)
                                }
                            )
                        }
                        2 -> {
                            // STEP 2: START TRIP & OTP VERIFICATION
                            ActiveTripVerificationStep(
                                trip = trip,
                                enteredOtp = dispatchEnteredOtp,
                                onOtpChange = { input ->
                                    val digits = input.filter { it.isDigit() }
                                    if (digits.length <= 4) {
                                        dispatchEnteredOtp = digits
                                        if (digits.length == 4) {
                                            keyboardController?.hide()
                                        }
                                    }
                                },
                                isOtpCorrect = isDispatchOtpCorrect,
                                onBackToNavigation = {
                                    activeTripStep = 1
                                },
                                onStartMeter = {
                                    scope.launch {
                                        FirebaseManager.updateTripStatus(
                                            context = context,
                                            tripDocId = trip.doc_id,
                                            status = "IN_PROGRESS",
                                            extraData = mapOf("started_at" to System.currentTimeMillis())
                                        )
                                    }
                                    val isPackageTrip = trip.is_package || (trip.hour_fare != null && trip.hour_fare > 0.0) || trip.trip_type.equals("PACKAGE", ignoreCase = true)
                                    if (isPackageTrip) {
                                        val hourRate = trip.hour_fare ?: (if (viewModel.packagePerHourRate.value > 0.0) viewModel.packagePerHourRate.value else 0.0)
                                        val kmRate = trip.kms_fare ?: (if (viewModel.packagePerKmRate.value > 0.0) viewModel.packagePerKmRate.value else 0.0)
                                        val waitCharge = 0.0 // Standby waiting fare is not calculated for package meter
                                        val baseRate = if (trip.base_fare != null && trip.base_fare > 0.0) trip.base_fare else (if (viewModel.packageBaseFare.value > 0.0) viewModel.packageBaseFare.value else hourRate)
                                        val extraKmRate = if (kmRate > 0.0) kmRate else viewModel.packageExtraKmRate.value

                                        val backendAssignedId = trip.trip_id.ifBlank { trip.doc_id }
                                        viewModel.startTaxiTrip(
                                            context = context,
                                            customerMobile = trip.customer_phone.orEmpty(),
                                            isPackage = true,
                                            pkgName = "1 Hour Package",
                                            pkgBaseFare = baseRate,
                                            pkgIncludedKm = 0.0,
                                            pkgIncludedMinutes = 0,
                                            pkgExtraKmRate = extraKmRate,
                                            pkgExtraTimeRate = 0.0,
                                            pkgWaitingCharge = waitCharge,
                                            pkgPerHourRate = hourRate,
                                            pkgPerKmRate = kmRate,
                                            dispatchDocId = trip.doc_id,
                                            dispatchTripId = backendAssignedId
                                        )
                                    } else {
                                        val baseRate = trip.base_fare ?: (if (viewModel.baseFare.value > 0.0) viewModel.baseFare.value else 0.0)
                                        val kmRate = trip.kms_fare ?: (if (viewModel.perKmFare.value > 0.0) viewModel.perKmFare.value else 0.0)
                                        val backendAssignedId = trip.trip_id.ifBlank { trip.doc_id }
                                        viewModel.startTaxiTrip(
                                            context = context,
                                            customerMobile = trip.customer_phone.orEmpty(),
                                            customBaseFare = baseRate,
                                            customKmsFare = kmRate,
                                            dispatchDocId = trip.doc_id,
                                            dispatchTripId = backendAssignedId
                                        )
                                    }
                                    activeTripStep = 3
                                }
                            )
                        }
                        3 -> {
                            // STEP 3: MERGED STEP 3 & LIVE METER IN SAME PAGE
                            ActiveTripInProgressStep(
                                trip = trip,
                                viewModel = viewModel,
                                onEndTrip = {
                                    val backendAssignedId = trip.trip_id.ifBlank { trip.doc_id }
                                    viewModel.endTaxiTrip(context, explicitTripId = backendAssignedId)
                                    TaxiDispatchService.activeInstance?.stopLoudAlert()
                                    TaxiDispatchService.activeInstance?.forceRefreshTrips()
                                }
                            )
                        }
                    }
                } else if (isMeterRunning) {
                    // Meter running independently
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BrandWhite),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("LIVE TAXI METER ACTIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrandRed)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "${serviceState.speedKmH.toInt()} KM/H",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                color = BrandRed
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("DISTANCE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Text("${String.format(java.util.Locale.ROOT, "%.2f", serviceState.distanceKm)} KM", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("DURATION", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Text(com.trustyyellowcabs.driver.service.TaxiMeterService.formatDuration(serviceState.durationSeconds), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    SwipeToEndButton(
                        onSwipeComplete = {
                            viewModel.endTaxiTrip(context)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            val combinedTrips = remember(openTrips, latestAlert) {
                val list = openTrips.toMutableList()
                if (latestAlert != null && list.none { it.doc_id == latestAlert?.doc_id || it.trip_id == latestAlert?.trip_id }) {
                    list.add(0, latestAlert!!)
                }
                list.distinctBy { it.doc_id.ifBlank { it.trip_id } }
            }

            val displayedOpenTrips = combinedTrips.filter { trip ->
                val ignoredAt = ignoredTimeMap[trip.doc_id.ifBlank { trip.trip_id }] ?: 0L
                currentTick - ignoredAt >= 60000L
            }

            // --- 3. AVAILABLE TRIPS (CLEAN SINGLE VIEW) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stringResource(R.string.available_trips)} (${displayedOpenTrips.size})",
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = GraySlate
                )
                
                IconButton(
                    onClick = {
                        scope.launch {
                            val activeService = TaxiDispatchService.activeInstance
                            if (activeService != null) {
                                activeService.forceRefreshTrips()
                            } else {
                                val all = FirebaseManager.fetchAllTrips(context)
                                val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                                val driverCat = prefs.getString("vehicle_category", "Mini") ?: "Mini"
                                val openOnes = all.filter { trip ->
                                    trip.status == "OPEN" &&
                                    FirebaseManager.isVehicleCategoryEligible(trip.vehicle_category, driverCat) &&
                                    FirebaseManager.isTripWithinRadius(trip, null, null)
                                }
                                TaxiDispatchServiceState.setOpenTrips(openOnes)
                            }
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync", tint = BrandRed, modifier = Modifier.size(20.dp))
                }
            }

            // Warning banner if a trip was already taken by another driver
            if (tripAlreadyTakenMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tripAlreadyTakenMessage ?: "",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF991B1B),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { tripAlreadyTakenMessage = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color(0xFF991B1B),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (!isOnline) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandWhite, RoundedCornerShape(14.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.WifiOff,
                            contentDescription = "Offline Mode",
                            tint = Color.LightGray,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.offline),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.duty_offline_desc),
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (displayedOpenTrips.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandWhite, RoundedCornerShape(14.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.no_trips_available),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.loading_trips),
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    displayedOpenTrips.forEach { trip ->
                        val isAlerting = latestAlert != null && (latestAlert?.doc_id == trip.doc_id || latestAlert?.trip_id == trip.trip_id)
                        val isThisTripAccepting = acceptingTripDocId == trip.doc_id
                        val isAnyTripAccepting = acceptingTripDocId != null

                        SingleTripItem(
                            trip = trip,
                            isAlerting = isAlerting,
                            isAccepting = isThisTripAccepting,
                            isActionDisabled = isAnyTripAccepting,
                            onAccept = {
                                if (acceptingTripDocId != null) return@SingleTripItem
                                val tripKey = trip.doc_id.ifBlank { trip.trip_id }
                                acceptingTripDocId = trip.doc_id
                                tripAlreadyTakenMessage = null

                                // Stop audible alert and dismiss alert banner immediately
                                TaxiDispatchService.activeInstance?.stopLoudAlert()
                                TaxiDispatchServiceState.triggerNewTripAlert(null)

                                val driverId = FirebaseManager.getDriverId(context)

                                scope.launch {
                                    try {
                                        val result = FirebaseManager.acceptTrip(
                                            context = context,
                                            tripDocId = trip.doc_id,
                                            driverId = driverId
                                        )
                                        when (result) {
                                            is FirebaseManager.AcceptTripResult.Success -> {
                                                // Winner: First driver to accept gets assigned the trip!
                                                TaxiDispatchServiceState.setActiveTrip(result.updatedTrip)
                                                TaxiDispatchServiceState.setOpenTrips(emptyList())
                                                activeTripStep = 1
                                                dispatchEnteredOtp = ""
                                            }
                                            is FirebaseManager.AcceptTripResult.AlreadyTaken -> {
                                                // Another driver accepted first! Squelch active trip state so this driver is NOT assigned
                                                if (TaxiDispatchServiceState.activeTrip.value?.doc_id == trip.doc_id) {
                                                    TaxiDispatchServiceState.setActiveTrip(null)
                                                }
                                                // Remove this trip immediately from the local open trips list
                                                val currentOpen = TaxiDispatchServiceState.openTrips.value.toMutableList()
                                                currentOpen.removeAll { (it.doc_id.ifBlank { it.trip_id }) == tripKey }
                                                TaxiDispatchServiceState.setOpenTrips(currentOpen)

                                                tripAlreadyTakenMessage = context.getString(R.string.trip_already_taken)
                                            }
                                            is FirebaseManager.AcceptTripResult.Error -> {
                                                android.util.Log.e("DispatchScreen", "Could not accept: ${result.message}")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        android.util.Log.e("DispatchScreen", "Error accepting trip: ${t.message}", t)
                                    } finally {
                                        acceptingTripDocId = null
                                    }
                                }
                            },
                            onIgnore = {
                                if (acceptingTripDocId != null) return@SingleTripItem
                                TaxiDispatchService.activeInstance?.stopLoudAlert()
                                TaxiDispatchServiceState.ignoreTrip(trip.doc_id.ifBlank { trip.trip_id })
                                if (isAlerting) {
                                    TaxiDispatchServiceState.triggerNewTripAlert(null)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Step Progress Indicator Header
 */
@Composable
fun TripStepProgressHeader(currentStep: Int) {
    val steps = listOf(
        stringResource(R.string.pickup_location),
        stringResource(R.string.start_trip),
        stringResource(R.string.nav_meter)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandWhite, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, title ->
            val stepNumber = index + 1
            val isCurrent = currentStep == stepNumber
            val isCompleted = currentStep > stepNumber

            val bgColor = when {
                isCompleted -> Color(0xFF22C55E)
                isCurrent -> Color(0xFF0F172A)
                else -> Color(0xFFE2E8F0)
            }
            val textColor = when {
                isCompleted -> Color(0xFF16A34A)
                isCurrent -> Color(0xFF0F172A)
                else -> Color(0xFF94A3B8)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(bgColor),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCompleted) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    } else {
                        Text(
                            text = "$stepNumber",
                            color = if (isCurrent) Color.White else Color(0xFF64748B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                    color = textColor
                )
            }

            if (index < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(1.5.dp)
                        .background(if (currentStep > stepNumber) Color(0xFF22C55E) else Color(0xFFE2E8F0))
                )
            }
        }
    }
}

/**
 * STEP 1: Pickup Navigation & Customer Details View
 */
@Composable
fun ActiveTripPickupStep(
    trip: FirestoreTrip,
    onProceedToVerification: () -> Unit,
    onCancelTrip: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandWhite, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Step Banner
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.navigate_pickup),
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                color = Color(0xFF0F172A)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF1F5F9))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "ID: ${trip.trip_id.ifBlank { trip.doc_id.take(8) }}",
                    color = Color(0xFF334155),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        // Customer Contact Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = trip.customer_name.orEmpty().ifBlank { "Customer" },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF0F172A)
                )
                Text(
                    text = trip.customer_phone.orEmpty().ifBlank { "No phone" },
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
            }

            if (!trip.customer_phone.isNullOrBlank()) {
                Button(
                    onClick = {
                        com.trustyyellowcabs.driver.util.PhoneCallUtils.makeCall(context, trip.customer_phone.orEmpty())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(15.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_call), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Locations (Step 1 only has Pickup Map button)
        LocationRow(
            label = stringResource(R.string.pickup_location),
            address = trip.pickup_location.orEmpty(),
            tint = OnlineGreen,
            onMapClick = {
                com.trustyyellowcabs.driver.util.MapNavigationUtils.openGoogleMaps(context, trip.pickup_location.orEmpty())
            }
        )
        LocationRow(
            label = stringResource(R.string.drop_location),
            address = trip.drop_location.orEmpty(),
            tint = BrandRed,
            onMapClick = null
        )

        // Category & Trip Type in Step 1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (trip.is_package || trip.trip_type.equals("PACKAGE", ignoreCase = true) || (trip.hour_fare != null && trip.hour_fare > 0.0)) "PACKAGE TRIP" else "REGULAR TRIP",
                color = Color(0xFF0F172A),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = trip.vehicle_category.orEmpty().ifEmpty { "Standard" },
                color = Color(0xFF334155),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Big Primary Button to Advance to Next Step
        Button(
            onClick = onProceedToVerification,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("btn_proceed_to_start")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${stringResource(R.string.reached_pickup)} >>", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Secondary Support Options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { com.trustyyellowcabs.driver.util.PhoneCallUtils.callOffice(context) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = "Office", modifier = Modifier.size(14.dp), tint = Color(0xFF334155))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.call_office), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
            }
            OutlinedButton(
                onClick = onCancelTrip,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                border = BorderStroke(1.dp, Color(0xFFFECACA)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Icon(Icons.Default.Cancel, contentDescription = "Cancel", modifier = Modifier.size(14.dp), tint = Color(0xFFDC2626))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.reject_trip), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
            }
        }
    }
}

/**
 * STEP 2: Start Trip & Verification Step
 */
@Composable
fun ActiveTripVerificationStep(
    trip: FirestoreTrip,
    enteredOtp: String,
    onOtpChange: (String) -> Unit,
    isOtpCorrect: Boolean,
    onBackToNavigation: () -> Unit,
    onStartMeter: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandWhite, RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Step Banner with Step title and single Trip ID Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.start_trip),
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                color = Color(0xFF0F172A)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFF1F5F9))
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "ID: ${trip.trip_id.ifBlank { trip.doc_id.take(8) }}",
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        // OTP Instruction
        Text(
            text = stringResource(R.string.enter_otp_hint),
            fontSize = 11.sp,
            color = Color(0xFF64748B),
            textAlign = TextAlign.Center
        )

        // OTP Input Field
        OutlinedTextField(
            value = enteredOtp,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }
                if (digits.length <= 4) {
                    onOtpChange(digits)
                }
            },
            label = { Text(stringResource(R.string.passenger_otp)) },
            placeholder = { Text("4-digit OTP") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (isOtpCorrect) Color(0xFF2ECC71) else Color(0xFF64748B)
                )
            },
            trailingIcon = {
                if (isOtpCorrect) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2ECC71))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isOtpCorrect) Color(0xFF2ECC71) else Color(0xFF0F172A),
                focusedLabelColor = if (isOtpCorrect) Color(0xFF2ECC71) else Color(0xFF0F172A),
                unfocusedBorderColor = Color(0xFFCBD5E1)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dispatch_otp_input")
        )

        if (isOtpCorrect) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE8F5E9), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.verified_ready), color = Color(0xFF2E7D32), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else if (enteredOtp.length == 4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF0F0), shape = RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Error, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.incorrect_otp), color = Color(0xFFDC2626), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Start Meter Button (strictly guarded by valid OTP verification)
        Button(
            onClick = onStartMeter,
            enabled = isOtpCorrect,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF16A34A),
                disabledContainerColor = Color(0xFFCBD5E1)
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("btn_start_taxi_meter")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.start_taxi_meter), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            }
        }

        // Secondary Options: Back to Step 1 and Single Office Call Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBackToNavigation,
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF8FAFC), contentColor = Color(0xFF334155)),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("btn_back_to_pickup")
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF334155))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.btn_back), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
            }

            OutlinedButton(
                onClick = { com.trustyyellowcabs.driver.util.PhoneCallUtils.callOffice(context) },
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFF8FAFC), contentColor = Color(0xFF0F172A)),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("btn_call_office_step2")
            ) {
                Icon(Icons.Default.Call, contentDescription = "Office Call", modifier = Modifier.size(15.dp), tint = Color(0xFF16A34A))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.office_call), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            }
        }
    }
}

/**
 * STEP 3: Merged Step 3 & Live Meter in Same Page
 */
@Composable
fun ActiveTripInProgressStep(
    trip: FirestoreTrip,
    viewModel: TaxiMeterViewModel,
    onEndTrip: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.serviceState.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // --- 1. ACTIVE DISPATCH TRIP DETAILS (STEP 3) ---
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandWhite),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.notif_trip_in_progress),
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = Color(0xFF16A34A),
                            letterSpacing = 0.5.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "ID: ${trip.trip_id.ifBlank { trip.doc_id.take(8) }}",
                            color = Color(0xFF334155),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                // Customer Contact Row with Call Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = trip.customer_name.orEmpty().ifBlank { "Customer" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF0F172A)
                        )
                        Text(
                            text = trip.customer_phone.orEmpty().ifBlank { "No phone" },
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (!trip.customer_phone.isNullOrBlank()) {
                        Button(
                            onClick = {
                                com.trustyyellowcabs.driver.util.PhoneCallUtils.makeCall(context, trip.customer_phone.orEmpty())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .width(115.dp)
                                .height(38.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(15.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.btn_call), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Drop Location with DROP MAP Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFDC2626))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(stringResource(R.string.drop_location), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                            Text(
                                text = trip.drop_location.orEmpty().ifEmpty { "Drop location not specified" },
                                fontSize = 12.sp,
                                color = Color(0xFF0F172A),
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = {
                            com.trustyyellowcabs.driver.util.MapNavigationUtils.openGoogleMaps(context, trip.drop_location.orEmpty())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .width(115.dp)
                            .height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Directions,
                            contentDescription = "Drop Map",
                            modifier = Modifier.size(15.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.navigate_drop), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Call Office Button
                Button(
                    onClick = {
                        com.trustyyellowcabs.driver.util.PhoneCallUtils.callOffice(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call Office",
                        tint = BrandRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${stringResource(R.string.call_office)}: +91 422 359 6446",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }
            }
        }

        // --- 2. LIVE TAXI METER PANEL (MERGED IN SAME PAGE) ---
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandWhite),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Meter Mode Pill & Backend Trip ID
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFEF2F2), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (state.isPackageMeter) "${state.packageName.ifBlank { "PACKAGE METER" }.uppercase(java.util.Locale.ROOT)} ACTIVE" else "LIVE TAXI METER ACTIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandRed,
                            letterSpacing = 1.sp
                        )
                    }

                    val backendTripId = trip.trip_id.ifBlank { trip.doc_id.take(8) }
                    if (backendTripId.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFEFF6FF), RoundedCornerShape(50))
                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "TRIP ID: $backendTripId",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1D4ED8),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Vehicle Speed Display
                Text(
                    text = stringResource(R.string.speed),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 1.5.sp
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${state.speedKmH.toInt()}",
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        color = BrandRed,
                        letterSpacing = (-1.0).sp,
                        modifier = Modifier.testTag("live_speed_display")
                    )
                    Text(
                        text = " KM/H",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandRed,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3-Column Info Matrix: DISTANCE | DURATION | STANDBY
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = Color(0xFFF1F5F9), shape = RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Distance
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.distance),
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${String.format(java.util.Locale.ROOT, "%.2f", state.distanceKm)} KM",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0F172A)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFF1F5F9))
                    )

                    // Duration
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1.1f)
                    ) {
                        Text(
                            text = stringResource(R.string.duration),
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = com.trustyyellowcabs.driver.service.TaxiMeterService.formatDuration(state.durationSeconds),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0F172A)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color(0xFFF1F5F9))
                    )

                    // Standby
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = stringResource(R.string.standby),
                            fontSize = 9.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = com.trustyyellowcabs.driver.service.TaxiMeterService.formatDuration(state.waitingSeconds),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF475569)
                        )
                    }
                }

                if (state.isPackageMeter) {
                    val hourRate = if (state.packagePerHourRate > 0.0) state.packagePerHourRate else state.packageBaseFare
                    val kmRate = if (state.packagePerKmRate > 0.0) state.packagePerKmRate else state.extraKmRate
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rate: ₹${hourRate.toInt()}/Hr + ₹${kmRate.toInt()}/KM",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "Live Fare: ₹${String.format(java.util.Locale.ROOT, "%.2f", state.currentFare)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF16A34A)
                        )
                    }
                }
            }
        }

        // --- 3. END TRIP ACTION (SWIPE TO END SLIDER) ---
        SwipeToEndButton(
            onSwipeComplete = onEndTrip,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("btn_complete_meter_and_trip")
        )
    }
}

/**
 * Clean Single Trip List Item (No bulky card, no green border, no "NEW" tag)
 */
@Composable
fun SingleTripItem(
    trip: FirestoreTrip,
    isAlerting: Boolean = false,
    isAccepting: Boolean = false,
    isActionDisabled: Boolean = false,
    onAccept: () -> Unit,
    onIgnore: () -> Unit
) {
    val formattedTime = remember(trip.created_at) {
        if (trip.created_at > 0) {
            val sdf = java.text.SimpleDateFormat("hh:mm a, dd MMM", java.util.Locale.getDefault())
            sdf.format(java.util.Date(trip.created_at))
        } else {
            "Just now"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrandWhite, RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header Row: Trip ID & Vehicle Category & Fare
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Trip #${trip.trip_id.ifBlank { trip.doc_id.take(6) }}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF0F172A)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = trip.vehicle_category.orEmpty().ifEmpty { "CAB" }.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF334155)
                    )
                }

                if (trip.is_package || (trip.hour_fare != null && trip.hour_fare > 0.0)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFDBEAFE))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PACKAGE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D4ED8)
                        )
                    }
                }
            }

            if (trip.estimated_fare != null && trip.estimated_fare > 0.0) {
                Text(
                    text = "₹${trip.estimated_fare.toInt()}",
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    color = Color(0xFF0F172A)
                )
            }
        }

        // Time and Customer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = formattedTime, fontSize = 11.sp, color = Color(0xFF64748B))
            }

            if (!trip.customer_name.isNullOrBlank() && trip.customer_name != "Customer") {
                Text(text = trip.customer_name, fontSize = 11.sp, color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
            }
        }

        HorizontalDivider(color = Color(0xFFF1F5F9), thickness = 1.dp)

        // Pickup & Drop with Direction Buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OnlineGreen)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.pickup_location), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
                    Text(
                        text = trip.pickup_location.orEmpty().ifEmpty { "Pickup location not specified" },
                        fontSize = 12.sp,
                        color = Color(0xFF0F172A),
                        lineHeight = 16.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(BrandRed)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.drop_location), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                        Text(
                            text = trip.drop_location.orEmpty().ifEmpty { "Drop location not specified" },
                            fontSize = 12.sp,
                            color = Color(0xFF0F172A),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Bold Black Tariff Breakdown (No yellow box, bold black typography)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF8FAFC))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fare details breakdown - Bold Black
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (trip.base_fare != null && trip.base_fare > 0.0) {
                    Text(
                        text = "Base: ₹${trip.base_fare.toInt()}",
                        fontSize = 12.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (trip.kms_fare != null && trip.kms_fare > 0.0) {
                    Text(
                        text = "₹${trip.kms_fare}/km",
                        fontSize = 12.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                }
                if (trip.hour_fare != null && trip.hour_fare > 0.0) {
                    Text(
                        text = "₹${trip.hour_fare}/hr",
                        fontSize = 12.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                }
                if ((trip.base_fare == null || trip.base_fare == 0.0) && (trip.kms_fare == null || trip.kms_fare == 0.0)) {
                    Text(
                        text = "Type: ${trip.trip_type}",
                        fontSize = 12.sp,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Special Instructions / Passenger Notes (if any)
        if (!trip.notes.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFEF3C7))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFFB45309),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Note: ${trip.notes}",
                    fontSize = 11.sp,
                    color = Color(0xFF92400E),
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Action Buttons: SKIP & ACCEPT
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onIgnore,
                enabled = !isActionDisabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64748B)),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.btn_skip), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF64748B))
            }

            Button(
                onClick = onAccept,
                enabled = !isActionDisabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F172A),
                    disabledContainerColor = Color(0xFF475569)
                ),
                modifier = Modifier
                    .weight(1.5f)
                    .height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (isAccepting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_accepting), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White)
                } else {
                    Text(stringResource(R.string.btn_accept), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
fun LocationRow(
    label: String,
    address: String,
    tint: Color,
    onMapClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label.uppercase(), color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = address.ifBlank { "Location not specified" },
                    color = Color(0xFF0F172A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2
                )
            }
        }

        if (onMapClick != null) {
            Button(
                onClick = onMapClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Directions,
                    contentDescription = "Map",
                    modifier = Modifier.size(15.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("MAP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SwipeToEndButton(
    onSwipeComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var dragAmount by remember { mutableStateOf(0f) }
    val maxDragX = 240.dp
    val density = LocalDensity.current
    val maxDragPx = with(density) { maxDragX.toPx() }

    val animatedOffset by animateFloatAsState(
        targetValue = dragAmount,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "drag"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(Color(0xFFEAEDED))
            .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(29.dp)),
        contentAlignment = Alignment.CenterStart
    ) {
        val progress = if (maxDragPx > 0) (animatedOffset / maxDragPx).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        colors = listOf(Color(0xFFE74C3C), Color(0xFFC0392B))
                    )
                )
        )

        Text(
            text = "${stringResource(R.string.complete_trip)} >>",
            color = if (progress > 0.6f) Color.White else Color(0xFF7F8C8D),
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .size(58.dp)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, Color(0xFFC0392B), CircleShape)
                .pointerInput(maxDragPx) {
                    detectDragGestures(
                        onDragEnd = {
                            if (dragAmount >= maxDragPx * 0.82f) {
                                dragAmount = maxDragPx
                                onSwipeComplete()
                            } else {
                                dragAmount = 0f
                            }
                        },
                        onDragCancel = {
                            dragAmount = 0f
                        },
                        onDrag = { change, dragAmountDelta ->
                            change.consume()
                            dragAmount = (dragAmount + dragAmountDelta.x).coerceIn(0f, maxDragPx)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = "Swipe arrow",
                tint = Color(0xFFC0392B),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
