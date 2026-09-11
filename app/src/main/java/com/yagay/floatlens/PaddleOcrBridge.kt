package com.yagay.floatlens

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
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
        fun onSuccess(text: String, blocks: List<String>, totalMs: Long, lineCount: Int, averageConfidence: Float)
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
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) throw IllegalArgumentException("invalid bitmap")
                if (!OcrModelManager.isReady(app, model)) throw IllegalStateException("model_not_downloaded")
                val ocr = getOrCreate(app, model)
                val result = runMutex.withLock { ocr.recognize(bitmap) }
                val blocks = result.results.mapNotNull { item -> item.text.trim().takeIf { it.isNotEmpty() } }
                val text = blocks.joinToString("\n").trim()
                val avg = if (result.results.isEmpty()) 0f else result.results.map { it.confidence }.average().toFloat()
                withContext(Dispatchers.Main) { callback.onSuccess(text, blocks, result.totalTimeMs, result.lineCount, avg) }
            } catch (t: Throwable) {
                val msg = describeThrowable(t)
                DiagnosticLog.i(app, "PPOCRV6_BRIDGE", "failure model=$model $msg")
                withContext(Dispatchers.Main) { callback.onFailure(msg) }
            }
        }
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
            if (!OpenCVUtils.init(context)) {
                val detail = OpenCVUtils.lastError()?.takeIf { it.isNotBlank() } ?: "unknown native loader error"
                DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_failed detail=$detail")
                throw IllegalStateException("OpenCV 初始化失败: $detail")
            }
            DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_ok")
            if (!OcrModelManager.isReady(context, model)) throw IllegalStateException("model_not_downloaded")
            val config = PaddleOCRConfig(
                detThresh = 0.20f,
                detBoxThresh = 0.45f,
                detUnclipRatio = 1.4f,
                recScoreThresh = 0.0f,
                recBatchSize = if (model == OcrModelManager.MEDIUM) 2 else 4,
            )
            val created = PaddleOCR.create(
                context = context,
                config = config,
                engineConfig = EngineConfig(numThreads = 4),
                detModelAssetPath = OcrModelManager.detFile(context, model).absolutePath,
                recModelAssetPath = OcrModelManager.recFile(context, model).absolutePath,
                recConfigAssetPath = OcrModelManager.ymlFile(context, model).absolutePath,
            )
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
