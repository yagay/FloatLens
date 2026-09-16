package com.yagay.floatlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.engine.DetectionEngine
import com.paddle.ocr.engine.ORTSessionManager
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detection-only PP-OCR bridge used by Google Circle's background TextMap.
 *
 * This runtime maps only the detector ONNX model. It never loads the recognition model and never
 * decodes text. Recognition remains a separate lazy operation after a gesture hits a TextMap node.
 */
object PaddleTextDetectorBridge {
    interface Callback {
        fun onSuccess(regions: List<Rect>, totalMs: Long, model: Int, coldLoadMs: Long)
        fun onFailure(message: String)
    }

    private data class Runtime(
        val model: Int,
        val manager: ORTSessionManager,
        val detector: DetectionEngine,
        val modelBytes: Long,
        val modelModified: Long,
        val coldLoadMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeMutex = Mutex()
    private val runMutex = Mutex()
    private var runtime: Runtime? = null

    @JvmStatic
    fun detect(context: Context, bitmap: Bitmap, callback: Callback) {
        val app = context.applicationContext
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalArgumentException("invalid detector bitmap")
                }
                val model = preferredModel(app)
                    ?: throw IllegalStateException("text_detector_model_not_downloaded")

                val pair = runMutex.withLock {
                    val active = runtimeMutex.withLock { getOrCreate(app, model) }
                    Pair(active, active.detector.detect(bitmap))
                }
                val active = pair.first
                val result = pair.second
                val regions = result.boxes.mapNotNull {
                    boxRect(it, bitmap.width, bitmap.height)
                }.filter { !it.isEmpty }
                val elapsed = System.currentTimeMillis() - started

                DiagnosticLog.i(
                    app,
                    "TEXT_MAP_DETECTOR",
                    "success detOnly=true model=$model bitmap=${bitmap.width}x${bitmap.height}" +
                        " regions=${regions.size}" +
                        " preprocessMs=${result.preprocessMs}" +
                        " inferenceMs=${result.inferenceMs}" +
                        " postprocessMs=${result.postprocessMs}" +
                        " detectorMs=${result.timeMs}" +
                        " totalMs=$elapsed" +
                        " coldLoadMs=${active.coldLoadMs}" +
                        " inputShape=${result.inputShape}",
                )
                withContext(Dispatchers.Main) {
                    callback.onSuccess(regions, elapsed, model, active.coldLoadMs)
                }
            } catch (t: Throwable) {
                val message = describeThrowable(t)
                DiagnosticLog.i(app, "TEXT_MAP_DETECTOR", "failure detOnly=true $message")
                withContext(Dispatchers.Main) { callback.onFailure(message) }
            }
        }
    }

    private fun preferredModel(context: Context): Int? {
        if (OcrModelManager.isReady(context, OcrModelManager.SMALL)) return OcrModelManager.SMALL
        if (OcrModelManager.isReady(context, OcrModelManager.MEDIUM)) return OcrModelManager.MEDIUM
        return null
    }

    private fun getOrCreate(context: Context, model: Int): Runtime {
        val detFile = OcrModelManager.detFile(context, model)
        val bytes = detFile.length()
        val modified = detFile.lastModified()
        runtime?.let { existing ->
            if (existing.model == model && existing.modelBytes == bytes
                && existing.modelModified == modified && bytes > 0L) {
                return existing
            }
            try { existing.manager.release() } catch (_: Throwable) { }
            runtime = null
        }

        if (!OpenCVUtils.init(context)) {
            val detail = OpenCVUtils.lastError()?.takeIf { it.isNotBlank() }
                ?: "unknown native loader error"
            throw IllegalStateException("OpenCV 初始化失败: $detail")
        }
        if (!detFile.isFile || bytes <= 0L) throw IllegalStateException("detector_model_missing")

        // The frozen screen is already downscaled by the caller. resize_long keeps the detector
        // input bounded and deterministic even when a future caller supplies a larger bitmap.
        val config = PaddleOCRConfig(
            detLimitSideLen = 960,
            detLimitType = "resize_long",
            detMaxSideLimit = 1024,
            detThresh = 0.20f,
            detBoxThresh = 0.45f,
            detUnclipRatio = 1.30f,
            detMaxCandidates = 1200,
            detUseDilation = false,
            detScoreMode = "fast",
            detBoxType = "quad",
        )
        val manager = ORTSessionManager(context, EngineConfig(numThreads = 4))
        manager.loadDetectionModel(detFile.absolutePath)
        val created = Runtime(
            model = model,
            manager = manager,
            detector = DetectionEngine(manager, config),
            modelBytes = bytes,
            modelModified = modified,
            coldLoadMs = manager.coldLoadTimeMs,
        )
        runtime = created
        DiagnosticLog.i(
            context,
            "TEXT_MAP_DETECTOR",
            "loaded detOnly=true model=$model bytes=$bytes coldLoadMs=${created.coldLoadMs}",
        )
        return created
    }

    private fun boxRect(box: OCRBox, imageWidth: Int, imageHeight: Int): Rect? {
        if (imageWidth <= 0 || imageHeight <= 0 || box.points.isEmpty()) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        box.points.forEach { point ->
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null
        val left = minX.toInt().coerceIn(0, imageWidth - 1)
        val top = minY.toInt().coerceIn(0, imageHeight - 1)
        val right = kotlin.math.ceil(maxX.toDouble()).toInt().coerceIn(left + 1, imageWidth)
        val bottom = kotlin.math.ceil(maxY.toDouble()).toInt().coerceIn(top + 1, imageHeight)
        return Rect(left, top, right, bottom)
    }

    private fun describeThrowable(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            val name = current.javaClass.simpleName.ifBlank { current.javaClass.name }
            val message = current.message?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.isNotEmpty() }
            parts += if (message == null) name else "$name: $message"
            current = current.cause
            depth++
        }
        return parts.distinct().joinToString(" <- ").take(700).ifBlank { t.javaClass.name }
    }
}
