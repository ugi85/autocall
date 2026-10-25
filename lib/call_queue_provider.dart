import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
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
  // Backend Upload API & Contacts Fetch API
  static const String defaultGoogleAppsScriptUrl =
      'https://script.google.com/macros/s/AKfycbw-3o-BK7ANO-lm1Ri39MNqmJHP6wt783U-swYptte_lkqIzNTR789YTfwLqpakjjZc/exec';

  String _backendUrl = 'https://your-api-domain.com/api/v1/recordings/upload';
  String _contactsApiUrl = defaultGoogleAppsScriptUrl;
  bool _isLoadingContactsFromApi = false;
  DateTime? _lastSyncTime;

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
  static const String _keyContactsApiUrl = 'autocall_contacts_api_url';

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
  String get contactsApiUrl => _contactsApiUrl;
  bool get isLoadingContactsFromApi => _isLoadingContactsFromApi;
  DateTime? get lastSyncTime => _lastSyncTime;

  int get callTimeoutSeconds => _callTimeoutSeconds;
  int get callSecondsElapsed => _callSecondsElapsed;
  int get callTimeoutRemaining =>
      (_callTimeoutSeconds - _callSecondsElapsed).clamp(0, _callTimeoutSeconds);
  bool get isCallTimerRunning => _isCallTimerRunning;
  List<Map<String, dynamic>> get deviceRecordings => _deviceRecordings;

  Future<int> syncContactsSilently() async {
    if (_isLoadingContactsFromApi) return 0;
    try {
      final count = await fetchContactsFromApi(
        clearExisting: false,
        isSilent: true,
      );
      _lastSyncTime = DateTime.now();
      notifyListeners();
      return count;
    } catch (e) {
      debugPrint('[CallQueueProvider] Silent sync error: $e');
      return 0;
    }
  }

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

      // Load contacts API URL
      if (prefs.containsKey(_keyContactsApiUrl)) {
        final savedContactsUrl = prefs.getString(_keyContactsApiUrl);
        if (savedContactsUrl != null && savedContactsUrl.isNotEmpty) {
          final sanitized = sanitizeApiUrl(savedContactsUrl);
          if (sanitized.isNotEmpty && !sanitized.contains('your-api-domain.com')) {
            _contactsApiUrl = sanitized;
            if (sanitized != savedContactsUrl) {
              _saveSettingsToStorage();
            }
          }
        }
      }

      // Fallback: jika belum ada URL kontak tersimpan, ambil dari login atau URL bawaan
      if (_contactsApiUrl.isEmpty) {
        final savedLoginUrl = prefs.getString('autocall_login_api_url');
        if (savedLoginUrl != null && savedLoginUrl.isNotEmpty) {
          _contactsApiUrl = savedLoginUrl;
        } else {
          _contactsApiUrl = defaultGoogleAppsScriptUrl;
        }
      }

      // If backend upload URL is still placeholder, auto-link to contacts API URL
      if ((_backendUrl.contains('your-api-domain.com') || _backendUrl.isEmpty) &&
          _contactsApiUrl.isNotEmpty) {
        _backendUrl = _contactsApiUrl;
        await _nativeBridge.setBackendUrl(_contactsApiUrl);
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

      // Sinkronisasi kontak awal jika antrean di HP masih kosong
      if (_queue.isEmpty && _contactsApiUrl.isNotEmpty) {
        syncContactsSilently();
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
      await prefs.setString(_keyContactsApiUrl, _contactsApiUrl);
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
          recordingFilePath: isCallSuccess
              ? (recordingPath.isNotEmpty ? recordingPath : item.recordingFilePath)
              : null,
          uploadStatus: isCallSuccess
              ? (recordingPath.isNotEmpty ? UploadStatus.waitingRecording : UploadStatus.idle)
              : UploadStatus.idle,
          uploadMessage: isCallSuccess
              ? (recordingPath.isNotEmpty ? 'Rekaman lokal ditemukan' : 'Menunggu rekaman...')
              : 'Panggilan tidak terjawab / sibuk',
        );
      });

      // Sinkronkan hasil panggilan secara realtime ke Google Spreadsheet (baik Terhubung maupun Gagal/Sibuk)
      final phone = _queue.firstWhere((e) => e.id == targetId).phoneNumber;
      _syncCallStatusToSpreadsheet(
        phoneNumber: phone,
        statusText: isCallSuccess
            ? 'TERHUBUNG (${duration}s)'
            : (duration > 0 ? 'GAGAL (${duration}s)' : 'TIDAK DIANGKAT / SIBUK'),
        duration: duration,
      );

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

  /// Cleans up accidentally merged URLs (e.g. if pasted on top of placeholder)
  static String sanitizeApiUrl(String rawUrl) {
    var url = rawUrl.trim();
    if (url.isEmpty) return '';

    // If pasted over placeholder and merged e.g. '.../execur-api-domain.com/api/v1/contacts'
    if (url.contains('script.google.com') && url.contains('/exec')) {
      final execIdx = url.indexOf('/exec');
      url = url.substring(0, execIdx + 5);
    } else if (url.contains('your-api-domain.com') && url.startsWith('http')) {
      url = url.replaceAll(RegExp(r'https?://your-api-domain\.com[^\s]*'), '').trim();
    }
    return url;
  }

  Future<void> setContactsApiUrl(String url) async {
    final clean = sanitizeApiUrl(url);
    _contactsApiUrl = clean;
    if ((_backendUrl.contains('your-api-domain.com') || _backendUrl.isEmpty) && clean.isNotEmpty) {
      _backendUrl = clean;
      await _nativeBridge.setBackendUrl(clean);
    }
    notifyListeners();
    _saveSettingsToStorage();
  }

  /// Sends call status update to Google Spreadsheet for unanswered / busy calls
  Future<void> _syncCallStatusToSpreadsheet({
    required String phoneNumber,
    required String statusText,
    required int duration,
  }) async {
    final targetUrl = _backendUrl.isNotEmpty && !_backendUrl.contains('your-api-domain.com')
        ? _backendUrl
        : _contactsApiUrl;

    if (targetUrl.isEmpty || !targetUrl.startsWith('http')) return;

    try {
      final payload = jsonEncode({
        'action': 'update_status',
        'phone': phoneNumber,
        'phoneNumber': phoneNumber,
        'phone_number': phoneNumber,
        'status': statusText,
        'call_duration': duration,
        'call_timestamp': DateTime.now().millisecondsSinceEpoch,
      });

      await http.post(
        Uri.parse(targetUrl),
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: payload,
      ).timeout(const Duration(seconds: 10));
    } catch (e) {
      debugPrint('[CallQueueProvider] POST update status failed, trying GET fallback: $e');
      try {
        final getUri = Uri.parse(targetUrl).replace(
          queryParameters: {
            'action': 'update_status',
            'phone': phoneNumber,
            'status': statusText,
            'duration': duration.toString(),
          },
        );
        await http.get(getUri).timeout(const Duration(seconds: 10));
      } catch (err) {
        debugPrint('[CallQueueProvider] Failed to sync status via GET fallback: $err');
      }
    }
  }

  /// Fetches contact list from external API database and loads them into the queue.
  /// Supports common JSON schemas:
  /// - Array of objects: [ {"phoneNumber": "...", "name": "..."}, ... ]
  /// - Nested in data/contacts/leads/results: { "data": [ ... ] }
  Future<int> fetchContactsFromApi({
    String? overrideUrl,
    bool clearExisting = false,
    bool isSilent = false,
  }) async {
    final rawUrl = overrideUrl?.trim().isNotEmpty == true
        ? overrideUrl!.trim()
        : _contactsApiUrl.trim();

    final targetUrl = sanitizeApiUrl(rawUrl);

    if (targetUrl.isEmpty) {
      throw Exception(
        'URL API belum diatur. Silakan masukkan URL API kontak di menu Pengaturan aplikasi.',
      );
    }

    if (targetUrl != _contactsApiUrl) {
      _contactsApiUrl = targetUrl;
      _saveSettingsToStorage();
    }

    if (!isSilent || _queue.isEmpty) {
      _isLoadingContactsFromApi = true;
      notifyListeners();
    }

    try {
      final response = await http.get(
        Uri.parse(targetUrl),
        headers: {
          'Accept': 'application/json',
          'User-Agent': 'AutoCallApp/1.0',
        },
      ).timeout(const Duration(seconds: 15));

      if (response.statusCode != 200) {
        throw Exception(
          'Server merespon dengan status HTTP ${response.statusCode}',
        );
      }

      final dynamic decoded = jsonDecode(response.body);
      List<dynamic> itemsList = [];

      if (decoded is List) {
        itemsList = decoded;
      } else if (decoded is Map<String, dynamic>) {
        if (decoded['data'] is List) {
          itemsList = decoded['data'] as List;
        } else if (decoded['contacts'] is List) {
          itemsList = decoded['contacts'] as List;
        } else if (decoded['leads'] is List) {
          itemsList = decoded['leads'] as List;
        } else if (decoded['results'] is List) {
          itemsList = decoded['results'] as List;
        } else if (decoded['items'] is List) {
          itemsList = decoded['items'] as List;
        }
      }

      if (itemsList.isEmpty) {
        _isLoadingContactsFromApi = false;
        notifyListeners();
        return 0;
      }

      if (clearExisting) {
        _queue.clear();
      }

      int syncedCount = 0;
      for (final raw in itemsList) {
        if (raw is! Map) continue;
        final map = Map<String, dynamic>.from(raw);

        // Normalize all keys to lowercase alphanumeric for robust case-insensitive extraction
        final normalized = <String, dynamic>{};
        for (final entry in map.entries) {
          final cleanKey = entry.key
              .toString()
              .toLowerCase()
              .replaceAll(RegExp(r'[^a-z0-9]'), '');
          normalized[cleanKey] = entry.value;
        }

        // Flexible phone number extraction
        final phone = (normalized['notlp'] ??
                normalized['notelp'] ??
                normalized['nohp'] ??
                normalized['phone'] ??
                normalized['phonenumber'] ??
                normalized['telepon'] ??
                normalized['telp'] ??
                normalized['nomor'] ??
                normalized['mobile'] ??
                normalized['contact'])
            ?.toString()
            .trim() ?? '';

        if (phone.isEmpty) continue;

        // Flexible name extraction
        final name = (normalized['name'] ??
                normalized['nama'] ??
                normalized['fullname'] ??
                normalized['customername'] ??
                normalized['customer'] ??
                normalized['client'] ??
                normalized['label'])
            ?.toString()
            .trim() ?? '';

        final id = (normalized['no'] ??
                normalized['id'] ??
                normalized['contactid'] ??
                normalized['leadid'])
            ?.toString() ??
            DateTime.now().microsecondsSinceEpoch.toString();

        // Deteksi status panggilan dari spreadsheet
        final rawStatus =
            (normalized['status'] ?? '').toString().toUpperCase();
        CallStatus parsedStatus = CallStatus.pending;
        if (rawStatus.contains('TERHUBUNG')) {
          parsedStatus = CallStatus.completed;
        } else if (rawStatus.contains('TIDAK DIANGKAT') ||
            rawStatus.contains('SIBUK') ||
            rawStatus.contains('GAGAL') ||
            rawStatus.contains('REJECT')) {
          parsedStatus = CallStatus.failedOrBusy;
        }

        if (clearExisting) {
          _queue.add(
            CallItem(
              id: id,
              phoneNumber: phone,
              name: name.isNotEmpty ? name : 'Kontak ${_queue.length + 1}',
              status: parsedStatus,
            ),
          );
          syncedCount++;
        } else {
          // Smart sync: cek apakah nomor sudah ada di queue
          final cleanPhone = phone.replaceAll(RegExp(r'[^0-9]'), '');
          final existingIdx = _queue.indexWhere((item) {
            final qPhone = item.phoneNumber.replaceAll(RegExp(r'[^0-9]'), '');
            return qPhone == cleanPhone ||
                (qPhone.isNotEmpty &&
                    cleanPhone.isNotEmpty &&
                    (qPhone.endsWith(cleanPhone) || cleanPhone.endsWith(qPhone)));
          });

          if (existingIdx != -1) {
            final existing = _queue[existingIdx];
            // Update nama dan status jika belum aktif dipanggil
            // PENTING: Jika kontak berstatus pending (misalnya dikembalikan ke antrean oleh user),
            // JANGAN timpa kembali ke status lama spreadsheet agar tidak "mental"!
            CallStatus updatedStatus = existing.status;
            if (existing.status != CallStatus.dialing &&
                existing.status != CallStatus.inCall) {
              if (existing.status != CallStatus.pending) {
                if (rawStatus.isEmpty) {
                  updatedStatus = CallStatus.pending;
                } else if (parsedStatus != CallStatus.pending) {
                  updatedStatus = parsedStatus;
                }
              }
            }

            _queue[existingIdx] = existing.copyWith(
              name: name.isNotEmpty ? name : existing.name,
              status: updatedStatus,
            );
            syncedCount++;
          } else {
            // Nomor baru dari spreadsheet: tambahkan ke antrean
            _queue.add(
              CallItem(
                id: id,
                phoneNumber: phone,
                name: name.isNotEmpty ? name : 'Kontak ${_queue.length + 1}',
                status: parsedStatus,
              ),
            );
            syncedCount++;
          }
        }
      }

      // Sinkronkan penghapusan: jika queue sedang diam (idle), bersihkan kontak pending yang sudah dihapus dari sheet
      if (!_isQueueRunning && !clearExisting && _queue.isNotEmpty) {
        final incomingPhones = itemsList.map((raw) {
          if (raw is! Map) return '';
          final map = Map<String, dynamic>.from(raw);
          final norm = <String, dynamic>{};
          for (final entry in map.entries) {
            norm[entry.key.toString().toLowerCase().replaceAll(RegExp(r'[^a-z0-9]'), '')] = entry.value;
          }
          final p = (norm['notlp'] ?? norm['notelp'] ?? norm['nohp'] ?? norm['phone'] ?? norm['nomor'] ?? '').toString();
          return p.replaceAll(RegExp(r'[^0-9]'), '');
        }).where((p) => p.isNotEmpty).toSet();

        _queue.removeWhere((item) {
          if (item.status != CallStatus.pending) return false;
          final clean = item.phoneNumber.replaceAll(RegExp(r'[^0-9]'), '');
          return !incomingPhones.any((p) => p == clean || p.endsWith(clean) || clean.endsWith(p));
        });
      }

      _lastSyncTime = DateTime.now();
      _saveQueueToStorage();
      _isLoadingContactsFromApi = false;
      notifyListeners();

      return syncedCount;
    } catch (e) {
      _isLoadingContactsFromApi = false;
      notifyListeners();
      if (!isSilent) {
        rethrow;
      }
      debugPrint('[CallQueueProvider] Silent fetch error: $e');
      return 0;
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
