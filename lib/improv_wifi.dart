import 'dart:async';
import 'package:flutter/services.dart';

/// Represents a discovered Improv WiFi device.
class ImprovDevice {
  final String id;
  final String? name;

  const ImprovDevice({required this.id, this.name});

  factory ImprovDevice.fromMap(Map<String, dynamic> map) {
    return ImprovDevice(
      id: map['id'] as String,
      name: map['name'] as String?,
    );
  }

  @override
  String toString() => 'ImprovDevice(id: $id, name: $name)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is ImprovDevice &&
          runtimeType == other.runtimeType &&
          id == other.id &&
          name == other.name;

  @override
  int get hashCode => id.hashCode ^ name.hashCode;
}

/// Bluetooth adapter state.
enum BluetoothState {
  unknown,
  resetting,
  unsupported,
  unauthorized,
  poweredOff,
  poweredOn,
}

/// Improv device state.
enum DeviceState {
  authorizationRequired,
  authorized,
  provisioning,
  provisioned,
}

/// Improv error state.
enum ErrorState {
  noError,
  invalidRPCPacket,
  unknownCommand,
  unableToConnect,
  notAuthorized,
  unknown,
}

/// Represents the current state of Improv WiFi.
class ImprovState {
  final List<ImprovDevice> foundDevices;
  final String? connectedDeviceId;
  final BluetoothState bluetoothState;
  final DeviceState? deviceState;
  final ErrorState? errorState;
  final List<String>? lastResult;

  const ImprovState({
    required this.foundDevices,
    this.connectedDeviceId,
    required this.bluetoothState,
    this.deviceState,
    this.errorState,
    this.lastResult,
  });

  factory ImprovState.fromMap(Map<dynamic, dynamic> map) {
    final foundDevicesList = (map['foundDevices'] as List<dynamic>?)
            ?.map((e) => ImprovDevice.fromMap(Map<String, dynamic>.from(e as Map)))
            .toList() ??
        [];

    return ImprovState(
      foundDevices: foundDevicesList,
      connectedDeviceId: map['connectedDeviceId'] as String?,
      bluetoothState: _parseBluetoothState(map['bluetoothState'] as String?),
      deviceState: _parseDeviceState(map['deviceState'] as String?),
      errorState: _parseErrorState(map['errorState'] as String?),
      lastResult: (map['lastResult'] as List<dynamic>?)?.cast<String>(),
    );
  }

  static BluetoothState _parseBluetoothState(String? value) {
    switch (value) {
      case 'unknown':
        return BluetoothState.unknown;
      case 'resetting':
        return BluetoothState.resetting;
      case 'unsupported':
        return BluetoothState.unsupported;
      case 'unauthorized':
        return BluetoothState.unauthorized;
      case 'poweredOff':
        return BluetoothState.poweredOff;
      case 'poweredOn':
        return BluetoothState.poweredOn;
      default:
        return BluetoothState.unknown;
    }
  }

  static DeviceState? _parseDeviceState(String? value) {
    switch (value) {
      case 'authorizationRequired':
        return DeviceState.authorizationRequired;
      case 'authorized':
        return DeviceState.authorized;
      case 'provisioning':
        return DeviceState.provisioning;
      case 'provisioned':
        return DeviceState.provisioned;
      default:
        return null;
    }
  }

  static ErrorState? _parseErrorState(String? value) {
    switch (value) {
      case 'noError':
        return ErrorState.noError;
      case 'invalidRPCPacket':
        return ErrorState.invalidRPCPacket;
      case 'unknownCommand':
        return ErrorState.unknownCommand;
      case 'unableToConnect':
        return ErrorState.unableToConnect;
      case 'notAuthorized':
        return ErrorState.notAuthorized;
      case 'unknown':
        return ErrorState.unknown;
      default:
        return null;
    }
  }

  @override
  String toString() =>
      'ImprovState(foundDevices: $foundDevices, connectedDeviceId: $connectedDeviceId, bluetoothState: $bluetoothState, deviceState: $deviceState, errorState: $errorState, lastResult: $lastResult)';
}

/// Flutter plugin for Improv WiFi provisioning.
class ImprovWifi {
  static const MethodChannel _methodChannel =
      MethodChannel('be.azimut.improv_wifi/methods');
  static const EventChannel _eventChannel =
      EventChannel('be.azimut.improv_wifi/state');

  static Stream<ImprovState>? _stateStream;

  /// Stream of Improv state updates.
  static Stream<ImprovState> get stateStream {
    _stateStream ??= _eventChannel
        .receiveBroadcastStream()
        .map((event) => ImprovState.fromMap(event as Map<dynamic, dynamic>));
    return _stateStream!;
  }

  /// Start scanning for Improv WiFi devices.
  static Future<void> startScan() async {
    await _methodChannel.invokeMethod('startScan');
  }

  /// Stop scanning for Improv WiFi devices.
  static Future<void> stopScan() async {
    await _methodChannel.invokeMethod('stopScan');
  }

  /// Connect to an Improv WiFi device by its ID.
  static Future<void> connectToDevice(String deviceId) async {
    await _methodChannel.invokeMethod('connectToDevice', {'deviceId': deviceId});
  }

  /// Disconnect from the currently connected device.
  static Future<void> disconnectDevice() async {
    await _methodChannel.invokeMethod('disconnectDevice');
  }

  /// Send identify command to the connected device.
  static Future<void> identifyDevice() async {
    await _methodChannel.invokeMethod('identifyDevice');
  }

  /// Send WiFi credentials to the connected device.
  static Future<void> sendWifi(String ssid, String password) async {
    await _methodChannel.invokeMethod('sendWifi', {
      'ssid': ssid,
      'password': password,
    });
  }
}
