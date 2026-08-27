package com.hamam.quickocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Strict numeric OCR: uncertainty always results in rejection, never guessing. */
object OcrEngine {
    data class Result(val text: String, val confidence: Int, val accepted: Boolean)

    private const val MIN_CONFIDENCE = 78
    private const val MIN_DIGITS = 1
    private const val MAX_DIGITS = 32
    private const val MAX_SIDE = 1600

    @Volatile private var prepared = false
    private var tess: TessBaseAPI? = null

    /** True once tessdata has been copied and Tesseract initialized successfully. */
    @Volatile var ready = false
        private set

    /** Human-readable reason prepare() failed, for status notifications. Null when ready. */
    @Volatile var lastError: String? = null
        private set

    fun prepare(context: Context) {
        if (prepared) return
        synchronized(this) {
            if (prepared) return
            prepared = true // never retry every frame; a failed prepare stays failed until process restart
            try {
                val dir = File(context.filesDir, "tessdata")
                if (!dir.exists()) dir.mkdirs()
                if (!hasAsset(context, "tessdata/ara.traineddata") || !hasAsset(context, "tessdata/eng.traineddata")) {
                    lastError = "بيانات اللغة غير مثبتة (ara/eng.traineddata مفقودة)"
                    return
                }
                copyAssetIfMissing(context, dir, "ara.traineddata")
                copyAssetIfMissing(context, dir, "eng.traineddata")
                val api = TessBaseAPI()
                if (!api.init(context.filesDir.absolutePath, "eng+ara", TessBaseAPI.OEM_LSTM_ONLY)) {
                    lastError = "تعذر تهيئة محرك القراءة"
                    return
                }
                api.setVariable("tessedit_char_whitelist", "0123456789٠١٢٣٤٥٦٧٨٩")
                api.setVariable("classify_bln_numeric_mode", "1")
                tess = api
                ready = true
            } catch (t: Throwable) {
                lastError = "تعذر تجهيز محرك القراءة: ${t.message}"
            }
        }
    }

    private fun hasAsset(context: Context, path: String): Boolean =
        runCatching { context.assets.open(path).use { true } }.getOrDefault(false)

    /** Two independent renderings must agree exactly and both must be high confidence. */
    fun recognizeFast(context: Context, source: Bitmap, roi: Rect): Result {
        prepare(context)
        var crop: Bitmap? = null
        var limited: Bitmap? = null
        var binary: Bitmap? = null
        try {
            crop = safeCrop(source, roi)
            limited = limitSize(crop, MAX_SIDE)
            val api = tess ?: return Result("", 0, false)

            val first = run(api, limited)
            binary = otsu(limited)
            val second = run(api, binary)

            val same = first.text.isNotEmpty() && first.text == second.text
            val confidence = minOf(100, (first.confidence + second.confidence) / 2)
            val valid = isStrictDigits(first.text) && first.text.length in MIN_DIGITS..MAX_DIGITS
            val accepted = same && valid && first.confidence >= MIN_CONFIDENCE && second.confidence >= MIN_CONFIDENCE
            return if (accepted) Result(first.text, confidence, true) else Result("", confidence, false)
        } finally {
            if (binary != null && !binary.isRecycled) binary.recycle()
            if (limited != null && limited !== crop && !limited.isRecycled) limited.recycle()
            if (crop != null && !crop.isRecycled) crop.recycle()
        }
    }

    private data class Ocr(val text: String, val confidence: Int)

    private fun run(api: TessBaseAPI, bitmap: Bitmap): Ocr {
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
        api.setImage(bitmap)
        val raw = api.getUTF8Text().orEmpty()
        val normalized = normalizeDigitsStrict(raw)
        val text = if (normalized != null) normalized else ""
return Ocr(text, api.meanConfidence().coerceIn(0, 100))

    private fun normalizeDigitsStrict(value: String): String? {
        val s = value.replace("\n", "").replace("\r", "").replace(" ", "").replace("\t", "")
        if (s.isEmpty()) return ""
        val out = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                else -> ch
            }
            if (c !in '0'..'9') return null
            out.append(c)
        }
        return out.toString()
    }

    private fun isStrictDigits(value: String): Boolean = value.isNotEmpty() && value.all { it in '0'..'9' }

    private fun limitSize(source: Bitmap, maxSide: Int): Bitmap {
        val side = max(source.width, source.height)
        if (side <= maxSide) return source
        val scale = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun otsu(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        for (p in pixels) {
            val g = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            hist[g]++
        }
        val total = pixels.size
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var threshold = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val score = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (score > best) { best = score; threshold = t }
        }
        for (i in pixels.indices) {
            val p = pixels[i]
            val g = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            pixels[i] = if (g < threshold) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun safeCrop(source: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, source.width - 1)
        val top = r.top.coerceIn(0, source.height - 1)
        val right = r.right.coerceIn(left + 1, source.width)
        val bottom = r.bottom.coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun copyAssetIfMissing(context: Context, dir: File, name: String) {
        val target = File(dir, name)
        if (!target.exists()) context.assets.open("tessdata/$name").use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
    }
}
