package com.trustyyellowcabs.driver.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustyyellowcabs.driver.network.FirebaseManager
import com.trustyyellowcabs.driver.network.FirestoreDriver
import com.trustyyellowcabs.driver.ui.theme.RedAccentBg
import com.trustyyellowcabs.driver.ui.theme.Slate200
import com.trustyyellowcabs.driver.ui.theme.Slate400
import com.trustyyellowcabs.driver.ui.theme.Slate500
import com.trustyyellowcabs.driver.ui.theme.Slate700
import com.trustyyellowcabs.driver.ui.theme.Slate900
import com.trustyyellowcabs.driver.ui.theme.TaxiRed
import com.trustyyellowcabs.driver.ui.theme.TaxiWhite
import com.trustyyellowcabs.driver.util.GoogleDriveUtils
import com.trustyyellowcabs.driver.util.PhoneCallUtils
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DriverLoginScreen(viewModel: TaxiMeterViewModel) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var driverIdInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var isPinVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isTermsAgreed by remember { mutableStateOf(prefs.getBoolean("terms_and_conditions_accepted", false)) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var pendingTakeoverDriver by remember { mutableStateOf<FirestoreDriver?>(null) }

    fun performLogin() {
        focusManager.clearFocus()
        val cleanId = driverIdInput.trim().uppercase(Locale.ROOT)
        val cleanPin = pinInput.trim()

        if (!isTermsAgreed) {
            errorMessage = "Please accept the Terms & Conditions to log in."
            return
        }
        if (cleanId.isEmpty()) {
            errorMessage = "Please enter your Driver ID."
            return
        }
        if (cleanPin.isEmpty()) {
            errorMessage = "Please enter your PIN (Last 4 digits of vehicle number)."
            return
        }

        isLoading = true
        errorMessage = null

        scope.launch {
            try {
                val driver = FirebaseManager.fetchDriver(context, cleanId, forceRemote = true)
                if (driver != null) {
                    // Check if Driver account is blocked or suspended by Admin
                    if (driver.isBlocked) {
                        errorMessage = if (driver.block_reason.isNotBlank()) {
                            "Account Suspended: ${driver.block_reason}\nPlease contact office."
                        } else {
                            "Your driver account has been suspended/blocked by Admin.\nPlease contact office."
                        }
                        isLoading = false
                        return@launch
                    }

                    // Secure Driver PIN verification via AppSecurityShield
                    val isPinValid = com.trustyyellowcabs.driver.security.AppSecurityShield.verifyDriverCredential(cleanPin, driver.vehicle_number)

                    if (!isPinValid) {
                        errorMessage = "Incorrect PIN"
                        isLoading = false
                        return@launch
                    }

                    // Strict Single-Device Enforcement:
                    // Only ONE device can be logged in at a time.
                    val currentDeviceId = FirebaseManager.getOrCreateDeviceId(context)
                    if (driver.device_id.isNotBlank() && driver.device_id != currentDeviceId) {
                        pendingTakeoverDriver = driver
                        errorMessage = "Driver ID '${driver.driver_id.ifEmpty { cleanId }}' is already logged in on another device.\nOnly one device can be logged in at a time.\nPlease log out from the other device or contact office."
                        isLoading = false
                        return@launch
                    }

                    // Bind this device to the driver's Firestore record
                    val bound = FirebaseManager.bindDriverDeviceSession(context, driver.driver_id.ifEmpty { cleanId })
                    if (!bound) {
                        pendingTakeoverDriver = driver
                        errorMessage = "Driver ID '${driver.driver_id.ifEmpty { cleanId }}' is already logged in on another device.\nOnly one device can be logged in at a time."
                        isLoading = false
                        return@launch
                    }

                    // Update logged-in driver session in ViewModel and SharedPreferences
                    viewModel.setDriverLoggedIn(
                        driverId = driver.driver_id.ifEmpty { cleanId },
                        name = driver.driver_name,
                        vNumber = driver.vehicle_number,
                        category = driver.vehicle_category.ifEmpty { "Mini" },
                        model = "",
                        mobile = driver.mobile_number,
                        photoUrl = driver.photo_url
                    )

                    // Download and cache driver selfie photo locally if provided
                    if (driver.photo_url.isNotBlank()) {
                        launch {
                            GoogleDriveUtils.downloadAndSaveDriverPhoto(context, driver.photo_url)
                        }
                    }
                    isLoading = false
                } else {
                    // Check local device SharedPreferences as offline fallback
                    val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                    val localDriverId = prefs.getString("unique_driver_id", "") ?: ""
                    val localName = prefs.getString("driver_name", "") ?: ""
                    val localVNum = prefs.getString("vehicle_number", "") ?: ""
                    val localCat = prefs.getString("vehicle_category", "Mini") ?: "Mini"
                    val localMobile = prefs.getString("driver_mobile", "") ?: ""
                    val localPhoto = prefs.getString("driver_photo_url", "") ?: ""

                    val isLocalPinValid = com.trustyyellowcabs.driver.security.AppSecurityShield.verifyDriverCredential(cleanPin, localVNum)

                    if (localDriverId.equals(cleanId, ignoreCase = true) && localName.isNotBlank() && isLocalPinValid) {
                        viewModel.setDriverLoggedIn(
                            driverId = localDriverId,
                            name = localName,
                            vNumber = localVNum,
                            category = localCat,
                            model = "",
                            mobile = localMobile,
                            photoUrl = localPhoto
                        )
                    } else {
                        errorMessage = "Driver ID '$cleanId' not found.\nPlease check your ID or contact dispatch."
                    }
                }
            } catch (e: Exception) {
                errorMessage = "Login failed: ${e.message ?: "Connection error"}"
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand Badge
            Box(
                modifier = Modifier
                    .background(TaxiRed.copy(alpha = 0.08f), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "TRUSTY YELLOW CAB",
                    color = TaxiRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Title
            Text(
                text = "Driver Login",
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900,
                    letterSpacing = (-0.5).sp
                )
            )

            Text(
                text = "Enter Driver ID and PIN to login",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = Slate500,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Login Card
            Card(
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Text(
                        text = "DRIVER ID",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate500,
                        letterSpacing = 0.6.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Driver ID Input
                    OutlinedTextField(
                        value = driverIdInput,
                        onValueChange = {
                            driverIdInput = it.uppercase(Locale.ROOT)
                            errorMessage = null
                        },
                        placeholder = {
                            Text(
                                text = "Enter Driver ID",
                                fontSize = 14.sp,
                                color = Slate400
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Badge,
                                contentDescription = "Driver ID icon",
                                tint = if (driverIdInput.isNotEmpty()) TaxiRed else Slate400,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Color(0xFFFAFAFA),
                            unfocusedContainerColor = TaxiWhite
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("driver_id_input")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "PIN",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate500,
                        letterSpacing = 0.6.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // PIN Input
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 6) {
                                pinInput = it
                                errorMessage = null
                            }
                        },
                        placeholder = {
                            Text(
                                text = "XXXX",
                                fontSize = 14.sp,
                                color = Slate400
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Password,
                                contentDescription = "PIN icon",
                                tint = if (pinInput.isNotEmpty()) TaxiRed else Slate400,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { isPinVisible = !isPinVisible }) {
                                Icon(
                                    imageVector = if (isPinVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isPinVisible) "Hide PIN" else "Show PIN",
                                    tint = Slate400,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        visualTransformation = if (isPinVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { performLogin() }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TaxiRed,
                            focusedLabelColor = TaxiRed,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Color(0xFFFAFAFA),
                            unfocusedContainerColor = TaxiWhite
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("driver_pin_input")
                    )

                    // Error Message Display
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RedAccentBg, RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = TaxiRed,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMessage!!,
                                    color = TaxiRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    // Terms & Conditions Acceptance Row
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isTermsAgreed = !isTermsAgreed
                                prefs.edit().putBoolean("terms_and_conditions_accepted", isTermsAgreed).apply()
                                if (isTermsAgreed) errorMessage = null
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = isTermsAgreed,
                            onCheckedChange = {
                                isTermsAgreed = it
                                prefs.edit().putBoolean("terms_and_conditions_accepted", it).apply()
                                if (it) errorMessage = null
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = TaxiRed,
                                checkmarkColor = TaxiWhite,
                                uncheckedColor = Slate400
                            ),
                            modifier = Modifier.testTag("login_terms_checkbox")
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "I Agree to the ",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = Slate700
                            )
                            Text(
                                text = "Terms & Conditions",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TaxiRed,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .clickable { showTermsDialog = true }
                                    .testTag("view_terms_link")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Login Action Button
                    Button(
                        onClick = { performLogin() },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TaxiRed,
                            contentColor = TaxiWhite,
                            disabledContainerColor = TaxiRed.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("login_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = TaxiWhite,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "VERIFYING...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Login lock icon",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "LOGIN",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Office / Attachment Support Section
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "For Attachment / Support",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            PhoneCallUtils.callOffice(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Call Office",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Call Office: ${PhoneCallUtils.OFFICE_PHONE_DISPLAY}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Protected by Trusty Yellow Cab Dispatch Network",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Slate400
            )
        }

        if (showTermsDialog) {
            DriverTermsDialog(onDismiss = { showTermsDialog = false })
        }

        if (pendingTakeoverDriver != null) {
            val drv = pendingTakeoverDriver!!
            AlertDialog(
                onDismissRequest = { pendingTakeoverDriver = null },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TaxiRed,
                        modifier = Modifier.size(28.dp)
                    )
                },
                title = {
                    Text(
                        text = "Already Logged In",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Slate900
                    )
                },
                text = {
                    Text(
                        text = "Driver ID ${drv.driver_id} is already logged in on another device.\n\nOnly one device can be logged in at the same time. Please log out from your other device before logging in here, or contact the office.",
                        fontSize = 14.sp,
                        color = Slate700
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { pendingTakeoverDriver = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "OK",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TaxiWhite
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = TaxiWhite
            )
        }
    }
}
