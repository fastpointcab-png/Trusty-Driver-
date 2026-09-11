package com.trustyyellowcabs.driver

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.location.LocationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.trustyyellowcabs.driver.data.AppDatabase
import com.trustyyellowcabs.driver.data.Trip
import com.trustyyellowcabs.driver.data.TripRepository
import com.trustyyellowcabs.driver.service.TaxiMeterService
import com.trustyyellowcabs.driver.service.TripStatus
import com.trustyyellowcabs.driver.ui.TaxiMeterViewModel
import com.trustyyellowcabs.driver.ui.TaxiMeterViewModelFactory
import com.trustyyellowcabs.driver.ui.theme.*
import com.trustyyellowcabs.driver.ui.DispatchScreen
import com.trustyyellowcabs.driver.ui.DriverLoginScreen
import com.trustyyellowcabs.driver.ui.DriverTermsScreen
import com.trustyyellowcabs.driver.ui.components.DriverAvatar
import com.trustyyellowcabs.driver.service.TaxiDispatchService
import com.trustyyellowcabs.driver.service.TaxiDispatchServiceState
import com.trustyyellowcabs.driver.network.FirebaseManager
import com.trustyyellowcabs.driver.util.PdfReceiptGenerator
import com.trustyyellowcabs.driver.util.FareBreakdownHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import java.io.FileOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.stringResource
import com.trustyyellowcabs.driver.util.LocaleHelper

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        val wrappedContext = LocaleHelper.wrapContext(newBase)
        super.attachBaseContext(wrappedContext)
    }

    private lateinit var viewModel: TaxiMeterViewModel

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleHelper.init(this)

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = TripRepository(database.tripDao())
        
        viewModel = ViewModelProvider(
            this, 
            TaxiMeterViewModelFactory(repository, applicationContext)
        )[TaxiMeterViewModel::class.java]

        if (intent?.getBooleanExtra("OPEN_DISPATCH_ALERT", false) == true) {
            viewModel.setNavigation("DISPATCH")
        }

        // Pre-initialize the TTS voice engine immediately for instant voice announcements
        com.trustyyellowcabs.driver.util.TtsAnnouncer.preInitialize(this)

        // Initialize Firebase and start listening to backend control document immediately
        FirebaseManager.ensureInitialized(applicationContext)

        // Prevent FCM hard-failure exceptions on emulators and devices without GCM provisioning
        try {
            // Overwrite any persisted auto-init preference to keep FCM idle
            val fcmPrefs = applicationContext.getSharedPreferences("com.google.firebase.messaging", Context.MODE_PRIVATE)
            fcmPrefs.edit().putBoolean("auto_init", false).apply()

            val fcm = com.google.firebase.messaging.FirebaseMessaging.getInstance()
            fcm.isAutoInitEnabled = false

            // Only attempt token retrieval on non-emulator physical hardware with verified Google Play Services
            if (!com.trustyyellowcabs.driver.security.AppSecurityShield.isEmulator()) {
                val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                val resultCode = availability.isGooglePlayServicesAvailable(this)
                if (resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS) {
                    val prefs = getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                    val existingToken = prefs.getString("driver_fcm_token", null)
                    if (existingToken.isNullOrBlank()) {
                        fcm.token.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val token = task.result
                                if (!token.isNullOrBlank()) {
                                    prefs.edit().putString("driver_fcm_token", token).apply()
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        FirebaseManager.updateDriverFcmToken(applicationContext, token)
                                    }
                                }
                            } else {
                                android.util.Log.i("MainActivity", "FCM token registration skipped: ${task.exception?.message}")
                            }
                        }
                    }
                }
            } else {
                android.util.Log.i("MainActivity", "Streaming emulator environment detected: FCM background sync suppressed. Realtime Firestore sync active.")
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "FCM setup handled: ${e.message}")
        }

        setContent {
            MyApplicationTheme {
                MainLayout(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TaxiDispatchServiceState.setAppInForeground(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.cancel(3003)
    }

    override fun onResume() {
        super.onResume()
        TaxiDispatchServiceState.setAppInForeground(true)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notificationManager?.cancel(3003)
    }

    override fun onPause() {
        super.onPause()
        TaxiDispatchServiceState.setAppInForeground(false)
    }

    override fun onStop() {
        super.onStop()
        TaxiDispatchServiceState.setAppInForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_DISPATCH_ALERT", false)) {
            viewModel.setNavigation("DISPATCH")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TaxiDispatchServiceState.setAppInForeground(false)
        com.trustyyellowcabs.driver.util.TtsAnnouncer.shutdown()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@RequiresApi(Build.VERSION_CODES.M)
@Composable
fun MainLayout(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val serviceState by viewModel.serviceState.collectAsState()
    val sDriverId by viewModel.driverId.collectAsState()
    val sDriverName by viewModel.driverName.collectAsState()
    val sVehicleNum by viewModel.vehicleNumber.collectAsState()
    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()

    val isOnline by TaxiDispatchServiceState.isOnline.collectAsState()
    val openTrips by TaxiDispatchServiceState.openTrips.collectAsState()
    val activeTrip by TaxiDispatchServiceState.activeTrip.collectAsState()
    val latestTripAlert by TaxiDispatchServiceState.latestTripAlert.collectAsState()

    val isDriverLoggedIn by viewModel.isDriverLoggedIn.collectAsState()
    val isLoginNeeded = !isDriverLoggedIn || sDriverId.trim().isEmpty() || sDriverName.trim().isEmpty()
    val prefs = remember(context) { context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE) }
    var isTermsAccepted by remember {
        mutableStateOf(prefs.getBoolean("terms_and_conditions_accepted", false))
    }

    // Real-time single-device session and Admin block enforcement:
    // Maintain local session and check device validity on app launches
    LaunchedEffect(sDriverId) {
        if (sDriverId.isNotBlank()) {
            val bound = FirebaseManager.bindDriverDeviceSession(context, sDriverId)
            if (!bound) {
                scope.launch(Dispatchers.Main) {
                    TaxiDispatchService.stopService(context)
                    TaxiDispatchServiceState.setOnline(false)
                    viewModel.logoutDriver(context)
                    val msg = "Driver ID is logged in on another device. Only one device is allowed at a time."
                    android.util.Log.w("MainActivity", msg)
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                return@LaunchedEffect
            }
            FirebaseManager.startListeningToDriverSession(context, sDriverId) { message ->
                scope.launch(Dispatchers.Main) {
                    TaxiDispatchService.stopService(context)
                    TaxiDispatchServiceState.setOnline(false)
                    viewModel.logoutDriver(context)
                    android.util.Log.w("MainActivity", "Driver session terminated: $message")
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else {
            FirebaseManager.stopListeningToDriverSession()
        }
    }

    // State to check if required permissions and location settings are enabled
    val initialFine = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val initialNotification = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val initialBackground = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val initialGps = remember(context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    val initialBattery = remember(context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    var hasFineState by remember { mutableStateOf(initialFine) }
    var hasNotificationState by remember { mutableStateOf(initialNotification) }
    var hasBackgroundState by remember { mutableStateOf(initialBackground) }
    var gpsEnabledState by remember { mutableStateOf(initialGps) }
    var batteryOptimizationIgnoredState by remember { mutableStateOf(initialBattery) }

    fun checkPermissionsAndGps() {
        hasFineState = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasNotificationState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        hasBackgroundState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsEnabledState = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        batteryOptimizationIgnoredState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    // Refresh status when returning to app
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkPermissionsAndGps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var autoPromptStage by remember { mutableStateOf(0) }

    // Request launchers for different permissions
    val foregroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        checkPermissionsAndGps()
        val isFineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (isFineGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationState) {
                autoPromptStage = 2
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundState) {
                autoPromptStage = 3
            } else {
                autoPromptStage = 4
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationState) {
                autoPromptStage = 2
            } else {
                autoPromptStage = 4
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkPermissionsAndGps()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundState && hasFineState) {
            autoPromptStage = 3
        } else {
            autoPromptStage = 4
        }
    }

    val backgroundPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        checkPermissionsAndGps()
        autoPromptStage = 4
    }

    LaunchedEffect(Unit) {
        checkPermissionsAndGps()
        if (hasFineState && hasNotificationState && hasBackgroundState && gpsEnabledState) {
            autoPromptStage = 4
        } else {
            // Slight delay for a smoother visual startup, then begin automatic prompts
            kotlinx.coroutines.delay(800)
            if (!hasFineState) {
                autoPromptStage = 1
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationState) {
                autoPromptStage = 2
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundState) {
                autoPromptStage = 3
            } else {
                autoPromptStage = 4
            }
        }
    }

    LaunchedEffect(autoPromptStage) {
        when (autoPromptStage) {
            1 -> {
                val perms = arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                foregroundPermissionLauncher.launch(perms)
            }
            2 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationState) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    autoPromptStage = 3
                }
            }
            3 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundState && hasFineState) {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    autoPromptStage = 4
                }
            }
        }
    }

    val isAllGrantedAndGpsOn = hasFineState && hasNotificationState && hasBackgroundState && gpsEnabledState && batteryOptimizationIgnoredState

    if (!isAllGrantedAndGpsOn) {
        MandatoryPermissionsGateScreen(
            context = context,
            hasFine = hasFineState,
            hasNotification = hasNotificationState,
            hasBackground = hasBackgroundState,
            gpsEnabled = gpsEnabledState,
            batteryOptimizationIgnored = batteryOptimizationIgnoredState,
            onRequestForeground = {
                val perms = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                foregroundPermissionLauncher.launch(perms.toTypedArray())
            },
            onRequestNotification = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onRequestBackground = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            },
            onOpenGpsSettings = {
                try {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Could not open GPS settings: ${e.message}", e)
                }
            },
            onRequestBatteryOptimization = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (ex: Exception) {
                        android.util.Log.e("MainActivity", "Could not open battery settings", ex)
                    }
                }
            },
            onOpenAppSettings = {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Could not open app settings: ${e.message}", e)
                }
            }
        )
    } else if (isLoginNeeded && !isTermsAccepted) {
        DriverTermsScreen(
            onTermsAccepted = {
                prefs.edit().putBoolean("terms_and_conditions_accepted", true).apply()
                isTermsAccepted = true
            }
        )
    } else if (isLoginNeeded) {
        DriverLoginScreen(viewModel = viewModel)
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                // Only show bottom navigation when NOT actively tracking a live trip in real-time
                if (serviceState.status != TripStatus.RUNNING && serviceState.status != TripStatus.PAUSED) {
                    NavigationBar(
                        containerColor = TaxiWhite,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = Slate100,
                                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                            )
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        NavigationBarItem(
                            selected = currentScreen == "DISPATCH",
                            onClick = { viewModel.setNavigation("DISPATCH") },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        if (activeTrip != null) {
                                            Badge(containerColor = TaxiRed) {
                                                Text("1", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        } else if (isOnline && openTrips.isNotEmpty()) {
                                            Badge(containerColor = Color(0xFF2ECC71)) {
                                                Text("${openTrips.size}", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Home, contentDescription = "Home Dispatch")
                                }
                            },
                            label = { Text(stringResource(R.string.nav_home), fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TaxiRed,
                                selectedTextColor = TaxiRed,
                                unselectedTextColor = Slate400,
                                unselectedIconColor = Slate400,
                                indicatorColor = RedAccentBg
                            ),
                            modifier = Modifier.testTag("nav_btn_dispatch")
                        )
                        NavigationBarItem(
                            selected = currentScreen == "FORM" || currentScreen == "LIVE",
                            onClick = { 
                                viewModel.setNavigation(
                                    if (serviceState.status == TripStatus.RUNNING || serviceState.status == TripStatus.PAUSED) "LIVE" else "FORM"
                                ) 
                            },
                            icon = { Icon(Icons.Default.FrontHand, contentDescription = "Meter") },
                            label = { Text(stringResource(R.string.nav_meter), fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TaxiRed,
                                selectedTextColor = TaxiRed,
                                unselectedTextColor = Slate400,
                                unselectedIconColor = Slate400,
                                indicatorColor = RedAccentBg
                            ),
                            modifier = Modifier.testTag("nav_btn_meter")
                        )
                        NavigationBarItem(
                            selected = currentScreen == "HISTORY",
                            onClick = { viewModel.setNavigation("HISTORY") },
                            icon = { Icon(Icons.Default.History, contentDescription = "History") },
                            label = { Text(stringResource(R.string.nav_history), fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TaxiRed,
                                selectedTextColor = TaxiRed,
                                unselectedTextColor = Slate400,
                                unselectedIconColor = Slate400,
                                indicatorColor = RedAccentBg
                            ),
                            modifier = Modifier.testTag("nav_btn_history")
                        )
                        NavigationBarItem(
                            selected = currentScreen in listOf("SETTINGS", "OFFICE_PAYMENT", "LANGUAGE", "OFFICE_SUPPORT"),
                            onClick = { viewModel.setNavigation("SETTINGS") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                            label = { Text(stringResource(R.string.nav_settings), fontWeight = FontWeight.Bold, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TaxiRed,
                                selectedTextColor = TaxiRed,
                                unselectedTextColor = Slate400,
                                unselectedIconColor = Slate400,
                                indicatorColor = RedAccentBg
                            ),
                            modifier = Modifier.testTag("nav_btn_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TaxiBackground)
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    "FORM" -> DriverDetailsScreen(viewModel = viewModel, onStart = { mobile, isPackage ->
                        if (isPackage) {
                            viewModel.startTaxiTrip(
                                context = context,
                                customerMobile = mobile,
                                isPackage = true,
                                pkgName = viewModel.packageName.value,
                                pkgBaseFare = viewModel.packageBaseFare.value,
                                pkgIncludedKm = viewModel.packageIncludedKm.value,
                                pkgIncludedMinutes = viewModel.packageIncludedMinutes.value,
                                pkgExtraKmRate = viewModel.packageExtraKmRate.value,
                                pkgExtraTimeRate = viewModel.packageExtraTimeRate.value,
                                pkgWaitingCharge = 0.0,
                                pkgPerHourRate = viewModel.packagePerHourRate.value,
                                pkgPerKmRate = viewModel.packagePerKmRate.value
                            )
                        } else {
                            viewModel.startTaxiTrip(context, mobile)
                        }
                    })
                    "LIVE" -> LiveDisplayScreen(viewModel = viewModel, context = context)
                    "SUMMARY" -> TripSummaryScreen(viewModel = viewModel, context = context)
                    "DISPATCH" -> DispatchScreen(viewModel = viewModel)
                    "HISTORY" -> HistoryScreen(viewModel = viewModel)
                    "SETTINGS" -> SettingsScreen(viewModel = viewModel)
                    "OFFICE_PAYMENT" -> com.trustyyellowcabs.driver.ui.OfficePaymentScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.setNavigation("SETTINGS") }
                    )
                    "LANGUAGE" -> com.trustyyellowcabs.driver.ui.LanguageSelectionScreen(
                        onBack = { viewModel.setNavigation("SETTINGS") }
                    )
                    "OFFICE_SUPPORT" -> com.trustyyellowcabs.driver.ui.OfficeSupportScreen(
                        viewModel = viewModel,
                        onBack = { viewModel.setNavigation("SETTINGS") }
                    )
                    else -> DriverDetailsScreen(viewModel = viewModel, onStart = { mobile, isPackage ->
                        if (isPackage) {
                            viewModel.startTaxiTrip(
                                context = context,
                                customerMobile = mobile,
                                isPackage = true,
                                pkgName = viewModel.packageName.value,
                                pkgBaseFare = viewModel.packageBaseFare.value,
                                pkgIncludedKm = viewModel.packageIncludedKm.value,
                                pkgIncludedMinutes = viewModel.packageIncludedMinutes.value,
                                pkgExtraKmRate = viewModel.packageExtraKmRate.value,
                                pkgExtraTimeRate = viewModel.packageExtraTimeRate.value,
                                pkgWaitingCharge = 0.0,
                                pkgPerHourRate = viewModel.packagePerHourRate.value,
                                pkgPerKmRate = viewModel.packagePerKmRate.value
                            )
                        } else {
                            viewModel.startTaxiTrip(context, mobile)
                        }
                    })
                }
            }
        }
    }
}

// 1. DRIVER DETAILS ENTRY SCREEN
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverDetailsScreen(viewModel: TaxiMeterViewModel, onStart: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val sDriverName by viewModel.driverName.collectAsState()
    val sVehicleNum by viewModel.vehicleNumber.collectAsState()
    val sVehicleCat by viewModel.vehicleCategory.collectAsState()
    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()
    val sDriverPhotoUrl by viewModel.driverPhotoUrl.collectAsState()
    val sBaseFare by viewModel.baseFare.collectAsState()
    val sPerKmFare by viewModel.perKmFare.collectAsState()

    var showFareDialog by remember { mutableStateOf(false) }

    val initials = remember(sDriverName) {
        sDriverName.split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
            .ifEmpty { "TX" }
    }

    val selfieBitmap = remember(sDriverSelfiePath) {
        if (sDriverSelfiePath.isNotEmpty()) {
            try {
                BitmapFactory.decodeFile(sDriverSelfiePath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    var customerMobile by remember { mutableStateOf("") }
    var enteredOtp by remember { mutableStateOf("") }
    val defaultMode by viewModel.defaultMeterMode.collectAsState()
    var selectedMode by remember { mutableStateOf("REGULAR") } // "REGULAR" by default, or "PACKAGE" if switched

    LaunchedEffect(defaultMode) {
        selectedMode = defaultMode
    }

    val pkgName by viewModel.packageName.collectAsState()
    val pkgBaseFare by viewModel.packageBaseFare.collectAsState()
    val pkgIncludedKm by viewModel.packageIncludedKm.collectAsState()
    val pkgIncludedMinutes by viewModel.packageIncludedMinutes.collectAsState()
    val pkgExtraKmRate by viewModel.packageExtraKmRate.collectAsState()
    val pkgExtraTimeRate by viewModel.packageExtraTimeRate.collectAsState()
    val pkgWaitingCharge by viewModel.packageWaitingChargePerMin.collectAsState()
    val pkgPerHourRate by viewModel.packagePerHourRate.collectAsState()
    val pkgPerKmRate by viewModel.packagePerKmRate.collectAsState()
    val activeTrip by TaxiDispatchServiceState.activeTrip.collectAsState()

    val expectedTripOtp = remember(activeTrip?.otp, customerMobile) {
        val backendOtp = activeTrip?.otp?.trim()
        if (!backendOtp.isNullOrEmpty() && backendOtp.all { it.isDigit() } && backendOtp.length == 4) {
            backendOtp
        } else {
            val sanitized = customerMobile.filter { it.isDigit() }
            if (sanitized.length >= 10) {
                val last4 = sanitized.takeLast(4)
                val reversed = last4.reversed()
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
                val todayDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                val otpBuilder = StringBuilder()
                for (char in reversed) {
                    val digit = char.digitToIntOrNull() ?: 0
                    val incremented = (digit + todayDay) % 10
                    otpBuilder.append(incremented)
                }
                otpBuilder.toString()
            } else {
                ""
            }
        }
    }

    val isOtpCorrect = enteredOtp.length == 4 && expectedTripOtp.isNotEmpty() && enteredOtp == expectedTripOtp

    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isOtpCorrect) {
        if (isOtpCorrect) {
            keyboardController?.hide()
        }
    }

    if (showFareDialog) {
        SetFareRatesDialog(
            currentBaseFare = sBaseFare,
            currentPerKmFare = sPerKmFare,
            onDismiss = { showFareDialog = false },
            onSave = { newBase, newPerKm ->
                viewModel.updateBaseAndPerKmFare(newBase, newPerKm)
                showFareDialog = false
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Unified Meter Configuration Card with integrated Mode Switcher
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.5.dp, if (selectedMode == "PACKAGE") TaxiYellowGlow else Color(0xFFFFD4D4)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header inside the configuration card
                Text(
                    text = "METER CONFIGURATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = if (selectedMode == "PACKAGE") Color(0xFFB78103) else TaxiRed,
                    letterSpacing = 1.sp
                )

                // Seamless Switcher Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate100, shape = RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(
                                color = if (selectedMode == "REGULAR") TaxiRed else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                selectedMode = "REGULAR"
                                viewModel.setDefaultMeterMode("REGULAR")
                            }
                            .testTag("home_switch_regular_meter"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "REGULAR METER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedMode == "REGULAR") TaxiWhite else Slate600
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(
                                color = if (selectedMode == "PACKAGE") TaxiYellowGlow else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .then(
                                if (selectedMode == "PACKAGE") Modifier.border(1.dp, TaxiBlack, RoundedCornerShape(10.dp)) else Modifier
                            )
                            .clickable {
                                selectedMode = "PACKAGE"
                                viewModel.setDefaultMeterMode("PACKAGE")
                            }
                            .testTag("home_switch_package_meter"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "PACKAGE METER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedMode == "PACKAGE") TaxiBlack else Slate600
                        )
                    }
                }

                // Direct Rate Input Fields based on the selected mode
                if (selectedMode == "PACKAGE") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        var inputPerHourRate by remember { mutableStateOf(if (pkgPerHourRate <= 0.0) "350" else if (pkgPerHourRate % 1.0 == 0.0) pkgPerHourRate.toInt().toString() else pkgPerHourRate.toString()) }
                        var inputPerKmRate by remember { mutableStateOf(if (pkgPerKmRate <= 0.0) "25" else if (pkgPerKmRate % 1.0 == 0.0) pkgPerKmRate.toInt().toString() else pkgPerKmRate.toString()) }

                        LaunchedEffect(pkgPerHourRate) {
                            val currentLocalDouble = inputPerHourRate.toDoubleOrNull() ?: 0.0
                            if (currentLocalDouble != pkgPerHourRate && pkgPerHourRate > 0.0) {
                                inputPerHourRate = if (pkgPerHourRate % 1.0 == 0.0) pkgPerHourRate.toInt().toString() else pkgPerHourRate.toString()
                            }
                        }

                        LaunchedEffect(pkgPerKmRate) {
                            val currentLocalDouble = inputPerKmRate.toDoubleOrNull() ?: 0.0
                            if (currentLocalDouble != pkgPerKmRate && pkgPerKmRate > 0.0) {
                                inputPerKmRate = if (pkgPerKmRate % 1.0 == 0.0) pkgPerKmRate.toInt().toString() else pkgPerKmRate.toString()
                            }
                        }

                        OutlinedTextField(
                            value = inputPerHourRate,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() || it == '.' }) {
                                    inputPerHourRate = newValue
                                    val parsedRate = newValue.toDoubleOrNull() ?: 0.0
                                    val currentKmRate = inputPerKmRate.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePackageSettings(
                                        name = "1 Hour / 10 KM",
                                        baseFare = parsedRate,
                                        includedKm = 10.0,
                                        includedMinutes = 60,
                                        extraKmRate = currentKmRate,
                                        extraTimeRate = 2.0833,
                                        waitingCharge = 0.0,
                                        perHourRate = parsedRate,
                                        perKmRate = currentKmRate
                                    )
                                }
                            },
                            placeholder = { Text("350", color = Slate400, fontSize = 13.sp) },
                            label = { Text("Per Hour Fare (₹)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pkg_per_hour_rate_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TaxiRed,
                                unfocusedBorderColor = Slate200,
                                focusedLabelColor = TaxiRed,
                                unfocusedLabelColor = Slate400
                            )
                        )

                        OutlinedTextField(
                            value = inputPerKmRate,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() || it == '.' }) {
                                    inputPerKmRate = newValue
                                    val parsedKmRate = newValue.toDoubleOrNull() ?: 0.0
                                    val currentHourRate = inputPerHourRate.toDoubleOrNull() ?: 0.0
                                    viewModel.updatePackageSettings(
                                        name = "1 Hour / 10 KM",
                                        baseFare = currentHourRate,
                                        includedKm = 10.0,
                                        includedMinutes = 60,
                                        extraKmRate = parsedKmRate,
                                        extraTimeRate = 2.0833,
                                        waitingCharge = 0.0,
                                        perHourRate = currentHourRate,
                                        perKmRate = parsedKmRate
                                    )
                                }
                            },
                            readOnly = false,
                            placeholder = { Text("25", color = Slate400, fontSize = 13.sp) },
                            label = { Text("KMS Fare (₹/KM)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pkg_per_km_rate_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TaxiRed,
                                unfocusedBorderColor = Slate200,
                                focusedLabelColor = TaxiRed,
                                unfocusedLabelColor = Slate400
                            )
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        var inputBaseFare by remember { mutableStateOf(if (sBaseFare <= 0.0) "80" else if (sBaseFare % 1.0 == 0.0) sBaseFare.toInt().toString() else sBaseFare.toString()) }
                        var inputPerKmFare by remember { mutableStateOf(if (sPerKmFare <= 0.0) "28" else if (sPerKmFare % 1.0 == 0.0) sPerKmFare.toInt().toString() else sPerKmFare.toString()) }

                        LaunchedEffect(sBaseFare) {
                            val current = inputBaseFare.toDoubleOrNull() ?: 0.0
                            if (current != sBaseFare && sBaseFare > 0.0) {
                                inputBaseFare = if (sBaseFare % 1.0 == 0.0) sBaseFare.toInt().toString() else sBaseFare.toString()
                            }
                        }

                        LaunchedEffect(sPerKmFare) {
                            val current = inputPerKmFare.toDoubleOrNull() ?: 0.0
                            if (current != sPerKmFare && sPerKmFare > 0.0) {
                                inputPerKmFare = if (sPerKmFare % 1.0 == 0.0) sPerKmFare.toInt().toString() else sPerKmFare.toString()
                            }
                        }

                        OutlinedTextField(
                            value = inputBaseFare,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() || it == '.' }) {
                                    inputBaseFare = newValue
                                    val parsedBase = newValue.toDoubleOrNull() ?: 0.0
                                    val currentKm = inputPerKmFare.toDoubleOrNull() ?: 0.0
                                    viewModel.updateBaseAndPerKmFare(parsedBase, currentKm)
                                }
                            },
                            placeholder = { Text("80", color = Slate400, fontSize = 13.sp) },
                            label = { Text("Base Fare (₹)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("regular_base_fare_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TaxiRed,
                                unfocusedBorderColor = Slate200,
                                focusedLabelColor = TaxiRed,
                                unfocusedLabelColor = Slate400
                            )
                        )

                        OutlinedTextField(
                            value = inputPerKmFare,
                            onValueChange = { newValue ->
                                if (newValue.all { it.isDigit() || it == '.' }) {
                                    inputPerKmFare = newValue
                                    val parsedKm = newValue.toDoubleOrNull() ?: 0.0
                                    val currentBase = inputBaseFare.toDoubleOrNull() ?: 0.0
                                    viewModel.updateBaseAndPerKmFare(currentBase, parsedKm)
                                }
                            },
                            placeholder = { Text("28", color = Slate400, fontSize = 13.sp) },
                            label = { Text("KMS Fare (₹/KM)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("regular_per_km_fare_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TaxiRed,
                                unfocusedBorderColor = Slate200,
                                focusedLabelColor = TaxiRed,
                                unfocusedLabelColor = Slate400
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // CUSTOMER DETAILS CARD (with OTP hidden as requested)
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Slate100),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFFFF0F0), shape = RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = TaxiRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Trip Verification",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TaxiRed,
                        letterSpacing = 1.sp
                    )
                }

                HorizontalDivider(color = Slate500.copy(alpha = 0.1f))

                // Mobile number field
                OutlinedTextField(
                    value = customerMobile,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }
                        val sanitized = if (digits.length > 10) {
                            digits.takeLast(10)
                        } else {
                            digits
                        }
                        customerMobile = sanitized
                        if (sanitized.length == 10) {
                            keyboardController?.hide()
                        }
                    },
                    label = { Text("Passenger Mobile Number") },
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = "Phone icon", tint = Slate500) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    placeholder = { Text("Enter 10-digit mobile number") },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TaxiRed,
                        focusedLabelColor = TaxiRed,
                        unfocusedBorderColor = Slate200,
                        unfocusedLabelColor = Slate400,
                        focusedContainerColor = Color(0xFFFFFDFD)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_mobile_input")
                )

                if (customerMobile.length == 10) {
                    // OTP Input Field for local ride validation (Hidden correct validation OTP string as requested)
                    OutlinedTextField(
                        value = enteredOtp,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 4) {
                                enteredOtp = digits
                                if (digits.length == 4) {
                                    keyboardController?.hide()
                                }
                            }
                        },
                        label = { Text("Passenger Ride OTP") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "OTP Icon", tint = Slate500) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("Enter 4-digit OTP") },
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (isOtpCorrect) Color(0xFF2E7D32) else TaxiRed,
                            focusedLabelColor = if (isOtpCorrect) Color(0xFF2E7D32) else TaxiRed,
                            unfocusedBorderColor = if (isOtpCorrect) Color(0xFF2E7D32) else Slate200,
                            unfocusedLabelColor = Slate400,
                            focusedContainerColor = Color(0xFFFFFDFD)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("entered_otp_input"),
                        trailingIcon = {
                            if (isOtpCorrect) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Correct OTP", tint = Color(0xFF4CAF50))
                            }
                        }
                    )

                    if (isOtpCorrect) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9), shape = RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Valid",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OTP verified! Ready to initiate trip.",
                                color = Color(0xFF2E7D32),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (enteredOtp.length == 4) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFFF0F0), shape = RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Invalid",
                                tint = TaxiRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Incorrect OTP. Ask the passenger for correct OTP.",
                                color = TaxiRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "Please enter the passenger's 4-digit ride OTP to start meter.",
                            color = Slate500,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                } else if (customerMobile.isNotEmpty()) {
                    Text(
                        text = "Please enter 10 digits (${customerMobile.length}/10)",
                        color = Slate400,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        }

        // Sticky Start Trip Action Button with background gradient container at the bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            TaxiBackground.copy(alpha = 0.95f),
                            TaxiBackground
                        )
                    )
                )
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 20.dp)
        ) {
            // Modern, stylish Start Trip Action Button with a beautiful active gradient
            Button(
                onClick = {
                    onStart(customerMobile, selectedMode == "PACKAGE")
                },
                enabled = isOtpCorrect,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .shadow(if (isOtpCorrect) 6.dp else 0.dp, shape = RoundedCornerShape(18.dp))
                    .testTag("start_trip_button")
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (isOtpCorrect) {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFE60000), Color(0xFFFF5252)) // Vibrant active Taxi Red gradient
                                )
                            } else {
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFE2E8F0), Color(0xFFCBD5E1)) // Elegant Slate disabled gradient
                                )
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Start Ride icon",
                            tint = if (isOtpCorrect) Color.White else Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.start_trip),
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            color = if (isOtpCorrect) Color.White else Color(0xFF94A3B8),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

