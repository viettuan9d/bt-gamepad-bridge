package com.controller.btbridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class BluetoothHidManager(
    private val context: Context,
    private val onConnectionChanged: (Boolean, String?) -> Unit
) {
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var targetDevice: BluetoothDevice? = null
    private var isStarted = false
    private var isAppRegistered = false

    private val gamepadBuffer = ByteArray(6)
    private val mouseBuffer = ByteArray(4)

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerSdpProfile()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isAppRegistered = false
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        @SuppressLint("MissingPermission")
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            isAppRegistered = registered
            if (registered) {
                val connected = hidDevice?.connectedDevices?.firstOrNull() ?: pluggedDevice
                if (connected != null) {
                    targetDevice = connected
                    onConnectionChanged(true, connected.name ?: connected.address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    targetDevice = device
                    onConnectionChanged(true, device?.name ?: device?.address ?: "Unknown")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (targetDevice?.address == device?.address) {
                        targetDevice = null
                        onConnectionChanged(false, null)
                    }
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            super.onSetReport(device, type, id, data)
            triggerVibration(40)
        }
    }

    fun start() {
        if (isStarted && hidDevice != null) {
            if (!isAppRegistered) {
                registerSdpProfile()
            }
            return
        }
        isStarted = true
        bluetoothAdapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun registerSdpProfile() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "GameController Bridge",
            "Hardware Controller Bridge",
            "Android",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidProfile.COMPOSITE_DESCRIPTOR
        )
        hidDevice?.registerApp(sdp, null, null, { it.run() }, hidCallback)
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(device: BluetoothDevice) {
        targetDevice = device
        hidDevice?.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun sendGamepad(buttons: Int, lx: Byte, ly: Byte, rx: Byte, ry: Byte): Boolean {
        val hid = hidDevice ?: return false
        val device = targetDevice ?: hid.connectedDevices.firstOrNull()?.also {
            targetDevice = it
            onConnectionChanged(true, it.name ?: it.address)
        } ?: return false

        gamepadBuffer[0] = (buttons and 0xFF).toByte()
        gamepadBuffer[1] = ((buttons shr 8) and 0xFF).toByte()
        gamepadBuffer[2] = lx
        gamepadBuffer[3] = ly
        gamepadBuffer[4] = rx
        gamepadBuffer[5] = ry
        return hid.sendReport(device, 1, gamepadBuffer)
    }

    @SuppressLint("MissingPermission")
    fun sendMouse(buttons: Byte, dx: Byte, dy: Byte, wheel: Byte = 0): Boolean {
        val hid = hidDevice ?: return false
        val device = targetDevice ?: hid.connectedDevices.firstOrNull()?.also {
            targetDevice = it
            onConnectionChanged(true, it.name ?: it.address)
        } ?: return false

        mouseBuffer[0] = buttons
        mouseBuffer[1] = dx
        mouseBuffer[2] = dy
        mouseBuffer[3] = wheel
        return hid.sendReport(device, 2, mouseBuffer)
    }

    fun triggerVibration(durationMs: Long = 20) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            hidDevice?.unregisterApp()
        } catch (_: Exception) {}
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        isStarted = false
        isAppRegistered = false
    }
}
