package com.apex.nativeauto.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max

object ScreenCaptureManager {
    var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    var screenWidth = 1080
    var screenHeight = 2400
    private var screenDensity = 420

    @Volatile
    var latestCleanBitmap: Bitmap? = null

    fun init(context: Context, resultCode: Int, data: Intent) {
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        if (backgroundThread == null) {
            backgroundThread = HandlerThread("ApexScreenCaptureThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)

        imageReader?.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processImage(image)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, backgroundHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ApexScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )
    }

    private fun processImage(image: Image) {
        try {
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

            val clean = if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)

            if (!isBitmapBlack(clean)) {
                synchronized(this) {
                    latestCleanBitmap = clean
                }
            }
        } finally {
            image.close()
        }
    }

    private fun isBitmapBlack(bmp: Bitmap): Boolean {
        val stepX = max(1, bmp.width / 10)
        val stepY = max(1, bmp.height / 10)
        for (x in stepX until bmp.width step stepX) {
            for (y in stepY until bmp.height step stepY) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val a = Color.alpha(pixel)
                if (a > 0 && (r > 15 || g > 15 || b > 15)) {
                    return false
                }
            }
        }
        return true
    }

    // دالة عادية متزامنة لتفادي أخطاء الـ Coroutine
    fun getRealScreenshot(timeoutMs: Long = 800): Bitmap? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            synchronized(this) {
                if (latestCleanBitmap != null && !latestCleanBitmap!!.isRecycled && !isBitmapBlack(latestCleanBitmap!!)) {
                    return latestCleanBitmap!!.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            Thread.sleep(30)
        }
        return latestCleanBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun cropAreaFromScreen(cropRect: RectF): Bitmap {
        val fullScreenshot = getRealScreenshot()

        val left = max(0, cropRect.left.toInt().coerceAtMost(screenWidth - 1))
        val top = max(0, cropRect.top.toInt().coerceAtMost(screenHeight - 1))
        val width = max(1, cropRect.width().toInt().coerceAtMost(screenWidth - left))
        val height = max(1, cropRect.height().toInt().coerceAtMost(screenHeight - top))

        return if (fullScreenshot != null && !isBitmapBlack(fullScreenshot)) {
            Bitmap.createBitmap(fullScreenshot, left, top, width, height)
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.parseColor("#151722"))
            }
        }
    }
}