// 2. LIVE DISPLAY METER PANEL
@RequiresApi(Build.VERSION_CODES.M)
@Composable
fun LiveDisplayScreen(viewModel: TaxiMeterViewModel, context: Context) {
    val state by viewModel.serviceState.collectAsState()
    var showEndConfirmationDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            // Service stays up in foreground, nothing cleanup
        }
    }

    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()
    val sDriverPhotoUrl by viewModel.driverPhotoUrl.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Driver/Vehicle Info Card (Geometric Balance style) - Hidden during ongoing dispatch trip
        val activeDispatchTripState = com.trustyyellowcabs.driver.service.TaxiDispatchServiceState.activeTrip.collectAsState().value
        val hasLiveProfile = (state.driverName.trim().isNotEmpty() || state.vehicleNumber.trim().isNotEmpty()) && activeDispatchTripState == null
        if (hasLiveProfile) {
            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Slate100),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DriverAvatar(
                        driverName = state.driverName,
                        photoUrl = sDriverPhotoUrl,
                        localSelfiePath = sDriverSelfiePath,
                        size = 56.dp,
                        shape = RoundedCornerShape(16.dp),
                        fontSize = 20.sp
                    )

                    // Driver details
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.driverName.trim(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = "${state.vehicleCategory} • ${state.vehicleNumber.trim()}",
                            fontSize = 12.sp,
                            color = Slate500,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Sedan / category pill
                    Box(
                        modifier = Modifier
                            .background(GreenPillBg, shape = RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.vehicleCategory.uppercase(Locale.ROOT),
                            fontSize = 10.sp,
                            color = GreenPillText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (activeDispatchTripState != null) {
            val dTrip = activeDispatchTripState
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ONGOING DISPATCH TRIP",
                            fontWeight = FontWeight.Black,
                            color = TaxiRed,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        val ongoingTripId = dTrip.trip_id.orEmpty().ifBlank { dTrip.doc_id.take(8) }
                        if (ongoingTripId.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(RedAccentBg, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "ID: $ongoingTripId",
                                    color = TaxiRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Customer: ${dTrip.customer_name.orEmpty()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Slate900
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Mobile: ${dTrip.customer_phone.orEmpty()}",
                                fontSize = 13.sp,
                                color = Slate500,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Call Customer Button (Right-aligned, matching DROP MAP size)
                        if (!dTrip.customer_phone.isNullOrBlank()) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${dTrip.customer_phone}"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // Ignore dialer errors gracefully
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .width(115.dp)
                                    .height(38.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Dial", modifier = Modifier.size(15.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CALL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Drop location with solid DROP MAP button (straight-aligned, identical size)
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
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(8.dp)
                                    .background(Color(0xFFE74C3C), RoundedCornerShape(50))
                            )
                            Column {
                                Text("DROP LOCATION", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Slate400)
                                Text(
                                    text = dTrip.drop_location.orEmpty().ifEmpty { "Drop location not specified" },
                                    fontSize = 13.sp,
                                    color = Slate700,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        Button(
                            onClick = {
                                com.trustyyellowcabs.driver.util.MapNavigationUtils.openGoogleMaps(context, dTrip.drop_location.orEmpty())
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
                                contentDescription = "Navigate to Drop",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DROP MAP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Slate100)
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            com.trustyyellowcabs.driver.util.PhoneCallUtils.callOffice(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate100, contentColor = Slate900),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call Office",
                            tint = TaxiRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CALL OFFICE: +91 422 359 6446",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (state.isPackageMeter) {
            val hourRate = if (state.packagePerHourRate > 0.0) state.packagePerHourRate else state.packageBaseFare
            val kmRate = if (state.packagePerKmRate > 0.0) state.packagePerKmRate else state.extraKmRate

            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFFFFD4D4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Package Mode Indicator
                    Box(
                        modifier = Modifier
                            .background(RedAccentBg, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${state.packageName.ifBlank { "PACKAGE METER" }.uppercase(Locale.US)} ACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TaxiRed,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big Vehicle Speed Display instead of Fare as requested
                    Text(
                        text = "VEHICLE SPEED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400,
                        letterSpacing = 1.5.sp
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "${state.speedKmH.toInt()}",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            color = TaxiRed,
                            modifier = Modifier.testTag("live_speed_display")
                        )
                        Text(
                            text = " KM/H",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TaxiRed,
                            modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3-Column Info Matrix with thin vertical dividers for Package Meter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 1.dp, color = Slate100, shape = RoundedCornerShape(16.dp))
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Distance column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "DISTANCE",
                                fontSize = 10.sp,
                                color = Slate400,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${String.format("%.2f", state.distanceKm)} KM",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = Slate900
                            )
                        }

                        // Vertical Divider 1
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(Slate100)
                        )

                        // Total Time column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Text(
                                text = "DURATION",
                                fontSize = 10.sp,
                                color = Slate400,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = TaxiMeterService.formatDuration(state.durationSeconds),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = Slate900
                            )
                        }

                        // Vertical Divider 2
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(Slate100)
                        )

                        // Standby column
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "STANDBY",
                                fontSize = 10.sp,
                                color = Slate400,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = TaxiMeterService.formatDuration(state.waitingSeconds),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = Slate700
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Slate50, RoundedCornerShape(12.dp))
                            .border(1.dp, Slate100, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rate: ₹${hourRate.toInt()}/Hr + ₹${kmRate.toInt()}/KM",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate500
                        )
                        Text(
                            text = "Live Fare: ₹${String.format(Locale.US, "%.2f", state.currentFare)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF16A34A)
                        )
                    }
                }
            }
        } else {
            // The Digital Meter Panel in Geometric Balance
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "VEHICLE SPEED",
                    fontSize = 12.sp,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "${state.speedKmH.toInt()}",
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        color = TaxiRed,
                        letterSpacing = (-2.0).sp,
                        modifier = Modifier.testTag("live_speed_display")
                    )
                    Text(
                        text = " KM/H",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TaxiRed,
                        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3-Column Info Matrix with thin vertical dividers
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = Slate100, shape = RoundedCornerShape(20.dp))
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Distance column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "DISTANCE",
                            fontSize = 10.sp,
                            color = Slate400,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${String.format("%.2f", state.distanceKm)} KM",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Slate900
                        )
                    }

                    // Vertical Divider 1
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Slate100)
                    )

                    // Total Time column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text(
                            text = "DURATION",
                            fontSize = 10.sp,
                            color = Slate400,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = TaxiMeterService.formatDuration(state.durationSeconds),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Slate900
                        )
                    }

                    // Vertical Divider 2
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(36.dp)
                            .background(Slate100)
                    )

                    // Standby column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "STANDBY",
                            fontSize = 10.sp,
                            color = Slate400,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = TaxiMeterService.formatDuration(state.waitingSeconds),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Slate700
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))



        Spacer(modifier = Modifier.weight(1.0f))
        Spacer(modifier = Modifier.height(24.dp))

        // Controls Area: START/PAUSE/RESUME/FINISH
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // End Trip button (solid red, round dot inside next to text)
            Button(
                onClick = { showEndConfirmationDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = TaxiRed, contentColor = TaxiWhite),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag("end_trip_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(TaxiWhite, shape = RoundedCornerShape(50))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "END TRIP",
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = TaxiWhite,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        if (showEndConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showEndConfirmationDialog = false },
                title = { Text("End Trip?") },
                text = { Text("Are you sure you want to end and close the trip? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.endTaxiTrip(context)
                            showEndConfirmationDialog = false
                        }
                    ) {
                        Text("END TRIP", color = TaxiRed, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEndConfirmationDialog = false }) {
                        Text("CANCEL", color = Slate500, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

// 3. TRIP RECEIPT SUMMARY & UPI PAYMENT VIEW
@Composable
fun TripSummaryScreen(viewModel: TaxiMeterViewModel, context: Context) {
    val trip by viewModel.selectedTripForInvoice.collectAsState()

    val safeTrip = trip ?: return // Safely fallback if active null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = GreenPillText,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "TRIP BILLINGS COMPLETE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                color = GreenPillText,
                letterSpacing = 0.5.sp
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = "Total Charged Amount",
            fontSize = 11.sp,
            color = Slate500,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "₹${String.format("%.2f", safeTrip.totalFare)}",
            fontSize = 44.sp,
            fontWeight = FontWeight.Black,
            color = TaxiRed,
            letterSpacing = (-1.5).sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Printable Ticket visual card
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Slate100),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Trip Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TRUSTY YELLOW CAB INVOICE",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        color = TaxiRed,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = safeTrip.dateStr.split(",").firstOrNull() ?: "",
                        fontSize = 11.sp,
                        color = Slate500,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Slate100)
                Spacer(modifier = Modifier.height(16.dp))

                // Metadata list
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReceiptRow(label = "Trip Id", value = if (safeTrip.tripIdCode.isNotEmpty()) safeTrip.tripIdCode else safeTrip.id.toString())
                    ReceiptRow(label = "Driver Name", value = safeTrip.driverName)
                    ReceiptRow(label = "Vehicle Registration", value = safeTrip.vehicleNumber)
                    ReceiptRow(label = "Category", value = safeTrip.vehicleCategory)
                    val timeFormatter = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("hh:mm a")
                    ReceiptRow(label = "Start Timing", value = timeFormatter.format(Date(safeTrip.startTime)))
                    ReceiptRow(label = "End Timing", value = timeFormatter.format(Date(safeTrip.endTime)))
                    val startMapsUrl = getMapsUrlForTripLocation(safeTrip.startLatitude, safeTrip.startLongitude, safeTrip.startLocation)
                    val endMapsUrl = getMapsUrlForTripLocation(safeTrip.endLatitude, safeTrip.endLongitude, safeTrip.endLocation)
                    
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        ReceiptRow(
                            label = "Start Location", 
                            value = safeTrip.startLocation,
                            onClick = if (startMapsUrl.isNotEmpty()) { { openMapForTripLocation(context, safeTrip.startLatitude, safeTrip.startLongitude, safeTrip.startLocation) } } else null,
                            verticalAlignment = Alignment.Top
                        )
                        ReceiptRow(
                            label = "End Location", 
                            value = safeTrip.endLocation,
                            onClick = if (endMapsUrl.isNotEmpty()) { { openMapForTripLocation(context, safeTrip.endLatitude, safeTrip.endLongitude, safeTrip.endLocation) } } else null,
                            verticalAlignment = Alignment.Top
                        )
                    }
                    ReceiptRow(label = "Total Distance", value = "${String.format("%.2f", safeTrip.distance)} KM")
                    ReceiptRow(label = "Trip Time Details", value = TaxiMeterService.formatDuration(safeTrip.durationSeconds))
                    
                    HorizontalDivider(color = Slate100)

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text(
                            text = "CHARGES BREAKDOWN",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            color = TaxiRed,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        val breakdownItems = remember(safeTrip) {
                            FareBreakdownHelper.calculateBreakdown(safeTrip)
                        }

                        for (item in breakdownItems) {
                            val isAdjustment = item.label.contains("Adjustment", ignoreCase = true) || item.label.contains("Minimum Fare", ignoreCase = true)
                            val displayLabel = if (isAdjustment) {
                                "Minimum Fare Adjustment"
                            } else if (item.detail.isNotEmpty()) {
                                "${item.label} (${item.detail})"
                            } else {
                                item.label
                            }
                            ReceiptRow(
                                label = displayLabel,
                                value = "₹${String.format(java.util.Locale.US, "%.2f", item.amount)}",
                                singleLine = true
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = TaxiRed, thickness = 2.dp)
                Spacer(modifier = Modifier.height(16.dp))

                // Grand cash due
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("TOTAL NET DUE", fontWeight = FontWeight.Black, fontSize = 14.sp, color = Slate900, letterSpacing = 0.5.sp)
                    Text("₹ ${String.format("%.2f", safeTrip.totalFare)}", fontWeight = FontWeight.Black, fontSize = 24.sp, color = TaxiRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Slate100),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "RECEIPTS & PRINTS", 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = TaxiRed, 
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Export/Share PDF Receipt
                    OutlinedButton(
                        onClick = {
                            val pdfFile = PdfReceiptGenerator.generateTripReceipt(context, safeTrip)
                            if (pdfFile != null) {
                                sharePdfReceipt(context, pdfFile)
                            }
                        },
                        border = BorderStroke(1.dp, TaxiRed),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = TaxiRed)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Invoice", color = TaxiRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    // Print action
                    OutlinedButton(
                        onClick = {
                            val pdfFile = PdfReceiptGenerator.generateTripReceipt(context, safeTrip)
                            if (pdfFile != null) {
                                requestFilePrint(context, pdfFile)
                            }
                        },
                        border = BorderStroke(1.dp, Slate500),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1.0f)
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null, tint = Slate700)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Print Receipt", color = Slate700, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Sharing channels
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Slate100),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "TEXT CHANNEL SHARING", 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = TaxiRed, 
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                )

                val standardShares = buildTripShareText(safeTrip, includeCustomerMobile = false)
                val vendorShares = buildTripShareText(safeTrip, includeCustomerMobile = true)

                // Direct sharing channels (Customer number omitted for privacy)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val shareChannels = listOf(
                        Triple("WhatsApp", Color(0xFF25D366), Icons.Default.Share),
                        Triple("Telegram", Color(0xFF0088CC), Icons.Default.Send),
                        Triple("Copy", TaxiRed, Icons.Default.ContentCopy)
                    )

                    shareChannels.forEach { (label, color, icon) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(color.copy(alpha = 0.08f))
                                .border(BorderStroke(1.dp, color.copy(alpha = 0.25f)), RoundedCornerShape(16.dp))
                                .clickable {
                                    when (label) {
                                        "WhatsApp" -> launchPlatformShare(context, "com.whatsapp", standardShares)
                                        "Telegram" -> launchTelegramOrChooser(context, standardShares)
                                        "Copy" -> {
                                            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Trusty Yellow Cab Details", standardShares))
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = color,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// 4. TRIPS LOG DETAILS
@Composable
fun HistoryScreen(viewModel: TaxiMeterViewModel) {
    val history by viewModel.tripHistory.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trip_history),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TaxiRed,
                    letterSpacing = 1.sp
                )
            }
            if (history.isNotEmpty()) {
                var showClearDialog by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    border = BorderStroke(1.dp, TaxiRed),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TaxiRed),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear History",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CLEAR ALL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (showClearDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        title = { Text("Clear All History?") },
                        text = { Text("Are you sure you want to permanently delete all trip records? This cannot be undone.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.clearTripHistory()
                                    showClearDialog = false
                                }
                            ) {
                                Text("CLEAR ALL", color = TaxiRed, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog = false }) {
                                Text("CANCEL", color = Slate500)
                            }
                        }
                    )
                }
            }
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Slate100
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("No billing logs currently saved", color = Slate500, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("trips_logs_list")
            ) {
                items(history) { trip ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.viewInvoice(trip) }
                            .border(1.dp, Slate100, RoundedCornerShape(20.dp)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = trip.vehicleNumber,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 15.sp,
                                            color = Slate900
                                        )
                                        val displayTripId = if (trip.tripIdCode.isNotBlank()) trip.tripIdCode else trip.id.toString()
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = displayTripId,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate700
                                            )
                                        }
                                    }
                                    Text(
                                         text = trip.dateStr,
                                         fontSize = 11.sp,
                                         color = Slate400,
                                         fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "₹${String.format("%.2f", trip.totalFare)}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TaxiRed
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = Slate100)
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint = Slate400,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = trip.vehicleCategory,
                                        fontSize = 12.sp,
                                        color = Slate500,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Map,
                                        contentDescription = null,
                                        tint = Slate400,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${String.format("%.2f", trip.distance)} KM",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate900
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 5. RATES CARD SETTINGS PORTAL
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sDriverId by viewModel.driverId.collectAsState()
    val sDriverName by viewModel.driverName.collectAsState()
    val sVehicleNum by viewModel.vehicleNumber.collectAsState()
    val sVehicleCat by viewModel.vehicleCategory.collectAsState()
    val isNightActive by viewModel.isNightModeActive.collectAsState()
    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()
    val sDriverPhotoUrl by viewModel.driverPhotoUrl.collectAsState()
    val officePaymentInfo by viewModel.officePaymentInfo.collectAsState()

    var driverNameVal by remember { mutableStateOf(sDriverName) }
    var vehicleNumVal by remember { mutableStateOf(sVehicleNum) }
    var vehicleCatVal by remember { mutableStateOf(sVehicleCat) }
    var defaultNightVal by remember { mutableStateOf(isNightActive) }
    var isEditingProfile by remember { mutableStateOf(sDriverName.trim().isEmpty() || sVehicleNum.trim().isEmpty() || sDriverSelfiePath.trim().isEmpty()) }

    LaunchedEffect(sDriverName, sVehicleNum, sVehicleCat, isNightActive, sDriverSelfiePath) {
        driverNameVal = sDriverName
        vehicleNumVal = sVehicleNum
        vehicleCatVal = sVehicleCat
        defaultNightVal = isNightActive
        if (sDriverName.trim().isEmpty() || sVehicleNum.trim().isEmpty() || sDriverSelfiePath.trim().isEmpty()) {
            isEditingProfile = true
        }
    }

    LaunchedEffect(isNightActive) {
        defaultNightVal = isNightActive
    }

    // Auto-save driver details whenever driver details change
    LaunchedEffect(driverNameVal, vehicleNumVal, vehicleCatVal, defaultNightVal) {
        if (driverNameVal.trim().isNotEmpty() && vehicleNumVal.trim().isNotEmpty()) {
            viewModel.saveDriverDetails(
                name = driverNameVal,
                vNumber = vehicleNumVal,
                category = vehicleCatVal,
                isNight = defaultNightVal
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header with TRUSTY YELLOW CAB and compact Logout Button on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .background(TaxiRed, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "TRUSTY YELLOW CAB",
                    color = TaxiWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }

            // Compact clean Logout button at top right
            OutlinedButton(
                onClick = {
                    viewModel.logoutDriver(context)
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Slate100,
                    contentColor = Slate800
                ),
                border = BorderStroke(1.dp, Slate300),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("logout_driver_btn")
            ) {
                Text(
                    text = stringResource(R.string.logout),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Slate800
                )
            }
        }

        // 1. DRIVER PROFILE CARD (Prominently visible boxy card at top - kept as requested)
        Card(
            colors = CardDefaults.cardColors(containerColor = TaxiWhite),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("driver_profile_settings_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DRIVER PROFILE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate700,
                        letterSpacing = 1.sp
                    )

                    Box(
                        modifier = Modifier
                            .background(Color(0xFFDCFCE7), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "VERIFIED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF15803D),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Driver Photo Preview (Clean, prominent display showing full photo without cutting)
                    DriverAvatar(
                        driverName = driverNameVal,
                        photoUrl = sDriverPhotoUrl,
                        localSelfiePath = sDriverSelfiePath,
                        size = 84.dp,
                        shape = RoundedCornerShape(12.dp),
                        fontSize = 28.sp,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = driverNameVal.ifEmpty { "Trusty Driver" },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            maxLines = 1
                        )

                        if (sDriverId.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFFEF3C7))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "DRIVER ID: $sDriverId",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB45309)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Slate100)

                // Vehicle Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "VEHICLE NUMBER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate500,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = vehicleNumVal.ifEmpty { "Not Set" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "CATEGORY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate500,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = vehicleCatVal.ifEmpty { "SEDAN" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate800
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Slate200.copy(alpha = 0.6f), thickness = 1.dp)

        // 2. MODERN CLEAN OFFICE PAYMENT SECTION (Taxi app button style)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "OFFICE PAYMENT",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate600,
                letterSpacing = 1.sp
            )

            val dueBadge = if (officePaymentInfo.amount > 0.0) {
                "Due: ₹${String.format(Locale.US, "%.0f", officePaymentInfo.amount)}"
            } else {
                "No Dues"
            }
            val dueColor = if (officePaymentInfo.amount > 0.0) Color(0xFFB45309) else Color(0xFF15803D)
            val noteSubtitle = if (officePaymentInfo.amount > 0.0 && officePaymentInfo.paymentNote.isNotBlank()) {
                "Note: ${officePaymentInfo.paymentNote}"
            } else null

            SettingsMenuButton(
                icon = Icons.Outlined.Payment,
                title = "Office Payment Portal",
                subtitle = noteSubtitle,
                trailingBadge = dueBadge,
                trailingBadgeColor = dueColor,
                modifier = Modifier.testTag("btn_open_office_payment"),
                onClick = {
                    viewModel.setNavigation("OFFICE_PAYMENT")
                }
            )
        }

        HorizontalDivider(color = Slate200.copy(alpha = 0.6f), thickness = 1.dp)

        // 3. MODERN CLEAN LANGUAGE SELECTOR (Taxi app button style - Opens Language Selection Page)
        val currentLang by LocaleHelper.currentLanguage.collectAsState()
        val currentLangLabel = if (currentLang == LocaleHelper.LANGUAGE_TAMIL) "தமிழ் (Tamil)" else "English"

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("language_settings_card"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.language_settings).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate600,
                letterSpacing = 1.sp
            )

            SettingsMenuButton(
                icon = Icons.Outlined.Translate,
                title = "Language / மொழி",
                subtitle = "Tap to choose app language (English / தமிழ்)",
                trailingBadge = currentLangLabel,
                trailingBadgeColor = Color(0xFF15803D),
                modifier = Modifier.testTag("btn_open_language_selection"),
                onClick = {
                    viewModel.setNavigation("LANGUAGE")
                }
            )
        }

        HorizontalDivider(color = Slate200.copy(alpha = 0.6f), thickness = 1.dp)

        // 4. MODERN CLEAN OFFICE SUPPORT & ENQUIRY (Taxi app button style - Opens Office Support Page)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "OFFICE SUPPORT & ENQUIRY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate600,
                letterSpacing = 1.sp
            )

            val officePhoneNum = officePaymentInfo.officePhone.ifBlank { "+919600403032" }

            SettingsMenuButton(
                icon = Icons.Outlined.Phone,
                title = "Office Support & Enquiry",
                subtitle = "",
                trailingBadge = "Helpline",
                trailingBadgeColor = Color(0xFF1D4ED8),
                modifier = Modifier.testTag("btn_open_office_support"),
                onClick = {
                    viewModel.setNavigation("OFFICE_SUPPORT")
                }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SettingsMenuButton(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailingBadge: String? = null,
    trailingBadgeColor: Color = Slate600,
    isActive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = TaxiWhite),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (isActive) TaxiRed.copy(alpha = 0.4f) else Slate200.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isActive) TaxiRed else Slate700,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) Slate900 else Slate800
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Slate500
                    )
                }
            }

            if (!trailingBadge.isNullOrBlank()) {
                Text(
                    text = trailingBadge,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = trailingBadgeColor,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// SUB-COMPOSABLES HELPER UTILS

@Composable
fun SetFareRatesDialog(
    currentBaseFare: Double,
    currentPerKmFare: Double,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var baseFareText by remember {
        mutableStateOf(if (currentBaseFare <= 0.0) "80" else if (currentBaseFare % 1.0 == 0.0) currentBaseFare.toInt().toString() else currentBaseFare.toString())
    }
    var perKmText by remember {
        mutableStateOf(if (currentPerKmFare <= 0.0) "28" else if (currentPerKmFare % 1.0 == 0.0) currentPerKmFare.toInt().toString() else currentPerKmFare.toString())
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(RedAccentBg, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Fare settings",
                        tint = TaxiRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "SET FARE RATES",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Slate900,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Set Base Fare & KMS Rate for rides",
                        fontSize = 11.sp,
                        color = Slate500,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Base Fare field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "BASE FARE (₹)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate500,
                        letterSpacing = 0.5.sp
                    )
                    OutlinedTextField(
                        value = baseFareText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() || it == '.' }) {
                                baseFareText = input
                                errorMessage = null
                            }
                        },
                        placeholder = { Text("80", color = Slate400, fontSize = 14.sp) },
                        leadingIcon = {
                            Text(
                                text = "₹",
                                fontWeight = FontWeight.Bold,
                                color = TaxiRed,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            unfocusedLabelColor = Slate400
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_base_fare_input")
                    )
                }

                // KMS Fare field
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "KMS FARE (₹ / KM)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate500,
                        letterSpacing = 0.5.sp
                    )
                    OutlinedTextField(
                        value = perKmText,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() || it == '.' }) {
                                perKmText = input
                                errorMessage = null
                            }
                        },
                        placeholder = { Text("28", color = Slate400, fontSize = 14.sp) },
                        leadingIcon = {
                            Text(
                                text = "₹",
                                fontWeight = FontWeight.Bold,
                                color = TaxiRed,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            unfocusedLabelColor = Slate400
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_per_km_input")
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = TaxiRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val base = baseFareText.toDoubleOrNull()
                    val perKm = perKmText.toDoubleOrNull()
                    if (base == null || base < 0.0) {
                        errorMessage = "Please enter a valid Base Fare"
                    } else if (perKm == null || perKm < 0.0) {
                        errorMessage = "Please enter a valid KMS Fare"
                    } else {
                        onSave(base, perKm)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = TaxiRed, contentColor = TaxiWhite),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("dialog_save_fare_btn")
            ) {
                Text("SAVE FARE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Slate300),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate700)
            ) {
                Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = TaxiWhite
    )
}

@Composable
fun GeometricHeader(modifier: Modifier = Modifier.padding(vertical = 4.dp)) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(TaxiRed, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "TRUSTY YELLOW CAB",
                color = TaxiWhite,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
    }
}

