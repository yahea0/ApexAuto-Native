package com.apex.nativeauto.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.macro.ActionType
import com.apex.nativeauto.macro.MacroStep
import com.apex.nativeauto.macro.ScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var bubbleView: TextView
    private lateinit var suitePanel: LinearLayout
    private var cropOverlayView: CropSelectorView? = null

    private val stepsList = mutableListOf<MacroStep>()
    private val scriptRunner = ScriptRunner()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default)

    private var isRunningLoop = false
    private var currentEditingStepIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // تهيئة خطوتين افتراضيتين
        stepsList.add(MacroStep(1, "نقرة الهدف الأساسي", ActionType.TAP, RectF(540f, 960f, 540f, 960f)))
        stepsList.add(MacroStep(2, "فحص شرطي JavaScript", ActionType.JS_SCRIPT))

        buildOverlayUI()
    }

    private fun getOverlayLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 260
        }
    }

    private fun buildOverlayUI() {
        val params = getOverlayLayoutParams()
        rootContainer = FrameLayout(this)

        // 1. الأيقونة الدائرية العائمة (Floating Neon Bubble)
        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
                setStroke(4, Color.parseColor("#00F0FF"))
            }
            background = circle
            val size = (56 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        // 2. الجناح الموسع المتقدم (Suite Panel)
        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 20, 24, 20)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(Color.parseColor("#E60B0F19"))
                setStroke(2, Color.parseColor("#1E293B"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams(
                (320 * resources.displayMetrics.density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        renderSuiteContent()

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)

        // سحب الأيقونة أو فتح اللوحة بالنقر
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(rootContainer, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (System.currentTimeMillis() - startTime < 250 &&
                            abs(event.rawX - initialTouchX) < 20 &&
                            abs(event.rawY - initialTouchY) < 20
                        ) {
                            togglePanel(true)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootContainer, params)
    }

    private fun renderSuiteContent() {
        suitePanel.removeAllViews()

        // شريط العنوان
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Apex Macro Studio"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 18f
            setPadding(12, 4, 12, 4)
            setOnClickListener { togglePanel(false) }
        }
        header.addView(title)
        header.addView(btnClose)
        suitePanel.addView(header)

        // قائمة الخطوات المترقمة
        val stepsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        stepsList.forEachIndexed { index, step ->
            val stepRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12, 10, 12, 10)
                val rowBg = GradientDrawable().apply {
                    cornerRadius = 14f
                    setColor(Color.parseColor("#151E32"))
                }
                background = rowBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
            }

            val badge = TextView(this).apply {
                text = "${step.stepNumber}"
                setTextColor(Color.parseColor("#0B0F19"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val bCircle = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#00F0FF"))
                }
                background = bCircle
                val s = (24 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            }

            val name = TextView(this).apply {
                text = " ${step.name}"
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnEdit = Button(this).apply {
                text = "تعديل"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#334155"))
                setTextColor(Color.parseColor("#38BDF8"))
                setOnClickListener {
                    openStepEditor(index)
                }
            }

            stepRow.addView(badge)
            stepRow.addView(name)
            stepRow.addView(btnEdit)
            stepsContainer.addView(stepRow)
        }
        suitePanel.addView(stepsContainer)

        // أزرار العمليات والتحكم
        val btnAddImageStep = Button(this).apply {
            text = "➕ إضافة فحص صورة (إطار مطاطي)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 12f
            setOnClickListener {
                showRubberBandSelector()
            }
        }

        val btnToggleRun = Button(this).apply {
            text = if (isRunningLoop) "⏹ إيقاف الأتمتة" else "▶ تشغيل الأتمتة المتسلسلة"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(if (isRunningLoop) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
            setOnClickListener {
                if (isRunningLoop) stopExecution() else startExecution()
                renderSuiteContent()
            }
        }

        suitePanel.addView(btnAddImageStep)
        suitePanel.addView(btnToggleRun)
    }

    // إظهار الإطار المطاطي لاختيار الهدف
    private fun showRubberBandSelector() {
        togglePanel(false)
        if (cropOverlayView != null) return

        val cropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val cropView = CropSelectorView(this) { selectedRect ->
            // عند انتهاء المستخدم من تحريك وتكبير الإطار:
            val nextIndex = stepsList.size + 1
            stepsList.add(
                MacroStep(
                    nextIndex,
                    "هدف صورة ($nextIndex)",
                    ActionType.IMAGE_TARGET,
                    RectF(selectedRect)
                )
            )
            Toast.makeText(this, "تم حفظ الإطار للخطوة $nextIndex بنجاح!", Toast.LENGTH_SHORT).show()
            removeRubberBandSelector()
            togglePanel(true)
        }

        cropOverlayView = cropView
        windowManager.addView(cropView, cropParams)
        Toast.makeText(this, "اسحب الإطار أو كبّره من الزاوية لتحديد الهدف!", Toast.LENGTH_LONG).show()
    }

    private fun removeRubberBandSelector() {
        cropOverlayView?.let {
            windowManager.removeView(it)
            cropOverlayView = null
        }
    }

    private fun openStepEditor(index: Int) {
        currentEditingStepIndex = index
        val step = stepsList[index]

        suitePanel.removeAllViews()

        val title = TextView(this).apply {
            text = "تعديل الخطوة: ${step.stepNumber}"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 10)
        }
        suitePanel.addView(title)

        val editCode = EditText(this).apply {
            setText(step.scriptCode)
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(16, 16, 16, 16)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (140 * resources.displayMetrics.density).toInt()
            )
        }
        suitePanel.addView(editCode)

        val btnSave = Button(this).apply {
            text = "💾 حفظ الكود والعودة"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            setOnClickListener {
                step.scriptCode = editCode.text.toString()
                renderSuiteContent()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun startExecution() {
        isRunningLoop = true
        scope.launch {
            while (isRunningLoop) {
                for (step in stepsList) {
                    if (!isRunningLoop) break
                    if (!step.enabled) continue

                    when (step.type) {
                        ActionType.TAP -> {
                            val centerX = step.targetArea.centerX()
                            val centerY = step.targetArea.centerY()
                            ApexAccessibilityService.instance?.performClick(centerX, centerY)
                        }
                        ActionType.IMAGE_TARGET -> {
                            val centerX = step.targetArea.centerX()
                            val centerY = step.targetArea.centerY()
                            ApexAccessibilityService.instance?.performClick(centerX, centerY)
                        }
                        ActionType.JS_SCRIPT -> {
                            scriptRunner.execute(
                                step.scriptCode,
                                step.stepNumber,
                                step.targetArea.centerX(),
                                step.targetArea.centerY()
                            )
                        }
                    }
                    Thread.sleep(step.delayAfterMs)
                }
            }
        }
    }

    private fun stopExecution() {
        isRunningLoop = false
    }

    private fun togglePanel(expand: Boolean) {
        if (expand) {
            bubbleView.visibility = View.GONE
            suitePanel.visibility = View.VISIBLE
            renderSuiteContent()
        } else {
            suitePanel.visibility = View.GONE
            bubbleView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopExecution()
        removeRubberBandSelector()
        if (::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
        }
    }
}
