package com.apex.nativeauto.overlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class CropSelectorView(context: Context, private val onAreaConfirmed: (RectF) -> Unit) : View(context) {

    var cropRect = RectF(200f, 500f, 600f, 900f)
    private val strokePaint = Paint().apply {
        color = Color.parseColor("#00F0FF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#3300F0FF")
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handleRadius = 24f
    private var activeHandle = -1 // 0: None, 1: Body drag, 2: Bottom-Right Resize
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // رسم المساحة المحددة
        canvas.drawRect(cropRect, fillPaint)
        canvas.drawRect(cropRect, strokePaint)

        // رسم مقبض التحكم في الزاوية السفلية اليمنى
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius / 1.5f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y

                // فحص إذا كان اللمس على مقبض التكبير/التصغير
                val distToBR = Math.hypot((x - cropRect.right).toDouble(), (y - cropRect.bottom).toDouble())
                if (distToBR < handleRadius * 2) {
                    activeHandle = 2 // Resize
                } else if (cropRect.contains(x, y)) {
                    activeHandle = 1 // Drag Body
                } else {
                    activeHandle = 0
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY

                if (activeHandle == 1) { // تحريك الإطار بالكامل
                    cropRect.offset(dx, dy)
                    invalidate()
                } else if (activeHandle == 2) { // تغيير حجم الإطار المطاطي
                    cropRect.right = max(cropRect.left + 80f, cropRect.right + dx)
                    cropRect.bottom = max(cropRect.top + 80f, cropRect.bottom + dy)
                    invalidate()
                }

                lastTouchX = x
                lastTouchY = y
                return true
            }

            MotionEvent.ACTION_UP -> {
                activeHandle = 0
                onAreaConfirmed(cropRect)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
