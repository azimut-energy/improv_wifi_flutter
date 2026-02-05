import 'package:flutter/material.dart';
import 'dart:async';

import 'package:improv_wifi/improv_wifi.dart';

import 'wifi_info_service.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  ImprovState? _state;
  StreamSubscription<ImprovState>? _subscription;
  final _ssidController = TextEditingController(
    text: "FRITZ!Box 7530 BG",
  );
  final _passwordController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _subscription = ImprovWifi.stateStream.listen((state) {
      print("State: $state");
      setState(() {
        _state = state;
      });
    });
    // Defer so the platform channel is ready
    WidgetsBinding.instance.addPostFrameCallback((_) => _prefillSsid());
  }

  Future<void> _prefillSsid({BuildContext? context}) async {
    final ssid = await WifiInfoService.getCurrentSsid();
    if (ssid != null && ssid.isNotEmpty && mounted) {
      _ssidController.text = ssid;
    } else if (context != null && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Could not get WiFi name. Grant location permission and try again.',
          ),
          duration: Duration(seconds: 4),
        ),
      );
    }
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _ssidController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Improv WiFi Example')),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildStateInfo(),
              const SizedBox(height: 16),
              _buildScanControls(),
              const SizedBox(height: 16),
              _buildDeviceList(),
              const SizedBox(height: 16),
              _buildConnectionControls(),
              const SizedBox(height: 16),
              _buildWifiForm(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStateInfo() {
    final state = _state;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Bluetooth: ${state?.bluetoothState.name ?? "unknown"}'),
            Text('Device State: ${state?.deviceState?.name ?? "none"}'),
            Text('Error: ${state?.errorState?.name ?? "none"}'),
            Text('Connected: ${state?.connectedDeviceId ?? "none"}'),
            if (state?.lastResult != null)
              Text('Result: ${state!.lastResult!.join(", ")}'),
          ],
        ),
      ),
    );
  }

  Widget _buildScanControls() {
    return Row(
      children: [
        ElevatedButton(
          onPressed: () => ImprovWifi.startScan(),
          child: const Text('Start Scan'),
        ),
        const SizedBox(width: 8),
        ElevatedButton(
          onPressed: () => ImprovWifi.stopScan(),
          child: const Text('Stop Scan'),
        ),
      ],
    );
  }

  Widget _buildDeviceList() {
    final devices = _state?.foundDevices ?? [];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Found Devices (${devices.length}):',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            if (devices.isEmpty)
              const Text('No devices found')
            else
              ...devices.map(
                (device) => ListTile(
                  title: Text(device.name ?? 'Unknown'),
                  subtitle: Text(device.id),
                  trailing: ElevatedButton(
                    onPressed: () {
                      print('[UI] Connect button pressed for device: ${device.id}');
                      ImprovWifi.connectToDevice(device.id);
                    },
                    child: const Text('Connect'),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildConnectionControls() {
    final isConnected = _state?.connectedDeviceId != null;
    return Row(
      children: [
        ElevatedButton(
          onPressed: isConnected ? () => ImprovWifi.disconnectDevice() : null,
          child: const Text('Disconnect'),
        ),
        const SizedBox(width: 8),
        ElevatedButton(
          onPressed: isConnected ? () => ImprovWifi.identifyDevice() : null,
          child: const Text('Identify'),
        ),
      ],
    );
  }

  Widget _buildWifiForm() {
    final isConnected = _state?.connectedDeviceId != null;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'WiFi Credentials:',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _ssidController,
                    decoration: const InputDecoration(
                      labelText: 'SSID',
                      border: OutlineInputBorder(),
                    ),
                    
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  onPressed: () => _prefillSsid(context: context),
                  tooltip: 'Use current network',
                  icon: const Icon(Icons.wifi_find),
                ),
              ],
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _passwordController,
              decoration: const InputDecoration(
                labelText: 'Password',
                border: OutlineInputBorder(),
              ),
              obscureText: true,
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: isConnected
                  ? () => ImprovWifi.sendWifi(
                      _ssidController.text,
                      _passwordController.text,
                    )
                  : null,
              child: const Text('Send WiFi'),
            ),
          ],
        ),
      ),
    );
  }
}
