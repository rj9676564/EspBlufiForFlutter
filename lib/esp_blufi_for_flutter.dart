import 'dart:async';

import 'package:flutter/services.dart';

typedef ResultCallback = void Function(String? data);

class BlufiPlugin {
  final MethodChannel? _channel = const MethodChannel('esp_blufi_for_flutter');
  final EventChannel _eventChannel = EventChannel('esp_blufi_for_flutter/state');

  BlufiPlugin._() {
    _channel!.setMethodCallHandler(null);

    _eventChannel
        .receiveBroadcastStream()
        .listen(speechResultsHandler, onError: speechResultErrorHandler);
  }

  ResultCallback? _resultSuccessCallback;
  ResultCallback? _resultErrorCallback;

  static BlufiPlugin _instance = new BlufiPlugin._();
  static BlufiPlugin get instance => _instance;

  /// 设置消息接收回调
  /// [successCallback] 成功回调，接收来自原生平台的消息
  /// [errorCallback] 错误回调，接收错误信息
  void onMessageReceived({ResultCallback? successCallback, ResultCallback? errorCallback}) {
    _resultSuccessCallback = successCallback;
    _resultErrorCallback = errorCallback;
  }

  /// 获取平台版本信息
  Future<String?> get platformVersion async {
    final String? version = await _channel!.invokeMethod('getPlatformVersion');
    return version;
  }

  /// 扫描蓝牙设备
  /// [filterString] 过滤字符串，用于过滤设备名称
  /// 返回 true 表示开始扫描，false 表示扫描失败
  Future<bool?> scanDeviceInfo({String? filterString}) async {
    final bool? isEnable =
        await _channel!.invokeMethod('scanDeviceInfo', <String, dynamic>{'filter': filterString});
    return isEnable;
  }

  /// 停止扫描蓝牙设备
  Future stopScan() async {
    await _channel!.invokeMethod('stopScan');
  }

  /// 连接蓝牙设备
  /// [peripheralAddress] 设备地址（MAC地址）
  Future connectPeripheral({String? peripheralAddress}) async {
    await _channel!
        .invokeMethod('connectPeripheral', <String, dynamic>{'peripheral': peripheralAddress});
  }

  /// 请求关闭连接
  Future requestCloseConnection() async {
    await _channel!.invokeMethod('requestCloseConnection');
  }

  /// 协商安全加密
  /// 在配网之前需要先进行安全协商，后续通信数据将被加密
  Future negotiateSecurity() async {
    await _channel!.invokeMethod('negotiateSecurity');
  }

  /// 请求设备版本信息
  Future requestDeviceVersion() async {
    await _channel!.invokeMethod('requestDeviceVersion');
  }

  /// 配置配网参数（Station模式）
  /// [username] WiFi SSID（WiFi名称）
  /// [password] WiFi 密码
  Future configProvision({String? username, String? password}) async {
    await _channel!.invokeMethod(
        'configProvision', <String, dynamic>{'username': username, 'password': password});
  }

  /// 请求设备当前状态
  /// 可以查询设备是否已连接到WiFi等信息
  Future requestDeviceStatus() async {
    await _channel!.invokeMethod('requestDeviceStatus');
  }

  /// 请求设备扫描WiFi列表
  /// 获取设备扫描到的附近WiFi网络列表
  Future requestDeviceScan() async {
    await _channel!.invokeMethod('requestDeviceScan');
  }

  /// 发送自定义数据到设备
  /// [dataStr] 自定义数据字符串
  Future postCustomData(String dataStr) async {
    await _channel!.invokeMethod('postCustomData', <String, dynamic>{'custom_data': dataStr});
  }

  speechResultsHandler(dynamic event) {
    if (_resultSuccessCallback != null) _resultSuccessCallback!(event);
  }

  speechResultErrorHandler(dynamic error) {
    if (_resultErrorCallback != null) _resultErrorCallback!(error);
  }
}
