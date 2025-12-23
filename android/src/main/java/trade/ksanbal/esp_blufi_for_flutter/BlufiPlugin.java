package trade.ksanbal.esp_blufi_for_flutter;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import trade.ksanbal.esp_blufi_for_flutter.constants.BlufiConstants;
import trade.ksanbal.esp_blufi_for_flutter.params.BlufiConfigureParams;
import trade.ksanbal.esp_blufi_for_flutter.params.BlufiParameter;
import trade.ksanbal.esp_blufi_for_flutter.response.BlufiScanResult;
import trade.ksanbal.esp_blufi_for_flutter.response.BlufiStatusResponse;

/**
 * ESP Blufi Flutter Plugin - Android 实现
 * 提供蓝牙配网功能，支持 Station 模式配网
 */
public class BlufiPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler {

  private static final int REQUEST_FINE_LOCATION_PERMISSIONS = 1452;

  private Map<String, ScanResult> mDeviceMap;
  private ScanCallback mScanCallback;
  private String mBlufiFilter;

  private BluetoothDevice mDevice;
  private BlufiClient mBlufiClient;
  private volatile boolean mConnected;
  private volatile boolean mSecurityNegotiated;
  private CountDownLatch mConnectLatch;
  private volatile boolean mConnectResult;

  private Context mContext;
  private ActivityPluginBinding activityBinding;

  private EventChannel stateChannel;
  private EventChannel.StreamHandler streamHandler;
  private EventChannel.EventSink sink;

