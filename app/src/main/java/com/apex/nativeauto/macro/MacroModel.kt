package com.apex.nativeauto.macro

import android.graphics.Bitmap
import android.graphics.RectF

enum class ActionType {
    CLICK_IMAGE,
    TAP_COORDINATE,
    JS_SCRIPT
}

enum class DetectScope {
    CAPTURED_LOCATION,
    CUSTOM_REGION,
    FULL_SCREEN
}

data class MacroStep(
    var stepNumber: Int = 1,
    var name: String = "Action 1",
    var type: ActionType = ActionType.CLICK_IMAGE,
    var targetArea: RectF = RectF(200f, 600f, 500f, 900f),
    var targetImageName: String = "target_1",
    var thumbnail: Bitmap? = null,
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
