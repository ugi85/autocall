package com.autocall.center.autocall_record

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * UploadRecordingWorker
 *
 * Background worker responsible for:
 * 1. Scanning OEM call recording directories (Samsung, Xiaomi/MIUI, standard Android).
 * 2. Correlating recording audio files based on phone number matching and call timestamp proximity.
 * 3. Uploading the audio file with metadata via OkHttp Multipart POST.
 * 4. Handling retry mechanisms for network failures.
 */
class UploadRecordingWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "UploadRecordingWorker"

        const val KEY_PHONE_NUMBER = "KEY_PHONE_NUMBER"
        const val KEY_CONTACT_NAME = "KEY_CONTACT_NAME"
        const val KEY_CALL_TIMESTAMP = "KEY_CALL_TIMESTAMP"
        const val KEY_CALL_DURATION = "KEY_CALL_DURATION"
        const val KEY_CALL_TYPE = "KEY_CALL_TYPE"
        const val KEY_API_URL = "KEY_API_URL"

        const val DEFAULT_API_URL = "https://your-api-domain.com/api/v1/recordings/upload"

        // Broadcast actions for real-time Flutter updates
        const val ACTION_UPLOAD_STATUS = "com.autocall.center.ACTION_UPLOAD_STATUS"
        const val EXTRA_STATUS = "EXTRA_STATUS" // "UPLOADING", "SUCCESS", "FAILED"
        const val EXTRA_PHONE_NUMBER = "EXTRA_PHONE_NUMBER"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"

        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "aac", "wav", "ogg", "3gp")
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun doWork(): Result {
        val phoneNumber = inputData.getString(KEY_PHONE_NUMBER) ?: "UNKNOWN"
        val contactName = inputData.getString(KEY_CONTACT_NAME) ?: ""
        val callTimestamp = inputData.getLong(KEY_CALL_TIMESTAMP, System.currentTimeMillis())
        val callDuration = inputData.getLong(KEY_CALL_DURATION, 0L)
        val callType = inputData.getString(KEY_CALL_TYPE) ?: "OUTGOING"
        val apiUrl = inputData.getString(KEY_API_URL) ?: DEFAULT_API_URL

        Log.i(TAG, "Starting recording sync for: $contactName ($phoneNumber) (Timestamp: $callTimestamp, Duration: ${callDuration}s)")
        notifyStatus("UPLOADING", phoneNumber, "Mencari file rekaman panggilan...", null)

        // 1. Locate the recording file. OEM recorders can take a few seconds to flush to disk.
        var targetFile: File? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            targetFile = findMatchingRecordingFile(phoneNumber, contactName, callTimestamp, callDuration)
            if (targetFile != null) {
                Log.i(TAG, "Found matching recording on attempt $attempt: ${targetFile.absolutePath}")
                break
            }
            if (attempt < maxAttempts) {
                Log.d(TAG, "Recording not found yet, retrying in 2000ms (attempt $attempt/$maxAttempts)...")
                delay(2000)
            }
        }

        if (targetFile == null || !targetFile.exists() || targetFile.length() == 0L) {
            val errMsg = "File rekaman tidak ditemukan di direktori OEM (Samsung/Xiaomi)"
            Log.w(TAG, "$errMsg untuk nomor $phoneNumber")
            notifyStatus("FAILED", phoneNumber, errMsg, null)
            return Result.failure(workDataOf("error" to errMsg))
        }

        notifyStatus("UPLOADING", phoneNumber, "Mengunggah rekaman (${targetFile.name})...", targetFile.absolutePath)

        // 2. Perform Upload (Google Apps Script JSON Base64 or Standard Multipart)
        return try {
            val isGoogleAppsScript = apiUrl.contains("script.google.com")

            val requestBody = if (isGoogleAppsScript) {
                val fileBytes = targetFile.readBytes()
                val base64Audio = Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                val jsonPayload = JSONObject().apply {
                    put("action", "upload_recording")
                    put("phone_number", phoneNumber)
                    put("name", contactName)
                    put("status", if (callDuration > 0) "TERHUBUNG (${callDuration}s)" else "SELESAI")
                    put("call_duration", callDuration)
                    put("call_timestamp", callTimestamp)
                    put("call_type", callType)
                    put("file_name", targetFile.name)
                    put("audio_base64", base64Audio)
                    put("device_manufacturer", Build.MANUFACTURER)
                    put("device_model", Build.MODEL)
                }.toString()

                jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            } else {
                val mediaType = when (targetFile.extension.lowercase()) {
                    "mp3" -> "audio/mpeg".toMediaTypeOrNull()
                    "m4a" -> "audio/mp4".toMediaTypeOrNull()
                    "amr" -> "audio/amr".toMediaTypeOrNull()
                    "aac" -> "audio/aac".toMediaTypeOrNull()
                    else -> "audio/*".toMediaTypeOrNull()
                }

                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        targetFile.name,
                        targetFile.asRequestBody(mediaType)
                    )
                    .addFormDataPart("phone_number", phoneNumber)
                    .addFormDataPart("call_duration", callDuration.toString())
                    .addFormDataPart("call_timestamp", callTimestamp.toString())
                    .addFormDataPart("call_type", callType)
                    .addFormDataPart("device_manufacturer", Build.MANUFACTURER)
                    .addFormDataPart("device_model", Build.MODEL)
                    .addFormDataPart("android_sdk", Build.VERSION.SDK_INT.toString())
                    .build()
            }

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "AutoCallCenter-Android/${Build.MODEL}")
                .header("Accept", "application/json")
                .post(requestBody)
                .build()

            Log.i(TAG, "Executing upload request to $apiUrl for file ${targetFile.name} (${targetFile.length()} bytes)")
            val response = httpClient.newCall(request).execute()

            var finalCode = response.code
            var responseBody = ""

            // Handle Google Apps Script 302 Redirect
            if (response.code == 302) {
                val redirectUrl = response.header("Location")
                if (!redirectUrl.isNullOrEmpty()) {
                    Log.d(TAG, "Following Google Apps Script redirect to $redirectUrl")
                    val redirectRequest = Request.Builder()
                        .url(redirectUrl)
                        .header("Accept", "application/json")
                        .get()
                        .build()
                    val redirectResponse = httpClient.newCall(redirectRequest).execute()
                    finalCode = redirectResponse.code
                    responseBody = redirectResponse.body?.string() ?: ""
                    redirectResponse.close()
                } else {
                    finalCode = 200
                    responseBody = response.body?.string() ?: ""
                }
            } else {
                responseBody = response.body?.string() ?: ""
            }
            response.close()

            var isBackendSuccess = false
            var responseErrorMessage = ""

            if (finalCode in 200..299 || (isGoogleAppsScript && finalCode == 302)) {
                // If Google Apps Script returned a JSON error (e.g. DriveApp authorization missing)
                if (responseBody.contains("\"status\":\"error\"") || responseBody.contains("\"error\"")) {
                    try {
                        val json = JSONObject(responseBody)
                        responseErrorMessage = json.optString("message", json.optString("error", "Error server"))
                    } catch (e: Exception) {
                        responseErrorMessage = "Respon error: $responseBody"
                    }
                    isBackendSuccess = false
                } else {
                    isBackendSuccess = true
                }
            }

            if (isBackendSuccess) {
                Log.i(TAG, "Upload successful: Code $finalCode, Body: $responseBody")
                val successMsg = if (isGoogleAppsScript) "Rekaman & Status tersimpan ke Spreadsheet" else "Rekaman berhasil diunggah"
                notifyStatus("SUCCESS", phoneNumber, successMsg, targetFile.absolutePath)
                Result.success(
                    workDataOf(
                        "uploaded_file" to targetFile.absolutePath,
                        "response_code" to finalCode
                    )
                )
            } else if (finalCode in 500..599) {
                val errMsg = "Server backend error (HTTP $finalCode). Worker akan retry."
                Log.e(TAG, errMsg)
                notifyStatus("FAILED", phoneNumber, errMsg, targetFile.absolutePath)
                Result.retry()
            } else {
                val errMsg = if (responseErrorMessage.isNotBlank()) responseErrorMessage else "Upload ditolak backend (HTTP $finalCode): $responseBody"
                Log.e(TAG, errMsg)
                notifyStatus("FAILED", phoneNumber, errMsg, targetFile.absolutePath)
                Result.failure(workDataOf("error" to errMsg, "response_code" to finalCode))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during upload: ${e.localizedMessage}. Scheduling retry.", e)
            notifyStatus("FAILED", phoneNumber, "Koneksi terputus: ${e.localizedMessage}", targetFile.absolutePath)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during upload: ${e.localizedMessage}", e)
            notifyStatus("FAILED", phoneNumber, "Error: ${e.localizedMessage}", targetFile.absolutePath)
            Result.failure(workDataOf("error" to (e.localizedMessage ?: "Unknown error")))
        }
    }

    /**
     * Scans known OEM directories and matches audio files.
     */
    private fun findMatchingRecordingFile(
        phoneNumber: String,
        contactName: String,
        callTimestamp: Long,
        callDuration: Long
    ): File? {
        return RecordingSearchHelper.findMatchingRecordingFile(phoneNumber, contactName, callTimestamp, callDuration)
    }

    private fun notifyStatus(status: String, phoneNumber: String, message: String, filePath: String?) {
        try {
            val intent = Intent(ACTION_UPLOAD_STATUS).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_FILE_PATH, filePath ?: "")
            }
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send upload status broadcast: ${e.localizedMessage}")
        }
    }
}
