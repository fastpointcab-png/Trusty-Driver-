/**
 * Trusty Yellow Cab - Google Sheets Web App Sync Script (V12 Update)
 * 
 * INSTRUCTIONS FOR DEPLOYMENT:
 * 1. Open your Google Sheet.
 * 2. Click on "Extensions" -> "Apps Script".
 * 3. Delete any default code in Code.gs and paste this entire script.
 * 4. Click "Save" (disk icon).
 * 5. Click "Deploy" -> "New deployment".
 * 6. Under "Select type", choose "Web app".
 * 7. Set:
 *    - Description: "Trusty Yellow Cab Driver Sync"
 *    - Execute as: "Me" (your email)
 *    - Who has access: "Anyone" (This is required for the app to send data)
 * 8. Click "Deploy".
 * 9. Copy the generated "Web app URL" and paste it as the dynamic URL in your app
 *    or update the HARDCODED_SHEETS_URL in GoogleSheetsSyncManager.kt!
 */

function doPost(e) {
  try {
    var jsonString = e.postData.contents;
    var data = JSON.parse(jsonString);
    var sheetApp = SpreadsheetApp.getActiveSpreadsheet();
    
    // Create/get the required sheets
    var driversSheet = getOrCreateSheet(sheetApp, "Drivers");
    var tripsSheet = getOrCreateSheet(sheetApp, "Trips");
    
    // Ensure Headers exist with Trusty Yellow styling in the EXACT requested order
    var driverHeaders = [
      "Driver Name", 
      "Vehicle Number", 
      "Vehicle Category", 
      "Vehicle Model", 
      "Driver Mobile", 
      "Device ID", 
      "Last Active Time (IST)"
    ];
    
    var tripHeaders = [
      "Trip ID Code", 
      "Date (IST)", 
      "Driver Name", 
      "Vehicle Number", 
      "Vehicle Category", 
      "Vehicle Model", 
      "Start Time (IST)", 
      "End Time (IST)", 
      "Distance (KM)", 
      "Duration", 
      "Waiting Time", 
      "Total Fare (INR)", 
      "Base Fare (INR)", 
      "Per KM Fare (INR)", 
      "Waiting Charge/Min", 
      "Minimum Fare Bound", 
      "Night Charge %", 
      "CC Commission (INR)", 
      "Customer Mobile", 
      "Start Location", 
      "End Location", 
      "Is Package Meter?", 
      "Package Name", 
      "Package Base Fare", 
      "Included KM", 
      "Included Minutes", 
      "Extra KM Rate", 
      "Extra Time Rate", 
      "Package Waiting/Min", 
      "Device ID"
    ];

    initHeaders(driversSheet, driverHeaders);
    initHeaders(tripsSheet, tripHeaders);

    var response = {};

    if (data.type === "login" || data.type === "logout") {
      var driverMobile = (data.driverMobile || "").trim();
      var vehicleNumber = (data.vehicleNumber || "").trim().toUpperCase();
      var driverName = (data.driverName || "").trim().toUpperCase();
      var statusStr = (data.type === "login") ? "Online" : "Offline";
      
      var rows = driversSheet.getDataRange().getValues();
      var matchedRowIndex = -1;
      
      // Look for existing driver based on Mobile Number or Vehicle Number (skipping header row)
      for (var i = 1; i < rows.length; i++) {
        var sheetMobile = String(rows[i][4] || "").trim();
        var sheetVehicle = String(rows[i][1] || "").trim().toUpperCase();
        
        if ((driverMobile !== "" && sheetMobile === driverMobile) || 
            (vehicleNumber !== "" && sheetVehicle === vehicleNumber)) {
          matchedRowIndex = i + 1; // 1-based index row in Google Sheets
          break;
        }
      }
      
      var timestampStr = data.dateStr || new Date().toLocaleString("en-US", {timeZone: "Asia/Kolkata"});
      
      if (matchedRowIndex !== -1) {
        // Update existing row
        if (driverName !== "") driversSheet.getRange(matchedRowIndex, 1).setValue(driverName);
        if (vehicleNumber !== "") driversSheet.getRange(matchedRowIndex, 2).setValue(vehicleNumber);
        if (data.vehicleCategory) driversSheet.getRange(matchedRowIndex, 3).setValue(data.vehicleCategory);
        if (data.vehicleModel) driversSheet.getRange(matchedRowIndex, 4).setValue(data.vehicleModel);
        if (driverMobile !== "") driversSheet.getRange(matchedRowIndex, 5).setValue(driverMobile);
        if (data.deviceId) driversSheet.getRange(matchedRowIndex, 6).setValue(data.deviceId);
        driversSheet.getRange(matchedRowIndex, 7).setValue(timestampStr);
        
        response = {
          status: "success",
          message: "Driver profile updated successfully"
        };
      } else {
        // Only insert if it's a login, if it's a logout and not found we don't need to append
        if (data.type === "login") {
          driversSheet.appendRow([
            driverName,
            vehicleNumber,
            data.vehicleCategory || "Mini",
            data.vehicleModel || "N/A",
            driverMobile,
            data.deviceId || "",
            timestampStr
          ]);
          
          response = {
            status: "success",
            message: "New driver profile registered successfully"
          };
        } else {
          response = {
            status: "success",
            message: "Driver logout completed"
          };
        }
      }
      
      // Auto formatting styles for the table row
      formatTableRows(driversSheet, driverHeaders.length);
      
    } else if (data.type === "trip") {
      // HANDLE COMPLETED TRIP SYNC (Always append to Trips sheet to keep full historical logs)
      var durationFormatted = formatDuration(data.durationSeconds);
      var waitingFormatted = formatDuration(data.waitingSeconds);
      
      tripsSheet.appendRow([
        data.tripIdCode || "N/A",
        data.dateStr || new Date().toLocaleString("en-US", {timeZone: "Asia/Kolkata"}),
        data.driverName || "N/A",
        data.vehicleNumber || "N/A",
        data.vehicleCategory || "N/A",
        data.vehicleModel || "N/A",
        data.startTime || "N/A",
        data.endTime || "N/A",
        Number(data.distance || 0),
        durationFormatted,
        waitingFormatted,
        Number(data.totalFare || 0),
        Number(data.baseFare || 0),
        Number(data.perKmFare || 0),
        Number(data.waitingChargePerMin || 0),
        Number(data.minimumFare || 0),
        Number(data.nightChargePercent || 0),
        Number(data.ccCommission || 0),
        data.customerMobile || "",
        data.startLocation || "Unknown",
        data.endLocation || "Unknown",
        data.isPackageMeter ? "TRUE" : "FALSE",
        data.packageName || "",
        Number(data.packageBaseFare || 0),
        Number(data.includedKm || 0),
        Number(data.includedMinutes || 0),
        Number(data.extraKmRate || 0),
        Number(data.extraTimeRate || 0),
        Number(data.packageWaitingChargePerMin || 0),
        data.deviceId || ""
      ]);
      
      // Highlight Package Meter rows beautifully to distinguish them
      var lastRow = tripsSheet.getLastRow();
      if (data.isPackageMeter) {
        var rowRange = tripsSheet.getRange(lastRow, 1, 1, tripHeaders.length);
        rowRange.setBackground("#FFFDE7"); // Very light warm yellow for package trips
      }
      
      response = {
        status: "success",
        message: "Trip synced successfully"
      };
      
      formatTableRows(tripsSheet, tripHeaders.length);
    } else {
      response = {
        status: "error",
        message: "Unknown request type: " + data.type
      };
    }
    
    return ContentService.createTextOutput(JSON.stringify(response))
      .setMimeType(ContentService.MimeType.JSON);
      
  } catch (error) {
    var errorResponse = {
      status: "error",
      message: error.toString()
    };
    return ContentService.createTextOutput(JSON.stringify(errorResponse))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

// Helper to format duration beautifully
function formatDuration(seconds) {
  if (!seconds || isNaN(seconds)) return "0s";
  var hrs = Math.floor(seconds / 3600);
  var mins = Math.floor((seconds % 3600) / 60);
  var secs = seconds % 60;
  
  var parts = [];
  if (hrs > 0) parts.push(hrs + "h");
  if (mins > 0) parts.push(mins + "m");
  if (secs > 0 || parts.length === 0) parts.push(secs + "s");
  return parts.join(" ");
}

// Helper to open or create a sheet tab by name
function getOrCreateSheet(spreadsheet, name) {
  var sheet = spreadsheet.getSheetByName(name);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(name);
  }
  return sheet;
}

// Helper to set headers and style them beautifully (Yellow trust theme)
function initHeaders(sheet, headers) {
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(headers);
  }
  
  // Always style the header row (Row 1) to make sure it's gorgeous and up-to-date
  var headerRange = sheet.getRange(1, 1, 1, headers.length);
  headerRange.setFontWeight("bold");
  headerRange.setBackgroundColor("#FBC02D"); // Trusty Yellow theme header color (#FBC02D)
  headerRange.setFontColor("#111111");       // Dark high-contrast text
  headerRange.setHorizontalAlignment("center");
  headerRange.setVerticalAlignment("middle");
  sheet.setRowHeight(1, 32);                 // Nice spacious header height
  sheet.setFrozenRows(1);
}

// Auto format rows with light gray gridlines, custom heights, and column auto-sizing
function formatTableRows(sheet, colCount) {
  var lastRow = sheet.getLastRow();
  if (lastRow <= 1) return;
  
  // Set default heights for all data rows to be comfortable and spacious (24px)
  for (var r = 2; r <= lastRow; r++) {
    sheet.setRowHeight(r, 24);
  }
  
  // Auto-resize columns to fit content beautifully without truncation
  sheet.autoResizeColumns(1, colCount);
  
  // Alignments
  var dataRange = sheet.getRange(2, 1, lastRow - 1, colCount);
  dataRange.setVerticalAlignment("middle");
}
