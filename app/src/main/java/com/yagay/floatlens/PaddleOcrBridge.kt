package com.yagay.floatlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PaddleOcrBridge {
    interface Callback {
        fun onSuccess(
            text: String,
            blocks: List<String>,
            words: List<SpatialOcrEngine.Word>,
            totalMs: Long,
            lineCount: Int,
            averageConfidence: Float,
        )
        fun onFailure(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    private val runMutex = Mutex()
    private val engines = mutableMapOf<Int, PaddleOCR>()

    @JvmStatic
    fun recognize(context: Context, bitmap: Bitmap, model: Int, callback: Callback) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalArgumentException("invalid bitmap")
                }
                if (!OcrModelManager.isReady(app, model)) throw IllegalStateException("model_not_downloaded")
                val ocr = getOrCreate(app, model)
                val result = runMutex.withLock { ocr.recognize(bitmap) }
                val blocks = result.results.mapNotNull { item -> item.text.trim().takeIf { it.isNotEmpty() } }
                val text = blocks.joinToString("\n").trim()
                val avg = if (result.results.isEmpty()) 0f
                else result.results.map { it.confidence }.average().toFloat()
                val words = toSpatialWords(result.results, bitmap.width, bitmap.height)
                withContext(Dispatchers.Main) {
                    callback.onSuccess(text, blocks, words, result.totalTimeMs, result.lineCount, avg)
                }
            } catch (t: Throwable) {
                val msg = describeThrowable(t)
                DiagnosticLog.i(app, "PPOCRV6_BRIDGE", "failure model=$model $msg")
                withContext(Dispatchers.Main) { callback.onFailure(msg) }
            }
        }
    }

    private fun toSpatialWords(
        results: List<OCRResult>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<SpatialOcrEngine.Word> {
        val out = mutableListOf<SpatialOcrEngine.Word>()
        var order = 0
        var nextGroup = 0
        results.forEachIndexed { lineIndex, item ->
            val text = item.text.trim()
            if (text.isEmpty()) return@forEachIndexed
            val rect = boxRect(item, imageWidth, imageHeight) ?: return@forEachIndexed
            val cpCount = text.codePointCount(0, text.length).coerceAtLeast(1)
            var charOffset = 0
            var cpIndex = 0
            var group = nextGroup++
            while (charOffset < text.length) {
                val cp = text.codePointAt(charOffset)
                val charCount = Character.charCount(cp)
                val value = String(Character.toChars(cp))
                if (Character.isWhitespace(cp)) {
                    group = nextGroup++
                } else {
                    val left = rect.left + ((rect.width().toLong() * cpIndex) / cpCount).toInt()
                    val right = rect.left + ((rect.width().toLong() * (cpIndex + 1)) / cpCount).toInt()
                    val unit = Rect(
                        left.coerceIn(0, imageWidth - 1),
                        rect.top.coerceIn(0, imageHeight - 1),
                        right.coerceAtLeast(left + 1).coerceAtMost(imageWidth),
                        rect.bottom.coerceAtLeast(rect.top + 1).coerceAtMost(imageHeight),
                    )
                    out.add(SpatialOcrEngine.Word(value, unit, lineIndex, group, order++))
                }
                charOffset += charCount
                cpIndex++
            }
        }
        return out
    }

    private fun boxRect(item: OCRResult, imageWidth: Int, imageHeight: Int): Rect? {
        val points = item.box.points
        if (points.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        points.forEach { p ->
            minX = minOf(minX, p.x)
            minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x)
            maxY = maxOf(maxY, p.y)
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null
        val left = minX.toInt().coerceIn(0, imageWidth - 1)
        val top = minY.toInt().coerceIn(0, imageHeight - 1)
        val right = kotlin.math.ceil(maxX.toDouble()).toInt().coerceIn(left + 1, imageWidth)
        val bottom = kotlin.math.ceil(maxY.toDouble()).toInt().coerceIn(top + 1, imageHeight)
        return Rect(left, top, right, bottom)
    }

    @JvmStatic fun isLoaded(model: Int): Boolean = synchronized(engines) { engines.containsKey(model) }

    @JvmStatic
    fun releaseModel(model: Int) {
        scope.launch {
            initMutex.withLock {
                val old = synchronized(engines) { engines.remove(model) }
                try { old?.release() } catch (_: Throwable) { }
            }
        }
    }

    private suspend fun getOrCreate(context: Context, model: Int): PaddleOCR {
        synchronized(engines) { engines[model] }?.let { return it }
        return initMutex.withLock {
            synchronized(engines) { engines[model] }?.let { return@withLock it }
            val created = try {
                if (!OpenCVUtils.init(context)) {
                    val detail = OpenCVUtils.lastError()?.takeIf { it.isNotBlank() }
                        ?: "unknown native loader error"
                    DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_failed detail=$detail")
                    throw IllegalStateException("OpenCV 初始化失败: $detail")
                }
                DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_ok")
                if (!OcrModelManager.isReady(context, model)) {
                    throw IllegalStateException("model_not_downloaded")
                }
                val config = PaddleOCRConfig(
                    detThresh = 0.20f,
                    detBoxThresh = 0.45f,
                    detUnclipRatio = 1.4f,
                    recScoreThresh = 0.0f,
                    recBatchSize = if (model == OcrModelManager.MEDIUM) 2 else 4,
                )
                PaddleOCR.create(
                    context = context,
                    config = config,
                    engineConfig = EngineConfig(numThreads = 4),
                    detModelAssetPath = OcrModelManager.detFile(context, model).absolutePath,
                    recModelAssetPath = OcrModelManager.recFile(context, model).absolutePath,
                    recConfigAssetPath = OcrModelManager.ymlFile(context, model).absolutePath,
                )
            } catch (t: Throwable) {
                throw t
            }
            synchronized(engines) { engines[model] = created }
            DiagnosticLog.i(
                context,
                "PPOCRV6_BRIDGE",
                "loaded model=$model coldLoadMs=${created.coldLoadTimeMs} detBytes=${OcrModelManager.detFile(context, model).length()} recBytes=${OcrModelManager.recFile(context, model).length()}",
            )
            created
        }
    }

    private fun describeThrowable(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 8) {
            val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val message = current.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val part = if (message == null) name else "$name: $message"
            if (parts.lastOrNull() != part) parts.add(part)
            current = current.cause
            depth++
        }
        return parts.joinToString(" <- ").take(900).ifBlank { t.javaClass.name }
    }
}
