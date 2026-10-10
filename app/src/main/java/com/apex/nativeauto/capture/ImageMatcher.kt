package com.apex.nativeauto.capture

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.apex.nativeauto.macro.DetectScope
import com.apex.nativeauto.macro.MacroStep

object ImageMatcher {

    init {
        try {
            System.loadLibrary("apex_vision")
            Log.i("Apex_ImageMatcher", "تم تحميل محرك C++ و OpenCV بنجاح عبر JNI!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Apex_ImageMatcher", "فشل تحميل مكتبة C++: ${e.message}")
        }
    }

    // الدالة الأصلية في C++
    private external fun nativeMatchWithOpenCV(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        scope: Int,
        thresholdPercent: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int
    ): FloatArray

    // فحص الهدف بالاعتماد الكامل على C++ و OpenCV
    fun findTarget(step: MacroStep, screenBitmap: Bitmap): MatchResult {
        val template = step.thumbnail ?: return MatchResult(false, null, 0)

        val scopeInt = when (step.detectScope) {
            DetectScope.CAPTURED_LOCATION -> 0
            DetectScope.CUSTOM_REGION -> 1
            DetectScope.FULL_SCREEN -> 2
        }

        val roi = when (step.detectScope) {
            DetectScope.CUSTOM_REGION -> step.customRegion ?: step.targetArea
            else -> step.targetArea
        }

        return try {
            val res = nativeMatchWithOpenCV(
                screenBitmap,
                template,
                scopeInt,
                step.similarityPercent,
                roi.left.toInt(),
                roi.top.toInt(),
                roi.width().toInt(),
                roi.height().toInt()
            )

            val isFound = res[0] > 0.5f
            val targetCenter = PointF(res[1], res[2])
            val confidence = res[3].toInt()

            MatchResult(isFound, targetCenter, confidence)
        } catch (e: Exception) {
            Log.e("Apex_ImageMatcher", "خطأ أثناء مطابقة C++: ${e.message}")
            MatchResult(false, null, 0)
        }
    }

    data class MatchResult(val isMatched: Boolean, val targetCenter: PointF?, val currentSimilarity: Int)
}
