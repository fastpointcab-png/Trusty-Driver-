package com.trustyyellowcabs.driver.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trustyyellowcabs.driver.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeSupportScreen(
    viewModel: TaxiMeterViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val paymentInfo by viewModel.officePaymentInfo.collectAsState()

    BackHandler {
        onBack()
    }

   val primaryMobile = "+919600403032"
val secondaryLandline = "+914223596446"

    fun callNumber(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("OfficeSupportScreen", "Could not open dialer for $phone", e)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Office Support & Enquiry",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_back_from_support")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Slate800
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Info Banner
         

            Text(
                text = "SELECT NUMBER TO CALL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate500,
                letterSpacing = 1.sp
            )

            // 1. Primary Dispatch & Office Mobile
            SupportContactCard(
                title = "Office Mobile",
                phoneNumber = primaryMobile,
                modifier = Modifier.testTag("btn_call_office_mobile"),
                onCall = { callNumber(primaryMobile) }
            )

            // 2. Landline Office Desk
            SupportContactCard(
                title = "Office Landline",
                phoneNumber = secondaryLandline,
                modifier = Modifier.testTag("btn_call_office_landline"),
                onCall = { callNumber(secondaryLandline) }
            )

            
        }
    }
}

@Composable
private fun SupportContactCard(
    title: String,
    phoneNumber: String,
    modifier: Modifier = Modifier,
    onCall: () -> Unit
) {
    Card(
        onClick = onCall,
        colors = CardDefaults.cardColors(containerColor = TaxiWhite),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = phoneNumber,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF15803D)
                )
            }

            // Single Call Icon Action Button
            IconButton(
                onClick = onCall,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color(0xFF15803D),
                    contentColor = Color.White
                ),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Call $phoneNumber",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
