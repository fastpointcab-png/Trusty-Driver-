package com.trustyyellowcabs.driver.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.trustyyellowcabs.driver.ui.theme.Slate100
import com.trustyyellowcabs.driver.ui.theme.Slate200
import com.trustyyellowcabs.driver.ui.theme.TaxiRed
import com.trustyyellowcabs.driver.util.GoogleDriveUtils
import java.io.File

@Composable
fun DriverAvatar(
    driverName: String,
    photoUrl: String = "",
    localSelfiePath: String = "",
    size: Dp = 52.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    fontSize: TextUnit = 18.sp,
    contentScale: ContentScale = ContentScale.Fit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val initials = remember(driverName) {
        driverName.split(" ")
            .filter { it.isNotEmpty() }
            .take(2)
            .map { it.first().uppercase() }
            .joinToString("")
            .ifEmpty { "TX" }
    }

    // Determine candidate image model:
    // 1. If photoUrl is set, resolve with GoogleDriveUtils (supports Drive, Firebase, HTTP)
    // 2. Else if localSelfiePath exists on device, use File(localSelfiePath)
    val imageModel = remember(photoUrl, localSelfiePath) {
        val cleanUrl = photoUrl.trim()
        if (cleanUrl.isNotEmpty()) {
            GoogleDriveUtils.toDirectImageUrl(cleanUrl)
        } else if (localSelfiePath.isNotEmpty() && File(localSelfiePath).exists()) {
            File(localSelfiePath)
        } else {
            null
        }
    }

    if (imageModel != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(Color(0xFFF1F5F9))
                .border(1.dp, Slate200, shape),
            contentAlignment = Alignment.Center
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(true)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build(),
                contentDescription = "Driver Photo",
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    // Show subtle loader or initials placeholder while fetching
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF1F5F9)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = TaxiRed,
                            modifier = Modifier.size(size / 3)
                        )
                    }
                },
                error = {
                    // If direct link failed and local file exists, try fallback
                    val fallbackFile = if (localSelfiePath.isNotEmpty()) File(localSelfiePath) else null
                    if (fallbackFile != null && fallbackFile.exists()) {
                        val localBitmap = remember(localSelfiePath) {
                            try {
                                BitmapFactory.decodeFile(fallbackFile.absolutePath)?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (localBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = localBitmap,
                                contentDescription = "Driver Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = contentScale
                            )
                        } else {
                            InitialsPlaceholder(initials = initials, fontSize = fontSize)
                        }
                    } else {
                        InitialsPlaceholder(initials = initials, fontSize = fontSize)
                    }
                }
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(Slate100)
                .border(1.dp, Slate200, shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                color = TaxiRed
            )
        }
    }
}

@Composable
private fun InitialsPlaceholder(
    initials: String,
    fontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate100),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            color = TaxiRed
        )
    }
}
