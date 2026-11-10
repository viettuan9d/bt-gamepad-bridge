package com.controller.btbridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GamepadVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var buttonMask: Int = 0
    private var lx: Float = 0f // -1.0f .. 1.0f
    private var ly: Float = 0f
    private var rx: Float = 0f
    private var ry: Float = 0f
    private var l2Val: Float = 0f // 0.0f .. 1.0f
    private var r2Val: Float = 0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1D28.toInt()
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E4560.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF282C3E.toInt()
        style = Paint.Style.FILL
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.FILL
    }

    private val stickKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0E0E0.toInt()
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val bodyRect = RectF()
    private val tempRect = RectF()

    fun updateGamepadState(
        mask: Int,
        leftX: Byte,
        leftY: Byte,
        rightX: Byte,
        rightY: Byte,
        l2: Float = if ((mask and (1 shl 14)) != 0) 1f else 0f,
        r2: Float = if ((mask and (1 shl 15)) != 0) 1f else 0f
    ) {
        this.buttonMask = mask
        this.lx = (leftX / 127f).coerceIn(-1f, 1f)
        this.ly = (leftY / 127f).coerceIn(-1f, 1f)
        this.rx = (rightX / 127f).coerceIn(-1f, 1f)
        this.ry = (rightY / 127f).coerceIn(-1f, 1f)
        this.l2Val = l2.coerceIn(0f, 1f)
        this.r2Val = r2.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    private fun isPressed(bit: Int): Boolean = (buttonMask and (1 shl bit)) != 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.54f
        val padW = minOf(w * 0.88f, h * 1.55f)
        val padH = padW * 0.56f

        // 1. Vẽ thân tay cầm (Controller Body Silhouette)
        bodyRect.set(cx - padW * 0.46f, cy - padH * 0.38f, cx + padW * 0.46f, cy + padH * 0.42f)
        canvas.drawRoundRect(bodyRect, padH * 0.28f, padH * 0.28f, bodyPaint)
        canvas.drawRoundRect(bodyRect, padH * 0.28f, padH * 0.28f, outlinePaint)

        // 2. Vẽ cụm cò L2 / R2 (Triggers phía trên) & L1 / R1 (Bumpers)
        val trigW = padW * 0.13f
        val trigH = padH * 0.16f
        val lTrigX = cx - padW * 0.28f
        val rTrigX = cx + padW * 0.28f
        val trigY = cy - padH * 0.65f

        // L2 Trigger
        tempRect.set(lTrigX - trigW / 2, trigY - trigH / 2, lTrigX + trigW / 2, trigY + trigH / 2)
        canvas.drawRoundRect(tempRect, 12f, 12f, if (isPressed(14) || l2Val > 0.1f) activePaint else inactivePaint)
        canvas.drawRoundRect(tempRect, 12f, 12f, outlinePaint)
        canvas.drawText("L2", lTrigX, trigY + 8f, textPaint)

        // R2 Trigger
        tempRect.set(rTrigX - trigW / 2, trigY - trigH / 2, rTrigX + trigW / 2, trigY + trigH / 2)
        canvas.drawRoundRect(tempRect, 12f, 12f, if (isPressed(15) || r2Val > 0.1f) activePaint else inactivePaint)
        canvas.drawRoundRect(tempRect, 12f, 12f, outlinePaint)
        canvas.drawText("R2", rTrigX, trigY + 8f, textPaint)

        // L1 / R1 Bumpers
        val bumpW = padW * 0.16f
        val bumpH = padH * 0.10f
        val bumpY = cy - padH * 0.45f

        tempRect.set(lTrigX - bumpW / 2, bumpY - bumpH / 2, lTrigX + bumpW / 2, bumpY + bumpH / 2)
        canvas.drawRoundRect(tempRect, 10f, 10f, if (isPressed(4)) activePaint else inactivePaint)
        canvas.drawRoundRect(tempRect, 10f, 10f, outlinePaint)
        canvas.drawText("L1", lTrigX, bumpY + 8f, textPaint)

        tempRect.set(rTrigX - bumpW / 2, bumpY - bumpH / 2, rTrigX + bumpW / 2, bumpY + bumpH / 2)
        canvas.drawRoundRect(tempRect, 10f, 10f, if (isPressed(5)) activePaint else inactivePaint)
        canvas.drawRoundRect(tempRect, 10f, 10f, outlinePaint)
        canvas.drawText("R1", rTrigX, bumpY + 8f, textPaint)

        // 3. Vẽ Cần Analog Trái (Left Stick + L3)
        val stickRadius = padH * 0.18f
        val lStickCx = cx - padW * 0.27f
        val lStickCy = cy - padH * 0.10f
        canvas.drawCircle(lStickCx, lStickCy, stickRadius, inactivePaint)
        canvas.drawCircle(lStickCx, lStickCy, stickRadius, outlinePaint)
        val lKnobX = lStickCx + lx * (stickRadius * 0.55f)
        val lKnobY = lStickCy + ly * (stickRadius * 0.55f)
        canvas.drawCircle(lKnobX, lKnobY, stickRadius * 0.48f, if (isPressed(12)) activePaint else stickKnobPaint)

        // 4. Vẽ Cần Analog Phải (Right Stick + R3)
        val rStickCx = cx + padW * 0.14f
        val rStickCy = cy + padH * 0.17f
        canvas.drawCircle(rStickCx, rStickCy, stickRadius, inactivePaint)
        canvas.drawCircle(rStickCx, rStickCy, stickRadius, outlinePaint)
        val rKnobX = rStickCx + rx * (stickRadius * 0.55f)
        val rKnobY = rStickCy + ry * (stickRadius * 0.55f)
        canvas.drawCircle(rKnobX, rKnobY, stickRadius * 0.48f, if (isPressed(13)) activePaint else stickKnobPaint)

        // 5. Vẽ cụm phím điều hướng D-Pad (Up=8, Down=9, Left=10, Right=11)
        val dpadCx = cx - padW * 0.14f
        val dpadCy = cy + padH * 0.17f
        val dpadDist = padH * 0.11f
        val dpadBtnR = padH * 0.055f

        drawCircleButton(canvas, dpadCx, dpadCy - dpadDist, dpadBtnR, isPressed(8), "▲")
        drawCircleButton(canvas, dpadCx, dpadCy + dpadDist, dpadBtnR, isPressed(9), "▼")
        drawCircleButton(canvas, dpadCx - dpadDist, dpadCy, dpadBtnR, isPressed(10), "◀")
        drawCircleButton(canvas, dpadCx + dpadDist, dpadCy, dpadBtnR, isPressed(11), "▶")

        // 6. Vẽ cụm nút A / B / X / Y (A=0, B=1, X=2, Y=3)
        val abxyCx = cx + padW * 0.27f
        val abxyCy = cy - padH * 0.10f
        val abxyDist = padH * 0.12f
        val abxyBtnR = padH * 0.062f

        drawCircleButton(canvas, abxyCx, abxyCy - abxyDist, abxyBtnR, isPressed(3), "Y")
        drawCircleButton(canvas, abxyCx, abxyCy + abxyDist, abxyBtnR, isPressed(0), "A")
        drawCircleButton(canvas, abxyCx - abxyDist, abxyCy, abxyBtnR, isPressed(2), "X")
        drawCircleButton(canvas, abxyCx + abxyDist, abxyCy, abxyBtnR, isPressed(1), "B")

        // 7. Vẽ nút Select (6) & Start (7) ở giữa
        val midBtnR = padH * 0.045f
        drawCircleButton(canvas, cx - padW * 0.07f, cy - padH * 0.12f, midBtnR, isPressed(6), "SEL")
        drawCircleButton(canvas, cx + padW * 0.07f, cy - padH * 0.12f, midBtnR, isPressed(7), "STA")
    }

    private fun drawCircleButton(canvas: Canvas, x: Float, y: Float, r: Float, active: Boolean, label: String) {
        canvas.drawCircle(x, y, r, if (active) activePaint else inactivePaint)
        canvas.drawCircle(x, y, r, outlinePaint)
        val oldSize = textPaint.textSize
        textPaint.textSize = r * 0.85f
        canvas.drawText(label, x, y + (r * 0.3f), textPaint)
        textPaint.textSize = oldSize
    }
}
