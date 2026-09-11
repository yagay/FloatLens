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

/** Java-friendly, process-wide PP-OCRv6 bridge. The model is loaded once and reused. */
object PaddleOcrBridge {
    interface Callback {
        fun onSuccess(
            text: String,
            blocks: List<String>,
            totalMs: Long,
            lineCount: Int,
            averageConfidence: Float,
        )
        fun onFailure(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initMutex = Mutex()
    private val runMutex = Mutex()

    @Volatile
    private var engine: PaddleOCR? = null

    @JvmStatic
    fun recognize(context: Context, bitmap: Bitmap, callback: Callback) {
        val app = context.applicationContext
        scope.launch {
            try {
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    throw IllegalArgumentException("invalid bitmap")
                }
                val ocr = getOrCreate(app)
                val result = runMutex.withLock { ocr.recognize(bitmap) }
                val blocks = result.results.mapNotNull { item ->
                    item.text.trim().takeIf { it.isNotEmpty() }
                }
                val text = blocks.joinToString("\n").trim()
                val avg = if (result.results.isEmpty()) 0f
                else result.results.map { it.confidence }.average().toFloat()
                withContext(Dispatchers.Main) {
                    callback.onSuccess(text, blocks, result.totalTimeMs, result.lineCount, avg)
                }
            } catch (t: Throwable) {
                val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                withContext(Dispatchers.Main) { callback.onFailure(msg) }
            }
        }
    }

    @JvmStatic
    fun warmUp(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try { getOrCreate(app) } catch (_: Throwable) { }
        }
    }

    @JvmStatic
    fun isLoaded(): Boolean = engine != null

    private suspend fun getOrCreate(context: Context): PaddleOCR {
        engine?.let { return it }
        return initMutex.withLock {
            engine?.let { return@withLock it }
            if (!OpenCVUtils.init(context)) {
                throw IllegalStateException("OpenCV 初始化失败")
            }
            val config = PaddleOCRConfig(
                detThresh = 0.20f,
                detBoxThresh = 0.45f,
                detUnclipRatio = 1.4f,
                recScoreThresh = 0.0f,
                recBatchSize = 4,
            )
            val created = PaddleOCR.create(
                context = context,
                config = config,
                engineConfig = EngineConfig(numThreads = 4),
            )
            engine = created
            created
        }
    }
}
