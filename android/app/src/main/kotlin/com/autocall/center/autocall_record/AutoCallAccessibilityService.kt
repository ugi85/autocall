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
         * Checks if the active call on OEM InCallUI has been answered (timer is running / connected).
         */
        fun checkIsCallConnected(): Boolean {
            return instance?.isCallConnected() ?: false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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

            // If foreground service is running and countdown is not yet paused,
            // monitor if the remote party answered and call timer started ticking!
            if (AutoCallForegroundService.isRunning && !AutoCallForegroundService.isTimeoutPaused) {
                val source = event.source
                if (source != null && hasConnectedCallIndicator(source)) {
                    Log.i(TAG, "Call answered detected via accessibility event in $pkg! Halting timeout.")
                    CallManager.instance?.notifyCallAnswered()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error processing accessibility event: ${e.localizedMessage}")
        }
    }

    /**
     * Actively inspects dialer windows to determine if active call conversation
     * is underway (chronometer is running or connected status is shown).
     */
    fun isCallConnected(): Boolean {
        try {
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
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (!isExcludedPackage(pkg) && hasConnectedCallIndicator(activeRoot)) {
                    return true
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error checking isCallConnected: ${e.localizedMessage}")
        }
        return false
    }

    private fun hasConnectedCallIndicator(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val combined = "$text $desc".trim()

        // If the node specifically indicates still dialing / ringing, it's not connected yet
        val lowerCombined = combined.lowercase()
        val isDialing = lowerCombined.contains("memanggil") ||
                lowerCombined.contains("dialing") ||
                lowerCombined.contains("calling") ||
                lowerCombined.contains("menghubungi")

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
}
