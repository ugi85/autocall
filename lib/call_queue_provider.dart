import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'models/call_item.dart';
import 'native_bridge.dart';

/// CallQueueProvider manages the queue state and coordinates the sequential
/// autocall loop with safe delays between calls.
class CallQueueProvider with ChangeNotifier {
  final NativeBridge _nativeBridge = NativeBridge.instance;

  List<CallItem> _queue = [];
  bool _isQueueRunning = false;
  bool _isPaused = false;
  String? _currentProcessingId;

  // Cooldown delay configuration (5 - 8 seconds as per requirements)
  int _cooldownTotalSeconds = 6;
  int _cooldownSecondsRemaining = 0;
  Timer? _cooldownTimer;

  StreamSubscription<Map<String, dynamic>>? _telephonySubscription;
  Map<String, dynamic> _permissionsStatus = {};
  String _backendUrl = 'https://your-api-domain.com/api/v1/recordings/upload';

  // Call Timeout Configuration (Auto-hangup jika tidak diangkat, sibuk, atau tidak aktif)
  int _callTimeoutSeconds = 25; // Default 25 detik
  int _callSecondsElapsed = 0;
  bool _isCallTimerRunning = false;
  Timer? _callTimeoutTimer;
  bool _isAccessibilityEnabled = false;
  bool _canDrawOverlays = false;

  // Stored device recordings library (Vivo / Internal Storage)
  List<Map<String, dynamic>> _deviceRecordings = [];

  static const String _keyQueue = 'autocall_saved_queue_v1';
  static const String _keyTimeout = 'autocall_call_timeout_seconds';
  static const String _keyCooldown = 'autocall_cooldown_seconds';
  static const String _keyBackendUrl = 'autocall_backend_url';

  CallQueueProvider() {
    _loadPersistedData();
    _initTelephonyListener();
    checkPermissions();
    fetchDeviceRecordings();
  }

  // Getters
  List<CallItem> get queue => List.unmodifiable(_queue);
  bool get isQueueRunning => _isQueueRunning;
  bool get isPaused => _isPaused;
  String? get currentProcessingId => _currentProcessingId;
  int get cooldownSecondsRemaining => _cooldownSecondsRemaining;
  int get cooldownTotalSeconds => _cooldownTotalSeconds;
  bool get isInCooldown => _cooldownSecondsRemaining > 0;
  Map<String, dynamic> get permissionsStatus => _permissionsStatus;
  bool get isAccessibilityEnabled => _isAccessibilityEnabled;
  bool get canDrawOverlays => _canDrawOverlays;
  String get backendUrl => _backendUrl;

  int get callTimeoutSeconds => _callTimeoutSeconds;
  int get callSecondsElapsed => _callSecondsElapsed;
  int get callTimeoutRemaining =>
      (_callTimeoutSeconds - _callSecondsElapsed).clamp(0, _callTimeoutSeconds);
  bool get isCallTimerRunning => _isCallTimerRunning;
  List<Map<String, dynamic>> get deviceRecordings => _deviceRecordings;

  void setCallTimeoutSeconds(int seconds) {
    if (seconds >= 10 && seconds <= 90) {
      _callTimeoutSeconds = seconds;
      notifyListeners();
      _saveSettingsToStorage();
    }
  }

  /// Manually hangup active call programmatically
  Future<void> hangupCurrentCall() async {
    _cancelCallTimeoutTimer();
    await _nativeBridge.endCall();
  }

  /// Disable timeout for current call (e.g., if user is actively talking)
  void disableTimeoutForCurrentCall() {
    _cancelCallTimeoutTimer();
    _nativeBridge.pauseCallTimeout();
    notifyListeners();
  }

  Future<void> fetchDeviceRecordings() async {
    _deviceRecordings = await _nativeBridge.getAllRecordings();
    notifyListeners();
  }

  // Statistics Getters
  int get totalCount => _queue.length;
  int get pendingCount =>
      _queue.where((e) => e.status == CallStatus.pending).length;
  int get completedCount =>
      _queue.where((e) => e.status == CallStatus.completed).length;
  int get failedCount =>
      _queue.where((e) => e.status == CallStatus.failedOrBusy).length;
  int get uploadedCount =>
      _queue.where((e) => e.uploadStatus == UploadStatus.uploaded).length;

