package be.azimut.improv_wifi

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission") // Permission checks are expected to be done by the app
class ImprovManager(
    private val context: Context,
    private val callback: ImprovManagerCallback
) {

    companion object {
        private const val TAG = "ImprovManager"

        private val UUID_SERVICE_PROVISION: UUID =
            UUID.fromString("00467768-6228-2272-4663-277478268000")
        private val UUID_CHAR_CURRENT_STATE: UUID =
            UUID.fromString("00467768-6228-2272-4663-277478268001")
        private val UUID_CHAR_ERROR_STATE: UUID =
            UUID.fromString("00467768-6228-2272-4663-277478268002")
        private val UUID_CHAR_RPC: UUID =
            UUID.fromString("00467768-6228-2272-4663-277478268003")
        private val UUID_CHAR_RPC_RESULT: UUID =
            UUID.fromString("00467768-6228-2272-4663-277478268004")
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val scanner = bluetoothManager.adapter.bluetoothLeScanner
    private val scanFilter =
        ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID_SERVICE_PROVISION)).build()
    private val scanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private val foundDevices = mutableMapOf<String, BluetoothDevice>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            with(result.device) {
                foundDevices[address] = this
                callback.onDeviceFound(ImprovDevice(name, address))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionAttemptCount = 0
                    bluetoothGatt = gatt
                    callback.onConnectionStateChange(
                        ImprovDevice(gatt.device.name, gatt.device.address)
                    )
                    operationQueue.add(RequestLargeMtu)
                    operationQueue.add(DiscoverServices)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    bluetoothGatt = null
                    clearOperationQueue()
                    callback.onConnectionStateChange(null)
                }
            } else {
                Log.e(TAG, "GATT error: status=$status (${getGattErrorString(status)})")
                gatt.close()
                bluetoothGatt = null
                clearOperationQueue()
                callback.onConnectionStateChange(null)
            }

            if (pendingOperation is Connect)
                signalEndOfOperation()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                UUID_CHAR_CURRENT_STATE -> {
                    val value =
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0).toUByte()
                    val deviceState = DeviceState.values().firstOrNull { it.value == value }
                    if (deviceState != null)
                        callback.onStateChange(deviceState)
                    else
                        Log.e(TAG, "Unable to determine Current State from value $value")
                }
                UUID_CHAR_ERROR_STATE -> {
                    val value =
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0).toUByte()
                    val errorState = ErrorState.values().firstOrNull { it.value == value }
                    if (errorState != null)
                        callback.onErrorStateChange(errorState)
                    else
                        Log.e(TAG, "Unable to determine Error State from value $value")
                }
                UUID_CHAR_RPC_RESULT -> {
                    val result = extractResultStrings(characteristic.value)
                    if (result != null)
                        callback.onRpcResult(result)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic ${characteristic.uuid} write failed with status $status")
            }
            if (pendingOperation is CharacteristicWrite)
                signalEndOfOperation()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicChanged(gatt, characteristic)
            } else {
                Log.e(TAG, "Characteristic ${characteristic.uuid} read failed with status $status")
            }
            if (pendingOperation is CharacteristicRead)
                signalEndOfOperation()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor ${descriptor.uuid} write failed with status $status")
            }
            if (pendingOperation is DescriptorWrite)
                signalEndOfOperation()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt.services.isEmpty()) {
                Log.e(TAG, "No services found")
            }

            val service = gatt.getService(UUID_SERVICE_PROVISION)
            val currentStateChar = service.getCharacteristic(UUID_CHAR_CURRENT_STATE)
            if ((currentStateChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                enqueueOperation(CharacteristicRead(currentStateChar))
            }
            if (gatt.setCharacteristicNotification(currentStateChar, true)) {
                currentStateChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for Current State notifications")

            val errorStateChar = service.getCharacteristic(UUID_CHAR_ERROR_STATE)
            if ((errorStateChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                enqueueOperation(CharacteristicRead(errorStateChar))
            }
            if (gatt.setCharacteristicNotification(errorStateChar, true)) {
                errorStateChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for Error State notifications")

            val rpcResultChar = service.getCharacteristic(UUID_CHAR_RPC_RESULT)
            if ((rpcResultChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                enqueueOperation(CharacteristicRead(rpcResultChar))
            }
            if (gatt.setCharacteristicNotification(rpcResultChar, true)) {
                rpcResultChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for RPC Result notifications")

            if (pendingOperation is DiscoverServices)
                signalEndOfOperation()
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            handler.removeCallbacksAndMessages(null)
            if (pendingOperation is RequestLargeMtu)
                signalEndOfOperation()
        }
    }

    private var isScanning = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var lastConnectionAttemptTime: Long = 0
    private var connectionAttemptCount: Int = 0

    fun stopScan() {
        if (isScanning) {
            scanner.stopScan(scanCallback)
            callback.onScanningStateChange(false)
        }
        isScanning = false
    }

    fun findDevices() {
        if (isScanning) {
            scanner.stopScan(scanCallback)
        }
        isScanning = true
        callback.onScanningStateChange(true)
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    fun connectToDevice(device: ImprovDevice) {
        stopScan()

        if (foundDevices.containsKey(device.address)) {
            val alreadyInQueue = operationQueue.any { it is Connect && it.device.address == device.address }
            val alreadyPending = pendingOperation is Connect && (pendingOperation as Connect).device.address == device.address

            if (alreadyInQueue || alreadyPending) {
                return
            }

            bluetoothGatt?.let { existingGatt ->
                existingGatt.close()
                bluetoothGatt = null
            }

            enqueueOperation(Connect(foundDevices[device.address]!!))
        } else {
            Log.e(TAG, "Device ${device.address} not found in scan results")
        }
    }

    fun identifyDevice() {
        if (bluetoothGatt == null) {
            error("Not Connected to a Device!")
        }
        bluetoothGatt?.let {
            val rpc = it.getService(UUID_SERVICE_PROVISION)?.getCharacteristic(UUID_CHAR_RPC)
            if (rpc != null) {
                sendRpc(rpc, RpcCommand.IDENTIFY, arrayOf())
            }
        }

    }

    fun disconnectDevice() {
        bluetoothGatt?.disconnect()
    }

    fun sendWifi(ssid: String, password: String) {
        if (bluetoothGatt == null) {
            error("Not Connected to a Device!")
        }
        bluetoothGatt?.let {
            val rpc = it.getService(UUID_SERVICE_PROVISION)?.getCharacteristic(UUID_CHAR_RPC)
            if (rpc != null) {
                val encodedSsid = ssid.encodeToByteArray()
                val encodedPassword = password.encodeToByteArray()
                val data =
                    arrayOf(encodedSsid.size.toUByte()) + encodedSsid.toUByteArray() + encodedPassword.size.toUByte() + encodedPassword.toUByteArray()
                sendRpc(rpc, RpcCommand.SEND_WIFI, data)
            }
        }
    }

    private fun sendRpc(rpc: BluetoothGattCharacteristic, command: RpcCommand, data: Array<UByte>) {
        val payload = arrayOf(command.value, data.size.toUByte()) + data + 0.toUByte()
        payload[payload.size - 1] = payload.reduce { sum, cur -> (sum + cur).toUByte() }
        rpc.value = payload.toUByteArray().toByteArray()
        enqueueOperation(CharacteristicWrite(rpc))
    }

    private fun extractResultStrings(data: ByteArray): List<String>? {
        if (data.size < 3) return null

        val strings = mutableListOf<String>()
        var currentIndex = 2

        while (currentIndex < data.size) {
            val stringLength = data[currentIndex].toInt()
            currentIndex++

            if (currentIndex + stringLength > data.size) return strings

            try {
                val string = data.decodeToString(currentIndex, currentIndex + stringLength, throwOnInvalidSequence = true)
                currentIndex += stringLength
                strings += string
            } catch (e: Exception) {
                return strings
            }
        }

        return strings
    }

    private val operationQueue = ConcurrentLinkedQueue<BleOperationType>()
    private var pendingOperation: BleOperationType? = null
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    private fun enqueueOperation(operation: BleOperationType) {
        operationQueue.add(operation)
        if (pendingOperation == null) {
            doNextOperation()
        }
    }

    @Synchronized
    private fun doNextOperation() {
        if (pendingOperation != null) {
            return
        }

        val operation = operationQueue.poll() ?: return
        pendingOperation = operation

        when (operation) {
            is Connect -> {
                val device = operation.device
                connectionAttemptCount++
                lastConnectionAttemptTime = System.currentTimeMillis()

                try {
                    val gatt = operation.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    if (gatt == null) {
                        Log.e(TAG, "connectGatt returned null")
                        signalEndOfOperation()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during connectGatt: ${e.message}")
                    signalEndOfOperation()
                }
            }
            is Disconnect -> {
                // Noop
            }
            is DiscoverServices -> {
                if (bluetoothGatt != null) {
                    bluetoothGatt!!.discoverServices()
                } else {
                    Log.e(TAG, "Tried to discover services without device connected.")
                }
            }
            is CharacteristicWrite -> {
                if (bluetoothGatt != null) {
                    bluetoothGatt!!.writeCharacteristic(operation.char)
                } else {
                    Log.e(TAG, "Tried writing characteristic without device connected.")
                }
            }
            is CharacteristicRead -> {
                if (bluetoothGatt != null) {
                    bluetoothGatt!!.readCharacteristic(operation.char)
                } else {
                    Log.e(TAG, "Tried reading characteristic without device connected.")
                }
            }
            is DescriptorWrite -> {
                if (bluetoothGatt != null) {
                    bluetoothGatt!!.writeDescriptor(operation.desc)
                } else {
                    Log.e(TAG, "Tried writing descriptor without device connected.")
                }
            }
            is RequestLargeMtu -> {
                if (bluetoothGatt != null) {
                    bluetoothGatt!!.requestMtu(517)
                    handler.postDelayed({
                        if (pendingOperation is RequestLargeMtu) {
                            Log.w(TAG, "MTU request timed out, proceeding with default MTU")
                            signalEndOfOperation()
                        }
                    }, 5000)
                } else {
                    Log.e(TAG, "Tried requesting MTU without device connected.")
                    signalEndOfOperation()
                }
            }
            else -> {
                error("Unhandled Operation!")
            }
        }
    }

    @Synchronized
    private fun signalEndOfOperation() {
        pendingOperation = null
        if (operationQueue.isNotEmpty()) {
            doNextOperation()
        }
    }

    @Synchronized
    private fun clearOperationQueue() {
        handler.removeCallbacksAndMessages(null)
        operationQueue.clear()
        pendingOperation = null
    }

    private fun getGattErrorString(status: Int): String {
        return when (status) {
            BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
            BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
            BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
            BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
            BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED"
            BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
            8 -> "GATT_INSUF_AUTHORIZATION"
            19 -> "GATT_CONN_TERMINATE_PEER_USER"
            22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
            34 -> "GATT_CONN_LMP_TIMEOUT"
            62 -> "GATT_CONN_FAIL_ESTABLISH"
            133 -> "GATT_ERROR"
            256 -> "GATT_CONN_CANCEL"
            257 -> "GATT_BUSY"
            else -> "UNKNOWN_ERROR"
        }
    }
}
