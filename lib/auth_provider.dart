import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// Provider yang mengelola autentikasi pengguna ke Google Apps Script Web App.
/// Catatan: Sesi login tidak disimpan secara permanen di memori HP sesuai instruksi,
/// sehingga aplikasi akan selalu meminta login setiap kali dibuka ulang.
class AuthProvider with ChangeNotifier {
  static const String defaultGoogleAppsScriptUrl =
      'https://script.google.com/macros/s/AKfycbw-3o-BK7ANO-lm1Ri39MNqmJHP6wt783U-swYptte_lkqIzNTR789YTfwLqpakjjZc/exec';

  static const String _keyLoginApiUrl = 'autocall_login_api_url';
  static const String _keySavedUsername = 'autocall_last_login_username';

  bool _isLoggedIn = false;
  Map<String, dynamic>? _currentUser;
  bool _isLoading = false;
  String? _errorMessage;
  String _loginApiUrl = defaultGoogleAppsScriptUrl;
  String _savedUsername = '';

  AuthProvider() {
    _loadPersistedSettings();
  }

  // Getters
  bool get isLoggedIn => _isLoggedIn;
  Map<String, dynamic>? get currentUser => _currentUser;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  String get loginApiUrl => _loginApiUrl;
  String get savedUsername => _savedUsername;

  String get displayName {
    if (_currentUser == null) return '';
    return _currentUser!['name']?.toString() ??
        _currentUser!['username']?.toString() ??
        'User';
  }

  String get userRole {
    if (_currentUser == null) return '';
    return _currentUser!['role']?.toString() ?? 'Operator';
  }

  /// Memuat preferensi URL dan username yang tersimpan
  Future<void> _loadPersistedSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedUrl = prefs.getString(_keyLoginApiUrl);
      if (savedUrl != null && savedUrl.trim().isNotEmpty) {
        _loginApiUrl = savedUrl.trim();
      }

      final savedUser = prefs.getString(_keySavedUsername);
      if (savedUser != null && savedUser.trim().isNotEmpty) {
        _savedUsername = savedUser.trim();
      }
      notifyListeners();
    } catch (e) {
      debugPrint('AuthProvider _loadPersistedSettings error: $e');
    }
  }

  /// Mengubah URL Google Apps Script untuk login
  Future<void> setLoginApiUrl(String url) async {
    final cleanUrl = url.trim();
    if (cleanUrl.isNotEmpty && cleanUrl.startsWith('http')) {
      _loginApiUrl = cleanUrl;
      notifyListeners();
      try {
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_keyLoginApiUrl, _loginApiUrl);
      } catch (e) {
        debugPrint('AuthProvider setLoginApiUrl error: $e');
      }
    }
  }

  /// Menghapus pesan error yang aktif
  void clearError() {
    if (_errorMessage != null) {
      _errorMessage = null;
      notifyListeners();
    }
  }

  /// Melakukan proses login ke Google Apps Script Web App
  Future<bool> login({
    required String username,
    required String password,
    String? customUrl,
  }) async {
    final cleanUsername = username.trim();
    final cleanPassword = password.trim();

    if (cleanUsername.isEmpty || cleanPassword.isEmpty) {
      _errorMessage = 'Username dan kata sandi wajib diisi.';
      notifyListeners();
      return false;
    }

    final targetUrl = (customUrl != null && customUrl.trim().isNotEmpty)
        ? customUrl.trim()
        : _loginApiUrl;

    if (!targetUrl.startsWith('http')) {
      _errorMessage = 'URL API server tidak valid (harus diawali http/https).';
      notifyListeners();
      return false;
    }

    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      // Menyusun URL dengan query parameters untuk Google Apps Script
      final baseUri = Uri.parse(targetUrl);
      final queryParams = Map<String, String>.from(baseUri.queryParameters);
      queryParams['action'] = 'login';
      queryParams['username'] = cleanUsername;
      queryParams['password'] = cleanPassword;

      final requestUri = baseUri.replace(queryParameters: queryParams);

      final response = await http.get(
        requestUri,
        headers: {
          'Accept': 'application/json',
          'User-Agent': 'AutoCallApp/1.0',
        },
      ).timeout(const Duration(seconds: 15));

      if (response.statusCode != 200) {
        throw HttpException(
          'Server Google Apps Script merespon dengan status HTTP ${response.statusCode}',
        );
      }

      final dynamic decoded = jsonDecode(response.body);

      if (decoded is Map<String, dynamic>) {
        final status = (decoded['status'] ?? '').toString().toLowerCase();
        final isSuccess = decoded['success'] == true ||
            status == 'success' ||
            status == 'ok' ||
            decoded['code'] == 200;

        if (isSuccess) {
          _isLoggedIn = true;
          _currentUser = (decoded['user'] is Map<String, dynamic>)
              ? Map<String, dynamic>.from(decoded['user'] as Map)
              : {
                  'username': cleanUsername,
                  'name': cleanUsername,
                  'role': 'Operator',
                };
          _errorMessage = null;
          _savedUsername = cleanUsername;

          // Simpan username dan URL ke SharedPreferences (hanya username dan URL, sesi login tetap non-persisten)
          final prefs = await SharedPreferences.getInstance();
          await prefs.setString(_keySavedUsername, _savedUsername);
          if (targetUrl != _loginApiUrl) {
            _loginApiUrl = targetUrl;
            await prefs.setString(_keyLoginApiUrl, _loginApiUrl);
          }

          _isLoading = false;
          notifyListeners();
          return true;
        } else {
          _errorMessage = decoded['message']?.toString() ??
              'Username atau kata sandi tidak cocok.';
          _isLoading = false;
          notifyListeners();
          return false;
        }
      } else {
        throw const FormatException(
          'Format respon dari server bukan objek JSON status.',
        );
      }
    } on TimeoutException {
      _errorMessage =
          'Koneksi waktu habis (Timeout). Periksa koneksi internet Anda atau coba beberapa saat lagi.';
      _isLoading = false;
      notifyListeners();
      return false;
    } on SocketException catch (e) {
      _errorMessage =
          'Tidak dapat terhubung ke server Google Apps Script (${e.message}). Pastikan jaringan internet aktif.';
      _isLoading = false;
      notifyListeners();
      return false;
    } on FormatException {
      _errorMessage =
          'Respon dari Google Apps Script tidak valid. Pastikan Web App sudah dideploy dengan hak akses "Anyone" (Siapa saja).';
      _isLoading = false;
      notifyListeners();
      return false;
    } catch (e) {
      _errorMessage = 'Terjadi kesalahan saat login: $e';
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }

  /// Logout pengguna dan reset status login
  void logout() {
    _isLoggedIn = false;
    _currentUser = null;
    _errorMessage = null;
    notifyListeners();
  }
}
