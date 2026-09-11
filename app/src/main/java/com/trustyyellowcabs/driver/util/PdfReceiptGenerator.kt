package com.trustyyellowcabs.driver.util

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.trustyyellowcabs.driver.data.Trip
import com.trustyyellowcabs.driver.service.TaxiMeterService
import java.io.File
import java.io.FileOutputStream
import java.util.Date

object PdfReceiptGenerator {
    fun generateTripReceipt(context: Context, trip: Trip): File? {
        val pdfDocument = PdfDocument()
        // Standard A4 dimensions in points (72 points per inch): 595 x 842
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        // Colors
        val colorWhite = Color.parseColor("#FFFFFF")
        val colorLightGray = Color.parseColor("#F3F4F6") // Slightly darker container background for better card contrast
        val colorDivider = Color.parseColor("#D1D5DB") // Darker divider lines for high contrast visibility
        val colorTextBlack = Color.parseColor("#111827") // Jet black for main text and titles
        val colorTextGray = Color.parseColor("#1F2937") // Much darker charcoal gray for standard texts
        val colorTextLightGray = Color.parseColor("#374151") // Darker slate gray for secondary labels/titles
        val colorYellowLogo = Color.parseColor("#FFD54F")
        val colorBlueAccent = Color.parseColor("#1D4ED8")
        val colorGreenRoute = Color.parseColor("#059669") // Richer green for route points
        val colorRedRoute = Color.parseColor("#DC2626") // Richer red for route points

        // Standard Typefaces (loading TrueType fonts directly to prevent OEM custom font theme distortion)
        val typefaceRegular = getFixedTypeface(TypefaceStyle.REGULAR)
        val typefaceMedium = getFixedTypeface(TypefaceStyle.MEDIUM)
        val typefaceBold = getFixedTypeface(TypefaceStyle.BOLD)

        // Paint definitions
        val bgPaint = Paint().apply {
            color = colorWhite
            style = Paint.Style.FILL
        }
        
        val logoBgPaint = Paint().apply {
            color = colorYellowLogo
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val logoTextPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 12f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val invoiceTitlePaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 26f
            isAntiAlias = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.04f
            }
        }

