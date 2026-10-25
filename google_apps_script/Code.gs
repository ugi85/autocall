/**
 * ==============================================================================
 * AUTOCALL GOOGLE APPS SCRIPT BACKEND
 * ==============================================================================
 * Script ini melayani 2 fungsi untuk aplikasi AutoCall menggunakan 2 Sheet:
 * 1. Sheet "Users"   : Autentikasi Pengguna (Login)
 * 2. Sheet "Kontak"  : Penarikan Data Daftar Kontak / Leads AutoCall
 *
 * CARA SETUP:
 * 1. Buka Google Spreadsheet Anda (yang memiliki tab "Kontak" dan "Users").
 * 2. Di tab browser "Untitled project - Project Editor" (Apps Script):
 *    - Hapus semua isi file default (myFunction)
 *    - Salin seluruh isi file Code.gs ini dan tempel (paste).
 *    - Klik ikon disket (Save) atau tekan Ctrl + S.
 * 3. Klik tombol "Deploy" di kanan atas > pilih "New deployment"
 * 4. Klik ikon gerigi (Select type) > pilih "Web app".
 *    - Description : AutoCall Backend v1
 *    - Execute as  : Me (akun Google Anda)
 *    - Who has access: Anyone (Siapa saja)
 * 5. Klik "Deploy" > "Authorize access" > pilih akun > Advanced > Go to AutoCall (unsafe) > Allow.
 * 6. Salin Web app URL yang didapat (akhiran /exec) dan masukkan ke AutoCall di layar Login.
 * ==============================================================================
 */

// ==============================================================================
// 1. FUNGSI AKTIVASI IZIN TULIS GOOGLE DRIVE (Opsional: jalankan sekali di editor)
// ==============================================================================
function izinkanGoogleDrive() {
  var folderName = "AutoCall Recordings";
  var folders = DriveApp.getFoldersByName(folderName);
  var folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(folderName);
  var file = folder.createFile("test_izin.txt", "Izin Menulis Google Drive Berhasil!", "text/plain");
  file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  Logger.log("✅ SUKSES! Folder 'AutoCall Recordings' sudah dibuat di Drive Anda: " + folder.getUrl());
}

// ==============================================================================
// 2. ROUTER HTTP GET (Login & Penarikan Kontak)
// ==============================================================================
function doGet(e) {
  try {
    var params = (e && e.parameter) ? e.parameter : {};
    var action = (params.action || '').toLowerCase().trim();

    // 1. ENDPOINT LOGIN
    if (action === 'login') {
      return handleLogin(params.username, params.password);
    }

    // 2. ENDPOINT CHECK / STATUS
    if (action === 'ping' || action === 'status') {
      return createJsonResponse({
        status: 'success',
        message: 'AutoCall Google Apps Script API Aktif',
        serverTime: new Date().toISOString()
      });
    }

    // 3. DEFAULT: PENARIKAN DATA KONTAK / LEADS
    // Jika tidak ada action atau action=getleads / contacts, kembalikan daftar nomor
    return handleGetLeads();

  } catch (error) {
    return createJsonResponse({
      status: 'error',
      message: 'Server Error: ' + error.toString()
    });
  }
}

function doPost(e) {
  try {
    var payload = {};
    if (e && e.postData && e.postData.contents) {
      try {
        payload = JSON.parse(e.postData.contents);
      } catch (err) {
        payload = (e && e.parameter) ? e.parameter : {};
      }
    } else if (e && e.parameter) {
      payload = e.parameter;
    }

    var action = (payload.action || '').toLowerCase().trim();

    if (action === 'login') {
      return handleLogin(payload.username, payload.password);
    }

    // Support update status panggilan / kontak
    if (action === 'update_status' || action === 'updatestatus') {
      return handleUpdateCallStatus(payload);
    }

    // Default fallback
    return doGet(e);

  } catch (error) {
    return createJsonResponse({
      status: 'error',
      message: 'Server POST Error: ' + error.toString()
    });
  }
}

/**
 * Logika verifikasi Login pengguna terhadap Sheet "Users"
 */
