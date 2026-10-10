#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>

#define LOG_TAG "Apex_NativeMatcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// تحويل Android Bitmap إلى cv::Mat في ذاكرة الرام مباشرة
static bool bitmapToMat(JNIEnv* env, jobject bitmap, cv::Mat& dst) {
    AndroidBitmapInfo info;
    void* pixels = nullptr;

    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) return false;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return false;

    cv::Mat tmp(info.height, info.width, CV_8UC4, pixels);
    tmp.copyTo(dst);

    AndroidBitmap_unlockPixels(env, bitmap);
    return true;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_apex_nativeauto_capture_ImageMatcher_nativeMatchWithOpenCV(
    JNIEnv* env,
    jobject /* thiz */,
    jobject screenBitmap,
    jobject templateBitmap,
    jint scope,              // 0: CAPTURED_LOCATION, 1: CUSTOM_REGION, 2: FULL_SCREEN
    jint thresholdPercent,   // مثلاً 70
    jint roiX, jint roiY, jint roiW, jint roiH
) {
    jfloatArray resultArray = env->NewFloatArray(4);
    jfloat defaultResult[4] = {0.0f, 0.0f, 0.0f, 0.0f}; // [isFound, centerX, centerY, confidence]

    if (!screenBitmap || !templateBitmap) {
        env->SetFloatArrayRegion(resultArray, 0, 4, defaultResult);
        return resultArray;
    }

    cv::Mat screenRgba, templateRgba;
    if (!bitmapToMat(env, screenBitmap, screenRgba) || !bitmapToMat(env, templateBitmap, templateRgba)) {
        env->SetFloatArrayRegion(resultArray, 0, 4, defaultResult);
        return resultArray;
    }

    cv::Mat screenBgr, templateBgr;
    cv::cvtColor(screenRgba, screenBgr, cv::COLOR_RGBA2BGR);
    cv::cvtColor(templateRgba, templateBgr, cv::COLOR_RGBA2BGR);

    if (templateBgr.cols > screenBgr.cols || templateBgr.rows > screenBgr.rows) {
        env->SetFloatArrayRegion(resultArray, 0, 4, defaultResult);
        return resultArray;
    }

    cv::Mat searchRegion;
    int offsetX = 0;
    int offsetY = 0;

    if (scope == 0) { // 1. Captured Location (فحص فوري في نفس المكان)
        int x = std::clamp(roiX, 0, screenBgr.cols - 1);
        int y = std::clamp(roiY, 0, screenBgr.rows - 1);
        int w = std::clamp(roiW, 1, screenBgr.cols - x);
        int h = std::clamp(roiH, 1, screenBgr.rows - y);

        if (templateBgr.cols <= w && templateBgr.rows <= h) {
            searchRegion = screenBgr(cv::Rect(x, y, w, h));
            offsetX = x;
            offsetY = y;
        } else {
            searchRegion = screenBgr;
        }
    } else if (scope == 1) { // 2. Custom Region
        int x = std::clamp(roiX, 0, screenBgr.cols - 1);
        int y = std::clamp(roiY, 0, screenBgr.rows - 1);
        int w = std::clamp(roiW, 1, screenBgr.cols - x);
        int h = std::clamp(roiH, 1, screenBgr.rows - y);
        searchRegion = screenBgr(cv::Rect(x, y, w, h));
        offsetX = x;
        offsetY = y;
    } else { // 3. Full Screen (تمشيط كامل الشاشة بـ OpenCV)
        searchRegion = screenBgr;
        offsetX = 0;
        offsetY = 0;
    }

    // تنفيذ المطابقة باستخدام خوارزمية OpenCV القياسية TM_CCOEFF_NORMED
    cv::Mat matchResult;
    cv::matchTemplate(searchRegion, templateBgr, matchResult, cv::TM_CCOEFF_NORMED);

    double minVal = 0.0, maxVal = 0.0;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(matchResult, &minVal, &maxVal, &minLoc, &maxLoc);

    float similarityPercent = static_cast<float>(maxVal * 100.0);
    bool isMatched = (similarityPercent >= thresholdPercent);

    float targetCenterX = static_cast<float>(offsetX + maxLoc.x + templateBgr.cols / 2.0f);
    float targetCenterY = static_cast<float>(offsetY + maxLoc.y + templateBgr.rows / 2.0f);

    jfloat output[4] = {
        isMatched ? 1.0f : 0.0f,
        targetCenterX,
        targetCenterY,
        similarityPercent
    };

    env->SetFloatArrayRegion(resultArray, 0, 4, output);
    return resultArray;
}
