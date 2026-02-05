package com.example.improv_wifi

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
// SDK classes are now in the same package
// import com.wifi.improv.ImprovManager
// import com.wifi.improv.ImprovManagerCallback
// import com.wifi.improv.ImprovDevice
// import com.wifi.improv.DeviceState
// import com.wifi.improv.ErrorState

class ImprovWifiPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var context: Context? = null
    private var improvManager: ImprovManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // State tracking
    private val foundDevices = mutableMapOf<String, ImprovDevice>()
    private var connectedDevice: ImprovDevice? = null
    private var isScanning = false
    private var currentDeviceState: DeviceState? = null
    private var currentErrorState: ErrorState? = null
    private var lastResult: List<String>? = null

    private val callback = object : ImprovManagerCallback {
        override fun onScanningStateChange(scanning: Boolean) {
            isScanning = scanning
            sendStateUpdate()
        }

        override fun onDeviceFound(device: ImprovDevice) {
            foundDevices[device.address] = device
            sendStateUpdate()
        }

        override fun onConnectionStateChange(device: ImprovDevice?) {
            connectedDevice = device
            if (device == null) {
                // Reset states when disconnected
                currentDeviceState = null
                currentErrorState = null
                lastResult = null
            }
            sendStateUpdate()
        }

        override fun onStateChange(state: DeviceState) {
            currentDeviceState = state
            sendStateUpdate()
        }

        override fun onErrorStateChange(errorState: ErrorState) {
            currentErrorState = errorState
            sendStateUpdate()
        }

        override fun onRpcResult(result: List<String>) {
            lastResult = result
            sendStateUpdate()
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.example.improv_wifi/methods")
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.example.improv_wifi/state")
        eventChannel.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        context = null
        improvManager = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "startScan" -> {
                ensureManagerInitialized()
                foundDevices.clear()
                improvManager?.findDevices()
                result.success(null)
            }
            "stopScan" -> {
                improvManager?.stopScan()
                result.success(null)
            }
            "connectToDevice" -> {
                android.util.Log.d("ImprovWifiPlugin", "=== connectToDevice method called ===")
                val deviceId = call.argument<String>("deviceId")
                android.util.Log.d("ImprovWifiPlugin", "deviceId: $deviceId")
                if (deviceId == null) {
                    android.util.Log.e("ImprovWifiPlugin", "deviceId is null!")
                    result.error("INVALID_ARGUMENTS", "deviceId is required", null)
                    return
                }
                val device = foundDevices[deviceId]
                android.util.Log.d("ImprovWifiPlugin", "device found in foundDevices: ${device != null}")
                android.util.Log.d("ImprovWifiPlugin", "foundDevices contains: ${foundDevices.keys}")
                if (device == null) {
                    android.util.Log.e("ImprovWifiPlugin", "Device not found in foundDevices!")
                    result.error("DEVICE_NOT_FOUND", "Device with id $deviceId not found", null)
                    return
                }
                android.util.Log.i("ImprovWifiPlugin", "Calling improvManager.connectToDevice for ${device.name} (${device.address})")
                improvManager?.connectToDevice(device)
                result.success(null)
                android.util.Log.d("ImprovWifiPlugin", "connectToDevice completed successfully")
            }
            "disconnectDevice" -> {
                // Note: The Android SDK doesn't have a disconnectDevice method exposed
                // We reset the state
                connectedDevice = null
                currentDeviceState = null
                currentErrorState = null
                lastResult = null
                sendStateUpdate()
                result.success(null)
            }
            "identifyDevice" -> {
                try {
                    improvManager?.identifyDevice()
                    result.success(null)
                } catch (e: Exception) {
                    result.error("NOT_CONNECTED", e.message, null)
                }
            }
            "sendWifi" -> {
                val ssid = call.argument<String>("ssid")
                val password = call.argument<String>("password")
                if (ssid == null || password == null) {
                    result.error("INVALID_ARGUMENTS", "ssid and password are required", null)
                    return
                }
                try {
                    improvManager?.sendWifi(ssid, password)
                    result.success(null)
                } catch (e: Exception) {
                    result.error("NOT_CONNECTED", e.message, null)
                }
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        ensureManagerInitialized()
        sendStateUpdate()
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    private fun ensureManagerInitialized() {
        android.util.Log.d("ImprovWifiPlugin", "ensureManagerInitialized: improvManager=${improvManager != null}, context=${context != null}")
        if (improvManager == null && context != null) {
            android.util.Log.i("ImprovWifiPlugin", "Creating new ImprovManager instance")
            improvManager = ImprovManager(context!!, callback)
            android.util.Log.i("ImprovWifiPlugin", "ImprovManager created successfully")
        }
    }

    private fun sendStateUpdate() {
        val sink = eventSink ?: return

        val devicesList = foundDevices.values.map { device ->
            mapOf(
                "id" to device.address,
                "name" to device.name
            )
        }

        val state = mapOf(
            "foundDevices" to devicesList,
            "connectedDeviceId" to connectedDevice?.address,
            "bluetoothState" to "poweredOn", // Android SDK doesn't expose BT state directly
            "deviceState" to deviceStateToString(currentDeviceState),
            "errorState" to errorStateToString(currentErrorState),
            "lastResult" to lastResult
        )

        // CRITICAL: EventChannel must be called on the main thread
        mainHandler.post {
            sink.success(state)
        }
    }

    private fun deviceStateToString(state: DeviceState?): String? {
        return when (state) {
            DeviceState.AUTHORIZATION_REQUIRED -> "authorizationRequired"
            DeviceState.AUTHORIZED -> "authorized"
            DeviceState.PROVISIONING -> "provisioning"
            DeviceState.PROVISIONED -> "provisioned"
            null -> null
        }
    }

    private fun errorStateToString(state: ErrorState?): String? {
        return when (state) {
            ErrorState.NO_ERROR -> "noError"
            ErrorState.INVALID_RPC_PACKET -> "invalidRPCPacket"
            ErrorState.UNKNOWN_COMMAND -> "unknownCommand"
            ErrorState.UNABLE_TO_CONNECT -> "unableToConnect"
            ErrorState.NOT_AUTHORIZED -> "notAuthorized"
            ErrorState.UNKNOWN -> "unknown"
            null -> null
        }
    }
}
