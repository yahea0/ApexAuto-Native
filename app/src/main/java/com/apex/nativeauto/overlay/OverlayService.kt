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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // إضافة خطوات افتراضية
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
            x = 50
            y = 220
        }
    }

    // تفعيل أو تعطيل تركيز الكيبورد ديناميكياً لحل مشكلة الكتابة في المحرر
    private fun setKeyboardFocusable(focusable: Boolean) {
        val params = rootContainer.layoutParams as WindowManager.LayoutParams
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(rootContainer, params)
    }

    private fun buildOverlayUI() {
        val params = getOverlayLayoutParams()
        rootContainer = FrameLayout(this)

        // 1. الأيقونة المصغرة العائمة الفخمة (Mini Cyber Bubble - 44dp)
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

        // 2. الجناح الموسع المتقدم
        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())

            val panelBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F2080D1A")) // زجاجي داكن فخم
                setStroke((1.5f * density).toInt(), Color.parseColor("#00F0FF"))
            }
            background = panelBg
            layoutParams = FrameLayout.LayoutParams((330 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        renderMainSuiteView()

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)

        // حركة السحب اللطيفة للأيقونة مع تمييز النقر
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
                        windowManager.updateViewLayout(rootContainer, params)
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

        windowManager.addView(rootContainer, params)
    }

    private fun renderMainSuiteView() {
        setKeyboardFocusable(false)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        // الشريط العلوي (العنوان وزر الإغلاق)
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

        // حاوية التمرير الثابتة (ارتفاع محدد لـ 5 خطوات فقط مع التمرير لمئات الخطوات)
        val maxListHeight = (245 * density).toInt()
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxListHeight
            ).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            isVerticalScrollBarEnabled = true
        }

        val stepsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // بناء صفوف الخطوات
        stepsList.forEachIndexed { index, step ->
            val stepRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())

                val rowBg = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#131B2E"))
                    setStroke(1, Color.parseColor("#1E293B"))
                }
                background = rowBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            // رقم الخطوة
            val badge = TextView(this).apply {
                text = "${step.stepNumber}"
                setTextColor(Color.parseColor("#0B0F19"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val bBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#00F0FF"))
                }
                background = bBg
                val s = (22 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
            stepRow.addView(badge)

            // صورة الهدف المصغرة Thumbnail (إذا كانت خطوة صورة)
            if (step.thumbnail != null) {
                val thumbView = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val s = (26 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        marginStart = (6 * density).toInt()
                    }
                }
                stepRow.addView(thumbView)
            }

            // اسم الخطوة
            val nameView = TextView(this).apply {
                text = " ${step.name}"
                setTextColor(Color.WHITE)
                textSize = 12f
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (6 * density).toInt()
                }
            }
            stepRow.addView(nameView)

            // 1. زر اختبار الخطوة المستقل (NEW)
            val btnTest = TextView(this).apply {
                text = "▶"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#10B981"))
                val tBg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#064E3B"))
                }
                background = tBg
                val btnW = (28 * density).toInt()
                val btnH = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply {
                    marginEnd = (4 * density).toInt()
                }
                setOnClickListener {
                    executeSingleStep(step)
                }
            }
            stepRow.addView(btnTest)

            // 2. زر التعديل
            val btnEdit = TextView(this).apply {
                text = "✏"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#38BDF8"))
                val eBg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#0C4A6E"))
                }
                background = eBg
                val btnW = (28 * density).toInt()
                val btnH = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH).apply {
                    marginEnd = (4 * density).toInt()
                }
                setOnClickListener {
                    openStepEditor(index)
                }
            }
            stepRow.addView(btnEdit)

            // 3. زر الحذف
            val btnDelete = TextView(this).apply {
                text = "🗑"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#EF4444"))
                val dBg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#450A0A"))
                }
                background = dBg
                val btnW = (28 * density).toInt()
                val btnH = (24 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnW, btnH)
                setOnClickListener {
                    deleteStep(index)
                }
            }
            stepRow.addView(btnDelete)

            stepsContainer.addView(stepRow)
        }

        scrollView.addView(stepsContainer)
        suitePanel.addView(scrollView)

        // أزرار العمليات السفلية
        val btnAddImage = Button(this).apply {
            text = "➕ التقاط هدف صورة (إطار مطاطي)"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#1E293B"))
            }
            background = bg
            setOnClickListener { showRubberBandSelector() }
        }

        val btnToggleLoop = Button(this).apply {
            text = if (isRunningLoop) "⏹ إيقاف الأتمتة المتسلسلة" else "▶ تشغيل الأتمتة الشاملة"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 13f
            val bg = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(if (isRunningLoop) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                if (isRunningLoop) stopExecution() else startExecution()
                renderMainSuiteView()
            }
        }

        suitePanel.addView(btnAddImage)
        suitePanel.addView(btnToggleLoop)
    }

    // شاشة تعديل الخطوة الاحترافية مع الكيبورد والمحرر الملون وزر الاختبار
    private fun openStepEditor(index: Int) {
        setKeyboardFocusable(true) // تفعيل الكيبورد
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density
        val step = stepsList[index]

        val header = TextView(this).apply {
            text = "تعديل الخطوة رقم [${step.stepNumber}]"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        suitePanel.addView(header)

        // تعديل اسم الخطوة
        val labelName = TextView(this).apply {
            text = "اسم الخطوة:"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
        }
        val editName = EditText(this).apply {
            setText(step.name)
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        }
        suitePanel.addView(labelName)
        suitePanel.addView(editName)

        // محرر الأكواد الملون لجافا سكريبت
        val labelCode = TextView(this).apply {
            text = "كود JavaScript الشرطي (مع التلوين الذكي):"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val editCode = EditText(this).apply {
            setText(step.scriptCode)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (120 * density).toInt()
            )
            // تفعيل التلوين التلقائي
            addTextChangedListener(CodeSyntaxHighlighter())
        }
        suitePanel.addView(labelCode)
        suitePanel.addView(editCode)

        // زر اختبار الخطوة داخل شاشة التعديل
        val btnTestThis = Button(this).apply {
            text = "⚡ اختبار هذه الخطوة الآن للتأكد"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#8B5CF6"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                step.name = editName.text.toString()
                step.scriptCode = editCode.text.toString()
                executeSingleStep(step)
            }
        }
        suitePanel.addView(btnTestThis)

        // زر الحفظ والعودة
        val btnSave = Button(this).apply {
            text = "💾 حفظ البيانات والعودة"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#0284C7"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
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

    // تنفيذ خطوة واحدة على حدة لاختبارها
    private fun executeSingleStep(step: MacroStep) {
        when (step.type) {
            ActionType.TAP, ActionType.IMAGE_TARGET -> {
                val cx = step.targetArea.centerX()
                val cy = step.targetArea.centerY()
                val ok = ApexAccessibilityService.instance?.performClick(cx, cy) ?: false
                Toast.makeText(
                    this,
                    if (ok) "✓ تم النقر على الهدف (${cx.toInt()}, ${cy.toInt()})" else "⚠ خدمة الوصول غير مفعلة!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            ActionType.JS_SCRIPT -> {
                val ok = scriptRunner.execute(step.scriptCode, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
                Toast.makeText(
                    this,
                    if (ok) "✓ نجح تنفيذ سكريبت الخطوة [${step.stepNumber}]" else "⚠ حدث خطأ في سكريبت الخطوة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteStep(index: Int) {
        if (index in 0 until stepsList.size) {
            val removed = stepsList.removeAt(index)
            // إعادة ترقيم الخطوات تلقائياً
            stepsList.forEachIndexed { i, s ->
                stepsList[i] = s.copy(stepNumber = i + 1)
            }
            Toast.makeText(this, "تم حذف ${removed.name}", Toast.LENGTH_SHORT).show()
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

                // حفظ في مكتبة الصور المركزية
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

                Toast.makeText(this, "تم حفظ الهدف بالصورة المصغرة بنجاح!", Toast.LENGTH_SHORT).show()
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
        if (::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
        }
    }
}
