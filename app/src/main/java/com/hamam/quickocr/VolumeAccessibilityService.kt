package com.hamam.quickocr

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.sqrt

/** Accessibility service: detects a deliberate strong shake and writes only verified OCR output. */
class VolumeAccessibilityService : AccessibilityService(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val gravity = FloatArray(3)
    private var initialized = false
    private var lastPeakTime = 0L
    private var peakCount = 0
    private var lastDominantSign = 0
    private var lastTrigger = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val now = SystemClock.elapsedRealtime()
        if (!initialized) {
            gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
            initialized = true
            return
        }
        val alpha = 0.88f
        gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2]
        val x = event.values[0] - gravity[0]
        val y = event.values[1] - gravity[1]
        val z = event.values[2] - gravity[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        if (now - lastPeakTime > SHAKE_WINDOW_MS) {
            peakCount = 0
            lastDominantSign = 0
        }
        // Strong intentional shake: >= ~1.15g dynamic acceleration, with alternating dominant direction.
        if (magnitude >= SHAKE_THRESHOLD) {
            val dominant = when {
                abs(x) >= abs(y) && abs(x) >= abs(z) -> x
                abs(y) >= abs(z) -> y
                else -> z
            }
            val sign = if (dominant >= 0f) 1 else -1
            if (sign != lastDominantSign) {
                peakCount++
                lastDominantSign = sign
                lastPeakTime = now
            }
        }

        if (peakCount >= REQUIRED_ALTERNATIONS && now - lastTrigger >= TRIGGER_COOLDOWN_MS) {
            peakCount = 0
            lastDominantSign = 0
            lastTrigger = now
            QuickCaptureService.trigger()
        }
    }

    fun pasteIntoFocusedField(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val inserted = node?.isEditable == true && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!inserted) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Quick OCR", text))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val SHAKE_THRESHOLD = 11.5f
        private const val SHAKE_WINDOW_MS = 700L
        private const val REQUIRED_ALTERNATIONS = 3
        private const val TRIGGER_COOLDOWN_MS = 2200L
        @Volatile private var instance: VolumeAccessibilityService? = null

        fun pasteWhenAvailable(text: String, delayMs: Long = 0L) {
            instance?.let { service ->
                service.handler.postDelayed({ service.pasteIntoFocusedField(text) }, delayMs)
            }
        }
    }
}
