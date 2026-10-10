package com.apex.nativeauto.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.capture.ImageMatcher
import com.apex.nativeauto.capture.ScreenCaptureManager
import com.apex.nativeauto.macro.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var isAttached = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()
        buildOverlayUI()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("EXTRA_DATA")

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                ScreenCaptureManager.init(this, resultCode, data)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "ApexCaptureChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Apex Macro Overlay", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Apex Macro Studio Active")
            .setContentText("محرك الأتمتة وجافا سكريبت يعمل في الخلفية")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
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

        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0B0F19"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#00F0FF"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt())
        }

        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#F50D111D"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#7C3AED"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams((320 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)

        // حركة سلسة مع تقييد صارم لحدود شاشة الهاتف (Realme GT Master Edition)
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var downTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val screenW = ScreenCaptureManager.screenWidth
                val screenH = ScreenCaptureManager.screenHeight

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
                        val newX = initialX + (event.rawX - touchX).toInt()
                        val newY = initialY + (event.rawY - touchY).toInt()

                        // منع الخروج من حدود الشاشة
                        params.x = max(0, min(newX, screenW - rootContainer.width))
                        params.y = max(0, min(newY, screenH - rootContainer.height))

                        if (isAttached) windowManager.updateViewLayout(rootContainer, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (System.currentTimeMillis() - downTime < 220 && abs(event.rawX - touchX) < 15) {
                            togglePanelExpansion(true)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(rootContainer, params)
        isAttached = true
        renderMainJobsView()
    }

    private fun renderMainJobsView() {
        setKeyboardFocusable(false)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        // الشريط العلوي
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ Main Job"
            setTextColor(Color.parseColor("#A855F7"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 18f
            setPadding((6 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener { togglePanelExpansion(false) }
        }
        header.addView(title)
        header.addView(btnClose)
        suitePanel.addView(header)

        // حاوية التمرير للبطاقات
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (250 * density).toInt()
            ).apply { topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt() }
        }

        val cardsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stepsList.forEachIndexed { index, step ->
            val cardWrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            }

            // البطاقة البنفسجية الاحترافية
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val cBg = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#C084FC"))
                }
                background = cBg
            }

            // شريط التحكم بالترتيب والاختبار السريع بالأعلى
            val controlBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            }

            val stepTitle = TextView(this).apply {
                text = "${index + 1}. ${step.name}"
                setTextColor(Color.parseColor("#1E1B4B"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showActionSettingsDialog(index) } // تعديل الاسم والسرعة
            }

            // أزرار التحريك لأعلى ولأسفل
            val btnUp = TextView(this).apply {
                text = "▲"
                textSize = 12f
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setTextColor(Color.parseColor("#312E81"))
                setOnClickListener { moveStep(index, -1) }
            }
            val btnDown = TextView(this).apply {
                text = "▼"
                textSize = 12f
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                setTextColor(Color.parseColor("#312E81"))
                setOnClickListener { moveStep(index, 1) }
            }

            // زر اختبار هذا الأكشن على حدى
            val btnTestSingle = TextView(this).apply {
                text = "▶"
                textSize = 12f
                setTextColor(Color.parseColor("#065F46"))
                setPadding((6 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener { executeSingleStep(step) }
            }

            // زر حذف الخطوة
            val btnDelete = TextView(this).apply {
                text = "🗑"
                textSize = 12f
                setPadding((4 * density).toInt(), 0, (2 * density).toInt(), 0)
                setOnClickListener {
                    stepsList.removeAt(index)
                    renderMainJobsView()
                }
            }

            controlBar.addView(stepTitle)
            controlBar.addView(btnTestSingle)
            controlBar.addView(btnUp)
            controlBar.addView(btnDown)
            controlBar.addView(btnDelete)

            // السطر الأول: Click [Thumbnail] [Delay] + زر فتح محرر JS
            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val txtClick = TextView(this).apply {
                text = "Click "
                setTextColor(Color.parseColor("#1E1B4B"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rowTop.addView(txtClick)

            if (step.thumbnail != null) {
                val thumb = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    val s = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                }
                rowTop.addView(thumb)
            }

            val txtDelay = TextView(this).apply {
                text = " [X${step.repeatCount}] [${step.delayAfterMs}ms]"
                setTextColor(Color.parseColor("#312E81"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowTop.addView(txtDelay)

            // زر محرر جافا سكريبت المخصص لهذا الأكشن
            val btnJs = Button(this).apply {
                text = "{JS}"
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#7C3AED"))
                val btnSize = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(btnSize, (24 * density).toInt())
                setOnClickListener { openJsEditor(index) }
            }
            rowTop.addView(btnJs)

            // السطر الثاني: الفرع └ Image [Thumbnail] [Appear] [70%] [Scope]
            val rowSub = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (4 * density).toInt(), 0, 0)
                setOnClickListener { showBranchSettingsDialog(index) } // ضغطة تفتح إعدادات الفرع
            }

            val txtBranch = TextView(this).apply {
                text = "└ Image "
                setTextColor(Color.parseColor("#1E1B4B"))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rowSub.addView(txtBranch)

            if (step.thumbnail != null) {
                val thumbSub = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    val s = (18 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                }
                rowSub.addView(thumbSub)
            }

            val scopeName = when (step.detectScope) {
                DetectScope.CAPTURED_LOCATION -> "Captured Location"
                DetectScope.CUSTOM_REGION -> "Custom Region"
                DetectScope.FULL_SCREEN -> "Full Screen"
            }

            val txtCondition = TextView(this).apply {
                text = " [Appear] [${step.similarityPercent}%] [$scopeName] ⚙"
                setTextColor(Color.parseColor("#312E81"))
                textSize = 10f
            }
            rowSub.addView(txtCondition)

            card.addView(controlBar)
            card.addView(rowTop)
            card.addView(rowSub)
            cardWrapper.addView(card)
            cardsContainer.addView(cardWrapper)
        }

        scrollView.addView(cardsContainer)
        suitePanel.addView(scrollView)

        // الأزرار السفلية
        val btnAddAction = Button(this).apply {
            text = "➕ Click Image (اقتطاع صورة جديدة)"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#7C3AED"))
            }
            background = bg
            setOnClickListener { showRubberBandSelector(isRegionOnly = false, targetIndex = -1) }
        }

        val btnRunJob = Button(this).apply {
            text = if (isRunningLoop) "⏹ Stop Job" else "▶ Run Job"
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 13f
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
                renderMainJobsView()
            }
        }

        suitePanel.addView(btnAddAction)
        suitePanel.addView(btnRunJob)
    }

    // 1. محرر JavaScript الكامل المخصص لكل خطوة مع زر الرجوع
    private fun openJsEditor(index: Int) {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density
        val step = stepsList[index]

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        val btnBack = TextView(this).apply {
            text = "← رجوع"
            setTextColor(Color.parseColor("#00F0FF"))
            textSize = 14f
            setOnClickListener { renderMainJobsView() }
        }

        val title = TextView(this).apply {
            text = " محرر JS: ${step.name}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }

        header.addView(btnBack)
        header.addView(title)
        suitePanel.addView(header)

        val editCode = EditText(this).apply {
            setText(step.customScript)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (140 * density).toInt()
            )
            addTextChangedListener(CodeSyntaxHighlighter())
        }
        suitePanel.addView(editCode)

        val btnSave = Button(this).apply {
            text = "💾 حفظ الكود للأكشن"
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#10B981"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                step.customScript = editCode.text.toString()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    // 2. إعدادات الأكشن السريعة (الاسم، سرعة الضغط، تغيير الصورة من المكتبة أو الشريط)
    private fun showActionSettingsDialog(index: Int) {
        setKeyboardFocusable(true)
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "إعدادات الأكشن: ${step.name}"
            setTextColor(Color.parseColor("#A855F7"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        suitePanel.addView(title)

        val editName = EditText(this).apply {
            hint = "اسم الأكشن"
            setText(step.name)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editName)

        val editDelay = EditText(this).apply {
            hint = "التأخير الزمني (ms)"
            setText(step.delayAfterMs.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editDelay)

        // زر إعادة أخذ صورة جديدة بالإطار
        val btnRetake = Button(this).apply {
            text = "📷 أخذ صورة جديدة بالإطار"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            setOnClickListener {
                showRubberBandSelector(isRegionOnly = false, targetIndex = index)
            }
        }
        suitePanel.addView(btnRetake)

        // زر حفظ الإعدادات
        val btnSave = Button(this).apply {
            text = "حفظ"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                step.name = editName.text.toString()
                step.delayAfterMs = editDelay.text.toString().toLongOrNull() ?: 500L
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    // 3. إعدادات الفرع (دقة التطابق، ونطاق البحث الثلاثي + Custom Region الشفاف)
    private fun showBranchSettingsDialog(index: Int) {
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "Detect Settings (إعدادات الفرع)"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        suitePanel.addView(title)

        // تعديل نسبة التطابق
        val txtSim = TextView(this).apply {
            text = "نسبة التطابق: ${step.similarityPercent}%"
            setTextColor(Color.parseColor("#A855F7"))
            setPadding(0, 8, 0, 4)
        }
        val editSim = EditText(this).apply {
            setText(step.similarityPercent.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(16, 10, 16, 10)
        }
        suitePanel.addView(txtSim)
        suitePanel.addView(editSim)

        // النطاقات الثلاثة
        val rg = RadioGroup(this)
        val rbCap = RadioButton(this).apply {
            text = "Captured Location (المكان الأصلي)"
            setTextColor(Color.WHITE)
            isChecked = step.detectScope == DetectScope.CAPTURED_LOCATION
        }
        val rbFull = RadioButton(this).apply {
            text = "Full Screen (كامل الشاشة)"
            setTextColor(Color.WHITE)
            isChecked = step.detectScope == DetectScope.FULL_SCREEN
        }
        val rbCustom = RadioButton(this).apply {
            text = "Custom Region (منطقة مخصصة بالإطار)"
            setTextColor(Color.WHITE)
            isChecked = step.detectScope == DetectScope.CUSTOM_REGION
        }
        rg.addView(rbCap)
        rg.addView(rbFull)
        rg.addView(rbCustom)
        suitePanel.addView(rg)

        // زر لفتح الإطار المخصص إذا اختار Custom Region
        val btnDefineRegion = Button(this).apply {
            text = "📐 تحديد منطقة البحث المخصصة"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F59E0B"))
            setOnClickListener {
                showRubberBandSelector(isRegionOnly = true, targetIndex = index)
            }
        }
        suitePanel.addView(btnDefineRegion)

        val btnSave = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setOnClickListener {
                step.similarityPercent = editSim.text.toString().toIntOrNull() ?: 70
                step.detectScope = when {
                    rbCap.isChecked -> DetectScope.CAPTURED_LOCATION
                    rbFull.isChecked -> DetectScope.FULL_SCREEN
                    else -> DetectScope.CUSTOM_REGION
                }
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun moveStep(index: Int, direction: Int) {
        val target = index + direction
        if (target in 0 until stepsList.size) {
            val item = stepsList.removeAt(index)
            stepsList.add(target, item)
            renderMainJobsView()
        }
    }

    private fun executeSingleStep(step: MacroStep) {
        scope.launch {
            val screen = ScreenCaptureManager.captureCurrentScreen()
            if (screen != null) {
                val match = ImageMatcher.findTarget(step, screen)
                if (match.isMatched && match.targetCenter != null) {
                    ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
                    Toast.makeText(applicationContext, "✓ نُفّذ بنجاح: تطابق ${match.currentSimilarity}%", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "✕ لم يُعثر على الهدف (تطابق ${match.currentSimilarity}%)", Toast.LENGTH_SHORT).show()
                }
            }
            // تنفيذ سكريبت الجافا سكريبت المرتبط بالخطوة إن وجد
            if (step.customScript.isNotBlank()) {
                scriptRunner.execute(step.customScript, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
            }
        }
    }

    private fun showRubberBandSelector(isRegionOnly: Boolean, targetIndex: Int) {
        togglePanelExpansion(false)
        if (cropOverlayView != null) return

        val cropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val cropView = CropSelectorView(
            this,
            isRegionSelectionOnly = isRegionOnly,
            onConfirm = { selectedRect, _ ->
                if (isRegionOnly && targetIndex != -1) {
                    // تحديد Custom Region للبحث فقط
                    stepsList[targetIndex].customRegion = RectF(selectedRect)
                    stepsList[targetIndex].detectScope = DetectScope.CUSTOM_REGION
                    Toast.makeText(this, "تم حفظ منطقة البحث المخصصة بنجاح!", Toast.LENGTH_SHORT).show()
                } else if (targetIndex != -1) {
                    // إعادة التقاط الصورة لنفس الأكشن
                    val realBitmap = ScreenCaptureManager.cropAreaFromScreen(selectedRect)
                    stepsList[targetIndex].thumbnail = realBitmap
                    stepsList[targetIndex].targetArea = RectF(selectedRect)
                    Toast.makeText(this, "تم تحديث صورة الأكشن بنجاح!", Toast.LENGTH_SHORT).show()
                } else {
                    // أكشن جديد كامل
                    val realBitmap = ScreenCaptureManager.cropAreaFromScreen(selectedRect)
                    val nextNum = stepsList.size + 1
                    val newStep = MacroStep(
                        stepNumber = nextNum,
                        name = "Click Image $nextNum",
                        type = ActionType.CLICK_IMAGE,
                        targetArea = RectF(selectedRect),
                        targetImageName = "target_$nextNum",
                        thumbnail = realBitmap,
                        similarityPercent = 70,
                        detectScope = DetectScope.CAPTURED_LOCATION
                    )
                    ImageLibrary.saveTarget("target_$nextNum", realBitmap)
                    stepsList.add(newStep)
                    Toast.makeText(this, "تم التقاط الهدف بدقة تامة!", Toast.LENGTH_SHORT).show()
                }
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
                // تنفيذ كل الأكشنات بالتسلسل الصارم
                for (step in stepsList) {
                    if (!isRunningLoop) break
                    if (!step.enabled) continue

                    // 1. البحث بالنقر والمطابقة
                    val screen = ScreenCaptureManager.captureCurrentScreen()
                    if (screen != null) {
                        val match = ImageMatcher.findTarget(step, screen)
                        if (match.isMatched && match.targetCenter != null) {
                            for (r in 0 until step.repeatCount) {
                                ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
                                Thread.sleep(60)
                            }
                        }
                    }

                    // 2. تشغيل كود جافا سكريبت الخاص بالخطوة
                    if (step.customScript.isNotBlank()) {
                        scriptRunner.execute(step.customScript, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
                    }

                    // 3. الانتظار الزمني للأكشن قبل الانتقال للأكشن التالي
                    Thread.sleep(step.delayAfterMs)
                }
            }
        }
    }

    private fun stopExecution() {
        isRunningLoop = false
    }

    private fun togglePanelExpansion(expand: Boolean) {
        if (expand) {
            bubbleView.visibility = View.GONE
            suitePanel.visibility = View.VISIBLE
            renderMainJobsView()
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
