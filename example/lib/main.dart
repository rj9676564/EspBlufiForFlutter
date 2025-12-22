import 'dart:convert';

import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:esp_blufi_for_flutter/esp_blufi_for_flutter.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String contentJson = 'Unknown';

  Map<String, dynamic> scanResult = Map<String, dynamic>();
  bool isConnected = false;
  bool isSecurityNegotiated = false;
  String? connectedDeviceAddress;

  @override
  void initState() {
    super.initState();
    initPlatformState();

    BlufiPlugin.instance.onMessageReceived(
        successCallback: (String? data) {
          print("success data: $data");
          setState(() {
            contentJson = data ?? 'Unknown';
            Map<String, dynamic> mapData = json.decode(data ?? '{}');
            if (mapData.containsKey('key')) {
              String key = mapData['key'];
              if (key == 'ble_scan_result') {
                Map<String, dynamic> peripheral = mapData['value'];

                String address = peripheral['address'];
                String name = peripheral['name'];
                String rssi = peripheral['rssi'];
                print(rssi);
                scanResult[address] = name;

                // 自动连接第一个扫描到的设备（如果还没有连接）
                // if (!isConnected && scanResult.isNotEmpty) {
                //   print('Auto connecting to first device: $name ($address)');
                //   _autoConnectDevice(address);
                // }
              } else if (key == 'peripheral_connect' || key == 'blufi_connect_prepared') {
                // 设备连接成功
                String value = mapData['value'] ?? '';
                if (value == '1') {
                  isConnected = true;
                  connectedDeviceAddress = mapData['address'];
                  print('Device connected successfully');
                }
              } else if (key == 'negotiate_security') {
                // 安全协商结果
                String value = mapData['value'] ?? '';
                if (value == '1') {
                  isSecurityNegotiated = true;
                  print('Security negotiated successfully');
                } else {
                  isSecurityNegotiated = false;
                  print('Security negotiation failed');
                }
              } else if (key == 'configure_params') {
                // 配网结果
                String value = mapData['value'] ?? '';
                if (value == '1') {
                  print('Configuration successful');
                  // 可以查询设备状态确认配网是否成功
                  _checkDeviceStatus();
                } else {
                  print('Configuration failed');
                }
              } else if (key == 'device_wifi_connect') {
                // WiFi连接状态
                String value = mapData['value'] ?? '';
                if (value == '1') {
                  print('Device connected to WiFi successfully!');
                } else {
                  print('Device not connected to WiFi yet');
                }
              } else if (key == 'receive_error_code') {
                // 设备错误码（0 表示无错误，其他值表示错误）
                String value = mapData['value'] ?? '';
                int errorCode = int.tryParse(value) ?? -1;
                if (errorCode == 0) {
                  print('Device status: No error (code 0)');
                } else {
                  print('⚠️ Device error code: $errorCode');
                  // 显示常见错误码的含义
                  String errorMsg = _getErrorMessage(errorCode);
                  print('Error meaning: $errorMsg');
                }
              }
            }
          });
        },
        errorCallback: (String? error) {
          print("error: $error");
        });
  }

  Future<void> _negotiateSecurity() async {
    if (isConnected && !isSecurityNegotiated) {
      print('Starting security negotiation...');
      await BlufiPlugin.instance.negotiateSecurity();
    }
  }

  Future<void> _checkDeviceStatus() async {
    await BlufiPlugin.instance.requestDeviceStatus();
  }

  Future<void> _autoConnectDevice(String address) async {
    try {
      await BlufiPlugin.instance.connectPeripheral(peripheralAddress: address);
    } catch (e) {
      print('Auto connect failed: $e');
    }
  }

  String _getErrorMessage(int errorCode) {
    // ESP Blufi 错误码含义
    switch (errorCode) {
      case 0x00:
        return 'Sequence error (序列错误)';
      case 0x01:
        return 'Checksum error (校验和错误)';
      case 0x02:
        return 'Decrypt error (解密错误)';
      case 0x03:
        return 'Encrypt error (加密错误)';
      case 0x04:
        return 'Security init error (安全初始化错误)';
      case 0x05:
        return 'DH memory allocation error (DH 内存分配错误)';
      case 0x06:
        return 'DH parameter error (DH 参数错误)';
      case 0x07:
        return 'Read parameter error (读取参数错误)';
      case 0x08:
        return 'Generate public key error (生成公钥错误)';
      case 0x09:
        return 'Data format error (数据格式错误)';
      case 0x0a:
        return 'MD5 calculation error (计算 MD5 错误)';
      case 0x0b:
        return 'WiFi scan error (WiFi 扫描错误)';
      case 0:
        return 'No error (无错误/成功)';
      default:
        return 'Unknown error code (未知错误码)';
    }
  }

  // Platform messages are asynchronous, so we initialize in an async method.
  Future<void> initPlatformState() async {
    String? platformVersion;
    // Platform messages may fail, so we use a try/catch PlatformException.
    try {
      platformVersion = await BlufiPlugin.instance.platformVersion;
    } on PlatformException {
      platformVersion = 'Failed to get platform version.';
    }

    // If the widget was removed from the tree while the asynchronous platform
    // message was in flight, we want to discard the reply rather than calling
    // setState to update our non-existent appearance.
    if (!mounted) return;
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('Plugin example app'),
        ),
        body: Column(
          children: [
            TextButton(
                onPressed: () async {
                  await BlufiPlugin.instance
                      .scanDeviceInfo(filterString: 'Q31PRO');
                },
                child: Text('Scan')),
            TextButton(
                onPressed: () async {
                  await BlufiPlugin.instance.stopScan();
                },
                child: Text('Stop Scan')),
            TextButton(
                onPressed: () async {
                  if (scanResult.isEmpty) {
                    print('No device found, please scan first');
                    return;
                  }
                  isConnected = false;
                  isSecurityNegotiated = false;
                  String address = scanResult.keys.first;
                  print('Manually connecting to: ${scanResult[address]} ($address)');
                  await BlufiPlugin.instance.connectPeripheral(
                      peripheralAddress: address);
                },
                child: Text('Connect Peripheral (Manual)')),
            TextButton(
                onPressed: () async {
                  await BlufiPlugin.instance.requestCloseConnection();
                  isConnected = false;
                  isSecurityNegotiated = false;
                  connectedDeviceAddress = null;
                },
                child: Text('Close Connect')),
            TextButton(
                onPressed: () async {
                  if (!isConnected) {
                    print('Device not connected, please scan first and wait for auto-connect');
                    return;
                  }
                  // if (!isSecurityNegotiated) {
                  //   print('Security not negotiated, please wait... (this should happen automatically)');
                  //   // 如果安全协商还没完成，等待一下再试
                  //   await Future.delayed(Duration(seconds: 1));
                  //   if (!isSecurityNegotiated) {
                  //     print('Security negotiation timeout, please try again');
                  //     return;
                  //   }
                  // }
                  // 配置 WiFi 信息，请修改为你的 WiFi SSID 和密码
                  print('Starting configuration with WiFi: hmop');
                  await BlufiPlugin.instance
                      .configProvision(username: '小店掌柜', password: 'juhesaas2023');
                  // await BlufiPlugin.instance
                  //     .configProvision(username: 'blu', password: '88888888');
                },
                child: Text('Config Provision (WiFi: hmop)')),
            TextButton(
                onPressed: () async {
                  if (!isConnected) {
                    print('Device not connected');
                    return;
                  }
                  await BlufiPlugin.instance.requestDeviceStatus();
                },
                child: Text('Check Device Status')),
            TextButton(
                onPressed: () async {
                  String command = '12345678';
                  await BlufiPlugin.instance.postCustomData(command);
                },
                child: Text('Send Custom Data')),
            TextButton(
                onPressed: () async {
                  await BlufiPlugin.instance.requestDeviceScan();
                },
                child: Text('Get wifi list')),
            Padding(
              padding: EdgeInsets.all(8.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Status:', style: TextStyle(fontWeight: FontWeight.bold)),
                  Text('Connected: ${isConnected ? "Yes" : "No"}'),
                  Text('Security Negotiated: ${isSecurityNegotiated ? "Yes" : "No"}'),
                  Text('Devices Found: ${scanResult.length}'),
                  if (connectedDeviceAddress != null)
                    Text('Connected Device: $connectedDeviceAddress'),
                  SizedBox(height: 8),
                  Text('Latest Message:', style: TextStyle(fontWeight: FontWeight.bold)),
                  Text(contentJson, style: TextStyle(fontSize: 12)),
                ],
              ),
            )
          ],
        ),
      ),
    );
  }
}
