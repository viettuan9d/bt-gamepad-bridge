package com.controller.btbridge

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

class WifiUdpBridge(
    context: Context,
    private val port: Int = 9876,
    private val onServerConnected: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("bt_bridge_prefs", Context.MODE_PRIVATE)

    @Volatile
    var hostIp: String = prefs.getString("last_wifi_ip", "255.255.255.255") ?: "255.255.255.255"
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: DatagramSocket? = null

    fun start() {
        executor.execute {
            try {
                if (socket == null || socket!!.isClosed) {
                    socket = DatagramSocket()
                    socket?.broadcast = true
                    socket?.soTimeout = 1500
                }
                pingAndAutoDiscover()
            } catch (_: Exception) {
            }
        }
    }

    fun setTargetIp(newIp: String) {
        val cleanIp = newIp.trim()
        hostIp = cleanIp
        prefs.edit().putString("last_wifi_ip", cleanIp).apply()
        executor.execute {
            pingAndAutoDiscover()
        }
    }

    fun autoDiscover() {
        executor.execute {
            pingAndAutoDiscover()
        }
    }

    /**
     * Tự động phát gói tin Broadcast (255.255.255.255 và Subnet Broadcast của mọi mạng Wi-Fi/Hotspot)
     * để tự tìm bất kỳ máy tính Windows / Linux nào đang chạy Server trong cùng mạng LAN.
     */
    private fun pingAndAutoDiscover() {
        try {
            val s = socket ?: DatagramSocket().also {
                it.broadcast = true
                it.soTimeout = 1200
                socket = it
            }
            val pingData = byteArrayOf(0x00)

            // 1. Gửi tới IP đã lưu gần nhất (nếu có)
            if (hostIp != "255.255.255.255") {
                try {
                    val directAddr = InetAddress.getByName(hostIp)
                    s.send(DatagramPacket(pingData, pingData.size, directAddr, port))
                } catch (_: Exception) {}
            }

            // 2. Gửi tới địa chỉ Broadcast của tất cả card mạng Wi-Fi / Hotspot hiện tại
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val netIf = interfaces.nextElement()
                    if (!netIf.isUp || netIf.isLoopback) continue
                    for (ifAddr in netIf.interfaceAddresses) {
                        val bcast = ifAddr.broadcast ?: continue
                        try {
                            s.send(DatagramPacket(pingData, pingData.size, bcast, port))
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}

            // 3. Gửi tới Global Broadcast 255.255.255.255
            try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                s.send(DatagramPacket(pingData, pingData.size, broadcastAddr, port))
            } catch (_: Exception) {}

            val recvBuf = ByteArray(64)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            s.receive(recvPacket)
            val reply = String(recvPacket.data, 0, recvPacket.length, Charsets.US_ASCII)
            if (reply.startsWith("PONG_PC_BRIDGE")) {
                val foundIp = recvPacket.address.hostAddress ?: hostIp
                hostIp = foundIp
                prefs.edit().putString("last_wifi_ip", foundIp).apply()
                isConnected = true
                onServerConnected(foundIp)
            }
        } catch (_: Exception) {
        }
    }

    fun sendGamepad(buttons: Int, lx: Byte, ly: Byte, rx: Byte, ry: Byte) {
        executor.execute {
            try {
                val s = socket ?: return@execute
                val packet = byteArrayOf(
                    0x01,
                    (buttons and 0xFF).toByte(),
                    ((buttons shr 8) and 0xFF).toByte(),
                    lx,
                    ly,
                    rx,
                    ry
                )
                val addr = InetAddress.getByName(hostIp)
                s.send(DatagramPacket(packet, packet.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    fun sendMouse(buttons: Byte, dx: Byte, dy: Byte) {
        executor.execute {
            try {
                val s = socket ?: return@execute
                val packet = byteArrayOf(0x02, buttons, dx, dy)
                val addr = InetAddress.getByName(hostIp)
                s.send(DatagramPacket(packet, packet.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    fun sendScrollAndZoom(wheelY: Byte, wheelX: Byte, zoomSteps: Byte) {
        executor.execute {
            try {
                val s = socket ?: return@execute
                val packet = byteArrayOf(0x03, wheelY, wheelX, zoomSteps)
                val addr = InetAddress.getByName(hostIp)
                s.send(DatagramPacket(packet, packet.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    fun sendGesture(gestureCode: Byte) {
        executor.execute {
            try {
                val s = socket ?: return@execute
                val packet = byteArrayOf(0x04, gestureCode)
                val addr = InetAddress.getByName(hostIp)
                s.send(DatagramPacket(packet, packet.size, addr, port))
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
