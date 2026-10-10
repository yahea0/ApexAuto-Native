package com.apex.nativeauto.macro

import android.graphics.Bitmap
import android.graphics.RectF

enum class ActionType {
    TAP,            // نقرة إحداثيات
    IMAGE_TARGET,   // نقرة على صورة/إطار
    JS_SCRIPT       // كود جافا سكريبت شرطي
}

data class MacroStep(
    val stepNumber: Int,
    var name: String,
    var type: ActionType,
    var targetArea: RectF = RectF(500f, 900f, 600f, 1000f),
    var targetName: String = "",        // اسم الهدف للتعرف عليه في كود الجافا سكريبت
    var thumbnail: Bitmap? = null,      // الصورة المصغرة للهدف
    var scriptCode: String = "// كود الأتمتة المخصص\nif (step == 1) {\n    click(x, y);\n    sleep(300);\n}",
    var delayAfterMs: Long = 400L,
    var enabled: Boolean = true
)
