import 'dart:async';

import 'package:flutter/services.dart';

typedef ResultCallback = void Function(String? data);

class BlufiPlugin {
  final MethodChannel _channel = const MethodChannel('esp_blufi_for_flutter');
  final EventChannel _eventChannel = EventChannel('esp_blufi_for_flutter/state');

  BlufiPlugin._() {
    _channel.setMethodCallHandler(null);

    _eventChannel
        .receiveBroadcastStream()
        .listen(speechResultsHandler, onError: speechResultErrorHandler);
  }

  ResultCallback? _resultSuccessCallback;
  ResultCallback? _resultErrorCallback;

  static final BlufiPlugin _instance = BlufiPlugin._();
  static BlufiPlugin get instance => _instance;

  void onMessageReceived({ResultCallback? successCallback, ResultCallback? errorCallback}) {
    _resultSuccessCallback = successCallback;
    _resultErrorCallback = errorCallback;
  }

  Future<String?> get platformVersion async {
    final String? version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }

  Future<bool?> scanDeviceInfo({String? filterString}) async {
    final bool? isEnable =
        await _channel.invokeMethod('scanDeviceInfo', <String, dynamic>{'filter': filterString});
    return isEnable;
  }

  Future<void> stopScan() async {
    await _channel.invokeMethod('stopScan');
  }

  Future<void> connectPeripheral({String? peripheralAddress}) async {
    await _channel.invokeMethod('connectPeripheral', <String, dynamic>{'peripheral': peripheralAddress});
  }

  Future<void> requestCloseConnection() async {
    await _channel.invokeMethod('requestCloseConnection');
  }

  Future<void> negotiateSecurity() async {
    await _channel.invokeMethod('negotiateSecurity');
  }

  Future<void> requestDeviceVersion() async {
    await _channel.invokeMethod('requestDeviceVersion');
  }

  Future<void> configProvision({String? username, String? password}) async {
    await _channel.invokeMethod(
        'configProvision', <String, dynamic>{'username': username, 'password': password});
  }

  Future<void> requestDeviceStatus() async {
    await _channel.invokeMethod('requestDeviceStatus');
  }

  Future<void> requestDeviceScan() async {
    await _channel.invokeMethod('requestDeviceScan');
  }

  Future<void> postCustomData(String dataStr) async {
    await _channel.invokeMethod('postCustomData', <String, dynamic>{'custom_data': dataStr});
  }

  void speechResultsHandler(dynamic event) {
    _resultSuccessCallback?.call(event);
  }

  void speechResultErrorHandler(dynamic error) {
    _resultErrorCallback?.call(error);
  }
}
