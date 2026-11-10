package com.controller.btbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.max
import kotlin.math.min

class AndroidReceiverAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AndroidReceiverAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: CursorOverlayView? = null

    private var screenW = 1080f
    private var screenH = 2400f
    private var cursorX = 540f
    private var cursorY = 1200f
    private var prevMouseBtns: Byte = 0
    private var prevGamepadMask: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val dm = resources.displayMetrics
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()
        cursorX = screenW * 0.5f
        cursorY = screenH * 0.5f
        showCursorOverlay()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        removeCursorOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        removeCursorOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun showCursorOverlay() {
        mainHandler.post {
            try {
                if (cursorView != null) return@post
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm
                val view = CursorOverlayView(this)
                val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                wm.addView(view, params)
                cursorView = view
            } catch (_: Exception) {
            }
        }
    }

    private fun removeCursorOverlay() {
        mainHandler.post {
            try {
                cursorView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {}
            cursorView = null
        }
    }

    fun onRemoteMousePacket(btns: Byte, dx: Byte, dy: Byte) {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels.toFloat()
        screenH = dm.heightPixels.toFloat()

        cursorX = (cursorX + dx * 2.2f).coerceIn(5f, screenW - 5f)
        cursorY = (cursorY + dy * 2.2f).coerceIn(5f, screenH - 5f)

        val leftNow = (btns.toInt() and 0x01) != 0
        val leftPrev = (prevMouseBtns.toInt() and 0x01) != 0
        val rightNow = (btns.toInt() and 0x02) != 0
        val rightPrev = (prevMouseBtns.toInt() and 0x02) != 0
        val midNow = (btns.toInt() and 0x04) != 0
        val midPrev = (prevMouseBtns.toInt() and 0x04) != 0
        prevMouseBtns = btns

        mainHandler.post {
            if (cursorView == null) showCursorOverlay()
            cursorView?.updatePosition(cursorX, cursorY, leftNow)

            // Chuột trái -> Chạm (Tap) tại vị trí con trỏ
            if (leftNow && !leftPrev) {
                performTap(cursorX, cursorY)
            }
            // Chuột phải -> Nút Back trên Android
            if (rightNow && !rightPrev) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            // Chuột giữa -> Nút Home trên Android
            if (midNow && !midPrev) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    fun onRemoteScrollZoomPacket(wheelY: Byte, wheelX: Byte, zoomSteps: Byte) {
        mainHandler.post {
            if (wheelY.toInt() != 0 || wheelX.toInt() != 0) {
                val endX = (cursorX + wheelX * 28f).coerceIn(20f, screenW - 20f)
                val endY = (cursorY + wheelY * 32f).coerceIn(20f, screenH - 20f)
                performSwipe(cursorX, cursorY, endX, endY, 120L)
            }
        }
    }

    fun onRemoteGesturePacket(gestureCode: Byte) {
        mainHandler.post {
            when (gestureCode.toInt()) {
                1 -> performGlobalAction(GLOBAL_ACTION_RECENTS) // 3 ngón vuốt lên -> Đa nhiệm Android
                2 -> performGlobalAction(GLOBAL_ACTION_HOME)    // 3 ngón vuốt xuống -> Màn hình chính Home
                3 -> performGlobalAction(GLOBAL_ACTION_BACK)    // 3 ngón vuốt trái -> Back
                4 -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) // 3 ngón vuốt phải -> Thanh thông báo
            }
        }
    }

    fun onRemoteGamepadPacket(mask: Int, lx: Byte, ly: Byte, rx: Byte, ry: Byte) {
        // Hỗ trợ dùng Cần Phải để di chuyển con trỏ trên máy Android thứ 2 và nút A để chạm, nút B để Back
        if (kotlin.math.abs(rx.toInt()) > 8 || kotlin.math.abs(ry.toInt()) > 8) {
            onRemoteMousePacket(prevMouseBtns, (rx / 6).toByte(), (ry / 6).toByte())
        }
        val diff = mask xor prevGamepadMask
        if (diff != 0) {
            if ((diff and 1) != 0 && (mask and 1) != 0) {
                mainHandler.post { performTap(cursorX, cursorY) } // Nút A -> Tap
            }
            if ((diff and 2) != 0 && (mask and 2) != 0) {
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) } // Nút B -> Back
            }
            if ((diff and (1 shl 7)) != 0 && (mask and (1 shl 7)) != 0) {
                mainHandler.post { performGlobalAction(GLOBAL_ACTION_HOME) } // Start -> Home
            }
            prevGamepadMask = mask
        }
    }

    private fun performTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }
    }

    private class CursorOverlayView(context: Context) : View(context) {
        private var cx = 500f
        private var cy = 500f
        private var isClicking = false

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00E676.toInt()
            style = Paint.Style.FILL
        }

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private val pointerPath = Path()

        fun updatePosition(x: Float, y: Float, clicking: Boolean) {
            cx = x
            cy = y
            isClicking = clicking
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            pointerPath.reset()
            pointerPath.moveTo(cx, cy)
            pointerPath.lineTo(cx, cy + 42f)
            pointerPath.lineTo(cx + 12f, cy + 32f)
            pointerPath.lineTo(cx + 22f, cy + 50f)
            pointerPath.lineTo(cx + 29f, cy + 46f)
            pointerPath.lineTo(cx + 19f, cy + 28f)
            pointerPath.lineTo(cx + 32f, cy + 28f)
            pointerPath.close()

            fillPaint.color = if (isClicking) 0xFFFF5252.toInt() else 0xFF00E676.toInt()
            canvas.drawPath(pointerPath, fillPaint)
            canvas.drawPath(pointerPath, strokePaint)
        }
    }
}
