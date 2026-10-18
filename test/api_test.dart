import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:autocall_record/call_queue_provider.dart';

class _AllowNetworkHttpOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? context) {
    return super.createHttpClient(context)
      ..badCertificateCallback = (cert, host, port) => true;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  HttpOverrides.global = _AllowNetworkHttpOverrides();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('com.autocall.center/telephony'),
      (MethodCall methodCall) async {
        if (methodCall.method == 'checkPermissions') {
          return {'ALL_GRANTED': true};
        }
        if (methodCall.method == 'getAllRecordings') {
          return [];
        }
        if (methodCall.method == 'isAccessibilityEnabled') {
          return true;
        }
        if (methodCall.method == 'canDrawOverlays') {
          return true;
        }
        return null;
      },
    );
  });

  test('CallQueueProvider parses Google Apps Script leads directly', () async {
    SharedPreferences.setMockInitialValues({});
    final provider = CallQueueProvider();

    await provider.setContactsApiUrl(
      'https://script.google.com/macros/s/AKfycbw-3o-BK7ANO-lm1Ri39MNqmJHP6wt783U-swYptte_lkqIzNTR789YTfwLqpakjjZc/exec',
    );
    final count = await provider.fetchContactsFromApi();

    expect(count, 5);
    expect(provider.queue.length, 5);

    expect(provider.queue[0].name, 'Bunda');
    expect(provider.queue[0].phoneNumber, '089521263565');

    expect(provider.queue[1].name, 'Alya');
    expect(provider.queue[1].phoneNumber, '089638833022');

    expect(provider.queue[2].name, 'Byu');
    expect(provider.queue[2].phoneNumber, '085157997349');

    expect(provider.queue[3].name, 'Nenek');
    expect(provider.queue[3].phoneNumber, '0895332270020');

    expect(provider.queue[4].name, 'Fitri');
    expect(provider.queue[4].phoneNumber, '08986899669');


  });
}
