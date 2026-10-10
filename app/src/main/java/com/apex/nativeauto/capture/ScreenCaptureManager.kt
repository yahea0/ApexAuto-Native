package com.apex.nativeauto.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max

object ScreenCaptureManager {
    var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    var screenWidth = 1080
    var screenHeight = 2400
    private var screenDensity = 420

    fun init(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ApexScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    // التقاط إطار الشاشة النظيف مع تنظيف إزاحة الـ RowPadding
    fun captureCurrentScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null

        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val rawBitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        rawBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        // استخراج الصورة النظيفة المطابقة لأبعاد الشاشة بالملي
        return if (rowPadding == 0) {
            rawBitmap
        } else {
            val cleanBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
            rawBitmap.recycle()
            cleanBitmap
        }
    }

    // اقتطاع ما داخل الإطار المطاطي فقط بحساب دقيق للبكسلات
    fun cropAreaFromScreen(cropRect: RectF): Bitmap {
        val fullScreenshot = captureCurrentScreen()

        val left = max(0, cropRect.left.toInt().coerceAtMost(screenWidth - 1))
        val top = max(0, cropRect.top.toInt().coerceAtMost(screenHeight - 1))
        val width = max(1, cropRect.width().toInt().coerceAtMost(screenWidth - left))
        val height = max(1, cropRect.height().toInt().coerceAtMost(screenHeight - top))

        return if (fullScreenshot != null) {
            val cropped = Bitmap.createBitmap(fullScreenshot, left, top, width, height)
            fullScreenshot.recycle()
            cropped
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.DKGRAY)
            }
        }
    }
}
