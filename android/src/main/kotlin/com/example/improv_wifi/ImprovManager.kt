package com.example.improv_wifi

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
                Log.i(TAG, "Found BLE device! Name: ${name ?: "Unnamed"}, address: $address, rssi: ${result.rssi}")
                Log.d(TAG, "  Device type: ${when(type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                    BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                    else -> "UNKNOWN($type)"
                }}")
                Log.d(TAG, "  Bond state: ${when(bondState) {
                    BluetoothDevice.BOND_BONDED -> "BONDED"
                    BluetoothDevice.BOND_BONDING -> "BONDING"
                    BluetoothDevice.BOND_NONE -> "NONE"
                    else -> "UNKNOWN($bondState)"
                }}")
                result.scanRecord?.serviceUuids?.let { uuids ->
                    Log.d(TAG, "  Advertised UUIDs: ${uuids.joinToString()}")
                }
                foundDevices[address] = this
                Log.d(TAG, "Added to foundDevices, now contains ${foundDevices.size} devices")
                callback.onDeviceFound(ImprovDevice(name, address))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val timeSinceConnectionAttempt = System.currentTimeMillis() - lastConnectionAttemptTime
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: "Unknown"
            val deviceType = when (gatt.device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                else -> "UNKNOWN"
            }
            val bondState = when (gatt.device.bondState) {
                BluetoothDevice.BOND_BONDED -> "BONDED"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_NONE -> "NONE"
                else -> "UNKNOWN"
            }
            val stateString = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN($newState)"
            }
            Log.d(TAG, "onConnectionStateChange: device=$deviceAddress ($deviceName), type=$deviceType, bond=$bondState, status=$status, newState=$stateString")
            Log.d(TAG, "  Bluetooth adapter state: ${bluetoothManager.adapter.state}, isEnabled: ${bluetoothManager.adapter.isEnabled}")
            Log.d(TAG, "  Time since connection attempt: ${timeSinceConnectionAttempt}ms")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceAddress in ${timeSinceConnectionAttempt}ms, discovering services.")
                    connectionAttemptCount = 0  // Reset counter on successful connection
                    bluetoothGatt = gatt
                    callback.onConnectionStateChange(
                        ImprovDevice(
                            gatt.device.name,
                            gatt.device.address
                        )
                    )

                    operationQueue.add(RequestLargeMtu)
                    operationQueue.add(DiscoverServices)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.w(TAG, "Successfully disconnected from $deviceAddress")
                    gatt.close()
                    bluetoothGatt = null
                    callback.onConnectionStateChange(null)
                }
            } else {
                Log.e(TAG, "=== GATT ERROR DETECTED ===")
                Log.e(TAG, "  Status: $status (${getGattErrorString(status)})")
                Log.e(TAG, "  Device: $deviceAddress ($deviceName)")
                Log.e(TAG, "  New State: $stateString")

                if (status == 133) {
                    Log.e(TAG, "  Error 133 common causes:")
                    Log.e(TAG, "    1. Device is out of range or powered off")
                    Log.e(TAG, "    2. BLE stack issue (try: disable/enable Bluetooth, restart app)")
                    Log.e(TAG, "    3. Too many rapid connection attempts")
                    Log.e(TAG, "    4. Device not advertising anymore")
                    Log.e(TAG, "    5. Bonding/pairing issues")
                    Log.e(TAG, "    6. Using autoConnect=true may need autoConnect=false")
                    Log.e(TAG, "  Current operation pending: $pendingOperation")
                    Log.e(TAG, "  Operations in queue: ${operationQueue.size}")
                }

                gatt.close()
                bluetoothGatt = null
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
                    Log.i(TAG, "Current State has changed to $value.")
                    val deviceState = DeviceState.values().firstOrNull { it.value == value }
                    if (deviceState != null)
                        callback.onStateChange(deviceState)
                    else
                        Log.e(TAG, "Unable to determine Current State")
                }
                UUID_CHAR_ERROR_STATE -> {
                    val value =
                        characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0).toUByte()
                    Log.i(TAG, "Error State has changed to $value.")
                    val errorState = ErrorState.values().firstOrNull { it.value == value }
                    if (errorState != null)
                        callback.onErrorStateChange(errorState)
                    else
                        Log.e(TAG, "Unable to determine Error State")
                }
                UUID_CHAR_RPC_RESULT -> {
                    Log.i(TAG, "RPC Result has changed to ${characteristic.value.joinToString()}.")
                    val result = extractResultStrings(characteristic.value)
                    if (result != null)
                        callback.onRpcResult(result)
                    else
                        Log.w(TAG, "Received empty RPC Result")
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Char ${characteristic.uuid} write complete")
            } else {
                Log.e(TAG, "Char ${characteristic.uuid} not written!!")
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
                Log.i(TAG, "Char ${characteristic.uuid} read complete: ${characteristic.value}")
                onCharacteristicChanged(gatt, characteristic)
            } else {
                Log.e(TAG, "Char ${characteristic.uuid} not read!!")
            }
            if (pendingOperation is CharacteristicRead)
                signalEndOfOperation()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Desc ${descriptor.uuid} write complete")
            } else {
                Log.e(TAG, "Desc ${descriptor.uuid} not written!!")
            }
            if (pendingOperation is DescriptorWrite)
                signalEndOfOperation()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            for (service in gatt.services) {
                val sb = StringBuilder("Found service: ${service.uuid}")
                for (char in service.characteristics) {
                    sb.append("\n\tChar: ${char.uuid}, Value: ${char.value}")
                }
                Log.i(TAG, sb.toString())
            }
            if (gatt.services.isEmpty())
                Log.e(TAG, "No Services Found!!")

            val service = gatt.getService(UUID_SERVICE_PROVISION)
            val currentStateChar = service.getCharacteristic(UUID_CHAR_CURRENT_STATE)
            // Try to read initial state if the characteristic supports it
            if ((currentStateChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                Log.d(TAG, "Current State supports READ, attempting to read initial value")
                enqueueOperation(CharacteristicRead(currentStateChar))
            } else {
                Log.d(TAG, "Current State does not support READ, will rely on notifications")
            }
            if (gatt.setCharacteristicNotification(currentStateChar, true)) {
                Log.i(
                    TAG,
                    "Registered for Current State Notifications, descriptors: ${currentStateChar.descriptors}}"
                )
                currentStateChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for Current State Notifications")

            val errorStateChar = service.getCharacteristic(UUID_CHAR_ERROR_STATE)
            // Try to read initial state if the characteristic supports it
            if ((errorStateChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                Log.d(TAG, "Error State supports READ, attempting to read initial value")
                enqueueOperation(CharacteristicRead(errorStateChar))
            } else {
                Log.d(TAG, "Error State does not support READ, will rely on notifications")
            }
            if (gatt.setCharacteristicNotification(errorStateChar, true)) {
                Log.i(TAG, "Registered for Error State Notifications")
                errorStateChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for Error State Notifications")

            val rpcResultChar = service.getCharacteristic(UUID_CHAR_RPC_RESULT)
            // Try to read initial state if the characteristic supports it
            if ((rpcResultChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                Log.d(TAG, "RPC Result supports READ, attempting to read initial value")
                enqueueOperation(CharacteristicRead(rpcResultChar))
            } else {
                Log.d(TAG, "RPC Result does not support READ, will rely on notifications")
            }
            if (gatt.setCharacteristicNotification(rpcResultChar, true)) {
                Log.i(TAG, "Registered for RPC Result Notifications")
                rpcResultChar.descriptors.firstOrNull()?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enqueueOperation(DescriptorWrite(it))
                }
            } else
                Log.e(TAG, "Unable to register for RPC Result Notifications")

            if (pendingOperation is DiscoverServices)
                signalEndOfOperation()
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.d(TAG, "MTU change to $mtu returned status: $status")
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
        Log.i(TAG, "Find Devices")
        isScanning = true
        callback.onScanningStateChange(true)
        scanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    fun connectToDevice(device: ImprovDevice) {
        Log.i(TAG, "=== connectToDevice called ===")
        Log.i(TAG, "  Target device: ${device.address}")
        Log.d(TAG, "  foundDevices contains ${foundDevices.size} devices: ${foundDevices.keys}")
        Log.d(TAG, "  Current bluetoothGatt: ${if(bluetoothGatt != null) "connected to ${bluetoothGatt?.device?.address}" else "null"}")
        Log.d(TAG, "  Pending operation: $pendingOperation")
        Log.d(TAG, "  Operations in queue: ${operationQueue.size}")

        stopScan()

        if (foundDevices.containsKey(device.address)) {
            // Check if we're already trying to connect to this device
            val alreadyInQueue = operationQueue.any { it is Connect && it.device.address == device.address }
            val alreadyPending = pendingOperation is Connect && (pendingOperation as Connect).device.address == device.address

            if (alreadyInQueue || alreadyPending) {
                Log.w(TAG, "Connection to ${device.address} already in progress or queued! Skipping duplicate request.")
                return
            }

            // Close any existing GATT connection before starting a new one
            bluetoothGatt?.let { existingGatt ->
                Log.w(TAG, "Closing existing GATT connection to ${existingGatt.device.address} before connecting to new device")
                existingGatt.close()
                bluetoothGatt = null
            }

            Log.d(TAG, "Device found in cache, enqueueing connect operation")
            enqueueOperation(Connect(foundDevices[device.address]!!))
        } else {
            Log.e(TAG, "Tried to connect to a device we didn't find? Looking for ${device.address} in ${foundDevices.keys}")
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

    fun sendWifi(ssid: String, password: String) {
        Log.i(TAG, "Send Wifi")
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

        Log.d(TAG, "Sending ${payload.map { it.toString() }.toList()}")
        enqueueOperation(CharacteristicWrite(rpc))
    }

    private fun extractResultStrings(data: ByteArray): List<String>? {
        // Ensure the data is at least 3 bytes long to read the first string length
        if (data.size < 3) return null

        val strings = mutableListOf<String>()
        var currentIndex = 2 // Start after the first two bytes

        while (currentIndex < data.size) {
            // Get the length of the current string
            val stringLength = data[currentIndex].toInt()
            currentIndex++

            // Ensure there are enough bytes left for the current string
            if (currentIndex + stringLength > data.size) return strings

            // Extract the string and add it to the list
            try {
                val string = data.decodeToString(currentIndex, currentIndex + stringLength, throwOnInvalidSequence = true)
                currentIndex += stringLength
                strings += string
            } catch (e: Exception) {
                Log.e(TAG, "Invalid string encoding, returning strings previously decoded")
                return strings
            }
        }

        return strings
    }

    private val operationQueue = ConcurrentLinkedQueue<BleOperationType>()
    private var pendingOperation: BleOperationType? = null

    @Synchronized
    private fun enqueueOperation(operation: BleOperationType) {
        Log.d(TAG, "enqueueOperation: Adding $operation to queue (current size: ${operationQueue.size}, pending: ${pendingOperation != null})")
        operationQueue.add(operation)
        if (pendingOperation == null) {
            Log.d(TAG, "enqueueOperation: No pending operation, calling doNextOperation()")
            doNextOperation()
        } else {
            Log.d(TAG, "enqueueOperation: Operation pending ($pendingOperation), queuing for later")
        }
    }

    @Synchronized
    private fun doNextOperation() {
        if (pendingOperation != null) {
            Log.e(TAG, "doNextOperation() called when an operation is pending! Aborting.")
            return
        }

        val operation = operationQueue.poll() ?: run {
            Log.v(TAG, "Operation queue empty, returning")
            return
        }
        pendingOperation = operation

        when (operation) {
            is Connect -> {
                val device = operation.device
                val currentTime = System.currentTimeMillis()
                val timeSinceLastAttempt = currentTime - lastConnectionAttemptTime
                connectionAttemptCount++

                Log.d(TAG, "=== Attempting to connect to device (attempt #$connectionAttemptCount) ===")
                Log.d(TAG, "  Address: ${device.address}")
                Log.d(TAG, "  Name: ${device.name ?: "Unknown"}")
                Log.d(TAG, "  Type: ${when(device.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
                    BluetoothDevice.DEVICE_TYPE_LE -> "LE"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
                    else -> "UNKNOWN(${device.type})"
                }}")
                Log.d(TAG, "  Bond state: ${when(device.bondState) {
                    BluetoothDevice.BOND_BONDED -> "BONDED"
                    BluetoothDevice.BOND_BONDING -> "BONDING"
                    BluetoothDevice.BOND_NONE -> "NONE"
                    else -> "UNKNOWN(${device.bondState})"
                }}")
                Log.d(TAG, "  Current bluetoothGatt: ${if(bluetoothGatt != null) "NOT NULL (already connected?)" else "null (OK)"}")
                Log.d(TAG, "  Adapter state: ${bluetoothManager.adapter.state}, enabled: ${bluetoothManager.adapter.isEnabled}")
                Log.d(TAG, "  Time since last attempt: ${timeSinceLastAttempt}ms")
                Log.d(TAG, "  Using autoConnect: false (direct connection)")

                if (timeSinceLastAttempt < 1000 && connectionAttemptCount > 1) {
                    Log.w(TAG, "  WARNING: Rapid reconnection attempt (${timeSinceLastAttempt}ms). Consider adding delay between attempts.")
                }

                lastConnectionAttemptTime = currentTime

                try {
                    val gatt = operation.device.connectGatt(context, false, gattCallback)
                    Log.d(TAG, "  connectGatt() returned: ${if(gatt != null) "BluetoothGatt instance" else "NULL (FAILED)"}")
                    if (gatt == null) {
                        Log.e(TAG, "  connectGatt returned null! This indicates an immediate failure.")
                        signalEndOfOperation()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  Exception during connectGatt: ${e.message}", e)
                    signalEndOfOperation()
                }
            }
            is Disconnect -> {
                // Noop?
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
                } else {
                    Log.e(TAG, "Tried requesting MTU without device connected.")
                }
            }
            else -> {
                error("Unhandled Operation!")
            }
        }
    }

    @Synchronized
    private fun signalEndOfOperation() {
        Log.d(TAG, "End of $pendingOperation")
        pendingOperation = null
        if (operationQueue.isNotEmpty()) {
            doNextOperation()
        }
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
            133 -> "GATT_ERROR (generic error, often BLE stack issue)"
            256 -> "GATT_CONN_CANCEL"
            257 -> "GATT_BUSY"
            else -> "UNKNOWN_ERROR"
        }
    }
}
