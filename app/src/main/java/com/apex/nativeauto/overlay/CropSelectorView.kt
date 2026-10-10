package com.apex.nativeauto.overlay

import android.content.Context
import android.graphics.RectF
import android.view.View

// تم تعطيل هذا الكائن بعد إلغاء الشريط والاعتماد الكامل على المعرض والمستودع
class CropSelectorView(context: Context) : View(context) {
    var cropRect = RectF(0f, 0f, 0f, 0f)
}
