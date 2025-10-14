package com.example.myapplication.smarthelmet

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

class BleManager(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val customSVC: UUID? = null,
    private val customSensor: UUID? = null,
    private val customCmd: UUID? = null,
    private val customAck: UUID? = null,
    private val advertisedName: String? = "SMART-HELMET-PI"
) : CoroutineScope by MainScope() {

    companion object {
        private const val TAG = "BleManager"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val NUS_SVC: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX : UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX : UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    }

    private val btManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var isScanning = false

    private val svcUuid    = customSVC ?: NUS_SVC
    private val sensorUuid = customSensor
    private val cmdUuid    = customCmd
    private val ackUuid    = customAck

    private var sensorChar: BluetoothGattCharacteristic? = null
    private var cmdChar: BluetoothGattCharacteristic? = null
    private var ackChar: BluetoothGattCharacteristic? = null
    private var nusRx: BluetoothGattCharacteristic? = null
    private var nusTx: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()
    private val _sensorFlow = MutableSharedFlow<ByteArray>(replay = 1)
    val sensorFlow: SharedFlow<ByteArray> = _sensorFlow
    private val _ackFlow = MutableSharedFlow<String>(replay = 1)
    val ackFlow: SharedFlow<String> = _ackFlow

    suspend fun awaitAck(regex: Regex, timeoutMs: Long): Boolean = withTimeout(timeoutMs) {
        ackFlow.first { it.contains(regex) }
        true
    }

    fun connect(
        onConnected: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        if (!hasBlePermissions()) {
            onError(SecurityException("BLE permissions not granted"))
            return
        }
        val bt = adapter ?: return onError(IllegalStateException("Bluetooth not supported"))
        if (!bt.isEnabled) return onError(IllegalStateException("Bluetooth disabled"))
        if (gatt != null) { onConnected(); return }

        val filters = buildList {
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(svcUuid)).build())
            advertisedName?.let { add(ScanFilter.Builder().setDeviceName(it).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            startScanInternal(filters, settings)
        } catch (se: SecurityException) {
            onError(se); return
        } catch (e: Exception) {
            onError(e); return
        }

        this.onConnectedCallback = onConnected
        this.onErrorCallback = onError
    }

    fun sendCommand(command: String, onError: (Throwable) -> Unit = {}) {
        val g = gatt ?: return onError(IllegalStateException("Not connected"))
        val c = (cmdChar ?: nusRx) ?: return onError(IllegalStateException("CMD characteristic not found"))
        launch(io) {
            try {
                c.value = command.toByteArray(Charsets.UTF_8)
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, c.value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
                if (!ok) withContext(Dispatchers.Main) { onError(IllegalStateException("writeCharacteristic failed")) }
                else Log.d(TAG, "TX: $command")
            } catch (se: SecurityException) {
                withContext(Dispatchers.Main) { onError(se) }
            } catch (e: Exception) {
                Log.e(TAG, "sendCommand error", e); withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    fun disconnect() {
        stopScan()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        sensorChar = null; cmdChar = null; ackChar = null; nusRx = null; nusTx = null
        launch { _connectionState.emit(false) }
        Log.d(TAG, "Disconnected")
    }

    fun close() { disconnect(); cancel() }

    private var onConnectedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((Throwable) -> Unit)? = null

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!isScanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        isScanning = false
        Log.d(TAG, "BLE scan stopped")
    }

    // ---- 내부: 권한가드 뒤에서만 호출 (Lint 억제) ----
    @SuppressLint("MissingPermission")
    private fun startScanInternal(filters: List<ScanFilter>, settings: ScanSettings) {
        adapter?.bluetoothLeScanner?.startScan(filters, settings, scanCb)
        isScanning = true
        Log.d(TAG, "BLE scan started")
        launch {
            delay(12_000)
            if (isScanning) {
                stopScan()
                onErrorCallback?.invoke(IllegalStateException("Scan timeout"))
            }
        }
    }

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            try {
                gatt = result.device.connectGatt(context, false, gattCb)
            } catch (se: SecurityException) {
                onErrorCallback?.invoke(se)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            onErrorCallback?.invoke(IllegalStateException("Scan failed: $errorCode"))
        }
    }

    // Descriptor 쓰기 체이닝
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private var enablingNotify = false

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: $status -> $newState")
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                onErrorCallback?.invoke(IllegalStateException("GATT status=$status"))
                disconnect(); return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) disconnect()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onErrorCallback?.invoke(IllegalStateException("Service discovery failed: $status")); return
            }
            val svc = g.getService(svcUuid) ?: run {
                onErrorCallback?.invoke(IllegalStateException("Service not found: $svcUuid")); return
            }
            if (customSVC == null) {
                nusRx = svc.getCharacteristic(NUS_RX)
                nusTx = svc.getCharacteristic(NUS_TX)
                queueEnableNotify(nusTx)
            } else {
                sensorChar = svc.getCharacteristic(sensorUuid!!)
                cmdChar    = svc.getCharacteristic(cmdUuid!!)
                ackChar    = svc.getCharacteristic(ackUuid!!)
                queueEnableNotify(sensorChar)
                queueEnableNotify(ackChar)
            }
            enableNextNotify(g)
        }

        private fun queueEnableNotify(c: BluetoothGattCharacteristic?) {
            if (c != null) notifyQueue.add(c)
        }

        @SuppressLint("MissingPermission")
        private fun enableNextNotify(g: BluetoothGatt) {
            if (enablingNotify) return
            val c = notifyQueue.removeFirstOrNull() ?: run {
                launch { _connectionState.emit(true) }
                onConnectedCallback?.invoke()
                return
            }
            enablingNotify = true
            try {
                g.setCharacteristicNotification(c, true)
                val cccd = c.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    enablingNotify = false
                    enableNextNotify(g)
                    return
                }
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { cccd.value = value; g.writeDescriptor(cccd) }
                }
                if (!ok) {
                    enablingNotify = false
                    enableNextNotify(g)
                }
            } catch (se: SecurityException) {
                enablingNotify = false
                onErrorCallback?.invoke(se)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            enablingNotify = false
            enableNextNotify(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            when (c.uuid) {
                sensorUuid -> {
                    val data = c.value ?: return
                    launch { _sensorFlow.emit(data) }
                    Log.d(TAG, "SENSOR notify: ${data.decodeToStringOrHex()}")
                }
                ackUuid -> {
                    val str = (c.value ?: byteArrayOf()).toString(Charsets.UTF_8)
                    launch { _ackFlow.emit(str) }
                    Log.d(TAG, "ACK: $str")
                }
                NUS_TX -> {
                    val str = (c.value ?: byteArrayOf()).toString(Charsets.UTF_8).trim()
                    if (str.startsWith("SENSOR:")) {
                        launch { _sensorFlow.emit(str.removePrefix("SENSOR:").toByteArray()) }
                    } else {
                        launch { _ackFlow.emit(str) }
                    }
                    Log.d(TAG, "NUS TX: $str")
                }
            }
        }
    }

    // ---- Permissions ----
    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scan && conn
        } else if (Build.VERSION.SDK_INT >= 23) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ---- Utils ----
    private fun ByteArray.decodeToStringOrHex(): String =
        try { toString(Charsets.UTF_8) } catch (_: Exception) { joinToString(" ") { "%02X".format(it) } }
}
