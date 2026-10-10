package com.apex.nativeauto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
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

    private val mainLibraryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    val count = ImageLibrary.targetImages.size + 1
                    ImageLibrary.saveTarget("target_$count", bmp)
                    Toast.makeText(this, "تمت إضافة الصورة إلى المستودع بنجاح!", Toast.LENGTH_SHORT).show()
                }
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

        // استدعاء آمن لمعرف زر المستودع لمنع أي خطأ تجميع
        val libraryResId = resources.getIdentifier("btn_open_library", "id", packageName)
        if (libraryResId != 0) {
            findViewById<Button?>(libraryResId)?.setOnClickListener {
                mainLibraryLauncher.launch("image/*")
            }
        }
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
