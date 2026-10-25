# Panduan Setup Google Apps Script untuk Login AutoCall

Panduan ini menjelaskan cara mengonfigurasi Google Spreadsheet dan Google Apps Script agar sistem login online di aplikasi **AutoCall** dapat langsung digunakan.

---

## 1. Struktur Spreadsheet
Sesuai spreadsheet Google Sheets Anda (**Daftar Kontak**):
- **Tab 1: `Kontak`**  
  Menyimpan daftar nomor telepon, nama calon penerima telepon, dan status panggilan.
- **Tab 2: `Users`**  
  Menyimpan akun pengguna login aplikasi AutoCall:
  | Username | Password | Name | Role | Status |
  | :--- | :--- | :--- | :--- | :--- |
  | admin | admin123 | administrator | admin | active |
  | operator1 | 123456 | opt1 CC | operator | active |

---

## 2. Memasang Kode Script
1. Di Google Spreadsheet Anda, klik menu **Extensions** > **Apps Script** (atau **Ekstensi** > **Apps Script**).
2. Hapus isi file `Code.gs` yang lama.
3. Buka file [`google_apps_script/Code.gs`](./Code.gs) di repository ini, salin seluruh kodenya, lalu tempel (paste) ke editor Apps Script.
4. Klik tombol **Save** (ikon disket / Ctrl+S).

---

## 3. Melakukan Deployment (Penting!)
Agar aplikasi Android dapat mengakses script:
1. Klik tombol **Deploy** di pojok kanan atas > pilih **New deployment** (atau **Manage deployments** jika memperbarui versi).
2. Klik ikon gerigi (Settings) di sebelah kiri > pilih **Web app**.
3. Isi konfigurasi berikut:
   - **Description**: `AutoCall Login & Leads API v1`
   - **Execute as**: `Me (email google Anda)`
   - **Who has access**: **`Anyone`** *(Sangat penting: pilih Anyone agar aplikasi tidak tertahan izin OAuth)*
4. Klik **Deploy**.
5. Jika muncul permintaan izin (*Authorization required*):
   - Klik **Authorize access**.
   - Pilih akun Google Anda.
   - Klik **Advanced** (Lanjutan) > Klik **Go to AutoCall (unsafe)**.
   - Klik **Allow**.
6. Salin **Web app URL** yang diberikan (berakhiran `/exec`), contoh:
   ```
   https://script.google.com/macros/s/AKfycbw-xxxxxxxxxx/exec
   ```

---

## 4. Menghubungkan ke Aplikasi AutoCall
1. Buka aplikasi **AutoCall**.
2. Di layar Login, Anda dapat membuka menu **Pengaturan Server API** di bawah tombol login.
3. Masukkan Web App URL yang baru saja disalin.
4. Masukkan Username dan Password (misal: `admin` / `admin123`).
5. Klik **Masuk ke AutoCall**.

Setelah berhasil login, aplikasi akan membuka menu utama antrean panggilan secara normal.
