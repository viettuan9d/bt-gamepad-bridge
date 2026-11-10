package com.controller.btbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothRfcommBridge(
    private val context: Context,
    private val onStatusChanged: (Boolean, String) -> Unit
) {
    private val prefs = context.getSharedPreferences("bt_bridge_prefs", Context.MODE_PRIVATE)
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val bridgeUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var btSocket: BluetoothSocket? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    @Volatile
    private var outStream: OutputStream? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var isServerRunning: Boolean = false

    fun startAll() {
        startBluetoothServer()
        connectAutoOrDevice(null)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothServer() {
        if (isServerRunning) return
        isServerRunning = true
        Thread {
            while (isServerRunning) {
                try {
                    val adapter = bluetoothAdapter
                    if (adapter == null) {
                        Thread.sleep(2000)
                        continue
                    }
                    val srv = adapter.listenUsingInsecureRfcommWithServiceRecord("BTGamepadBridge", bridgeUuid)
                    serverSocket = srv
                    val accepted = srv.accept()
                    if (accepted != null) {
                        val remoteDev = accepted.remoteDevice
                        if (remoteDev != null) {
                            prefs.edit().putString("last_bt_mac", remoteDev.address).apply()
                        }
                        setActiveSocket(accepted, remoteDev?.name ?: remoteDev?.address ?: "Thiết bị Bluetooth")
                    }
                    try {
                        srv.close()
                    } catch (_: Exception) {}
                } catch (_: Exception) {
                    try {
                        Thread.sleep(2000)
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    /**
     * Tự động dò tìm và kết nối tới bất kỳ Máy tính (PC/Laptop) hoặc Điện thoại nào đã ghép đôi Bluetooth,
     * không phụ thuộc vào địa chỉ MAC cứng của một máy cố định.
     */
    @SuppressLint("MissingPermission")
    fun connectAutoOrDevice(customDevice: BluetoothDevice? = null) {
        if (isConnecting && customDevice == null) return
        isConnecting = true

        Thread {
            try {
                val adapter = bluetoothAdapter
                if (adapter == null) {
                    onStatusChanged(false, "Không tìm thấy chip Bluetooth!")
                    isConnecting = false
                    return@Thread
                }

                try {
                    adapter.cancelDiscovery()
                } catch (_: Exception) {}

                val candidates = mutableListOf<BluetoothDevice>()
                if (customDevice != null) {
                    candidates.add(customDevice)
                } else {
                    val bonded = try {
                        adapter.bondedDevices?.toList() ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val lastMac = prefs.getString("last_bt_mac", null)

                    // 1. Ưu tiên thiết bị kết nối thành công gần nhất
                    if (lastMac != null) {
                        bonded.firstOrNull { it.address.equals(lastMac, ignoreCase = true) }?.let {
                            candidates.add(it)
                        }
                    }

                    // 2. Ưu tiên các thiết bị có phân lớp (Class of Device) là Máy tính (COMPUTER) hoặc Điện thoại (PHONE)
                    val sortedBonded = bonded.sortedByDescending { dev ->
                        val majorClass = try { dev.bluetoothClass?.majorDeviceClass } catch (_: Exception) { null }
                        when (majorClass) {
                            BluetoothClass.Device.Major.COMPUTER -> 3
                            BluetoothClass.Device.Major.PHONE -> 2
                            else -> 1
                        }
                    }
                    for (d in sortedBonded) {
                        if (candidates.none { it.address.equals(d.address, ignoreCase = true) }) {
                            candidates.add(d)
                        }
                    }
                }

                if (candidates.isEmpty()) {
                    onStatusChanged(false, "Hãy ghép đôi Bluetooth với PC/ĐT rồi bấm '🔵 BT'")
                    isConnecting = false
                    return@Thread
                }

                var connectedSocket: BluetoothSocket? = null
                var connectedDevice: BluetoothDevice? = null

                for (target in candidates) {
                    if (isConnected && customDevice == null) break
                    val targetLabel = try { target.name } catch (_: Exception) { null } ?: target.address
                    onStatusChanged(false, "Đang kết nối Bluetooth tới $targetLabel...")

                    // Thử 1: Chuẩn SPP UUID (00001101-...)
                    try {
                        val s = target.createInsecureRfcommSocketToServiceRecord(bridgeUuid)
                        s.connect()
                        connectedSocket = s
                        connectedDevice = target
                        break
                    } catch (_: Exception) {
                    }

                    // Thử 2: Quét nhanh các cổng RFCOMM phổ biến (4, 1, 2, 3, 5)
                    val channels = listOf(4, 1, 2, 3, 5)
                    for (ch in channels) {
                        if (isConnected && customDevice == null) break
                        try {
                            val m = target.javaClass.getMethod(
                                "createInsecureRfcommSocket",
                                Int::class.javaPrimitiveType
                            )
                            val s = m.invoke(target, ch) as BluetoothSocket
                            s.connect()
                            connectedSocket = s
                            connectedDevice = target
                            break
                        } catch (_: Exception) {
                        }
                    }
                    if (connectedSocket != null) break
                }

                if (connectedSocket != null && connectedSocket.isConnected && connectedDevice != null) {
                    prefs.edit().putString("last_bt_mac", connectedDevice.address).apply()
                    val devName = try { connectedDevice.name } catch (_: Exception) { null } ?: connectedDevice.address
                    setActiveSocket(connectedSocket, devName)
                } else if (!isConnected) {
                    onStatusChanged(false, "Đang chờ kết nối Bluetooth (Bấm '🔵 BT' để chọn thiết bị)")
                }
            } catch (_: SecurityException) {
                onStatusChanged(false, "Thiếu quyền 'Thiết bị ở gần'! Bấm '🔵 BT' để cấp quyền.")
            } catch (_: Exception) {
                if (!isConnected) {
                    onStatusChanged(false, "Đang chờ kết nối Bluetooth...")
                }
            } finally {
                isConnecting = false
            }
        }.start()
    }

    @Synchronized
    private fun setActiveSocket(socket: BluetoothSocket, deviceName: String) {
        closeCurrentSocket()
        btSocket = socket
        outStream = socket.outputStream
        isConnected = true
        onStatusChanged(true, "Đã kết nối Bluetooth: $deviceName")

        val inputStream = socket.inputStream
        Thread {
            readIncomingStreamLoop(inputStream)
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun readIncomingStreamLoop(inp: InputStream) {
        val buf = ByteArray(256)
        val streamBuf = ByteArray(512)
        var streamLen = 0

        try {
            while (isConnected) {
                val r = inp.read(buf)
                if (r <= 0) break

                if (streamLen + r > streamBuf.size) {
                    streamLen = 0
                }
                System.arraycopy(buf, 0, streamBuf, streamLen, r)
                streamLen += r

                var idx = 0
                while (idx < streamLen) {
                    val pktType = streamBuf[idx].toInt() and 0xFF
                    when (pktType) {
                        0x01 -> {
                            if (idx + 7 <= streamLen) {
                                val mask = (streamBuf[idx + 1].toInt() and 0xFF) or ((streamBuf[idx + 2].toInt() and 0xFF) shl 8)
                                val lx = streamBuf[idx + 3]
                                val ly = streamBuf[idx + 4]
                                val rx = streamBuf[idx + 5]
                                val ry = streamBuf[idx + 6]
                                AndroidReceiverAccessibilityService.instance?.onRemoteGamepadPacket(mask, lx, ly, rx, ry)
                                idx += 7
                            } else break
                        }
                        0x02 -> {
                            if (idx + 4 <= streamLen) {
                                val btns = streamBuf[idx + 1]
                                val dx = streamBuf[idx + 2]
                                val dy = streamBuf[idx + 3]
                                AndroidReceiverAccessibilityService.instance?.onRemoteMousePacket(btns, dx, dy)
                                idx += 4
                            } else break
                        }
                        0x03 -> {
                            if (idx + 4 <= streamLen) {
                                val wy = streamBuf[idx + 1]
                                val wx = streamBuf[idx + 2]
                                val zoom = streamBuf[idx + 3]
                                AndroidReceiverAccessibilityService.instance?.onRemoteScrollZoomPacket(wy, wx, zoom)
                                idx += 4
                            } else break
                        }
                        0x04 -> {
                            if (idx + 2 <= streamLen) {
                                val code = streamBuf[idx + 1]
                                AndroidReceiverAccessibilityService.instance?.onRemoteGesturePacket(code)
                                idx += 2
                            } else break
                        }
                        else -> idx++
                    }
                }

                if (idx > 0) {
                    val remaining = streamLen - idx
                    if (remaining > 0) {
                        System.arraycopy(streamBuf, idx, streamBuf, 0, remaining)
                    }
                    streamLen = remaining
                }
            }
        } catch (_: Exception) {
        } finally {
            handleDisconnect()
        }
    }

    fun sendGamepad(buttons: Int, lx: Byte, ly: Byte, rx: Byte, ry: Byte): Boolean {
        if (!isConnected) return false
        ioExecutor.execute {
            try {
                val out = outStream ?: return@execute
                val packet = byteArrayOf(
                    0x01,
                    (buttons and 0xFF).toByte(),
                    ((buttons shr 8) and 0xFF).toByte(),
                    lx,
                    ly,
                    rx,
                    ry
                )
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
                handleDisconnect()
            }
        }
        return true
    }

    fun sendMouse(buttons: Byte, dx: Byte, dy: Byte): Boolean {
        if (!isConnected) return false
        ioExecutor.execute {
            try {
                val out = outStream ?: return@execute
                val packet = byteArrayOf(0x02, buttons, dx, dy)
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
                handleDisconnect()
            }
        }
        return true
    }

    fun sendScrollAndZoom(wheelY: Byte, wheelX: Byte, zoomSteps: Byte): Boolean {
        if (!isConnected) return false
        ioExecutor.execute {
            try {
                val out = outStream ?: return@execute
                val packet = byteArrayOf(0x03, wheelY, wheelX, zoomSteps)
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
                handleDisconnect()
            }
        }
        return true
    }

    fun sendGesture(gestureCode: Byte): Boolean {
        if (!isConnected) return false
        ioExecutor.execute {
            try {
                val out = outStream ?: return@execute
                val packet = byteArrayOf(0x04, gestureCode)
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
                handleDisconnect()
            }
        }
        return true
    }

    private fun handleDisconnect() {
        if (isConnected) {
            isConnected = false
            closeCurrentSocket()
            onStatusChanged(false, "Mất kết nối BT, đang tự kết nối lại...")
            connectAutoOrDevice(null)
        }
    }

    private fun closeCurrentSocket() {
        try {
            outStream?.close()
        } catch (_: Exception) {}
        try {
            btSocket?.close()
        } catch (_: Exception) {}
        outStream = null
        btSocket = null
    }

    fun stop() {
        isServerRunning = false
        isConnected = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        closeCurrentSocket()
    }
}
