package com.autocall.center.autocall_record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WhatsAppAudioRecorder
 *
 * Dedicated in-app audio recorder for WhatsApp VoIP calls.
 * Because OEM dialer recorders (Vivo/Samsung/Xiaomi) only record standard GSM calls,
 * this class captures the audio using MediaRecorder from AudioSource.MIC / VOICE_COMMUNICATION
 * and saves standard .m4a audio files for upload to Google Drive & Spreadsheet.
 */
class WhatsAppAudioRecorder(private val context: Context) {

    companion object {
        const val TAG = "WhatsAppAudioRecorder"
        var isRecording: Boolean = false
            private set
        var activeRecordingFilePath: String? = null
            private set
    }

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    @Synchronized
    fun startRecording(phoneNumber: String): String? {
        stopRecording()

        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
            val fileName = "WA_Call_${cleanPhone}_$timeStamp.m4a"

            // Target directory: Primary shared Recordings/Call or app external storage fallback
            val targetDir = getOutputDirectory()
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val file = File(targetDir, fileName)
            currentOutputFile = file

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            recorder = mediaRecorder
            isRecording = true
            activeRecordingFilePath = file.absolutePath
            Log.i(TAG, "WhatsApp call audio recording started: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WhatsApp call audio recording: ${e.localizedMessage}", e)
            cleanUpRecorder()
            null
        }
    }

    @Synchronized
    fun stopRecording(): String? {
        val path = activeRecordingFilePath
        val file = currentOutputFile

        if (recorder != null) {
            try {
                recorder?.apply {
                    stop()
                    reset()
                    release()
                }
                Log.i(TAG, "WhatsApp call audio recording stopped successfully: $path")
            } catch (e: Exception) {
                Log.w(TAG, "Exception while stopping MediaRecorder: ${e.localizedMessage}")
            } finally {
                cleanUpRecorder()
            }
        }

        // Return path only if file exists and has content (> 1KB)
        return if (file != null && file.exists() && file.length() > 1024) {
            path
        } else {
            path
        }
    }

    private fun cleanUpRecorder() {
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        activeRecordingFilePath = null
        currentOutputFile = null
    }

    private fun getOutputDirectory(): File {
        // Try public Music/Recordings/Call first
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val waDir = File(musicDir, "Recordings/Call")
        if (waDir.exists() || waDir.mkdirs()) {
            return waDir
        }

        // Fallback 1: Root /Recordings/Call
        val rootRecordings = File(Environment.getExternalStorageDirectory(), "Recordings/Call")
        if (rootRecordings.exists() || rootRecordings.mkdirs()) {
            return rootRecordings
        }

        // Fallback 2: App-specific external files dir (always accessible without permission restrictions)
        val appExtDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        if (appExtDir != null && (appExtDir.exists() || appExtDir.mkdirs())) {
            return appExtDir
        }

        return context.filesDir
    }
}

