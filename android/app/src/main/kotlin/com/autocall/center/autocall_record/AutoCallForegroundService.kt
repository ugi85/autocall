package com.autocall.center.autocall_record

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * AutoCallForegroundService
 *
 * Runs a continuous Android Foreground Service with CPU WakeLock and a Floating Overlay
 * while an outgoing call is in progress.
 *
 * Solves the critical issue where Vivo / OEM systems freeze Flutter background timers:
 * - This service runs in a native OS process, immune to background app freezes.
 * - Manages an active countdown timer independently of Flutter.
 * - Displays a live draggable Floating Overlay widget on top of Vivo's dialer screen.
 * - Automatically triggers end-call upon timeout or when the user taps "Tutup & Lanjut".
 */
class AutoCallForegroundService : Service() {

    companion object {
        const val TAG = "AutoCallFgService"
        const val CHANNEL_ID = "autocall_foreground_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.autocall.center.ACTION_START"
        const val ACTION_STOP = "com.autocall.center.ACTION_STOP"
        const val ACTION_PAUSE_TIMEOUT = "com.autocall.center.ACTION_PAUSE_TIMEOUT"

        const val EXTRA_PHONE_NUMBER = "EXTRA_PHONE_NUMBER"
        const val EXTRA_NAME = "EXTRA_NAME"
        const val EXTRA_TIMEOUT_SECONDS = "EXTRA_TIMEOUT_SECONDS"
        const val EXTRA_CALL_MODE = "EXTRA_CALL_MODE"

        const val CALL_MODE_GSM = "GSM"
        const val CALL_MODE_WHATSAPP = "WHATSAPP"

        var currentCallMode: String = CALL_MODE_GSM
            private set

        var isRunning: Boolean = false
            private set

        var activeRemainingSeconds: Int = 0
            private set

        var isTimeoutPaused: Boolean = false
            private set

        var instance: AutoCallForegroundService? = null
            private set

        fun start(
            context: Context,
            phoneNumber: String,
            name: String,
            timeoutSeconds: Int,
            callMode: String = CALL_MODE_GSM
        ) {
            currentCallMode = callMode
            val intent = Intent(context, AutoCallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_TIMEOUT_SECONDS, timeoutSeconds)
                putExtra(EXTRA_CALL_MODE, callMode)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start AutoCallForegroundService: ${e.localizedMessage}", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoCallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pauseTimeout(context: Context) {
            // User explicitly pauses timer
            isTimeoutPaused = true
            instance?.stopCountdown()
            instance?.stopInCallMonitor()
            instance?.mainHandler?.post {
                instance?.updateNotification("Panggilan Dijeda (Manual)")
                instance?.updateOverlayText()
            }

            val intent = Intent(context, AutoCallForegroundService::class.java).apply {
                action = ACTION_PAUSE_TIMEOUT
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed startService for pauseTimeout: ${e.localizedMessage}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var windowManager: WindowManager? = null
    private var floatingOverlayView: View? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var targetPhoneNumber: String = ""
    private var targetName: String = ""
    private var totalTimeoutSeconds: Int = 25
    private var currentCountdown: Int = 25
    private var callMode: String = CALL_MODE_GSM
    private var waAudioRecorder: WhatsAppAudioRecorder? = null

    private var countdownRunnable: Runnable? = null
    private var isScreenReceiverRegistered = false

    // Detect when screen turns back ON (e.g. user moves phone from ear)
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                if (isRunning && !isTimeoutPaused) {
                    mainHandler.postDelayed({
                        if (AutoCallAccessibilityService.checkIsCallConnected()) {
                            Log.i(TAG, "Call answered detected upon SCREEN_ON! Halting auto-hangup timer.")
                            CallManager.instance?.notifyCallAnswered()
                        }
                    }, 350L)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        isRunning = true

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter)
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenStateReceiver: ${e.localizedMessage}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP. Tearing down foreground service.")
                cleanUpAndStop()
                return START_NOT_STICKY
            }

            ACTION_PAUSE_TIMEOUT -> {
                Log.i(TAG, "Received ACTION_PAUSE_TIMEOUT. Pausing countdown timer.")
                isTimeoutPaused = true
                stopCountdown()
                stopInCallMonitor()
                updateNotification("Panggilan Dijeda (Manual)")
                updateOverlayText()
                return START_STICKY
            }

            ACTION_START -> {
                targetPhoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                targetName = intent.getStringExtra(EXTRA_NAME) ?: ""
                totalTimeoutSeconds = intent.getIntExtra(EXTRA_TIMEOUT_SECONDS, 25)
                callMode = intent.getStringExtra(EXTRA_CALL_MODE) ?: currentCallMode
                currentCallMode = callMode
                currentCountdown = totalTimeoutSeconds
                activeRemainingSeconds = currentCountdown
                isTimeoutPaused = false

                if (callMode == CALL_MODE_WHATSAPP) {
                    try {
                        waAudioRecorder = WhatsAppAudioRecorder(this)
                        waAudioRecorder?.startRecording(targetPhoneNumber)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error initiating WhatsAppAudioRecorder: ${e.localizedMessage}")
                    }
                }

                try {
                    val notification = buildNotification("Memanggil... Auto-close dalam ${currentCountdown}s")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val fgsType = if (callMode == CALL_MODE_WHATSAPP) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        }
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            fgsType
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val fgsType = if (callMode == CALL_MODE_WHATSAPP) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        }
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            fgsType
                        )
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in startForeground: ${e.localizedMessage}", e)
                }

