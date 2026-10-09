package com.apex.nativeauto.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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

    private val daemonController = DaemonController()
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isExpanded = false

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
            x = 60
            y = 250
        }

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. الدائرة العائمة الصغيرة (Small Floating Bubble)
        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            // خلفية دائرية مظللة
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0284C7"))
                setStroke(3, Color.parseColor("#38BDF8"))
            }
            background = circle

            val size = (58 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        // 2. نافذة التحكم الموسعة (Expanded Panel)
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

        val title = TextView(this).apply {
            text = "Apex Auto Core"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val btnTap = Button(this).apply {
            text = "🎯 تنفيذ نقرة فائقة"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                scope.launch { daemonController.sendTap(540, 960) }
            }
        }

        val btnMinimize = Button(this).apply {
            text = "🔽 تصغير إلى دائرة"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener {
                toggleExpansion(false)
            }
        }

        val btnClose = Button(this).apply {
            text = "❌ إغلاق الخدمة"
            setBackgroundColor(Color.parseColor("#DC2626"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener {
                stopSelf()
            }
        }

        expandedPanel.addView(title)
        expandedPanel.addView(btnTap)
        expandedPanel.addView(btnMinimize)
        expandedPanel.addView(btnClose)

        rootContainer.addView(bubbleView)
        rootContainer.addView(expandedPanel)

        // معالجة السحب (Drag) واللمس السريع (Click)
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

                        // إذا كانت الحركة صغيرة والوقت قصير = نقرة عادية (Click)
                        if (duration < 250 && diffX < 15 && diffY < 15) {
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

    private fun toggleExpansion(expand: Boolean) {
        isExpanded = expand
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
        if (::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
        }
    }
}
