package com.apex.nativeauto.picker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.apex.nativeauto.overlay.OverlayService

class TransparentPickerActivity : ComponentActivity() {

    private var imageHandled = false

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap: Bitmap? = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        imageHandled = true
                        val isTrainingMode = intent?.getBooleanExtra("IS_TRAINING_MODE", false) ?: false
                        val targetStepIndex = intent?.getIntExtra("TARGET_STEP_INDEX", -1) ?: -1

                        if (isTrainingMode && targetStepIndex != -1) {
                            OverlayService.addTrainingVariation(targetStepIndex, bitmap)
                        } else {
                            OverlayService.addGalleryTarget(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // إخفاء نافذة التطبيق العائمة تلقائياً لفتح معرض النظام بسلاسة
        OverlayService.instance?.hideOverlayForPicker()
        galleryLauncher.launch("image/*")
    }

    override fun onDestroy() {
        super.onDestroy()
        // في حال خرج المستخدم من المعرض دون اختيار صورة، تعود النافذة العائمة فوراً
        if (!imageHandled) {
            OverlayService.instance?.restoreOverlayAfterPicker()
        }
    }
}
