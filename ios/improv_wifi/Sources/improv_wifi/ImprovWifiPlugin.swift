import Flutter
import UIKit
import CoreBluetooth
import Combine
import Improv_iOS

public class ImprovWifiPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var eventSink: FlutterEventSink?
    private var cancellables = Set<AnyCancellable>()
    private var peripheralMap: [String: CBPeripheral] = [:]

    public static func register(with registrar: FlutterPluginRegistrar) {
        let methodChannel = FlutterMethodChannel(
            name: "com.example.improv_wifi/methods",
            binaryMessenger: registrar.messenger()
        )
        let eventChannel = FlutterEventChannel(
            name: "com.example.improv_wifi/state",
            binaryMessenger: registrar.messenger()
        )

        let instance = ImprovWifiPlugin()
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        eventChannel.setStreamHandler(instance)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startScan":
            ImprovManager.shared.scan()
            result(nil)
        case "stopScan":
            ImprovManager.shared.stopScan()
            result(nil)
        case "connectToDevice":
            guard let args = call.arguments as? [String: Any],
                  let deviceId = args["deviceId"] as? String else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "deviceId is required", details: nil))
                return
            }
            guard let peripheral = peripheralMap[deviceId] else {
                result(FlutterError(code: "DEVICE_NOT_FOUND", message: "Device with id \(deviceId) not found", details: nil))
                return
            }
            ImprovManager.shared.connectToDevice(peripheral)
            result(nil)
        case "disconnectDevice":
            if let peripheral = ImprovManager.shared.connectedDevice {
                ImprovManager.shared.disconnectFromDevice(peripheral)
            }
            result(nil)
        case "identifyDevice":
            ImprovManager.shared.identifyDevice()
            result(nil)
        case "sendWifi":
            guard let args = call.arguments as? [String: Any],
                  let ssid = args["ssid"] as? String,
                  let password = args["password"] as? String else {
                result(FlutterError(code: "INVALID_ARGUMENTS", message: "ssid and password are required", details: nil))
                return
            }
            ImprovManager.shared.sendWifi(ssid: ssid, password: password)
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    // MARK: - FlutterStreamHandler

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        setupSubscriptions()
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        cancellables.removeAll()
        return nil
    }

    // MARK: - Private

    private func setupSubscriptions() {
        guard let manager = ImprovManager.shared as? ImprovManager else { return }

        // Subscribe to all @Published properties and send consolidated state updates
        manager.$foundDevices
            .sink { [weak self] (_: [String: CBPeripheral]) in self?.sendStateUpdate() }
            .store(in: &cancellables)

        manager.$connectedDevice
            .sink { [weak self] (_: CBPeripheral?) in self?.sendStateUpdate() }
            .store(in: &cancellables)

        manager.$bluetoothState
            .sink { [weak self] (_: CBManagerState) in self?.sendStateUpdate() }
            .store(in: &cancellables)

        manager.$deviceState
            .sink { [weak self] (_: DeviceState?) in self?.sendStateUpdate() }
            .store(in: &cancellables)

        manager.$errorState
            .sink { [weak self] (_: ErrorState?) in self?.sendStateUpdate() }
            .store(in: &cancellables)

        manager.$lastResult
            .sink { [weak self] (_: [String]?) in self?.sendStateUpdate() }
            .store(in: &cancellables)
    }

    private func sendStateUpdate() {
        guard let eventSink = eventSink else { return }

        let manager = ImprovManager.shared

        // Update peripheral map (SDK uses [String: CBPeripheral])
        peripheralMap.removeAll()
        for peripheral in manager.foundDevices.values {
            peripheralMap[peripheral.identifier.uuidString] = peripheral
        }

        // Build found devices list
        let foundDevices: [[String: Any?]] = manager.foundDevices.values.map { peripheral in
            return [
                "id": peripheral.identifier.uuidString,
                "name": peripheral.name
            ]
        }

        // Build state map
        var state: [String: Any?] = [
            "foundDevices": foundDevices,
            "connectedDeviceId": manager.connectedDevice?.identifier.uuidString,
            "bluetoothState": bluetoothStateToString(manager.bluetoothState),
            "deviceState": deviceStateToString(manager.deviceState),
            "errorState": errorStateToString(manager.errorState),
            "lastResult": manager.lastResult
        ]

        eventSink(state)
    }

    private func bluetoothStateToString(_ state: CBManagerState) -> String {
        switch state {
        case .unknown: return "unknown"
        case .resetting: return "resetting"
        case .unsupported: return "unsupported"
        case .unauthorized: return "unauthorized"
        case .poweredOff: return "poweredOff"
        case .poweredOn: return "poweredOn"
        @unknown default: return "unknown"
        }
    }

    private func deviceStateToString(_ state: DeviceState?) -> String? {
        guard let state = state else { return nil }
        switch state {
        case .authorizationRequired: return "authorizationRequired"
        case .authorized: return "authorized"
        case .provisioning: return "provisioning"
        case .provisioned: return "provisioned"
        @unknown default: return nil
        }
    }

    private func errorStateToString(_ state: ErrorState?) -> String? {
        guard let state = state else { return nil }
        switch state {
        case .noError: return "noError"
        case .invalidRPCPacket: return "invalidRPCPacket"
        case .unknownCommand: return "unknownCommand"
        case .unableToConnect: return "unableToConnect"
        case .notAuthorized: return "notAuthorized"
        case .unknown: return "unknown"
        @unknown default: return "unknown"
        }
    }
}
