package com.apex.nativeauto.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.max

class CropSelectorView(
    context: Context,
    private val onConfirm: (RectF, Bitmap) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    var cropRect = RectF(220f, 600f, 620f, 1000f)
    private var activeHandle = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val handleRadius = 26f

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#00F0FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#2500F0FF")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#4400F0FF")
        strokeWidth = 1.5f
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val drawView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(cropRect, fillPaint)
            canvas.drawRect(cropRect, strokePaint)

            // رسم خطوط التوجيه الشبكية داخل الإطار
            val thirdW = cropRect.width() / 3
            val thirdH = cropRect.height() / 3
            canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left + thirdW * 2, cropRect.top, cropRect.left + thirdW * 2, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2, cropRect.right, cropRect.top + thirdH * 2, gridPaint)

            // مقابض التحريك والتصغير
            canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius, handlePaint)
            canvas.drawCircle(cropRect.left, cropRect.top, handleRadius / 1.5f, handlePaint)
        }
    }

    init {
        addView(drawView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupFloatingToolbar()
    }

    private fun setupFloatingToolbar() {
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 10, 16, 10)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 30f
                setColor(Color.parseColor("#E60F172A"))
                setStroke(2, Color.parseColor("#00F0FF"))
            }
            background = bg
        }

        val btnConfirm = Button(context).apply {
            text = "✓ تأكيد"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                val preview = createThumbnailFromCrop(cropRect)
                onConfirm(cropRect, preview)
            }
        }

        val btnCopy = Button(context).apply {
            text = "📋 نسخ"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#0284C7"))
            setOnClickListener {
                val text = "X: ${cropRect.centerX().toInt()}, Y: ${cropRect.centerY().toInt()}, W: ${cropRect.width().toInt()}, H: ${cropRect.height().toInt()}"
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", text))
                Toast.makeText(context, "تم نسخ الإحداثيات!", Toast.LENGTH_SHORT).show()
            }
        }

        val btnCancel = Button(context).apply {
            text = "✕ إلغاء"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#EF4444"))
            setOnClickListener { onCancel() }
        }

        toolbar.addView(btnConfirm)
        toolbar.addView(btnCopy)
        toolbar.addView(btnCancel)

        val tParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 140
        }
        addView(toolbar, tParams)
    }

    private fun createThumbnailFromCrop(rect: RectF): Bitmap {
        val w = max(1, rect.width().toInt())
        val h = max(1, rect.height().toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#151E32"))
        val p = Paint().apply {
            color = Color.parseColor("#00F0FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return bmp
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                val dist = Math.hypot((x - cropRect.right).toDouble(), (y - cropRect.bottom).toDouble())
                if (dist < handleRadius * 2) {
                    activeHandle = 2 // Resize Handle
                } else if (cropRect.contains(x, y)) {
                    activeHandle = 1 // Body Move
                } else {
                    activeHandle = 0
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY

                if (activeHandle == 1) {
                    cropRect.offset(dx, dy)
                    drawView.invalidate()
                } else if (activeHandle == 2) {
                    cropRect.right = max(cropRect.left + 80f, cropRect.right + dx)
                    cropRect.bottom = max(cropRect.top + 80f, cropRect.bottom + dy)
                    drawView.invalidate()
                }

                lastTouchX = x
                lastTouchY = y
                return true
            }

            MotionEvent.ACTION_UP -> {
                activeHandle = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