  Future<void> _loadPersistedData() async {
    try {
      final prefs = await SharedPreferences.getInstance();

      // Load timeout
      if (prefs.containsKey(_keyTimeout)) {
        _callTimeoutSeconds = prefs.getInt(_keyTimeout) ?? 25;
      }

      // Load cooldown
      if (prefs.containsKey(_keyCooldown)) {
        _cooldownTotalSeconds = prefs.getInt(_keyCooldown) ?? 6;
      }

      // Load backend URL
      if (prefs.containsKey(_keyBackendUrl)) {
        final savedUrl = prefs.getString(_keyBackendUrl);
        if (savedUrl != null && savedUrl.isNotEmpty) {
          _backendUrl = savedUrl;
          await _nativeBridge.setBackendUrl(savedUrl);
        }
      }

      // Load queue
      final jsonString = prefs.getString(_keyQueue);
      if (jsonString != null && jsonString.isNotEmpty) {
        final List<dynamic> decoded = jsonDecode(jsonString);
        _queue = decoded
            .map((e) => CallItem.fromMap(Map<String, dynamic>.from(e as Map)))
            .toList();

        // Safety: reset in-flight statuses to pending on startup
        for (int i = 0; i < _queue.length; i++) {
          if (_queue[i].status == CallStatus.dialing ||
              _queue[i].status == CallStatus.inCall) {
            _queue[i] = _queue[i].copyWith(status: CallStatus.pending);
          }
        }
      } else {
        _queue = [];
      }
      notifyListeners();
    } catch (e) {
      debugPrint('[CallQueueProvider] Failed to load persisted data: $e');
    }
  }

