package com.apex.nativeauto.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object ImageLibrary {
    val targetImages = mutableMapOf<String, Bitmap>()
    private var storageDir: File? = null

    fun init(context: Context) {
        storageDir = File(context.filesDir, "macro_targets").apply { mkdirs() }
        loadAllFromDisk()
    }

    fun saveTarget(name: String, bitmap: Bitmap) {
        targetImages[name] = bitmap
        storageDir?.let { dir ->
            try {
                val file = File(dir, "$name.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getTarget(name: String): Bitmap? = targetImages[name]

    fun renameTarget(oldName: String, newName: String) {
        val bmp = targetImages.remove(oldName) ?: return
        targetImages[newName] = bmp
        storageDir?.let { dir ->
            File(dir, "$oldName.png").delete()
            saveTarget(newName, bmp)
        }
    }

    fun deleteTarget(name: String) {
        targetImages.remove(name)
        storageDir?.let { dir ->
            File(dir, "$name.png").delete()
        }
    }

    private fun loadAllFromDisk() {
        storageDir?.listFiles { file -> file.extension.lowercase() == "png" }?.forEach { file ->
            val name = file.nameWithoutExtension
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                targetImages[name] = bmp
            }
        }
    }
}
