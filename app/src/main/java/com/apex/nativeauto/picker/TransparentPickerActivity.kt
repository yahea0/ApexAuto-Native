package com.apex.nativeauto.picker

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.apex.nativeauto.overlay.OverlayService

class TransparentPickerActivity : AppCompatActivity() {

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        val isTrainingMode = intent.getBooleanExtra("IS_TRAINING_MODE", false)
                        val targetStepIndex = intent.getIntExtra("TARGET_STEP_INDEX", -1)

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
        finish() // إغلاق فوري للعودة للعبة
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryLauncher.launch("image/*")
    }
}
