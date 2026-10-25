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

    // 3. ENDPOINT UPDATE STATUS PANGGILAN (GET Fallback)
    if (action === 'update_status' || action === 'updatestatus') {
      return handleUpdateCallStatus(params);
    }

    // 4. DEFAULT: PENARIKAN DATA KONTAK / LEADS
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

    // Support upload rekaman audio ke Google Drive & tautkan ke Sheet Kontak
    if (action === 'upload_recording' || action === 'uploadrecording') {
      return handleUploadRecording(payload);
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
    userSheet.appendRow(["No", "Username", "Password", "Name", "Role", "Status"]);
    userSheet.appendRow([1, "admin", "admin123", "Administrator", "Admin", "Active"]);
    userSheet.appendRow([2, "callcenter", "123456", "Staff Operator", "Operator", "Active"]);
    
    // Format header
    var headerRange = userSheet.getRange(1, 1, 1, 6);
    headerRange.setFontWeight("bold");
    headerRange.setBackground("#1565C0");
    headerRange.setFontColor("#FFFFFF");
  }

  var data = userSheet.getDataRange().getValues();
  if (data.length <= 1) {
    // Sheet ada tapi kosong hanya header
    userSheet.appendRow([1, "admin", "admin123", "Administrator", "Admin", "Active"]);
    data = userSheet.getDataRange().getValues();
  }

  var headers = data[0];
  var userCol = -1;
  var passCol = -1;
  var nameCol = -1;
  var roleCol = -1;
  var statusCol = -1;

  // Deteksi kolom secara dinamis berdasarkan nama header
  for (var j = 0; j < headers.length; j++) {
    var h = String(headers[j]).toLowerCase().replace(/[^a-z0-9]/g, '');
    if (h === 'username' || h === 'user' || h === 'id') userCol = j;
    else if (h === 'password' || h === 'pass' || h === 'sandi') passCol = j;
    else if (h === 'name' || h === 'nama' || h === 'fullname') nameCol = j;
    else if (h === 'role' || h === 'jabatan' || h === 'level') roleCol = j;
    else if (h === 'status' || h === 'state') statusCol = j;
  }

  // Fallback jika header tidak terdeteksi
  if (userCol === -1) userCol = 0;
  if (passCol === -1) passCol = 1;
  if (nameCol === -1) nameCol = 2;
  if (roleCol === -1) roleCol = 3;
  if (statusCol === -1) statusCol = 4;

  var inputUser = String(username).trim().toLowerCase();
  var inputPass = String(password).trim();

  // Cari di data baris (baris 0 adalah header)
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var rowUser = String(row[userCol] || '').trim().toLowerCase();
    var rowPass = String(row[passCol] || '').trim();
    var rowName = String(row[nameCol] || row[userCol] || '').trim();
    var rowRole = String(row[roleCol] || 'User').trim();
    var rowStatus = String(row[statusCol] || 'Active').trim();

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
            username: String(row[userCol]),
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

    // Filter baris kosong: pastikan baris memiliki data (tidak kosong semua)
    var hasContent = false;
    for (var k = 0; k < row.length; k++) {
      if (String(row[k]).trim() !== '') {
        hasContent = true;
        break;
      }
    }
    if (!hasContent) continue;

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

  var phone = String(payload.phone || payload.phoneNumber || payload.phone_number || '').trim();
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
    if (h.includes('tlp') || h.includes('telp') || h.includes('phone') || h.includes('hp')) phoneCol = j + 1;
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
 * Menerima file rekaman audio dari HP (dalam format base64),
 * menyimpannya ke folder Google Drive "AutoCall Recordings",
 * dan memasukkan hyperlink "=HYPERLINK(..., '▶ Putar Rekaman')" ke kolom "Record" di Sheet "Kontak".
 */
