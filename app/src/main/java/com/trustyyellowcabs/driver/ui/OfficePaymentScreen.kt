package com.trustyyellowcabs.driver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustyyellowcabs.driver.R
import com.trustyyellowcabs.driver.ui.theme.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficePaymentScreen(
    viewModel: TaxiMeterViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    val driverId by viewModel.driverId.collectAsState()
    val vehicleNumber by viewModel.vehicleNumber.collectAsState()
    val paymentInfo by viewModel.officePaymentInfo.collectAsState()

    // Handle device hardware back press
    BackHandler {
        onBack()
    }

    val activeUpiId = paymentInfo.upiId.ifBlank { "" }
    val payeeName = paymentInfo.payeeName.ifBlank { "" }
    val officePhone = paymentInfo.officePhone.ifBlank { "" }
    val payableAmount = paymentInfo.amount

    var showNoUpiAppDialog by remember { mutableStateOf(false) }

    fun copyUpiIdToClipboard() {
        if (activeUpiId.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Office UPI ID", activeUpiId)
        clipboard.setPrimaryClip(clip)
    }

    fun launchUpiPayment() {
        if (payableAmount <= 0.0 || activeUpiId.isBlank()) {
            return
        }

        val noteText = if (paymentInfo.paymentNote.isNotBlank()) {
            "${paymentInfo.paymentNote} - Driver $driverId"
        } else {
            "Office Fee - Driver $driverId ($vehicleNumber)"
        }

        val formattedAmount = String.format(Locale.US, "%.2f", payableAmount)
        val uriString = "upi://pay?" +
                "pa=${Uri.encode(activeUpiId)}" +
                "&pn=${Uri.encode(payeeName)}" +
                "&am=${Uri.encode(formattedAmount)}" +
                "&cu=INR" +
                "&tn=${Uri.encode(noteText)}"

        val upiIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(uriString)
        }

        try {
            val chooser = Intent.createChooser(upiIntent, "Pay Office Fee via UPI")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            showNoUpiAppDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_back_to_settings")
                    ) {
                        Text(
                            text = "Back",
                            color = Slate800,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TaxiSurface
                ),
                modifier = Modifier.shadow(1.dp)
            )
        },
        containerColor = TaxiBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Amount to Pay Card (Fixed Backend Amount - Corporate Style)
            val hasDue = payableAmount > 0.0

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = TaxiSurface
                ),
                border = BorderStroke(
                    1.dp,
                    Slate200
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (hasDue) Slate800 else GreenPillBg
                    ) {
                        Text(
                            text = if (hasDue) "OFFICE DUE AMOUNT" else "ALL CLEAR • NO DUES",
                            color = if (hasDue) Color.White else GreenPillText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (hasDue) "₹${String.format(Locale.US, "%.2f", payableAmount)}" else "₹0.00",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasDue) Slate900 else GreenPillText
                    )

                    if (paymentInfo.paymentNote.isNotBlank() && hasDue) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = paymentInfo.paymentNote,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate700,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (hasDue) "" else "No pending dues assigned to your account",
                        fontSize = 12.sp,
                        color = Slate500,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 2. Payee Office & UPI Card (Clean corporate without icons)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = TaxiSurface),
                border = BorderStroke(1.dp, Slate200),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = payeeName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = "Official Billing Account",
                            fontSize = 11.sp,
                            color = Slate500
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "OFFICE UPI ID",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate500
                                )
                                Text(
                                    text = activeUpiId,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900
                                )
                            }

                            if (activeUpiId.isNotBlank()) {
                                TextButton(
                                    onClick = { copyUpiIdToClipboard() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.copy_upi),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate800
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. One-Click Pay Action Button (Corporate Style & Cleaner)
            Button(
                onClick = {
                    launchUpiPayment()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("btn_click_to_pay_upi"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Slate900,
                    disabledContainerColor = Slate200
                ),
                enabled = hasDue && activeUpiId.isNotBlank()
            ) {
                Text(
                    text = if (hasDue) {
                        if (activeUpiId.isNotBlank()) "Pay ₹${String.format(Locale.US, "%.2f", payableAmount)} via UPI" else "Office UPI ID Not Set"
                    } else {
                        "No Dues to Pay"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasDue && activeUpiId.isNotBlank()) Color.White else Slate500
                )
            }

            Text(
                text = stringResource(R.string.pay_via_upi_desc),
                fontSize = 11.sp,
                color = Slate500,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Dialog if no UPI app is installed
    if (showNoUpiAppDialog) {
        AlertDialog(
            onDismissRequest = { showNoUpiAppDialog = false },
            title = {
                Text(
                    text = "No UPI App Detected",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "We could not detect a UPI app (Google Pay, PhonePe, Paytm, BHIM) on this device.\n\nYou can copy the office UPI ID and pay directly from any bank app or another device:",
                        fontSize = 13.sp,
                        color = Slate600
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate100,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = activeUpiId,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = TaxiBlack,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        copyUpiIdToClipboard()
                        showNoUpiAppDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                ) {
                    Text("Copy UPI ID", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoUpiAppDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
