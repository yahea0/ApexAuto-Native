package com.apex.nativeauto.macro

import android.graphics.Bitmap

object ImageLibrary {
    // تخزين الصور بأسمائها
    val targetImages = mutableMapOf<String, Bitmap>()

    fun saveTarget(name: String, bitmap: Bitmap) {
        targetImages[name] = bitmap
    }

    fun getTarget(name: String): Bitmap? {
        return targetImages[name]
    }

    fun renameTarget(oldName: String, newName: String) {
        val bmp = targetImages.remove(oldName)
        if (bmp != null) {
            targetImages[newName] = bmp
        }
    }

    fun deleteTarget(name: String) {
        targetImages.remove(name)
    }
}
