package com.apex.nativeauto.macro

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import com.apex.nativeauto.capture.ImageMatcher
import com.apex.nativeauto.capture.ScreenCaptureManager
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

class ScriptRunner {

    private val mainHandler = Handler(Looper.getMainLooper())
    var onLiveLogEmitted: ((String) -> Unit)? = null

    fun execute(code: String, stepNum: Int, targetX: Float, targetY: Float): Boolean {
        return try {
            val cx = Context.enter()
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()

            ScriptableObject.putProperty(scope, "step", stepNum)
            ScriptableObject.putProperty(scope, "x", targetX)
            ScriptableObject.putProperty(scope, "y", targetY)

            // دالة النقر
            val clickFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    if (args != null && args.size >= 2) {
                        val px = Context.toNumber(args[0]).toFloat()
                        val py = Context.toNumber(args[1]).toFloat()
                        ApexAccessibilityService.instance?.performClick(px, py)
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "click", clickFunc)

            // دالة البحث عن صورة
            val findImageFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val name = args?.getOrNull(0)?.toString() ?: ""
                    val thresh = if (args != null && args.size >= 2) Context.toNumber(args[1]).toInt() else 70

                    val bmp = ImageLibrary.getTarget(name)
                    val screen = ScreenCaptureManager.getRealScreenshot()

                    val resultObj = cx?.newObject(scope) ?: return Context.getUndefinedValue()

                    if (bmp != null && screen != null) {
                        val step = MacroStep(
                            name = name,
                            thumbnail = bmp,
                            similarityPercent = thresh,
                            detectScope = DetectScope.FULL_SCREEN
                        )
                        val match = ImageMatcher.findTarget(step, screen)
                        ScriptableObject.putProperty(resultObj, "found", match.isMatched)
                        ScriptableObject.putProperty(resultObj, "x", match.targetCenter?.x ?: 0f)
                        ScriptableObject.putProperty(resultObj, "y", match.targetCenter?.y ?: 0f)
                        ScriptableObject.putProperty(resultObj, "similarity", match.currentSimilarity)
                    } else {
                        ScriptableObject.putProperty(resultObj, "found", false)
                        ScriptableObject.putProperty(resultObj, "x", 0f)
                        ScriptableObject.putProperty(resultObj, "y", 0f)
                        ScriptableObject.putProperty(resultObj, "similarity", 0)
                    }
                    return resultObj
                }
            }
            ScriptableObject.putProperty(scope, "findImage", findImageFunc)

            // دالة النقر المباشر على صورة
            val clickImageFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val name = args?.getOrNull(0)?.toString() ?: ""
                    val thresh = if (args != null && args.size >= 2) Context.toNumber(args[1]).toInt() else 70

                    val bmp = ImageLibrary.getTarget(name)
                    val screen = ScreenCaptureManager.getRealScreenshot()

                    if (bmp != null && screen != null) {
                        val step = MacroStep(
                            name = name,
                            thumbnail = bmp,
                            similarityPercent = thresh,
                            detectScope = DetectScope.FULL_SCREEN
                        )
                        val match = ImageMatcher.findTarget(step, screen)
                        if (match.isMatched && match.targetCenter != null) {
                            ApexAccessibilityService.instance?.performClick(match.targetCenter.x, match.targetCenter.y)
                            return true
                        }
                    }
                    return false
                }
            }
            ScriptableObject.putProperty(scope, "clickImage", clickImageFunc)

            // دالة الانتظار
            val sleepFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    if (args != null && args.isNotEmpty()) {
                        val ms = Context.toNumber(args[0]).toLong()
                        Thread.sleep(ms)
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "sleep", sleepFunc)

            // دالة إرسال سجل مباشر (Live Console Log)
            val logFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val msg = args?.getOrNull(0)?.toString() ?: ""
                    mainHandler.post {
                        onLiveLogEmitted?.invoke(msg)
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "log", logFunc)

            val toastFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    val msg = args?.getOrNull(0)?.toString() ?: ""
                    mainHandler.post {
                        ApexAccessibilityService.instance?.let {
                            Toast.makeText(it, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "toast", toastFunc)

            val backFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    ApexAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "pressBack", backFunc)

            val homeFunc = object : BaseFunction() {
                override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                    ApexAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "pressHome", homeFunc)

            cx.evaluateString(scope, code, "ApexMacroRuntime", 1, null)
            Context.exit()
            true
        } catch (e: Exception) {
            mainHandler.post {
                onLiveLogEmitted?.invoke("❌ خطأ برمجي: ${e.message}")
            }
            Context.exit()
            false
        }
    }
}
