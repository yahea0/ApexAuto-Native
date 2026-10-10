package com.apex.nativeauto.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.delay
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
    private val mainHandler = Handler(Looper.getMainLooper())

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

        val params = WindowManager.LayoutParams(
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return params
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

        // الزر العائم الملكي الذهبي المصغر (48dp)
        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFD700"))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0B0C10"))
                setStroke((2.5f * density).toInt(), Color.parseColor("#FFD700"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
        }

        // الجناح الموسع بتصميم أسود ملكي فخم
        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F50D0E15"))
                setStroke((2f * density).toInt(), Color.parseColor("#D4AF37"))
            }
            background = bg
            layoutParams = FrameLayout.LayoutParams((340 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
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

        // الشريط العلوي الملكي
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ APEX ROYAL STUDIO"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#D4AF37"))
            textSize = 18f
            setPadding((6 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener { togglePanelExpansion(false) }
        }
        header.addView(title)
        header.addView(btnClose)
        suitePanel.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (250 * density).toInt()
            ).apply { topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt() }
        }

        val cardsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stepsList.forEachIndexed { index, step ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val cBg = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(Color.parseColor("#151722"))
                    setStroke(1, Color.parseColor("#D4AF37"))
                }
                background = cBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            }

            // الشريط العلوي للبطاقة
            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }

            val titleText = TextView(this).apply {
                text = "${index + 1}. ${step.name}"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showActionSettingsDialog(index) }
            }

            // زر فحص واختبار الأكشن المكبر بدون أي اقتطاع
            val btnTest = Button(this).apply {
                text = "⚡ فحص"
                setTextColor(Color.parseColor("#0B0C10"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                val bg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                background = bg
                // زيادة العرض لمنع اقتطاع النص نهائياً
                layoutParams = LinearLayout.LayoutParams((72 * density).toInt(), (32 * density).toInt()).apply {
                    marginEnd = (6 * density).toInt()
                }
                setOnClickListener {
                    testSingleActionWithAutoPeek(step)
                }
            }

            val btnUp = TextView(this).apply {
                text = "▲"
                setTextColor(Color.parseColor("#D4AF37"))
                textSize = 14f
                setPadding((6 * density).toInt(), 0, (4 * density).toInt(), 0)
                setOnClickListener { moveStep(index, -1) }
            }
            val btnDown = TextView(this).apply {
                text = "▼"
                setTextColor(Color.parseColor("#D4AF37"))
                textSize = 14f
                setPadding((4 * density).toInt(), 0, (6 * density).toInt(), 0)
                setOnClickListener { moveStep(index, 1) }
            }

            val btnDel = TextView(this).apply {
                text = "🗑"
                textSize = 13f
                setPadding((4 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    stepsList.removeAt(index)
                    renderMainJobsView()
                }
            }

            topBar.addView(titleText)
            topBar.addView(btnTest)
            topBar.addView(btnUp)
            topBar.addView(btnDown)
            topBar.addView(btnDel)

            // سطر تفاصيل النقر والصورة
            val rowClick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, (6 * density).toInt(), 0, 0)
            }

            if (step.thumbnail != null) {
                val thumb = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val tBg = GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(Color.BLACK)
                        setStroke(1, Color.parseColor("#FFD700"))
                    }
                    background = tBg
                    val s = (36 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8 * density).toInt() }
                }
                rowClick.addView(thumb)
            }

            val txtClick = TextView(this).apply {
                text = "Click [X${step.repeatCount}] [${step.delayAfterMs}ms]"
                setTextColor(Color.WHITE)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowClick.addView(txtClick)

            // زر {JS} المكبر الذي يظهر بالكامل
            val btnJs = Button(this).apply {
                text = "{JS}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFD700"))
                val bBg = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#1F222E"))
                    setStroke(1, Color.parseColor("#D4AF37"))
                }
                background = bBg
                layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (28 * density).toInt())
                setOnClickListener { openJsEditor(index) }
            }
            rowClick.addView(btnJs)

            // سطر الفرع
            val rowBranch = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding((12 * density).toInt(), (6 * density).toInt(), 0, 0)
                setOnClickListener { showBranchSettingsDialog(index) }
            }

            val txtBranch = TextView(this).apply {
                val scopeName = when (step.detectScope) {
                    DetectScope.CAPTURED_LOCATION -> "Captured"
                    DetectScope.CUSTOM_REGION -> "Custom Region"
                    DetectScope.FULL_SCREEN -> "Full Screen"
                }
                text = "└ Image [Appear] [${step.similarityPercent}%] [$scopeName] ⚙"
                setTextColor(Color.parseColor("#A0A5B5"))
                textSize = 10f
            }
            rowBranch.addView(txtBranch)

            card.addView(topBar)
            card.addView(rowClick)
            card.addView(rowBranch)
            cardsContainer.addView(card)
        }

        scrollView.addView(cardsContainer)
        suitePanel.addView(scrollView)

        // الأزرار السفلية
        val btnAddAction = Button(this).apply {
            text = "➕ Click Image (اقتطاع صورة جديدة)"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            background = bg
            setOnClickListener { showRubberBandSelector(isRegionOnly = false, targetIndex = -1) }
        }

        val btnRunJob = Button(this).apply {
            text = if (isRunningLoop) "⏹ Stop Job" else "▶ Run Job"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            val bg = GradientDrawable().apply {
                cornerRadius = 14 * density
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

    // إخفاء القائمة فوراً عند فحص الأكشن لإتاحة رؤية الشاشة ثم إعادتها
    private fun testSingleActionWithAutoPeek(step: MacroStep) {
        rootContainer.visibility = View.GONE

        scope.launch {
            delay(200)
            val screen = ScreenCaptureManager.captureCurrentScreen()
            mainHandler.post {
                if (screen != null) {
                    val match = ImageMatcher.findTarget(step, screen)
                    if (match.isMatched && match.targetCenter != null) {
                        ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
                        Toast.makeText(applicationContext, "✓ نُفّذ في المنتصف بنجاح! التطابق: ${match.currentSimilarity}%", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "✕ لم يُعثر على الهدف (تطابق ${match.currentSimilarity}%)", Toast.LENGTH_SHORT).show()
                    }
                }
                mainHandler.postDelayed({
                    rootContainer.visibility = View.VISIBLE
                }, 1500)
            }
        }
    }

    // نافذة تسمية الهدف بعد الاقتطاع
    private fun showNameTargetDialog(realBitmap: Bitmap, targetArea: RectF) {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "تسمية الهدف (Target Name)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        suitePanel.addView(title)

        val editName = EditText(this).apply {
            val nextNum = stepsList.size + 1
            setText("target_$nextNum")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F222E"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editName)

        val btnSave = Button(this).apply {
            text = "حفظ الهدف للأكشن"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                val tName = editName.text.toString().trim()
                val nextNum = stepsList.size + 1
                val newStep = MacroStep(
                    stepNumber = nextNum,
                    name = tName,
                    type = ActionType.CLICK_IMAGE,
                    targetArea = RectF(targetArea),
                    targetImageName = tName,
                    thumbnail = realBitmap,
                    similarityPercent = 70,
                    detectScope = DetectScope.CAPTURED_LOCATION
                )
                ImageLibrary.saveTarget(tName, realBitmap)
                stepsList.add(newStep)

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)

                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    // إعدادات الفرع بدون أي تعليق مع استجابة فورية
    private fun showBranchSettingsDialog(index: Int) {
        setKeyboardFocusable(true)
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "Detect Settings (إعدادات الفرع)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        suitePanel.addView(title)

        val txtSim = TextView(this).apply {
            text = "نسبة التطابق المطلوبة %:"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
        }
        val editSim = EditText(this).apply {
            setText(step.similarityPercent.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFD700"))
            setBackgroundColor(Color.parseColor("#151722"))
            setPadding(16, 10, 16, 10)
        }
        suitePanel.addView(txtSim)
        suitePanel.addView(editSim)

        // حاوية الكروت الذهبية المستقلة
        var selectedScope = step.detectScope
        val cardScopeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        lateinit var cardCap: LinearLayout
        lateinit var cardFull: LinearLayout
        lateinit var cardCustom: LinearLayout

        fun updateCardStyles() {
            fun style(card: LinearLayout, isSel: Boolean) {
                val bg = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(if (isSel) Color.parseColor("#262214") else Color.parseColor("#151722"))
                    setStroke(1, if (isSel) Color.parseColor("#FFD700") else Color.parseColor("#333A4D"))
                }
                card.background = bg
                (card.getChildAt(0) as TextView).text = if (isSel) "● " else "○ "
                (card.getChildAt(0) as TextView).setTextColor(if (isSel) Color.parseColor("#FFD700") else Color.GRAY)
            }
            style(cardCap, selectedScope == DetectScope.CAPTURED_LOCATION)
            style(cardFull, selectedScope == DetectScope.FULL_SCREEN)
            style(cardCustom, selectedScope == DetectScope.CUSTOM_REGION)
        }

        fun makeCard(name: String, scopeType: DetectScope): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (5 * density).toInt() }
                setOnClickListener {
                    selectedScope = scopeType
                    updateCardStyles() // استجابة فورية بدون تعليق
                }
            }
            val icon = TextView(this).apply { textSize = 12f }
            val text = TextView(this).apply {
                this.text = name
                setTextColor(Color.WHITE)
                textSize = 11f
            }
            card.addView(icon)
            card.addView(text)
            return card
        }

        cardCap = makeCard("Captured Location (المكان الأصلي)", DetectScope.CAPTURED_LOCATION)
        cardFull = makeCard("Full Screen (كامل الشاشة)", DetectScope.FULL_SCREEN)
        cardCustom = makeCard("Custom Region (منطقة مخصصة بالإطار)", DetectScope.CUSTOM_REGION)

        cardScopeContainer.addView(cardCap)
        cardScopeContainer.addView(cardFull)
        cardScopeContainer.addView(cardCustom)
        suitePanel.addView(cardScopeContainer)

        updateCardStyles()

        val btnDefineRegion = Button(this).apply {
            text = "📐 تحديد منطقة البحث المخصصة بالإطار"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F222E"))
            setOnClickListener {
                showRubberBandSelector(isRegionOnly = true, targetIndex = index)
            }
        }
        suitePanel.addView(btnDefineRegion)

        val btnSave = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
            setOnClickListener {
                step.similarityPercent = editSim.text.toString().toIntOrNull() ?: 70
                step.detectScope = selectedScope
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun showActionSettingsDialog(index: Int) {
        setKeyboardFocusable(true)
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "إعدادات الأكشن: ${step.name}"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        suitePanel.addView(title)

        val editName = EditText(this).apply {
            setText(step.name)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151722"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editName)

        val editDelay = EditText(this).apply {
            hint = "التأخير الزمني ms"
            setText(step.delayAfterMs.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151722"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editDelay)

        val btnRetake = Button(this).apply {
            text = "📷 إعادة أخذ صورة جديدة بالإطار"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F222E"))
            setOnClickListener {
                showRubberBandSelector(isRegionOnly = false, targetIndex = index)
            }
        }
        suitePanel.addView(btnRetake)

        val btnSave = Button(this).apply {
            text = "حفظ"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
            setOnClickListener {
                step.name = editName.text.toString()
                step.delayAfterMs = editDelay.text.toString().toLongOrNull() ?: 500L
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

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
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            setOnClickListener { renderMainJobsView() }
        }
        val title = TextView(this).apply {
            text = "محرر JS: ${step.name}"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        header.addView(btnBack)
        header.addView(title)
        suitePanel.addView(header)

        val editCode = EditText(this).apply {
            setText(step.customScript)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0C10"))
            setPadding(16, 16, 16, 16)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (140 * density).toInt()
            )
            addTextChangedListener(CodeSyntaxHighlighter())
        }
        suitePanel.addView(editCode)

        val btnSave = Button(this).apply {
            text = "💾 حفظ الكود"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
            setOnClickListener {
                step.customScript = editCode.text.toString()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cropParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val cropView = CropSelectorView(
            this,
            isRegionSelectionOnly = isRegionOnly,
            onConfirm = { selectedRect ->
                if (isRegionOnly && targetIndex != -1) {
                    stepsList[targetIndex].customRegion = RectF(selectedRect)
                    stepsList[targetIndex].detectScope = DetectScope.CUSTOM_REGION
                    Toast.makeText(this, "تم حفظ منطقة البحث المخصصة بنجاح!", Toast.LENGTH_SHORT).show()
                    removeRubberBandSelector()
                    togglePanelExpansion(true)
                } else if (targetIndex != -1) {
                    val realBitmap = ScreenCaptureManager.cropAreaFromScreen(selectedRect)
                    stepsList[targetIndex].thumbnail = realBitmap
                    stepsList[targetIndex].targetArea = RectF(selectedRect)
                    removeRubberBandSelector()
                    togglePanelExpansion(true)
                } else {
                    val realBitmap = ScreenCaptureManager.cropAreaFromScreen(selectedRect)
                    removeRubberBandSelector()
                    togglePanelExpansion(true)
                    showNameTargetDialog(realBitmap, selectedRect)
                }
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

                    if (step.customScript.isNotBlank()) {
                        scriptRunner.execute(step.customScript, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
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
