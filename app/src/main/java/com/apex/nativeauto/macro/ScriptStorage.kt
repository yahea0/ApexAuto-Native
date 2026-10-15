package com.apex.nativeauto.macro

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ScriptStorage {
    private var scriptsDir: File? = null

    fun init(context: Context) {
        scriptsDir = File(context.filesDir, "saved_scripts").apply { mkdirs() }
    }

    // حفظ السكربت بكافة تفاصيله وخطواته
    fun saveScript(name: String, steps: List<MacroStep>, loopLimit: Int = 0): Boolean {
        return try {
            val dir = scriptsDir ?: return false
            val root = JSONObject().apply {
                put("name", name)
                put("loopLimit", loopLimit)
                put("timestamp", System.currentTimeMillis())

                val stepsArray = JSONArray()
                steps.forEach { step ->
                    val sObj = JSONObject().apply {
                        put("stepNumber", step.stepNumber)
                        put("name", step.name)
                        put("type", step.type.name)
                        put("targetImageName", step.targetImageName)
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

            val file = File(dir, "$name.json")
            file.writeText(root.toString(2))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // استرجاع السكربت المحفوظ وإعادة بناء الكائنات
    fun loadScript(name: String): Pair<List<MacroStep>, Int>? {
        return try {
            val dir = scriptsDir ?: return null
            val file = File(dir, "$name.json")
            if (!file.exists()) return null

            val jsonStr = file.readText()
            val root = JSONObject(jsonStr)
            val loopLimit = root.optInt("loopLimit", 0)
            val stepsArray = root.getJSONArray("steps")

            val stepsList = mutableListOf<MacroStep>()
            for (i in 0 until stepsArray.length()) {
                val sObj = stepsArray.getJSONObject(i)
                val targetName = sObj.optString("targetImageName", "")
                val bmp = ImageLibrary.getTarget(targetName)

                val step = MacroStep(
                    stepNumber = sObj.optInt("stepNumber", i + 1),
                    name = sObj.optString("name", "Step ${i + 1}"),
                    type = try { ActionType.valueOf(sObj.optString("type", ActionType.CLICK_IMAGE.name)) } catch (e: Exception) { ActionType.CLICK_IMAGE },
                    targetArea = RectF(
                        sObj.optDouble("left", 0.0).toFloat(),
                        sObj.optDouble("top", 0.0).toFloat(),
                        sObj.optDouble("right", 0.0).toFloat(),
                        sObj.optDouble("bottom", 0.0).toFloat()
                    ),
                    targetImageName = targetName,
                    thumbnail = bmp,
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
        val dir = scriptsDir ?: return emptyList()
        return dir.listFiles { file -> file.extension.equals("json", ignoreCase = true) }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun deleteScript(name: String): Boolean {
        val dir = scriptsDir ?: return false
        val file = File(dir, "$name.json")
        return file.delete()
    }
}
