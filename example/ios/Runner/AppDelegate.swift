import Flutter
import UIKit
import SystemConfiguration.CaptiveNetwork
import CoreLocation

@main
@objc class AppDelegate: FlutterAppDelegate, CLLocationManagerDelegate {
  private var locationManager: CLLocationManager?
  private var ssidResult: FlutterResult?

  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    let registrar = self.registrar(forPlugin: "WifiInfoPlugin")!
    let channel = FlutterMethodChannel(
      name: "com.example.improv_wifi_example/wifi_info",
      binaryMessenger: registrar.messenger()
    )
    channel.setMethodCallHandler { [weak self] call, result in
      if call.method == "getCurrentSsid" {
        self?.getCurrentSsid(result: result)
      } else {
        result(FlutterMethodNotImplemented)
      }
    }
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  private func getCurrentSsid(result: @escaping FlutterResult) {
    // iOS 13+ requires location permission for CNCopyCurrentNetworkInfo
    if #available(iOS 13.0, *) {
      let status = CLLocationManager.authorizationStatus()
      if status == .notDetermined {
        ssidResult = result
        let manager = CLLocationManager()
        manager.delegate = self
        manager.requestWhenInUseAuthorization()
        locationManager = manager
        return
      }
      if status == .denied || status == .restricted {
        result(nil)
        return
      }
    }

    fetchSsid(result: result)
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    handleLocationAuthChange(status: manager.authorizationStatus)
  }

  func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
    handleLocationAuthChange(status: status)
  }

  private func handleLocationAuthChange(status: CLAuthorizationStatus) {
    guard let result = ssidResult else { return }
    ssidResult = nil
    locationManager = nil
    if status == .authorizedWhenInUse || status == .authorizedAlways {
      fetchSsid(result: result)
    } else {
      result(nil)
    }
  }

  private func fetchSsid(result: @escaping FlutterResult) {
    guard let interfaces = CNCopySupportedInterfaces() as? [String] else {
      result(nil)
      return
    }
    for interface in interfaces {
      guard let info = CNCopyCurrentNetworkInfo(interface as CFString) as? [String: Any],
            let ssid = info[kCNNetworkInfoKeySSID as String] as? String else {
        continue
      }
      result(ssid)
      return
    }
    result(nil)
  }
}
