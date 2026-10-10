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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.apex.nativeauto.picker.TransparentPickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null

        fun addGalleryTarget(bitmap: Bitmap) {
            instance?.let { service ->
                service.mainHandler.post {
                    val nextNum = service.stepsList.size + 1
                    val targetName = "target_$nextNum"
                    val newStep = MacroStep(
                        stepNumber = nextNum,
                        name = targetName,
                        type = ActionType.CLICK_IMAGE,
                        targetImageName = targetName,
                        thumbnail = bitmap,
                        similarityPercent = 70,
                        detectScope = DetectScope.FULL_SCREEN
                    )
                    ImageLibrary.saveTarget(targetName, bitmap)
                    service.stepsList.add(newStep)
                    service.renderMainJobsView()
                    service.togglePanelExpansion(true)
                }
            }
        }

        fun addTrainingVariation(stepIndex: Int, bitmap: Bitmap) {
            instance?.let { service ->
                service.mainHandler.post {
                    if (stepIndex in 0 until service.stepsList.size) {
                        service.stepsList[stepIndex].trainedVariations.add(bitmap)
                        Toast.makeText(service.applicationContext, "✓ أضيفت زاوية تدريبية جديدة بنجاح!", Toast.LENGTH_SHORT).show()
                        service.showActionSettingsDialog(stepIndex)
                    }
                }
            }
        }

        fun notifyTargetRenamed(oldName: String, newName: String) {
            instance?.let { service ->
                service.mainHandler.post {
                    service.stepsList.forEach { step ->
                        if (step.targetImageName == oldName) {
                            step.targetImageName = newName
                            step.name = newName
                        }
                    }
                    service.renderMainJobsView()
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var bubbleView: TextView
    private lateinit var suitePanel: LinearLayout
    private lateinit var liveConsoleView: TextView

    val stepsList = mutableListOf<MacroStep>()
    private val scriptRunner = ScriptRunner()
    private val scope = CoroutineScope(Dispatchers.Default)
    val mainHandler = Handler(Looper.getMainLooper())

    private var isRunningLoop = false
    private var isAttached = false
    private var currentOperatingMode = "VISUAL"
    private var proStandaloneScript = "// محرر المحترفين - Apex Pro Script\n// اكتب كودك بحرية مع الإكمال التلقائي:\nif (findImage(\"target_1\", 70).found) {\n    clickImage(\"target_1\");\n    log(\"تم النقر على الهدف بنجاح!\");\n}\nsleep(500);"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ImageLibrary.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundServiceNotification()

        scriptRunner.onLiveLogEmitted = { logText ->
            if (::liveConsoleView.isInitialized) {
                liveConsoleView.text = "⚡ Log: $logText"
                liveConsoleView.visibility = View.VISIBLE
            }
        }

        buildOverlayUI()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        val resultCode = intent?.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("EXTRA_DATA")
        currentOperatingMode = intent?.getStringExtra("EXTRA_MODE") ?: "VISUAL"

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                ScreenCaptureManager.init(this, resultCode, data)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (currentOperatingMode == "PRO_CODE") {
            togglePanelExpansion(true)
            openProStandaloneIDE()
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

        // الزر العائم الملكي الذهبي المصغر
        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 20f
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

        // الجناح الموسع
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
            layoutParams = FrameLayout.LayoutParams((345 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // شريط السجل الحي العائم (Live Console HUD)
        liveConsoleView = TextView(this).apply {
            text = "⚡ Live Console Ready"
            textSize = 10f
            setTextColor(Color.parseColor("#FFD700"))
            setBackgroundColor(Color.parseColor("#EE0B0C10"))
            setPadding(12, 6, 12, 6)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
        }

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)
        rootContainer.addView(liveConsoleView)

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
                            if (currentOperatingMode == "PRO_CODE") {
                                openProStandaloneIDE()
                            }
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

    fun renderMainJobsView() {
        setKeyboardFocusable(false)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ APEX ROYAL STUDIO"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAddEmptyStep = TextView(this).apply {
            text = " ➕ "
            textSize = 15f
            setTextColor(Color.parseColor("#FFD700"))
            setOnClickListener { addEmptyCustomStep() }
        }

        val btnTopLibrary = TextView(this).apply {
            text = " 📁 "
            textSize = 15f
            setTextColor(Color.parseColor("#FFD700"))
            setOnClickListener { showInAppTargetPicker() }
        }

        val btnClose = TextView(this).apply {
            text = " ✕"
            setTextColor(Color.parseColor("#D4AF37"))
            textSize = 17f
            setPadding((6 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener { togglePanelExpansion(false) }
        }

        header.addView(title)
        header.addView(btnAddEmptyStep)
        header.addView(btnTopLibrary)
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

            val topBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }

            val titleText = TextView(this).apply {
                val anglesCount = step.trainedVariations.size + (if (step.thumbnail != null) 1 else 0)
                text = "${index + 1}. ${step.name}" + if (anglesCount > 0) " ($anglesCount زوايا)" else ""
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showActionSettingsDialog(index) }
            }

            val btnJs = TextView(this).apply {
                text = "{JS}"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FFD700"))
                val bBg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#1F222E"))
                    setStroke(1, Color.parseColor("#D4AF37"))
                }
                background = bBg
                val w = (42 * density).toInt()
                val h = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { openJsEditor(index) }
            }

            val btnTest = TextView(this).apply {
                text = "فحص ⚡"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0B0C10"))
                val bg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                background = bg
                val w = (68 * density).toInt()
                val h = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { testSingleActionWithAutoPeek(step) }
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
            topBar.addView(btnJs)
            topBar.addView(btnTest)
            topBar.addView(btnDel)

            val rowClick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, (8 * density).toInt(), 0, 0)
                setOnClickListener { showActionSettingsDialog(index) }
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
                text = "Click [X${step.repeatCount}] [${step.delayAfterMs}ms] ⚡"
                setTextColor(Color.WHITE)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowClick.addView(txtClick)

            card.addView(topBar)
            card.addView(rowClick)
            cardsContainer.addView(card)
        }

        scrollView.addView(cardsContainer)
        suitePanel.addView(scrollView)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }

        val btnPickFromLibrary = Button(this).apply {
            text = "📁 من المستودع"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#FFD700"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                marginEnd = (4 * density).toInt()
            }
            setOnClickListener { showInAppTargetPicker() }
        }

        val btnGalleryAction = Button(this).apply {
            text = "🖼️ من المعرض"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1F222E"))
                setStroke(1, Color.parseColor("#D4AF37"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                marginStart = (4 * density).toInt()
            }
            setOnClickListener {
                val intent = Intent(this@OverlayService, TransparentPickerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                startActivity(intent)
            }
        }

        buttonsRow.addView(btnPickFromLibrary)
        buttonsRow.addView(btnGalleryAction)
        suitePanel.addView(buttonsRow)

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
                (44 * density).toInt()
            ).apply { topMargin = (6 * density).toInt() }
            setOnClickListener {
                if (isRunningLoop) stopExecution() else startExecution()
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnRunJob)
    }

    private fun addEmptyCustomStep() {
        val nextNum = stepsList.size + 1
        val newStep = MacroStep(
            stepNumber = nextNum,
            name = "Script Step $nextNum",
            type = ActionType.JS_SCRIPT,
            thumbnail = null,
            customScript = "",
            delayAfterMs = 300L
        )
        stepsList.add(newStep)
        renderMainJobsView()
        openJsEditor(stepsList.size - 1)
    }

    // استوديو أتمتة المحترفين المتقدم المباشر (Pro Standalone IDE)
    private fun openProStandaloneIDE() {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        val btnClose = TextView(this).apply {
            text = "✕ إغلاق"
            setTextColor(Color.parseColor("#D4AF37"))
            textSize = 13f
            setOnClickListener { togglePanelExpansion(false) }
        }
        val title = TextView(this).apply {
            text = "💻 Pro JS Automation IDE"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
        }
        header.addView(btnClose)
        header.addView(title)
        suitePanel.addView(header)

        // محرر الكود الذكي مع دعم التحديد والنسخ الكامل
        val editCode = EditText(this).apply {
            setText(proStandaloneScript)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0C10"))
            setPadding(16, 16, 16, 16)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true) // دعم التحديد والنسخ بالضغط المطول
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * density).toInt()
            )
            addTextChangedListener(CodeSyntaxHighlighter())
        }

        // شريط الاقتراحات الذكي الحي (Autocomplete Bar)
        val autocompleteContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val autocompleteScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        fun updateSuggestions(word: String) {
            autocompleteContainer.removeAllViews()
            val allSuggestions = listOf(
                "click(x, y)",
                "clickImage(\"\")",
                "findImage(\"\", 70)",
                "sleep(500)",
                "log(\"\")",
                "toast(\"\")",
                "pressBack()",
                "pressHome()",
                "if () {}",
                "while () {}"
            ) + ImageLibrary.targetImages.keys.map { "\"$it\"" }

            val matched = allSuggestions.filter { it.contains(word, ignoreCase = true) }
            val listToShow = if (matched.isEmpty()) allSuggestions.take(5) else matched.take(7)

            listToShow.forEach { suggestion ->
                val chip = Button(this@OverlayService).apply {
                    text = suggestion
                    textSize = 9f
                    setTextColor(Color.parseColor("#0B0C10"))
                    val bg = GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(Color.parseColor("#FFD700"))
                    }
                    background = bg
                    setPadding(10, 0, 10, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (28 * density).toInt()
                    ).apply { marginEnd = (4 * density).toInt() }
                    setOnClickListener {
                        val start = max(0, editCode.selectionStart)
                        val end = max(0, editCode.selectionEnd)
                        editCode.text.replace(min(start, end), max(start, end), suggestion)
                    }
                }
                autocompleteContainer.addView(chip)
            }
        }

        editCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val full = s?.toString() ?: ""
                val cursor = editCode.selectionStart
                val sub = full.take(cursor)
                val lastWord = sub.split(Regex("[\\s,;()]+")).lastOrNull() ?: ""
                updateSuggestions(lastWord)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        updateSuggestions("")
        autocompleteScroll.addView(autocompleteContainer)
        suitePanel.addView(autocompleteScroll)
        suitePanel.addView(editCode)

        val btnRunProScript = Button(this).apply {
            text = if (isRunningLoop) "⏹ إيقاف السكربت" else "▶ تشغيل السكربت المباشر"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (isRunningLoop) Color.parseColor("#EF4444") else Color.parseColor("#10B981"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                proStandaloneScript = editCode.text.toString()
                if (isRunningLoop) {
                    stopExecution()
                    text = "▶ تشغيل السكربت المباشر"
                    setBackgroundColor(Color.parseColor("#10B981"))
                } else {
                    isRunningLoop = true
                    text = "⏹ إيقاف السكربت"
                    setBackgroundColor(Color.parseColor("#EF4444"))
                    scope.launch {
                        scriptRunner.execute(proStandaloneScript, 1, 0f, 0f)
                        mainHandler.post {
                            isRunningLoop = false
                            text = "▶ تشغيل السكربت المباشر"
                            val b = GradientDrawable().apply {
                                cornerRadius = 10 * density
                                setColor(Color.parseColor("#10B981"))
                            }
                            background = b
                        }
                    }
                }
            }
        }
        suitePanel.addView(btnRunProScript)
    }

    private fun openJsEditor(index: Int) {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density
        val step = stepsList[index]

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
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
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (140 * density).toInt()
            )
            addTextChangedListener(CodeSyntaxHighlighter())
        }

        fun insertSnippet(snippet: String) {
            val start = max(0, editCode.selectionStart)
            val end = max(0, editCode.selectionEnd)
            editCode.text.replace(min(start, end), max(start, end), snippet)
        }

        val toolbarScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            ).apply { bottomMargin = (4 * density).toInt() }
        }
        val toolbarContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeSnippetBtn(label: String, code: String): Button {
            return Button(this).apply {
                text = label
                textSize = 9f
                setTextColor(Color.parseColor("#0B0C10"))
                val bg = GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                background = bg
                setPadding(10, 0, 10, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * density).toInt()
                ).apply { marginEnd = (4 * density).toInt() }
                setOnClickListener { insertSnippet(code) }
            }
        }

        toolbarContainer.addView(makeSnippetBtn("👆 clickImage", "clickImage(\"${step.targetImageName}\");\n"))
        toolbarContainer.addView(makeSnippetBtn("👁️ findImage", "if (findImage(\"${step.targetImageName}\", 70).found) {\n    click(x, y);\n}\n"))
        toolbarContainer.addView(makeSnippetBtn("⏱️ sleep", "sleep(500);\n"))
        toolbarContainer.addView(makeSnippetBtn("💬 log", "log(\"خطوة مكتملة\");\n"))
        toolbarContainer.addView(makeSnippetBtn("🔙 Back", "pressBack();\n"))
        toolbarContainer.addView(makeSnippetBtn("🏠 Home", "pressHome();\n"))

        toolbarScroll.addView(toolbarContainer)
        suitePanel.addView(toolbarScroll)
        suitePanel.addView(editCode)

        val btnSave = Button(this).apply {
            text = "💾 حفظ الكود للأكشن"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
            setOnClickListener {
                step.customScript = editCode.text.toString()
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun showInAppTargetPicker() {
        val targets = ImageLibrary.targetImages
        if (targets.isEmpty()) {
            Toast.makeText(this, "المستودع فارغ، أضف صوراً عبر زر المعرض أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "اختر هدفاً من المستودع"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        suitePanel.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (200 * density).toInt()
            )
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        targets.forEach { (name, bmp) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                val bg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#151722"))
                    setStroke(1, Color.parseColor("#D4AF37"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (4 * density).toInt() }
                setOnClickListener {
                    addGalleryTarget(bmp)
                }
            }

            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            val txt = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            row.addView(img)
            row.addView(txt)
            container.addView(row)
        }

        scroll.addView(container)
        suitePanel.addView(scroll)

        val btnBack = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F222E"))
            setOnClickListener { renderMainJobsView() }
        }
        suitePanel.addView(btnBack)
    }

    fun showActionSettingsDialog(index: Int) {
        setKeyboardFocusable(true)
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "إعدادات الأكشن والسرعة"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        suitePanel.addView(title)

        val editName = EditText(this).apply {
            hint = "اسم الأكشن"
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

        val editRepeat = EditText(this).apply {
            hint = "مرات التكرار (Repeat Count)"
            setText(step.repeatCount.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#151722"))
            setPadding(16, 12, 16, 12)
        }
        suitePanel.addView(editRepeat)

        if (step.thumbnail != null) {
            val txtTraining = TextView(this).apply {
                text = "🧠 تدريب الذكاء الاصطناعي (${step.trainedVariations.size} زوايا مدربة):"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 11f
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            }
            suitePanel.addView(txtTraining)

            val horizontalScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (45 * density).toInt()
                )
            }
            val trainingContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            step.trainedVariations.forEachIndexed { vIndex, vBmp ->
                val vImg = ImageView(this).apply {
                    setImageBitmap(vBmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val bg = GradientDrawable().apply {
                        cornerRadius = 4 * density
                        setStroke(1, Color.parseColor("#FFD700"))
                    }
                    background = bg
                    layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnLongClickListener {
                        step.trainedVariations.removeAt(vIndex)
                        showActionSettingsDialog(index)
                        true
                    }
                }
                trainingContainer.addView(vImg)
            }
            horizontalScroll.addView(trainingContainer)
            suitePanel.addView(horizontalScroll)

            val btnAddVariation = Button(this).apply {
                text = "➕ تدريب زاوية جديدة من المعرض"
                setTextColor(Color.parseColor("#0B0C10"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                val bg = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                background = bg
                setOnClickListener {
                    val intent = Intent(this@OverlayService, TransparentPickerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        putExtra("IS_TRAINING_MODE", true)
                        putExtra("TARGET_STEP_INDEX", index)
                    }
                    startActivity(intent)
                }
            }
            suitePanel.addView(btnAddVariation)
        }

        val btnSave = Button(this).apply {
            text = "حفظ"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                step.name = editName.text.toString()
                step.delayAfterMs = editDelay.text.toString().toLongOrNull() ?: 500L
                step.repeatCount = editRepeat.text.toString().toIntOrNull() ?: 1
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun testSingleActionWithAutoPeek(step: MacroStep) {
        rootContainer.visibility = View.GONE

        scope.launch {
            delay(150)
            val screen = ScreenCaptureManager.getRealScreenshot()
            mainHandler.post {
                if (screen != null && step.thumbnail != null) {
                    val match = ImageMatcher.findTarget(step, screen)
                    if (match.isMatched && match.targetCenter != null) {
                        ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
                        Toast.makeText(applicationContext, "✓ نُفّذ بنجاح! التطابق: ${match.currentSimilarity}%", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "✕ لم يُعثر على أي من زوايا الهدف (تطابق ${match.currentSimilarity}%)", Toast.LENGTH_SHORT).show()
                    }
                } else if (step.customScript.isNotBlank()) {
                    scriptRunner.execute(step.customScript, step.stepNumber, step.targetArea.centerX(), step.targetArea.centerY())
                    Toast.makeText(applicationContext, "✓ تم تنفيذ الكود المخصص!", Toast.LENGTH_SHORT).show()
                }
                mainHandler.postDelayed({
                    rootContainer.visibility = View.VISIBLE
                }, 1400)
            }
        }
    }

    private fun startExecution() {
        isRunningLoop = true
        scope.launch {
            while (isRunningLoop) {
                for (step in stepsList) {
                    if (!isRunningLoop) break
                    if (!step.enabled) continue

                    val screen = ScreenCaptureManager.getRealScreenshot()
                    if (screen != null && step.thumbnail != null) {
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

    fun togglePanelExpansion(expand: Boolean) {
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
        instance = null
        stopExecution()
        if (isAttached && ::rootContainer.isInitialized) {
            windowManager.removeView(rootContainer)
            isAttached = false
        }
    }
}
