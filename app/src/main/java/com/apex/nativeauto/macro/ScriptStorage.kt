package com.apex.nativeauto.macro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object ScriptStorage {
    private var baseDir: File? = null

    fun init(context: Context) {
        baseDir = File(context.filesDir, "macro_scripts").apply { mkdirs() }
    }

    // حفظ السكربت مع عزل صوره بالكامل داخل مجلد خاص به
    fun saveScript(name: String, steps: List<MacroStep>, loopLimit: Int = 0): Boolean {
        return try {
            val parent = baseDir ?: return false
            // مجلد مخصص ومعزول للسكربت الحالي فقط
            val scriptFolder = File(parent, name).apply { mkdirs() }

            val root = JSONObject().apply {
                put("name", name)
                put("loopLimit", loopLimit)
                put("timestamp", System.currentTimeMillis())

                val stepsArray = JSONArray()
                steps.forEachIndexed { index, step ->
                    val stepNum = index + 1
                    step.stepNumber = stepNum

                    // 1. حفظ صورة الهدف الأساسية في مجلد هذا السكربت فقط
                    val thumbFileName = "thumb_$stepNum.png"
                    if (step.thumbnail != null) {
                        val thumbFile = File(scriptFolder, thumbFileName)
                        FileOutputStream(thumbFile).use { out ->
                            step.thumbnail!!.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }

                    // 2. حفظ الزوايا التدريبية إن وُجدت في نفس المجلد المعزول
                    val varsArray = JSONArray()
                    step.trainedVariations.forEachIndexed { vIdx, vBmp ->
                        val varFileName = "var_${stepNum}_$vIdx.png"
                        val varFile = File(scriptFolder, varFileName)
                        FileOutputStream(varFile).use { out ->
                            vBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        varsArray.put(varFileName)
                    }

                    // ربط اسم الهدف باسم السكربت لمنع التضارب
                    val uniqueTargetName = "${name}_target_$stepNum"
                    step.targetImageName = uniqueTargetName

                    val sObj = JSONObject().apply {
                        put("stepNumber", stepNum)
                        put("name", step.name)
                        put("type", step.type.name)
                        put("targetImageName", uniqueTargetName)
                        put("thumbFile", if (step.thumbnail != null) thumbFileName else "")
                        put("variations", varsArray)
                        put("similarityPercent", step.similarityPercent)
                        put("detectScope", step.detectScope.name)
                        put("repeatCount", step.repeatCount)
                        put("delayBeforeMs", step.delayBeforeMs)
                        put("delayAfterMs", step.delayAfterMs)
                        put("customScript", step.customScript)
                        put("enabled", step.enabled)
                        put("left", step.targetArea.left.toDouble())
                        put("top", step.targetArea.top.toDouble())
                        put("right", step.targetArea.right.toDouble())
                        put("bottom", step.targetArea.bottom.toDouble())
                    }
                    stepsArray.put(sObj)
                }
                put("steps", stepsArray)
            }

            val configFile = File(scriptFolder, "script.json")
            configFile.writeText(root.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // استرجاع السكربت مع قراءة صوره حصراً من مجلده الخاص
    fun loadScript(name: String): Pair<List<MacroStep>, Int>? {
        return try {
            val parent = baseDir ?: return null
            val scriptFolder = File(parent, name)
            val configFile = File(scriptFolder, "script.json")
            val legacyFile = File(parent, "$name.json")

            val jsonStr = if (configFile.exists()) {
                configFile.readText()
            } else if (legacyFile.exists()) {
                legacyFile.readText()
            } else {
                return null
            }

            val root = JSONObject(jsonStr)
            val loopLimit = root.optInt("loopLimit", 0)
            val stepsArray = root.getJSONArray("steps")

            val stepsList = mutableListOf<MacroStep>()
            for (i in 0 until stepsArray.length()) {
                val sObj = stepsArray.getJSONObject(i)
                val stepNum = sObj.optInt("stepNumber", i + 1)
                val targetName = sObj.optString("targetImageName", "${name}_target_$stepNum")
                val thumbFileName = sObj.optString("thumbFile", "")

                // استرجاع صورة الهدف من مجلد السكربت المعزول
                var bmp: Bitmap? = null
                if (thumbFileName.isNotBlank()) {
                    val tFile = File(scriptFolder, thumbFileName)
                    if (tFile.exists()) {
                        bmp = BitmapFactory.decodeFile(tFile.absolutePath)
                    }
                }
                if (bmp == null && targetName.isNotBlank()) {
                    bmp = ImageLibrary.getTarget(targetName)
                }

                // استرجاع الزوايا التدريبية من نفس المجلد
                val trainedVars = mutableListOf<Bitmap>()
                val varsArray = sObj.optJSONArray("variations")
                if (varsArray != null) {
                    for (v in 0 until varsArray.length()) {
                        val vFileName = varsArray.getString(v)
                        val vFile = File(scriptFolder, vFileName)
                        if (vFile.exists()) {
                            BitmapFactory.decodeFile(vFile.absolutePath)?.let { trainedVars.add(it) }
                        }
                    }
                }

                // تسجيل الصورة في الذاكرة باسمها المعزول لتتمكن C++ و OpenCV من مطابقتها
                if (bmp != null) {
                    ImageLibrary.saveTarget(targetName, bmp)
                }

                val step = MacroStep(
                    stepNumber = stepNum,
                    name = sObj.optString("name", "Step $stepNum"),
                    type = try { ActionType.valueOf(sObj.optString("type", ActionType.CLICK_IMAGE.name)) } catch (e: Exception) { ActionType.CLICK_IMAGE },
                    targetArea = RectF(
                        sObj.optDouble("left", 0.0).toFloat(),
                        sObj.optDouble("top", 0.0).toFloat(),
                        sObj.optDouble("right", 0.0).toFloat(),
                        sObj.optDouble("bottom", 0.0).toFloat()
                    ),
                    targetImageName = targetName,
                    thumbnail = bmp,
                    trainedVariations = trainedVars,
                    similarityPercent = sObj.optInt("similarityPercent", 70),
                    detectScope = try { DetectScope.valueOf(sObj.optString("detectScope", DetectScope.FULL_SCREEN.name)) } catch (e: Exception) { DetectScope.FULL_SCREEN },
                    repeatCount = sObj.optInt("repeatCount", 1),
                    delayBeforeMs = sObj.optLong("delayBeforeMs", 0L),
                    delayAfterMs = sObj.optLong("delayAfterMs", 500L),
                    customScript = sObj.optString("customScript", ""),
                    enabled = sObj.optBoolean("enabled", true)
                )
                stepsList.add(step)
            }
            Pair(stepsList, loopLimit)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getAllScripts(): List<String> {
        val parent = baseDir ?: return emptyList()
        val list = mutableListOf<String>()

        parent.listFiles()?.forEach { file ->
            if (file.isDirectory && File(file, "script.json").exists()) {
                list.add(file.name)
            } else if (file.isFile && file.extension.equals("json", ignoreCase = true)) {
                val n = file.nameWithoutExtension
                if (!list.contains(n)) list.add(n)
            }
        }
        return list.sorted()
    }

    fun deleteScript(name: String): Boolean {
        val parent = baseDir ?: return false
        val folder = File(parent, name)
        if (folder.exists()) {
            folder.deleteRecursively()
        }
        val legacy = File(parent, "$name.json")
        if (legacy.exists()) {
            legacy.delete()
        }
        return true
    }
}
