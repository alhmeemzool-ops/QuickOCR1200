package com.hamam.quickocr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the camera ready without a Preview. A shake requests one short OCR transaction.
 * The service is intentionally started by an explicit user action from MainActivity.
 */
class QuickCaptureService : LifecycleService() {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var latestFrame: Frame? = null
    private var lastCaptureAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("جاهز — هز الهاتف هزّة قوية للقراءة"))
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        bindCamera()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setImageQueueDepth(1)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        if (!processing.get()) {
                            latestFrame?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                            latestFrame = Frame(imageToCenterBitmap(image, image.imageInfo.rotationDegrees), image.imageInfo.rotationDegrees)
                        }
                    } catch (_: Throwable) {
                        // Drop malformed frames; never crash the service.
                    } finally {
                        image.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }.onFailure { postStatus("تعذر تجهيز الكاميرا") }
        }, ContextCompat.getMainExecutor(this))
    }

    fun requestCapture() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < COOLDOWN_MS) return
        if (!processing.compareAndSet(false, true)) return
        lastCaptureAt = now
        updateNotification("جاري القراءة…")
        cameraExecutor.execute { processCapture() }
    }

    private fun processCapture() {
        val started = SystemClock.elapsedRealtime()
        try {
            val frame = latestFrame
            if (frame == null) {
                postStatus("لم أتمكن من التقاط صورة واضحة")
                return
            }
            val rotated = rotate(frame.bitmap, frame.rotation)
            val roi = centerRoi(rotated.width, rotated.height)
            val result = OcrEngine.recognizeFast(this, rotated, roi)
            if (result.accepted) {
                VolumeAccessibilityService.pasteWhenAvailable(result.text, 0L)
                postStatus("تمت القراءة واللصق ✓")
            } else {
                postStatus(OcrEngine.lastError ?: "تعذر قراءة النص بوضوح — لم يتم النسخ")
            }
            rotated.recycleIfDifferent(frame.bitmap)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > 3000) postStatus("تمت القراءة، لكنها استغرقت ${elapsed}ms")
        } catch (_: Throwable) {
            postStatus("تعذر قراءة النص — لم يتم النسخ")
        } finally {
            processing.set(false)
            updateNotification("جاهز — هز الهاتف هزّة قوية للقراءة")
        }
    }

    private fun centerRoi(w: Int, h: Int): Rect {
        // A wide, shallow central band works well for IDs/phone/account numbers while excluding clutter.
        val rw = (w * 0.82f).toInt().coerceAtLeast(320).coerceAtMost(w)
        val rh = (h * 0.22f).toInt().coerceAtLeast(120).coerceAtMost(h)
        val left = (w - rw) / 2
        val top = (h - rh) / 2
        return Rect(left, top, left + rw, top + rh)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun Bitmap.recycleIfDifferent(other: Bitmap) {
        if (this !== other && !isRecycled) recycle()
        if (this === other && !other.isRecycled) other.recycle()
    }

    private fun imageToCenterBitmap(image: ImageProxy, rotation: Int): Bitmap {
        val img = image.image ?: throw IllegalStateException("No image")
        if (img.format != ImageFormat.YUV_420_888) throw IllegalStateException("Unsupported image format")
        val fullW = img.width
        val fullH = img.height
        val sideways = rotation == 90 || rotation == 270
        // Loose pre-crop only to bound per-frame YUV->RGB conversion cost. The precise "wide
        // shallow band" used for OCR is applied exactly once, after rotation, by centerRoi().
        // Previously this stage used the SAME tight ratio (0.82/0.22) as centerRoi, so the two
        // crops compounded and left a sliver only ~5% of the frame height — fixed by keeping a
        // generous margin here instead of re-applying the final ratio twice.
        val cropW = (fullW * if (sideways) 0.55f else 1f).toInt().coerceAtLeast(1)
        val cropH = (fullH * if (sideways) 1f else 0.55f).toInt().coerceAtLeast(1)
        val left = (fullW - cropW) / 2
        val top = (fullH - cropH) / 2
        val yPlane = img.planes[0]
        val uPlane = img.planes[1]
        val vPlane = img.planes[2]
        val yBuf = yPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val yBytes = ByteArray(yBuf.remaining()).also { yBuf.get(it) }
        val uBytes = ByteArray(uBuf.remaining()).also { uBuf.get(it) }
        val vBytes = ByteArray(vBuf.remaining()).also { vBuf.get(it) }
        val yStride = yPlane.rowStride
        val uStride = uPlane.rowStride
        val vStride = vPlane.rowStride
        val uPixel = uPlane.pixelStride
        val vPixel = vPlane.pixelStride
        val pixels = IntArray(cropW * cropH)
        for (j in 0 until cropH) {
            val srcY = top + j
            val uvRow = srcY shr 1
            for (i in 0 until cropW) {
                val srcX = left + i
                val yy = yBytes[srcY * yStride + srcX].toInt() and 0xff
                val uvCol = srcX shr 1
                val ui = uvRow * uStride + uvCol * uPixel
                val vi = uvRow * vStride + uvCol * vPixel
                val uu = (uBytes[ui].toInt() and 0xff) - 128
                val vv = (vBytes[vi].toInt() and 0xff) - 128
                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yy - 0.344136f * uu - 0.714136f * vv).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                pixels[j * cropW + i] = android.graphics.Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(pixels, cropW, cropH, Bitmap.Config.ARGB_8888)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quick OCR", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "حالة خدمة Quick OCR والكاميرا"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Quick OCR")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun postStatus(text: String) {
        updateNotification(text)
        android.os.Handler(mainLooper).postDelayed({
            if (!processing.get()) updateNotification("جاهز — هز الهاتف هزّة قوية للقراءة")
        }, 1200L)
    }

    override fun onDestroy() {
        instance = null
        latestFrame?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        latestFrame = null
        provider?.unbindAll()
        cameraExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private data class Frame(val bitmap: Bitmap, val rotation: Int)

    companion object {
        private const val CHANNEL_ID = "quick_ocr_status"
        private const val NOTIFICATION_ID = 9001
        private const val COOLDOWN_MS = 1800L
        @Volatile private var instance: QuickCaptureService? = null

        fun trigger() { instance?.requestCapture() }
    }
}
