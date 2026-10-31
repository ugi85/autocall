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
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
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
    private var activeTargetName: String? = null
    private var lastRecordedState: Int = TelephonyManager.CALL_STATE_IDLE
    private var wasOffhook: Boolean = false
    var callInitiatedTimestamp: Long = 0L
    private var lastEndCallTimestamp: Long = 0L
    var callAnsweredTimestamp: Long = 0L
    var wasCallAnswered: Boolean = false

    var backendUploadUrl: String = UploadRecordingWorker.DEFAULT_API_URL

    // Android 12+ Callback (Stored as Any? to prevent NoClassDefFoundError on Android < 12)
    private var telephonyCallback: Any? = null

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
                telephonyCallback = Api31TelephonyHelper.register(
                    telephonyManager,
                    appContext
                ) { state ->
                    handleCallStateChanged(state, null)
                }
            } else {
                telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            Log.i(TAG, "Telephony state listener registered successfully.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException registering call state listener. Permission missing.", e)
        } catch (e: Throwable) {
            Log.e(TAG, "General error registering call state listener: ${e.localizedMessage}", e)
        }
    }

    /**
     * Stop listening to telephony state changes.
     */
    fun stopListening() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    Api31TelephonyHelper.unregister(telephonyManager, it)
                    telephonyCallback = null
                }
            } else {
                telephonyManager.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error unregistering listener: ${e.localizedMessage}")
        }
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
                    activeTargetNumber = null

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
     * Initiates an outgoing call using either standard Intent.ACTION_CALL (GSM)
     * or WhatsApp VoIP call, and starts the native AutoCallForegroundService
     * to manage the countdown, in-app recorder, and floating overlay.
     */
    fun startCall(
        phoneNumber: String,
        name: String = "",
        timeoutSeconds: Int = 25,
        callMode: String = "GSM"
    ): Boolean {
        if (callMode.equals("WHATSAPP", ignoreCase = true)) {
            return startWhatsAppCall(phoneNumber, name, timeoutSeconds)
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start call: CALL_PHONE permission not granted.")
            return false
        }

        val cleanNumber = phoneNumber.trim()
        activeTargetNumber = cleanNumber
        activeTargetName = name.trim()
        callInitiatedTimestamp = System.currentTimeMillis()
        callAnsweredTimestamp = 0L
        wasCallAnswered = false
        wasOffhook = false

        Log.i(TAG, "Initiating call to $cleanNumber via Intent.ACTION_CALL with timeout $timeoutSeconds s")

        return try {
            // Start native Foreground Service with WakeLock & Floating Overlay
            AutoCallForegroundService.start(
                appContext,
                cleanNumber,
                name,
                timeoutSeconds,
                AutoCallForegroundService.CALL_MODE_GSM
            )

            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(cleanNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appContext.startActivity(callIntent)

            emitEvent(
                mapOf(
                    "event" to "CALL_STARTED",
                    "phoneNumber" to cleanNumber,
                    "callMode" to "GSM",
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
     * Initiates an outgoing WhatsApp VoIP call by launching WhatsApp chat
     * and signaling AutoCallAccessibilityService to trigger the voice call button.
     * Records audio concurrently using WhatsAppAudioRecorder.
     */
    fun startWhatsAppCall(phoneNumber: String, name: String = "", timeoutSeconds: Int = 25): Boolean {
        // Pre-dial cleanup: Ensure any previous lingering call is terminated before launching new call!
        try {
            if (AutoCallAccessibilityService.isServiceConnected) {
                AutoCallAccessibilityService.dismissAnyLingeringCall()
            }
        } catch (_: Exception) {}

        val cleanNumber = normalizeWhatsAppNumber(phoneNumber)
        activeTargetNumber = cleanNumber
        activeTargetName = name.trim()
        callInitiatedTimestamp = System.currentTimeMillis()
        callAnsweredTimestamp = 0L
        wasCallAnswered = false
        wasOffhook = false

        Log.i(TAG, "Initiating WhatsApp call to $cleanNumber with timeout $timeoutSeconds s")

        return try {
            // 1. Start native Foreground Service with WakeLock, Overlay, and WhatsApp In-App Recorder
            AutoCallForegroundService.start(
                appContext,
                cleanNumber,
                name,
                timeoutSeconds,
                callMode = AutoCallForegroundService.CALL_MODE_WHATSAPP
            )

            // 2. Try direct WhatsApp VoIP Call if contact exists in phone contacts
            val voipDataId = findWhatsAppVoipDataId(cleanNumber)
            if (voipDataId != null) {
                Log.i(TAG, "Initiating DIRECT WhatsApp VoIP call via ContactsContract Data ID: $voipDataId")
                AutoCallAccessibilityService.isWhatsAppPendingCall = false
                val dataUri = Uri.parse("content://com.android.contacts/data/$voipDataId")
                val waCallIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(dataUri, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
                    `package` = "com.whatsapp"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                appContext.startActivity(waCallIntent)
            } else {
                // Fallback for unsaved contacts: Open chat and have Accessibility click voice call button
                Log.i(TAG, "VoIP data ID not found. Opening WhatsApp chat with accessibility automation fallback.")
                AutoCallAccessibilityService.isWhatsAppPendingCall = true
                val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
                val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (isPackageInstalled("com.whatsapp")) {
                        `package` = "com.whatsapp"
                    } else if (isPackageInstalled("com.whatsapp.w4b")) {
                        `package` = "com.whatsapp.w4b"
                    }
                }
                appContext.startActivity(waIntent)
            }

            emitEvent(
                mapOf(
                    "event" to "CALL_STARTED",
                    "phoneNumber" to cleanNumber,
                    "callMode" to "WHATSAPP",
                    "timestamp" to callInitiatedTimestamp
                )
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp call: ${e.localizedMessage}", e)
            AutoCallAccessibilityService.isWhatsAppPendingCall = false
            AutoCallForegroundService.stop(appContext)
            false
        }
    }

    private fun findWhatsAppVoipDataId(cleanPhone: String): Long? {
        return try {
            if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "READ_CONTACTS permission not granted. Cannot query WhatsApp VoIP data.")
                return null
            }

            val resolver = appContext.contentResolver
            val projection = arrayOf(
                android.provider.ContactsContract.Data._ID,
                android.provider.ContactsContract.Data.DATA1,
                android.provider.ContactsContract.Data.MIMETYPE
            )
            val selection = "${android.provider.ContactsContract.Data.MIMETYPE} = ?"
            val selectionArgs = arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call")

            val cursor = resolver.query(
                android.provider.ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                val idCol = it.getColumnIndex(android.provider.ContactsContract.Data._ID)
                val dataCol = it.getColumnIndex(android.provider.ContactsContract.Data.DATA1)

                val normalizedTarget = cleanPhone.replace(Regex("[^0-9]"), "")
                val last7 = if (normalizedTarget.length >= 7) normalizedTarget.substring(normalizedTarget.length - 7) else normalizedTarget

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val data = it.getString(dataCol) ?: ""
                    val cleanData = data.replace(Regex("[^0-9]"), "")
                    if (cleanData.isNotEmpty() && (cleanData.endsWith(last7) || normalizedTarget.endsWith(cleanData))) {
                        Log.i(TAG, "Found WhatsApp VoIP contact data ID: $id for phone $cleanPhone (data: $data)")
                        return id
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "findWhatsAppVoipDataId error: ${e.localizedMessage}")
            null
        }
    }

    private fun normalizeWhatsAppNumber(rawPhone: String): String {
        var clean = rawPhone.replace(Regex("[^0-9]"), "")
        if (clean.startsWith("08")) {
            clean = "628" + clean.substring(2)
        } else if (clean.startsWith("8")) {
            clean = "628" + clean.substring(1)
        } else if (clean.startsWith("0")) {
            clean = "62" + clean.substring(1)
        }
        return clean
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun onWhatsAppCallFinished(recordingPath: String, wasAnswered: Boolean? = null) {
        val phone = activeTargetNumber ?: ""
        val name = activeTargetName ?: ""
        val start = callInitiatedTimestamp
        val answeredStart = callAnsweredTimestamp

        // Determine if call was genuinely answered by remote party
        val isActuallyAnswered = wasAnswered ?: (wasCallAnswered || AutoCallForegroundService.isTimeoutPaused)

        // If answered, duration is conversation time from answeredStart (or start).
        // If NOT answered (timed out or hung up while ringing), talk duration is strictly 0!
        val duration = if (isActuallyAnswered) {
            val baseTime = if (answeredStart > 0L) answeredStart else start
            if (baseTime > 0L) {
                ((System.currentTimeMillis() - baseTime) / 1000).toLong().coerceAtLeast(1L)
            } else 1L
        } else {
            0L
        }

        activeTargetNumber = null
        activeTargetName = null
        callInitiatedTimestamp = 0L
        callAnsweredTimestamp = 0L
        wasCallAnswered = false

        val isSuccess = isActuallyAnswered && duration > 0

        Log.i(TAG, "WhatsApp call finished. Phone: $phone, wasAnswered: $isActuallyAnswered, Duration: ${duration}s, Recording: $recordingPath")

        emitEvent(
            mapOf(
                "event" to "CALL_COMPLETED",
                "phoneNumber" to phone,
                "duration" to duration.toInt(),
                "timestamp" to System.currentTimeMillis(),
                "callType" to "WHATSAPP",
                "status" to if (isSuccess) "COMPLETED" else "FAILED_OR_BUSY",
                "recordingPath" to if (isSuccess) recordingPath else ""
            )
        )

        // Schedule WorkManager Upload ONLY if call was genuinely answered and audio exists
        if (isSuccess && recordingPath.isNotEmpty() && java.io.File(recordingPath).exists()) {
            enqueueUploadWorker(phone, name, start, duration, "WHATSAPP", recordingPath)
        }

        // Return to AutoCall app screen
        bringAppToForeground()
    }

    /**
     * Brings the AutoCall Flutter app back to foreground.
     * Essential after WhatsApp calls finish so user returns to the queue screen.
     */
    fun bringAppToForeground() {
        mainHandler.postDelayed({
            try {
                val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                if (intent != null) {
                    if (AutoCallAccessibilityService.isServiceConnected) {
                        AutoCallAccessibilityService.bringAppToFront(intent)
                    } else {
                        appContext.startActivity(intent)
                    }
                    Log.i(TAG, "bringAppToForeground: Successfully launched AutoCall main activity.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bringAppToForeground failed: ${e.localizedMessage}")
            }
        }, 300L)
    }

    /**
     * Programmatically terminates the active or ringing call.
     * Triggered automatically by the native autocall timer when call timeout expires
     * or when user clicks 'Tutup' on the floating overlay.
     */
    fun endCall(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastEndCallTimestamp < 800L) {
            Log.i(TAG, "endCall called within 800ms debouncing window. Skipping duplicate trigger.")
            return true
        }
        lastEndCallTimestamp = now

        Log.i(TAG, "Executing endCall to terminate call on timeout...")
        var ended = false

        // Dedicated WhatsApp call termination
        if (AutoCallForegroundService.currentCallMode == AutoCallForegroundService.CALL_MODE_WHATSAPP) {
            Log.i(TAG, "Terminating WhatsApp call via accessibility service & TelecomManager...")
            try {
                if (AutoCallAccessibilityService.isServiceConnected) {
                    ended = AutoCallAccessibilityService.endCall()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AutoCallAccessibilityService WhatsApp endCall error: ${e.localizedMessage}")
            }

            // Also request TelecomManager to end call if registered
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val telecomManager = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    if (telecomManager != null && ContextCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.ANSWER_PHONE_CALLS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        telecomManager.endCall()
                        Log.i(TAG, "TelecomManager.endCall() executed for WhatsApp VoIP session.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TelecomManager.endCall() for WhatsApp: ${e.localizedMessage}")
                }
            }

            // CRITICAL: Give AccessibilityService 1500ms to click the red button and retry while WhatsApp is in the foreground,
            // then stop service (and recorder) and bring AutoCall app back to front!
            mainHandler.postDelayed({
                AutoCallForegroundService.stop(appContext)
                bringAppToForeground()
            }, 1500L)

            return ended
        }

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
        if (wasCallAnswered) return
        Log.i(TAG, "Remote party answered call! Recording answer timestamp and updating status.")
        wasCallAnswered = true
        callAnsweredTimestamp = System.currentTimeMillis()

        AutoCallForegroundService.instance?.onCallAnsweredDetected()

        emitEvent(
            mapOf(
                "event" to "CALL_ANSWERED",
                "phoneNumber" to (activeTargetNumber ?: ""),
                "timestamp" to callAnsweredTimestamp
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
    private suspend fun processCallCompleted(targetNumber: String?) {
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

        val finalName = activeTargetName ?: ""

        // Immediate check for local recording file on internal storage (Infinix, Vivo, Samsung, Xiaomi, etc.)
        var localRecordingPath = ""
        var localFile = RecordingSearchHelper.findMatchingRecordingFile(finalNumber, finalName, timestamp, duration)
        if (localFile == null && duration > 0L) {
            // OEM recorders (like Transsion / Infinix / Vivo) can take a few hundred ms to flush audio to disk
            delay(1000)
            localFile = RecordingSearchHelper.findMatchingRecordingFile(finalNumber, finalName, timestamp, duration)
        }
        if (localFile != null && localFile.exists()) {
            localRecordingPath = localFile.absolutePath
            Log.i(TAG, "Found local recording file: $localRecordingPath")
        }

        emitEvent(
            mapOf(
                "event" to "CALL_COMPLETED",
                "phoneNumber" to finalNumber,
                "name" to finalName,
                "duration" to duration,
                "timestamp" to timestamp,
                "callType" to callType,
                "status" to status,
                "recordingPath" to localRecordingPath
            )
        )

        // If the call connected and had a duration, enqueue upload worker
        if (duration > 0L) {
            enqueueUploadWorker(finalNumber, finalName, timestamp, duration, callType, localRecordingPath)
        } else {
            Log.i(TAG, "Call duration was 0 (unanswered or busy). Skipping recording upload.")
        }

        // Reset active target
        activeTargetNumber = null
        activeTargetName = null
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
        contactName: String,
        timestamp: Long,
        duration: Long,
        callType: String,
        filePath: String = ""
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = workDataOf(
            UploadRecordingWorker.KEY_PHONE_NUMBER to phoneNumber,
            UploadRecordingWorker.KEY_CONTACT_NAME to contactName,
            UploadRecordingWorker.KEY_CALL_TIMESTAMP to timestamp,
            UploadRecordingWorker.KEY_CALL_DURATION to duration,
            UploadRecordingWorker.KEY_CALL_TYPE to callType,
            UploadRecordingWorker.KEY_API_URL to backendUploadUrl,
            UploadRecordingWorker.EXTRA_FILE_PATH to filePath
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
        return try {
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
                Api30StorageHelper.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
                } catch (_: Exception) {
                    false
                }
            } else {
                true
            }

            mapOf(
                "CALL_PHONE" to hasCallPhone,
                "READ_PHONE_STATE" to hasReadPhoneState,
                "READ_CALL_LOG" to hasReadCallLog,
                "MANAGE_EXTERNAL_STORAGE" to hasAllFilesAccess,
                "IGNORE_BATTERY_OPTIMIZATIONS" to isIgnoringBatteryOptimizations,
                "ALL_GRANTED" to (hasCallPhone && hasReadPhoneState && hasReadCallLog && hasAllFilesAccess)
            )
        } catch (e: Exception) {
            Log.e(TAG, "checkPermissions error: ${e.localizedMessage}")
            mapOf(
                "CALL_PHONE" to false,
                "READ_PHONE_STATE" to false,
                "READ_CALL_LOG" to false,
                "MANAGE_EXTERNAL_STORAGE" to false,
                "IGNORE_BATTERY_OPTIMIZATIONS" to false,
                "ALL_GRANTED" to false
            )
        }
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

    /**
     * Isolated helper for Android 12 (API 31+) TelephonyCallback.
     * Prevents ART ClassLoader on Android 8-11 from throwing NoClassDefFoundError.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31TelephonyHelper {
        fun register(
            telephonyManager: TelephonyManager,
            context: Context,
            onCallState: (Int) -> Unit
        ): Any {
            val callback = object : android.telephony.TelephonyCallback(),
                android.telephony.TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onCallState(state)
                }
            }
            val executor = ContextCompat.getMainExecutor(context)
            telephonyManager.registerTelephonyCallback(executor, callback)
            return callback
        }

        fun unregister(telephonyManager: TelephonyManager, callback: Any) {
            try {
                if (callback is android.telephony.TelephonyCallback) {
                    telephonyManager.unregisterTelephonyCallback(callback)
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Isolated helper for Android 11 (API 30+) Environment.isExternalStorageManager().
     * Prevents method verification errors on Android 8-10.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private object Api30StorageHelper {
        fun isExternalStorageManager(): Boolean {
            return try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        }
    }
}
