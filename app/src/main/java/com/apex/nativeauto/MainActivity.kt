package com.apex.nativeauto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.apex.nativeauto.capture.ImageMatcher
import com.apex.nativeauto.capture.ScreenCaptureManager
import com.apex.nativeauto.overlay.OverlayService
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureManager.init(this, result.resultCode, result.data!!)
            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "تم تفعيل محرك الذكاء الاصطناعي بنجاح!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "يلزم السماح بالتقاط الشاشة لاقتطاع الصور وفحصها", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // نسخ وتهيئة نموذج YOLOv12 تلقائياً إلى ذاكرة الهاتف
        prepareYOLOModel()

        val btnAccessibility = findViewById<Button>(R.id.btn_enable_accessibility)
        val btnStartOverlay = findViewById<Button>(R.id.btn_start_overlay)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "قم بتفعيل خدمة ApexAuto Native", Toast.LENGTH_SHORT).show()
        }

        btnStartOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mpManager.createScreenCaptureIntent())
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