        val verificationTitlePaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 14f
            isAntiAlias = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.02f
            }
        }

        val invoiceNoPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceMedium
            textSize = 10f
            isAntiAlias = true
        }

        val companyNamePaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 12f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val companyDetailsPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 9f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val sectionTitlePaint = Paint().apply {
            color = colorTextLightGray
            typeface = typefaceBold
            textSize = 8f
            isAntiAlias = true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.08f
            }
        }

        val textBoldPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 11f
            isAntiAlias = true
        }

        val textRegularPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 10f
            isAntiAlias = true
        }

        val labelRoutePaint = Paint().apply {
            color = colorTextLightGray
            typeface = typefaceBold
            textSize = 8f
            isAntiAlias = true
        }

        val cardBgPaint = Paint().apply {
            color = colorLightGray
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val cardBorderPaint = Paint().apply {
            color = colorDivider
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        val cardTextBoldRightPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 18f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val cardTextRegularRightPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 9f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val dividerPaint = Paint().apply {
            color = colorDivider
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val routeLinePaint = Paint().apply {
            color = colorTextLightGray
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
            isAntiAlias = true
        }

        val greenDotPaint = Paint().apply {
            color = colorGreenRoute
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val redDotPaint = Paint().apply {
            color = colorRedRoute
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val chargeLabelPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceRegular
            textSize = 10f
            isAntiAlias = true
        }

        val chargeDetailPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 8.5f
            isAntiAlias = true
        }

        val chargeValueRightPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 10f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val blueAccentPaint = Paint().apply {
            color = colorBlueAccent
            typeface = typefaceBold
            textSize = 10f
            isAntiAlias = true
        }

        val blueAccentValueRightPaint = Paint().apply {
            color = colorBlueAccent
            typeface = typefaceBold
            textSize = 14f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val footerTextBoldPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 9f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.05f
            }
        }

        val footerTextRegularPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 8f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // 1. Draw light background
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        val marginX = 45f
        val rightLimitX = 550f
        var currentY = 50f

        // 2. Draw Header
        // Look up the custom logo image. It can be img_trusty_cab_logo.jpg/png in drawables
        val logoResId = context.resources.getIdentifier("img_trusty_cab_logo", "drawable", context.packageName)
        var logoBitmap: android.graphics.Bitmap? = null
        if (logoResId != 0) {
            try {
                logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, logoResId)
            } catch (e: Exception) {
                // Ignore loading failure
            }
        }

        val hasLogo = logoBitmap != null
        val logoHeight = 75f

        // Draw logo only if identified and loaded successfully
        if (hasLogo && logoBitmap != null) {
            val logoWidth = logoBitmap.width.toFloat()
            val origHeight = logoBitmap.height.toFloat()
            val targetWidth = (logoWidth / origHeight) * logoHeight
            // Ensure targetWidth is capped to a reasonable limit to prevent overlapping company text
            val finalWidth = minOf(targetWidth, 200f)
            val logoRect = RectF(marginX, currentY + 5f, marginX + finalWidth, currentY + 5f + logoHeight)
            val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(logoBitmap, null, logoRect, logoPaint)
        }

        // Invoice title and No vertical positioning depends on whether we have a logo
        val invoiceTitleY = if (hasLogo) currentY + 115f else currentY + 30f
        val invoiceNoY = if (hasLogo) currentY + 133f else currentY + 48f

        // Invoice title
        canvas.drawText("INVOICE", marginX, invoiceTitleY, invoiceTitlePaint)
        
        val tripIdCode = if (trip.tripIdCode.isNotEmpty()) trip.tripIdCode else trip.id.toString()
        canvas.drawText("Invoice No: $tripIdCode", marginX, invoiceNoY, invoiceNoPaint)

        // Company Details Right Aligned - Align evenly with the logo vertical space
        canvas.drawText("TRUSTY YELLOW CAB", rightLimitX, currentY + 15f, companyNamePaint)
        canvas.drawText("COIMBATORE, TAMILNADU -641007", rightLimitX, currentY + 31f, companyDetailsPaint)
        canvas.drawText("+91 422-3596446", rightLimitX, currentY + 45f, companyDetailsPaint)
        canvas.drawText("trustyyellowcabs@gmail.com", rightLimitX, currentY + 59f, companyDetailsPaint)
        canvas.drawText("www.trustyyellowcabs.in", rightLimitX, currentY + 73f, companyDetailsPaint)
        
        val sdf = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm a")
        val formattedDate = sdf.format(Date(trip.startTime))
        canvas.drawText("Date: $formattedDate", rightLimitX, currentY + 98f, companyDetailsPaint)

        // Thin divider with generous spacing
        val dividerY = if (hasLogo) currentY + 155f else currentY + 110f
        canvas.drawLine(marginX, dividerY, rightLimitX, dividerY, dividerPaint)

        currentY = dividerY

        // 3. Passenger Details & Trip Timings side-by-side
        currentY += 20f
        
        val sectionTitleRightPaint = Paint().apply {
            color = colorTextLightGray
            typeface = typefaceBold
            textSize = 8f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.08f
            }
        }

        val textRegularRightPaint = Paint().apply {
            color = colorTextGray
            typeface = typefaceRegular
            textSize = 9.5f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val timeSdf = com.trustyyellowcabs.driver.util.NetworkTimeHelper.getAsiaKolkataFormatter("dd MMM yyyy, hh:mm a")
        val formattedStart = timeSdf.format(Date(trip.startTime)) + " IST"
        val formattedEnd = timeSdf.format(Date(trip.endTime)) + " IST"

        // Left Column: Passenger Details
        canvas.drawText("PASSENGER DETAILS", marginX, currentY, sectionTitlePaint)
        // Right Column: Trip Timings Title
        canvas.drawText("TRIP TIMINGS", rightLimitX, currentY, sectionTitleRightPaint)

        currentY += 18f
        // Left Column: Customer Name
        canvas.drawText("Valued Customer", marginX, currentY, textBoldPaint)
        // Right Column: Start Time
        canvas.drawText("Start: $formattedStart", rightLimitX, currentY, textRegularRightPaint)

        currentY += 15f
        val customerMobile = if (trip.customerMobile.isNotEmpty()) "+91 ${trip.customerMobile}" else "Walk-in Guest"
        // Left Column: Customer Mobile
        canvas.drawText("Mobile: $customerMobile", marginX, currentY, textRegularPaint)
        // Right Column: End Time
        canvas.drawText("End: $formattedEnd", rightLimitX, currentY, textRegularRightPaint)

        // Thin divider
        currentY += 22f
        canvas.drawLine(marginX, currentY, rightLimitX, currentY, dividerPaint)

        // 4. Trip Route Flow
        currentY += 20f
        canvas.drawText("TRIP ROUTE ADDRESSES", marginX, currentY, sectionTitlePaint)

        // Pickup Location (Y layout begins here)
        val pickupY = currentY + 28f
        canvas.drawCircle(55f, pickupY - 5f, 4f, greenDotPaint)
        canvas.drawText("PICKUP POINT", 75f, pickupY - 10f, labelRoutePaint)
        
        val pickupLines = wrapTextToList(trip.startLocation, textRegularPaint, 475f)
        var pickupLineY = pickupY + 5f
        for (line in pickupLines) {
            canvas.drawText(line, 75f, pickupLineY, textRegularPaint)
            pickupLineY += 14f
        }

        // Dropoff Location starts below pickup lines
        val dropoffY = pickupLineY + 12f
        canvas.drawCircle(55f, dropoffY - 5f, 4f, redDotPaint)
        canvas.drawText("DROPOFF POINT", 75f, dropoffY - 10f, labelRoutePaint)

        val dropoffLines = wrapTextToList(trip.endLocation, textRegularPaint, 475f)
        var dropoffLineY = dropoffY + 5f
        for (line in dropoffLines) {
            canvas.drawText(line, 75f, dropoffLineY, textRegularPaint)
            dropoffLineY += 14f
        }

        // Draw dotted connection line between Pickup and Dropoff circles
        canvas.drawLine(55f, pickupY, 55f, dropoffY - 10f, routeLinePaint)

        // Thin divider below route
        currentY = dropoffLineY + 10f
        canvas.drawLine(marginX, currentY, rightLimitX, currentY, dividerPaint)

        // 5. Vehicle Details Rounded Card
        currentY += 15f
        val cardRect = RectF(marginX, currentY, rightLimitX, currentY + 68f)
        canvas.drawRoundRect(cardRect, 10f, 10f, cardBgPaint)

        // Card Content Left
        canvas.drawText("VEHICLE DETAILS", marginX + 15f, currentY + 22f, textBoldPaint)
        canvas.drawText("Vehicle: ${trip.vehicleModel.ifEmpty { "Yellow Cab" }} (${trip.vehicleCategory})", marginX + 15f, currentY + 38f, textRegularPaint)
        canvas.drawText("Reg No: ${trip.vehicleNumber.ifEmpty { "N/A" }}", marginX + 15f, currentY + 52f, textRegularPaint)

        // Card Content Right Aligned
        canvas.drawText("TOTAL DISTANCE", rightLimitX - 15f, currentY + 22f, cardTextRegularRightPaint)
        canvas.drawText(String.format("%.2f KM", trip.distance), rightLimitX - 15f, currentY + 44f, cardTextBoldRightPaint)
        canvas.drawText("Duration: ${TaxiMeterService.formatDuration(trip.durationSeconds)}", rightLimitX - 15f, currentY + 56f, cardTextRegularRightPaint)

        // Thin divider below Card
        currentY += 83f
        canvas.drawLine(marginX, currentY, rightLimitX, currentY, dividerPaint)

        // 6. Charges Breakdown
        currentY += 18f
        canvas.drawText("CHARGES BREAKDOWN", marginX, currentY, sectionTitlePaint)

        val breakdownItems = FareBreakdownHelper.calculateBreakdown(trip)

        var chargeRowY = currentY + 22f
        for (item in breakdownItems) {
            val isAdjustment = item.label.contains("Adjustment", ignoreCase = true) || item.label.contains("Minimum Fare", ignoreCase = true)
            val labelText = if (isAdjustment) {
                "Minimum Fare Adjustment"
            } else if (item.detail.isNotEmpty() && !item.detail.contains("Guarantee", ignoreCase = true)) {
                "${item.label} (${item.detail})"
            } else {
                item.label
            }
            canvas.drawText(labelText, marginX, chargeRowY, chargeLabelPaint)
            canvas.drawText("₹${String.format(java.util.Locale.US, "%.2f", item.amount)}", rightLimitX, chargeRowY, chargeValueRightPaint)
            chargeRowY += 16f
        }

        // 7. Large Grand Total Card & Driver Details side-by-side
        val totalCardY = chargeRowY + 12f
        val cardWidth = 240f
        val cardHeight = 94f
        val cardLeft = rightLimitX - cardWidth

        // Draw Total Card Background
        val totalCardRect = RectF(cardLeft, totalCardY, rightLimitX, totalCardY + cardHeight)
        canvas.drawRoundRect(totalCardRect, 12f, 12f, cardBgPaint)
        canvas.drawRoundRect(totalCardRect, 12f, 12f, cardBorderPaint)

        // Total Card Inner Content
        val grandTotalLabelPaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 11f
            isAntiAlias = true
        }

        val grandTotalValuePaint = Paint().apply {
            color = colorTextBlack
            typeface = typefaceBold
            textSize = 15f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val balanceLabelPaint = Paint().apply {
            color = colorBlueAccent
            typeface = typefaceBold
            textSize = 11f
            isAntiAlias = true
        }

        val balanceValuePaint = Paint().apply {
            color = colorBlueAccent
            typeface = typefaceBold
            textSize = 15f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        val grandTotalText = "₹${String.format("%.2f", trip.totalFare)}"

        // Align both label and value precisely at the same vertical positions
        canvas.drawText("GRAND TOTAL", cardLeft + 15f, totalCardY + 30f, grandTotalLabelPaint)
        canvas.drawText(grandTotalText, rightLimitX - 15f, totalCardY + 30f, grandTotalValuePaint)

        // Inside Total Card Divider Line
        canvas.drawLine(cardLeft + 15f, totalCardY + 46f, rightLimitX - 15f, totalCardY + 46f, dividerPaint)

        // Balance Payable in Blue (even in size and perfectly aligned with Grand Total)
        canvas.drawText("Balance Payable", cardLeft + 15f, totalCardY + 76f, balanceLabelPaint)
        canvas.drawText(grandTotalText, rightLimitX - 15f, totalCardY + 76f, balanceValuePaint)

        // 7b. Terms & Conditions Notes on Page 1 (Aligned inside the empty left space next to the Grand Total card)
        var leftNoteY = totalCardY + 26f
        val leftMaxWidth = cardLeft - marginX - 15f // 250f of available width
        
        val note1 = "Above fare given based on travel distance and waiting. Toll, Parking, Permit charges may applicable extra. T&C apply."
        val note2 = "Fare includes additional charges for out of the city limit pickup/drop."
        
        val leftNoteTitlePaint = Paint().apply {
            color = colorTextLightGray
            typeface = typefaceBold
            textSize = 7.5f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                letterSpacing = 0.05f
            }
        }

        val leftNotePaint = Paint().apply {
            color = Color.parseColor("#4B5563") // Gray 600 - clean and readable
            typeface = typefaceRegular
            textSize = 7.5f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
        
        val leftNotePaintItalic = Paint().apply {
            color = Color.parseColor("#4B5563") // Gray 600
            typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            textSize = 7.5f
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
        
        canvas.drawText("TERMS & CONDITIONS", marginX, leftNoteY, leftNoteTitlePaint)
        leftNoteY += 12f

        val lines1 = wrapTextToList(note1, leftNotePaint, leftMaxWidth)
        for (line in lines1) {
            canvas.drawText(line, marginX, leftNoteY, leftNotePaint)
            leftNoteY += 10.5f
        }
        
        leftNoteY += 3f // small spacing between notes
        val lines2 = wrapTextToList(note2, leftNotePaintItalic, leftMaxWidth)
        for (line in lines2) {
            canvas.drawText(line, marginX, leftNoteY, leftNotePaintItalic)
            leftNoteY += 10.5f
        }

        // 8. Footer Section
        val footerY = 745f
        canvas.drawLine(marginX, footerY, rightLimitX, footerY, dividerPaint)

        canvas.drawText("THANK YOU FOR TRAVELLING WITH US", 595f / 2f, footerY + 22f, footerTextBoldPaint)
        canvas.drawText("This is a computer generated invoice. No physical signature is required.", 595f / 2f, footerY + 36f, footerTextRegularPaint)

        // Finish Page
        pdfDocument.finishPage(page)

        // 9. Page 2: Driver & Company Verification Details
        val pageInfo2 = PdfDocument.PageInfo.Builder(595, 842, 2).create()
        val page2 = pdfDocument.startPage(pageInfo2)
        val canvas2 = page2.canvas

        // Draw light background on Page 2
        canvas2.drawRect(0f, 0f, 595f, 842f, bgPaint)

        var currentY2 = 50f

        // Section title on Page 2 (Logo has been removed from page 2 as requested)
        val secTitleY = currentY2 + 25f
        canvas2.drawText("Trustyyellowcabs Trusted & Verified Service", marginX, secTitleY, verificationTitlePaint)
        
        val docSubTitleY = currentY2 + 42f
        canvas2.drawText("Driver Profile - Passenger Safety Board", marginX, docSubTitleY, invoiceNoPaint)

        // Draw divider
        val dividerY2 = currentY2 + 65f
        canvas2.drawLine(marginX, dividerY2, rightLimitX, dividerY2, dividerPaint)
        
        currentY2 = dividerY2

        // Section: Driver Details
        currentY2 += 30f
        canvas2.drawText("DRIVER PROFILE", marginX, currentY2, sectionTitlePaint)

        // Draw the Driver Selfie Photo on Page 2!
        currentY2 += 25f
        val selfiePicY = currentY2
        var hasSelfieOnReceipt = false
        val prefs = context.getSharedPreferences("TrustyYellowCabPrefs", Context.MODE_PRIVATE)
        var selfiePath = prefs.getString("driver_selfie_path", "") ?: ""
        if (selfiePath.isEmpty() || !java.io.File(selfiePath).exists()) {
            val defaultFile = java.io.File(context.filesDir, "driver_selfie.jpg")
            if (defaultFile.exists()) {
                selfiePath = defaultFile.absolutePath
            }
        }

        if (selfiePath.isNotEmpty()) {
            try {
                // Decode options to find dimensions
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(selfiePath, options)
                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                
                // Calculate inSampleSize for target size of 800 (for pristine HD quality)
                var inSampleSize = 1
                val reqSize = 800
                if (srcHeight > reqSize || srcWidth > reqSize) {
                    val halfHeight = srcHeight / 2
                    val halfWidth = srcWidth / 2
                    while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                        inSampleSize *= 2
                    }
                }
                
                val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                    this.inSampleSize = inSampleSize
                }
                val selfieBitmap = android.graphics.BitmapFactory.decodeFile(selfiePath, decodeOptions)
                
                if (selfieBitmap != null) {
                    val photoBoxWidth = 135f
                    val photoBoxHeight = 160f
                    val highResWidth = 675
                    val highResHeight = 800
                    val highResRadius = 45f
                    val fittedPhotoBitmap = getFittedPhotoBitmap(
                        selfieBitmap,
                        highResWidth,
                        highResHeight,
                        highResRadius,
                        Color.parseColor("#F1F5F9")
                    )
                    val photoRect = RectF(marginX, selfiePicY, marginX + photoBoxWidth, selfiePicY + photoBoxHeight)
                    
                    val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                        isDither = true
                    }
                    canvas2.drawBitmap(fittedPhotoBitmap, null, photoRect, photoPaint)
                    
                    // Recycle loaded bitmaps to keep PDF memory usage low and save MBs
                    fittedPhotoBitmap.recycle()
                    selfieBitmap.recycle()
                    
                    hasSelfieOnReceipt = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // If selfie was not found or failed to load, draw a placeholder avatar
        if (!hasSelfieOnReceipt) {
            val photoBoxWidth = 135f
            val photoBoxHeight = 160f
            val placeholderBgPaint = Paint().apply {
                color = colorLightGray
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val placeholderRect = RectF(marginX, selfiePicY, marginX + photoBoxWidth, selfiePicY + photoBoxHeight)
            canvas2.drawRoundRect(placeholderRect, 10f, 10f, placeholderBgPaint)
            
            // Draw initials inside placeholder
            val initialsPaint = Paint().apply {
                color = colorTextBlack
                typeface = typefaceBold
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val initials = if (trip.driverName.isNotEmpty()) {
                trip.driverName.split(" ").filter { it.isNotEmpty() }.take(2).map { it.first().uppercase() }.joinToString("")
            } else {
                "TX"
            }
            
            // Vertically center initials text inside photo box bounds
            val textBounds = android.graphics.Rect()
            initialsPaint.getTextBounds(initials, 0, initials.length, textBounds)
            val centerY = selfiePicY + photoBoxHeight / 2f + (textBounds.height() / 2f)
            
            canvas2.drawText(initials, marginX + photoBoxWidth / 2f, centerY, initialsPaint)
        }

        // Driver details text next to the photo
        val textStartX = marginX + 155f
        var detailsY = selfiePicY + 20f

        canvas2.drawText("DRIVER NAME:", textStartX, detailsY, sectionTitlePaint)
        canvas2.drawText(trip.driverName.ifEmpty { "Verified Driver" }, textStartX, detailsY + 16f, textBoldPaint)

        detailsY += 45f
        canvas2.drawText("VEHICLE MODEL & CATEGORY:", textStartX, detailsY, sectionTitlePaint)
        canvas2.drawText("${trip.vehicleModel.ifEmpty { "Yellow Cab" }} (${trip.vehicleCategory})", textStartX, detailsY + 16f, textBoldPaint)

        detailsY += 45f
        canvas2.drawText("VEHICLE REGISTRATION NO:", textStartX, detailsY, sectionTitlePaint)
        canvas2.drawText(trip.vehicleNumber.ifEmpty { "N/A" }, textStartX, detailsY + 16f, textBoldPaint)

        // Draw a line separating driver and company
        currentY2 = selfiePicY + 170f
        canvas2.drawLine(marginX, currentY2, rightLimitX, currentY2, dividerPaint)

        // Section: Company Details
        currentY2 += 30f
        canvas2.drawText("TRUSTY YELLOW CAB • OFFICIAL DETAILS", marginX, currentY2, sectionTitlePaint)

        currentY2 += 25f
        val cardRect2 = RectF(marginX, currentY2, rightLimitX, currentY2 + 180f)
        canvas2.drawRoundRect(cardRect2, 12f, 12f, cardBgPaint)
        canvas2.drawRoundRect(cardRect2, 12f, 12f, cardBorderPaint)

        // Company content on Page 2
        var compY = currentY2 + 30f
        canvas2.drawText("OFFICIAL TRANSPORT OPERATOR:", marginX + 20f, compY, sectionTitlePaint)
        canvas2.drawText("TRUSTY YELLOW CAB SERVICE (COIMBATORE)", marginX + 20f, compY + 16f, textBoldPaint)

        compY += 45f
        canvas2.drawText("REGISTERED ADDRESS:", marginX + 20f, compY, sectionTitlePaint)
        canvas2.drawText("COIMBATORE, TAMILNADU, INDIA - 641007", marginX + 20f, compY + 16f, textRegularPaint)

        compY += 45f
        canvas2.drawText("CONTACT CHANNELS:", marginX + 20f, compY, sectionTitlePaint)
        canvas2.drawText("Phone: +91 422-3596446  |  Email: trustyyellowcabs@gmail.com", marginX + 20f, compY + 16f, textRegularPaint)
        canvas2.drawText("Support Website: www.trustyyellowcabs.in", marginX + 20f, compY + 30f, textRegularPaint)

        // Page 2 Footer
        val footerY2 = 745f
        canvas2.drawLine(marginX, footerY2, rightLimitX, footerY2, dividerPaint)
        canvas2.drawText("TRUSTY YELLOW CAB - WE CARE FOR YOUR SAFETY", 595f / 2f, footerY2 + 22f, footerTextBoldPaint)
        canvas2.drawText("Page 2 of 2  •  This is a computer generated document verification of the driver's identity and registration.", 595f / 2f, footerY2 + 36f, footerTextRegularPaint)

        // Finish Page 2
        pdfDocument.finishPage(page2)

        // Write output to cache directory
        val tripIdentifier = if (trip.tripIdCode.isNotEmpty()) trip.tripIdCode else trip.id.toString()
        val file = File(context.cacheDir, "trip_receipt_$tripIdentifier.pdf")
        logoBitmap?.recycle()
        return try {
            val fos = FileOutputStream(file)
            pdfDocument.writeTo(fos)
            pdfDocument.close()
            fos.close()
            file
        } catch (e: Exception) {
            pdfDocument.close()
            null
        }
    }

    private fun wrapTextToList(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isEmpty()) return listOf("N/A")
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""
        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val width = paint.measureText(testLine)
            if (width > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine)
                }
                currentLine = word
            } else {
                currentLine = testLine
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }
        return lines
    }

    private fun getFittedPhotoBitmap(
        bitmap: android.graphics.Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        cornerRadius: Float,
        containerBgColor: Int
    ): android.graphics.Bitmap {
        val output = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        
        // 1. Draw rounded background container
        val bgPaint = Paint().apply {
            color = containerBgColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val fullRect = android.graphics.RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        canvas.drawRoundRect(fullRect, cornerRadius, cornerRadius, bgPaint)
        
        // 2. Calculate proportional fit bounds (never crop any portion of the original photo)
        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        if (srcW > 0f && srcH > 0f) {
            val scale = minOf(targetWidth.toFloat() / srcW, targetHeight.toFloat() / srcH)
            val destW = srcW * scale
            val destH = srcH * scale
            val destX = (targetWidth.toFloat() - destW) / 2f
            val destY = (targetHeight.toFloat() - destH) / 2f
            
            // Clip to rounded container so corners remain smoothly curved
            val clipPath = android.graphics.Path().apply {
                addRoundRect(fullRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            
            val photoPaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            val destRect = android.graphics.RectF(destX, destY, destX + destW, destY + destH)
            canvas.drawBitmap(bitmap, null, destRect, photoPaint)
            canvas.restore()
        }
        
        // 3. Draw subtle border around photo card
        val borderPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1") // Slate 300
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(fullRect, cornerRadius, cornerRadius, borderPaint)
        
        return output
    }

    private enum class TypefaceStyle {
        REGULAR,
        MEDIUM,
        BOLD
    }

    private fun getFixedTypeface(style: TypefaceStyle): Typeface {
        val candidatePaths = when (style) {
            TypefaceStyle.REGULAR -> listOf(
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/RobotoStatic-Regular.ttf",
                "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/system/fonts/DroidSans.ttf"
            )
            TypefaceStyle.MEDIUM -> listOf(
                "/system/fonts/Roboto-Medium.ttf",
                "/system/fonts/RobotoStatic-Medium.ttf",
                "/system/fonts/NotoSans-Medium.ttf",
                "/system/fonts/Roboto-Regular.ttf",
                "/system/fonts/RobotoStatic-Regular.ttf"
            )
            TypefaceStyle.BOLD -> listOf(
                "/system/fonts/Roboto-Bold.ttf",
                "/system/fonts/RobotoStatic-Bold.ttf",
                "/system/fonts/NotoSans-Bold.ttf",
                "/system/fonts/DroidSans-Bold.ttf",
                "/system/fonts/Roboto-Regular.ttf"
            )
        }

        for (path in candidatePaths) {
            try {
                val fontFile = File(path)
                if (fontFile.exists() && fontFile.canRead() && fontFile.length() > 0) {
                    val tf = Typeface.createFromFile(fontFile)
                    if (tf != null) {
                        return tf
                    }
                }
            } catch (_: Exception) {
                // Continue to next candidate
            }
        }

        // Fallback if no direct system font file is accessible
        return when (style) {
            TypefaceStyle.REGULAR -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            TypefaceStyle.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            TypefaceStyle.BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
    }
}
