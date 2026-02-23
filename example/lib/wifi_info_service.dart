import 'dart:async';

import 'package:flutter/services.dart';

/// Gets current WiFi SSID via platform channel (no extra dependencies).
class WifiInfoService {
  static const _channel = MethodChannel(
    'be.azimut.improv_wifi_example/wifi_info',
  );

  /// Returns the current WiFi SSID, or null if unavailable (e.g. not on WiFi,
  /// permission denied, or simulator).
  static Future<String?> getCurrentSsid() async {
    try {
      final result = await _channel.invokeMethod<String>('getCurrentSsid');
      if (result != null && result.isNotEmpty) {
        // Android returns SSID wrapped in quotes
        if (result.startsWith('"') && result.endsWith('"')) {
          return result.substring(1, result.length - 1);
        }
        return result;
      }
    } on PlatformException {
      // Permission denied, not on WiFi, etc.
    }
    return null;
  }
}
