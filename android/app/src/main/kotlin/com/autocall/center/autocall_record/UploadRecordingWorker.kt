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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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
        val callTimestamp = inputData.getLong(KEY_CALL_TIMESTAMP, System.currentTimeMillis())
        val callDuration = inputData.getLong(KEY_CALL_DURATION, 0L)
        val callType = inputData.getString(KEY_CALL_TYPE) ?: "OUTGOING"
        val apiUrl = inputData.getString(KEY_API_URL) ?: DEFAULT_API_URL

        Log.i(TAG, "Starting recording sync for: $phoneNumber (Timestamp: $callTimestamp, Duration: ${callDuration}s)")
        notifyStatus("UPLOADING", phoneNumber, "Mencari file rekaman panggilan...", null)

        // 1. Locate the recording file. OEM recorders can take a few seconds to flush to disk.
        var targetFile: File? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            targetFile = findMatchingRecordingFile(phoneNumber, callTimestamp, callDuration)
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

        // 2. Perform OkHttp Multipart Upload
        return try {
            val mediaType = when (targetFile.extension.lowercase()) {
                "mp3" -> "audio/mpeg".toMediaTypeOrNull()
                "m4a" -> "audio/mp4".toMediaTypeOrNull()
                "amr" -> "audio/amr".toMediaTypeOrNull()
                "aac" -> "audio/aac".toMediaTypeOrNull()
                else -> "audio/*".toMediaTypeOrNull()
            }

            val requestBody = MultipartBody.Builder()
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

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "AutoCallCenter-Android/${Build.MODEL}")
                .post(requestBody)
                .build()

            Log.i(TAG, "Executing upload request to $apiUrl for file ${targetFile.name} (${targetFile.length()} bytes)")
            val response = httpClient.newCall(request).execute()

            response.use { res ->
                val responseBody = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    Log.i(TAG, "Upload successful: Code ${res.code}, Body: $responseBody")
                    notifyStatus("SUCCESS", phoneNumber, "Rekaman berhasil diunggah", targetFile.absolutePath)
                    Result.success(
                        workDataOf(
                            "uploaded_file" to targetFile.absolutePath,
                            "response_code" to res.code
                        )
                    )
                } else if (res.code in 500..599) {
                    val errMsg = "Server backend error (HTTP ${res.code}). Worker akan retry."
                    Log.e(TAG, errMsg)
                    notifyStatus("FAILED", phoneNumber, errMsg, targetFile.absolutePath)
                    Result.retry()
                } else {
                    val errMsg = "Upload ditolak backend (HTTP ${res.code}): $responseBody"
                    Log.e(TAG, errMsg)
                    notifyStatus("FAILED", phoneNumber, errMsg, targetFile.absolutePath)
                    Result.failure(workDataOf("error" to errMsg, "response_code" to res.code))
                }
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
        callTimestamp: Long,
        callDuration: Long
    ): File? {
        return RecordingSearchHelper.findMatchingRecordingFile(phoneNumber, callTimestamp, callDuration)
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
