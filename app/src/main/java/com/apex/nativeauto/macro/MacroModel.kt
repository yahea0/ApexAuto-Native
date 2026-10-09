package com.apex.nativeauto.macro

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
    var scriptCode: String = "// اكتب كود جافا سكريبت هنا\nif (step == 1) {\n    click(x, y);\n    sleep(400);\n}",
    var delayAfterMs: Long = 500L,
    var enabled: Boolean = true
)