fun getMapsUrl(loc: String): String {
    if (loc.isBlank() || loc.startsWith("Awaiting") || loc == "Unknown") return ""
    try {
        val parts = loc.split(",")
        if (parts.size == 2) {
            val latText = parts[0].trim()
            val lngText = parts[1].trim()
            
            var latVal = latText.replace("°", "").replace("N", "").replace("S", "").trim().toDoubleOrNull()
            var lngVal = lngText.replace("°", "").replace("E", "").replace("W", "").replace(" ", "").trim().toDoubleOrNull()
            if (latVal != null && lngVal != null) {
                if (latText.contains("S")) {
                    latVal = -latVal
                }
                if (lngText.contains("W")) {
                    lngVal = -lngVal
                }
                return "https://www.google.com/maps/search/?api=1&query=$latVal,$lngVal"
            }
        }
    } catch (e: Exception) {
        // Fallback to query encoding below
    }
    return "https://www.google.com/maps/search/?api=1&query=${Uri.encode(loc)}"
}

fun openMap(context: Context, locationStr: String) {
    val mapsUrl = getMapsUrl(locationStr)
    if (mapsUrl.isNotEmpty()) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Could not open map search", e)
        }
    }
}

fun getMapsUrlForTripLocation(latitude: Double?, longitude: Double?, fallbackAddress: String): String {
    if (latitude != null && longitude != null && latitude != 0.0 && longitude != 0.0) {
        return "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
    }
    return getMapsUrl(fallbackAddress)
}

