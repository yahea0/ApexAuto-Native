package com.apex.nativeauto.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.capture.ImageMatcher
import com.apex.nativeauto.capture.ScreenCaptureManager
import com.apex.nativeauto.macro.*
import com.apex.nativeauto.picker.TransparentPickerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        var instance: OverlayService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        const val ACTION_EDIT_SCRIPT = "ACTION_EDIT_SCRIPT"
        const val ACTION_RUN_MINI_CONTROLLER = "ACTION_RUN_MINI_CONTROLLER"
        const val EXTRA_SCRIPT_NAME = "EXTRA_SCRIPT_NAME"

        // إضافة هدف جديد معزول تماماً ومربوط باسم السكربت الحالي
        fun addGalleryTarget(bitmap: Bitmap) {
            instance?.let { service ->
                service.mainHandler.post {
                    val nextNum = service.stepsList.size + 1
                    val targetName = "${service.currentScriptName}_target_$nextNum"
                    val newStep = MacroStep(
                        stepNumber = nextNum,
                        name = "target_$nextNum",
                        type = ActionType.CLICK_IMAGE,
                        targetImageName = targetName,
                        thumbnail = bitmap,
                        similarityPercent = 70,
                        detectScope = DetectScope.FULL_SCREEN,
                        customScript = ""
                    )
                    ImageLibrary.saveTarget(targetName, bitmap)
                    service.stepsList.add(newStep)

                    // حفظ تلقائي فوري في مجلد السكربت لضمان عدم ضياع الصور
                    ScriptStorage.saveScript(service.currentScriptName, service.stepsList, service.loopCountLimit)

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
                        ScriptStorage.saveScript(service.currentScriptName, service.stepsList, service.loopCountLimit)
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
                    ScriptStorage.saveScript(service.currentScriptName, service.stepsList, service.loopCountLimit)
                    service.renderMainJobsView()
                }
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var bubbleView: TextView
    private lateinit var suitePanel: LinearLayout
    private lateinit var miniControllerView: LinearLayout
    private lateinit var miniStatusText: TextView

    val stepsList = mutableListOf<MacroStep>()
    private val scriptRunner = ScriptRunner()
    private val scope = CoroutineScope(Dispatchers.Default)
    val mainHandler = Handler(Looper.getMainLooper())

    private var executionJob: Job? = null
    private var isRunningLoop = false
    private var isAttached = false
    private var loopCountLimit = 0
    private var currentExecutionIndex = 0
    var currentScriptName = "Script_1"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ImageLibrary.init(this)
        ScriptStorage.init(this)
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

        val sName = intent?.getStringExtra(EXTRA_SCRIPT_NAME)
        when (intent?.action) {
            ACTION_EDIT_SCRIPT -> {
                if (!sName.isNullOrBlank()) {
                    loadScriptForEditing(sName)
                }
            }
            ACTION_RUN_MINI_CONTROLLER -> {
                if (!sName.isNullOrBlank()) {
                    loadScriptForMiniRunner(sName)
                }
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
            x = 40
            y = 200
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

    private fun setPanelDimensions(expandedForEditor: Boolean) {
        val density = resources.displayMetrics.density
        val targetWidth = if (expandedForEditor) {
            min((ScreenCaptureManager.screenWidth * 0.94f).toInt(), (400 * density).toInt())
        } else {
            (345 * density).toInt()
        }
        val layoutParams = suitePanel.layoutParams
        layoutParams.width = targetWidth
        suitePanel.layoutParams = layoutParams
    }

    private fun buildOverlayUI() {
        val params = getOverlayLayoutParams()
        rootContainer = FrameLayout(this)
        val density = resources.displayMetrics.density

        bubbleView = TextView(this).apply {
            text = "⚡"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFD700"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60D121F"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#80FFD700"))
            }
            layoutParams = FrameLayout.LayoutParams((50 * density).toInt(), (50 * density).toInt())
        }

        suitePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F20B0F19"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#4DFFD700"))
            }
            layoutParams = FrameLayout.LayoutParams((345 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        buildMiniController(density)

        rootContainer.addView(bubbleView)
        rootContainer.addView(suitePanel)
        rootContainer.addView(miniControllerView)

        bubbleView.setOnTouchListener(createDragTouchListener(params) {
            togglePanelExpansion(true)
        })

        miniControllerView.setOnTouchListener(createDragTouchListener(params, onClick = null))

        windowManager.addView(rootContainer, params)
        isAttached = true
        renderMainJobsView()
    }

    private fun buildMiniController(density: Float) {
        miniControllerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.parseColor("#E6080C14"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#6600E676"))
            }
            layoutParams = FrameLayout.LayoutParams((300 * density).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (6 * density).toInt())
        }

        miniStatusText = TextView(this).apply {
            text = "⚡ السكربت جاهز"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnExitMini = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener {
                stopExecution()
                miniControllerView.visibility = View.GONE
                bubbleView.visibility = View.VISIBLE
            }
        }

        topRow.addView(miniStatusText)
        topRow.addView(btnExitMini)
        miniControllerView.addView(topRow)

        val btnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            )
        }

        fun makeMiniActionBtn(label: String, bgColor: Int, txtColor: Int, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(txtColor)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(bgColor)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = (3 * density).toInt()
                    marginStart = (3 * density).toInt()
                }
                setOnClickListener { onClick() }
            }
        }

        btnsRow.addView(makeMiniActionBtn("▶ من البداية", Color.parseColor("#FFD700"), Color.parseColor("#0B0C10")) {
            startExecutionFromBeginning()
        })

        btnsRow.addView(makeMiniActionBtn("⏹ إيقاف", Color.parseColor("#EF4444"), Color.WHITE) {
            stopExecution()
            miniStatusText.text = "⏹ متوقف عند الخطوة (${currentExecutionIndex + 1})"
        })

        btnsRow.addView(makeMiniActionBtn("⏯ تكملة", Color.parseColor("#00E676"), Color.parseColor("#042614")) {
            resumeExecution()
        })

        miniControllerView.addView(btnsRow)
    }

    private fun createDragTouchListener(params: WindowManager.LayoutParams, onClick: (() -> Unit)? = null): View.OnTouchListener {
        return object : View.OnTouchListener {
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
                            onClick?.invoke()
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    fun renderMainJobsView() {
        setKeyboardFocusable(false)
        setPanelDimensions(false)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        val title = TextView(this).apply {
            text = "⚡ $currentScriptName"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makeGlassActionIcon(symbol: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = symbol
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F1F5F9"))
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#1FFFFFFF"))
                    setStroke(1, Color.parseColor("#33FFFFFF"))
                }
                val s = (28 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (5 * density).toInt() }
                setOnClickListener { onClick() }
            }
        }

        header.addView(title)
        header.addView(makeGlassActionIcon("➕") { addEmptyCustomStep() })
        header.addView(makeGlassActionIcon("💾") { showSaveScriptDialog() })
        header.addView(makeGlassActionIcon("📜") { showInAppScriptsRepository() })
        header.addView(makeGlassActionIcon("📁") { showInAppTargetPicker() })
        header.addView(makeGlassActionIcon("⚙️") { showGlobalJobSettingsDialog() })
        header.addView(makeGlassActionIcon("✕") { togglePanelExpansion(false) })
        suitePanel.addView(header)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (250 * density).toInt()
            ).apply { topMargin = (6 * density).toInt(); bottomMargin = (8 * density).toInt() }
            isVerticalScrollBarEnabled = false
        }

        val cardsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stepsList.forEachIndexed { index, step ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(Color.parseColor("#14FFFFFF"))
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
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
                setTextColor(Color.parseColor("#38BDF8"))
                background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#1A38BDF8"))
                    setStroke(1, Color.parseColor("#4D38BDF8"))
                }
                val w = (42 * density).toInt()
                val h = (26 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { openJsEditor(index) }
            }

            val btnTest = TextView(this).apply {
                text = "فحص ⚡"
                textSize = 11f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#0B0C10"))
                background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                val w = (66 * density).toInt()
                val h = (26 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(w, h).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { testSingleActionWithAutoPeek(step) }
            }

            val btnUp = TextView(this).apply {
                text = "▲"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                setPadding((5 * density).toInt(), 0, (3 * density).toInt(), 0)
                setOnClickListener { moveStep(index, -1) }
            }
            val btnDown = TextView(this).apply {
                text = "▼"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                setPadding((3 * density).toInt(), 0, (5 * density).toInt(), 0)
                setOnClickListener { moveStep(index, 1) }
            }

            val btnDel = TextView(this).apply {
                text = "🗑"
                textSize = 13f
                setPadding((4 * density).toInt(), 0, 0, 0)
                setOnClickListener {
                    stepsList.removeAt(index)
                    ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
                    renderMainJobsView()
                }
            }

            topBar.addView(titleText)
            topBar.addView(btnJs)
            topBar.addView(btnTest)
            topBar.addView(btnUp)
            topBar.addView(btnDown)
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
                    background = GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(Color.BLACK)
                        setStroke(1, Color.parseColor("#4DFFD700"))
                    }
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
                text = if (step.thumbnail != null) "└ Image [Appear] [${step.similarityPercent}%] [$scopeName] ⚙" else "└ Code Action (مخصص) ⚙"
                setTextColor(Color.parseColor("#94A3B8"))
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

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }

        val btnPickFromLibrary = Button(this).apply {
            text = "📁 من المستودع"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                marginEnd = (5 * density).toInt()
            }
            setOnClickListener { showInAppTargetPicker() }
        }

        val btnGalleryAction = Button(this).apply {
            text = "🖼️ من المعرض"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply {
                marginStart = (5 * density).toInt()
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
            setTextColor(if (isRunningLoop) Color.WHITE else Color.parseColor("#042614"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(if (isRunningLoop) Color.parseColor("#EF4444") else Color.parseColor("#00E676"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (46 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                if (isRunningLoop) stopExecution() else startExecutionFromBeginning()
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnRunJob)
    }

    private fun showSaveScriptDialog() {
        setKeyboardFocusable(true)
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "💾 حفظ السكربت الحالي"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        suitePanel.addView(title)

        val editName = EditText(this).apply {
            hint = "اسم السكربت (مثال: FarmScript)"
            setText(currentScriptName)
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * density).toInt() }
        }
        suitePanel.addView(editName)

        val btnSave = Button(this).apply {
            text = "حفظ في المستودع"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            )
            setOnClickListener {
                val name = editName.text.toString().trim()
                if (name.isNotBlank()) {
                    currentScriptName = name
                    val ok = ScriptStorage.saveScript(name, stepsList, loopCountLimit)
                    if (ok) {
                        Toast.makeText(this@OverlayService, "✓ تم حفظ السكربت ($name) بنجاح!", Toast.LENGTH_SHORT).show()
                        renderMainJobsView()
                    } else {
                        Toast.makeText(this@OverlayService, "فشل حفظ السكربت!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        suitePanel.addView(btnSave)

        val btnCancel = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(1, Color.parseColor("#1FFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener { renderMainJobsView() }
        }
        suitePanel.addView(btnCancel)
    }

    private fun showInAppScriptsRepository() {
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "📜 مستودع السكربتات المحفوظة"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        val scripts = ScriptStorage.getAllScripts()
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * density).toInt()
            )
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (scripts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "لا توجد سكربتات محفوظة.\nاستخدم زر 💾 في الأعلى لحفظ السكربت الحالي."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (30 * density).toInt(), 0, 0)
            }
            container.addView(empty)
        } else {
            scripts.forEach { sName ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * density
                        setColor(Color.parseColor("#14FFFFFF"))
                        setStroke(1, Color.parseColor("#26FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (6 * density).toInt() }
                }

                val txt = TextView(this).apply {
                    text = sName
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnLoad = TextView(this).apply {
                    text = "✏️ فتح"
                    setTextColor(Color.parseColor("#FFD700"))
                    textSize = 12f
                    setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        loadScriptForEditing(sName)
                    }
                }

                val btnRun = TextView(this).apply {
                    text = "▶️ تشغيل"
                    setTextColor(Color.parseColor("#00E676"))
                    textSize = 12f
                    setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                    setOnClickListener {
                        loadScriptForMiniRunner(sName)
                    }
                }

                row.addView(txt)
                row.addView(btnLoad)
                row.addView(btnRun)
                container.addView(row)
            }
        }

        scroll.addView(container)
        suitePanel.addView(scroll)

        val btnBack = Button(this).apply {
            text = "رجوع"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(1, Color.parseColor("#1FFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
            setOnClickListener { renderMainJobsView() }
        }
        suitePanel.addView(btnBack)
    }

    // تفريغ وتحميل السكربت المحدد حصراً مع صوره المستقلة
    fun loadScriptForEditing(name: String) {
        val data = ScriptStorage.loadScript(name)
        if (data != null) {
            stepsList.clear()
            stepsList.addAll(data.first)
            loopCountLimit = data.second
            currentScriptName = name
            currentExecutionIndex = 0
            stopExecution()
            miniControllerView.visibility = View.GONE
            togglePanelExpansion(true)
            Toast.makeText(this, "تم تحميل السكربت ($name) للتعديل!", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadScriptForMiniRunner(name: String) {
        val data = ScriptStorage.loadScript(name)
        if (data != null) {
            stepsList.clear()
            stepsList.addAll(data.first)
            loopCountLimit = data.second
            currentScriptName = name
            currentExecutionIndex = 0
            stopExecution()

            bubbleView.visibility = View.GONE
            suitePanel.visibility = View.GONE
            miniControllerView.visibility = View.VISIBLE
            miniStatusText.text = "⚡ $name (جاهز)"
            Toast.makeText(this, "تم تفعيل المتحكم العائم للسكربت ($name)!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startExecutionFromBeginning() {
        stopExecution()
        currentExecutionIndex = 0
        runExecutionLoop()
    }

    private fun resumeExecution() {
        if (isRunningLoop) return
        runExecutionLoop()
    }

    private fun runExecutionLoop() {
        if (stepsList.isEmpty()) {
            Toast.makeText(applicationContext, "السكربت لا يحتوي على أي خطوات!", Toast.LENGTH_SHORT).show()
            return
        }

        isRunningLoop = true
        miniStatusText.text = "▶ جاري تشغيل: $currentScriptName"

        executionJob = scope.launch {
            var currentCycle = 0
            while (isRunningLoop) {
                if (loopCountLimit > 0 && currentCycle >= loopCountLimit) break

                val startIndex = if (currentExecutionIndex in 0 until stepsList.size) currentExecutionIndex else 0

                for (i in startIndex until stepsList.size) {
                    if (!isRunningLoop) {
                        currentExecutionIndex = i
                        break
                    }

                    currentExecutionIndex = i
                    val step = stepsList[i]
                    if (!step.enabled) continue

                    mainHandler.post {
                        miniStatusText.text = "⚡ تنفيذ [${i + 1}/${stepsList.size}]: ${step.name}"
                    }

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

                if (isRunningLoop) {
                    currentExecutionIndex = 0
                    currentCycle++
                }
            }
            isRunningLoop = false
            mainHandler.post {
                miniStatusText.text = "⏹ متوقف: $currentScriptName"
                renderMainJobsView()
            }
        }
    }

    private fun stopExecution() {
        isRunningLoop = false
        executionJob?.cancel()
        executionJob = null
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
        ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
        renderMainJobsView()
        openJsEditor(stepsList.size - 1)
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
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        suitePanel.addView(title)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (210 * density).toInt()
            )
            isVerticalScrollBarEnabled = false
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        targets.forEach { (name, bmp) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#14FFFFFF"))
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
                setOnClickListener {
                    addGalleryTarget(bmp)
                }
            }

            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
            }
            val txt = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(img)
            row.addView(txt)
            container.addView(row)
        }

        scroll.addView(container)
        suitePanel.addView(scroll)

        val btnBack = Button(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(1, Color.parseColor("#1FFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
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
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        fun makeGlassInput(hintStr: String, textStr: String, isNum: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintStr
                setText(textStr)
                if (isNum) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                textSize = 13f
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(Color.parseColor("#1AFFFFFF"))
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            }
        }

        val editName = makeGlassInput("اسم الأكشن", step.name)
        val editDelay = makeGlassInput("التأخير الزمني ms", step.delayAfterMs.toString(), true)
        val editRepeat = makeGlassInput("مرات التكرار (Repeat Count)", step.repeatCount.toString(), true)

        suitePanel.addView(editName)
        suitePanel.addView(editDelay)
        suitePanel.addView(editRepeat)

        if (step.thumbnail != null) {
            val txtTraining = TextView(this).apply {
                text = "🧠 تدريب الذكاء الاصطناعي (${step.trainedVariations.size} زوايا مدربة):"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 11f
                setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
            }
            suitePanel.addView(txtTraining)

            val horizontalScroll = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (46 * density).toInt()
                )
                isVerticalScrollBarEnabled = false
            }
            val trainingContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            step.trainedVariations.forEachIndexed { vIndex, vBmp ->
                val vImg = ImageView(this).apply {
                    setImageBitmap(vBmp)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = 6 * density
                        setColor(Color.BLACK)
                        setStroke(1, Color.parseColor("#FFD700"))
                    }
                    layoutParams = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt()).apply {
                        marginEnd = (6 * density).toInt()
                    }
                    setOnLongClickListener {
                        step.trainedVariations.removeAt(vIndex)
                        ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
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
                stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(Color.parseColor("#FFD700"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (38 * density).toInt()
                ).apply { topMargin = (6 * density).toInt() }
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
            setTextColor(Color.parseColor("#042614"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#00E676"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
            setOnClickListener {
                step.name = editName.text.toString()
                step.delayAfterMs = editDelay.text.toString().toLongOrNull() ?: 500L
                step.repeatCount = editRepeat.text.toString().toIntOrNull() ?: 1
                ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
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
                        Toast.makeText(applicationContext, "✕ لم يُعثر على الهدف (تطابق ${match.currentSimilarity}%)", Toast.LENGTH_SHORT).show()
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
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        val editSim = EditText(this).apply {
            setText(step.similarityPercent.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() }
        }
        suitePanel.addView(editSim)

        val cardFull = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#262214"))
                setStroke(1, Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val icon = TextView(this).apply {
            text = "● "
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
        }
        val labelTextView = TextView(this).apply {
            text = "Full Screen (كامل الشاشة للبحث عن الهدف)"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        cardFull.addView(icon)
        cardFull.addView(labelTextView)
        suitePanel.addView(cardFull)

        val btnSave = Button(this).apply {
            text = "حفظ"
            setTextColor(Color.parseColor("#0B0C10"))
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply { topMargin = (12 * density).toInt() }
            setOnClickListener {
                step.similarityPercent = editSim.text.toString().toIntOrNull() ?: 70
                step.detectScope = DetectScope.FULL_SCREEN
                ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun openJsEditor(index: Int) {
        setKeyboardFocusable(true)
        setPanelDimensions(true)
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
            typeface = Typeface.DEFAULT_BOLD
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
            setTextColor(Color.parseColor("#38BDF8"))
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.parseColor("#FA060910"))
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * density).toInt()
            )
            addTextChangedListener(CodeSyntaxHighlighter())
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val editActionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (34 * density).toInt()
            ).apply { bottomMargin = (6 * density).toInt() }
        }

        fun makeEditToolBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F1F5F9"))
                background = GradientDrawable().apply {
                    cornerRadius = 8 * density
                    setColor(Color.parseColor("#1FFFFFFF"))
                    setStroke(1, Color.parseColor("#26FFFFFF"))
                }
                layoutParams = LinearLayout.LayoutParams(0, (28 * density).toInt(), 1f).apply {
                    marginEnd = (4 * density).toInt()
                }
                setOnClickListener { onClick() }
            }
        }

        editActionsBar.addView(makeEditToolBtn("📋 لصق") {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val pasteText = clip.getItemAt(0).text?.toString() ?: ""
                val start = max(0, editCode.selectionStart)
                val end = max(0, editCode.selectionEnd)
                editCode.text.replace(min(start, end), max(start, end), pasteText)
            }
        })

        editActionsBar.addView(makeEditToolBtn("📑 نسخ") {
            val start = editCode.selectionStart
            val end = editCode.selectionEnd
            val selectedText = if (start != end) {
                editCode.text.substring(min(start, end), max(start, end))
            } else {
                editCode.text.toString()
            }
            if (selectedText.isNotEmpty()) {
                clipboard.setPrimaryClip(ClipData.newPlainText("ApexCode", selectedText))
                Toast.makeText(this@OverlayService, "تم النسخ إلى الحافظة!", Toast.LENGTH_SHORT).show()
            }
        })

        editActionsBar.addView(makeEditToolBtn("🔲 تحديد الكل") {
            editCode.selectAll()
        })

        editActionsBar.addView(makeEditToolBtn("⌫ مسح") {
            editCode.setText("")
        })

        suitePanel.addView(editActionsBar)

        fun insertSnippet(snippet: String) {
            val start = max(0, editCode.selectionStart)
            val end = max(0, editCode.selectionEnd)
            editCode.text.replace(min(start, end), max(start, end), snippet)
        }

        val toolbarScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            ).apply { bottomMargin = (8 * density).toInt() }
            isVerticalScrollBarEnabled = false
        }
        val toolbarContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun makeSnippetBtn(label: String, code: String): Button {
            return Button(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(Color.parseColor("#FFD700"))
                stateListAnimator = null
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(Color.parseColor("#1AFFE082"))
                    setStroke(1, Color.parseColor("#4DFFD700"))
                }
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * density).toInt()
                ).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { insertSnippet(code) }
            }
        }

        toolbarContainer.addView(makeSnippetBtn("👆 click", "click(x, y);\n"))
        toolbarContainer.addView(makeSnippetBtn("👆 clickImage", "clickImage(\"${step.targetImageName}\", 70);\n"))
        toolbarContainer.addView(makeSnippetBtn("👁️ findImage", "let res = findImage(\"${step.targetImageName}\", 70);\nif (res.found) {\n    click(res.x, res.y);\n}\n"))
        toolbarContainer.addView(makeSnippetBtn("⏱️ sleep", "sleep(500);\n"))
        toolbarContainer.addView(makeSnippetBtn("↔️ swipe", "swipe(x, y, x, y - 400, 300);\n"))
        toolbarContainer.addView(makeSnippetBtn("⏳ longClick", "longClick(x, y, 800);\n"))
        toolbarContainer.addView(makeSnippetBtn("🎲 random", "let r = random(200, 500);\nsleep(r);\n"))
        toolbarContainer.addView(makeSnippetBtn("💬 toast", "toast(\"تم التنفيذ بنجاح!\");\n"))
        toolbarContainer.addView(makeSnippetBtn("🔙 Back", "pressBack();\n"))
        toolbarContainer.addView(makeSnippetBtn("🏠 Home", "pressHome();\n"))
        toolbarContainer.addView(makeSnippetBtn("📑 Recent", "pressRecent();\n"))

        val btnPickTargetName = Button(this).apply {
            text = "📁 اسم هدف"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (28 * density).toInt()
            )
            setOnClickListener {
                insertSnippet("\"${step.targetImageName}\"")
            }
        }
        toolbarContainer.addView(btnPickTargetName)

        toolbarScroll.addView(toolbarContainer)
        suitePanel.addView(toolbarScroll)
        suitePanel.addView(editCode)

        val btnSave = Button(this).apply {
            text = "💾 حفظ الكود"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
            setOnClickListener {
                step.customScript = editCode.text.toString()
                ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun showGlobalJobSettingsDialog() {
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "⚙️ إعدادات المهمة العامة"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        val txtLoop = TextView(this).apply {
            text = "عدد مرات تكرار المهمة (0 = تكرار لا نهائي):"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        val editLoop = EditText(this).apply {
            setText(loopCountLimit.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(1, Color.parseColor("#26FFFFFF"))
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        suitePanel.addView(txtLoop)
        suitePanel.addView(editLoop)

        val btnSave = Button(this).apply {
            text = "حفظ والعودة"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (42 * density).toInt()
            ).apply { topMargin = (12 * density).toInt() }
            setOnClickListener {
                loopCountLimit = editLoop.text.toString().toIntOrNull() ?: 0
                ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
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
            ScriptStorage.saveScript(currentScriptName, stepsList, loopCountLimit)
            renderMainJobsView()
        }
    }

    fun togglePanelExpansion(expand: Boolean) {
        if (expand) {
            bubbleView.visibility = View.GONE
            miniControllerView.visibility = View.GONE
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
