# improv_wifi

A Flutter plugin for Improv WiFi provisioning via BLE.

## iOS – Improv SDK submodule

The iOS implementation uses the [Improv WiFi iOS SDK](https://github.com/improv-wifi/sdk-iOS) as a **git submodule** at `ios/improv_wifi/sdk-iOS`.

**Clone with submodules:**
```bash
git clone --recurse-submodules https://github.com/azimut-energy/improv_wifi_flutter.git
```

**If you already cloned:**
```bash
git submodule update --init --recursive
```

**Update the SDK to a different version:**
```bash
cd ios/improv_wifi/sdk-iOS
git fetch --tags && git checkout 0.0.6   # or another tag
cd - && git add ios/improv_wifi/sdk-iOS && git commit -m "chore(ios): pin Improv SDK to 0.0.6"
```

## Getting Started

This project is a starting point for a Flutter
[plug-in package](https://flutter.dev/to/develop-plugins),
a specialized package that includes platform-specific implementation code for
Android and/or iOS.

For help getting started with Flutter development, view the
[online documentation](https://docs.flutter.dev), which offers tutorials,
samples, guidance on mobile development, and a full API reference.

