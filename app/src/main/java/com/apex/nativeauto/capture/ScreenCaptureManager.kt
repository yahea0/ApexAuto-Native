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
import android.os.Handler
import android.os.Looper
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

    @Volatile
    private var latestCleanBitmap: Bitmap? = null

    fun init(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)
        
        // مستمع دائم لتحديث لقطة الشاشة ورفض الإطارات السوداء
        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = image.planes[0]
                val buffer = plane.buffer
                buffer.rewind()

                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val raw = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(buffer)
                image.close()

                val clean = if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)

                // فحص الإطار: إذا لم يكن إطاراً أسود بالكامل نحتفظ به فوراً
                if (!isBitmapPureBlack(clean)) {
                    synchronized(this) {
                        latestCleanBitmap = clean
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ApexScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun isBitmapPureBlack(bmp: Bitmap): Boolean {
        // فحص عينات من منتصف وأطراف الصورة
        val c = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        val c1 = bmp.getPixel(bmp.width / 4, bmp.height / 4)
        val c2 = bmp.getPixel(bmp.width * 3 / 4, bmp.height * 3 / 4)
        return (c == 0 || c == -16777216) && (c1 == 0 || c1 == -16777216) && (c2 == 0 || c2 == -16777216)
    }

    fun captureCurrentScreen(): Bitmap? {
        // محاولة جلب أحدث لقطة حقيقية محفوظة
        synchronized(this) {
            if (latestCleanBitmap != null && !latestCleanBitmap!!.isRecycled) {
                return latestCleanBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
        Thread.sleep(80)
        return latestCleanBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    // قص الهدف بدقة تامة من الإطار الحقيقي
    fun cropAreaFromScreen(cropRect: RectF): Bitmap {
        val fullScreenshot = captureCurrentScreen()

        val left = max(0, cropRect.left.toInt().coerceAtMost(screenWidth - 1))
        val top = max(0, cropRect.top.toInt().coerceAtMost(screenHeight - 1))
        val width = max(1, cropRect.width().toInt().coerceAtMost(screenWidth - left))
        val height = max(1, cropRect.height().toInt().coerceAtMost(screenHeight - top))

        return if (fullScreenshot != null) {
            Bitmap.createBitmap(fullScreenshot, left, top, width, height)
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.DKGRAY)
            }
        }
    }
}
