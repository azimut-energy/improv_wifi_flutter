package be.azimut.improv_wifi

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
// SDK classes are now in the same package
// import com.wifi.improv.ImprovManager
// import com.wifi.improv.ImprovManagerCallback
// import com.wifi.improv.ImprovDevice
// import com.wifi.improv.DeviceState
// import com.wifi.improv.ErrorState

class ImprovWifiPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler,
    ActivityAware, PluginRegistry.RequestPermissionsResultListener {

    companion object {
        private const val REQUEST_BLE_PERMISSIONS = 1001
    }

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var context: Context? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var improvManager: ImprovManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingScanResult: Result? = null

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

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "be.azimut.improv_wifi/methods")
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "be.azimut.improv_wifi/state")
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
                handleStartScan(result)
            }
            "stopScan" -> {
                improvManager?.stopScan()
                result.success(null)
            }
            "connectToDevice" -> {
                val deviceId = call.argument<String>("deviceId")
                if (deviceId == null) {
                    result.error("INVALID_ARGUMENTS", "deviceId is required", null)
                    return
                }
                val device = foundDevices[deviceId]
                if (device == null) {
                    result.error("DEVICE_NOT_FOUND", "Device with id $deviceId not found", null)
                    return
                }
                improvManager?.connectToDevice(device)
                result.success(null)
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
        if (improvManager == null && context != null) {
            improvManager = ImprovManager(context!!, callback)
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

    // --- BLE permission handling ---

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean {
        val ctx = context ?: return false
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleStartScan(result: Result) {
        if (hasPermissions()) {
            doStartScan(result)
            return
        }
        val act = activity
        if (act == null) {
            result.error("PERMISSION_DENIED", "No activity available to request permissions", null)
            return
        }
        pendingScanResult = result
        ActivityCompat.requestPermissions(act, requiredPermissions(), REQUEST_BLE_PERMISSIONS)
    }

    private fun doStartScan(result: Result) {
        ensureManagerInitialized()
        foundDevices.clear()
        improvManager?.findDevices()
        result.success(null)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != REQUEST_BLE_PERMISSIONS) return false
        val result = pendingScanResult ?: return false
        pendingScanResult = null

        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            doStartScan(result)
        } else {
            result.error("PERMISSION_DENIED", "BLE permissions were denied by the user", null)
        }
        return true
    }

    // --- ActivityAware lifecycle ---

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activity = null
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activity = null
        activityBinding = null
    }
}
