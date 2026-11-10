#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LINUX XBOX 360 CONTROLLER & MULTI-TOUCH BRIDGE SERVER (KERNEL UINPUT)
---------------------------------------------------------------------
- Tạo trực tiếp 2 thiết bị phần cứng ảo trong nhân Linux (/dev/uinput):
  1) "Microsoft X-Box 360 pad" (VID=0x045e, PID=0x028e) -> Nhận diện 100% trong Steam, Proton, jstest, evtest, Gamepad Tester.
  2) "BT Bridge Multi-Touch Mouse & Keyboard" -> Hoạt động trên cả X11 lẫn Wayland (Ubuntu, Arch, Fedora, SteamOS...).
- Nhận kết nối đồng thời qua:
  + Sóng Bluetooth RFCOMM (Channel 4 & Auto-Connect)
  + Mạng Wi-Fi UDP (Port 9876)
- Hỗ trợ chạy ngầm (--daemon) và tắt hoàn toàn (--stop).
"""

import os
import sys
import glob
import time
import fcntl
import struct
import socket
import signal
import subprocess
import threading

# ================= LINUX INPUT / UINPUT CONSTANTS =================
UINPUT_MAX_NAME_SIZE = 80
BUS_USB = 0x03

# ioctl request codes for /dev/uinput (x86_64 / ARM64 Linux standard)
UI_SET_EVBIT   = 0x40045564
UI_SET_KEYBIT  = 0x40045565
UI_SET_RELBIT  = 0x40045566
UI_SET_ABSBIT  = 0x40045567
UI_DEV_CREATE  = 0x5501
UI_DEV_DESTROY = 0x5502

EV_SYN = 0x00
EV_KEY = 0x01
EV_REL = 0x02
EV_ABS = 0x03

SYN_REPORT = 0x00

# Mouse buttons & axes
BTN_LEFT   = 0x110
BTN_RIGHT  = 0x111
BTN_MIDDLE = 0x112

REL_X      = 0x00
REL_Y      = 0x01
REL_HWHEEL = 0x06
REL_WHEEL  = 0x08

# Keyboard keys for Multi-Touch Zoom & 3-Finger Gestures
KEY_TAB      = 15
KEY_LEFTCTRL = 29
KEY_D        = 32
KEY_LEFTSHIFT= 42
KEY_LEFTALT  = 56
KEY_LEFTMETA = 125 # Super / Windows key

# Xbox 360 Controller Buttons (Linux Gamepad Spec)
BTN_A      = 0x130
BTN_B      = 0x131
BTN_X      = 0x133
BTN_Y      = 0x134
BTN_TL     = 0x136 # LB / L1
BTN_TR     = 0x137 # RB / R1
BTN_SELECT = 0x13a # Back / Select
BTN_START  = 0x13b # Start
BTN_MODE   = 0x13c # Xbox Guide
BTN_THUMBL = 0x13d # L3
BTN_THUMBR = 0x13e # R3

# Xbox 360 Controller Axes
ABS_X     = 0x00 # Left Stick X (-32768..32767)
ABS_Y     = 0x01 # Left Stick Y (-32768..32767)
ABS_Z     = 0x02 # Left Trigger L2 (0..255)
ABS_RX    = 0x03 # Right Stick X (-32768..32767)
ABS_RY    = 0x04 # Right Stick Y (-32768..32767)
ABS_RZ    = 0x05 # Right Trigger R2 (0..255)
ABS_HAT0X = 0x10 # D-Pad X (-1, 0, 1)
ABS_HAT0Y = 0x11 # D-Pad Y (-1, 0, 1)

PID_FILE = "/tmp/linux_bt_bridge_server.pid"


class LinuxUinputDevice:
    def __init__(self, name, vendor, product, version):
        self.name = name
        self.vendor = vendor
        self.product = product
        self.version = version
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        self.absmin = [0] * 64
        self.absmax = [0] * 64
        self.absfuzz = [0] * 64
        self.absflat = [0] * 64

    def enable_ev(self, ev):
        fcntl.ioctl(self.fd, UI_SET_EVBIT, ev)

    def enable_key(self, key):
        fcntl.ioctl(self.fd, UI_SET_KEYBIT, key)

    def enable_rel(self, rel):
        fcntl.ioctl(self.fd, UI_SET_RELBIT, rel)

    def enable_abs(self, axis, min_val, max_val, fuzz=0, flat=0):
        fcntl.ioctl(self.fd, UI_SET_ABSBIT, axis)
        self.absmin[axis] = min_val
        self.absmax[axis] = max_val
        self.absfuzz[axis] = fuzz
        self.absflat[axis] = flat

    def create(self):
        # struct uinput_user_dev:
        # char name[80]; struct input_id (4x uint16); uint32 ff_effects_max; int32 absmax[64]; int32 absmin[64]; int32 absfuzz[64]; int32 absflat[64]
        name_bytes = self.name.encode("utf-8")[:UINPUT_MAX_NAME_SIZE - 1]
        name_padded = name_bytes.ljust(UINPUT_MAX_NAME_SIZE, b"\x00")
        header = struct.pack("<80sHHHHI", name_padded, BUS_USB, self.vendor, self.product, self.version, 0)
        abs_arrays = (
            struct.pack("<64i", *self.absmax) +
            struct.pack("<64i", *self.absmin) +
            struct.pack("<64i", *self.absfuzz) +
            struct.pack("<64i", *self.absflat)
        )
        os.write(self.fd, header + abs_arrays)
        fcntl.ioctl(self.fd, UI_DEV_CREATE)

    def emit(self, ev_type, code, value):
        now = time.time()
        sec = int(now)
        usec = int((now - sec) * 1000000)
        pkt = struct.pack("<llHHi", sec, usec, ev_type, code, int(value))
        os.write(self.fd, pkt)

    def syn(self):
        self.emit(EV_SYN, SYN_REPORT, 0)

    def close(self):
        if self.fd is not None:
            try:
                fcntl.ioctl(self.fd, UI_DEV_DESTROY)
            except Exception:
                pass
            try:
                os.close(self.fd)
            except Exception:
                pass
            self.fd = None


class LinuxBridgeServer:
    def __init__(self, phone_mac=None):
        self.phone_mac = phone_mac
        self.running = True
        self.has_active_bt = False
        self.lock = threading.Lock()
        self.prev_mouse_btns = 0
        self.prev_gamepad_mask = -1

        # 1. Tạo tay cầm "Microsoft X-Box 360 pad" chuẩn trong nhân Linux
        self.xbox_dev = LinuxUinputDevice("Microsoft X-Box 360 pad", 0x045e, 0x028e, 0x0110)
        self.xbox_dev.enable_ev(EV_KEY)
        self.xbox_dev.enable_ev(EV_ABS)
        for btn in [BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR]:
            self.xbox_dev.enable_key(btn)

        self.xbox_dev.enable_abs(ABS_X, -32768, 32767, 16, 128)
        self.xbox_dev.enable_abs(ABS_Y, -32768, 32767, 16, 128)
        self.xbox_dev.enable_abs(ABS_RX, -32768, 32767, 16, 128)
        self.xbox_dev.enable_abs(ABS_RY, -32768, 32767, 16, 128)
        self.xbox_dev.enable_abs(ABS_Z, 0, 255, 0, 0)
        self.xbox_dev.enable_abs(ABS_RZ, 0, 255, 0, 0)
        self.xbox_dev.enable_abs(ABS_HAT0X, -1, 1, 0, 0)
        self.xbox_dev.enable_abs(ABS_HAT0Y, -1, 1, 0, 0)
        self.xbox_dev.create()

        # 2. Tạo Chuột & Bàn phím ảo cho Multi-Touch Trackpad (Hoạt động trên cả X11 & Wayland)
        self.mouse_dev = LinuxUinputDevice("BT Bridge Multi-Touch Mouse", 0x1d6b, 0x0104, 0x0100)
        self.mouse_dev.enable_ev(EV_KEY)
        self.mouse_dev.enable_ev(EV_REL)
        for btn in [BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, KEY_LEFTCTRL, KEY_LEFTMETA, KEY_LEFTALT, KEY_LEFTSHIFT, KEY_TAB, KEY_D]:
            self.mouse_dev.enable_key(btn)
        for rel in [REL_X, REL_Y, REL_WHEEL, REL_HWHEEL]:
            self.mouse_dev.enable_rel(rel)
        self.mouse_dev.create()

        print("[+] Đã khởi tạo thành công 'Microsoft X-Box 360 pad' & 'Multi-Touch Mouse' trong nhân Linux (/dev/uinput)!")

    @staticmethod
    def scale_axis(val_byte):
        v = struct.unpack("<b", bytes([val_byte]))[0]
        if abs(v) <= 2:
            return 0
        scaled = v * 258
        return max(-32768, min(32767, scaled))

    def process_gamepad(self, data, offset):
        with self.lock:
            mask = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
            lx = self.scale_axis(data[offset + 2])
            ly = self.scale_axis(data[offset + 3])
            rx = self.scale_axis(data[offset + 4])
            ry = self.scale_axis(data[offset + 5])

            self.xbox_dev.emit(EV_KEY, BTN_A, 1 if (mask & (1 << 0)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_B, 1 if (mask & (1 << 1)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_X, 1 if (mask & (1 << 2)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_Y, 1 if (mask & (1 << 3)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_TL, 1 if (mask & (1 << 4)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_TR, 1 if (mask & (1 << 5)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_SELECT, 1 if (mask & (1 << 6)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_START, 1 if (mask & (1 << 7)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_THUMBL, 1 if (mask & (1 << 12)) else 0)
            self.xbox_dev.emit(EV_KEY, BTN_THUMBR, 1 if (mask & (1 << 13)) else 0)

            hat_x = 0
            if mask & (1 << 10): hat_x = -1
            elif mask & (1 << 11): hat_x = 1

            hat_y = 0
            if mask & (1 << 8): hat_y = -1
            elif mask & (1 << 9): hat_y = 1

            self.xbox_dev.emit(EV_ABS, ABS_HAT0X, hat_x)
            self.xbox_dev.emit(EV_ABS, ABS_HAT0Y, hat_y)
            self.xbox_dev.emit(EV_ABS, ABS_Z, 255 if (mask & (1 << 14)) else 0)
            self.xbox_dev.emit(EV_ABS, ABS_RZ, 255 if (mask & (1 << 15)) else 0)

            self.xbox_dev.emit(EV_ABS, ABS_X, lx)
            self.xbox_dev.emit(EV_ABS, ABS_Y, ly)
            self.xbox_dev.emit(EV_ABS, ABS_RX, rx)
            self.xbox_dev.emit(EV_ABS, ABS_RY, ry)
            self.xbox_dev.syn()

    def process_mouse(self, data, offset):
        with self.lock:
            btns = data[offset]
            dx = struct.unpack("<b", bytes([data[offset + 1]]))[0]
            dy = struct.unpack("<b", bytes([data[offset + 2]]))[0]

            if (btns & 0x01) != (self.prev_mouse_btns & 0x01):
                self.mouse_dev.emit(EV_KEY, BTN_LEFT, 1 if (btns & 0x01) else 0)
            if (btns & 0x02) != (self.prev_mouse_btns & 0x02):
                self.mouse_dev.emit(EV_KEY, BTN_RIGHT, 1 if (btns & 0x02) else 0)
            if (btns & 0x04) != (self.prev_mouse_btns & 0x04):
                self.mouse_dev.emit(EV_KEY, BTN_MIDDLE, 1 if (btns & 0x04) else 0)
            self.prev_mouse_btns = btns

            if dx != 0:
                self.mouse_dev.emit(EV_REL, REL_X, dx)
            if dy != 0:
                self.mouse_dev.emit(EV_REL, REL_Y, dy)
            self.mouse_dev.syn()

    def process_scroll_zoom(self, data, offset):
        with self.lock:
            wheel_y = struct.unpack("<b", bytes([data[offset]]))[0]
            wheel_x = struct.unpack("<b", bytes([data[offset + 1]]))[0]
            zoom = struct.unpack("<b", bytes([data[offset + 2]]))[0]

            if wheel_y != 0:
                self.mouse_dev.emit(EV_REL, REL_WHEEL, wheel_y)
                self.mouse_dev.syn()
            if wheel_x != 0:
                self.mouse_dev.emit(EV_REL, REL_HWHEEL, -wheel_x)
                self.mouse_dev.syn()
            if zoom != 0:
                self.mouse_dev.emit(EV_KEY, KEY_LEFTCTRL, 1)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_REL, REL_WHEEL, zoom)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_KEY, KEY_LEFTCTRL, 0)
                self.mouse_dev.syn()

    def process_gesture(self, data, offset):
        with self.lock:
            code = data[offset]
            if code == 1:
                # 3-Finger Swipe Up -> GNOME/KDE Overview (Super key)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTMETA, 1)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_KEY, KEY_LEFTMETA, 0)
                self.mouse_dev.syn()
            elif code == 2:
                # 3-Finger Swipe Down -> Show Desktop (Super + D)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTMETA, 1)
                self.mouse_dev.emit(EV_KEY, KEY_D, 1)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_KEY, KEY_D, 0)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTMETA, 0)
                self.mouse_dev.syn()
            elif code == 3:
                # 3-Finger Swipe Left -> Alt + Shift + Tab
                self.mouse_dev.emit(EV_KEY, KEY_LEFTALT, 1)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTSHIFT, 1)
                self.mouse_dev.emit(EV_KEY, KEY_TAB, 1)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_KEY, KEY_TAB, 0)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTSHIFT, 0)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTALT, 0)
                self.mouse_dev.syn()
            elif code == 4:
                # 3-Finger Swipe Right -> Alt + Tab
                self.mouse_dev.emit(EV_KEY, KEY_LEFTALT, 1)
                self.mouse_dev.emit(EV_KEY, KEY_TAB, 1)
                self.mouse_dev.syn()
                self.mouse_dev.emit(EV_KEY, KEY_TAB, 0)
                self.mouse_dev.emit(EV_KEY, KEY_LEFTALT, 0)
                self.mouse_dev.syn()

    def handle_bt_stream(self, sock):
        self.has_active_bt = True
        stream_buf = bytearray()
        try:
            while self.running:
                chunk = sock.recv(256)
                if not chunk:
                    break
                stream_buf.extend(chunk)
                idx = 0
                n = len(stream_buf)
                while idx < n:
                    pkt_type = stream_buf[idx]
                    if pkt_type == 0x01:
                        if idx + 7 <= n:
                            self.process_gamepad(stream_buf, idx + 1)
                            idx += 7
                        else:
                            break
                    elif pkt_type == 0x02:
                        if idx + 4 <= n:
                            self.process_mouse(stream_buf, idx + 1)
                            idx += 4
                        else:
                            break
                    elif pkt_type == 0x03:
                        if idx + 4 <= n:
                            self.process_scroll_zoom(stream_buf, idx + 1)
                            idx += 4
                        else:
                            break
                    elif pkt_type == 0x04:
                        if idx + 2 <= n:
                            self.process_gesture(stream_buf, idx + 1)
                            idx += 2
                        else:
                            break
                    else:
                        idx += 1
                if idx > 0:
                    del stream_buf[:idx]
        except Exception:
            pass
        finally:
            self.has_active_bt = False
            try:
                sock.close()
            except Exception:
                pass
            print("[BT] Kết nối Bluetooth tạm ngắt, đang chờ kết nối lại...")

    def run_bt_rfcomm_server(self):
        if not hasattr(socket, "AF_BLUETOOTH"):
            print("[!] Python trên máy này chưa bật AF_BLUETOOTH, đang dùng chế độ Wi-Fi UDP.")
            return
        try:
            srv = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            srv.bind((socket.BDADDR_ANY, 4))
            srv.listen(4)
            print("[BT Server] Đang lắng nghe Bluetooth RFCOMM tại Channel 4...")
            while self.running:
                client_sock, addr = srv.accept()
                print(f"[BT] >>> ĐÃ KẾT NỐI BLUETOOTH TỪ ĐIỆN THOẠI: {addr} <<<")
                t = threading.Thread(target=self.handle_bt_stream, args=(client_sock,), daemon=True)
                t.start()
        except Exception as e:
            print(f"[BT Server] Lưu ý: {e}")

    @staticmethod
    def get_paired_bluetooth_macs(preferred_mac=None):
        macs = []
        if preferred_mac:
            macs.append(preferred_mac)
        # 1. Đọc từ /var/lib/bluetooth/<adapter>/<device_mac>/info
        try:
            for path in glob.glob("/var/lib/bluetooth/*/*"):
                base = os.path.basename(path)
                if len(base) == 17 and base.count(":") == 5:
                    if base not in macs:
                        macs.append(base)
        except Exception:
            pass
        # 2. Dự phòng qua bluetoothctl devices
        try:
            out = subprocess.check_output(["bluetoothctl", "devices"], stderr=subprocess.DEVNULL, timeout=2.0).decode("utf-8", errors="ignore")
            for line in out.splitlines():
                parts = line.strip().split()
                if len(parts) >= 2 and parts[0] == "Device" and len(parts[1]) == 17 and parts[1].count(":") == 5:
                    if parts[1] not in macs:
                        macs.append(parts[1])
        except Exception:
            pass
        return macs

    def run_bt_auto_connect(self):
        if not hasattr(socket, "AF_BLUETOOTH"):
            return
        while self.running:
            if not self.has_active_bt:
                candidate_macs = self.get_paired_bluetooth_macs(self.phone_mac)
                for mac in candidate_macs:
                    if not self.running or self.has_active_bt:
                        break
                    for ch in (4, 1, 2, 3, 5):
                        if not self.running or self.has_active_bt:
                            break
                        try:
                            s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                            s.settimeout(1.5)
                            s.connect((mac, ch))
                            s.settimeout(None)
                            print(f"[BT] >>> ĐÃ TỰ ĐỘNG KẾT NỐI BLUETOOTH TỚI ĐIỆN THOẠI ({mac}, Ch {ch}) <<<")
                            self.handle_bt_stream(s)
                            break
                        except Exception:
                            try:
                                s.close()
                            except Exception:
                                pass
            time.sleep(3.0)

    def run_udp_server(self):
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.bind(("0.0.0.0", 9876))
        udp.settimeout(1.0)
        print("[UDP] Đang lắng nghe tại cổng 9876...")
        while self.running:
            try:
                data, addr = udp.recvfrom(256)
                if not data:
                    continue
                t = data[0]
                if t == 0x00:
                    udp.sendto(b"PONG_PC_BRIDGE", addr)
                elif t == 0x01 and len(data) >= 7:
                    self.process_gamepad(data, 1)
                elif t == 0x02 and len(data) >= 4:
                    self.process_mouse(data, 1)
                elif t == 0x03 and len(data) >= 4:
                    self.process_scroll_zoom(data, 1)
                elif t == 0x04 and len(data) >= 2:
                    self.process_gesture(data, 1)
            except socket.timeout:
                continue
            except Exception:
                pass

    def shutdown(self):
        self.running = False
        self.xbox_dev.close()
        self.mouse_dev.close()
        if os.path.exists(PID_FILE):
            try:
                os.remove(PID_FILE)
            except Exception:
                pass


def stop_existing_daemon():
    if os.path.exists(PID_FILE):
        try:
            with open(PID_FILE, "r") as f:
                pid = int(f.read().strip())
            os.kill(pid, signal.SIGTERM)
            print(f"[+] Đã tắt tiến trình Linux Bridge Server chạy ngầm (PID={pid}) và rút tay cầm Xbox 360 ảo!")
        except Exception:
            pass
        try:
            os.remove(PID_FILE)
        except Exception:
            pass
    else:
        print("[i] Không có tiến trình Linux Bridge Server nào đang chạy.")


def main():
    if "--stop" in sys.argv:
        stop_existing_daemon()
        return

    if os.geteuid() != 0 and not os.access("/dev/uinput", os.W_OK):
        print("[!] Cần quyền truy cập /dev/uinput để tạo tay cầm Xbox 360 ảo trên Linux.")
        print("    Hãy chạy lệnh bằng sudo: sudo python3 linux_bridge_server.py --daemon")
        sys.exit(1)

    stop_existing_daemon()

    if "--daemon" in sys.argv:
        pid = os.fork()
        if pid > 0:
            print(f"[+] Linux Xbox 360 & Multi-Touch Bridge đang chạy ngầm (PID={pid}).")
            print("    Để tắt ứng dụng chạy ngầm, chạy: sudo ./stop-linux-server.sh")
            sys.exit(0)
        os.setsid()

    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))

    server = LinuxBridgeServer()

    def sig_handler(signum, frame):
        server.shutdown()
        sys.exit(0)

    signal.signal(signal.SIGTERM, sig_handler)
    signal.signal(signal.SIGINT, sig_handler)

    t_bt_srv = threading.Thread(target=server.run_bt_rfcomm_server, daemon=True)
    t_bt_srv.start()

    t_bt_cli = threading.Thread(target=server.run_bt_auto_connect, daemon=True)
    t_bt_cli.start()

    server.run_udp_server()


if __name__ == "__main__":
    main()
