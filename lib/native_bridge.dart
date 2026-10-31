import 'dart:async';
import 'package:flutter/services.dart';

/// NativeBridge manages two-way communication between Flutter and Android Native
/// via MethodChannel and EventChannel.
class NativeBridge {
  static const MethodChannel _methodChannel =
      MethodChannel('com.autocall.center/telephony');
  static const EventChannel _eventChannel =
      EventChannel('com.autocall.center/telephony_events');

  // Singleton instance
  static final NativeBridge instance = NativeBridge._internal();
  NativeBridge._internal();

  Stream<Map<String, dynamic>>? _eventsStream;

  /// Stream of native events:
  /// - CALL_STARTED: { event, phoneNumber, timestamp }
  /// - CALL_STATE_CHANGED: { event, state: "OFFHOOK"|"RINGING"|"IDLE", phoneNumber }
  /// - CALL_COMPLETED: { event, phoneNumber, duration, timestamp, callType, status }
  /// - RECORDING_UPLOAD_STATUS: { event, status: "UPLOADING"|"SUCCESS"|"FAILED", phoneNumber, message, filePath }
  Stream<Map<String, dynamic>> get telephonyEvents {
    _eventsStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((dynamic event) => Map<String, dynamic>.from(event as Map));
    return _eventsStream!;
  }

  /// Triggers an outgoing phone call directly via native Intent.ACTION_CALL (GSM)
  /// or WhatsApp VoIP call, starting native foreground service and floating overlay.
  Future<bool> startCall(
    String phoneNumber, {
    String name = '',
    int timeoutSeconds = 25,
    String callMode = 'GSM',
  }) async {
    try {
      final bool? result = await _methodChannel.invokeMethod<bool>(
        'startCall',
        {
          'phoneNumber': phoneNumber,
          'name': name,
          'timeoutSeconds': timeoutSeconds,
          'callMode': callMode,
        },
      );
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] startCall error: ${e.message}');
      return false;
    }
  }

  /// Terminates active/ringing call programmatically (for timeout on busy/unanswered/inactive calls)
  Future<bool> endCall() async {
    try {
      final bool? result = await _methodChannel.invokeMethod<bool>('endCall');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] endCall error: ${e.message}');
      return false;
    }
  }

  /// Pauses the auto-close countdown (e.g., if agent answers and talks)
  Future<bool> pauseCallTimeout() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('pauseCallTimeout');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] pauseCallTimeout error: ${e.message}');
      return false;
    }
  }

  /// Checks if Overlay permission (SYSTEM_ALERT_WINDOW) is granted
  Future<bool> canDrawOverlays() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('canDrawOverlays');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] canDrawOverlays error: ${e.message}');
      return false;
    }
  }

  /// Opens system settings to grant Overlay permission
  Future<bool> requestOverlayPermission() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('requestOverlayPermission');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] requestOverlayPermission error: ${e.message}');
      return false;
    }
  }

  /// Checks if AutoCall Accessibility Service is enabled in system settings
  Future<bool> isAccessibilityEnabled() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('isAccessibilityEnabled');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] isAccessibilityEnabled error: ${e.message}');
      return false;
    }
  }

  /// Opens Android System Accessibility Settings screen
  Future<bool> openAccessibilitySettings() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('openAccessibilitySettings');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] openAccessibilitySettings error: ${e.message}');
      return false;
    }
  }

  /// Scans internal storage for all saved call recordings (Vivo, Samsung, etc.)
  Future<List<Map<String, dynamic>>> getAllRecordings() async {
    try {
      final List<dynamic>? list =
          await _methodChannel.invokeMethod<List<dynamic>>('getAllRecordings');
      if (list != null) {
        return list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] getAllRecordings error: ${e.message}');
    }
    return [];
  }

  /// Checks whether required telephony and storage permissions are granted
  Future<Map<String, dynamic>> checkPermissions() async {
    try {
      final Map<dynamic, dynamic>? result =
          await _methodChannel.invokeMethod<Map<dynamic, dynamic>>('checkPermissions');
      if (result != null) {
        return Map<String, dynamic>.from(result);
      }
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] checkPermissions error: ${e.message}');
    }
    return {
      'CALL_PHONE': false,
      'READ_PHONE_STATE': false,
      'READ_CALL_LOG': false,
      'MANAGE_EXTERNAL_STORAGE': false,
      'IGNORE_BATTERY_OPTIMIZATIONS': false,
      'ALL_GRANTED': false,
    };
  }

  /// Triggers standard Android runtime permissions dialog
  Future<bool> requestPermissions() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('requestPermissions');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] requestPermissions error: ${e.message}');
      return false;
    }
  }

  /// Opens Android 11+ (API 30+) All Files Access Settings
  Future<bool> requestAllFilesAccess() async {
    try {
      final bool? result =
          await _methodChannel.invokeMethod<bool>('requestAllFilesAccess');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] requestAllFilesAccess error: ${e.message}');
      return false;
    }
  }

  /// Opens Battery Optimization whitelist dialog
  Future<bool> requestIgnoreBatteryOptimizations() async {
    try {
      final bool? result = await _methodChannel
          .invokeMethod<bool>('requestIgnoreBatteryOptimizations');
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] requestIgnoreBatteryOptimizations error: ${e.message}');
      return false;
    }
  }

  /// Configures dynamic backend API endpoint for recording uploads
  Future<bool> setBackendUrl(String url) async {
    try {
      final bool? result = await _methodChannel.invokeMethod<bool>(
        'setBackendUrl',
        {'url': url},
      );
      return result ?? false;
    } on PlatformException catch (e) {
      // ignore: avoid_print
      print('[NativeBridge] setBackendUrl error: ${e.message}');
      return false;
    }
  }
}