fun openMapForTripLocation(context: Context, latitude: Double?, longitude: Double?, fallbackAddress: String) {
    val mapsUrl = getMapsUrlForTripLocation(latitude, longitude, fallbackAddress)
    if (mapsUrl.isNotEmpty()) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Could not open map search", e)
        }
    }
}

@Composable
fun ReceiptRow(
    label: String, 
    value: String, 
    onClick: (() -> Unit)? = null,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    singleLine: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = verticalAlignment
    ) {
        Text(
            text = label, 
            fontSize = 13.sp, 
            color = Slate500, 
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(end = 8.dp),
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            overflow = if (singleLine) androidx.compose.ui.text.style.TextOverflow.Ellipsis else androidx.compose.ui.text.style.TextOverflow.Clip
        )
        Text(
            text = value, 
            fontSize = 13.sp, 
            fontWeight = FontWeight.Bold, 
            color = if (onClick != null) TaxiRed else Slate900,
            textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            textAlign = TextAlign.End,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            modifier = if (singleLine) Modifier else Modifier.weight(1f)
        )
    }
}


// TEXT BILLING SHARING LAYOUT FORMULATORS

fun buildTripShareText(trip: Trip, includeCustomerMobile: Boolean = false): String {
    val distanceStr = String.format("%.2f", trip.distance)
    val fareStr = String.format("%.2f", trip.totalFare)
    
    val customerLine = if (includeCustomerMobile && trip.customerMobile.isNotBlank()) {
        "\nCustomer Contact: ${trip.customerMobile}"
    } else {
        ""
    }

    val istTimeFormatter = SimpleDateFormat("hh:mm a", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    val startTimeFormatted = if (trip.startTime > 0) "${istTimeFormatter.format(Date(trip.startTime))} IST" else ""
    val endTimeFormatted = if (trip.endTime > 0) "${istTimeFormatter.format(Date(trip.endTime))} IST" else ""

    val timingLines = buildString {
        if (startTimeFormatted.isNotBlank()) {
            append("\nStart Time: $startTimeFormatted")
        }
        if (endTimeFormatted.isNotBlank()) {
            append("\nEnd Time: $endTimeFormatted")
        }
    }

    val routeMapLink = if (trip.startLatitude != null && trip.startLongitude != null && 
                           trip.endLatitude != null && trip.endLongitude != null && 
                           trip.startLatitude != 0.0 && trip.endLatitude != 0.0) {
        "https://www.google.com/maps/dir/?api=1&origin=${trip.startLatitude},${trip.startLongitude}&destination=${trip.endLatitude},${trip.endLongitude}"
    } else if (trip.startLocation.isNotBlank() && trip.endLocation.isNotBlank() && 
               !trip.startLocation.startsWith("Awaiting") && !trip.endLocation.startsWith("Awaiting") &&
               trip.startLocation != "Unknown" && trip.endLocation != "Unknown") {
        "https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(trip.startLocation)}&destination=${Uri.encode(trip.endLocation)}"
    } else {
        ""
    }

    val pickupSection = "Pickup Location: ${trip.startLocation}"
    val dropSection = "Drop Location: ${trip.endLocation}"

    val routeSection = if (routeMapLink.isNotEmpty()) {
        "\nRoute Map: $routeMapLink"
    } else {
        ""
    }

    val breakdownItems = FareBreakdownHelper.calculateBreakdown(trip)
    val breakdownLines = breakdownItems.joinToString("\n") { item ->
        val detailPart = if (item.detail.isNotEmpty()) " (${item.detail})" else ""
        "${item.label}$detailPart: INR ${String.format("%.2f", item.amount)}"
    }

    if (trip.isPackageMeter) {
        val totalMinutes = trip.durationSeconds.toDouble() / 60.0
        val packageHours = maxOf(1, kotlin.math.floor(totalMinutes / 60.0).toInt())
        val pkgIncludedKm = if (trip.includedKm > 0.0) trip.includedKm else (packageHours * 10.0)

        return """
TRUSTY YELLOW CAB - PACKAGE RECEIPT

Trip Id: ${if (trip.tripIdCode.isNotEmpty()) trip.tripIdCode else trip.id.toString()}
Date: ${trip.dateStr}$timingLines
Driver: ${trip.driverName} (${trip.vehicleNumber})
Vehicle: ${trip.vehicleCategory} (${trip.vehicleModel})$customerLine

Package Details:
Package: ${if (trip.packageName.isNotBlank()) trip.packageName else "$packageHours Hours / ${pkgIncludedKm.toInt()} KM"}
Included KM: ${pkgIncludedKm.toInt()} KM
Included Time: ${packageHours * 60} Mins

Charges Breakdown:
$breakdownLines

Distance: *$distanceStr KM*
Duration: ${TaxiMeterService.formatDuration(trip.durationSeconds)}

Total Payable: *INR $fareStr*

$pickupSection
$dropSection$routeSection

Thank you for your business!
""".trimIndent()
    } else {
        return """
TRUSTY YELLOW CAB - RECEIPT

Trip Id: ${if (trip.tripIdCode.isNotEmpty()) trip.tripIdCode else trip.id.toString()}
Date: ${trip.dateStr}$timingLines
Driver: ${trip.driverName} (${trip.vehicleNumber})
Vehicle: ${trip.vehicleCategory} (${trip.vehicleModel})$customerLine

Charges Breakdown:
$breakdownLines

Distance: *$distanceStr KM*
Duration: ${TaxiMeterService.formatDuration(trip.durationSeconds)}

Total Payable: *INR $fareStr*

$pickupSection
$dropSection$routeSection

Thank you for your business!
""".trimIndent()
    }
}

// ACTION UTILITIES

fun launchPlatformShare(context: Context, targetPackage: String, message: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
    }
    
    // Test for package availability
    val pm = context.packageManager
    val resolved = pm.queryIntentActivities(intent, 0)
    var supportsDirect = false
    for (res in resolved) {
        if (res.activityInfo.packageName.startsWith(targetPackage)) {
            intent.setPackage(res.activityInfo.packageName)
            supportsDirect = true
            break
        }
    }
    
    try {
        if (supportsDirect) {
            context.startActivity(intent)
        } else {
            // General fallback chooser
            val chooser = Intent.createChooser(intent, "Share Trip Details")
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "Error opening sharing client", e)
    }
}

