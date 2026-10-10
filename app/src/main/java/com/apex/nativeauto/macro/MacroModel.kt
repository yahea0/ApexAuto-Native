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
    CUSTOM_REGION,     // منطقة مخصصة محددة بإطار
    FULL_SCREEN        // كامل الشاشة
}

data class MacroStep(
    var stepNumber: Int,
    var name: String = "Action 1",
    var type: ActionType = ActionType.CLICK_IMAGE,
    var targetArea: RectF = RectF(400f, 800f, 700f, 1000f),
    var targetImageName: String = "target_1", // اسم الصورة لاستدعائها في الكود
    var thumbnail: Bitmap? = null,
    var similarityPercent: Int = 70,          // نسبة التطابق الافتراضية
    var detectScope: DetectScope = DetectScope.CAPTURED_LOCATION,
    var customRegion: RectF? = null,          // منطقة البحث المخصصة
    var repeatCount: Int = 1,                 // عدد مرات الضغط
    var delayBeforeMs: Long = 0L,
    var delayAfterMs: Long = 500L,
    var customScript: String = "// كود JavaScript مخصص لهذا الأكشن\nclick(x, y);\nsleep(300);",
    var enabled: Boolean = true
)
