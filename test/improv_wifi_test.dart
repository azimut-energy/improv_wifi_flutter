import 'package:flutter_test/flutter_test.dart';
import 'package:improv_wifi/improv_wifi.dart';

void main() {
  group('ImprovDevice', () {
    test('fromMap creates device correctly', () {
      final device = ImprovDevice.fromMap({
        'id': 'test-id-123',
        'name': 'Test Device',
      });

      expect(device.id, 'test-id-123');
      expect(device.name, 'Test Device');
    });

    test('fromMap handles null name', () {
      final device = ImprovDevice.fromMap({
        'id': 'test-id-123',
        'name': null,
      });

      expect(device.id, 'test-id-123');
      expect(device.name, isNull);
    });

    test('equality works correctly', () {
      final device1 = ImprovDevice(id: 'test-id', name: 'Test');
      final device2 = ImprovDevice(id: 'test-id', name: 'Test');
      final device3 = ImprovDevice(id: 'different-id', name: 'Test');

      expect(device1, equals(device2));
      expect(device1, isNot(equals(device3)));
    });
  });

  group('ImprovState', () {
    test('fromMap creates state correctly', () {
      final state = ImprovState.fromMap({
        'foundDevices': [
          {'id': 'device-1', 'name': 'Device 1'},
          {'id': 'device-2', 'name': null},
        ],
        'connectedDeviceId': 'device-1',
        'bluetoothState': 'poweredOn',
        'deviceState': 'authorized',
        'errorState': 'noError',
        'lastResult': ['http://192.168.1.100'],
      });

      expect(state.foundDevices.length, 2);
      expect(state.foundDevices[0].id, 'device-1');
      expect(state.foundDevices[0].name, 'Device 1');
      expect(state.foundDevices[1].id, 'device-2');
      expect(state.foundDevices[1].name, isNull);
      expect(state.connectedDeviceId, 'device-1');
      expect(state.bluetoothState, BluetoothState.poweredOn);
      expect(state.deviceState, DeviceState.authorized);
      expect(state.errorState, ErrorState.noError);
      expect(state.lastResult, ['http://192.168.1.100']);
    });

    test('fromMap handles empty/null values', () {
      final state = ImprovState.fromMap({
        'foundDevices': null,
        'connectedDeviceId': null,
        'bluetoothState': 'unknown',
        'deviceState': null,
        'errorState': null,
        'lastResult': null,
      });

      expect(state.foundDevices, isEmpty);
      expect(state.connectedDeviceId, isNull);
      expect(state.bluetoothState, BluetoothState.unknown);
      expect(state.deviceState, isNull);
      expect(state.errorState, isNull);
      expect(state.lastResult, isNull);
    });

    test('parses all bluetooth states', () {
      expect(
        ImprovState.fromMap({'bluetoothState': 'unknown', 'foundDevices': []}).bluetoothState,
        BluetoothState.unknown,
      );
      expect(
        ImprovState.fromMap({'bluetoothState': 'resetting', 'foundDevices': []}).bluetoothState,
        BluetoothState.resetting,
      );
      expect(
        ImprovState.fromMap({'bluetoothState': 'unsupported', 'foundDevices': []}).bluetoothState,
        BluetoothState.unsupported,
      );
      expect(
        ImprovState.fromMap({'bluetoothState': 'unauthorized', 'foundDevices': []}).bluetoothState,
        BluetoothState.unauthorized,
      );
      expect(
        ImprovState.fromMap({'bluetoothState': 'poweredOff', 'foundDevices': []}).bluetoothState,
        BluetoothState.poweredOff,
      );
      expect(
        ImprovState.fromMap({'bluetoothState': 'poweredOn', 'foundDevices': []}).bluetoothState,
        BluetoothState.poweredOn,
      );
    });

    test('parses all device states', () {
      expect(
        ImprovState.fromMap({'deviceState': 'authorizationRequired', 'foundDevices': [], 'bluetoothState': 'unknown'}).deviceState,
        DeviceState.authorizationRequired,
      );
      expect(
        ImprovState.fromMap({'deviceState': 'authorized', 'foundDevices': [], 'bluetoothState': 'unknown'}).deviceState,
        DeviceState.authorized,
      );
      expect(
        ImprovState.fromMap({'deviceState': 'provisioning', 'foundDevices': [], 'bluetoothState': 'unknown'}).deviceState,
        DeviceState.provisioning,
      );
      expect(
        ImprovState.fromMap({'deviceState': 'provisioned', 'foundDevices': [], 'bluetoothState': 'unknown'}).deviceState,
        DeviceState.provisioned,
      );
    });

    test('parses all error states', () {
      expect(
        ImprovState.fromMap({'errorState': 'noError', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.noError,
      );
      expect(
        ImprovState.fromMap({'errorState': 'invalidRPCPacket', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.invalidRPCPacket,
      );
      expect(
        ImprovState.fromMap({'errorState': 'unknownCommand', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.unknownCommand,
      );
      expect(
        ImprovState.fromMap({'errorState': 'unableToConnect', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.unableToConnect,
      );
      expect(
        ImprovState.fromMap({'errorState': 'notAuthorized', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.notAuthorized,
      );
      expect(
        ImprovState.fromMap({'errorState': 'unknown', 'foundDevices': [], 'bluetoothState': 'unknown'}).errorState,
        ErrorState.unknown,
      );
    });
  });
}
