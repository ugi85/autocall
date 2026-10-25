import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:autocall_record/auth_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('AuthProvider Tests', () {
    test('Initial state is not logged in and session is non-persistent', () {
      final auth = AuthProvider();
      expect(auth.isLoggedIn, false);
      expect(auth.currentUser, isNull);
      expect(auth.isLoading, false);
      expect(auth.errorMessage, isNull);
    });

    test('Validation fails when username or password is empty', () async {
      final auth = AuthProvider();
      final result = await auth.login(username: '', password: '');
      expect(result, false);
      expect(auth.isLoggedIn, false);
      expect(auth.errorMessage, isNotNull);
    });

    test('Successful login sets currentUser and isLoggedIn true', () async {
      final mockClient = MockClient((request) async {
        expect(request.url.queryParameters['action'], 'login');
        expect(request.url.queryParameters['username'], 'admin');
        expect(request.url.queryParameters['password'], 'admin123');

        return http.Response(
          jsonEncode({
            'status': 'success',
            'message': 'Login berhasil!',
            'user': {
              'username': 'admin',
              'name': 'Administrator Utama',
              'role': 'Admin',
            },
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      // Run with MockClient
      await http.runWithClient(() async {
        final auth = AuthProvider();
        final success = await auth.login(
          username: 'admin',
          password: 'admin123',
        );

        expect(success, true);
        expect(auth.isLoggedIn, true);
        expect(auth.displayName, 'Administrator Utama');
        expect(auth.userRole, 'Admin');
        expect(auth.errorMessage, isNull);
        expect(auth.savedUsername, 'admin');

        // Test logout
        auth.logout();
        expect(auth.isLoggedIn, false);
        expect(auth.currentUser, isNull);
      }, () => mockClient);
    });

    test('Failed login reflects server error message and remains logged out', () async {
      final mockClient = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'status': 'error',
            'message': 'Kata sandi salah.',
          }),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      await http.runWithClient(() async {
        final auth = AuthProvider();
        final success = await auth.login(
          username: 'admin',
          password: 'wrongpassword',
        );

        expect(success, false);
        expect(auth.isLoggedIn, false);
        expect(auth.errorMessage, 'Kata sandi salah.');
      }, () => mockClient);
    });
  });
}
