package com.codeair.mhe.operator.capture

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codeair.mhe.R

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, captureNotification())
        running = true
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun captureNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Capturing screen for automation")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun startCapture(resultCode: Int, data: Intent) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            width = metrics.widthPixels
            height = metrics.heightPixels
        }
        val density = resources.displayMetrics.densityDpi
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "screen-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null,
        )
        Thread {
            val bitmap = waitForFrame(width, height)
            if (bitmap != null) dispatch(bitmap)
            stopSelf()
        }.start()
    }

    private fun waitForFrame(width: Int, height: Int): Bitmap? {
        val reader = imageReader ?: return null
        val deadline = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MS
        var image: Image? = null
        while (SystemClock.uptimeMillis() < deadline && image == null) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(FRAME_POLL_MS)
        }
        return image?.use { toBitmap(it, width, height) }
    }

    private fun toBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / plane.pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun dispatch(bitmap: Bitmap) {
        val snapshot = synchronized(listeners) { listeners.values.toList() }
        snapshot.forEach { listener -> runCatching { listener(bitmap) } }
    }

    override fun onDestroy() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        private var running = false

        private const val CHANNEL_ID = "screen-capture"
        private const val NOTIFICATION_ID = 43
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        private const val FRAME_POLL_MS = 50L
        const val DEFAULT_REQUEST_CODE = 4402

        private val listeners = mutableMapOf<String, (Bitmap) -> Unit>()

        fun isActive(): Boolean = running

        fun requestCapture(context: Context) {
            (context as? Activity)?.let { requestCapture(it, DEFAULT_REQUEST_CODE) }
        }

        fun requestCapture(activity: Activity, requestCode: Int) {
            val mediaProjectionManager =
                activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            runCatching {
                activity.startActivityForResult(
                    mediaProjectionManager.createScreenCaptureIntent(),
                    requestCode,
                )
            }
        }

        fun handleActivityResult(
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
            expectedCode: Int,
            context: Context,
        ): Boolean {
            if (requestCode == expectedCode && resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScreenCaptureService::class.java)
                        .putExtra(EXTRA_RESULT_CODE, resultCode)
                        .putExtra(EXTRA_DATA, data),
                )
                return true
            }
            return false
        }

        @Synchronized
        fun registerListener(key: String, listener: (Bitmap) -> Unit) {
            listeners[key] = listener
        }

        @Synchronized
        fun unregisterListener(key: String) {
            listeners.remove(key)
        }
    }
}
