package com.apex.nativeauto.macro

import android.graphics.Color
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

class CodeSyntaxHighlighter : TextWatcher {

    private val keywordPattern = Pattern.compile("\\b(if|else|for|while|function|var|let|const|return|true|false|click|sleep|findImage)\\b")
    private val numberPattern = Pattern.compile("\\b(\\d+)\\b")
    private val stringPattern = Pattern.compile("(\".*?\"|'.*?')")
    private val commentPattern = Pattern.compile("//.*")

    private val keywordColor = Color.parseColor("#00F0FF") // Cyan Neon
    private val numberColor = Color.parseColor("#FB923C")  // Orange
    private val stringColor = Color.parseColor("#4ADE80")  // Emerald Green
    private val commentColor = Color.parseColor("#64748B") // Slate Gray

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(editable: Editable?) {
        if (editable == null) return

        // مسح الألوان السابقة لتطبيق التنسيق الجديد
        val spans = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
        for (span in spans) {
            editable.removeSpan(span)
        }

        // تلوين الكلمات المحجوزة
        var matcher = keywordPattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(ForegroundColorSpan(keywordColor), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // تلوين الأرقام
        matcher = numberPattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(ForegroundColorSpan(numberColor), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // تلوين النصوص
        matcher = stringPattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(ForegroundColorSpan(stringColor), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // تلوين التعليقات
        matcher = commentPattern.matcher(editable)
        while (matcher.find()) {
            editable.setSpan(ForegroundColorSpan(commentColor), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
