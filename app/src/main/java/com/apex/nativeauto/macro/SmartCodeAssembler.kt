package com.apex.nativeauto.macro

object SmartCodeAssembler {

    /**
     * يقوم بتحليل الكود الحالي أو الزر المضغوط وإعادة بنائه تلقائياً
     * وفق الهيكلية البرمجية المعتمدة (AST Structure) دون أخطاء بناء
     */
    fun assemble(currentCode: String, targetName: String, actionToAdd: String?): String {
        val codeLower = currentCode.lowercase()

        // استكشاف المكونات المفعلة حالياً أو المطلوب إضافتها
        var hasTryCatch = codeLower.contains("try {") || actionToAdd == "tryCatch"
        var hasRetry = codeLower.contains("attempt") || codeLower.contains("retry") || actionToAdd == "retry"
        var hasFindImage = codeLower.contains("findimage") || actionToAdd == "findImage" || actionToAdd == "ifElse"
        var hasClickImage = codeLower.contains("clickimage") || actionToAdd == "clickImage"
        var hasClick = codeLower.contains("click(") || actionToAdd == "click" || (!hasClickImage && hasFindImage)
        var hasSwipe = codeLower.contains("swipe(") || actionToAdd == "swipe"
        var hasSleep = codeLower.contains("sleep(") || actionToAdd == "sleep"
        var hasToast = codeLower.contains("toast(") || actionToAdd == "toast"
        var hasIfElse = codeLower.contains("if (") || codeLower.contains("else") || actionToAdd == "ifElse" || hasFindImage

        // إذا كان الكود فارغاً وتم الضغط على زر مستقل مثل click أو sleep
        if (currentCode.isBlank() && actionToAdd != null) {
            when (actionToAdd) {
                "click" -> return "click(x, y);\nsleep(300);"
                "clickImage" -> return "clickImage(\"$targetName\", 70);\nsleep(300);"
                "sleep" -> return "sleep(500);"
                "swipe" -> return "swipe(x, y, x, y - 400, 300);"
            }
        }

        // بناء الكود بترتيب هيكلي صارم: Try -> Retry -> Detection -> If/Else -> Actions -> Catch
        val sb = StringBuilder()
        var indent = ""

        if (hasTryCatch) {
            sb.append("try {\n")
            indent = "    "
        }

        if (hasRetry) {
            sb.append("${indent}for (let attempt = 1; attempt <= 3; attempt++) {\n")
            indent += "    "
        }

        if (hasClickImage && !hasFindImage) {
            sb.append("${indent}let clicked = clickImage(\"$targetName\", 70);\n")
            if (hasIfElse) {
                sb.append("${indent}if (clicked) {\n")
                if (hasSleep) sb.append("${indent}    sleep(300);\n")
                if (hasToast) sb.append("${indent}    toast(\"✓ تم النقر على الهدف بنجاح\");\n")
                if (hasRetry) sb.append("${indent}    break;\n")
                sb.append("${indent}} else {\n")
                if (hasToast) sb.append("${indent}    toast(\"✕ لم يتم العثور على الهدف\");\n")
                if (hasRetry) sb.append("${indent}    sleep(500);\n")
                sb.append("${indent}}\n")
            }
        } else {
            // البحث والتحقق دائماً في البداية
            sb.append("${indent}// 1. فحص وجود الهدف بدقة\n")
            sb.append("${indent}let res = findImage(\"$targetName\", 70);\n\n")

            if (hasIfElse) {
                sb.append("${indent}// 2. شرط النجاح والفشل\n")
                sb.append("${indent}if (res.found) {\n")

                if (hasClick) {
                    sb.append("${indent}    click(res.x, res.y);\n")
                }
                if (hasSwipe) {
                    sb.append("${indent}    swipe(res.x, res.y, res.x, res.y - 400, 300);\n")
                }
                if (hasSleep) {
                    sb.append("${indent}    sleep(300);\n")
                }
                if (hasToast) {
                    sb.append("${indent}    toast(\"✓ تم العثور على الهدف والنقر بنجاح\");\n")
                }
                if (hasRetry) {
                    sb.append("${indent}    break; // إنهاء المحاولات عند النجاح\n")
                }

                sb.append("${indent}} else {\n")
                if (hasToast) {
                    if (hasRetry) {
                        sb.append("${indent}    toast(\"✕ محاولة \" + attempt + \": لم يتم العثور على الهدف\");\n")
                    } else {
                        sb.append("${indent}    toast(\"✕ فشل: لم يتم العثور على الهدف\");\n")
                    }
                }
                if (hasRetry) {
                    sb.append("${indent}    sleep(500);\n")
                } else if (hasSleep) {
                    sb.append("${indent}    sleep(200);\n")
                }
                sb.append("${indent}}\n")
            }
        }

        if (hasRetry) {
            indent = indent.removePrefix("    ")
            sb.append("${indent}}\n")
        }

        if (hasTryCatch) {
            sb.append("} catch (e) {\n")
            sb.append("    log(\"حدث خطأ في الخطوة: \" + e);\n")
            sb.append("}\n")
        }

        return sb.toString().trimEnd()
    }
}
