package com.apex.nativeauto

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.apex.nativeauto.capture.ImageMatcher
import com.apex.nativeauto.macro.ImageLibrary
import com.apex.nativeauto.macro.ScriptStorage
import com.apex.nativeauto.overlay.OverlayService
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private var pendingScriptName: String? = null
    private var pendingScriptMode: String? = null

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_DATA", result.data)
                pendingScriptName?.let {
                    putExtra(OverlayService.EXTRA_SCRIPT_NAME, it)
                    action = pendingScriptMode
                }
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            pendingScriptName = null
            pendingScriptMode = null
            Toast.makeText(this, "تم تفعيل محرك الذكاء الاصطناعي بنجاح!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "يلزم السماح بالتقاط الشاشة لاقتطاع الصور وفحصها", Toast.LENGTH_LONG).show()
        }
    }

    private val libraryAddLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        val count = ImageLibrary.targetImages.size + 1
                        val name = "target_$count"
                        ImageLibrary.saveTarget(name, bmp)
                        Toast.makeText(this, "تمت إضافة الهدف ($name) بنجاح!", Toast.LENGTH_SHORT).show()
                        openLibraryManagerDialog()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ImageLibrary.init(this)
        ScriptStorage.init(this)
        prepareYOLOModel()

        val btnAccessibility = findViewById<Button?>(R.id.btn_enable_accessibility)
        val btnStartOverlay = findViewById<Button?>(R.id.btn_start_overlay)
        val btnOpenLibrary = findViewById<Button?>(R.id.btn_open_library)
        val btnOpenScripts = findViewById<Button?>(R.id.btn_open_scripts_repository)

        btnAccessibility?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "قم بتفعيل خدمة ApexAuto Native", Toast.LENGTH_SHORT).show()
        }

        btnStartOverlay?.setOnClickListener {
            startOverlayWithAction(null, null)
        }

        btnOpenLibrary?.setOnClickListener {
            openLibraryManagerDialog()
        }

        btnOpenScripts?.setOnClickListener {
            openScriptsRepositoryDialog()
        }
    }

    private fun startOverlayWithAction(scriptName: String?, actionType: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (OverlayService.isRunning) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = actionType
                putExtra(OverlayService.EXTRA_SCRIPT_NAME, scriptName)
            }
            startService(intent)
        } else {
            pendingScriptName = scriptName
            pendingScriptMode = actionType
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    private fun openScriptsRepositoryDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F20F1626"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#4DFFD700"))
            }
            layoutParams = ViewGroup.LayoutParams((340 * density).toInt(), (460 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "📜 مستودع السكربتات (Scripts Repository)"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(title)

        val scripts = ScriptStorage.getAllScripts()

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (scripts.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "لا توجد سكربتات محفوظة بعد.\nيمكنك حفظ السكربت الحالي من الاستوديو العائم عبر زر 💾"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, (40 * density).toInt(), 0, 0)
            }
            container.addView(emptyTxt)
        } else {
            scripts.forEach { sName ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 14 * density
                        setColor(Color.parseColor("#14FFFFFF"))
                        setStroke(1, Color.parseColor("#26FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * density).toInt() }
                }

                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val txtName = TextView(this).apply {
                    text = "⚡ $sName"
                    setTextColor(Color.parseColor("#FFD700"))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnDelete = TextView(this).apply {
                    text = "🗑"
                    textSize = 14f
                    setPadding((8 * density).toInt(), 0, 0, 0)
                    setOnClickListener {
                        ScriptStorage.deleteScript(sName)
                        dialog.dismiss()
                        openScriptsRepositoryDialog()
                    }
                }

                topRow.addView(txtName)
                topRow.addView(btnDelete)
                card.addView(topRow)

                val actionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (38 * density).toInt()
                    ).apply { topMargin = (8 * density).toInt() }
                }

                val btnEdit = Button(this).apply {
                    text = "✏️ تعديل السكربت"
                    setTextColor(Color.WHITE)
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    stateListAnimator = null
                    background = GradientDrawable().apply {
                        cornerRadius = 10 * density
                        setColor(Color.parseColor("#1FFFFFFF"))
                        setStroke(1, Color.parseColor("#33FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    setOnClickListener {
                        startOverlayWithAction(sName, OverlayService.ACTION_EDIT_SCRIPT)
                        dialog.dismiss()
                    }
                }

                val btnRun = Button(this).apply {
                    text = "▶️ تشغيل السكربت"
                    setTextColor(Color.parseColor("#042614"))
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    stateListAnimator = null
                    background = GradientDrawable().apply {
                        cornerRadius = 10 * density
                        setColor(Color.parseColor("#00E676"))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = (4 * density).toInt()
                    }
                    setOnClickListener {
                        startOverlayWithAction(sName, OverlayService.ACTION_RUN_MINI_CONTROLLER)
                        dialog.dismiss()
                    }
                }

                actionsRow.addView(btnEdit)
                actionsRow.addView(btnRun)
                card.addView(actionsRow)

                container.addView(card)
            }
        }

        scroll.addView(container)
        root.addView(scroll)

        val btnClose = Button(this).apply {
            text = "إغلاق"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(btnClose)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun openLibraryManagerDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(Color.parseColor("#F20F1626"))
                setStroke((1.2f * density).toInt(), Color.parseColor("#4DFFD700"))
            }
            layoutParams = ViewGroup.LayoutParams((330 * density).toInt(), (440 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "📁 مستودع الصور والأهداف الذكية"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * density).toInt())
        }
        root.addView(title)

        val btnAdd = Button(this).apply {
            text = "➕ استيراد صورة جديدة من المعرض"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            setOnClickListener {
                dialog.dismiss()
                libraryAddLauncher.launch("image/*")
            }
        }
        root.addView(btnAdd)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            ).apply { topMargin = (12 * density).toInt() }
            isVerticalScrollBarEnabled = false
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        ImageLibrary.targetImages.forEach { (name, bmp) ->
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
                ).apply { topMargin = (8 * density).toInt() }
            }

            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((42 * density).toInt(), (42 * density).toInt()).apply {
                    marginEnd = (10 * density).toInt()
                }
            }

            val txt = TextView(this).apply {
                text = "$name  ✏️"
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    showRenameDialog(name) { newName ->
                        ImageLibrary.renameTarget(name, newName)
                        OverlayService.notifyTargetRenamed(name, newName)
                        dialog.dismiss()
                        openLibraryManagerDialog()
                    }
                }
            }

            val btnDel = TextView(this).apply {
                text = "🗑"
                textSize = 15f
                setPadding((10 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener {
                    ImageLibrary.deleteTarget(name)
                    dialog.dismiss()
                    openLibraryManagerDialog()
                }
            }

            row.addView(img)
            row.addView(txt)
            row.addView(btnDel)
            container.addView(row)
        }

        scroll.addView(container)
        root.addView(scroll)

        val btnClose = Button(this).apply {
            text = "إغلاق"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#14FFFFFF"))
                setStroke(1, Color.parseColor("#1FFFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply { topMargin = (10 * density).toInt() }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(btnClose)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showRenameDialog(oldName: String, onRenamed: (String) -> Unit) {
        val d = Dialog(this)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val density = resources.displayMetrics.density

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.parseColor("#F50D1322"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#4DFFD700"))
            }
        }

        val t = TextView(this).apply {
            text = "تعديل اسم الهدف"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        layout.addView(t)

        val input = EditText(this).apply {
            setText(oldName)
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#1FFFFFFF"))
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }
        layout.addView(input)

        val btn = Button(this).apply {
            text = "تأكيد وتحديث في كل مكان"
            setTextColor(Color.parseColor("#0B0C10"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#FFD700"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt()
            ).apply { topMargin = (14 * density).toInt() }
            setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    onRenamed(newName)
                    d.dismiss()
                }
            }
        }
        layout.addView(btn)
        d.setContentView(layout)
        d.show()
    }

    private fun prepareYOLOModel() {
        try {
            val modelFile = File(filesDir, "yolov12n.onnx")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                assets.open("yolov12n.onnx").use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            ImageMatcher.setupYOLOModel(modelFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