fun launchTelegramOrChooser(context: Context, message: String) {
    // Exact spec: When clicked, Open Telegram and pasteGeneratedTripDetails and user sends to group
    val telegramIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, message)
        setPackage("org.telegram.messenger")
    }
    try {
        context.startActivity(telegramIntent)
    } catch (e: Exception) {
        // Fallback to standard chooser if Telegram is not direct available
        val chooserIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(chooserIntent, "Share customized receipt"))
    }
}

fun launchSmsApp(context: Context, rawMessage: String) {
    val smsUri = Uri.parse("smsto:")
    val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
        putExtra("sms_body", rawMessage)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "SMS dispatcher failed", e)
    }
}

fun sharePdfReceipt(context: Context, file: File) {
    val authority = "${context.packageName}.fileprovider"
    val fileUri = FileProvider.getUriForFile(context, authority, file)
    
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, fileUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share PDF Cab Invoice"))
}

fun requestFilePrint(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val fileUri = FileProvider.getUriForFile(context, authority, file)
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        context.startActivity(Intent.createChooser(intent, "Open receipt to print / save"))
    } catch (e: Exception) {
        android.util.Log.e("MainActivity", "No PDF rendering reader detected on device", e)
    }
}

fun saveAndNormalizeSelfie(context: Context, bitmap: Bitmap, viewModel: TaxiMeterViewModel) {
    val file = File(context.filesDir, "driver_selfie_${System.currentTimeMillis()}.jpg")
    try {
        // Auto-rotate 270 degrees to portrait if captured in landscape
        val finalBitmap = if (bitmap.width > bitmap.height) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(270f)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }

        val fos = FileOutputStream(file)
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        fos.close()
        viewModel.saveDriverSelfiePath(file.absolutePath)
    } catch (e: Exception) {
        e.printStackTrace()
        android.util.Log.e("MainActivity", "Failed to save selfie: ${e.message}", e)
    }
}

