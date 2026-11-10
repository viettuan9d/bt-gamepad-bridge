# 🎮 BT Gamepad & Multi-Touch Mouse Bridge (Android / Windows / Linux)

Biến điện thoại Android (cắm tay cầm Type-C như GameSir, Razer Kishi...) thành **Tay cầm Xbox 360 không dây (`Xbox 360 Controller`)** và **Bàn rê chuột Cảm ứng đa điểm (Multi-Touch Precision Trackpad)** qua sóng **Bluetooth (RFCOMM + HID)** và **Wi-Fi UDP** cho **Windows**, **Linux** và **Android-to-Android**.

---

## 🚀 Tính năng nổi bật

1. **Giả lập Tay cầm Xbox 360 thực thụ (XInput / Kernel uinput):**
   * **Trên Windows:** Tích hợp `ViGEmClient` cắm trực tiếp `Xbox 360 Controller for Windows` (`VID_045E&PID_028E`), nhận diện 100% trong `joy.cpl`, Steam, PC Game Pass và [Gamepad Tester](https://hardwaretester.com/gamepad).
   * **Trên Linux:** Tạo trực tiếp `Microsoft X-Box 360 pad` ở tầng nhân `/dev/uinput` (không cần cài đặt thêm thư viện ngoài).
   * **Trên Điện thoại:** Có sơ đồ **Visual Gamepad Tester** hiển thị trực quan mọi nút bấm và tọa độ 2 cần Analog theo thời gian thực.

2. **Bàn rê chuột Cảm ứng Đa điểm (Multi-Touch Trackpad):**
   * **1 Ngón:** Vuốt di chuột có gia tốc thông minh, chạm 1 lần = Click Chuột Trái, chạm đúp & giữ = Kéo thả (Drag).
   * **2 Ngón:** Chạm = Click Chuột Phải, vuốt dọc/ngang = Cuộn trang 2 chiều, chụm/tách = Phóng to / Thu nhỏ (`Ctrl + Con lăn`).
   * **3 Ngón:** Chạm = Chuột Giữa, vuốt lên = Mở Đa nhiệm (`Win + Tab` / `Super`), vuốt xuống = Hiện Desktop (`Win + D`), vuốt ngang = Chuyển cửa sổ (`Alt + Tab`).

3. **Hỗ trợ điều khiển giữa 2 máy Android (Android-to-Android):**
   * Tích hợp sẵn dịch vụ `AndroidReceiverAccessibilityService` hiển thị con trỏ chuột ảo trên màn hình máy Android thứ 2.

4. **Chạy ngầm gọn nhẹ & Tắt hoàn toàn bất cứ lúc nào:**
   * **Windows:** Chạy ngầm trên khay hệ thống (System Tray) hoặc dùng `Bat-Server-Chay-Ngam.bat` / `Tat-Server-Chay-Ngam.bat`.
   * **Linux:** Chạy ngầm qua `start-linux-server.sh` và tắt qua `stop-linux-server.sh`.
   * **Android:** Có nút `🛑 Tắt` trên góc ứng dụng và ngay trên thanh thông báo (Notification).

---

## 📦 Tải xuống và Sử dụng ngay (`Release-Package/`)

Toàn bộ file biên dịch sẵn nằm trong thư mục [`Release-Package/`](./Release-Package):
* `BT-Gamepad-Bridge.apk`: Cài đặt lên điện thoại Android.
* `PcBridgeServer.exe` & `ViGEmClient.dll`: Ứng dụng chạy ngầm trên Windows.
* `linux_bridge_server.py`, `start-linux-server.sh`, `stop-linux-server.sh`: Ứng dụng chạy ngầm trên Linux.
