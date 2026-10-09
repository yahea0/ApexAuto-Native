package com.apex.nativeauto.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.ipc.DaemonController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var bubbleView: TextView
    private lateinit var expandedPanel: LinearLayout
    private lateinit var targetPointer: TextView

    private val daemonController = DaemonController()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var targetX = 540f
    private var targetY = 960f
    private var isLooping = false
    private var clickIntervalMs = 500L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 300
        }

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. الأيقونة الدائرية العائمة (Bubble)
        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0284C7"))
                setStroke(3, Color.parseColor("#38BDF8"))
            }
            background = circle
            val size = (56 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        // 2. لوحة تحكم الأتمتة الموسعة
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 20, 24, 20)

            val panelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#EE0F172A"))
                setStroke(2, Color.parseColor("#334155"))
            }
            background = panelBg
        }

        val txtStatus = TextView(this).apply {
            text = "لوحة الأتمتة: Target ($targetX, $targetY)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }

        // زر النقر التجريبي
        val btnSingleTap = Button(this).apply {
            text = "🎯 نقرة تجريبية واحدة"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                executeTap(targetX, targetY)
            }
        }

        // خانة تعديل سرعة التكرار
        val editInterval = EditText(this).apply {
            hint = "الفارق الزمني بالملي ثانية (مثلاً 500)"
            setText("500")
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 16, 12)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        // زر تشغيل وإيقاف الأتمتة التلقائية المتواصلة
        val btnAutoLoop = Button(this).apply {
            text = "▶ بدء الأتمتة التلقائية"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!isLooping) {
                    val ms = editInterval.text.toString().toLongOrNull() ?: 500L
                    clickIntervalMs = if (ms < 50) 50 else ms
                    startAutomationLoop()
                    text = "⏸ إيقاف الأتمتة"
                    setBackgroundColor(Color.parseColor("#EF4444"))
                } else {
                    stopAutomationLoop()
                    text = "▶ بدء الأتمتة التلقائية"
                    setBackgroundColor(Color.parseColor("#10B981"))
                }
            }
        }

        val btnMinimize = Button(this).apply {
            text = "🔽 تصغير إلى دائرة"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { toggleExpansion(false) }
        }

        expandedPanel.addView(txtStatus)
        expandedPanel.addView(btnSingleTap)
        expandedPanel.addView(editInterval)
        expandedPanel.addView(btnAutoLoop)
        expandedPanel.addView(btnMinimize)

        rootContainer.addView(bubbleView)
        rootContainer.addView(expandedPanel)

        // تحريك الدائرة بالسحب أو فتح اللوحة بالنقر
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var touchStartTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        touchStartTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(rootContainer, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - touchStartTime
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (duration < 250 && diffX < 20 && diffY < 20) {
                            toggleExpansion(true)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootContainer, params)
    }

    private fun executeTap(x: Float, y: Float) {
        val acc = ApexAccessibilityService.instance
        if (acc != null) {
            acc.performClick(x, y)
        } else {
            // محاولة بديلة عبر C++ Daemon
            scope.launch {
                val ok = daemonController.sendTap(x.toInt(), y.toInt())
                if (!ok) {
                    Toast.makeText(applicationContext, "يرجى تفعيل خدمة إمكانية الوصول للتطبيق أولاً!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startAutomationLoop() {
        isLooping = true
        val runnable = object : Runnable {
            override fun run() {
                if (isLooping) {
                    executeTap(targetX, targetY)
                    handler.postDelayed(this, clickIntervalMs)
                }
            }
        }
        handler.post(runnable)
    }

    private fun stopAutomationLoop() {
        isLooping = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun toggleExpansion(expand: Boolean) {
        if (expand) {
            bubbleView.visibility = View.GONE
            expandedPanel.visibility = View.VISIBLE
        } else {
            expandedPanel.visibility = View.GONE
            bubbleView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutomationLoop()
        if (::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
        }
    }
}