fun rotateDriverSelfie(context: Context, currentPath: String, viewModel: TaxiMeterViewModel) {
    if (currentPath.isEmpty()) return
    try {
        val file = File(currentPath)
        if (!file.exists()) return
        
        // Load original bitmap
        val originalBitmap = BitmapFactory.decodeFile(currentPath) ?: return
        
        // Rotate 90 degrees clockwise
        val matrix = android.graphics.Matrix()
        matrix.postRotate(90f)
        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true
        )
        
        // Save to a new file to force compose cache bust
        val newFile = File(context.filesDir, "driver_selfie_${System.currentTimeMillis()}.jpg")
        val fos = FileOutputStream(newFile)
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
        fos.close()
        
        // Clean up old file
        try {
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Save new path in viewModel
        viewModel.saveDriverSelfiePath(newFile.absolutePath)
    } catch (e: Exception) {
        e.printStackTrace()
        android.util.Log.e("MainActivity", "Failed to rotate: ${e.message}", e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MandatoryProfileSetupScreen(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentDriverName by viewModel.driverName.collectAsState()
    val currentVehicleNumber by viewModel.vehicleNumber.collectAsState()
    val currentDriverMobile by viewModel.driverMobile.collectAsState()
    val currentVehicleCategory by viewModel.vehicleCategory.collectAsState()

    var driverNameVal by remember(currentDriverName) { mutableStateOf(currentDriverName) }
    var vehicleNumVal by remember(currentVehicleNumber) { mutableStateOf(currentVehicleNumber) }
    var driverMobileVal by remember(currentDriverMobile) { mutableStateOf(currentDriverMobile) }
    var vehicleCatVal by remember(currentVehicleCategory) { mutableStateOf(currentVehicleCategory) }
    var registrationErrorMsg by remember { mutableStateOf<String?>(null) }

    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            saveAndNormalizeSelfie(context, bitmap, viewModel)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            android.util.Log.w("MainActivity", "Camera permission is required to capture selfie")
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC)) // Clean, modern off-white background
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        val screenHeight = maxHeight
        val isVeryShort = screenHeight < 640.dp
        val isShort = screenHeight < 740.dp

        val horizontalPadding = if (isVeryShort) 12.dp else if (isShort) 16.dp else 20.dp
        val verticalPadding = if (isVeryShort) 6.dp else if (isShort) 10.dp else 14.dp
        val cardInnerPadding = if (isVeryShort) 10.dp else if (isShort) 12.dp else 16.dp
        val itemSpacing = if (isVeryShort) 5.dp else if (isShort) 7.dp else 10.dp
        val headerTitleSize = if (isVeryShort) 18.sp else if (isShort) 20.sp else 22.sp
        val selfieAvatarSize = if (isVeryShort) 44.dp else if (isShort) 52.dp else 60.dp
        val buttonHeight = if (isVeryShort) 42.dp else if (isShort) 46.dp else 48.dp
        val catChipHeight = if (isVeryShort) 28.dp else if (isShort) 30.dp else 34.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 480.dp)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header: Brand Badge + Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isVeryShort) 2.dp else 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(TaxiRed.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = if (isVeryShort) 3.dp else 4.dp)
                ) {
                    Text(
                        text = "TRUSTY YELLOW CAB",
                        color = TaxiRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }

                Text(
                    text = "Driver Registration",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = headerTitleSize,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        letterSpacing = (-0.5).sp
                    )
                )
            }

            // Main Content Card
            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(cardInnerPadding),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {

                    // Driver Name
                    OutlinedTextField(
                        value = driverNameVal,
                        onValueChange = { driverNameVal = it },
                        label = { Text("Driver Name", fontSize = if (isVeryShort) 11.sp else 12.sp) },
                        leadingIcon = { 
                            Icon(
                                imageVector = Icons.Default.Person, 
                                contentDescription = "Driver Name icon", 
                                tint = Slate400,
                                modifier = Modifier.size(if (isVeryShort) 18.dp else 20.dp)
                            ) 
                        },
                        singleLine = true,
                        placeholder = { Text("Driver name", fontSize = if (isVeryShort) 12.sp else 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (isVeryShort) 13.sp else 14.sp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            unfocusedLabelColor = Slate400,
                            focusedContainerColor = Color(0xFFFAFAFA)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_driver_name")
                    )

                    // Driver Mobile Number (Exactly 10 Digits, digits only)
                    OutlinedTextField(
                        value = driverMobileVal,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 10) {
                                driverMobileVal = digits
                            }
                        },
                        label = { Text("Mobile Number (10 Digits)", fontSize = if (isVeryShort) 11.sp else 12.sp) },
                        leadingIcon = { 
                            Icon(
                                imageVector = Icons.Default.Phone, 
                                contentDescription = "Driver Mobile icon", 
                                tint = Slate400,
                                modifier = Modifier.size(if (isVeryShort) 18.dp else 20.dp)
                            ) 
                        },
                        singleLine = true,
                        prefix = { Text("+91 ", fontSize = if (isVeryShort) 13.sp else 14.sp) },
                        placeholder = { Text("9876543210", fontSize = if (isVeryShort) 12.sp else 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (isVeryShort) 13.sp else 14.sp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            unfocusedLabelColor = Slate400,
                            focusedContainerColor = Color(0xFFFAFAFA)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_driver_mobile")
                    )

                    // Vehicle Number
                    OutlinedTextField(
                        value = vehicleNumVal,
                        onValueChange = { vehicleNumVal = it.uppercase(Locale.ROOT) },
                        label = { Text("Vehicle Number", fontSize = if (isVeryShort) 11.sp else 12.sp) },
                        leadingIcon = { 
                            Icon(
                                imageVector = Icons.Default.Pin, 
                                contentDescription = "Vehicle Plate icon", 
                                tint = Slate400,
                                modifier = Modifier.size(if (isVeryShort) 18.dp else 20.dp)
                            ) 
                        },
                        singleLine = true,
                        placeholder = { Text("e.g. TN 66 AB 1234", fontSize = if (isVeryShort) 12.sp else 13.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = if (isVeryShort) 13.sp else 14.sp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            unfocusedLabelColor = Slate400,
                            focusedContainerColor = Color(0xFFFAFAFA)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_vehicle_number")
                    )

                    // Vehicle Category Choice Label
                    Text(
                        text = "VEHICLE CATEGORY",
                        fontSize = if (isVeryShort) 9.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate500,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )

                    // Beautifully formatted single horizontal row where all 6 choices are visible at once!
                    val categories = listOf("Mini", "Sedan", "SUV", "SUV+", "Innova", "Traveller")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        categories.forEach { cat ->
                            val isSelected = vehicleCatVal == cat
                            Card(
                                onClick = { vehicleCatVal = cat },
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) TaxiRed else Slate200
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) RedAccentBg else TaxiWhite
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(catChipHeight)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cat,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) TaxiRed else Slate700,
                                        fontSize = if (cat == "Traveller") {
                                            if (isVeryShort) 7.5.sp else 8.sp
                                        } else if (cat == "Innova") {
                                            if (isVeryShort) 8.sp else 8.5.sp
                                        } else {
                                            if (isVeryShort) 8.5.sp else 9.5.sp
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // Highly Visible Selfie Photo Capture Section
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFAFAFA), shape = RoundedCornerShape(10.dp))
                            .border(1.dp, Slate200, shape = RoundedCornerShape(10.dp))
                            .padding(if (isVeryShort) 6.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val selfieBitmap = remember(sDriverSelfiePath) {
                            if (sDriverSelfiePath.isNotEmpty()) {
                                try {
                                    BitmapFactory.decodeFile(sDriverSelfiePath)?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                        // Circular preview
                        Box(
                            modifier = Modifier
                                .size(selfieAvatarSize)
                                .clip(CircleShape)
                                .background(Slate100, CircleShape)
                                .border(2.dp, if (sDriverSelfiePath.isNotEmpty()) TaxiRed else Slate200, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selfieBitmap != null) {
                                Image(
                                    bitmap = selfieBitmap,
                                    contentDescription = "Selfie Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = "Camera placeholder",
                                    tint = Slate400,
                                    modifier = Modifier.size(if (isVeryShort) 20.dp else 24.dp)
                                )
                            }
                        }

                        // Actions and status on the right, stacked vertically
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start
                        ) {
                            if (sDriverSelfiePath.isNotEmpty()) {
                                Text(
                                    text = "PHOTO CAPTURED ✓",
                                    fontSize = if (isVeryShort) 9.sp else 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                                takePictureLauncher.launch(null)
                                            } else {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                        },
                                        border = BorderStroke(1.dp, TaxiRed),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = RedAccentBg, contentColor = TaxiRed),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.height(if (isVeryShort) 26.dp else 28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Re-take",
                                            modifier = Modifier.size(12.dp),
                                            tint = TaxiRed
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("RE-TAKE", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = { rotateDriverSelfie(context, sDriverSelfiePath, viewModel) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Slate100, contentColor = Slate900),
                                        border = BorderStroke(1.dp, Slate300),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.height(if (isVeryShort) 26.dp else 28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Rotate",
                                            modifier = Modifier.size(12.dp),
                                            tint = Slate900
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("ROTATE", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Text(
                                    text = "Selfie Photo is Required",
                                    fontSize = if (isVeryShort) 9.sp else 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Slate500
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Button(
                                    onClick = {
                                        val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                            takePictureLauncher.launch(null)
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TaxiRed, contentColor = TaxiWhite),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier.height(if (isVeryShort) 28.dp else 32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Camera Icon",
                                        modifier = Modifier.size(14.dp),
                                        tint = TaxiWhite
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "TAKE SELFIE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (registrationErrorMsg != null) {
                        Text(
                            text = registrationErrorMsg ?: "",
                            color = TaxiRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // SAVE & START USING METER button
                    Button(
                        onClick = {
                            if (driverNameVal.trim().isBlank() || vehicleNumVal.trim().isBlank() || driverMobileVal.trim().isBlank()) {
                                registrationErrorMsg = "Please enter Driver Name, Vehicle Number, and Mobile Number!"
                                return@Button
                            }
                            
                            if (driverMobileVal.trim().length != 10) {
                                registrationErrorMsg = "Mobile number must be exactly 10 digits!"
                                return@Button
                            }
                            
                            if (sDriverSelfiePath.trim().isBlank()) {
                                registrationErrorMsg = "Please capture your selfie photo to complete registration!"
                                return@Button
                            }

                            registrationErrorMsg = null
                            
                            viewModel.saveDriverDetails(
                                name = driverNameVal.trim(),
                                vNumber = vehicleNumVal.trim(),
                                category = vehicleCatVal,
                                model = "",
                                isNight = false,
                                mobile = driverMobileVal.trim()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TaxiRed, 
                            contentColor = TaxiWhite
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(buttonHeight)
                            .testTag("save_and_enter_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Save icon",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SAVE & START USING METER",
                                fontSize = if (isVeryShort) 11.sp else 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
            
            Text(
                text = "Meter Network • Trusty Yellow Cab Support",
                fontSize = if (isVeryShort) 8.sp else 9.sp,
                fontWeight = FontWeight.Medium,
                color = Slate400,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
fun MandatoryPermissionsGateScreen(
    context: Context,
    hasFine: Boolean,
    hasNotification: Boolean,
    hasBackground: Boolean,
    gpsEnabled: Boolean,
    batteryOptimizationIgnored: Boolean,
    onRequestForeground: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestBackground: () -> Unit,
    onOpenGpsSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TaxiBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        
        Text(
            text = "SETUP REQUIRED",
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = Slate900,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "To start using the taxi meter on this phone, please grant all required permissions. This is mandatory so the app works reliably on your phone.",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Slate500,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 1. Foreground Location Card
        PermissionStatusCard(
            title = "1. GPS Location Access",
            description = "Allows the taxi meter to track your current route, speed, and exact trip distance.",
            isGranted = hasFine,
            actionLabel = "GRANT LOCATION ACCESS",
            onAction = onRequestForeground
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 2. Notification Card (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStatusCard(
                title = "2. Notifications Permission",
                description = "Required to display the taxi meter status in your notification tray while running in the background.",
                isGranted = hasNotification,
                actionLabel = "ENABLE NOTIFICATIONS",
                onAction = onRequestNotification
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // 3. Background Location Card (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PermissionStatusCard(
                title = "3. Background Location (Allow All The Time)",
                description = "CRITICAL: You MUST select 'Allow all the time' in the system location settings. This stops Android from cutting off GPS tracking when your screen is locked or you are using navigation apps.",
                isGranted = hasBackground,
                actionLabel = "SELECT 'ALLOW ALL THE TIME'",
                onAction = onRequestBackground,
                showSpecialGuide = !hasBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        
        // 4. GPS Enabled Card
        PermissionStatusCard(
            title = "4. Phone Location (GPS) Enabled",
            description = "Your device's high-accuracy GPS receiver must be turned ON in system settings.",
            isGranted = gpsEnabled,
            actionLabel = "ENABLE GPS SERVICES",
            onAction = onOpenGpsSettings
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        // 5. Battery Optimization Card (MANDATORY)
        PermissionStatusCard(
            title = "5. Battery Optimization Bypass (Mandatory)",
            description = "CRITICAL: You MUST disable battery optimization for Trusty Yellow Cab to allow the taxi meter to track continuously in the background without Android putting the app to sleep.",
            isGranted = batteryOptimizationIgnored,
            actionLabel = "DISABLE BATTERY OPTIMIZATION",
            onAction = onRequestBatteryOptimization,
            isMandatory = true
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedButton(
            onClick = onOpenAppSettings,
            border = BorderStroke(1.dp, Slate400),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Slate900, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("OPEN APP SETTINGS PAGE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate900)
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun PermissionStatusCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    showSpecialGuide: Boolean = false,
    isMandatory: Boolean = true
) {
    val borderColor = if (isGranted) Slate100 else if (isMandatory) TaxiRed.copy(alpha = 0.2f) else Slate400.copy(alpha = 0.3f)
    val icon = if (isGranted) Icons.Default.CheckCircle else if (isMandatory) Icons.Default.Warning else Icons.Default.Info
    val iconColor = if (isGranted) Color(0xFF2E7D32) else if (isMandatory) TaxiRed else Slate500
    val buttonColor = if (isMandatory) TaxiRed else Slate900

    Card(
        colors = CardDefaults.cardColors(containerColor = TaxiWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = if (isGranted) "Granted" else if (isMandatory) "Required" else "Recommended",
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = description,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Slate500,
                lineHeight = 16.sp
            )
            
            if (showSpecialGuide) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(RedAccentBg, shape = RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "In the screen that opens, choose 'Permissions' -> 'Location' -> then check the option 'Allow all the time'.",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TaxiRed,
                        lineHeight = 14.sp
                    )
                }
            }
            
            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                ) {
                    Text(
                        text = actionLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MandatorySelfieSetupScreen(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val sDriverSelfiePath by viewModel.driverSelfiePath.collectAsState()
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            saveAndNormalizeSelfie(context, bitmap, viewModel)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePictureLauncher.launch(null)
        } else {
            android.util.Log.w("MainActivity", "Camera permission is required to capture selfie")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFF9F9),
                        Color(0xFFFFF5F5),
                        Color(0xFFFFF0F0)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color(0x22FFE1E1),
                radius = 320.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f)
            )
            drawCircle(
                color = Color(0x11FFE1E1),
                radius = 260.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.85f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors = listOf(TaxiRed, Color(0xFFFF4D4D))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "TRUSTY YELLOW CAB",
                    color = TaxiWhite,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Selfie Photo Required",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900,
                    letterSpacing = (-0.5).sp
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "We have updated our taxi meter app. Please take a selfie photo of yourself. This photo is required for safety verification and will be printed on customer receipts.",
                fontSize = 13.sp,
                color = Slate500,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Selfie Display Card
            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    val selfieBitmap = remember(sDriverSelfiePath) {
                        if (sDriverSelfiePath.isNotEmpty()) {
                            try {
                                BitmapFactory.decodeFile(sDriverSelfiePath)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    if (selfieBitmap != null) {
                        Image(
                            bitmap = selfieBitmap,
                            contentDescription = "Captured Selfie Preview",
                            modifier = Modifier
                                .size(160.dp)
                                .clip(CircleShape)
                                .border(3.dp, TaxiRed, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .background(Slate100, CircleShape)
                                .border(2.dp, Slate300, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Camera Icon",
                                tint = Slate400,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }

                    if (sDriverSelfiePath.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                    takePictureLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            border = BorderStroke(1.5.dp, TaxiRed),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = RedAccentBg, contentColor = TaxiRed),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = TaxiRed)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "RE-TAKE SELFIE PHOTO",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TaxiRed
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                val permissionCheck = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                                    takePictureLauncher.launch(null)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TaxiRed, contentColor = TaxiWhite),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Camera", tint = TaxiWhite)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "CAPTURE SELFIE PHOTO",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (sDriverSelfiePath.isNotEmpty()) {
                        Button(
                            onClick = { rotateDriverSelfie(context, sDriverSelfiePath, viewModel) },
                            colors = ButtonDefaults.buttonColors(containerColor = Slate100, contentColor = Slate900),
                            border = BorderStroke(1.dp, Slate300),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Rotate Photo 90 degrees",
                                    tint = Slate900
                                )
                                Text(
                                    text = "ROTATE PHOTO 90°",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (sDriverSelfiePath.isNotEmpty()) {
                        Button(
                            onClick = {
                                // Selfie is saved and stored, we can proceed
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Proceed")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "PROCEED TO TAXI METER",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "",
                fontSize = 11.sp,
                color = Slate400
            )
        }
    }
}
