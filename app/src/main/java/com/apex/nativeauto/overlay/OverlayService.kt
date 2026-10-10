package com.apex.nativeauto.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.macro.*
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
    private val scope = CoroutineScope(Dispatchers.Default)

    private var isRunningLoop = false
    private var isPanelExpanded = false
    private var isAttached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        stepsList.add(MacroStep(1, "نقرة الزر الرئيسي", ActionType.TAP, RectF(540f, 960f, 540f, 960f)))
        stepsList.add(MacroStep(2, "فحص شرطي مخصص", ActionType.JS_SCRIPT))

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

    private fun setKeyboardFocusable(focusable: Boolean) {
        if (!isAttached || !::rootContainer.isInitialized) return
        try {
            val params = rootContainer.layoutParams as WindowManager.LayoutParams
            if (focusable) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager.updateViewLayout(rootContainer, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildOverlayUI() {
        val params = getOverlayLayoutParams()
        rootContainer = FrameLayout(this)
        val density = resources.displayMetrics.density
        val bubbleSize = (44 * density).toInt()

        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)

            val circleBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0B0F19"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#00F0FF"))
            }
            background = circleBg
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
        }

        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())

            val panelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F2080D1A"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#00F0FF"))
            }
            background = panelBg
            layoutParams = FrameLayout.LayoutParams((320 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)

        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var downTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        downTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        if (isAttached) {
                            windowManager.updateViewLayout(rootContainer, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (System.currentTimeMillis() - downTime < 220 &&
                            abs(event.rawX - touchX) < 15 && abs(event.rawY - touchY) < 15
                        ) {
                            togglePanelExpansion(true)
                        }
                        return true
                    }
                }
                return false
            }
        })

        // تثبيت النافذة في النظام أولاً لمنع الانهيار
        windowManager.addView(rootContainer, params)
        isAttached = true

        renderMainSuiteView()
    }

    private fun renderMainSuiteView() {
        setKeyboardFocusable(false)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ APEX MACRO STUDIO"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 18f
            setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener { togglePanelExpansion(false) }
        }
        header.addView(title)
        header.addView(btnClose)
        suitePanel.addView(header)

        // حاوية التمرير الثابتة لـ 5 خطوات
        val maxListHeight = (230 * density).toInt()
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxListHeight
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            isVerticalScrollBarEnabled = true
        }

        val stepsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        stepsList.forEachIndexed { index, step ->
            val stepRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())

                val rowBg = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(Color.parseColor("#131B2E"))
                    setStroke(1, Color.parseColor("#1E293B"))
                }
                background = rowBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (5 * density).toInt() }
            }

            val badge = TextView(this).apply {
                text = "${step.stepNumber}"
                setTextColor(Color.parseColor("#0B0F19"))
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val bBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#00F0FF"))
                }
                background = bBg
                val s = (20 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            stepRow.addView(badge)

            if (step.thumbnail != null) {
                val thumbView = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val s = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginStart = (4 * density).toInt()
                    }
                }
                stepRow.addView(thumbView)
            }

            val nameView = TextView(this).apply {
                text = " ${step.name}"
                setTextColor(Color.WHITE)
                textSize = 11f
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (4 * density).toInt()
                }
            }
            stepRow.addView(nameView)

            // زر اختبار الخطوة الفردية
            val btnTest = TextView(this).apply {
                text = "▶"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#10B981"))
                val tBg = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#064E3B"))
                }
                background = tBg
                val btnW = (24 * density).toInt()
                val btnH = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply {
                    marginEnd = (3 * density).toInt()
                }
                setOnClickListener { executeSingleStep(step) }
            }
            stepRow.addView(btnTest)

            // زر التعديل
            val btnEdit = TextView(this).apply {
                text = "✏"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#38BDF8"))
                val eBg = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#0C4A6E"))
                }
                background = eBg
                val btnW = (24 * density).toInt()
                val btnH = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply {
                    marginEnd = (3 * density).toInt()
                }
                setOnClickListener { openStepEditor(index) }
            }
            stepRow.addView(btnEdit)

            // زر الحذف
            val btnDelete = TextView(this).apply {
                text = "🗑"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#EF4444"))
                val dBg = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#450A0A"))
                }
                background = dBg
                val btnW = (24 * density).toInt()
                val btnH = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH)
                setOnClickListener { deleteStep(index) }
            }
            stepRow.addView(btnDelete)

            stepsContainer.addView(stepRow)
        }

        scrollView.addView(stepsContainer)
        suitePanel.addView(scrollView)

        val btnAddImage = Button(this).apply {
            text = "➕ التقاط صورة (إطار مطاطي)"
            setTextColor(Color.WHITE)
            textSize = 11f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1E293B"))
            }
            background = bg
            setOnClickListener { showRubberBandSelector() }
        }

        val btnToggleLoop = Button(this).apply {
            text = if (isRunningLoop) "⏹ إيقاف الأتمتة" else "▶ تشغيل الأتمتة الشاملة"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(if (isRunningLoop) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            setOnClickListener {
                if (isRunningLoop) stopExecution() else startExecution()
                renderMainSuiteView()
            }
        }

        suitePanel.addView(btnAddImage)
        suitePanel.addView(btnToggleLoop)
    }

    private fun openStepEditor(index: Int) {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density
        val step = stepsList[index]

        val header = TextView(this).apply {
            text = "تعديل الخطوة [${step.stepNumber}]"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        suitePanel.addView(header)

        val editName = EditText(this).apply {
            setText(step.name)
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        suitePanel.addView(editName)

        val editCode = EditText(this).apply {
            setText(step.scriptCode)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (110 * density).toInt()
            ).apply { topMargin = (6 * density).toInt() }
            addTextChangedListener(CodeSyntaxHighlighter())
        }
        suitePanel.addView(editCode)

        val btnTestThis = Button(this).apply {
            text = "⚡ اختبار الخطوة الآن"
            setTextColor(Color.WHITE)
            textSize = 11f
            val bg = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#8B5CF6"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            setOnClickListener {
                step.name = editName.text.toString()
                step.scriptCode = editCode.text.toString()
                executeSingleStep(step)
            }
        }
        suitePanel.addView(btnTestThis)

        val btnSave = Button(this).apply {
            text = "💾 حفظ والعودة"
            setTextColor(Color.WHITE)
            textSize = 11f
            val bg = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#0284C7"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            setOnClickListener {
                step.name = editName.text.toString()
                step.scriptCode = editCode.text.toString()

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
                renderMainSuiteView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun executeSingleStep(step: MacroStep) {
        when (step.type) {
            ActionType.TAP, ActionType.IMAGE_TARGET -> {
                val cx = step.targetArea.centerX()
                val cy = step.targetArea.centerY()
                val ok = ApexAccessibilityService.instance?.performClick(cx, cy) ?: false
                Toast.makeText(
                    this,
                    if (ok) "✓ نقر الهدف (${cx.toInt()}, ${cy.toInt()})" else "⚠ خدمة الوصول غير مفعلة!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ActionType.JS_SCRIPT -> {
                val ok = scriptRunner.execute(step.scriptCode, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
                Toast.makeText(
                    this,
                    if (ok) "✓ نجح تنفيذ كود الخطوة [${step.stepNumber}]" else "⚠ حدث خطأ في الكود",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteStep(index: Int) {
        if (index in 0 until stepsList.size) {
            stepsList.removeAt(index)
            stepsList.forEachIndexed { i, s ->
                stepsList[i] = s.copy(stepNumber = i + 1)
            }
            renderMainSuiteView()
        }
    }

    private fun showRubberBandSelector() {
        togglePanelExpansion(false)
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

        val cropView = CropSelectorView(
            this,
            onConfirm = { selectedRect, thumbnail ->
                val nextNum = stepsList.size + 1
                val targetId = "target_$nextNum"

                ImageLibrary.saveTarget(targetId, thumbnail)

                val newStep = MacroStep(
                    stepNumber = nextNum,
                    name = "هدف صورة ($nextNum)",
                    type = ActionType.IMAGE_TARGET,
                    targetArea = RectF(selectedRect),
                    targetName = targetId,
                    thumbnail = thumbnail
                )
                stepsList.add(newStep)

                removeRubberBandSelector()
                togglePanelExpansion(true)
            },
            onCancel = {
                removeRubberBandSelector()
                togglePanelExpansion(true)
            }
        )

        cropOverlayView = cropView
        windowManager.addView(cropView, cropParams)
    }

    private fun removeRubberBandSelector() {
        cropOverlayView?.let {
            windowManager.removeView(it)
            cropOverlayView = null
        }
    }

    private fun startExecution() {
        isRunningLoop = true
        scope.launch {
            while (isRunningLoop) {
                for (step in stepsList) {
                    if (!isRunningLoop) break
                    if (!step.enabled) continue

                    when (step.type) {
                        ActionType.TAP, ActionType.IMAGE_TARGET -> {
                            val cx = step.targetArea.centerX()
                            val cy = step.targetArea.centerY()
                            ApexAccessibilityService.instance?.performClick(cx, cy)
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

    private fun togglePanelExpansion(expand: Boolean) {
        isPanelExpanded = expand
        if (expand) {
            bubbleView.visibility = View.GONE
            suitePanel.visibility = View.VISIBLE
            renderMainSuiteView()
        } else {
            setKeyboardFocusable(false)
            suitePanel.visibility = View.GONE
            bubbleView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopExecution()
        removeRubberBandSelector()
        if (isAttached && ::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
            isAttached = false
        }
    }
}
