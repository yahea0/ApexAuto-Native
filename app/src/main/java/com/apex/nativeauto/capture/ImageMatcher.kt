package com.apex.nativeauto.capture

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.apex.nativeauto.macro.DetectScope
import com.apex.nativeauto.macro.MacroStep

object ImageMatcher {

    private var isYoloInitialized = false

    init {
        try {
            System.loadLibrary("apex_vision")
            Log.i("Apex_ImageMatcher", "تم تحميل مكتبة C++ بنجاح!")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("Apex_ImageMatcher", "فشل تحميل C++: ${e.message}")
        }
    }

    external fun nativeInitYOLO(modelPath: String): Boolean

    private external fun nativeMatchWithOpenCV(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        scope: Int,
        thresholdPercent: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int
    ): FloatArray

    fun setupYOLOModel(modelPath: String) {
        if (!isYoloInitialized) {
            isYoloInitialized = nativeInitYOLO(modelPath)
        }
    }

    fun findTarget(step: MacroStep, screenBitmap: Bitmap): MatchResult {
        val allTemplates = mutableListOf<Bitmap>()
        step.thumbnail?.let { allTemplates.add(it) }
        allTemplates.addAll(step.trainedVariations)

        if (allTemplates.isEmpty()) return MatchResult(false, null, 0)

        val scopeInt = when (step.detectScope) {
            DetectScope.CAPTURED_LOCATION -> 0
            DetectScope.CUSTOM_REGION -> 1
            DetectScope.FULL_SCREEN -> 2
        }

        val roi = when (step.detectScope) {
            DetectScope.CUSTOM_REGION -> step.customRegion ?: step.targetArea
            else -> step.targetArea
        }

        var bestMatch = MatchResult(false, null, 0)

        for (template in allTemplates) {
            try {
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

                if (isFound) {
                    return MatchResult(true, targetCenter, confidence)
                }

                if (confidence > bestMatch.currentSimilarity) {
                    bestMatch = MatchResult(false, targetCenter, confidence)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return bestMatch
    }

    data class MatchResult(val isMatched: Boolean, val targetCenter: PointF?, val currentSimilarity: Int)
}