  private final BlufiLog mLog = new BlufiLog(getClass());
  private MethodChannel channel;
  private Handler handler;



  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    handler = new Handler(Looper.getMainLooper());
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "esp_blufi_for_flutter");
    channel.setMethodCallHandler(this);
    stateChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), "esp_blufi_for_flutter/state");
    streamHandler = new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object arguments, EventChannel.EventSink events) {
        sink = events;
      }

      @Override
      public void onCancel(Object arguments) {
        sink = null;

      }
    };
    stateChannel.setStreamHandler(streamHandler);
    mContext = flutterPluginBinding.getApplicationContext();
    mDeviceMap = new HashMap<>();
    mScanCallback = new ScanCallback();
  }

  /**
   * 处理方法调用
   * 处理来自 Flutter 端的方法调用
   */
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    // 获取平台版本
    if (call.method.equals("getPlatformVersion")) {
      result.success("Android " + Build.VERSION.RELEASE);
    }
    // 扫描蓝牙设备
    else if (call.method.equals("scanDeviceInfo")) {
      if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION)
              != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
                activityBinding.getActivity(),
                new String[] {
                        Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQUEST_FINE_LOCATION_PERMISSIONS);
      }
      String filter = call.argument("filter");
      scan(filter, result);
    }
    // 停止扫描蓝牙设备
    else if (call.method.equals("stopScan")) {
      stopScan();
      result.success(true);
    }
    // 连接蓝牙设备
    else if (call.method.equals("connectPeripheral")) {
      String deviceId = call.argument("peripheral");
      if (deviceId != null) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
          try {
            BluetoothDevice device = adapter.getRemoteDevice(deviceId);
            boolean connectSuccess = connectDeviceSync(device);
            result.success(connectSuccess);
          } catch (IllegalArgumentException e) {
            mLog.w("Invalid device address: " + deviceId);
            result.error("INVALID_ARGUMENT", "Invalid device address: " + deviceId, null);
          }
        } else {
          mLog.w("Bluetooth adapter is null");
          result.error("BLUETOOTH_UNAVAILABLE", "Bluetooth adapter is not available", null);
        }
      } else {
        mLog.w("Device address is null");
        result.error("INVALID_ARGUMENT", "Device address cannot be null", null);
      }
    }
    // 请求关闭连接
    else if (call.method.equals("requestCloseConnection")) {
      disconnectGatt();
      result.success(true);
    }
    // 协商安全加密
    else if (call.method.equals("negotiateSecurity")) {
      negotiateSecurity();
      result.success(true);
    }
    // 配置配网参数（Station模式）
    else if (call.method.equals("configProvision")) {
      String ssid = call.argument("username");
      String password = call.argument("password");
      if (ssid == null || ssid.isEmpty()) {
        mLog.w("SSID is empty");
        updateMessage(makeJson("configure_params","0"));
        result.error("INVALID_ARGUMENT", "SSID cannot be empty", null);
        return;
      }
      configure(ssid, password != null ? password : "");
      result.success(true);
    }
    // 请求设备当前状态
    else if (call.method.equals("requestDeviceStatus")) {
      requestDeviceStatus();
      result.success(true);
    }
    // 请求设备扫描WiFi列表
    else if (call.method.equals("requestDeviceScan")) {
      requestDeviceWifiScan();
      result.success(true);
    }
    else {
      result.notImplemented();
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
  }


  /**
   * 扫描蓝牙设备
   * @param filter 过滤字符串，用于过滤设备名称
   * @param result Flutter 回调结果
   */
  private void scan(String filter, Result result) {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      mLog.w("Bluetooth adapter is null");
      result.success(false);
      return;
    }

    BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
    if (!adapter.isEnabled() || scanner == null) {
      mLog.w("Bluetooth is not enabled or scanner is null");
      result.success(false);
      return;
    }

    mDeviceMap.clear();
    mBlufiFilter = filter;

    mLog.d("Start scan BLE devices");
    scanner.startScan(null,
        new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build(),
        mScanCallback);
    result.success(true);
  }

  /**
   * 停止扫描蓝牙设备
   */
  private void stopScan() {
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter != null) {
      BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
      if (scanner != null) {
        scanner.stopScan(mScanCallback);
        mLog.d("Stop scan BLE devices");
        updateMessage(makeJson("stop_scan_ble","1"));
      }
    }
  }

  /**
   * 连接蓝牙设备（同步方法）
   * @param device 要连接的蓝牙设备
   * @return true 连接成功，false 连接失败或超时
   */
  boolean connectDeviceSync(BluetoothDevice device) {
    if (device == null) {
      mLog.w("Cannot connect: device is null");
      return false;
    }

    // 如果已有连接，先关闭
    if (mBlufiClient != null) {
      mBlufiClient.close();
      mBlufiClient = null;
    }

    // 重置连接状态
    mConnected = false;
    mSecurityNegotiated = false;
    mConnectResult = false;
    mConnectLatch = new CountDownLatch(1);

    mDevice = device;
    mBlufiClient = new BlufiClient(mContext, mDevice);
    mBlufiClient.setGattCallback(new GattCallback());
    mBlufiClient.setBlufiCallback(new BlufiCallbackMain());
    mBlufiClient.setGattWriteTimeout(BlufiConstants.GATT_WRITE_TIMEOUT);
    mBlufiClient.connect();
    mLog.d("Connecting to device: " + device.getAddress());

    // 等待连接结果，超时时间 30 秒
    // 注意：Flutter method call 在后台线程执行，蓝牙回调在主线程，所以不会死锁
    try {
      mLog.d("Waiting for connection result, timeout: 30 seconds");
      long startTime = System.currentTimeMillis();
      boolean awaitResult = mConnectLatch.await(30, TimeUnit.SECONDS);
      long elapsedTime = System.currentTimeMillis() - startTime;
      
      if (!awaitResult) {
        mLog.w("Connection timeout after " + elapsedTime + "ms");
        return false;
      }
      
      mLog.d("Connection result received after " + elapsedTime + "ms, result: " + mConnectResult);
      return mConnectResult;
    } catch (InterruptedException e) {
      mLog.w("Connection interrupted: " + e.getMessage());
      Thread.currentThread().interrupt();
      return false;
    } finally {
      mConnectLatch = null;
      mLog.d("Connection sync completed, latch cleared");
    }
  }

  /**
   * 连接蓝牙设备（异步方法，保留用于内部调用）
   * @param device 要连接的蓝牙设备
   */
  void connectDevice(BluetoothDevice device) {
    if (device == null) {
      mLog.w("Cannot connect: device is null");
      return;
    }

    mDevice = device;
    if (mBlufiClient != null) {
      mBlufiClient.close();
      mBlufiClient = null;
    }

    mConnected = false;
    mSecurityNegotiated = false;
    mBlufiClient = new BlufiClient(mContext, mDevice);
    mBlufiClient.setGattCallback(new GattCallback());
    mBlufiClient.setBlufiCallback(new BlufiCallbackMain());
    mBlufiClient.setGattWriteTimeout(BlufiConstants.GATT_WRITE_TIMEOUT);
    mBlufiClient.connect();
    mLog.d("Connecting to device: " + device.getAddress());
  }


  /**
   * 断开GATT连接
   */
  private void disconnectGatt() {
    if (mBlufiClient != null) {
      mBlufiClient.requestCloseConnection();
    }
    mConnected = false;
    mSecurityNegotiated = false;
  }

  /**
   * 协商安全加密
   * 如果安全协商成功，后续通信数据将被加密
   */
  private void negotiateSecurity() {
    if (mBlufiClient == null) {
      mLog.w("Cannot negotiate security: BlufiClient is null");
      updateMessage(makeJson("negotiate_security","0"));
      return;
    }
    if (!mConnected) {
      mLog.w("Cannot negotiate security: not connected");
      updateMessage(makeJson("negotiate_security","0"));
      return;
    }
    if (mSecurityNegotiated) {
      mLog.d("Security already negotiated, skipping");
      return;
    }
    mLog.d("Starting security negotiation");
//    mBlufiClient.negotiateSecurity();
  }


  /**
   * 配置设备为 Station 模式
   * 设置 WiFi SSID 和密码，使设备连接到指定的 WiFi 网络
   *
   * @param ssid WiFi SSID（WiFi名称）
   * @param password WiFi 密码
   */
  private void configure(String ssid, String password) {
    if (mBlufiClient == null) {
      mLog.w("Cannot configure: BlufiClient is null");
      updateMessage(makeJson("configure_params","0"));
      return;
    }
    if (!mConnected) {
      mLog.w("Cannot configure: not connected");
      updateMessage(makeJson("configure_params","0"));
      return;
    }
//    if (!mSecurityNegotiated) {
//      mLog.w("Cannot configure: security not negotiated");
//      updateMessage(makeJson("configure_params","0"));
//      return;
//    }

    // Create configuration parameters for station mode
    BlufiConfigureParams params = new BlufiConfigureParams();
    params.setOpMode(BlufiParameter.OP_MODE_STA);

    // Set SSID as byte array to support non-ASCII characters
    // Use UTF-8 encoding to ensure proper character handling
    byte[] ssidBytes = ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    params.setStaSSIDBytes(ssidBytes);
    params.setStaBSSID(ssid);
    params.setStaPassword(password != null ? password : "");

    mLog.d("Configuring station mode - SSID: " + ssid + " (length: " + ssidBytes.length + " bytes)");
    mLog.d("Password length: " + (password != null ? password.length() : 0) + " characters");
    mBlufiClient.configure(params);
  }

  /**
   * 请求设备当前状态
   * 可以查询设备是否已连接到WiFi等信息
   */
  private void requestDeviceStatus() {
    if (mBlufiClient == null || !mConnected) {
      mLog.w("Cannot request device status: not connected");
      updateMessage(makeJson("device_status","0"));
      return;
    }
    mBlufiClient.requestDeviceStatus();
  }

  /**
   * 请求设备扫描WiFi列表
   * 获取设备扫描到的附近WiFi网络列表
   */
  private void requestDeviceWifiScan() {
    if (mBlufiClient == null || !mConnected) {
      mLog.w("Cannot request device WiFi scan: not connected");
      updateMessage(makeJson("wifi_info","0"));
      return;
    }
    mBlufiClient.requestDeviceWifiScan();
  }

  private void onGattConnected() {
    mConnected = true;
    mConnectResult = true;
    // 通知等待连接的线程
    if (mConnectLatch != null && mConnectLatch.getCount() > 0) {
      mLog.d("Connection successful, notifying waiting thread");
      mConnectLatch.countDown();
    }
  }

  private void onGattDisconnected() {
    mConnected = false;
    mSecurityNegotiated = false;
    // 如果正在等待连接，通知连接失败
    if (mConnectLatch != null && mConnectLatch.getCount() > 0) {
      mConnectResult = false;
      mConnectLatch.countDown();
    }
  }

  /**
   * GATT 服务特征发现完成
   * MTU 设置完成，服务发现完成，可以开始安全协商
   * 参考 BlufiActivity，只通知 Flutter 端已准备好，不做其他处理
   */
  private void onGattServiceCharacteristicDiscovered() {
    mLog.d("GATT prepared, ready for operations");
    updateMessage(makeJson("gatt_prepared","1"));
  }


  @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
  private class GattCallback extends BluetoothGattCallback {
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
      String devAddr = gatt.getDevice().getAddress();
      mLog.d(String.format(Locale.ENGLISH, "onConnectionStateChange addr=%s, status=%d, newState=%d",
              devAddr, status, newState));
      if (status == BluetoothGatt.GATT_SUCCESS) {
        switch (newState) {
          case BluetoothProfile.STATE_CONNECTED:
            mLog.d("STATE_CONNECTED received, calling onGattConnected");
            onGattConnected();
            updateMessage(makeJson("peripheral_connect","1"));
            mLog.d("Connected to device: " + devAddr);
            break;
          case BluetoothProfile.STATE_DISCONNECTED:
            gatt.close();
            onGattDisconnected();
            updateMessage(makeJson("peripheral_connect","0"));
            mLog.d("Disconnected from device: " + devAddr);
            break;
        }
      } else {
        mLog.w(String.format(Locale.ENGLISH, "Connection failed: %s, status=%d", devAddr, status));
        gatt.close();
        onGattDisconnected();
        updateMessage(makeJson("peripheral_disconnect","1"));
        // 连接失败，通知等待连接的线程
        if (mConnectLatch != null && mConnectLatch.getCount() > 0) {
          mConnectResult = false;
          mConnectLatch.countDown();
        }
      }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
      mLog.d(String.format(Locale.ENGLISH, "onMtuChanged status=%d, mtu=%d", status, mtu));
      if (status == BluetoothGatt.GATT_SUCCESS) {
        // 参考 BlufiActivity，不设置包长度限制，使用默认值
        // mBlufiClient.setPostPackageLengthLimit(maxLength);
      } else {
        mBlufiClient.setPostPackageLengthLimit(20);
      }

      onGattServiceCharacteristicDiscovered();
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
      mLog.d(String.format(Locale.ENGLISH, "onServicesDiscovered status=%d", status));
      if (status != BluetoothGatt.GATT_SUCCESS) {
        mLog.w("Discover services failed, disconnecting");
        gatt.disconnect();
        updateMessage(makeJson("discover_services","0"));
      }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      mLog.d(String.format(Locale.ENGLISH, "onDescriptorWrite status=%d", status));
      if (descriptor.getUuid().equals(BlufiParameter.UUID_NOTIFICATION_DESCRIPTOR) &&
              descriptor.getCharacteristic().getUuid().equals(BlufiParameter.UUID_NOTIFICATION_CHARACTERISTIC)) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
          mLog.d("Notification enabled successfully");
        } else {
          mLog.w("Failed to enable notification");
        }
      }
    }
    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      if (status != BluetoothGatt.GATT_SUCCESS) {
        mLog.w("Characteristic write failed, disconnecting");
        gatt.disconnect();
      }
    }
  }

  /**
   * Blufi 回调处理类
   * 处理来自 BlufiClient 的各种回调事件
   */
  private class BlufiCallbackMain extends BlufiCallback {
    /**
     * GATT 准备完成回调
     * @param client BlufiClient 实例
     * @param status 状态码
     * @param gatt BluetoothGatt 实例
     */
    @Override
    public void onGattPrepared(BlufiClient client, int status, BluetoothGatt gatt) {
      switch (status) {
        case STATUS_SUCCESS:
          updateMessage(makeJson("discover_service","1"));
          int mtu = BlufiConstants.DEFAULT_MTU_LENGTH;
          mLog.d("Request MTU " + mtu);
          boolean requestMtu = gatt.requestMtu(mtu);
          if (!requestMtu) {
            mLog.w("Request mtu failed");
            updateMessage(makeJson("request_mtu","0"));
            onGattServiceCharacteristicDiscovered();
          } else {
            updateMessage(makeJson("request_mtu","1"));
          }
          break;
        case CODE_GATT_DISCOVER_SERVICE_FAILED:
          mLog.w("Discover service failed");
          gatt.disconnect();
          updateMessage(makeJson("discover_service","0"));
          break;
        case CODE_GATT_DISCOVER_WRITE_CHAR_FAILED:
          mLog.w("Get write characteristic failed");
          gatt.disconnect();
          updateMessage(makeJson("get_write_characteristic","0"));
          break;
        case CODE_GATT_DISCOVER_NOTIFY_CHAR_FAILED:
          mLog.w("Get notification characteristic failed");
          gatt.disconnect();
          updateMessage(makeJson("get_notification_characteristic","0"));
          break;
        case CODE_GATT_ERR_OPEN_NOTIFY:
          mLog.w("Open notify function failed");
          gatt.disconnect();
          updateMessage(makeJson("open_notify","0"));
          break;
        default:
          mLog.w("onGattPrepared unknown status: " + status);
          gatt.disconnect();
          updateMessage(makeJson("gatt_prepared","0"));
          break;
      }
    }

    /**
     * 安全协商结果回调
     * @param client BlufiClient 实例
     * @param status 状态码，STATUS_SUCCESS 表示成功
     */
    @Override
    public void onNegotiateSecurityResult(BlufiClient client, int status) {
      if (status == STATUS_SUCCESS) {
        mSecurityNegotiated = true;
        mLog.d("Negotiate security complete");
        updateMessage(makeJson("negotiate_security","1"));
      } else {
        mSecurityNegotiated = false;
        mLog.w("Negotiate security failed, code=" + status);
        updateMessage(makeJson("negotiate_security","0"));
      }
    }

    /**
     * 配网参数发送结果回调
     * @param client BlufiClient 实例
     * @param status 状态码，STATUS_SUCCESS 表示成功
     */
    @Override
    public void onPostConfigureParams(BlufiClient client, int status) {
      if (status == STATUS_SUCCESS) {
        mLog.d("Station mode configuration complete, device will attempt to connect to WiFi");
        updateMessage(makeJson("configure_params","1"));
        // Note: Device needs time to connect to WiFi, status will be checked separately
      } else {
        mLog.w("Station mode configuration failed, code=" + status);
        updateMessage(makeJson("configure_params","0"));
      }
    }

    /**
     * 设备状态响应回调
     * @param client BlufiClient 实例
     * @param status 状态码，STATUS_SUCCESS 表示成功
     * @param response 设备状态响应，包含 WiFi 连接状态等信息
     */
    @Override
    public void onDeviceStatusResponse(BlufiClient client, int status, BlufiStatusResponse response) {
      if (status == STATUS_SUCCESS) {
        updateMessage(makeJson("device_status","1"));
        // Check if station is connected to WiFi
        if (response.isStaConnectWifi()) {
          updateMessage(makeJson("device_wifi_connect","1"));
          mLog.d("Device connected to WiFi");
        } else {
          updateMessage(makeJson("device_wifi_connect","0"));
          mLog.d("Device not connected to WiFi");
        }
      } else {
        mLog.w("Device status response error, code=" + status);
        updateMessage(makeJson("device_status","0"));
      }
    }

    /**
     * 设备 WiFi 扫描结果回调
     * @param client BlufiClient 实例
     * @param status 状态码，STATUS_SUCCESS 表示成功
     * @param results WiFi 扫描结果列表
     */
    @Override
    public void onDeviceScanResult(BlufiClient client, int status, List<BlufiScanResult> results) {
      if (status == STATUS_SUCCESS) {
        for (BlufiScanResult scanResult : results) {
          updateMessage(makeWifiInfoJson(scanResult.getSsid(), scanResult.getRssi()));
        }
      } else {
        mLog.w("Device scan result error, code=" + status);
        updateMessage(makeJson("wifi_info","0"));
      }
    }


    /**
     * 错误回调
     * @param client BlufiClient 实例
     * @param errCode 错误码，0 表示无错误
     */
    @Override
    public void onError(BlufiClient client, int errCode) {
      // Error code 0 means no error/success, can be ignored
      if (errCode == 0) {
        mLog.d("Device reported error code 0 (no error/success)");
        return;
      }

      mLog.w(String.format(Locale.ENGLISH, "Device reported error code: %d", errCode));
      updateMessage(makeJson("receive_error_code", String.valueOf(errCode)));

      // Handle critical errors
      if (errCode == CODE_GATT_WRITE_TIMEOUT) {
        mLog.w("GATT write timeout, closing connection");
        client.close();
        onGattDisconnected();
      }
    }
  }


  private void updateMessage(String message) {
    Log.v("message", message);

    if (sink != null) {
      handler.post(
              new Runnable() {
                @Override
                public void run() {
                  sink.success(message);
                }
              });
    }
  }

  private String makeJson(String command, String data) {

    String address = "";
    if (mDevice != null) {
      address = mDevice.getAddress();
    }
    return String.format("{\"key\":\"%s\",\"value\":\"%s\",\"address\":\"%s\"}", command, data, address);
  }

  private String makeScanDeviceJson(String address, String name, int rssi) {
    return String.format("{\"key\":\"ble_scan_result\",\"value\":{\"address\":\"%s\",\"name\":\"%s\",\"rssi\":\"%s\"}}", address, name, rssi);
  }

  private String makeWifiInfoJson(String ssid, int rssi) {
    String address = "";
    if (mDevice != null) {
      address = mDevice.getAddress();
    }
    return String.format("{\"key\":\"wifi_info\",\"value\":{\"ssid\":\"%s\",\"rssi\":\"%s\",\"address\":\"%s\"}}", ssid, rssi, address);
  }


  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private class ScanCallback extends android.bluetooth.le.ScanCallback {

    @Override
    public void onScanFailed(int errorCode) {
      super.onScanFailed(errorCode);
    }

    @Override
    public void onBatchScanResults(List<ScanResult> results) {
      for (ScanResult result : results) {
        onLeScan(result);
      }
    }

    @Override
    public void onScanResult(int callbackType, ScanResult result) {
      onLeScan(result);
    }

    private void onLeScan(ScanResult scanResult) {
      String name = scanResult.getDevice().getName();

      if (!TextUtils.isEmpty(mBlufiFilter)) {
        if (name == null || !name.toLowerCase().contains(mBlufiFilter.toLowerCase())) {
          return;
        }
      }

      Log.v("ble scan", scanResult.getDevice().getAddress());

      if (scanResult.getDevice().getName() != null) {
        mDeviceMap.put(scanResult.getDevice().getAddress(), scanResult);
        updateMessage(makeScanDeviceJson(scanResult.getDevice().getAddress(), scanResult.getDevice().getName(), scanResult.getRssi()));
      }
    }
  }

  @Override
  public void onAttachedToActivity(ActivityPluginBinding binding) {
    activityBinding = binding;
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
  }

  @Override
  public void onDetachedFromActivity() {
  }
}
