package com.apex.nativeauto.macro

import android.util.Log
import com.apex.nativeauto.accessibility.ApexAccessibilityService
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

class ScriptRunner {

    // تنفيذ سكريبت جافا سكريبت وربطه بدوال النظام الحقيقية
    fun execute(code: String, stepNum: Int, targetX: Float, targetY: Float): Boolean {
        return try {
            val cx = Context.enter()
            cx.optimizationLevel = -1 // Interpreter mode
            val scope = cx.initStandardObjects()

            // ربط المتغيرات بالسكريبت
            ScriptableObject.putProperty(scope, "step", stepNum)
            ScriptableObject.putProperty(scope, "x", targetX)
            ScriptableObject.putProperty(scope, "y", targetY)

            // تصدير دالة click(x, y) لجافا سكريبت
            val clickFunction = object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: Context?, scope: org.mozilla.javascript.Scriptable?, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                    if (args != null && args.size >= 2) {
                        val px = Context.toNumber(args[0]).toFloat()
                        val py = Context.toNumber(args[1]).toFloat()
                        ApexAccessibilityService.instance?.performClick(px, py)
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "click", clickFunction)

            // تصدير دالة sleep(ms) لجافا سكريبت
            val sleepFunction = object : org.mozilla.javascript.BaseFunction() {
                override fun call(cx: Context?, scope: org.mozilla.javascript.Scriptable?, thisObj: org.mozilla.javascript.Scriptable?, args: Array<out Any>?): Any {
                    if (args != null && args.isNotEmpty()) {
                        val ms = Context.toNumber(args[0]).toLong()
                        Thread.sleep(ms)
                    }
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "sleep", sleepFunction)

            cx.evaluateString(scope, code, "UserMacroScript", 1, null)
            Context.exit()
            true
        } catch (e: Exception) {
            Log.e("Apex_ScriptRunner", "JS Error: ${e.message}")
            Context.exit()
            false
        }
    }
}
