package com.apex.nativeauto.overlay

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: FrameLayout
    private lateinit var bubbleView: TextView
    private lateinit var suitePanel: LinearLayout
    private var cropOverlayView: CropSelectorView? = null

    private val stepsList = mutableListOf<MacroStep>()
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
            .setContentText("محرك الأتمتة والتقاط البكسلات يعمل في الخلفية")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
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
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

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

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * density).toInt()
            ).apply { topMargin = (8 * density).toInt(); bottomMargin = (8 * density).toInt() }
        }

        val cardsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        stepsList.forEachIndexed { index, step ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                val cBg = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#C084FC"))
                }
                background = cBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                setOnClickListener { showStepContextMenu(index) }
            }

            val rowTop = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val txtClick = TextView(this).apply {
                text = "Click "
                setTextColor(Color.parseColor("#1E1B4B"))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rowTop.addView(txtClick)

            if (step.thumbnail != null) {
                val thumb = ImageView(this).apply {
                    setImageBitmap(step.thumbnail)
                    val s = (22 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s)
                }
                rowTop.addView(thumb)
            }

            val txtDelay = TextView(this).apply {
                text = " [X1] [Delay ${step.delayBeforeMs}ms/${step.delayAfterMs}ms]"
                setTextColor(Color.parseColor("#312E81"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            rowTop.addView(txtDelay)

            val rowSub = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (4 * density).toInt(), 0, 0)
            }

            val txtBranch = TextView(this).apply {
                text = "└ Image "
                setTextColor(Color.parseColor("#1E1B4B"))
                textSize = 12f
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
                text = " [Appear] [${step.similarityPercent}%] [$scopeName]"
                setTextColor(Color.parseColor("#312E81"))
                textSize = 10f
            }
            rowSub.addView(txtCondition)

            card.addView(rowTop)
            card.addView(rowSub)
            cardsContainer.addView(card)
        }

        scrollView.addView(cardsContainer)
        suitePanel.addView(scrollView)

        val btnAddAction = Button(this).apply {
            text = "➕ Click Image (اقتطاع صورة جديدة)"
            setTextColor(Color.WHITE)
            textSize = 12f
            val bg = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#7C3AED"))
            }
            background = bg
            setOnClickListener { showRubberBandSelector() }
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

    private fun showStepContextMenu(index: Int) {
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "إعدادات الأكشن: ${step.name}"
            setTextColor(Color.parseColor("#A855F7"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        fun createMenuItem(title: String, icon: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = "$icon  $title"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                val bg = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(Color.parseColor("#1E293B"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (42 * density).toInt()
                ).apply { topMargin = (5 * density).toInt() }
                setOnClickListener { onClick() }
            }
        }

        suitePanel.addView(createMenuItem("Edit Detect Location", "📍") {
            showDetectLocationDialog(index)
        })

        suitePanel.addView(createMenuItem("Edit Similarity %", "🎯") {
            step.similarityPercent = if (step.similarityPercent == 70) 85 else 70
            Toast.makeText(this, "تم تغيير نسبة التطابق إلى ${step.similarityPercent}%", Toast.LENGTH_SHORT).show()
            renderMainJobsView()
        })

        suitePanel.addView(createMenuItem("Test Condition", "▶") {
            testCurrentCondition(step)
        })

        suitePanel.addView(createMenuItem("Delete Action", "🗑") {
            stepsList.removeAt(index)
            renderMainJobsView()
        })

        val btnBack = Button(this).apply {
            text = "رجوع"
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { renderMainJobsView() }
        }
        suitePanel.addView(btnBack)
    }

    private fun showDetectLocationDialog(index: Int) {
        val step = stepsList[index]
        suitePanel.removeAllViews()
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "Detect Location"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        suitePanel.addView(title)

        val radioGroup = RadioGroup(this)

        val rbCaptured = RadioButton(this).apply {
            text = "Captured Location\n[${step.targetArea.left.toInt()}, ${step.targetArea.top.toInt()}, ${step.targetArea.width().toInt()}, ${step.targetArea.height().toInt()}] [1080x2400]"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = step.detectScope == DetectScope.CAPTURED_LOCATION
        }
        val rbCustom = RadioButton(this).apply {
            text = "Custom Region"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = step.detectScope == DetectScope.CUSTOM_REGION
        }
        val rbFull = RadioButton(this).apply {
            text = "Full Screen"
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = step.detectScope == DetectScope.FULL_SCREEN
        }

        radioGroup.addView(rbCaptured)
        radioGroup.addView(rbCustom)
        radioGroup.addView(rbFull)
        suitePanel.addView(radioGroup)

        val desc = TextView(this).apply {
            text = "Detection will run at exactly where the template object is captured thus achieve the highest performance."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        }
        suitePanel.addView(desc)

        val btnSave = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.parseColor("#C084FC"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                step.detectScope = when {
                    rbCaptured.isChecked -> DetectScope.CAPTURED_LOCATION
                    rbCustom.isChecked -> DetectScope.CUSTOM_REGION
                    else -> DetectScope.FULL_SCREEN
                }
                renderMainJobsView()
            }
        }
        suitePanel.addView(btnSave)
    }

    private fun testCurrentCondition(step: MacroStep) {
        val currentScreen = ScreenCaptureManager.captureCurrentScreen()
        if (currentScreen != null) {
            val result = ImageMatcher.findTarget(step, currentScreen)
            Toast.makeText(
                this,
                if (result.isMatched) "✓ تم العثور على الهدف! بنسبة تطابق ${result.currentSimilarity}%"
                else "✕ لم يتم العثور على الهدف (التطابق: ${result.currentSimilarity}%)",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "تعذر التقاط الشاشة", Toast.LENGTH_SHORT).show()
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
            onConfirm = { selectedRect, _ ->
                val realCapturedBitmap = ScreenCaptureManager.cropAreaFromScreen(selectedRect)

                val nextNum = stepsList.size + 1
                val newStep = MacroStep(
                    stepNumber = nextNum,
                    name = "Click Image ($nextNum)",
                    type = ActionType.CLICK_IMAGE,
                    targetArea = RectF(selectedRect),
                    thumbnail = realCapturedBitmap,
                    similarityPercent = 70,
                    detectScope = DetectScope.CAPTURED_LOCATION
                )
                stepsList.add(newStep)

                Toast.makeText(this, "تم التقاط بكسلات الهدف الحقيقية بنجاح!", Toast.LENGTH_SHORT).show()
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

                    val screen = ScreenCaptureManager.captureCurrentScreen()
                    if (screen != null) {
                        val match = ImageMatcher.findTarget(step, screen)
                        if (match.isMatched && match.targetCenter != null) {
                            ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
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
        if (expand) {
            bubbleView.visibility = View.GONE
            suitePanel.visibility = View.VISIBLE
            renderMainJobsView()
        } else {
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