function handleUploadRecording(payload) {
  try {
    var phoneNumber = String(payload.phone_number || payload.phone || '').trim();
    var contactName = String(payload.name || '').trim();
    var duration = payload.call_duration || 0;
    var status = String(payload.status || (duration > 0 ? 'TERHUBUNG (' + duration + 's)' : 'SELESAI')).trim();
    var fileName = String(payload.file_name || ('Call_' + phoneNumber + '_' + new Date().getTime() + '.m4a')).trim();
    var audioBase64 = payload.audio_base64;

    if (!audioBase64) {
      return createJsonResponse({
        status: 'error',
        message: 'Data audio base64 kosong'
      });
    }

    // 1. Dapatkan atau buat folder "AutoCall Recordings" di Google Drive
    var folderName = "AutoCall Recordings";
    var folders = DriveApp.getFoldersByName(folderName);
    var folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(folderName);

    // 2. Tentukan MIME Type berdasarkan ekstensi file
    var contentType = "audio/mp4";
    var lowerName = fileName.toLowerCase();
    if (lowerName.endsWith(".mp3")) contentType = "audio/mpeg";
    else if (lowerName.endsWith(".m4a")) contentType = "audio/mp4";
    else if (lowerName.endsWith(".amr")) contentType = "audio/amr";
    else if (lowerName.endsWith(".wav")) contentType = "audio/wav";
    else if (lowerName.endsWith(".aac")) contentType = "audio/aac";
    else if (lowerName.endsWith(".3gp")) contentType = "audio/3gpp";
    else if (lowerName.endsWith(".ogg") || lowerName.endsWith(".opus")) contentType = "audio/ogg";

    // 3. Decode base64 dan simpan file ke Google Drive
    var decodedBytes = Utilities.base64Decode(audioBase64);
    var blob = Utilities.newBlob(decodedBytes, contentType, fileName);
    var driveFile = folder.createFile(blob);

    // Buka akses file agar bisa didengarkan oleh siapa saja yang memiliki link
    driveFile.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    var fileUrl = driveFile.getUrl();

    // 4. Catat link rekaman dan update status ke Sheet "Kontak"
    var ss = SpreadsheetApp.getActiveSpreadsheet();
    var sheet = ss.getSheetByName("Kontak") || ss.getSheetByName("Leads") || ss.getSheets()[0];
    var data = sheet.getDataRange().getValues();
    var headers = data[0];

    var phoneCol = -1;
    var statusCol = -1;
    var recordCol = -1;

    for (var j = 0; j < headers.length; j++) {
      var h = String(headers[j]).toLowerCase();
      if (h.includes('tlp') || h.includes('telp') || h.includes('phone') || h.includes('hp')) phoneCol = j + 1;
      if (h.includes('status')) statusCol = j + 1;
      if (h.includes('record')) recordCol = j + 1;
    }

    // Jika kolom Record belum ada di header, tambahkan otomatis
    if (recordCol === -1) {
      recordCol = headers.length + 1;
      sheet.getRange(1, recordCol).setValue("Record");
      var headerRange = sheet.getRange(1, recordCol);
      headerRange.setFontWeight("bold");
      headerRange.setBackground("#1565C0");
      headerRange.setFontColor("#FFFFFF");
    }

    // Formula hyperlink: =HYPERLINK("...", "▶ Putar Rekaman")
    var recordFormula = '=HYPERLINK("' + fileUrl + '", "▶ Putar Rekaman")';

    var rowMatched = false;
    if (phoneCol > 0 && phoneNumber) {
      var cleanInputPhone = phoneNumber.replace(/[^0-9]/g, '');
      for (var i = 1; i < data.length; i++) {
        var rowPhone = String(data[i][phoneCol - 1]).replace(/[^0-9]/g, '');
        if (rowPhone && cleanInputPhone && (rowPhone === cleanInputPhone || rowPhone.endsWith(cleanInputPhone) || cleanInputPhone.endsWith(rowPhone))) {
          if (statusCol > 0) {
            sheet.getRange(i + 1, statusCol).setValue(status);
          }
          sheet.getRange(i + 1, recordCol).setValue(recordFormula);
          rowMatched = true;
          break;
        }
      }
    }

    // Jika nomor belum terdaftar di sheet, tambahkan baris baru
    if (!rowMatched && phoneCol > 0 && phoneNumber) {
      var newRow = [];
      var maxCols = Math.max(headers.length, recordCol);
      for (var col = 1; col <= maxCols; col++) {
        if (col === 1 && String(headers[0]).toLowerCase() === 'no') {
          newRow.push(data.length);
        } else if (col === phoneCol) {
          newRow.push(phoneNumber);
        } else if (col === statusCol) {
          newRow.push(status);
        } else if (col === recordCol) {
          newRow.push(recordFormula);
        } else {
          newRow.push(contactName);
        }
      }
      sheet.appendRow(newRow);
    }

    return createJsonResponse({
      status: 'success',
      message: 'Rekaman berhasil diunggah ke Google Drive dan dicatat di spreadsheet!',
      fileUrl: fileUrl,
      fileName: driveFile.getName()
    });

  } catch (error) {
    return createJsonResponse({
      status: 'error',
      message: 'Gagal mengunggah rekaman ke Drive: ' + error.toString()
    });
  }
}

/**
 * Utility untuk membuat Output JSON dengan MIME Type yang benar
 */
function createJsonResponse(data) {
  return ContentService.createTextOutput(JSON.stringify(data))
    .setMimeType(ContentService.MimeType.JSON);
}
