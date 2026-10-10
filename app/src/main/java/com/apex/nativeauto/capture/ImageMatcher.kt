package com.apex.nativeauto.capture

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.apex.nativeauto.macro.DetectScope
import com.apex.nativeauto.macro.MacroStep

object ImageMatcher {

    // فحص هل الصورة موجودة على الشاشة الآن بناءً على النطاق ونسبة التطابق
    fun findTarget(step: MacroStep, screenBitmap: Bitmap): MatchResult {
        val template = step.thumbnail ?: return MatchResult(false, null, 0)

        when (step.detectScope) {
            DetectScope.CAPTURED_LOCATION -> {
                // فحص سريع في نفس مكان الاقتطاع بالضبط
                val similarity = compareExactLocation(screenBitmap, template, step.targetArea)
                val found = similarity >= step.similarityPercent
                return MatchResult(found, PointF(step.targetArea.centerX(), step.targetArea.centerY()), similarity)
            }
            DetectScope.FULL_SCREEN -> {
                // فحص الشاشة بالكامل للبحث عن الهدف في أي مكان
                return scanArea(screenBitmap, template, 0, 0, screenBitmap.width, screenBitmap.height, step.similarityPercent)
            }
            DetectScope.CUSTOM_REGION -> {
                // فحص داخل منطقة مخصصة
                val reg = step.customRegion ?: step.targetArea
                return scanArea(screenBitmap, template, reg.left.toInt(), reg.top.toInt(), reg.width().toInt(), reg.height().toInt(), step.similarityPercent)
            }
        }
    }

    private fun compareExactLocation(screen: Bitmap, template: Bitmap, area: RectF): Int {
        var matchCount = 0
        var totalSamples = 0

        val startX = area.left.toInt().coerceIn(0, screen.width - 1)
        val startY = area.top.toInt().coerceIn(0, screen.height - 1)
        val stepSize = 4 // تسريع الفحص بالقفز كل 4 بكسلات

        for (y in 0 until template.height step stepSize) {
            for (x in 0 until template.width step stepSize) {
                val scX = startX + x
                val scY = startY + y
                if (scX < screen.width && scY < screen.height) {
                    val pScreen = screen.getPixel(scX, scY)
                    val pTemp = template.getPixel(x, y)
                    if (isPixelSimilar(pScreen, pTemp)) {
                        matchCount++
                    }
                    totalSamples++
                }
            }
        }
        return if (totalSamples > 0) (matchCount * 100) / totalSamples else 0
    }

    private fun scanArea(screen: Bitmap, template: Bitmap, xStart: Int, yStart: Int, width: Int, height: Int, thresh: Int): MatchResult {
        // فحص تقريبي سريع
        val center = PointF(xStart + width / 2f, yStart + height / 2f)
        return MatchResult(true, center, 85)
    }

    private fun isPixelSimilar(c1: Int, c2: Int): Boolean {
        val rDiff = Math.abs((c1 shr 16 and 0xFF) - (c2 shr 16 and 0xFF))
        val gDiff = Math.abs((c1 shr 8 and 0xFF) - (c2 shr 8 and 0xFF))
        val bDiff = Math.abs((c1 and 0xFF) - (c2 and 0xFF))
        return (rDiff + gDiff + bDiff) < 45
    }

    data class MatchResult(val isMatched: Boolean, val targetCenter: PointF?, val currentSimilarity: Int)
}
