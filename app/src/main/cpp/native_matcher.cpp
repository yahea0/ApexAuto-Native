#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <memory>
#include "yolo12_engine.hpp"
#include "orb_matcher.hpp"

#define LOG_TAG "Apex_NativeMatcher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::unique_ptr<YOLOv12Engine> g_yoloEngine = nullptr;
static std::unique_ptr<ORBMatcher> g_orbMatcher = nullptr;

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

// دالة تهيئة نموذج YOLOv12 من مسار الذاكرة
extern "C" JNIEXPORT jboolean JNICALL
Java_com_apex_nativeauto_capture_ImageMatcher_nativeInitYOLO(
    JNIEnv* env,
    jobject /* thiz */,
    jstring modelPathStr
) {
    const char* path = env->GetStringUTFChars(modelPathStr, nullptr);
    try {
        g_yoloEngine = std::make_unique<YOLOv12Engine>(std::string(path), 0.35f, 0.45f);
        g_orbMatcher = std::make_unique<ORBMatcher>(500);
        LOGI("YOLOv12 Engine & ORB Matcher Initialized Successfully from: %s", path);
        env->ReleaseStringUTFChars(modelPathStr, path);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGI("Failed to init YOLOv12: %s", e.what());
        env->ReleaseStringUTFChars(modelPathStr, path);
        return JNI_FALSE;
    }
}

// دالة الفحص الهجينة (OpenCV + ORB + YOLOv12)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_apex_nativeauto_capture_ImageMatcher_nativeMatchWithOpenCV(
    JNIEnv* env,
    jobject /* thiz */,
    jobject screenBitmap,
    jobject templateBitmap,
    jint scope,
    jint thresholdPercent,
    jint roiX, jint roiY, jint roiW, jint roiH
) {
    jfloatArray resultArray = env->NewFloatArray(4);
    jfloat defaultResult[4] = {0.0f, 0.0f, 0.0f, 0.0f};

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

    cv::Mat searchRegion;
    int offsetX = 0;
    int offsetY = 0;

    if (scope == 0) { // Captured Location
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
    } else if (scope == 1) { // Custom Region
        int x = std::clamp(roiX, 0, screenBgr.cols - 1);
        int y = std::clamp(roiY, 0, screenBgr.rows - 1);
        int w = std::clamp(roiW, 1, screenBgr.cols - x);
        int h = std::clamp(roiH, 1, screenBgr.rows - y);
        searchRegion = screenBgr(cv::Rect(x, y, w, h));
        offsetX = x;
        offsetY = y;
    } else { // Full Screen
        searchRegion = screenBgr;
    }

    // 1. الفحص السريع بـ OpenCV TM_CCOEFF_NORMED
    cv::Mat matchResult;
    cv::matchTemplate(searchRegion, templateBgr, matchResult, cv::TM_CCOEFF_NORMED);

    double minVal = 0.0, maxVal = 0.0;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(matchResult, &minVal, &maxVal, &minLoc, &maxLoc);

    float similarity = static_cast<float>(maxVal * 100.0);

    // 2. إذا كانت النتيجة جيدة يتم الاعتماد عليها فوراً
    if (similarity >= thresholdPercent) {
        float cx = static_cast<float>(offsetX + maxLoc.x + templateBgr.cols / 2.0f);
        float cy = static_cast<float>(offsetY + maxLoc.y + templateBgr.rows / 2.0f);
        jfloat output[4] = {1.0f, cx, cy, similarity};
        env->SetFloatArrayRegion(resultArray, 0, 4, output);
        return resultArray;
    }

    // 3. في حال كان البحث بكامل الشاشة ولم يعثر عليه، يستيقظ YOLOv12 و ORB
    if (scope == 2 && g_yoloEngine != nullptr) {
        auto detections = g_yoloEngine->detect(screenRgba.data, screenRgba.cols, screenRgba.rows);
        for (const auto& det : detections) {
            int bx = std::clamp(static_cast<int>(det.x1), 0, screenBgr.cols - 1);
            int by = std::clamp(static_cast<int>(det.y1), 0, screenBgr.rows - 1);
            int bw = std::clamp(static_cast<int>(det.x2 - det.x1), 1, screenBgr.cols - bx);
            int bh = std::clamp(static_cast<int>(det.y2 - det.y1), 1, screenBgr.rows - by);

            cv::Mat cropCandidate = screenBgr(cv::Rect(bx, by, bw, bh));
            if (g_orbMatcher && g_orbMatcher->matchTemplate(cropCandidate, templateBgr, 0.75f)) {
                float cx = (det.x1 + det.x2) / 2.0f;
                float cy = (det.y1 + det.y2) / 2.0f;
                jfloat output[4] = {1.0f, cx, cy, 95.0f}; // اكتشاف ذكي مؤكد
                env->SetFloatArrayRegion(resultArray, 0, 4, output);
                return resultArray;
            }
        }
    }

    // لم يتم العثور على الهدف
    jfloat output[4] = {0.0f, 0.0f, 0.0f, similarity};
    env->SetFloatArrayRegion(resultArray, 0, 4, output);
    return resultArray;
}
