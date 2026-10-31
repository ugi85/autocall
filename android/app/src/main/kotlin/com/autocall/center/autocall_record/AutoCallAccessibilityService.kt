package com.autocall.center.autocall_record

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * AutoCallAccessibilityService
 *
 * Automatically terminates phone calls on OEM dialers (such as Vivo V21 Funtouch OS dialer)
 * by interacting with the in-call screen UI when call timeout expires or when the user
 * requests a manual hangup.
 *
 * Keeps Vivo's native dialer and OEM call recorder 100% active, while AutoCall
 * programmatically ends the call upon timeout.
 */
class AutoCallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoCallA11y"
        private var instance: AutoCallAccessibilityService? = null

        val isServiceConnected: Boolean
            get() = instance != null

        /**
         * Checks whether the user has enabled this Accessibility Service in system settings.
         */
        fun isAccessibilityEnabled(context: Context): Boolean {
            if (instance != null) return true

            return try {
                val expectedServiceName = "${context.packageName}/${AutoCallAccessibilityService::class.java.name}"
                val enabledServicesSetting = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServicesSetting)

                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    if (componentNameString.equals(expectedServiceName, ignoreCase = true) ||
                        componentNameString.contains(AutoCallAccessibilityService::class.java.simpleName)
                    ) {
                        return true
                    }
                }
                false
            } catch (e: Exception) {
                Log.w(TAG, "isAccessibilityEnabled check failed gracefully: ${e.localizedMessage}")
                false
            }
        }

        /**
         * Dispatches end-call action through the running accessibility service.
         */
        fun endCall(): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "AutoCallAccessibilityService instance is null. Is service enabled in Settings -> Accessibility?")
                return false
            }
            return service.performEndCall()
        }

        /**
         * Pre-dial safety: terminates any hanging call session without returning to AutoCall app.
         */
        fun dismissAnyLingeringCall() {
            instance?.performPreDialCleanup()
        }

        /**
         * Checks if the active call on OEM InCallUI has been answered (timer is running / connected).
         */
        fun checkIsCallConnected(): Boolean {
            return instance?.isCallConnected() ?: false
        }

        fun bringAppToFront(intent: android.content.Intent) {
            try {
                instance?.startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "bringAppToFront via accessibility service failed: ${e.localizedMessage}")
            }
        }

        var isWhatsAppPendingCall: Boolean = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWhatsAppClickTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AutoCallAccessibilityService created.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dynamic service flags: ${e.localizedMessage}")
        }
        Log.i(TAG, "AutoCallAccessibilityService connected and ready to assist calls.")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
        Log.i(TAG, "AutoCallAccessibilityService destroyed.")
    }

    // Match timer formats like 00:01, 0:01, 01:23, 00:00:05, 0.05, and Unicode/bracketed variations
    private val timerRegex = Regex("""(?:\b|\D|^)\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\b|\D|$)""")
    // Match spoken duration like "5 detik", "0 menit 5 detik", "5 seconds" (used in Google Phone contentDescription)
    private val spokenTimerRegex = Regex("""\d+\s*(?:detik|dtk|menit|mnt|sec|min)""", RegexOption.IGNORE_CASE)

    private val connectedKeywords = listOf(
        "terhubung", "tersambung", "berlangsung", "bicara", "sedang bicara",
        "panggilan aktif", "connected", "in call", "incall", "ongoing",
        "active call", "call in progress"
    )

    private val connectedViewIdKeywords = listOf(
        "chronometer", "call_time", "floating_incall_time", "call_duration",
        "elapsed", "incall_timer", "call_state", "call_status"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val pkg = event.packageName?.toString() ?: ""
            if (isExcludedPackage(pkg)) return

            // Handle WhatsApp call launch automation if pending
            if (isWhatsAppPendingCall && pkg.contains("whatsapp")) {
                val root = rootInActiveWindow ?: event.source
                if (root != null) {
                    tryAutomateWhatsAppCall(root)
                }
            }

            // Monitor active call status while AutoCallForegroundService is running
            if (AutoCallForegroundService.isRunning) {
                if (AutoCallForegroundService.currentCallMode == AutoCallForegroundService.CALL_MODE_WHATSAPP) {
                    if (pkg.contains("whatsapp")) {
                        val root = getWhatsAppRootWindow() ?: rootInActiveWindow ?: event.source
                        if (root != null) {
                            // A: Check if WhatsApp call has ended naturally (screen shows 'call ended', 'panggilan berakhir', etc.)
                            if (hasWhatsAppCallEndedIndicator(root)) {
                                Log.i(TAG, "WhatsApp call ended indicator detected on screen! Finishing call session.")
                                AutoCallForegroundService.stop(applicationContext)
                                CallManager.instance?.bringAppToForeground()
                                return
                            }

                            // B: If call was marked answered and VoIP screen is no longer active (returned to chat), call ended!
                            val wasAnswered = CallManager.instance?.wasCallAnswered == true
                            val answeredAt = CallManager.instance?.callAnsweredTimestamp ?: 0L
                            if (wasAnswered && answeredAt > 0L && (System.currentTimeMillis() - answeredAt) > 2500L) {
                                val metrics = resources.displayMetrics
                                if (!isWhatsAppVoipActive(root, metrics.heightPixels)) {
                                    Log.i(TAG, "WhatsApp VoIP session closed after active conversation! Finishing call session.")
                                    AutoCallForegroundService.stop(applicationContext)
                                    CallManager.instance?.bringAppToForeground()
                                    return
                                }
                            }

                            // C: Detect call answered (connected timer ticking)
                            if (!wasAnswered && isWhatsAppCallConnected(root)) {
                                Log.i(TAG, "WhatsApp call answered detected! Updating status.")
                                CallManager.instance?.notifyCallAnswered()
                            }
                        }
                    }
                } else {
                    if (!isExcludedPackage(pkg) && !AutoCallForegroundService.isTimeoutPaused) {
                        val source = event.source ?: rootInActiveWindow
                        if (source != null && hasConnectedCallIndicator(source)) {
                            Log.i(TAG, "Call answered detected via accessibility event in $pkg! Halting timeout.")
                            CallManager.instance?.notifyCallAnswered()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error processing accessibility event: ${e.localizedMessage}")
        }
    }

    private fun getWhatsAppRootWindow(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString()?.contains("whatsapp") == true) {
            return activeRoot
        }
        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (w in winList) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString()?.contains("whatsapp") == true) {
                        return r
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun hasWhatsAppCallEndedIndicator(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("entry") || viewId.contains("input") || viewId.contains("send") || viewId.contains("bubble")) {
            return false
        }

        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val candidate = "$text $desc".trim()

        if (candidate.isNotEmpty()) {
            val endedKeywords = listOf(
                "panggilan berakhir", "call ended", "panggilan ditolak", "call declined",
                "tidak terjawab", "missed call", "tidak dapat tersambung", "couldn't connect",
                "panggilan dibatalkan", "call cancelled", "call canceled", "panggilan selesai"
            )
            if (endedKeywords.any { candidate.contains(it) }) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasWhatsAppCallEndedIndicator(child)) return true
        }
        return false
    }

    /**
     * Determines whether a WhatsApp VoIP call has been answered (chronometer ticking).
     * Strictly verifies that the screen is NOT in "Berdering" / "Memanggil" state,
     * and guarantees that chat history timestamps (e.g. 19.39, 12.25) are NEVER parsed as call duration.
     */
    fun isWhatsAppCallConnected(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        // 1. If screen contains ANY ringing/calling indicator, definitely NOT connected yet!
        if (isScreenInRingingOrDialingState(root)) {
            return false
        }

        // 2. Screen must have VoIP elements (full screen or minimized top banner)
        if (!hasVoipInCallElements(root)) {
            return false
        }

        // 3. Search for duration timer strictly outside chat bubbles and status bar
        val metrics = resources.displayMetrics
        return findWhatsAppVoipTimer(root, metrics.heightPixels)
    }

    private fun findWhatsAppVoipTimer(node: AccessibilityNodeInfo?, screenHeight: Int): Boolean {
        if (node == null) return false
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Strictly ignore chat messages, conversation list, input area, and system status bar
        if (viewId.contains("entry") || viewId.contains("message") ||
            viewId.contains("conversation") || viewId.contains("chat") ||
            viewId.contains("input") || viewId.contains("send") ||
            viewId.contains("bubble") || viewId.contains("list") ||
            viewId.contains("clock") || viewId.contains("status_bar")
        ) {
            return false
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Safety: System status bar is in top 8% of screen (where clock like 21:50 is located)
        // WhatsApp VoIP timer is in upper-middle area (between 8% and 65% of screen height)
        if (rect.centerY() < (screenHeight * 0.08f) || rect.centerY() > (screenHeight * 0.65f)) {
            return false
        }

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val candidate = if (text.isNotEmpty()) text else desc

        // Match call elapsed timer: strictly 00:02 up to 59:59 (never match clock hours 10-23)
        // Must start with 00: or 0[0-9]:
        val callTimerPattern = Regex("""^0[0-9]:[0-5][0-9](?::[0-5][0-9])?$""")
        if (callTimerPattern.matches(candidate)) {
            val parts = candidate.split(":")
            val timerSeconds = if (parts.size == 2) {
                (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            } else if (parts.size == 3) {
                (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            } else 0

            // CRITICAL: 00:00 or 00:01 while still ringing is NOT an active conversation!
            // Conversation must have progressed to at least 2 seconds (00:02+)
            if (timerSeconds < 2) {
                return false
            }

            // Extra sanity check: Call duration timer cannot exceed actual elapsed call initiation time + 5s!
            val start = CallManager.instance?.callInitiatedTimestamp ?: 0L
            if (start > 0L) {
                val elapsedSinceDial = (System.currentTimeMillis() - start) / 1000
                if (timerSeconds > (elapsedSinceDial + 5)) {
                    // This is a timestamp or clock, NOT this call's timer!
                    return false
                }
            }
            Log.i(TAG, "WhatsApp valid call timer verified: '$candidate' on view: $viewId at (${rect.centerX()}, ${rect.centerY()})")
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findWhatsAppVoipTimer(child, screenHeight)) return true
        }
        return false
    }

    /**
     * Determines whether the active screen belongs to an active WhatsApp VoIP Call screen.
     */
    private fun isWhatsAppVoipScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString() ?: ""
        if (!pkg.contains("whatsapp")) return false
        return hasVoipInCallElements(node)
    }

    private fun hasVoipInCallElements(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        // Strictly ignore chat conversation messages and text entry
        if (viewId.contains("entry") || viewId.contains("message") ||
            viewId.contains("conversation") || viewId.contains("chat") ||
            viewId.contains("input") || viewId.contains("send") ||
            viewId.contains("bubble") || viewId.contains("list")
        ) {
            return false
        }

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val combined = "$desc $text $viewId".trim()

        if (viewId.contains("end_call") || viewId.contains("call_end") || viewId.contains("voip") ||
            viewId.contains("call_status") || viewId.contains("call_time") || viewId.contains("footer_end_call") ||
            viewId.contains("mute_btn") || viewId.contains("speaker_btn") || viewId.contains("incall") ||
            combined.contains("akhiri") || combined.contains("tutup panggilan") || combined.contains("tutup telepon") ||
            combined.contains("putuskan") || combined.contains("end call") || combined.contains("hang up") ||
            combined.contains("berdering") || combined.contains("ringing") ||
            combined.contains("memanggil") || combined.contains("calling") || combined.contains("menghubungi") ||
            combined.contains("pelantang") || combined.contains("pengeras") ||
            combined.contains("bungkam") || combined.contains("matikan mikrofon")
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasVoipInCallElements(child)) return true
        }
        return false
    }

    private fun tryAutomateWhatsAppCall(root: AccessibilityNodeInfo): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastWhatsAppClickTimestamp < 4000L) {
            return false
        }

        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels

        // 1. Look for Confirmation Dialog Button ("PANGGIL" / "CALL" / android:id/button1)
        val confirmBtn = findConfirmationDialogButton(root)
        if (confirmBtn != null && confirmBtn.isClickable) {
            Log.i(TAG, "WhatsApp confirmation dialog found. Clicking PANGGIL/CALL!")
            lastWhatsAppClickTimestamp = now
            isWhatsAppPendingCall = false
            val clicked = confirmBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                val rect = Rect()
                confirmBtn.getBoundsInScreen(rect)
                if (rect.centerX() > 0 && rect.centerY() > 0) {
                    dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
                }
            }
            return true
        }

        // 2. Look for Voice Call icon in Conversation toolbar strictly in top bar (Y < 450)
        val callIcon = findWhatsAppVoiceCallButton(root, screenHeight)
        if (callIcon != null) {
            val rect = Rect()
            callIcon.getBoundsInScreen(rect)
            Log.i(TAG, "WhatsApp voice call button found at (${rect.centerX()}, ${rect.centerY()}). Clicking once!")
            lastWhatsAppClickTimestamp = now
            isWhatsAppPendingCall = false

            // Click ONCE only! Never dispatch duplicate tap if action click succeeded!
            val clicked = callIcon.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked && rect.centerX() > 0) {
                dispatchTap(rect.centerX().toFloat(), rect.centerY().toFloat())
            }

            // Also check for popup dialog after 500ms
            mainHandler.postDelayed({
                try {
                    val activeRoot = rootInActiveWindow
                    if (activeRoot != null) {
                        val dlgBtn = findConfirmationDialogButton(activeRoot)
                        if (dlgBtn != null && dlgBtn.isClickable) {
                            Log.i(TAG, "Delayed check: Clicking WhatsApp confirmation dialog!")
                            dlgBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                    }
                } catch (_: Exception) {}
            }, 500L)

            return true
        }

        return false
    }

    private fun findConfirmationDialogButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (node.isClickable && (
            viewId.endsWith(":id/button1") ||
            text.equals("panggil", ignoreCase = true) || text.equals("call", ignoreCase = true) ||
            desc.equals("panggil", ignoreCase = true) || desc.equals("call", ignoreCase = true)
        )) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findConfirmationDialogButton(child)
            if (found != null) return found
        }
        return null
    }

    private fun findWhatsAppVoiceCallButton(node: AccessibilityNodeInfo?, screenHeight: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Must be located in top action bar (top 450px) and not too small
        if (rect.top < 450 && rect.width() > 30 && rect.height() > 30) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // Exclude video call
            val isVideo = desc.contains("video") || viewId.contains("video")
            if (!isVideo) {
                if (desc.contains("panggilan suara") || desc.contains("voice call") ||
                    desc.contains("panggilan audio") || desc.contains("telepon suara") ||
                    (desc.contains("panggilan") && !desc.contains("video")) ||
                    (desc.contains("call") && !desc.contains("video")) ||
                    viewId.contains("menuitem_conversations_call") || viewId.contains("action_call") ||
                    viewId.contains("menuitem_call")
                ) {
                    return node
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWhatsAppVoiceCallButton(child, screenHeight)
            if (found != null) return found
        }
        return null
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, targetKeywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (node.isClickable && (targetKeywords.any { text.equals(it, ignoreCase = true) } || targetKeywords.any { desc.equals(it, ignoreCase = true) })) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, targetKeywords)
            if (found != null) return found
        }
        return null
    }

    /**
     * Actively inspects dialer windows to determine if active call conversation
     * is underway (chronometer is running or connected status is shown).
     */
    fun isCallConnected(): Boolean {
        try {
            if (AutoCallForegroundService.currentCallMode == AutoCallForegroundService.CALL_MODE_WHATSAPP) {
                val waRoot = getWhatsAppRootWindow()
                if (waRoot != null) {
                    return isWhatsAppCallConnected(waRoot)
                }
                return false
            }

            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (!isExcludedPackage(pkg) && hasConnectedCallIndicator(activeRoot)) {
                    return true
                }
            }

            val winList = windows
            if (!winList.isNullOrEmpty()) {
                for (window in winList) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (isExcludedPackage(pkg)) continue
                    if (hasConnectedCallIndicator(root)) {
                        return true
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking isCallConnected: ${e.localizedMessage}")
        }
        return false
    }

    private fun isScreenInRingingOrDialingState(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains("berdering") || text.contains("ringing") ||
            text.contains("memanggil") || text.contains("dialing") ||
            text.contains("calling") || text.contains("menghubungi") ||
            text.contains("menghubungkan") || text.contains("connecting") ||
            desc.contains("berdering") || desc.contains("ringing") ||
            desc.contains("memanggil") || desc.contains("dialing") ||
            desc.contains("menghubungkan") || desc.contains("connecting")
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isScreenInRingingOrDialingState(child)) return true
        }
        return false
    }

    private fun hasConnectedCallIndicator(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        // Crucial: If any visible node on this screen says "Berdering", "Memanggil", "Calling", "Ringing":
        // The call is definitely still dialing / ringing, NOT connected yet!
        if (isScreenInRingingOrDialingState(node)) {
            return false
        }

        // WhatsApp specific safeguard: Never scan chat bubbles, timestamps, or message views!
        val pkg = node.packageName?.toString() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (pkg.contains("whatsapp")) {
            if (viewId.contains("entry") || viewId.contains("message_text") ||
                viewId.contains("conversation") || viewId.contains("chat_list") ||
                viewId.contains("input_layout") || viewId.contains("send") ||
                viewId.contains("bubble")
            ) {
                return false
            }
        }

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val combined = "$text $desc".trim()

        // If the node specifically indicates still dialing / ringing, it's not connected yet
        val lowerCombined = combined.lowercase()
        val isDialing = lowerCombined.contains("memanggil") ||
                lowerCombined.contains("dialing") ||
                lowerCombined.contains("calling") ||
                lowerCombined.contains("menghubungi") ||
                lowerCombined.contains("berdering") ||
                lowerCombined.contains("ringing")

        if (!isDialing) {
            // 1. Check Chronometer widget class (direct indicator in Android)
            val className = node.className?.toString() ?: ""
            if (className.contains("Chronometer", ignoreCase = true)) {
                // Any active visible chronometer or one with digits
                if (combined.isNotEmpty() && (timerRegex.containsMatchIn(combined) || spokenTimerRegex.containsMatchIn(combined))) {
                    return true
                }
                // If it's a visible chronometer without text yet, it's still connected!
                if (node.isVisibleToUser) {
                    return true
                }
            }

            // 2. Check View Resource IDs
            val viewId = node.viewIdResourceName?.lowercase() ?: ""
            if (connectedViewIdKeywords.any { viewId.contains(it) }) {
                if (combined.isNotEmpty() && (timerRegex.containsMatchIn(combined) || spokenTimerRegex.containsMatchIn(combined))) {
                    return true
                }
                if (connectedKeywords.any { lowerCombined.contains(it) }) {
                    return true
                }
            }

            // 3. Check combined text & contentDescription for timer or keywords
            if (combined.isNotEmpty()) {
                if (timerRegex.containsMatchIn(combined) || spokenTimerRegex.containsMatchIn(combined)) {
                    return true
                }
                if (connectedKeywords.any { lowerCombined.contains(it) }) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasConnectedCallIndicator(child)) {
                return true
            }
        }

        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoCallAccessibilityService interrupted.")
    }

    /**
     * Executes end-call automation on OEM dialers (Vivo, Samsung, Xiaomi, etc.)
     * Multi-phase strategy:
     * 1. Target dialer windows (com.android.incallui, com.vivo.incallui, com.android.dialer, etc.).
     * 2. Search for exact end-call button view IDs (end_button, bbk_end_call_btn, endButton, etc.).
     * 3. Geometric search for bottom-center button in dialer window (strictly above system navigation bar).
     * 4. Multi-point gesture tap fallback directly targeting Vivo V21's red button area.
     *
     * STRICT SAFETY: Never clicks anything in com.android.systemui (navigation bar / home button),
     * launcher, or our own app overlay!
     */
    fun performEndCall(): Boolean {
        Log.i(TAG, "AutoCallAccessibilityService: Initiating end-call automation...")

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Route to dedicated WhatsApp end-call logic if in WhatsApp mode
        if (AutoCallForegroundService.currentCallMode == AutoCallForegroundService.CALL_MODE_WHATSAPP) {
            return performWhatsAppEndCall(screenWidth, screenHeight)
        }

        try {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                // Prioritize dialer windows first (e.g. com.android.incallui)
                val sortedWindows = winList.sortedByDescending { window ->
                    val pkg = window.root?.packageName?.toString() ?: ""
                    if (isDialerPackage(pkg)) 2 else if (!isExcludedPackage(pkg)) 1 else 0
                }

                for (window in sortedWindows) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""

                    // STRICTLY SKIP system UI (navigation bar), launcher, and our overlay
                    if (isExcludedPackage(pkg)) {
                        continue
                    }

                    // A: Targeted search by ID / Text / Description
                    val targetNode = findEndButtonNode(root, screenHeight)
                    if (targetNode != null) {
                        Log.i(TAG, "Found target end button by ID/Text in [$pkg]: ${targetNode.viewIdResourceName}")
                        clickAndTapNode(targetNode, screenHeight)
                        return true
                    }

                    // B: Geometric search for bottom-center button in dialer window
                    if (isDialerPackage(pkg)) {
                        val geometricNode = findGeometricEndButton(root, screenWidth, screenHeight)
                        if (geometricNode != null) {
                            Log.i(TAG, "Found target end button by geometry in [$pkg]: ${geometricNode.viewIdResourceName}")
                            clickAndTapNode(geometricNode, screenHeight)
                            return true
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error traversing windows: ${e.localizedMessage}")
        }

        // Phase 2: Check rootInActiveWindow
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (!isExcludedPackage(pkg)) {
                    val targetNode = findEndButtonNode(activeRoot, screenHeight)
                    if (targetNode != null) {
                        Log.i(TAG, "Found target end button in rootInActiveWindow [$pkg]: ${targetNode.viewIdResourceName}")
                        clickAndTapNode(targetNode, screenHeight)
                        return true
                    }

                    if (isDialerPackage(pkg)) {
                        val geometricNode = findGeometricEndButton(activeRoot, screenWidth, screenHeight)
                        if (geometricNode != null) {
                            Log.i(TAG, "Found target end button in rootInActiveWindow by geometry [$pkg]: ${geometricNode.viewIdResourceName}")
                            clickAndTapNode(geometricNode, screenHeight)
                            return true
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking rootInActiveWindow: ${e.localizedMessage}")
        }

        // Phase 3: Hardware gesture tap fallbacks tailored for Vivo V21 (1080x2400 20:9 screen)
        Log.i(TAG, "Executing multi-point gesture tap fallback on Vivo V21 red button area...")
        return performVivoGestureTaps(screenWidth, screenHeight)
    }

    /**
     * Checks if the package belongs to telephony / in-call dialer.
     */
    private fun isDialerPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val p = pkg.lowercase()
        return p.contains("incallui") ||
                p.contains("dialer") ||
                p.contains("phone") ||
                p.contains("telecom") ||
                p.contains("telephon") ||
                p.contains("contact") ||
                p.contains("caller") ||
                p.contains("whatsapp") ||
                (p.contains("call") && !p.contains("autocall"))
    }

    /**
     * Checks if the package belongs to SystemUI (navigation bar / home bar),
     * launcher, our own app, or system framework overlays that must NEVER be clicked.
     */
    private fun isExcludedPackage(pkg: String?): Boolean {
        if (pkg == null) return true
        val p = pkg.lowercase()
        return p == packageName ||
                p.contains("systemui") ||
                p.contains("launcher") ||
                p.contains("home") ||
                p.contains("inputmethod") ||
                p.contains("settings") ||
                p == "android"
    }

    /**
     * Recursively traverses nodes looking for end-call buttons by resource ID,
     * contentDescription, or text.
     * Strictly verifies that the node is NOT in the Android navigation bar.
     */
    private fun findEndButtonNode(node: AccessibilityNodeInfo?, screenHeight: Int): AccessibilityNodeInfo? {
        if (node == null) return null

        val pkg = node.packageName?.toString() ?: ""
        if (isExcludedPackage(pkg)) {
            return null
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Safety check: Android Navigation Bar / Gesture Bar is in the bottom 120px of the screen
        val navBarThreshold = screenHeight - 120
        if (rect.centerY() >= navBarThreshold) {
            // This is the Android navigation bar (Home/Back/Recents)! Strictly skip!
            return null
        }

        // 1. Check Resource ID
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (isEndButtonId(viewId)) {
            return node
        }

        // 2. Check Content Description & Text (only if within dialer package)
        if (isDialerPackage(pkg)) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""
            val keywords = listOf(
                "end call", "endcall", "akhiri", "tutup panggilan", "tutup telepon",
                "hang up", "hangup", "decline", "tolak", "putuskan", "putus",
                "batal", "selesai", "挂断", "结束", "拒绝"
            )
            if (keywords.any { desc.contains(it) } || keywords.any { text.contains(it) }) {
                return node
            }
        }

        // 3. Recurse down children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEndButtonNode(child, screenHeight)
            if (found != null) {
                return found
            }
        }

        return null
    }

    /**
     * Checks if resource ID matches known OEM dialer hangup buttons.
     * Explicitly excludes navigation bar and system elements like 'ends_group'.
     */
    private fun isEndButtonId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val id = viewId.lowercase()

        // Explicit blacklists of system views that happen to contain 'end'
        if (id.contains("ends_group") ||
            id.contains("navigation") ||
            id.contains("keyguard") ||
            id.contains("status_bar") ||
            id.contains("home") ||
            id.contains("back")
        ) {
            return false
        }

        // Known dialer IDs
        return id.endsWith(":id/end_button") ||
                id.endsWith(":id/bbk_end_call_btn") ||
                id.endsWith(":id/endbutton") ||
                id.endsWith(":id/incall_end_call") ||
                id.endsWith(":id/floating_end_call_action_button") ||
                id.endsWith(":id/btn_end") ||
                id.endsWith(":id/end_call") ||
                id.contains("end_button") ||
                id.contains("bbk_end") ||
                id.contains("hangup") ||
                id.contains("disconnect") ||
                id.contains("reject") ||
                id.contains("decline") ||
                (id.contains("end") && (id.contains("call") || id.contains("btn") || id.contains("button")))
    }

    /**
     * Identifies the OEM end-call Floating Action Button (FAB) based on screen geometry
     * strictly within a dialer package window.
     */
    private fun findGeometricEndButton(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val pkg = node.packageName?.toString() ?: ""
        if (isExcludedPackage(pkg) || !isDialerPackage(pkg)) return null

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val width = rect.width()
        val height = rect.height()

        val navBarThreshold = screenHeight - 120
        // Centered horizontally: within 30% to 70% of screen width
        val isCenteredX = centerX in (screenWidth * 0.30).toInt()..(screenWidth * 0.70).toInt()
        // Located in bottom call control area, but strictly ABOVE navigation bar
        val isBottomY = centerY in (screenHeight * 0.60).toInt()..navBarThreshold
        // Prominent button size
        val isValidSize = width in 50..350 && height in 50..350

        if (isCenteredX && isBottomY && isValidSize) {
            val className = node.className?.toString()?.lowercase() ?: ""
            val isButtonType = className.contains("button") ||
                    className.contains("imageview") ||
                    className.contains("view")

            if ((node.isClickable || isButtonType) && node.childCount == 0) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findGeometricEndButton(child, screenWidth, screenHeight)
            if (found != null) {
                return found
            }
        }

        return null
    }

    /**
     * Executes both accessibility action click and direct coordinate gesture tap
     * at the center of the node to guarantee trigger on all OEM implementations.
     */
    private fun clickAndTapNode(node: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        var clicked = false
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Extra guard: NEVER click if within the bottom 120px (navigation bar)
        if (rect.centerY() >= screenHeight - 120) {
            Log.w(TAG, "clickAndTapNode rejected: node sits in system navigation bar area (${rect.centerY()} >= ${screenHeight - 120})")
            return false
        }

        try {
            // 1. Accessibility click on node or parents
            if (node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                    parent = parent.parent
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error performing ACTION_CLICK: ${e.localizedMessage}")
        }

        // 2. Also dispatch gesture tap directly on node's center coordinate
        if (rect.width() > 0 && rect.height() > 0) {
            val tapX = rect.centerX().toFloat()
            val tapY = rect.centerY().toFloat()
            Log.i(TAG, "Dispatching direct gesture tap on dialer node center: ($tapX, $tapY)")
            dispatchTap(tapX, tapY)
        }

        return clicked
    }

    /**
     * Multi-coordinate gesture tap fallback.
     * On Vivo V21 (1080 x 2400, 20:9 aspect ratio):
     * The red End Call button is centered at (540, ~2050px) which is ~85.4% of screen height.
     * System navigation bar is below 2280px.
     * We fire taps strictly in the 85.0% - 87.0% range (Y = ~2040 - 2088px), well above the navigation bar!
     */
    private fun performVivoGestureTaps(screenWidth: Int, screenHeight: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val centerX = screenWidth / 2f
        // Primary Vivo V21 red button center: 85.0% height (~2040px on 2400px height)
        val targetY1 = screenHeight * 0.850f
        // Secondary Vivo target: 87.0% height (~2088px, still well above 2280px navigation bar)
        val targetY2 = screenHeight * 0.870f
        // Tertiary target: 83.0% height (~1992px)
        val targetY3 = screenHeight * 0.830f

        Log.i(TAG, "Dispatching Vivo V21 primary gesture tap at ($centerX, $targetY1)")
        dispatchTap(centerX, targetY1)

        // Staggered follow-up tap after 180ms
        mainHandler.postDelayed({
            Log.i(TAG, "Dispatching secondary gesture tap at ($centerX, $targetY2)")
            dispatchTap(centerX, targetY2)
        }, 180L)

        // Staggered tertiary tap after 360ms
        mainHandler.postDelayed({
            Log.i(TAG, "Dispatching tertiary gesture tap at ($centerX, $targetY3)")
            dispatchTap(centerX, targetY3)
        }, 360L)

        return true
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 75)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.i(TAG, "Gesture tap at ($x, $y) completed successfully.")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture tap at ($x, $y) was cancelled.")
                }
            }, null)
        } catch (e: Throwable) {
            Log.e(TAG, "dispatchTap error at ($x, $y): ${e.localizedMessage}")
            false
        }
    }

    fun performPreDialCleanup() {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        try {
            val root = getWhatsAppRootWindow()
            if (root != null) {
                if (isWhatsAppVoipActive(root, screenHeight)) {
                    Log.i(TAG, "Pre-dial cleanup: terminating lingering WhatsApp VoIP session.")
                    val endBtn = findWhatsAppEndCallButton(root, screenWidth, screenHeight)
                    if (endBtn != null) {
                        clickAndTapNode(endBtn, screenHeight)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Terminate active or ringing WhatsApp VoIP call.
     * Targeted strictly at the real End Call button on the WhatsApp call screen,
     * with direct coordinate tap on the red 'Akhiri' button.
     * NEVER uses GLOBAL_ACTION_BACK to prevent minimizing the call into background.
     */
    fun performWhatsAppEndCall(screenWidth: Int, screenHeight: Int): Boolean {
        Log.i(TAG, "AutoCallAccessibilityService: Executing performWhatsAppEndCall...")

        // Strictly target WhatsApp window (never AutoCall or other apps)
        val waRoot = getWhatsAppRootWindow()
        if (waRoot == null) {
            val active = rootInActiveWindow
            if (active?.packageName?.toString()?.contains("whatsapp") != true) {
                Log.w(TAG, "No WhatsApp window found in active windows (active: ${active?.packageName}). Skipping hangup.")
                return false
            }
        }

        val targetRoot = waRoot ?: rootInActiveWindow ?: return false

        // 1. Dismiss any WhatsApp confirmation dialog ("Panggil?", "Izin", etc.)
        dismissAnyWhatsAppDialog(targetRoot)

        // 2. Search for the End Call / Hangup button node
        val endBtn = findWhatsAppEndCallButton(targetRoot, screenWidth, screenHeight)

        if (endBtn != null) {
            val rect = Rect()
            endBtn.getBoundsInScreen(rect)
            val tapX = rect.centerX().toFloat()
            val tapY = rect.centerY().toFloat()
            val viewId = endBtn.viewIdResourceName ?: "no_id"
            val desc = endBtn.contentDescription?.toString() ?: ""
            val text = endBtn.text?.toString() ?: ""
            Log.i(TAG, "Found WhatsApp end-call button at ($tapX, $tapY) [id=$viewId, desc='$desc', text='$text']. Executing click & gesture tap!")

            // Accessibility action click on node and clickable parents
            if (endBtn.isClickable) {
                endBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = endBtn.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                parent = parent.parent
            }

            // Hardware gesture tap on the detected button coordinate
            if (tapX > 0 && tapY > (screenHeight * 0.45f) && tapY < screenHeight) {
                dispatchTap(tapX, tapY)
            }
        } else {
            Log.w(TAG, "WhatsApp end-call button node not found by id/text.")
        }

        // 3. Guaranteed Coordinate Tap on VoIP red button:
        // On WhatsApp VoIP call screen, the red 'Akhiri' button is at bottom-right (X ~80%, Y ~86%)
        val rightX = screenWidth * 0.80f
        val bottomY = screenHeight * 0.86f
        val centerX = screenWidth * 0.50f
        Log.i(TAG, "Executing guaranteed VoIP gesture tap at ($rightX, $bottomY)...")
        dispatchTap(rightX, bottomY)

        // Staggered follow-up tap at 120ms
        mainHandler.postDelayed({
            dispatchTap(rightX, bottomY)
            dispatchTap(centerX, bottomY)
        }, 120L)

        // 4. Verification check & Retry at 350ms
        mainHandler.postDelayed({
            try {
                val curWaRoot = getWhatsAppRootWindow()
                if (curWaRoot != null && isWhatsAppVoipActive(curWaRoot, screenHeight)) {
                    Log.i(TAG, "Verification at 350ms: WhatsApp call still active! Retrying hangup...")
                    dispatchTap(rightX, bottomY)
                    dispatchTap(centerX, bottomY)
                    val retryBtn = findWhatsAppEndCallButton(curWaRoot, screenWidth, screenHeight)
                    retryBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    Log.i(TAG, "Verification at 350ms: WhatsApp call successfully terminated!")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Verification check error: ${e.localizedMessage}")
            }
        }, 350L)

        // 5. Transition directly back to AutoCall app at 800ms
        mainHandler.postDelayed({
            CallManager.instance?.bringAppToForeground()
        }, 800L)

        return true
    }

    private fun dismissAnyWhatsAppDialog(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            if (node.isClickable && (
                text == "batal" || text == "cancel" || text == "tutup" || text == "close" ||
                desc == "batal" || desc == "cancel" || desc == "tutup" || desc == "close" ||
                viewId.endsWith(":id/button2")
            )) {
                Log.i(TAG, "Dismissing WhatsApp dialog button: text='$text', desc='$desc', id='$viewId'")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }

            for (i in 0 until node.childCount) {
                dismissAnyWhatsAppDialog(node.getChild(i))
            }
        } catch (_: Exception) {}
    }

    /**
     * Checks if WhatsApp is currently showing ANY VoIP call elements (ringing, calling, connected, full-screen, or banner).
     */
    fun isWhatsAppVoipActive(node: AccessibilityNodeInfo?, screenHeight: Int): Boolean {
        if (node == null) return false
        val pkg = node.packageName?.toString() ?: ""
        if (!pkg.contains("whatsapp")) return false

        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        // Strictly ignore chat conversation messages and text entry
        if (viewId.contains("entry") || viewId.contains("message") ||
            viewId.contains("conversation") || viewId.contains("chat") ||
            viewId.contains("input") || viewId.contains("send") ||
            viewId.contains("bubble") || viewId.contains("list")
        ) {
            return false
        }

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val combined = "$desc $text $viewId".trim()

        if (viewId.contains("end_call") || viewId.contains("call_end") || viewId.contains("voip") ||
            viewId.contains("call_status") || viewId.contains("footer_end_call") || viewId.contains("incall") ||
            combined.contains("akhiri") || combined.contains("tutup panggilan") || combined.contains("tutup telepon") ||
            combined.contains("putuskan") || combined.contains("end call") || combined.contains("hang up") ||
            combined.contains("berdering") || combined.contains("ringing") ||
            combined.contains("memanggil") || combined.contains("calling") || combined.contains("menghubungi") ||
            combined.contains("sedang bicara") || combined.contains("terhubung") || combined.contains("bicara")
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isWhatsAppVoipActive(child, screenHeight)) return true
        }
        return false
    }

    /**
     * Recursively traverses nodes to find WhatsApp's End Call / Hangup button.
     * Evaluates IDs, Indonesian and English descriptions, and bottom call bar geometry.
     * STRICT RULE: The End Call button is ALWAYS in the bottom area (Y > 55% of screenHeight).
     * Any node in the top 55% (like "Tambah orang", Menu, Back arrow) is STRICTLY IGNORED!
     */
    fun findWhatsAppEndCallButton(
        node: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        // STRICT SAFETY: Never match anything outside WhatsApp!
        val pkg = node.packageName?.toString() ?: ""
        if (pkg.isNotEmpty() && !pkg.contains("whatsapp")) {
            return null
        }

        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Safety check: Skip system gesture navigation bar (bottom 35px)
        if (rect.centerY() >= screenHeight - 35) {
            return null
        }

        // WhatsApp End Call button in full-screen or call sheet is in the lower half of the screen
        val isInBottomCallArea = rect.centerY() > (screenHeight * 0.45f)

        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Exclusions: Only exclude the current node if it represents mic, speaker, camera, video, etc.
        val isExcludedFeature = desc.contains("senyap") || desc.contains("mute") ||
                desc.contains("bungkam") || desc.contains("mikrofon") || desc.contains("mic") ||
                desc.contains("speaker") || desc.contains("pelantang") || desc.contains("pengeras") ||
                desc.contains("kamera") || desc.contains("video") || desc.contains("bagikan") ||
                desc.contains("tambah") || desc.contains("peserta") || desc.contains("participant")

        // 1. Match by Resource ID (in bottom call area)
        val isEndId = isInBottomCallArea && (
            viewId.contains("end_call") || viewId.contains("call_end") ||
            viewId.contains("hangup") || viewId.contains("disconnect") ||
            viewId.contains("footer_end_call") || viewId.contains("button_end_call") ||
            viewId.contains("btn_end") || viewId.contains("btn_call_end") ||
            viewId.contains("call_close")
        )

        // 2. Match by ContentDescription or Text (in bottom call area)
        val isEndKeyword = isInBottomCallArea && (
            desc.contains("akhiri") || text.contains("akhiri") ||
            desc.contains("tutup panggilan") || text.contains("tutup panggilan") ||
            desc.contains("tutup telepon") || text.contains("tutup telepon") ||
            desc.contains("putuskan") || text.contains("putuskan") ||
            desc.contains("tolak") || text.contains("tolak") ||
            desc.contains("end call") || text.contains("end call") ||
            desc.contains("hang up") || text.contains("hang up") ||
            desc.contains("hangup") || text.contains("hangup") ||
            desc.contains("disconnect") || text.contains("disconnect") ||
            desc.contains("decline") || text.contains("decline") ||
            desc.contains("leave call") || text.contains("leave call") ||
            desc.contains("batalkan panggilan") || text.contains("batalkan panggilan") ||
            desc == "end" || text == "end" || desc == "tutup" || text == "tutup" ||
            desc == "akhiri" || text == "akhiri" || desc == "batal" || text == "batal"
        )

        if ((isEndId || isEndKeyword) && !isExcludedFeature) {
            val parent = node.parent
            if (parent != null) {
                // If this is the label text "Akhiri", look for the sibling red ImageView or clickable view
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling != node && (sibling.isClickable ||
                            sibling.className?.toString()?.contains("ImageView") == true ||
                            sibling.className?.toString()?.contains("Button") == true)) {
                        return sibling
                    }
                }
                if (parent.isClickable) return parent
            }
            if (node.isClickable) return node
            return node
        }

        // Geometric fallback candidate: round button in bottom call area
        val isBottomCallBtn = isInBottomCallArea && rect.centerY() in (screenHeight * 0.60f).toInt()..(screenHeight - 50) &&
                (rect.centerX() > (screenWidth * 0.60f) || rect.centerX() in (screenWidth * 0.35f).toInt()..(screenWidth * 0.65f).toInt()) &&
                rect.width() in 40..350 && rect.height() in 40..350 &&
                !isExcludedFeature &&
                (node.isClickable || node.parent?.isClickable == true ||
                 node.className?.toString()?.contains("Button", ignoreCase = true) == true ||
                 node.className?.toString()?.contains("ImageView", ignoreCase = true) == true)

        // Recurse down children first to find specific text/id match
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWhatsAppEndCallButton(child, screenWidth, screenHeight)
            if (found != null) return found
        }

        // If no child matched specific ID/text, use geometric candidate if applicable
        if (isBottomCallBtn) {
            if (node.isClickable) return node
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
            return node
        }

        return null
    }
}
