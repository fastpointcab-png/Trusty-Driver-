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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trustyyellowcabs.driver.ui.theme.Slate100
import com.trustyyellowcabs.driver.ui.theme.Slate200
import com.trustyyellowcabs.driver.ui.theme.Slate400
import com.trustyyellowcabs.driver.ui.theme.Slate500
import com.trustyyellowcabs.driver.ui.theme.Slate600
import com.trustyyellowcabs.driver.ui.theme.Slate700
import com.trustyyellowcabs.driver.ui.theme.Slate800
import com.trustyyellowcabs.driver.ui.theme.Slate900
import com.trustyyellowcabs.driver.ui.theme.TaxiRed
import com.trustyyellowcabs.driver.ui.theme.TaxiWhite

object DriverTermsData {
    const val TITLE = "Taxi Driver App – Driver Terms & Conditions"
    const val PREAMBLE = "By registering and using this application, the driver agrees to the following terms and conditions:"

    val CLAUSES = listOf(
        "The driver must provide accurate and valid personal information, driving license details, vehicle documents, and contact information.",
        "The driver is solely responsible for maintaining valid driving licenses, permits, insurance, fitness certificates, and other required documents.",
        "The driver shall obey all traffic laws, government regulations, and local transport authority rules.",
        "The driver must treat customers respectfully and maintain professional behavior at all times.",
        "The driver is responsible for the safety and cleanliness of the vehicle.",
        "The company only provides a platform for trip allocation and does not guarantee a minimum number of trips or earnings.",
        "The driver shall not misuse the application, manipulate trip information, create fake bookings, or engage in fraudulent activities.",
        "Any abusive behavior, customer complaints, misconduct, or illegal activities may result in temporary suspension or permanent termination of the account.",
        "Registration fees, maintenance fees, document verification fees, or other applicable charges are non-refundable unless required by law.",
        "The company reserves the right to modify, suspend, or discontinue any feature of the application without prior notice.",
        "The driver is responsible for any fines, penalties, accidents, damages, or legal claims arising from their actions.",
        "The company is not liable for loss of income, vehicle damage, personal injury, or any indirect losses incurred by the driver.",
        "The driver agrees to receive trip notifications, service updates, and important communications through the application, SMS, phone calls, or WhatsApp.",
        "Sharing login credentials or allowing another person to use the driver's account is strictly prohibited.",
        "The company reserves the right to verify driver documents and account information at any time.",
        "Violation of these terms may result in account suspension, deactivation, or permanent removal from the platform.",
        "The driver acknowledges that personal information, vehicle details, location information, and trip-related data may be collected and processed in accordance with the application's Privacy Policy.",
        "The application acts solely as a technology platform for trip allocation and driver management and is not responsible for the driver's independent conduct or legal compliance.",
        "Any dispute arising from the use of this application shall be subject to the jurisdiction of the courts located in Coimbatore, Tamil Nadu, India.",
        "The company may revise fares, commission structures, service fees, or policies from time to time.",
        "By clicking \"I Agree\", the driver confirms that they have read, understood, and accepted these Terms & Conditions."
    )

    const val DECLARATION = "I hereby agree to comply with all the above terms and conditions."

    const val CHECKBOX_LABEL = "I Agree to the Terms & Conditions"
}

@Composable
fun DriverTermsScreen(
    onTermsAccepted: () -> Unit
) {
    val context = LocalContext.current
    var isAgreed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.TopCenter)
        ) {
            // Header Section
            Surface(
                color = TaxiWhite,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .background(TaxiRed.copy(alpha = 0.08f), shape = RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "TRUSTY YELLOW CAB",
                                color = TaxiRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Driver Terms & Conditions",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Please review and accept the official driver terms before logging into your account.",
                        fontSize = 12.sp,
                        color = Slate500,
                        lineHeight = 16.sp
                    )
                }
            }

            // Scrollable Content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Preamble Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = DriverTermsData.PREAMBLE,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E3A8A),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                // Clauses order-wise
                itemsIndexed(DriverTermsData.CLAUSES) { index, clauseText ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Slate100),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = clauseText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                color = Slate800,
                                lineHeight = 19.sp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Declaration
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = DriverTermsData.DECLARATION,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate700,
                            letterSpacing = 0.2.sp
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // Bottom Sticky Acceptance Bar
            Surface(
                color = TaxiWhite,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Checkbox Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { isAgreed = !isAgreed }
                            .padding(vertical = 4.dp)
                            .testTag("terms_checkbox_row")
                    ) {
                        Checkbox(
                            checked = isAgreed,
                            onCheckedChange = { isAgreed = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = TaxiRed,
                                checkmarkColor = TaxiWhite,
                                uncheckedColor = Slate400
                            ),
                            modifier = Modifier.testTag("terms_checkbox")
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = DriverTermsData.CHECKBOX_LABEL,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAgreed) Slate900 else Slate700
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Agree & Continue Button
                    Button(
                        onClick = {
                            if (isAgreed) {
                                val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putBoolean("terms_and_conditions_accepted", true)
                                    .putLong("terms_accepted_timestamp", System.currentTimeMillis())
                                    .apply()
                                onTermsAccepted()
                            }
                        },
                        enabled = isAgreed,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TaxiRed,
                            contentColor = TaxiWhite,
                            disabledContainerColor = Slate200,
                            disabledContentColor = Slate400
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("terms_agree_button")
                    ) {
                        Text(
                            text = "I AGREE & CONTINUE TO LOGIN",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog to inspect full Terms & Conditions from Login screen or Settings screen anytime
 */
@Composable
fun DriverTermsDialog(
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = TaxiWhite),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 500.dp)
                    .height(600.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Dialog Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Driver Terms & Conditions",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                            Text(
                                text = "Trusty Yellow Cab Official Agreement",
                                fontSize = 11.sp,
                                color = Slate500
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Close",
                                color = Slate600,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Divider(color = Slate200)

                    // Scrollable Terms Content
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = DriverTermsData.PREAMBLE,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1E3A8A),
                                    lineHeight = 17.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        itemsIndexed(DriverTermsData.CLAUSES) { index, clauseText ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TaxiRed,
                                    modifier = Modifier.width(26.dp)
                                )
                                Text(
                                    text = clauseText,
                                    fontSize = 12.sp,
                                    color = Slate700,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = DriverTermsData.DECLARATION,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                        }
                    }

                    Divider(color = Slate200)

                    // Dialog Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TaxiRed,
                                contentColor = TaxiWhite
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("CLOSE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
