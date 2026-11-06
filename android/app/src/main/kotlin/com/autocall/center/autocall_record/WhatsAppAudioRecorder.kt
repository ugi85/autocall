package com.autocall.center.autocall_record

import android.content.Context
import android.media.AudioManager
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
 * this class captures the audio using MediaRecorder configured for two-way speech capture
 * (using loudspeaker routing and AudioSource.VOICE_RECOGNITION / MIC)
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

    val isRecording: Boolean
        get() = Companion.isRecording

    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    @Synchronized
    fun startRecording(phoneNumber: String, callCenterName: String = ""): String? {
        stopRecording()

        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cleanPhone = phoneNumber.replace(Regex("[^0-9+]"), "")
            val cleanCC = if (callCenterName.isNotBlank()) {
                callCenterName.replace(Regex("[^a-zA-Z0-9_-]"), "_") + "_"
            } else {
                ""
            }
            val fileName = "WA_Call_${cleanCC}${cleanPhone}_$timeStamp.m4a"

            // Target directory: Primary shared Recordings/Call or app external storage fallback
            val targetDir = getOutputDirectory()
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val file = File(targetDir, fileName)
            currentOutputFile = file

            // Optimize audio routing and speakerphone so remote voice is captured clearly
            optimizeAudioRoutingForTwoWayCapture()

            val mediaRecorder = createConfiguredMediaRecorder(file)
            recorder = mediaRecorder
            Companion.isRecording = true
            Companion.activeRecordingFilePath = file.absolutePath
            Log.i(TAG, "WhatsApp two-way audio recording started: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WhatsApp call audio recording: ${e.localizedMessage}", e)
            cleanUpRecorder()
            null
        }
    }

    /**
     * Tries best audio sources for two-way call capture:
     * 1. VOICE_RECOGNITION - Speech capture without aggressive echo suppression cutting remote voice
     * 2. MIC - Universal standard microphone
     * 3. DEFAULT - System default fallback
     */
    private fun createConfiguredMediaRecorder(file: File): MediaRecorder {
        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )

        var lastException: Exception? = null

        for (source in sourcesToTry) {
            try {
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                mr.apply {
                    setAudioSource(source)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioChannels(1) // Mono for universal hardware compatibility
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(128000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }

                Log.i(TAG, "MediaRecorder initialized successfully with AudioSource: $source")
                return mr
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder AudioSource $source failed: ${e.localizedMessage}. Trying fallback...")
                lastException = e
            }
        }

        throw lastException ?: IllegalStateException("Unable to initialize MediaRecorder with any AudioSource")
    }

    /**
     * Activates speakerphone and optimizes voice call stream volume
     * so that the other party's voice is played clearly through the loudspeaker
     * and captured by the microphone together with local speech.
     */
    fun optimizeAudioRoutingForTwoWayCapture() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            // Activate loudspeaker
            audioManager.isSpeakerphoneOn = true

            // Set voice call volume to high level (approx 90%)
            val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val curCallVol = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val targetCallVol = (maxCallVol * 0.90).toInt().coerceAtLeast(1)
            if (curCallVol < targetCallVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetCallVol, 0)
            }

            // Also ensure media stream is audible
            val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val targetMusicVol = (maxMusicVol * 0.85).toInt().coerceAtLeast(1)
            if (curMusicVol < targetMusicVol) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusicVol, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error optimizing audio routing for recording: ${e.localizedMessage}")
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
        Companion.isRecording = false
        Companion.activeRecordingFilePath = null
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