function handleLogin(username, password) {
  if (!username || !password) {
    return createJsonResponse({
      status: 'error',
      message: 'Username dan kata sandi harus diisi.'
    });
  }

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var userSheet = ss.getSheetByName("Users");

  // Jika Sheet "Users" belum ada, buat otomatis dan isi akun default
  if (!userSheet) {
    userSheet = ss.insertSheet("Users");
    userSheet.appendRow(["Username", "Password", "Name", "Role", "Status"]);
    userSheet.appendRow(["admin", "admin123", "Administrator", "Admin", "Active"]);
    userSheet.appendRow(["callcenter", "123456", "Staff Operator", "Operator", "Active"]);
    
    // Format header
    var headerRange = userSheet.getRange(1, 1, 1, 5);
    headerRange.setFontWeight("bold");
    headerRange.setBackground("#1565C0");
    headerRange.setFontColor("#FFFFFF");
  }

  var data = userSheet.getDataRange().getValues();
  if (data.length <= 1) {
    // Sheet ada tapi kosong hanya header
    userSheet.appendRow(["admin", "admin123", "Administrator", "Admin", "Active"]);
    data = userSheet.getDataRange().getValues();
  }

  var inputUser = String(username).trim().toLowerCase();
  var inputPass = String(password).trim();

  // Cari di data baris (baris 0 adalah header)
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var rowUser = String(row[0]).trim().toLowerCase();
    var rowPass = String(row[1]).trim();
    var rowName = String(row[2] || row[0]).trim();
    var rowRole = String(row[3] || 'User').trim();
    var rowStatus = String(row[4] || 'Active').trim();

    if (rowUser === inputUser) {
      if (rowPass === inputPass) {
        if (rowStatus.toLowerCase() === 'inactive' || rowStatus.toLowerCase() === 'blocked') {
          return createJsonResponse({
            status: 'error',
            message: 'Akun Anda dinonaktifkan. Silakan hubungi administrator.'
          });
        }

        // Login Berhasil!
        return createJsonResponse({
          status: 'success',
          message: 'Login berhasil!',
          user: {
            username: row[0],
            name: rowName,
            role: rowRole,
            status: rowStatus
          }
        });
      } else {
        return createJsonResponse({
          status: 'error',
          message: 'Kata sandi salah.'
        });
      }
    }
  }

  return createJsonResponse({
    status: 'error',
    message: 'Pengguna "' + username + '" tidak ditemukan.'
  });
}

/**
 * Mengambil daftar kontak / leads untuk AutoCall (Sheet 'Kontak', 'Leads', atau Sheet pertama)
 */
function handleGetLeads() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Kontak") || ss.getSheetByName("Leads") || ss.getSheets()[0];
  var data = sheet.getDataRange().getValues();

  if (data.length <= 1) {
    return createJsonResponse([]);
  }

  var headers = data[0];
  var leads = [];

  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var obj = {};
    for (var j = 0; j < headers.length; j++) {
      var headerKey = String(headers[j]).trim();
      obj[headerKey] = row[j];
    }
    leads.push(obj);
  }

  return createJsonResponse(leads);
}

/**
 * Update status panggilan jika dikirim balik dari aplikasi
 */
function handleUpdateCallStatus(payload) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName("Kontak") || ss.getSheetByName("Leads") || ss.getSheets()[0];
  var data = sheet.getDataRange().getValues();

  var phone = String(payload.phone || payload.phoneNumber || '').trim();
  var status = String(payload.status || '').trim();
  var record = String(payload.recordUrl || payload.record || '').trim();

  if (!phone) {
    return createJsonResponse({ status: 'error', message: 'Nomor telepon kosong' });
  }

  // Cari kolom No_Tlp dan Status
  var headers = data[0];
  var phoneCol = -1;
  var statusCol = -1;
  var recordCol = -1;

  for (var j = 0; j < headers.length; j++) {
    var h = String(headers[j]).toLowerCase();
    if (h.includes('tlp') || h.includes('telp') || h.includes('phone')) phoneCol = j + 1;
    if (h.includes('status')) statusCol = j + 1;
    if (h.includes('record')) recordCol = j + 1;
  }

  if (phoneCol > 0 && statusCol > 0) {
    for (var i = 1; i < data.length; i++) {
      var rowPhone = String(data[i][phoneCol - 1]).replace(/[^0-9]/g, '');
      var cleanPhone = phone.replace(/[^0-9]/g, '');

      if (rowPhone && cleanPhone && (rowPhone === cleanPhone || rowPhone.endsWith(cleanPhone) || cleanPhone.endsWith(rowPhone))) {
        sheet.getRange(i + 1, statusCol).setValue(status);
        if (recordCol > 0 && record) {
          sheet.getRange(i + 1, recordCol).setValue(record);
        }
        return createJsonResponse({ status: 'success', message: 'Status berhasil diperbarui' });
      }
    }
  }

  return createJsonResponse({ status: 'ignored', message: 'Data baris nomor tidak ditemukan di sheet' });
}

/**
 * Utility untuk membuat Output JSON dengan MIME Type yang benar
 */
function createJsonResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