                showFloatingOverlay()
                startCountdown()
            }
        }

        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AutoCall:ForegroundServiceWakeLock"
            )?.apply {
                acquire(10 * 60 * 1000L /* 10 minutes max safety timeout */)
            }
            Log.i(TAG, "CPU WakeLock acquired successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.localizedMessage}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "CPU WakeLock released.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.localizedMessage}")
        }
        wakeLock = null
    }

    fun onCallAnsweredDetected() {
        mainHandler.post {
            stopCountdown()
            startInCallMonitor()
        }
    }

    private var inCallMonitorRunnable: Runnable? = null
    private var voipInactiveConsecutiveTicks = 0

    private fun startInCallMonitor() {
        stopInCallMonitor()
        voipInactiveConsecutiveTicks = 0
        val answeredAt = CallManager.instance?.callAnsweredTimestamp ?: System.currentTimeMillis()

        inCallMonitorRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) return

                val elapsedSec = ((System.currentTimeMillis() - answeredAt) / 1000).coerceAtLeast(0L)
                val durationStr = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)

                updateNotification("Terhubung ($durationStr)")
                updateOverlayInCallState(durationStr)

                // If in WhatsApp mode, actively monitor if VoIP call session has ended
                if (callMode == CALL_MODE_WHATSAPP) {
                    // Only evaluate after at least 2 seconds of conversation
                    if (elapsedSec >= 2) {
                        val isStillActive = AutoCallAccessibilityService.checkIsWhatsAppVoipActive()
                        if (!isStillActive) {
                            voipInactiveConsecutiveTicks++
                            if (voipInactiveConsecutiveTicks >= 2) {
                                Log.i(TAG, "WhatsApp VoIP session ended after $elapsedSec seconds (confirmed over 2 ticks)! Finishing call session.")
                                stop(applicationContext)
                                CallManager.instance?.bringAppToForeground()
                                return
                            }
                        } else {
                            voipInactiveConsecutiveTicks = 0
                        }
                    }
                }

                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.post(inCallMonitorRunnable!!)
    }

    private fun stopInCallMonitor() {
        inCallMonitorRunnable?.let {
            mainHandler.removeCallbacks(it)
            inCallMonitorRunnable = null
        }
        voipInactiveConsecutiveTicks = 0
    }

    private fun updateOverlayInCallState(durationStr: String) {
        val overlay = floatingOverlayView ?: return
        try {
            val subView = overlay.findViewWithTag<TextView>("OVERLAY_SUB")
            subView?.text = "Terhubung ($durationStr) ✓"
            subView?.setTextColor(Color.parseColor("#4ADE80")) // Green-400
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay in-call state: ${e.localizedMessage}")
        }
    }

    private fun startCountdown() {
        stopCountdown()
        var checkTickCount = 0
        countdownRunnable = object : Runnable {
            override fun run() {
                if (isTimeoutPaused) return

                // 1. Accessibility inspection on active dialer window
                if (callMode == CALL_MODE_WHATSAPP) {
                    if (AutoCallAccessibilityService.checkIsCallConnected()) {
                        Log.i(TAG, "WhatsApp call answered detected during native countdown tick! Halting auto-hangup timer.")
                        CallManager.instance?.notifyCallAnswered()
                        return
                    }
                } else {
                    if (AutoCallAccessibilityService.checkIsCallConnected()) {
                        Log.i(TAG, "Call answered detected during native countdown tick! Halting auto-hangup timer.")
                        CallManager.instance?.notifyCallAnswered()
                        return
                    }

                    // 2. Fallback: Check if OEM Call Recording has started (only for GSM calls on Vivo/Samsung)
                    if (callMode == CALL_MODE_GSM) {
                        checkTickCount++
                        if (checkTickCount % 2 == 0 && checkIfNewRecordingStarted()) {
                            Log.i(TAG, "Active GSM call recording file detected! Call is answered! Halting auto-hangup timer.")
                            CallManager.instance?.notifyCallAnswered()
                            return
                        }
                    }
                }

                currentCountdown--
                activeRemainingSeconds = currentCountdown
                Log.d(TAG, "Native countdown ticking: $currentCountdown seconds remaining.")

                val isAnswered = CallManager.instance?.wasCallAnswered == true
                val statusText = if (isAnswered) {
                    "Terhubung (Auto-close ${currentCountdown}s)"
                } else {
                    "Auto-close dalam ${currentCountdown}s"
                }

                updateNotification(statusText)
                updateOverlayText()

                if (currentCountdown <= 0) {
                    Log.i(TAG, "Countdown reached 0. Waktu durasi panggilan habis! Triggering automated call termination!")
                    triggerAutoClose()
                } else {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.postDelayed(countdownRunnable!!, 1000L)
    }

    /**
     * Checks if a new audio recording file has been generated by the OEM dialer
     * since the call was initiated. Built-in dialers only record answered calls.
     */
    private fun checkIfNewRecordingStarted(): Boolean {
        if (callMode != CALL_MODE_GSM) return false
        return try {
            val callStart = CallManager.instance?.callInitiatedTimestamp ?: 0L
            if (callStart <= 0L) return false
            val candidateDirs = RecordingSearchHelper.getCandidateRecordingDirectories()
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (file.name.startsWith("WA_Call_", ignoreCase = true)) continue
                    if (file.isFile && file.lastModified() >= (callStart - 2000L)) {
                        val name = file.name.lowercase()
                        if (name.endsWith(".m4a") || name.endsWith(".mp3") || name.endsWith(".amr") ||
                            name.endsWith(".wav") || name.endsWith(".aac") || name.endsWith(".3gp") ||
                            name.endsWith(".ogg") || name.endsWith(".opus")) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    /**
     * Executes call termination and advances the autocall queue.
     */
    private fun triggerAutoClose() {
        stopCountdown()
        updateNotification("Menutup telepon & lanjut antrean...")
        updateOverlayEndingState()

        // 1. Terminate call via CallManager & Accessibility Service
        CallManager.instance?.endCall()

        // 2. Safety cleanup fallback: If for any reason CALL_STATE_IDLE is delayed,
        // clean up after 3.5s for WhatsApp, 5s for GSM
        mainHandler.postDelayed({
            if (isRunning) {
                Log.i(TAG, "Safety cleanup timer fired after endCall.")
                cleanUpAndStop()
            }
        }, if (callMode == CALL_MODE_WHATSAPP) 3500L else 5000L)
    }

    // =================== FLOATING OVERLAY WIDGET ===================

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission (SYSTEM_ALERT_WINDOW) not granted. Floating widget skipped.")
            return
        }

        if (floatingOverlayView != null) {
            removeFloatingOverlay()
        }

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 280 // 280px from top so it sits safely below WhatsApp / dialer header action bar
            }

            val overlay = buildOverlayView()
            floatingOverlayView = overlay

            // Drag to reposition listener
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            overlay.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlay, params)
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(overlay, params)
            Log.i(TAG, "Floating overlay added to WindowManager.")
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying floating overlay: ${e.localizedMessage}", e)
        }
    }

    private fun buildOverlayView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = dpToPx(14)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#E60F172A")) // Slate 900 90% opacity
                setStroke(dpToPx(1), Color.parseColor("#38BDF8")) // Sky blue border
            }
        }

        // Info Column
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val titleView = TextView(this@AutoCallForegroundService).apply {
                tag = "OVERLAY_TITLE"
                val prefix = if (callMode == CALL_MODE_WHATSAPP) "💬 WA: " else ""
                text = if (targetName.isNotEmpty()) "$prefix$targetName ($targetPhoneNumber)" else "$prefix$targetPhoneNumber"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subView = TextView(this@AutoCallForegroundService).apply {
                tag = "OVERLAY_SUB"
                text = "Auto-close dalam ${currentCountdown}s (Tap jika terhubung)"
                setTextColor(Color.parseColor("#F87171")) // Red-400
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            }
            addView(titleView)
            addView(subView)

            // Let user tap info area to immediately mark call as answered
            setOnClickListener {
                if (!isTimeoutPaused) {
                    Log.i(TAG, "User tapped floating overlay info area. Halting auto-hangup timer.")
                    CallManager.instance?.notifyCallAnswered()
                }
            }
        }
        root.addView(infoCol)

        // Spacer
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(10), 1)
        }
        root.addView(spacer)

        // Hangup & Next Button
        val closeBtn = Button(this).apply {
            text = "❌ Tutup"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val btnPad = dpToPx(8)
            setPadding(btnPad, 0, btnPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(32)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#DC2626")) // Red-600
            }
            setOnClickListener {
                Log.i(TAG, "User tapped 'Tutup' button on Floating Overlay.")
                triggerAutoClose()
            }
        }
        root.addView(closeBtn)

        return root
    }

    private fun updateOverlayText() {
        val overlay = floatingOverlayView ?: return
        try {
            val subView = overlay.findViewWithTag<TextView>("OVERLAY_SUB")
            val isAnswered = CallManager.instance?.wasCallAnswered == true
            if (isTimeoutPaused) {
                subView?.text = "Panggilan Dijeda (Manual) ✓"
                subView?.setTextColor(Color.parseColor("#38BDF8")) // Sky blue
            } else if (isAnswered) {
                val answeredAt = CallManager.instance?.callAnsweredTimestamp ?: 0L
                val elapsedSec = if (answeredAt > 0L) {
                    ((System.currentTimeMillis() - answeredAt) / 1000).coerceAtLeast(0L)
                } else 0L
                val durationStr = String.format("%02d:%02d", elapsedSec / 60, elapsedSec % 60)
                subView?.text = "Terhubung ($durationStr) ✓"
                subView?.setTextColor(Color.parseColor("#4ADE80")) // Green-400
            } else {
                subView?.text = "Auto-close dalam ${currentCountdown}s"
                subView?.setTextColor(Color.parseColor("#F87171")) // Red-400
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay text: ${e.localizedMessage}")
        }
    }

    private fun updateOverlayEndingState() {
        val overlay = floatingOverlayView ?: return
        try {
            val subView = overlay.findViewWithTag<TextView>("OVERLAY_SUB")
            subView?.text = "Menutup telepon..."
            subView?.setTextColor(Color.parseColor("#F87171"))
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay ending state: ${e.localizedMessage}")
        }
    }

    private fun removeFloatingOverlay() {
        try {
            if (floatingOverlayView != null) {
                windowManager?.removeView(floatingOverlayView)
                floatingOverlayView = null
                Log.i(TAG, "Floating overlay removed.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing floating overlay: ${e.localizedMessage}")
        }
    }

    // =================== NOTIFICATION ===================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoCall Ongoing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status pemantauan panggilan AutoCall"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val appIcon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_action_call
        val titlePrefix = if (callMode == CALL_MODE_WHATSAPP) "AutoCall WhatsApp: " else "AutoCall: "

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$titlePrefix$targetPhoneNumber ${if (targetName.isNotEmpty()) "($targetName)" else ""}")
            .setContentText(contentText)
            .setSmallIcon(appIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(contentText: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification(contentText))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update notification: ${e.localizedMessage}")
        }
    }

    private fun cleanUpAndStop() {
        stopCountdown()
        stopInCallMonitor()
        removeFloatingOverlay()
        if (callMode == CALL_MODE_WHATSAPP) {
            try {
                val recordedPath = waAudioRecorder?.stopRecording() ?: ""
                waAudioRecorder = null
                val answered = isTimeoutPaused || (CallManager.instance?.wasCallAnswered == true)
                if (!answered) {
                    // Call was never answered (timed out or hung up while still ringing)
                    // Discard the recording file so no useless ringing audio is kept or uploaded
                    try {
                        if (recordedPath.isNotEmpty()) {
                            val f = java.io.File(recordedPath)
                            if (f.exists()) f.delete()
                        }
                    } catch (_: Exception) {}
                    CallManager.instance?.onWhatsAppCallFinished("", wasAnswered = false)
                } else {
                    CallManager.instance?.onWhatsAppCallFinished(recordedPath, wasAnswered = true)
                }
                CallManager.instance?.bringAppToForeground()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping WhatsApp audio recorder: ${e.localizedMessage}")
            }
        }
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenReceiverRegistered = false
        }
        stopCountdown()
        removeFloatingOverlay()
        releaseWakeLock()
        isRunning = false
        if (instance == this) {
            instance = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanUpAndStop()
        super.onDestroy()
        Log.i(TAG, "AutoCallForegroundService destroyed.")
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
