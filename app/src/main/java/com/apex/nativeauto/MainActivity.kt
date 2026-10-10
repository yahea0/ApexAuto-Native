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

    // منتقي الصور من معرض الهاتف
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        OverlayService.addGalleryTarget(bitmap)
                        Toast.makeText(this, "تم استيراد الصورة بنجاح إلى الأكشن!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "فشل استيراد الصورة من المعرض", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        // استقبال طلب فتح المعرض من النافذة العائمة
        if (intent?.getBooleanExtra("OPEN_GALLERY", false) == true) {
            galleryLauncher.launch("image/*")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("OPEN_GALLERY", false) == true) {
            galleryLauncher.launch("image/*")
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
