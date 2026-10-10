package com.apex.nativeauto.macro

import android.graphics.Bitmap
import android.graphics.RectF

enum class ActionType {
    CLICK_IMAGE,    // النقر على صورة
    TAP_COORDINATE, // نقرة إحداثيات عادية
    JS_SCRIPT       // كود جافا سكريبت شرطي
}

enum class DetectScope {
    CAPTURED_LOCATION, // المكان الافتراضي المقتطع
    CUSTOM_REGION,     // منطقة مخصصة
    FULL_SCREEN        // كامل الشاشة
}

data class MacroStep(
    val stepNumber: Int,
    var name: String,
    var type: ActionType,
    var targetArea: RectF = RectF(402f, 781f, 723f, 1019f),
    var thumbnail: Bitmap? = null,
    var similarityPercent: Int = 70, // نسبة التطابق الافتراضية 70%
    var detectScope: DetectScope = DetectScope.CAPTURED_LOCATION,
    var customRegion: RectF? = null,
    var delayBeforeMs: Long = 0L,
    var delayAfterMs: Long = 1000L,
    var enabled: Boolean = true
)
