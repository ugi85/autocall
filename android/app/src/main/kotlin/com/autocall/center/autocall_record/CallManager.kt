package com.autocall.center.autocall_record

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * CallManager
 *
 * Coordinates:
 * - Telephony permissions and intents (Intent.ACTION_CALL).
 * - Call state monitoring (TelephonyCallback on Android 12+ / PhoneStateListener on Android 10-11).
 * - ContentResolver CallLog.Calls queries on call termination.
 * - WorkManager enqueueing for UploadRecordingWorker.
 * - Event streaming to Flutter via EventChannel.EventSink.
 */
class CallManager(
    private val appContext: Context
) {

    companion object {
        const val TAG = "CallManager"
        const val PERMISSION_REQUEST_CODE = 1001

        var instance: CallManager? = null
            private set

        val REQUIRED_RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private var activity: Activity? = null
    private var eventSink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val telephonyManager: TelephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    // Active call tracking
    private var activeTargetNumber: String? = null
    private var lastRecordedState: Int = TelephonyManager.CALL_STATE_IDLE
    private var wasOffhook: Boolean = false
    private var callInitiatedTimestamp: Long = 0L
    private var lastEndCallTimestamp: Long = 0L

    var backendUploadUrl: String = UploadRecordingWorker.DEFAULT_API_URL

    // Android 12+ Callback
    private var telephonyCallback: TelephonyCallback? = null

    // Android 10-11 Listener
    private val legacyPhoneStateListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            handleCallStateChanged(state, phoneNumber)
        }
    }

    // Broadcast receiver for upload events dispatched by UploadRecordingWorker
    private val uploadBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UploadRecordingWorker.ACTION_UPLOAD_STATUS) {
                val status = intent.getStringExtra(UploadRecordingWorker.EXTRA_STATUS) ?: "UNKNOWN"
                val phone = intent.getStringExtra(UploadRecordingWorker.EXTRA_PHONE_NUMBER) ?: ""
                val msg = intent.getStringExtra(UploadRecordingWorker.EXTRA_MESSAGE) ?: ""
                val path = intent.getStringExtra(UploadRecordingWorker.EXTRA_FILE_PATH) ?: ""

                emitEvent(
                    mapOf(
                        "event" to "RECORDING_UPLOAD_STATUS",
                        "status" to status,
                        "phoneNumber" to phone,
                        "message" to msg,
                        "filePath" to path
                    )
                )
            }
        }
    }

    init {
        instance = this
        registerUploadBroadcastReceiver()
    }

    fun attachActivity(act: Activity) {
        this.activity = act
    }

    fun detachActivity() {
        this.activity = null
    }

    fun setEventSink(sink: EventChannel.EventSink?) {
        this.eventSink = sink
    }

    /**
     * Start listening to telephony state changes.
     */
    fun startListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerTelephonyCallbackApi31()
            } else {
                telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            Log.i(TAG, "Telephony state listener registered successfully.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException registering call state listener. Permission missing.", e)
        }
    }

    /**
     * Stop listening to telephony state changes.
     */
    fun stopListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                    telephonyCallback = null
                }
            } else {
                telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering listener: ${e.localizedMessage}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerTelephonyCallbackApi31() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChanged(state, null)
            }
        }
        telephonyCallback = callback
        telephonyManager.registerTelephonyCallback(appContext.mainExecutor, callback)
    }

    /**
     * Core state machine for handling call state transitions.
     */
    private fun handleCallStateChanged(state: Int, incomingNumber: String?) {
        val stateName = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "RINGING"
            TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
            TelephonyManager.CALL_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN"
        }

        Log.i(TAG, "Call State Transition: $lastRecordedState -> $state ($stateName)")

        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                wasOffhook = true
                emitEvent(
                    mapOf(
                        "event" to "CALL_STATE_CHANGED",
                        "state" to "OFFHOOK",
                        "phoneNumber" to (activeTargetNumber ?: incomingNumber ?: "")
                    )
                )
            }

            TelephonyManager.CALL_STATE_RINGING -> {
                emitEvent(
                    mapOf(
                        "event" to "CALL_STATE_CHANGED",
                        "state" to "RINGING",
                        "phoneNumber" to (incomingNumber ?: activeTargetNumber ?: "")
                    )
                )
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                emitEvent(
                    mapOf(
                        "event" to "CALL_STATE_CHANGED",
                        "state" to "IDLE",
                        "phoneNumber" to (activeTargetNumber ?: "")
                    )
                )

                // Stop native foreground service & floating overlay
                AutoCallForegroundService.stop(appContext)

                // If phone was in a call (OFFHOOK) and now hung up (IDLE)
                if (wasOffhook || activeTargetNumber != null) {
                    val targetNum = activeTargetNumber
                    wasOffhook = false

                    // Schedule retrieval of CallLog and start worker in IO scope
                    scope.launch {
                        // Allow Android system dialer 1.2s to commit call log to ContentResolver
                        delay(1200)
                        processCallCompleted(targetNum)
                    }
                }
            }
        }

        lastRecordedState = state
    }

    /**
     * Initiates an outgoing call using standard Intent.ACTION_CALL,
     * and starts the native AutoCallForegroundService to manage the countdown
     * and floating overlay without being frozen by Vivo Funtouch OS.
     */
    fun startCall(phoneNumber: String, name: String = "", timeoutSeconds: Int = 25): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start call: CALL_PHONE permission not granted.")
            return false
        }

        val cleanNumber = phoneNumber.trim()
        activeTargetNumber = cleanNumber
        callInitiatedTimestamp = System.currentTimeMillis()
        wasOffhook = false

        Log.i(TAG, "Initiating call to $cleanNumber via Intent.ACTION_CALL with timeout $timeoutSeconds s")

        return try {
            // Start native Foreground Service with WakeLock & Floating Overlay
            AutoCallForegroundService.start(appContext, cleanNumber, name, timeoutSeconds)

            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(cleanNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(callIntent)

            emitEvent(
                mapOf(
                    "event" to "CALL_STARTED",
                    "phoneNumber" to cleanNumber,
                    "timestamp" to callInitiatedTimestamp
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ACTION_CALL intent: ${e.localizedMessage}", e)
            AutoCallForegroundService.stop(appContext)
            false
        }
    }

    /**
     * Programmatically terminates the active or ringing call.
     * Triggered automatically by the native autocall timer when call timeout expires
     * or when user clicks 'Tutup' on the floating overlay.
     */
    fun endCall(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEndCallTimestamp < 2000L) {
            Log.i(TAG, "endCall called within 2s debouncing window. Skipping duplicate trigger.")
            return true
        }
        lastEndCallTimestamp = now

        Log.i(TAG, "Executing endCall to terminate call on timeout...")
        var ended = false

        // Note: Do NOT stop AutoCallForegroundService here.
        // Keeping the foreground service and CPU WakeLock active ensures that Vivo's Funtouch OS
        // does not freeze the app process while the accessibility service or dialer is executing hangup.
        // It will be stopped automatically once TelephonyManager fires CALL_STATE_IDLE.

        // 1. Accessibility Service Automation (Primary & Recommended for OEM dialers like Vivo/Samsung/Xiaomi)
        try {
            if (AutoCallAccessibilityService.isServiceConnected) {
                ended = AutoCallAccessibilityService.endCall()
                Log.i(TAG, "AutoCallAccessibilityService.endCall() result: $ended")
            } else {
                val isEnabledInSettings = isAccessibilityEnabled()
                Log.w(TAG, "AutoCallAccessibilityService not connected yet. (isAccessibilityEnabled in settings: $isEnabledInSettings)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AutoCallAccessibilityService error: ${e.localizedMessage}")
        }

        // 2. Android 9.0+ (API 28+) TelecomManager.endCall()
        if (!ended && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null && ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.ANSWER_PHONE_CALLS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    ended = telecomManager.endCall()
                    Log.i(TAG, "TelecomManager.endCall() returned: $ended")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager.endCall() error: ${e.localizedMessage}")
            }
        }

        // 3. Reflection fallback via ITelephony
        if (!ended) {
            try {
                val telephony = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val telephonyClass = Class.forName(telephony?.javaClass?.name ?: "")
                val methodGetITelephony = telephonyClass.getDeclaredMethod("getITelephony")
                methodGetITelephony.isAccessible = true
                val iTelephony = methodGetITelephony.invoke(telephony)
                val iTelephonyClass = Class.forName(iTelephony.javaClass.name)
                val methodEndCall = iTelephonyClass.getDeclaredMethod("endCall")
                methodEndCall.invoke(iTelephony)
                ended = true
                Log.i(TAG, "Call terminated via ITelephony reflection fallback")
            } catch (e: Exception) {
                Log.e(TAG, "ITelephony reflection endCall failed: ${e.localizedMessage}")
            }
        }

        return ended
    }

    /**
     * Pauses the countdown timer (e.g. if the agent is actively talking).
     */
    fun pauseCallTimeout() {
        AutoCallForegroundService.pauseTimeout(appContext)
    }

    /**
     * Called when the call is answered by the remote party (live conversation started).
     * Automatically halts the auto-hangup timeout so active conversations are never interrupted.
     */
    fun notifyCallAnswered() {
        Log.i(TAG, "Remote party answered call! Pausing auto-hangup timer so conversation can proceed.")
        AutoCallForegroundService.pauseTimeout(appContext)

        emitEvent(
            mapOf(
                "event" to "CALL_ANSWERED",
                "phoneNumber" to (activeTargetNumber ?: ""),
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Checks if AutoCallAccessibilityService is currently enabled in settings.
     */
    fun isAccessibilityEnabled(): Boolean {
        return AutoCallAccessibilityService.isAccessibilityEnabled(appContext)
    }

    /**
     * Opens Android System Accessibility Settings screen for user to toggle the service.
     */
    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings: ${e.localizedMessage}", e)
        }
    }

    /**
     * Checks if Overlay permission (SYSTEM_ALERT_WINDOW) is granted.
     */
    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    /**
     * Opens Settings screen to grant 'Display over other apps' permission.
     */
    fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${appContext.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay settings: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Queries ContentResolver CallLog.Calls for latest call metrics,
     * immediately scans for internal recording file, emits completion event to Flutter,
     * and schedules UploadRecordingWorker.
     */
    private fun processCallCompleted(targetNumber: String?) {
        val callRecord = queryLatestCallLog(targetNumber)
        val finalNumber = callRecord?.number ?: targetNumber ?: "UNKNOWN"
        val duration = callRecord?.duration ?: 0L
        val timestamp = callRecord?.date ?: callInitiatedTimestamp
        val callType = callRecord?.typeString ?: "OUTGOING"

        val status = if (duration > 0L) {
            "COMPLETED"
        } else {
            "FAILED_OR_BUSY"
        }

        Log.i(
            TAG,
            "Call finished. Number: $finalNumber, Duration: ${duration}s, Status: $status, Type: $callType"
        )

        // Immediate check for local recording file on internal storage (Vivo, Samsung, etc.)
        var localRecordingPath = ""
        val localFile = RecordingSearchHelper.findMatchingRecordingFile(finalNumber, timestamp, duration)
        if (localFile != null && localFile.exists()) {
            localRecordingPath = localFile.absolutePath
            Log.i(TAG, "Found local recording file immediately: $localRecordingPath")
        }

        emitEvent(
            mapOf(
                "event" to "CALL_COMPLETED",
                "phoneNumber" to finalNumber,
                "duration" to duration,
                "timestamp" to timestamp,
                "callType" to callType,
                "status" to status,
                "recordingPath" to localRecordingPath
            )
        )

        // Reset active target
        activeTargetNumber = null

        // If the call connected and had a duration, enqueue upload worker
        if (duration > 0L) {
            enqueueUploadWorker(finalNumber, timestamp, duration, callType)
        } else {
            Log.i(TAG, "Call duration was 0 (unanswered or busy). Skipping recording upload.")
        }
    }

    /**
     * Returns all recordings found across phone storage.
     */
    fun getAllRecordings(): List<Map<String, Any>> {
        return RecordingSearchHelper.scanAllRecordings()
    }

    /**
     * Enqueues UploadRecordingWorker in WorkManager.
     */
    private fun enqueueUploadWorker(
        phoneNumber: String,
        timestamp: Long,
        duration: Long,
        callType: String
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            UploadRecordingWorker.KEY_PHONE_NUMBER to phoneNumber,
            UploadRecordingWorker.KEY_CALL_TIMESTAMP to timestamp,
            UploadRecordingWorker.KEY_CALL_DURATION to duration,
            UploadRecordingWorker.KEY_CALL_TYPE to callType,
            UploadRecordingWorker.KEY_API_URL to backendUploadUrl
        )

        val uploadRequest = OneTimeWorkRequestBuilder<UploadRecordingWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag("recording_upload")
            .build()

        WorkManager.getInstance(appContext).enqueue(uploadRequest)
        Log.i(TAG, "UploadRecordingWorker enqueued successfully for $phoneNumber")
    }

    /**
     * ContentResolver query to android.provider.CallLog.Calls
     */
    private fun queryLatestCallLog(expectedNumber: String?): CallRecordData? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot read CallLog: READ_CALL_LOG permission denied.")
            return null
        }

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )

        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val cursor = appContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)

                val num = if (numberIdx != -1) it.getString(numberIdx) else ""
                val date = if (dateIdx != -1) it.getLong(dateIdx) else 0L
                val duration = if (durationIdx != -1) it.getLong(durationIdx) else 0L
                val typeInt = if (typeIdx != -1) it.getInt(typeIdx) else CallLog.Calls.OUTGOING_TYPE

                val typeString = when (typeInt) {
                    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
                    CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                    CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
                    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
                    else -> "UNKNOWN"
                }

                return CallRecordData(num, date, duration, typeString)
            }
        }

        return null
    }

    /**
     * Checks all runtime and special permissions status.
     */
    fun checkPermissions(): Map<String, Boolean> {
        val hasCallPhone = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasReadCallLog = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        } else {
            true
        }

        return mapOf(
            "CALL_PHONE" to hasCallPhone,
            "READ_PHONE_STATE" to hasReadPhoneState,
            "READ_CALL_LOG" to hasReadCallLog,
            "MANAGE_EXTERNAL_STORAGE" to hasAllFilesAccess,
            "IGNORE_BATTERY_OPTIMIZATIONS" to isIgnoringBatteryOptimizations,
            "ALL_GRANTED" to (hasCallPhone && hasReadPhoneState && hasReadCallLog && hasAllFilesAccess)
        )
    }

    /**
     * Request standard runtime permissions via Activity.
     */
    fun requestRuntimePermissions() {
        val act = activity ?: return
        ActivityCompat.requestPermissions(act, REQUIRED_RUNTIME_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    /**
     * Opens system setting for All Files Access (MANAGE_EXTERNAL_STORAGE).
     */
    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Request to ignore battery optimizations for dedicated inventory reliability.
     */
    fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot launch ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: ${e.localizedMessage}")
            }
        }
    }

    private fun registerUploadBroadcastReceiver() {
        val filter = IntentFilter(UploadRecordingWorker.ACTION_UPLOAD_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(uploadBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(uploadBroadcastReceiver, filter)
        }
    }

    fun cleanUp() {
        stopListening()
        try {
            appContext.unregisterReceiver(uploadBroadcastReceiver)
        } catch (_: Exception) {}
    }

    private fun emitEvent(eventMap: Map<String, Any>) {
        mainHandler.post {
            try {
                eventSink?.success(eventMap)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send event to Flutter EventSink: ${e.localizedMessage}")
            }
        }
    }

    data class CallRecordData(
        val number: String,
        val date: Long,
        val duration: Long,
        val typeString: String
    )
}
