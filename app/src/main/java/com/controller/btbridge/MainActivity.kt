package com.controller.btbridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private lateinit var btRfcomm: BluetoothRfcommBridge
    private lateinit var btHidManager: BluetoothHidManager
    private lateinit var wifiBridge: WifiUdpBridge
    private lateinit var inputManager: InputManager

    private lateinit var tvStatus: TextView
    private lateinit var tvInputDebug: TextView
    private lateinit var gamepadVisualizer: GamepadVisualizerView

    private var buttonMask = 0
    private var dpadAxisMask = 0
    private var triggerAxisMask = 0

    private var leftStickX: Byte = 0
    private var leftStickY: Byte = 0
    private var rightStickX: Byte = 0
    private var rightStickY: Byte = 0
    private var l2Float = 0f
    private var r2Float = 0f

    // ================= MULTI-TOUCH STATE =================
    private var mouseBtnState: Byte = 0
    private var isTapDragging = false
    private var lastSingleTapUpTime = 0L

    private var gestureStartTime = 0L
    private var maxPointersInGesture = 0
    private var hasMovedSignificantly = false

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastSingleX = 0f
    private var lastSingleY = 0f

    private var lastTwoCenterX = 0f
    private var lastTwoCenterY = 0f
    private var lastTwoSpan = 0f
    private var scrollAccumY = 0f
    private var scrollAccumX = 0f
    private var zoomAccum = 0f

    private var startThreeCenterX = 0f
    private var startThreeCenterY = 0f
    private var threeFingerSwipeTriggered = false

    private var lastKeyName = "Chưa nhấn"

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvInputDebug = findViewById(R.id.tvInputDebug)
        gamepadVisualizer = findViewById(R.id.gamepadVisualizer)

        BridgeForegroundService.onStopRequested = {
            shutdownAndExitApp()
        }
        startBackgroundKeepAliveService()

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, null)
        detectConnectedControllers()

        btRfcomm = BluetoothRfcommBridge(context = this) { isConnected, msg ->
            runOnUiThread {
                tvStatus.text = if (isConnected) "✅ $msg" else "⏳ $msg"
                tvStatus.setTextColor(if (isConnected) 0xFF00FF66.toInt() else 0xFFFFAA00.toInt())
            }
        }

        btHidManager = BluetoothHidManager(this) { isConnected, devName ->
            runOnUiThread {
                if (isConnected && !btRfcomm.isConnected) {
                    tvStatus.text = "✅ Đã kết nối Bluetooth HID: $devName"
                    tvStatus.setTextColor(0xFF00FF66.toInt())
                }
            }
        }

        wifiBridge = WifiUdpBridge(context = this) { foundIp ->
            runOnUiThread {
                if (!btRfcomm.isConnected) {
                    tvStatus.text = "✅ Đã tự động tìm thấy PC qua Wi-Fi ($foundIp)"
                    tvStatus.setTextColor(0xFF00FF66.toInt())
                }
            }
        }
        wifiBridge.start()

        if (hasBluetoothPermissions()) {
            startBluetoothConnections()
        } else {
            requestBluetoothPermissions()
        }

        setupHeaderButtons()
        setupMultiTouchTrackpad()
        setupMouseButtons()
    }

    private fun startBackgroundKeepAliveService() {
        try {
            val svcIntent = Intent(this, BridgeForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (_: Exception) {
        }
    }

    private fun shutdownAndExitApp() {
        try {
            stopService(Intent(this, BridgeForegroundService::class.java))
        } catch (_: Exception) {}
        try {
            inputManager.unregisterInputDeviceListener(this)
        } catch (_: Exception) {}
        btRfcomm.stop()
        btHidManager.stop()
        wifiBridge.stop()
        finishAndRemoveTask()
        exitProcess(0)
    }

    private fun detectConnectedControllers() {
        val controllers = mutableListOf<String>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val src = dev.sources
            val isPad = (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                    (src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            if (isPad) {
                controllers.add(dev.name)
            }
        }
        if (controllers.isNotEmpty()) {
            tvInputDebug.text = "✅ Đã nhận tay cầm: ${controllers.joinToString(", ")} — Hãy bấm nút để test!"
            gamepadVisualizer.visibility = View.VISIBLE
        } else {
            tvInputDebug.text = "Chưa cắm tay cầm Type-C (Nhớ bật 'OTG' trong Cài đặt nếu cắm chưa nhận)"
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        detectConnectedControllers()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        detectConnectedControllers()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        detectConnectedControllers()
    }

    private fun startBluetoothConnections() {
        btHidManager.start()
        btRfcomm.startAll()
    }

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = mutableListOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startBluetoothConnections()
            } else {
                tvStatus.text = "⚠️ Hãy bấm '🔵 Kết nối' -> Cấp quyền 'Thiết bị ở gần'!"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupHeaderButtons() {
        findViewById<Button>(R.id.btnTogglePadView).setOnClickListener {
            gamepadVisualizer.visibility = if (gamepadVisualizer.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        findViewById<Button>(R.id.btnConnectHost).setOnClickListener {
            if (!hasBluetoothPermissions()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    AlertDialog.Builder(this)
                        .setTitle("Cần cấp quyền Thiết bị ở gần (Bluetooth)")
                        .setMessage("Hãy bấm 'Mở Cài đặt' -> Quyền của ứng dụng -> Thiết bị ở gần (Nearby devices) -> Chọn 'Cho phép'.")
                        .setPositiveButton("Mở Cài đặt") { _, _ ->
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }
                        .setNeutralButton("Thử xin quyền lại") { _, _ ->
                            requestBluetoothPermissions()
                        }
                        .show()
                } else {
                    requestBluetoothPermissions()
                }
                return@setOnClickListener
            }

            val paired = btHidManager.getPairedDevices()
            val names = paired.map { "${it.name ?: "Thiết bị BT"} (${it.address})" }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Chọn thiết bị đích (Windows / Linux / Android 2)")
                .setItems(names) { _, which ->
                    val targetDev = paired[which]
                    btRfcomm.connectAutoOrDevice(targetDev)
                    btHidManager.connectToHost(targetDev)
                }
                .setPositiveButton("Tự động dò BT & Wi-Fi") { _, _ ->
                    btRfcomm.connectAutoOrDevice(null)
                    wifiBridge.autoDiscover()
                }
                .setNeutralButton("Cấu hình Wi-Fi / Android 2") { _, _ ->
                    showAdvancedConnectionDialog()
                }
                .setNegativeButton("Đóng", null)
                .show()
        }

        findViewById<Button>(R.id.btnRotate).setOnClickListener {
            val currentOrientation = resources.configuration.orientation
            requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        findViewById<Button>(R.id.btnExitApp).setOnClickListener {
            shutdownAndExitApp()
        }
    }

    private fun showAdvancedConnectionDialog() {
        val input = EditText(this).apply {
            setText(if (wifiBridge.hostIp == "255.255.255.255") "" else wifiBridge.hostIp)
            hint = "Nhập IP máy tính (Để trống = Tự động dò tìm)"
        }
        val receiverActive = AndroidReceiverAccessibilityService.instance != null
        val receiverBtnLabel = if (receiverActive) "✅ Máy Nhận Android 2: Đang Bật" else "📱 Bật làm Máy Nhận (Android 2)"

        AlertDialog.Builder(this)
            .setTitle("Tùy chọn Kết nối Wi-Fi & 2 Máy Android")
            .setMessage(
                "• Chế độ Wi-Fi LAN: Ứng dụng tự động dò tìm mọi PC Windows/Linux trong mạng Wi-Fi, hoặc bạn có thể nhập IP thủ công bên dưới.\n" +
                "• Chế độ 2 Máy Android: Bấm nút bên dưới trên Máy số 2 để bật con trỏ chuột trên màn hình."
            )
            .setView(input)
            .setPositiveButton("Lưu IP / Tự dò Wi-Fi") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    wifiBridge.setTargetIp(ip)
                } else {
                    wifiBridge.autoDiscover()
                }
            }
            .setNeutralButton(receiverBtnLabel) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // ================= BỘ XỬ LÝ CẢM ỨNG ĐA ĐIỂM (MULTI-TOUCH TRACKPAD ENGINE) =================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMultiTouchTrackpad() {
        val touchpad = findViewById<FrameLayout>(R.id.touchpadArea)
        touchpad.setOnTouchListener { _, event ->
            val pointerCount = event.pointerCount

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    gestureStartTime = now
                    maxPointersInGesture = 1
                    hasMovedSignificantly = false
                    threeFingerSwipeTriggered = false

                    activePointerId = event.getPointerId(0)
                    lastSingleX = event.getX(0)
                    lastSingleY = event.getY(0)

                    if (now - lastSingleTapUpTime < 260L) {
                        isTapDragging = true
                        mouseBtnState = (mouseBtnState.toInt() or 0x01).toByte()
                        btHidManager.triggerVibration(15)
                        sendMousePacket(mouseBtnState, 0, 0)
                        tvInputDebug.text = "Multi-Touch: Giữ & Kéo thả (Double-Tap Drag)"
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount > maxPointersInGesture) {
                        maxPointersInGesture = pointerCount
                    }
                    if (isTapDragging) {
                        isTapDragging = false
                        mouseBtnState = (mouseBtnState.toInt() and 0x01.inv()).toByte()
                        sendMousePacket(mouseBtnState, 0, 0)
                    }

                    if (pointerCount == 2) {
                        lastTwoCenterX = (event.getX(0) + event.getX(1)) * 0.5f
                        lastTwoCenterY = (event.getY(0) + event.getY(1)) * 0.5f
                        lastTwoSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                        scrollAccumX = 0f
                        scrollAccumY = 0f
                        zoomAccum = 0f
                        tvInputDebug.text = "Multi-Touch (2 ngón): Cuộn trang / Pinch Zoom / Chạm Chuột phải"
                    } else if (pointerCount == 3) {
                        startThreeCenterX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                        startThreeCenterY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                        threeFingerSwipeTriggered = false
                        tvInputDebug.text = "Multi-Touch (3 ngón): Vuốt Lên(Đa nhiệm) / Xuống(Home) / Ngang(Chuyển App)"
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    when {
                        pointerCount == 1 && maxPointersInGesture == 1 -> {
                            val idx = event.findPointerIndex(activePointerId)
                            if (idx != -1) {
                                val curX = event.getX(idx)
                                val curY = event.getY(idx)
                                val rawDx = curX - lastSingleX
                                val rawDy = curY - lastSingleY

                                if (abs(rawDx) > 3f || abs(rawDy) > 3f) {
                                    hasMovedSignificantly = true
                                }

                                val speed = hypot(rawDx, rawDy)
                                val accel = (1.6f + (speed * 0.04f)).coerceIn(1.5f, 3.2f)
                                val dx = (rawDx * accel).roundToInt().coerceIn(-127, 127).toByte()
                                val dy = (rawDy * accel).roundToInt().coerceIn(-127, 127).toByte()

                                lastSingleX = curX
                                lastSingleY = curY

                                if (dx.toInt() != 0 || dy.toInt() != 0) {
                                    sendMousePacket(mouseBtnState, dx, dy)
                                }
                            }
                        }

                        pointerCount == 2 -> {
                            val centerX = (event.getX(0) + event.getX(1)) * 0.5f
                            val centerY = (event.getY(0) + event.getY(1)) * 0.5f
                            val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

                            val dCenterX = centerX - lastTwoCenterX
                            val dCenterY = centerY - lastTwoCenterY
                            val dSpan = span - lastTwoSpan

                            if (abs(dCenterX) > 4f || abs(dCenterY) > 4f || abs(dSpan) > 6f) {
                                hasMovedSignificantly = true
                            }

                            lastTwoCenterX = centerX
                            lastTwoCenterY = centerY
                            lastTwoSpan = span

                            if (abs(dSpan) > abs(dCenterY) * 1.35f && abs(dSpan) > 4f) {
                                zoomAccum += dSpan / 28f
                                val zoomSteps = zoomAccum.toInt().coerceIn(-10, 10)
                                if (zoomSteps != 0) {
                                    zoomAccum -= zoomSteps
                                    sendScrollZoomPacket(0, 0, zoomSteps.toByte())
                                    tvInputDebug.text = "Multi-Touch: Phóng to / Thu nhỏ (Zoom: $zoomSteps)"
                                }
                            } else {
                                scrollAccumY += dCenterY / 14f
                                scrollAccumX += dCenterX / 18f

                                val wheelY = scrollAccumY.toInt().coerceIn(-15, 15)
                                val wheelX = scrollAccumX.toInt().coerceIn(-15, 15)

                                if (wheelY != 0 || wheelX != 0) {
                                    scrollAccumY -= wheelY
                                    scrollAccumX -= wheelX
                                    sendScrollZoomPacket(wheelY.toByte(), wheelX.toByte(), 0)
                                    tvInputDebug.text = "Multi-Touch: Cuộn trang (Dọc: $wheelY, Ngang: $wheelX)"
                                }
                            }
                        }

                        pointerCount >= 3 && !threeFingerSwipeTriggered -> {
                            val centerX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                            val centerY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                            val totalDx = centerX - startThreeCenterX
                            val totalDy = centerY - startThreeCenterY

                            val threshold = 75f
                            if (abs(totalDy) > threshold && abs(totalDy) > abs(totalDx)) {
                                hasMovedSignificantly = true
                                threeFingerSwipeTriggered = true
                                btHidManager.triggerVibration(25)
                                if (totalDy < 0) {
                                    sendGesturePacket(1)
                                    tvInputDebug.text = "Multi-Touch (3 ngón): Vuốt Lên -> Mở Đa nhiệm"
                                } else {
                                    sendGesturePacket(2)
                                    tvInputDebug.text = "Multi-Touch (3 ngón): Vuốt Xuống -> Về Màn hình chính"
                                }
                            } else if (abs(totalDx) > threshold && abs(totalDx) > abs(totalDy)) {
                                hasMovedSignificantly = true
                                threeFingerSwipeTriggered = true
                                btHidManager.triggerVibration(25)
                                if (totalDx > 0) {
                                    sendGesturePacket(4)
                                    tvInputDebug.text = "Multi-Touch (3 ngón): Vuốt Phải -> Chuyển cửa sổ"
                                } else {
                                    sendGesturePacket(3)
                                    tvInputDebug.text = "Multi-Touch (3 ngón): Vuốt Trái -> Quay lại / Cửa sổ trước"
                                }
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newIdx = if (pointerIndex == 0) 1 else 0
                        if (newIdx < event.pointerCount) {
                            activePointerId = event.getPointerId(newIdx)
                            lastSingleX = event.getX(newIdx)
                            lastSingleY = event.getY(newIdx)
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    val duration = now - gestureStartTime

                    if (isTapDragging) {
                        isTapDragging = false
                        mouseBtnState = (mouseBtnState.toInt() and 0x01.inv()).toByte()
                        sendMousePacket(mouseBtnState, 0, 0)
                        tvInputDebug.text = "Multi-Touch: Đã thả kéo chuột"
                    } else if (!hasMovedSignificantly && duration < 260L) {
                        when (maxPointersInGesture) {
                            1 -> {
                                lastSingleTapUpTime = now
                                clickMouseButtonMomentarily(0x01)
                                tvInputDebug.text = "Multi-Touch: Chạm 1 ngón -> Click Chuột Trái"
                            }
                            2 -> {
                                btHidManager.triggerVibration(20)
                                clickMouseButtonMomentarily(0x02)
                                tvInputDebug.text = "Multi-Touch: Chạm 2 ngón -> Click Chuột Phải"
                            }
                            3 -> {
                                btHidManager.triggerVibration(25)
                                clickMouseButtonMomentarily(0x04)
                                tvInputDebug.text = "Multi-Touch: Chạm 3 ngón -> Click Chuột Giữa"
                            }
                        }
                    } else {
                        sendMousePacket(mouseBtnState, 0, 0)
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (isTapDragging) {
                        isTapDragging = false
                        mouseBtnState = (mouseBtnState.toInt() and 0x01.inv()).toByte()
                    }
                    sendMousePacket(mouseBtnState, 0, 0)
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
            }
            true
        }
    }

    private fun clickMouseButtonMomentarily(bitMask: Int) {
        val downMask = (mouseBtnState.toInt() or bitMask).toByte()
        sendMousePacket(downMask, 0, 0)
        sendMousePacket(mouseBtnState, 0, 0)
    }

    private fun sendMousePacket(buttons: Byte, dx: Byte, dy: Byte) {
        btRfcomm.sendMouse(buttons, dx, dy)
        btHidManager.sendMouse(buttons, dx, dy, 0)
        wifiBridge.sendMouse(buttons, dx, dy)
    }

    private fun sendScrollZoomPacket(wheelY: Byte, wheelX: Byte, zoomSteps: Byte) {
        btRfcomm.sendScrollAndZoom(wheelY, wheelX, zoomSteps)
        btHidManager.sendMouse(mouseBtnState, 0, 0, wheelY)
        wifiBridge.sendScrollAndZoom(wheelY, wheelX, zoomSteps)
    }

    private fun sendGesturePacket(gestureCode: Byte) {
        btRfcomm.sendGesture(gestureCode)
        wifiBridge.sendGesture(gestureCode)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMouseButtons() {
        findViewById<Button>(R.id.btnLeftClick).setOnTouchListener { _, e ->
            handleMouseClick(0x01, e.actionMasked)
        }
        findViewById<Button>(R.id.btnMiddleClick).setOnTouchListener { _, e ->
            handleMouseClick(0x04, e.actionMasked)
        }
        findViewById<Button>(R.id.btnRightClick).setOnTouchListener { _, e ->
            handleMouseClick(0x02, e.actionMasked)
        }
    }

    private fun handleMouseClick(bitMask: Int, action: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                mouseBtnState = (mouseBtnState.toInt() or bitMask).toByte()
                btHidManager.triggerVibration(15)
                sendMousePacket(mouseBtnState, 0, 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                mouseBtnState = (mouseBtnState.toInt() and bitMask.inv()).toByte()
                sendMousePacket(mouseBtnState, 0, 0)
            }
        }
        return true
    }

    // ================= BẮT TÍN HIỆU TAY CẦM & CẬP NHẬT SƠ ĐỒ TAY CẦM TRỰC QUAN =================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = mapKeyCodeToBit(event.keyCode)
        if (bit != -1) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                buttonMask = buttonMask or (1 shl bit)
                lastKeyName = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
                sendAndLogGamepad()
            } else if (event.action == KeyEvent.ACTION_UP) {
                buttonMask = buttonMask and (1 shl bit).inv()
                sendAndLogGamepad()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        val isGamepadOrJoystick = (src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) ||
                (src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD)

        if (isGamepadOrJoystick && event.action == MotionEvent.ACTION_MOVE) {
            leftStickX = normalizeAxis(event.getAxisValue(MotionEvent.AXIS_X))
            leftStickY = normalizeAxis(event.getAxisValue(MotionEvent.AXIS_Y))

            val zVal = event.getAxisValue(MotionEvent.AXIS_Z)
            val rzVal = event.getAxisValue(MotionEvent.AXIS_RZ)
            val rxVal = event.getAxisValue(MotionEvent.AXIS_RX)
            val ryVal = event.getAxisValue(MotionEvent.AXIS_RY)

            rightStickX = normalizeAxis(if (abs(zVal) >= abs(rxVal)) zVal else rxVal)
            rightStickY = normalizeAxis(if (abs(rzVal) >= abs(ryVal)) rzVal else ryVal)

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var dpadBits = 0
            if (hatY < -0.5f) dpadBits = dpadBits or (1 shl 8)
            if (hatY > 0.5f) dpadBits = dpadBits or (1 shl 9)
            if (hatX < -0.5f) dpadBits = dpadBits or (1 shl 10)
            if (hatX > 0.5f) dpadBits = dpadBits or (1 shl 11)
            dpadAxisMask = dpadBits

            l2Float = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            )
            r2Float = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            )
            var trigBits = 0
            if (l2Float > 0.25f) trigBits = trigBits or (1 shl 14)
            if (r2Float > 0.25f) trigBits = trigBits or (1 shl 15)
            triggerAxisMask = trigBits

            sendAndLogGamepad()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun normalizeAxis(value: Float): Byte {
        if (abs(value) < 0.04f) return 0
        return (value * 127f).roundToInt().coerceIn(-127, 127).toByte()
    }

    private fun sendAndLogGamepad() {
        val combinedButtons = buttonMask or dpadAxisMask or triggerAxisMask

        gamepadVisualizer.updateGamepadState(
            combinedButtons,
            leftStickX,
            leftStickY,
            rightStickX,
            rightStickY,
            l2Float,
            r2Float
        )

        val btSent = btRfcomm.sendGamepad(combinedButtons, leftStickX, leftStickY, rightStickX, rightStickY)
        btHidManager.sendGamepad(combinedButtons, leftStickX, leftStickY, rightStickX, rightStickY)
        wifiBridge.sendGamepad(combinedButtons, leftStickX, leftStickY, rightStickX, rightStickY)

        val modeText = if (btSent) "BT Connected" else "Active"
        tvInputDebug.text =
            "Nút: $lastKeyName (0x${combinedButtons.toString(16).uppercase()}) | L($leftStickX,$leftStickY) R($rightStickX,$rightStickY) | $modeText"
    }

    private fun mapKeyCodeToBit(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 0
            KeyEvent.KEYCODE_BUTTON_B -> 1
            KeyEvent.KEYCODE_BUTTON_X -> 2
            KeyEvent.KEYCODE_BUTTON_Y -> 3
            KeyEvent.KEYCODE_BUTTON_L1 -> 4
            KeyEvent.KEYCODE_BUTTON_R1 -> 5
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> 6
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE -> 7
            KeyEvent.KEYCODE_DPAD_UP -> 8
            KeyEvent.KEYCODE_DPAD_DOWN -> 9
            KeyEvent.KEYCODE_DPAD_LEFT -> 10
            KeyEvent.KEYCODE_DPAD_RIGHT -> 11
            KeyEvent.KEYCODE_BUTTON_THUMBL -> 12
            KeyEvent.KEYCODE_BUTTON_THUMBR -> 13
            KeyEvent.KEYCODE_BUTTON_L2 -> 14
            KeyEvent.KEYCODE_BUTTON_R2 -> 15
            else -> -1
        }
    }

    override fun onResume() {
        super.onResume()
        detectConnectedControllers()
        if (hasBluetoothPermissions() && !btRfcomm.isConnected) {
            startBluetoothConnections()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            inputManager.unregisterInputDeviceListener(this)
        } catch (_: Exception) {}
        btRfcomm.stop()
        btHidManager.stop()
        wifiBridge.stop()
    }
}