  Future<void> _saveQueueToStorage() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final encoded = jsonEncode(_queue.map((item) => item.toMap()).toList());
      await prefs.setString(_keyQueue, encoded);
    } catch (e) {
      debugPrint('[CallQueueProvider] Failed to save queue to storage: $e');
    }
  }

  Future<void> _saveSettingsToStorage() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt(_keyTimeout, _callTimeoutSeconds);
      await prefs.setInt(_keyCooldown, _cooldownTotalSeconds);
      await prefs.setString(_keyBackendUrl, _backendUrl);
    } catch (e) {
      debugPrint('[CallQueueProvider] Failed to save settings to storage: $e');
    }
  }

  /// Listens to native telephony and recording upload events
  void _initTelephonyListener() {
    _telephonySubscription = _nativeBridge.telephonyEvents.listen(
      (event) {
        final eventType = event['event'] as String?;
        if (eventType == null) return;

        switch (eventType) {
          case 'CALL_STARTED':
            _handleCallStarted(event);
            break;
          case 'CALL_STATE_CHANGED':
            _handleCallStateChanged(event);
            break;
          case 'CALL_ANSWERED':
            _handleCallAnswered(event);
            break;
          case 'CALL_COMPLETED':
            _handleCallCompleted(event);
            break;
          case 'RECORDING_UPLOAD_STATUS':
            _handleRecordingUploadStatus(event);
            break;
        }
      },
      onError: (dynamic error) {
        // ignore: avoid_print
        print('[CallQueueProvider] Event stream error: $error');
      },
    );
  }

  void _handleCallStarted(Map<String, dynamic> event) {
    if (_currentProcessingId != null) {
      _updateItem(_currentProcessingId!, (item) {
        return item.copyWith(status: CallStatus.dialing);
      });
    }
  }

  void _handleCallStateChanged(Map<String, dynamic> event) {
    final state = event['state'] as String?;
    if (_currentProcessingId != null && state == 'OFFHOOK') {
      _updateItem(_currentProcessingId!, (item) {
        return item.copyWith(status: CallStatus.dialing);
      });
    }
  }

  void _handleCallAnswered(Map<String, dynamic> event) {
    _cancelCallTimeoutTimer();
    _isCallTimerRunning = false;
    if (_currentProcessingId != null) {
      _updateItem(_currentProcessingId!, (item) {
        return item.copyWith(
          status: CallStatus.inCall,
          uploadMessage: 'Sedang berbicara...',
        );
      });
    }
    notifyListeners();
  }

  void _handleCallCompleted(Map<String, dynamic> event) {
    final phoneNumber = event['phoneNumber'] as String? ?? '';
    final duration = (event['duration'] as num?)?.toInt() ?? 0;
    final timestampMillis = (event['timestamp'] as num?)?.toInt() ?? 0;
    final callType = event['callType'] as String? ?? 'OUTGOING';
    final statusString = event['status'] as String? ?? 'COMPLETED';

    final callTimestamp = timestampMillis > 0
        ? DateTime.fromMillisecondsSinceEpoch(timestampMillis)
        : DateTime.now();

    // Match either by active processing ID or phone number
    String? targetId = _currentProcessingId;
    if (targetId == null) {
      final match = _queue.cast<CallItem?>().firstWhere(
            (e) =>
                e != null &&
                (e.phoneNumber.replaceAll(RegExp(r'[^0-9]'), '') ==
                    phoneNumber.replaceAll(RegExp(r'[^0-9]'), '')),
            orElse: () => null,
          );
      targetId = match?.id;
    }

    final recordingPath = event['recordingPath'] as String? ?? '';

    if (targetId != null) {
      final isCallSuccess = duration > 0 && statusString == 'COMPLETED';

      _updateItem(targetId, (item) {
        return item.copyWith(
          status: isCallSuccess
              ? CallStatus.completed
              : CallStatus.failedOrBusy,
          durationSeconds: duration,
          callTimestamp: callTimestamp,
          callType: callType,
          recordingFilePath: recordingPath.isNotEmpty ? recordingPath : item.recordingFilePath,
          uploadStatus: isCallSuccess
              ? (recordingPath.isNotEmpty ? UploadStatus.waitingRecording : UploadStatus.idle)
              : UploadStatus.idle,
          uploadMessage: isCallSuccess
              ? (recordingPath.isNotEmpty ? 'Rekaman lokal ditemukan' : 'Menunggu rekaman...')
              : 'Panggilan tidak terjawab / sibuk',
        );
      });
      // Also refresh the device recordings library
      fetchDeviceRecordings();
    }

    _cancelCallTimeoutTimer();
    _currentProcessingId = null;

    // Trigger sequential autocall loop if queue is running
    if (_isQueueRunning && !_isPaused) {
      _startSafeCooldownTimer();
    } else {
      notifyListeners();
    }
  }

  void _handleRecordingUploadStatus(Map<String, dynamic> event) {
    final status = event['status'] as String? ?? '';
    final phone = event['phoneNumber'] as String? ?? '';
    final message = event['message'] as String? ?? '';
    final filePath = event['filePath'] as String? ?? '';

    // Find the call item
    final targetIndex = _queue.indexWhere((e) {
      final normalizedA = e.phoneNumber.replaceAll(RegExp(r'[^0-9]'), '');
      final normalizedB = phone.replaceAll(RegExp(r'[^0-9]'), '');
      return normalizedA.isNotEmpty &&
          normalizedB.isNotEmpty &&
          (normalizedA.endsWith(normalizedB) || normalizedB.endsWith(normalizedA));
    });

    if (targetIndex != -1) {
      final item = _queue[targetIndex];
      UploadStatus newUploadStatus = item.uploadStatus;

      switch (status) {
        case 'UPLOADING':
          newUploadStatus = UploadStatus.uploading;
          break;
        case 'SUCCESS':
          newUploadStatus = UploadStatus.uploaded;
          break;
        case 'FAILED':
          newUploadStatus = UploadStatus.failed;
          break;
      }

      _queue[targetIndex] = item.copyWith(
        uploadStatus: newUploadStatus,
        uploadMessage: message,
        recordingFilePath: filePath.isNotEmpty ? filePath : item.recordingFilePath,
      );
      notifyListeners();
      _saveQueueToStorage();
    }
  }

  // =================== AUTOCALL QUEUE CONTROLLER ===================

  /// Starts or resumes the sequential autocall queue
  Future<void> startQueue() async {
    final hasPerms = await checkPermissions();
    if (hasPerms['CALL_PHONE'] != true) {
      await _nativeBridge.requestPermissions();
      await checkPermissions();
    }

    _isQueueRunning = true;
    _isPaused = false;
    notifyListeners();

    _dialNextPendingCall();
  }

  /// Pauses the autocall queue
  void pauseQueue() {
    _isPaused = true;
    _cancelCooldownTimer();
    _cancelCallTimeoutTimer();
    notifyListeners();
  }

  /// Resumes the paused queue
  void resumeQueue() {
    _isPaused = false;
    notifyListeners();
    _dialNextPendingCall();
  }

  /// Stops the queue entirely
  void stopQueue() {
    _isQueueRunning = false;
    _isPaused = false;
    _currentProcessingId = null;
    _cancelCooldownTimer();
    _cancelCallTimeoutTimer();
    notifyListeners();
  }

  /// Manually skip the cooldown delay to immediately dial the next number
  void skipCooldown() {
    _cancelCooldownTimer();
    if (_isQueueRunning && !_isPaused) {
      _dialNextPendingCall();
    }
  }

  /// Internal mechanism to pick and dial the next PENDING call
  Future<void> _dialNextPendingCall() async {
    if (!_isQueueRunning || _isPaused) return;

    // Pick first pending item
    final nextIndex = _queue.indexWhere((e) => e.status == CallStatus.pending);

    if (nextIndex == -1) {
      // Queue exhausted
      _isQueueRunning = false;
      _currentProcessingId = null;
      _cancelCooldownTimer();
      _cancelCallTimeoutTimer();
      notifyListeners();
      return;
    }

    final targetItem = _queue[nextIndex];
    _currentProcessingId = targetItem.id;

    _queue[nextIndex] = targetItem.copyWith(status: CallStatus.dialing);
    notifyListeners();

    // Start auto-hangup timeout timer (e.g. 25s if unanswered, busy, or inactive)
    _startCallTimeoutTimer();

    final dialSuccess = await _nativeBridge.startCall(
      targetItem.phoneNumber,
      name: targetItem.name,
      timeoutSeconds: _callTimeoutSeconds,
    );

    if (!dialSuccess) {
      _cancelCallTimeoutTimer();
      // Could not initiate call (e.g., missing permission)
      _queue[nextIndex] = targetItem.copyWith(
        status: CallStatus.failedOrBusy,
        uploadMessage: 'Gagal inisialisasi dial Intent.ACTION_CALL',
      );
      _currentProcessingId = null;
      notifyListeners();

      // Proceed to next after short delay
      if (_isQueueRunning && !_isPaused) {
        _startSafeCooldownTimer();
      }
    }
  }

  /// Starts the auto-hangup timer for unanswered, busy, or inactive numbers.
  void _startCallTimeoutTimer() {
    _cancelCallTimeoutTimer();
    _callSecondsElapsed = 0;
    _isCallTimerRunning = true;
    notifyListeners();

    _callTimeoutTimer = Timer.periodic(const Duration(seconds: 1), (timer) async {
      if (_currentProcessingId == null || !_isQueueRunning) {
        timer.cancel();
        _isCallTimerRunning = false;
        notifyListeners();
        return;
      }

      // Check if call has been answered / in active conversation
      final currentItem = _queue.cast<CallItem?>().firstWhere(
            (e) => e?.id == _currentProcessingId,
            orElse: () => null,
          );
      if (currentItem?.status == CallStatus.inCall) {
        timer.cancel();
        _isCallTimerRunning = false;
        notifyListeners();
        return;
      }

      _callSecondsElapsed++;
      notifyListeners();

      // Check if timeout reached without remote party ending or answering
      if (_callSecondsElapsed >= _callTimeoutSeconds) {
        timer.cancel();
        _isCallTimerRunning = false;
        notifyListeners();

        // Automatically hang up the call on native Android!
        await _nativeBridge.endCall();

        // Safety fallback: If dialer doesn't dispatch IDLE within 3s, force advance
        Future.delayed(const Duration(milliseconds: 3000), () {
          if (_currentProcessingId != null) {
            _handleCallCompleted({
              'phoneNumber': '',
              'duration': 0,
              'timestamp': DateTime.now().millisecondsSinceEpoch,
              'callType': 'TIMEOUT / TIDAK AKTIF',
              'status': 'FAILED_OR_BUSY',
              'recordingPath': '',
            });
          }
        });
      }
    });
  }

  void _cancelCallTimeoutTimer() {
    _callTimeoutTimer?.cancel();
    _callTimeoutTimer = null;
    _isCallTimerRunning = false;
    _callSecondsElapsed = 0;
  }

  /// Starts a safe delay cooldown (5-8 seconds) with ticking countdown timer
  void _startSafeCooldownTimer() {
    _cancelCooldownTimer();
    _cooldownSecondsRemaining = _cooldownTotalSeconds;
    notifyListeners();

    _cooldownTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (!_isQueueRunning || _isPaused) {
        timer.cancel();
        _cooldownSecondsRemaining = 0;
        notifyListeners();
        return;
      }

      _cooldownSecondsRemaining--;
      notifyListeners();

      if (_cooldownSecondsRemaining <= 0) {
        timer.cancel();
        _dialNextPendingCall();
      }
    });
  }

  void _cancelCooldownTimer() {
    _cooldownTimer?.cancel();
    _cooldownTimer = null;
    _cooldownSecondsRemaining = 0;
  }

  // =================== QUEUE MANAGEMENT ACTIONS ===================

  void addNumber(String phoneNumber, [String name = '']) {
    final clean = phoneNumber.trim();
    if (clean.isEmpty) return;

    _queue.add(
      CallItem(
        id: DateTime.now().microsecondsSinceEpoch.toString(),
        phoneNumber: clean,
        name: name.trim().isEmpty ? 'Kontak ${_queue.length + 1}' : name.trim(),
        status: CallStatus.pending,
      ),
    );
    notifyListeners();
    _saveQueueToStorage();
  }

  void addBatchNumbers(List<String> rawLines) {
    for (final raw in rawLines) {
      final trimmed = raw.trim();
      if (trimmed.isEmpty) continue;

      // Check if comma or semicolon separated: "081234, Budi"
      final parts = trimmed.split(RegExp(r'[,;]'));
      if (parts.length >= 2) {
        final clean = parts[0].trim();
        final label = parts[1].trim();
        if (clean.isNotEmpty) {
          _queue.add(
            CallItem(
              id: DateTime.now().microsecondsSinceEpoch.toString(),
              phoneNumber: clean,
              name: label.isEmpty ? 'Kontak ${_queue.length + 1}' : label,
              status: CallStatus.pending,
            ),
          );
        }
      } else {
        final clean = trimmed.trim();
        if (clean.isNotEmpty) {
          _queue.add(
            CallItem(
              id: DateTime.now().microsecondsSinceEpoch.toString(),
              phoneNumber: clean,
              name: 'Kontak ${_queue.length + 1}',
              status: CallStatus.pending,
            ),
          );
        }
      }
    }
    notifyListeners();
    _saveQueueToStorage();
  }

  void removeNumber(String id) {
    _queue.removeWhere((item) => item.id == id);
    if (_currentProcessingId == id) {
      _currentProcessingId = null;
    }
    notifyListeners();
    _saveQueueToStorage();
  }

  void requeueCall(String id) {
    _updateItem(id, (item) {
      return item.copyWith(
        status: CallStatus.pending,
        uploadStatus: UploadStatus.idle,
        durationSeconds: 0,
        callTimestamp: null,
        callType: null,
        uploadMessage: null,
        retryCount: item.retryCount + 1,
      );
    });
  }

  void requeueAllFailed() {
    for (int i = 0; i < _queue.length; i++) {
      if (_queue[i].status == CallStatus.failedOrBusy) {
        _queue[i] = _queue[i].copyWith(
          status: CallStatus.pending,
          uploadStatus: UploadStatus.idle,
          durationSeconds: 0,
          callTimestamp: null,
          callType: null,
          uploadMessage: null,
          retryCount: _queue[i].retryCount + 1,
        );
      }
    }
    notifyListeners();
    _saveQueueToStorage();
  }

  void clearCompleted() {
    _queue.removeWhere((item) => item.status == CallStatus.completed);
    notifyListeners();
    _saveQueueToStorage();
  }

  void clearQueue() {
    stopQueue();
    _queue.clear();
    notifyListeners();
    _saveQueueToStorage();
  }

  void setCooldownSeconds(int seconds) {
    if (seconds >= 3 && seconds <= 30) {
      _cooldownTotalSeconds = seconds;
      notifyListeners();
      _saveSettingsToStorage();
    }
  }

  Future<void> setBackendUrl(String url) async {
    final clean = url.trim();
    if (clean.isNotEmpty) {
      _backendUrl = clean;
      await _nativeBridge.setBackendUrl(clean);
      notifyListeners();
      _saveSettingsToStorage();
    }
  }

  // =================== PERMISSIONS & ACCESSIBILITY ===================

  Future<Map<String, dynamic>> checkPermissions() async {
    _permissionsStatus = await _nativeBridge.checkPermissions();
    _isAccessibilityEnabled = await _nativeBridge.isAccessibilityEnabled();
    _canDrawOverlays = await _nativeBridge.canDrawOverlays();
    notifyListeners();
    return _permissionsStatus;
  }

  Future<void> requestRuntimePermissions() async {
    await _nativeBridge.requestPermissions();
    await checkPermissions();
  }

  Future<void> requestAllFilesAccess() async {
    await _nativeBridge.requestAllFilesAccess();
    await checkPermissions();
  }

  Future<void> requestIgnoreBatteryOptimizations() async {
    await _nativeBridge.requestIgnoreBatteryOptimizations();
    await checkPermissions();
  }

  Future<void> openAccessibilitySettings() async {
    await _nativeBridge.openAccessibilitySettings();
  }

  Future<void> requestOverlayPermission() async {
    await _nativeBridge.requestOverlayPermission();
  }

  void _updateItem(String id, CallItem Function(CallItem) updater) {
    final index = _queue.indexWhere((e) => e.id == id);
    if (index != -1) {
      _queue[index] = updater(_queue[index]);
      notifyListeners();
      _saveQueueToStorage();
    }
  }

  @override
  void dispose() {
    _cancelCooldownTimer();
    _cancelCallTimeoutTimer();
    _telephonySubscription?.cancel();
    super.dispose();
  }
}
