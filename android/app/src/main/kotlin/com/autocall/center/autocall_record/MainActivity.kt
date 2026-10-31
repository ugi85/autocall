package com.autocall.center.autocall_record

import android.content.pm.PackageManager
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity(), MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    companion object {
        const val METHOD_CHANNEL = "com.autocall.center/telephony"
        const val EVENT_CHANNEL = "com.autocall.center/telephony_events"
    }

    private lateinit var callManager: CallManager
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        callManager = CallManager(applicationContext)
        callManager.attachActivity(this)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel?.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startCall" -> {
                val phoneNumber = call.argument<String>("phoneNumber")
                val name = call.argument<String>("name") ?: ""
                val timeoutSeconds = call.argument<Int>("timeoutSeconds") ?: 25
                val callMode = call.argument<String>("callMode") ?: "GSM"
                if (phoneNumber.isNullOrBlank()) {
                    result.error("INVALID_ARGUMENT", "Phone number cannot be empty", null)
                } else {
                    val initiated = callManager.startCall(phoneNumber, name, timeoutSeconds, callMode)
                    result.success(initiated)
                }
            }

            "endCall" -> {
                val ended = callManager.endCall()
                result.success(ended)
            }

            "pauseCallTimeout" -> {
                callManager.pauseCallTimeout()
                result.success(true)
            }

            "canDrawOverlays" -> {
                val canDraw = callManager.canDrawOverlays()
                result.success(canDraw)
            }

            "requestOverlayPermission" -> {
                callManager.requestOverlayPermission()
                result.success(true)
            }

            "isAccessibilityEnabled" -> {
                val enabled = callManager.isAccessibilityEnabled()
                result.success(enabled)
            }

            "openAccessibilitySettings" -> {
                callManager.openAccessibilitySettings()
                result.success(true)
            }

            "getAllRecordings" -> {
                val recordings = callManager.getAllRecordings()
                result.success(recordings)
            }

            "checkPermissions" -> {
                val permissionsMap = callManager.checkPermissions()
                result.success(permissionsMap)
            }

            "requestPermissions" -> {
                pendingPermissionResult = result
                callManager.requestRuntimePermissions()
            }

            "requestAllFilesAccess" -> {
                callManager.requestAllFilesAccess()
                result.success(true)
            }

            "requestIgnoreBatteryOptimizations" -> {
                callManager.requestIgnoreBatteryOptimizations()
                result.success(true)
            }

            "setBackendUrl" -> {
                val url = call.argument<String>("url")
                if (!url.isNullOrBlank()) {
                    callManager.backendUploadUrl = url
                    result.success(true)
                } else {
                    result.error("INVALID_URL", "Backend URL cannot be empty", null)
                }
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CallManager.PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            pendingPermissionResult?.success(allGranted)
            pendingPermissionResult = null

            // Restart listener if permission granted
            if (allGranted) {
                callManager.startListening()
            }
        }
    }

    // EventChannel.StreamHandler implementation
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        callManager.setEventSink(events)
        callManager.startListening()
    }

    override fun onCancel(arguments: Any?) {
        callManager.setEventSink(null)
    }

    override fun onDestroy() {
        callManager.detachActivity()
        callManager.cleanUp()
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        super.onDestroy()
    }
}
