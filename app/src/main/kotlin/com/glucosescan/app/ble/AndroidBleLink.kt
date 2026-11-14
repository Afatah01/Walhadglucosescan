package com.glucosescan.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import com.glucosescan.app.data.LiveSensorRecord
import java.util.UUID

/**
 * The phone's Bluetooth as a [BleLink]. Android calls back on its own threads. Everything is
 * passed on to [handler], so that the session only ever runs on one thread.
 *
 * The permissions are checked by [problem] before anything is used; a permission that is taken
 * away in between shows up as a SecurityException, which is caught and told as a failure.
 */
@SuppressLint("MissingPermission")
class AndroidBleLink(
    context: Context,
    private val handler: Handler,
    private val events: () -> BleEvents?
) : BleLink {
    private val context = context.applicationContext

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun problem(): LinkProblem? {
        if (!LivePermissions.hasBluetooth(context)) {
            return LinkProblem.NO_PERMISSION
        }
        val bluetooth = adapter
        return try {
            if (bluetooth == null || !bluetooth.isEnabled) LinkProblem.BLUETOOTH_OFF else null
        } catch (e: SecurityException) {
            LinkProblem.NO_PERMISSION
        }
    }

    // ------------------------------------------------------------------ searching

    override fun startScan(sensor: LiveSensorRecord) {
        stopScan()
        if (!LivePermissions.canSearch(context)) {
            handler.post { events()?.onScanFailed(SCAN_NOT_ALLOWED) }
            return
        }
        val scanner = try {
            adapter?.bluetoothLeScanner
        } catch (e: SecurityException) {
            null
        }
        if (scanner == null) {
            handler.post { events()?.onScanFailed(SCAN_NOT_POSSIBLE) }
            return
        }

        // Android only searches with the screen off if the search names what it looks for.
        // The sensor shows the service FDE3 (the original app searches for it), its name and
        // its address. Any one of the three is enough.
        val filters = ArrayList<ScanFilter>()
        filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        sensor.bluetoothName?.let { filters.add(ScanFilter.Builder().setDeviceName(it).build()) }
        sensor.addressFromAnswer?.let {
            if (BluetoothAdapter.checkBluetoothAddress(it)) {
                filters.add(ScanFilter.Builder().setDeviceAddress(it).build())
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val address = result.device?.address ?: return
                val name = record?.deviceName
                val all = record?.manufacturerSpecificData
                val data = if (all != null && all.size() > 0) all.valueAt(0)?.copyOf() else null
                handler.post {
                    if (scanCallback === this) {
                        events()?.onFound(address, name, data)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (scanCallback === this) {
                        scanCallback = null
                        events()?.onScanFailed(errorCode)
                    }
                }
            }
        }
        scanCallback = callback
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: RuntimeException) { // no permission any more, or Bluetooth was just switched off
            scanCallback = null
            handler.post { events()?.onScanFailed(SCAN_NOT_POSSIBLE) }
        }
    }

    override fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: RuntimeException) {
            // Bluetooth is off: the search has ended anyway
        }
    }

    // ------------------------------------------------------------------ connecting

    override fun connect(address: String, wait: Boolean) {
        close()
        val bluetooth = adapter
        if (bluetooth == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            handler.post { events()?.onDisconnected(CONNECT_NOT_POSSIBLE) }
            return
        }
        try {
            val device = bluetooth.getRemoteDevice(address)
            val callback = Callback()
            val newGatt = device.connectGatt(context, wait, callback, BluetoothDevice.TRANSPORT_LE)
            if (newGatt == null) {
                handler.post { events()?.onDisconnected(CONNECT_NOT_POSSIBLE) }
            } else {
                gatt = newGatt
            }
        } catch (e: RuntimeException) {
            handler.post { events()?.onDisconnected(CONNECT_NOT_POSSIBLE) }
        }
    }

    override fun discoverServices(): Boolean {
        val current = gatt ?: return false
        return try {
            current.discoverServices()
        } catch (e: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    override fun writeUnlock(payload: ByteArray): Boolean {
        val current = gatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // with an answer, as in the original
                characteristic.value = payload
                current.writeCharacteristic(characteristic)
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    override fun enableNotifications(): Boolean {
        val current = gatt ?: return false
        val characteristic = notifyCharacteristic ?: return false
        return try {
            if (!current.setCharacteristicNotification(characteristic, true)) {
                return false
            }
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIGURATION) ?: return false
            val notifies = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            val value = if (notifies) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                current.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                current.writeDescriptor(descriptor)
            }
        } catch (e: RuntimeException) {
            false
        }
    }

    override fun close() {
        val current = gatt ?: return
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        try {
            current.disconnect()
        } catch (e: RuntimeException) {
            // nothing to do
        }
        try {
            current.close()
        } catch (e: RuntimeException) {
            // nothing to do
        }
    }

    /** What Android tells about one connection. Calls for a connection that was closed are dropped. */
    private inner class Callback : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    events()?.onConnected()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    events()?.onDisconnected(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) {
                    return@post
                }
                val service = if (status == BluetoothGatt.GATT_SUCCESS) g.getService(SERVICE) else null
                writeCharacteristic = service?.getCharacteristic(WRITE)
                notifyCharacteristic = service?.getCharacteristic(NOTIFY)
                events()?.onServicesDiscovered(writeCharacteristic != null && notifyCharacteristic != null)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val uuid = characteristic.uuid
            handler.post {
                if (g === gatt && uuid == WRITE) {
                    events()?.onUnlockWritten(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.uuid
            val owner = descriptor.characteristic?.uuid
            handler.post {
                if (g === gatt && uuid == CLIENT_CONFIGURATION && owner == NOTIFY) {
                    events()?.onNotificationsEnabled(status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        // Android 12 and older. The bytes are copied right here: the three parts of a message
        // come so fast that the next part would overwrite them before the handler runs.
        @Deprecated("Android 13 and newer call the function with the value")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value?.copyOf() ?: return
            packet(g, characteristic.uuid, value)
        }

        // Android 13 and newer
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            packet(g, characteristic.uuid, value.copyOf())
        }

        private fun packet(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
            handler.post {
                if (g === gatt && uuid == NOTIFY) {
                    events()?.onPacket(value)
                }
            }
        }
    }

    companion object {
        // As in the original app (Libre2Connection.swift): service FDE3, write F001, notify F002.
        val SERVICE: UUID = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        val WRITE: UUID = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        val NOTIFY: UUID = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")

        /** The standard descriptor that switches notifications on. */
        val CLIENT_CONFIGURATION: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val SCAN_NOT_ALLOWED = -2
        const val SCAN_NOT_POSSIBLE = -3
        const val CONNECT_NOT_POSSIBLE = -4
    }
}
