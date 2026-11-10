using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

class PcBridgeServer : ApplicationContext {
    // ================= VIGEM VIRTUAL XBOX 360 CONTROLLER APIS =================
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct XUSB_REPORT {
        public ushort wButtons;
        public byte bLeftTrigger;
        public byte bRightTrigger;
        public short sThumbLX;
        public short sThumbLY;
        public short sThumbRX;
        public short sThumbRY;
    }

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr vigem_alloc();

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern uint vigem_connect(IntPtr vigem);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr vigem_target_x360_alloc();

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern uint vigem_target_add(IntPtr vigem, IntPtr target);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern uint vigem_target_x360_update(IntPtr vigem, IntPtr target, XUSB_REPORT report);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern uint vigem_target_remove(IntPtr vigem, IntPtr target);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void vigem_target_free(IntPtr target);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void vigem_disconnect(IntPtr vigem);

    [DllImport("ViGEmClient.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void vigem_free(IntPtr vigem);

    const ushort XUSB_GAMEPAD_DPAD_UP        = 0x0001;
    const ushort XUSB_GAMEPAD_DPAD_DOWN      = 0x0002;
    const ushort XUSB_GAMEPAD_DPAD_LEFT      = 0x0004;
    const ushort XUSB_GAMEPAD_DPAD_RIGHT     = 0x0008;
    const ushort XUSB_GAMEPAD_START          = 0x0010;
    const ushort XUSB_GAMEPAD_BACK           = 0x0020;
    const ushort XUSB_GAMEPAD_LEFT_THUMB     = 0x0040;
    const ushort XUSB_GAMEPAD_RIGHT_THUMB    = 0x0080;
    const ushort XUSB_GAMEPAD_LEFT_SHOULDER  = 0x0100;
    const ushort XUSB_GAMEPAD_RIGHT_SHOULDER = 0x0200;
    const ushort XUSB_GAMEPAD_GUIDE          = 0x0400;
    const ushort XUSB_GAMEPAD_A              = 0x1000;
    const ushort XUSB_GAMEPAD_B              = 0x2000;
    const ushort XUSB_GAMEPAD_X              = 0x4000;
    const ushort XUSB_GAMEPAD_Y              = 0x8000;

    static IntPtr vigemClient = IntPtr.Zero;
    static IntPtr xbox360Target = IntPtr.Zero;
    static volatile bool xbox360Ready = false;
    static volatile bool isRunning = true;
    static volatile bool hasActiveBtClient = false;
    static IntPtr currentBtClientSock = IntPtr.Zero;
    static IntPtr currentBtListenSock = IntPtr.Zero;
    static UdpClient currentUdpClient = null;

    static NotifyIcon trayIcon;
    static ToolStripMenuItem statusItem;
    static ToolStripMenuItem xboxToggleItem;

    static void InitVirtualXbox360Controller() {
        try {
            if (xbox360Ready) return;
            vigemClient = vigem_alloc();
            uint conn = vigem_connect(vigemClient);
            if (conn == 0x20000000) {
                xbox360Target = vigem_target_x360_alloc();
                uint add = vigem_target_add(vigemClient, xbox360Target);
                if (add == 0x20000000) {
                    xbox360Ready = true;
                    XUSB_REPORT initRep = new XUSB_REPORT();
                    vigem_target_x360_update(vigemClient, xbox360Target, initRep);
                }
            }
        } catch {
        }
    }

    static void CleanupVirtualXbox360Controller() {
        try {
            if (xbox360Ready) {
                xbox360Ready = false;
                if (xbox360Target != IntPtr.Zero && vigemClient != IntPtr.Zero) {
                    vigem_target_remove(vigemClient, xbox360Target);
                    vigem_target_free(xbox360Target);
                    xbox360Target = IntPtr.Zero;
                }
                if (vigemClient != IntPtr.Zero) {
                    vigem_disconnect(vigemClient);
                    vigem_free(vigemClient);
                    vigemClient = IntPtr.Zero;
                }
            }
        } catch {
        }
    }

    static short ScaleAxisToShort(sbyte val, bool invert) {
        int v = (int)val;
        if (invert) v = -v;
        if (Math.Abs(v) <= 2) return 0;
        int scaled = v * 258;
        if (scaled > 32767) scaled = 32767;
        if (scaled < -32768) scaled = -32768;
        return (short)scaled;
    }

    static void UpdateXbox360Controller(int mask, sbyte lx, sbyte ly, sbyte rx, sbyte ry) {
        if (!xbox360Ready) return;

        ushort xBtns = 0;
        if ((mask & (1 << 0)) != 0)  xBtns |= XUSB_GAMEPAD_A;
        if ((mask & (1 << 1)) != 0)  xBtns |= XUSB_GAMEPAD_B;
        if ((mask & (1 << 2)) != 0)  xBtns |= XUSB_GAMEPAD_X;
        if ((mask & (1 << 3)) != 0)  xBtns |= XUSB_GAMEPAD_Y;
        if ((mask & (1 << 4)) != 0)  xBtns |= XUSB_GAMEPAD_LEFT_SHOULDER;
        if ((mask & (1 << 5)) != 0)  xBtns |= XUSB_GAMEPAD_RIGHT_SHOULDER;
        if ((mask & (1 << 6)) != 0)  xBtns |= XUSB_GAMEPAD_BACK;
        if ((mask & (1 << 7)) != 0)  xBtns |= XUSB_GAMEPAD_START;
        if ((mask & (1 << 8)) != 0)  xBtns |= XUSB_GAMEPAD_DPAD_UP;
        if ((mask & (1 << 9)) != 0)  xBtns |= XUSB_GAMEPAD_DPAD_DOWN;
        if ((mask & (1 << 10)) != 0) xBtns |= XUSB_GAMEPAD_DPAD_LEFT;
        if ((mask & (1 << 11)) != 0) xBtns |= XUSB_GAMEPAD_DPAD_RIGHT;
        if ((mask & (1 << 12)) != 0) xBtns |= XUSB_GAMEPAD_LEFT_THUMB;
        if ((mask & (1 << 13)) != 0) xBtns |= XUSB_GAMEPAD_RIGHT_THUMB;

        byte lt = ((mask & (1 << 14)) != 0) ? (byte)255 : (byte)0;
        byte rt = ((mask & (1 << 15)) != 0) ? (byte)255 : (byte)0;

        XUSB_REPORT rep = new XUSB_REPORT();
        rep.wButtons = xBtns;
        rep.bLeftTrigger = lt;
        rep.bRightTrigger = rt;
        rep.sThumbLX = ScaleAxisToShort(lx, false);
        rep.sThumbLY = ScaleAxisToShort(ly, true);
        rep.sThumbRX = ScaleAxisToShort(rx, false);
        rep.sThumbRY = ScaleAxisToShort(ry, true);

        vigem_target_x360_update(vigemClient, xbox360Target, rep);
    }

    // ================= WIN32 DESKTOP & INPUT APIS =================
    [DllImport("user32.dll", SetLastError = true)]
    static extern IntPtr OpenInputDesktop(uint dwFlags, bool fInherit, uint dwDesiredAccess);

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool SetThreadDesktop(IntPtr hDesktop);

    [DllImport("user32.dll", SetLastError = true)]
    static extern void mouse_event(uint dwFlags, int dx, int dy, uint dwData, UIntPtr dwExtraInfo);

    [DllImport("user32.dll", SetLastError = true)]
    static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    [DllImport("user32.dll")]
    static extern uint MapVirtualKey(uint uCode, uint uMapType);

    const uint MOUSEEVENTF_MOVE = 0x0001;
    const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    const uint MOUSEEVENTF_LEFTUP = 0x0004;
    const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    const uint MOUSEEVENTF_WHEEL = 0x0800;
    const uint MOUSEEVENTF_HWHEEL = 0x01000;
    const uint KEYEVENTF_KEYUP = 0x0002;

    // ================= BLUETOOTH RFCOMM WINSOCK =================
    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct SOCKADDR_BTH {
        public ushort addressFamily;
        public ulong btAddr;
        public Guid serviceClassId;
        public uint port;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    public struct WSAQUERYSET {
        public uint dwSize;
        public string lpszServiceInstanceName;
        public IntPtr lpServiceClassId;
        public IntPtr lpVersion;
        public string lpszComment;
        public uint dwNameSpace;
        public IntPtr lpNSProviderId;
        public string lpszContext;
        public uint dwNumberOfProtocols;
        public IntPtr lpafpProtocols;
        public string lpszQueryString;
        public uint dwNumberOfCsAddrs;
        public IntPtr lpcsaBuffer;
        public uint dwOutputFlags;
        public IntPtr lpBlob;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct SOCKET_ADDRESS {
        public IntPtr lpSockaddr;
        public int iSockaddrLength;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct CSADDR_INFO {
        public SOCKET_ADDRESS LocalAddr;
        public SOCKET_ADDRESS RemoteAddr;
        public int iSocketType;
        public int iProtocol;
    }

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern IntPtr socket(int af, int type, int protocol);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int bind(IntPtr s, ref SOCKADDR_BTH addr, int namelen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int getsockname(IntPtr s, ref SOCKADDR_BTH addr, ref int namelen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int listen(IntPtr s, int backlog);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern IntPtr accept(IntPtr s, ref SOCKADDR_BTH addr, ref int addrlen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int connect(IntPtr s, ref SOCKADDR_BTH addr, int namelen);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int recv(IntPtr s, byte[] buf, int len, int flags);

    [DllImport("ws2_32.dll", SetLastError = true)]
    public static extern int closesocket(IntPtr s);

    [DllImport("ws2_32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    public static extern int WSASetService(ref WSAQUERYSET lpqsRegInfo, int essoperation, int dwControlFlags);

    // ================= INPUT DISPATCH QUEUE =================
    struct InputCommand {
        public byte cmdType;
        public int mask;
        public sbyte lx, ly, rx, ry;
        public byte mouseBtns;
        public sbyte dx, dy;
        public sbyte wheelY, wheelX, zoomSteps;
        public byte gestureCode;
    }

    static readonly BlockingCollection<InputCommand> inputQueue = new BlockingCollection<InputCommand>(1024);

    static void UpdateTrayStatus(string text) {
        try {
            if (trayIcon != null) {
                string full = "BT Gamepad & Mouse Bridge\n" + text;
                trayIcon.Text = full.Substring(0, Math.Min(63, full.Length));
            }
            if (statusItem != null) {
                statusItem.Text = text;
            }
        } catch {
        }
    }

    /**
     * Tự động đọc danh sách tất cả các thiết bị Bluetooth đã ghép đôi trên bất kỳ máy tính Windows nào
     * thông qua khóa hệ thống HKLM\SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Devices
     */
    static List<ulong> GetAllPairedBluetoothDevicesOnWindows() {
        List<ulong> result = new List<ulong>();
        try {
            using (RegistryKey key = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Services\BTHPORT\Parameters\Devices")) {
                if (key != null) {
                    foreach (string subName in key.GetSubKeyNames()) {
                        ulong mac;
                        if (ulong.TryParse(subName, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out mac) && mac != 0) {
                            result.Add(mac);
                        }
                    }
                }
            }
        } catch {
        }
        return result;
    }

    static void RunInteractiveDesktopInputThread() {
        IntPtr desk = OpenInputDesktop(0, false, 0x01FF);
        if (desk != IntPtr.Zero) {
            SetThreadDesktop(desk);
        }

        byte prevMouseBtns = 0;

        Action<byte, bool> sendKey = (vk, down) => {
            byte scan = (byte)MapVirtualKey(vk, 0);
            keybd_event(vk, scan, down ? 0u : KEYEVENTF_KEYUP, UIntPtr.Zero);
        };

        Action<int, int, byte> sendMouse = (dx, dy, btns) => {
            uint flags = 0;
            if (dx != 0 || dy != 0) flags |= MOUSEEVENTF_MOVE;

            bool leftNow = (btns & 0x01) != 0;
            bool leftPrev = (prevMouseBtns & 0x01) != 0;
            if (leftNow && !leftPrev) flags |= MOUSEEVENTF_LEFTDOWN;
            if (!leftNow && leftPrev) flags |= MOUSEEVENTF_LEFTUP;

            bool rightNow = (btns & 0x02) != 0;
            bool rightPrev = (prevMouseBtns & 0x02) != 0;
            if (rightNow && !rightPrev) flags |= MOUSEEVENTF_RIGHTDOWN;
            if (!rightNow && rightPrev) flags |= MOUSEEVENTF_RIGHTUP;

            bool midNow = (btns & 0x04) != 0;
            bool midPrev = (prevMouseBtns & 0x04) != 0;
            if (midNow && !midPrev) flags |= MOUSEEVENTF_MIDDLEDOWN;
            if (!midNow && midPrev) flags |= MOUSEEVENTF_MIDDLEUP;

            prevMouseBtns = btns;

            if (flags != 0) {
                mouse_event(flags, dx, dy, 0, UIntPtr.Zero);
            }
        };

        foreach (var cmd in inputQueue.GetConsumingEnumerable()) {
            if (!isRunning) break;
            try {
                if (cmd.cmdType == 1) {
                    UpdateXbox360Controller(cmd.mask, cmd.lx, cmd.ly, cmd.rx, cmd.ry);
                } else {
                    IntPtr curDesk = OpenInputDesktop(0, false, 0x01FF);
                    if (curDesk != IntPtr.Zero) {
                        SetThreadDesktop(curDesk);
                    }

                    if (cmd.cmdType == 2) {
                        sendMouse(cmd.dx, cmd.dy, cmd.mouseBtns);
                    } else if (cmd.cmdType == 3) {
                        if (cmd.wheelY != 0) {
                            uint wheelData = unchecked((uint)(cmd.wheelY * 45));
                            mouse_event(MOUSEEVENTF_WHEEL, 0, 0, wheelData, UIntPtr.Zero);
                        }
                        if (cmd.wheelX != 0) {
                            uint hWheelData = unchecked((uint)(-cmd.wheelX * 45));
                            mouse_event(MOUSEEVENTF_HWHEEL, 0, 0, hWheelData, UIntPtr.Zero);
                        }
                        if (cmd.zoomSteps != 0) {
                            sendKey(0x11, true);
                            uint zoomWheel = unchecked((uint)(cmd.zoomSteps * 120));
                            mouse_event(MOUSEEVENTF_WHEEL, 0, 0, zoomWheel, UIntPtr.Zero);
                            sendKey(0x11, false);
                        }
                    } else if (cmd.cmdType == 4) {
                        if (cmd.gestureCode == 1) {
                            sendKey(0x5B, true); sendKey(0x09, true); sendKey(0x09, false); sendKey(0x5B, false);
                        } else if (cmd.gestureCode == 2) {
                            sendKey(0x5B, true); sendKey(0x44, true); sendKey(0x44, false); sendKey(0x5B, false);
                        } else if (cmd.gestureCode == 3) {
                            sendKey(0x12, true); sendKey(0x10, true); sendKey(0x09, true); sendKey(0x09, false); sendKey(0x10, false); sendKey(0x12, false);
                        } else if (cmd.gestureCode == 4) {
                            sendKey(0x12, true); sendKey(0x09, true); sendKey(0x09, false); sendKey(0x12, false);
                        }
                    }
                }
            } catch {
            }
        }
    }

    static void EnqueueGamepadPacket(byte[] data, int offset) {
        InputCommand cmd = new InputCommand();
        cmd.cmdType = 1;
        cmd.mask = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        cmd.lx = (sbyte)data[offset + 2];
        cmd.ly = (sbyte)data[offset + 3];
        cmd.rx = (sbyte)data[offset + 4];
        cmd.ry = (sbyte)data[offset + 5];
        inputQueue.TryAdd(cmd);
    }

    static void EnqueueMousePacket(byte[] data, int offset) {
        InputCommand cmd = new InputCommand();
        cmd.cmdType = 2;
        cmd.mouseBtns = data[offset];
        cmd.dx = (sbyte)data[offset + 1];
        cmd.dy = (sbyte)data[offset + 2];
        inputQueue.TryAdd(cmd);
    }

    static void EnqueueScrollZoomPacket(byte[] data, int offset) {
        InputCommand cmd = new InputCommand();
        cmd.cmdType = 3;
        cmd.wheelY = (sbyte)data[offset];
        cmd.wheelX = (sbyte)data[offset + 1];
        cmd.zoomSteps = (sbyte)data[offset + 2];
        inputQueue.TryAdd(cmd);
    }

    static void EnqueueGesturePacket(byte[] data, int offset) {
        InputCommand cmd = new InputCommand();
        cmd.cmdType = 4;
        cmd.gestureCode = data[offset];
        inputQueue.TryAdd(cmd);
    }

    // ================= 1. BLUETOOTH SERVER THREAD =================
    static void RunBluetoothRfcommServer() {
        try {
            Socket dummy = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);

            IntPtr listenSock = socket(32, 1, 3);
            currentBtListenSock = listenSock;
            SOCKADDR_BTH addr = new SOCKADDR_BTH();
            addr.addressFamily = 32;
            addr.btAddr = 0;
            addr.port = 0xFFFFFFFF;
            int size = Marshal.SizeOf(addr);

            if (bind(listenSock, ref addr, size) != 0) {
                return;
            }
            getsockname(listenSock, ref addr, ref size);
            listen(listenSock, 4);

            Guid sppGuid = new Guid("00001101-0000-1000-8000-00805F9B34FB");
            IntPtr guidPtr = Marshal.AllocHGlobal(Marshal.SizeOf(sppGuid));
            Marshal.StructureToPtr(sppGuid, guidPtr, false);

            IntPtr addrPtr = Marshal.AllocHGlobal(size);
            Marshal.StructureToPtr(addr, addrPtr, false);

            CSADDR_INFO csAddr = new CSADDR_INFO();
            csAddr.LocalAddr.lpSockaddr = addrPtr;
            csAddr.LocalAddr.iSockaddrLength = size;
            csAddr.iSocketType = 1;
            csAddr.iProtocol = 3;
            IntPtr csAddrPtr = Marshal.AllocHGlobal(Marshal.SizeOf(csAddr));
            Marshal.StructureToPtr(csAddr, csAddrPtr, false);

            WSAQUERYSET qs = new WSAQUERYSET();
            qs.dwSize = (uint)Marshal.SizeOf(qs);
            qs.lpszServiceInstanceName = "PC Gamepad & Mouse Bridge";
            qs.lpServiceClassId = guidPtr;
            qs.dwNameSpace = 16;
            qs.dwNumberOfCsAddrs = 1;
            qs.lpcsaBuffer = csAddrPtr;

            WSASetService(ref qs, 0, 0);

            while (isRunning) {
                SOCKADDR_BTH clientAddr = new SOCKADDR_BTH();
                int clientLen = Marshal.SizeOf(clientAddr);
                IntPtr clientSock = accept(listenSock, ref clientAddr, ref clientLen);
                if (!isRunning) break;
                if (clientSock == IntPtr.Zero || clientSock == new IntPtr(-1)) {
                    Thread.Sleep(200);
                    continue;
                }

                UpdateTrayStatus("Trạng thái: Đã kết nối Bluetooth (" + clientAddr.btAddr.ToString("X12") + ")");
                Thread clientThread = new Thread(() => HandleBluetoothStream(clientSock));
                clientThread.IsBackground = true;
                clientThread.Start();
            }
        } catch {
        }
    }

    // ================= 2. UNIVERSAL BLUETOOTH AUTO-CONNECTOR THREAD =================
    static void RunBluetoothAutoConnectorToPairedPhones() {
        Guid sppGuid = new Guid("00001101-0000-1000-8000-00805F9B34FB");

        while (isRunning) {
            try {
                if (!hasActiveBtClient) {
                    List<ulong> pairedMacs = GetAllPairedBluetoothDevicesOnWindows();
                    foreach (ulong mac in pairedMacs) {
                        if (!isRunning || hasActiveBtClient) break;
                        IntPtr sock = socket(32, 1, 3);
                        SOCKADDR_BTH addr = new SOCKADDR_BTH();
                        addr.addressFamily = 32;
                        addr.btAddr = mac;
                        addr.serviceClassId = sppGuid;
                        addr.port = 0;

                        int res = connect(sock, ref addr, Marshal.SizeOf(addr));
                        if (res == 0 && isRunning) {
                            UpdateTrayStatus("Trạng thái: Đã kết nối Bluetooth (" + mac.ToString("X12") + ")");
                            HandleBluetoothStream(sock);
                            break;
                        } else {
                            closesocket(sock);
                        }
                    }
                }
            } catch {
            }
            Thread.Sleep(2500);
        }
    }

    static void HandleBluetoothStream(IntPtr clientSock) {
        hasActiveBtClient = true;
        currentBtClientSock = clientSock;
        byte[] buf = new byte[256];
        byte[] streamBuf = new byte[512];
        int streamLen = 0;

        try {
            while (isRunning) {
                int r = recv(clientSock, buf, buf.Length, 0);
                if (r <= 0) break;

                if (streamLen + r > streamBuf.Length) {
                    streamLen = 0;
                }
                Buffer.BlockCopy(buf, 0, streamBuf, streamLen, r);
                streamLen += r;

                int idx = 0;
                while (idx < streamLen) {
                    byte pktType = streamBuf[idx];
                    if (pktType == 0x01) {
                        if (idx + 7 <= streamLen) {
                            EnqueueGamepadPacket(streamBuf, idx + 1);
                            idx += 7;
                        } else break;
                    } else if (pktType == 0x02) {
                        if (idx + 4 <= streamLen) {
                            EnqueueMousePacket(streamBuf, idx + 1);
                            idx += 4;
                        } else break;
                    } else if (pktType == 0x03) {
                        if (idx + 4 <= streamLen) {
                            EnqueueScrollZoomPacket(streamBuf, idx + 1);
                            idx += 4;
                        } else break;
                    } else if (pktType == 0x04) {
                        if (idx + 2 <= streamLen) {
                            EnqueueGesturePacket(streamBuf, idx + 1);
                            idx += 2;
                        } else break;
                    } else {
                        idx++;
                    }
                }

                if (idx > 0) {
                    int remaining = streamLen - idx;
                    if (remaining > 0) {
                        Buffer.BlockCopy(streamBuf, idx, streamBuf, 0, remaining);
                    }
                    streamLen = remaining;
                }
            }
        } catch {
        } finally {
            hasActiveBtClient = false;
            currentBtClientSock = IntPtr.Zero;
            closesocket(clientSock);
            if (isRunning) {
                UpdateTrayStatus("Trạng thái: Đang chờ kết nối từ điện thoại...");
            }
        }
    }

    // ================= 3. UDP SERVER THREAD =================
    static void RunUdpServer() {
        try {
            int port = 9876;
            currentUdpClient = new UdpClient(port);
            currentUdpClient.Client.ReceiveTimeout = 1000;
            IPEndPoint remoteEP = new IPEndPoint(IPAddress.Any, 0);

            while (isRunning) {
                try {
                    byte[] data = currentUdpClient.Receive(ref remoteEP);
                    if (data == null || data.Length == 0) continue;
                    byte packetType = data[0];
                    if (packetType == 0x00) {
                        byte[] reply = Encoding.ASCII.GetBytes("PONG_PC_BRIDGE");
                        currentUdpClient.Send(reply, reply.Length, remoteEP);
                    } else if (packetType == 0x01 && data.Length >= 7) {
                        EnqueueGamepadPacket(data, 1);
                    } else if (packetType == 0x02 && data.Length >= 4) {
                        EnqueueMousePacket(data, 1);
                    } else if (packetType == 0x03 && data.Length >= 4) {
                        EnqueueScrollZoomPacket(data, 1);
                    } else if (packetType == 0x04 && data.Length >= 2) {
                        EnqueueGesturePacket(data, 1);
                    }
                } catch (SocketException) {
                } catch {
                }
            }
        } catch {
        }
    }

    // ================= SYSTEM TRAY BACKGROUND APP =================
    public PcBridgeServer() {
        InitVirtualXbox360Controller();

        ContextMenuStrip menu = new ContextMenuStrip();

        ToolStripMenuItem titleItem = new ToolStripMenuItem("🎮 BT Gamepad & Mouse Bridge (Universal PC Server)");
        titleItem.Enabled = false;
        menu.Items.Add(titleItem);

        statusItem = new ToolStripMenuItem("Trạng thái: Đang chạy ngầm (Tự động kết nối mọi ĐT)");
        statusItem.Enabled = false;
        menu.Items.Add(statusItem);

        menu.Items.Add(new ToolStripSeparator());

        xboxToggleItem = new ToolStripMenuItem("✅ Tay cầm Xbox 360 ảo: Đang Cắm (Bấm để Rút/Cắm lại)");
        xboxToggleItem.Click += (s, e) => {
            if (xbox360Ready) {
                CleanupVirtualXbox360Controller();
                xboxToggleItem.Text = "❌ Tay cầm Xbox 360 ảo: Đã Rút (Bấm để Cắm lại)";
            } else {
                InitVirtualXbox360Controller();
                xboxToggleItem.Text = "✅ Tay cầm Xbox 360 ảo: Đang Cắm (Bấm để Rút/Cắm lại)";
            }
        };
        menu.Items.Add(xboxToggleItem);

        ToolStripMenuItem testJoyItem = new ToolStripMenuItem("🕹️ Mở trình kiểm tra tay cầm Windows (joy.cpl)");
        testJoyItem.Click += (s, e) => {
            try {
                System.Diagnostics.Process.Start("joy.cpl");
            } catch {}
        };
        menu.Items.Add(testJoyItem);

        menu.Items.Add(new ToolStripSeparator());

        ToolStripMenuItem exitItem = new ToolStripMenuItem("🛑 Tắt ứng dụng chạy ngầm (Thoát hoàn toàn)");
        exitItem.Click += (s, e) => ShutdownServer();
        menu.Items.Add(exitItem);

        trayIcon = new NotifyIcon();
        trayIcon.Icon = SystemIcons.Application;
        trayIcon.Text = "BT Gamepad & Mouse Bridge (Đang chạy ngầm)";
        trayIcon.ContextMenuStrip = menu;
        trayIcon.Visible = true;

        trayIcon.ShowBalloonTip(
            3000,
            "BT Gamepad & Mouse Bridge",
            "Đang chạy ngầm ở khay hệ thống và tự động dò tìm mọi điện thoại qua Bluetooth & Wi-Fi.",
            ToolTipIcon.Info
        );

        Thread inputThread = new Thread(RunInteractiveDesktopInputThread);
        inputThread.IsBackground = true;
        inputThread.Start();

        Thread btServerThread = new Thread(RunBluetoothRfcommServer);
        btServerThread.IsBackground = true;
        btServerThread.Start();

        Thread btClientThread = new Thread(RunBluetoothAutoConnectorToPairedPhones);
        btClientThread.IsBackground = true;
        btClientThread.Start();

        Thread udpThread = new Thread(RunUdpServer);
        udpThread.IsBackground = true;
        udpThread.Start();
    }

    public static void ShutdownServer() {
        isRunning = false;
        try {
            if (trayIcon != null) {
                trayIcon.Visible = false;
                trayIcon.Dispose();
            }
        } catch {}
        CleanupVirtualXbox360Controller();
        try {
            if (currentBtClientSock != IntPtr.Zero) closesocket(currentBtClientSock);
            if (currentBtListenSock != IntPtr.Zero) closesocket(currentBtListenSock);
            if (currentUdpClient != null) currentUdpClient.Close();
        } catch {}
        Application.Exit();
        Environment.Exit(0);
    }

    [STAThread]
    static void Main(string[] args) {
        if (args.Length > 0 && (args[0] == "--stop" || args[0] == "/stop")) {
            foreach (var proc in System.Diagnostics.Process.GetProcessesByName("PcBridgeServer")) {
                if (proc.Id != System.Diagnostics.Process.GetCurrentProcess().Id) {
                    try { proc.Kill(); } catch {}
                }
            }
            return;
        }

        foreach (var proc in System.Diagnostics.Process.GetProcessesByName("PcBridgeServer")) {
            if (proc.Id != System.Diagnostics.Process.GetCurrentProcess().Id) {
                try { proc.Kill(); } catch {}
            }
        }

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new PcBridgeServer());
    }
}
