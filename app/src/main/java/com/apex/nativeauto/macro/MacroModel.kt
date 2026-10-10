package com.apex.nativeauto.macro

import android.graphics.Bitmap
import android.graphics.RectF

enum class ActionType {
    CLICK_IMAGE,    // النقر على صورة
    TAP_COORDINATE, // نقرة إحداثيات عادية
    JS_SCRIPT       // كود جافا سكريبت شرطي
}

enum class DetectScope {
    CAPTURED_LOCATION, // المكان الافتراضي
    CUSTOM_REGION,     // منطقة مخصصة
    FULL_SCREEN        // كامل الشاشة
}

data class MacroStep(
    var stepNumber: Int,
    var name: String = "Action 1",
    var type: ActionType = ActionType.CLICK_IMAGE,
    var targetArea: RectF = RectF(200f, 600f, 500f, 900f),
    var targetImageName: String = "target_1",
    var thumbnail: Bitmap? = null,
    // قائمة الزوايا والأبعاد المتعددة لتدريب الذكاء الاصطناعي على الهدف
    val trainedVariations: MutableList<Bitmap> = mutableListOf(),
    var similarityPercent: Int = 70,
    var detectScope: DetectScope = DetectScope.FULL_SCREEN,
    var customRegion: RectF? = null,
    var repeatCount: Int = 1,
    var delayBeforeMs: Long = 0L,
    var delayAfterMs: Long = 500L,
    var customScript: String = "// كود JavaScript مخصص\nclick(x, y);\nsleep(300);",
    var enabled: Boolean = true
)
