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
import com.apex.nativeauto.overlay.OverlayService
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_RESULT_CODE", result.resultCode)
                putExtra("EXTRA_DATA", result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
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
        prepareYOLOModel()

        val btnAccessibility = findViewById<Button?>(R.id.btn_enable_accessibility)
        val btnStartOverlay = findViewById<Button?>(R.id.btn_start_overlay)
        val btnOpenLibrary = findViewById<Button?>(R.id.btn_open_library)

        btnAccessibility?.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "قم بتفعيل خدمة ApexAuto Native", Toast.LENGTH_SHORT).show()
        }

        btnStartOverlay?.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mpManager.createScreenCaptureIntent())
        }

        btnOpenLibrary?.setOnClickListener {
            openLibraryManagerDialog()
        }
    }

    // نافذة استعراض المستودع وتعديل أسماء الصور وحذفها
    private fun openLibraryManagerDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(Color.parseColor("#F50D0E15"))
                setStroke((2 * density).toInt(), Color.parseColor("#D4AF37"))
            }
            background = bg
            layoutParams = ViewGroup.LayoutParams((320 * density).toInt(), (400 * density).toInt())
        }

        val title = TextView(this).apply {
            text = "📁 مستودع الصور وتدريب الذكاء الاصطناعي"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        root.addView(title)

        val btnAdd = Button(this).apply {
            text = "➕ استيراد صورة جديدة من المعرض"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
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
            ).apply { topMargin = (10 * density).toInt() }
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        ImageLibrary.targetImages.forEach { (name, bmp) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                val rBg = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(Color.parseColor("#151722"))
                    setStroke(1, Color.parseColor("#333A4D"))
                }
                background = rBg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            val img = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                    marginEnd = (8 * density).toInt()
                }
            }

            // ضغطة على الاسم تفتح نافذة إعادة تسميته فورياً
            val txt = TextView(this).apply {
                text = "$name ✏️"
                setTextColor(Color.WHITE)
                textSize = 12f
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
                textSize = 14f
                setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
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
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F222E"))
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
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.parseColor("#151722"))
                setStroke(2, Color.parseColor("#FFD700"))
            }
            background = bg
        }

        val t = TextView(this).apply {
            text = "تعديل اسم الهدف"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 14f
        }
        layout.addView(t)

        val input = EditText(this).apply {
            setText(oldName)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0B0C10"))
            setPadding(16, 12, 16, 12)
        }
        layout.addView(input)

        val btn = Button(this).apply {
            text = "تأكيد وتحديث في كل مكان"
            setTextColor(Color.parseColor("#0B0C10"))
            setBackgroundColor(Color.parseColor("#FFD700"))
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
