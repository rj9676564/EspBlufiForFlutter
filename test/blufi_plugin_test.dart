import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:esp_blufi_for_flutter/esp_blufi_for_flutter.dart';

void main() {
  const MethodChannel channel = MethodChannel('esp_blufi_for_flutter');

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
      return '42';
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('gets platform version from method channel', () async {
    expect(await BlufiPlugin.instance.platformVersion, '42');
  });
}
