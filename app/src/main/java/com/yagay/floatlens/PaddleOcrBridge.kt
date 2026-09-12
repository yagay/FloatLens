package com.yagay.floatlens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** PP-OCR adapter. Engine-specific output is normalized to OcrDocument here. */
object PaddleOcrBridge {
    interface Callback {
        fun onSuccess(document: OcrDocument, totalMs: Long, lineCount: Int)
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
                val baseDocument = toDocument(app, result.results, bitmap.width, bitmap.height, model)
                val geometryStarted = System.currentTimeMillis()
                val document = OcrGeometryRefiner.refinePpWithUpscaledMlKit(app, bitmap, baseDocument)
                val geometryMs = System.currentTimeMillis() - geometryStarted
                withContext(Dispatchers.Main) {
                    callback.onSuccess(document, result.totalTimeMs + geometryMs, document.lines().size)
                }
            } catch (t: Throwable) {
                val msg = describeThrowable(t)
                DiagnosticLog.i(app, "PPOCRV6_BRIDGE", "failure model=$model $msg")
                withContext(Dispatchers.Main) { callback.onFailure(msg) }
            }
        }
    }

    private fun toDocument(
        context: Context,
        results: List<OCRResult>,
        imageWidth: Int,
        imageHeight: Int,
        model: Int,
    ): OcrDocument {
        val rawLines = mutableListOf<OcrDocument.Line>()
        var order = 0
        var nextGroup = 0

        results.forEachIndexed { lineIndex, item ->
            val lineText = item.text.trim()
            if (lineText.isEmpty()) return@forEachIndexed
            val lineRect = boxRect(item.box, imageWidth, imageHeight) ?: return@forEachIndexed
            val chars = mutableListOf<OcrDocument.CharUnit>()
            var group = nextGroup++

            for (character in item.characters) {
                val value = character.text
                if (value.isEmpty()) continue
                if (value.all { it.isWhitespace() }) {
                    group = nextGroup++
                    continue
                }
                val r = boxRect(character.box, imageWidth, imageHeight) ?: continue
                chars += OcrDocument.CharUnit(
                    value, r, character.confidence, lineIndex, group, order++,
                )
            }

            // Defensive compatibility fallback for model/runtime combinations that do not expose
            // CTC character alignment. This is not the primary path anymore.
            if (chars.isEmpty()) {
                val cps = lineText.codePoints().toArray()
                val visible = cps.count { !Character.isWhitespace(it) }.coerceAtLeast(1)
                var visibleIndex = 0
                for (cp in cps) {
                    if (Character.isWhitespace(cp)) {
                        group = nextGroup++
                        continue
                    }
                    val left = lineRect.left + lineRect.width() * visibleIndex / visible
                    val right = lineRect.left + lineRect.width() * (visibleIndex + 1) / visible
                    chars += OcrDocument.CharUnit(
                        String(Character.toChars(cp)),
                        Rect(left, lineRect.top, right.coerceAtLeast(left + 1), lineRect.bottom),
                        item.confidence, lineIndex, group, order++,
                    )
                    visibleIndex++
                }
            }

            rawLines += OcrDocument.Line(lineText, lineRect, item.confidence, chars)
        }

        val deduped = dedupeOverlappingLines(rawLines)
        if (deduped.size != rawLines.size) {
            DiagnosticLog.i(
                context,
                "PPOCR_DEDUP",
                "model=$model rawLines=${rawLines.size} keptLines=${deduped.size} removed=${rawLines.size - deduped.size}",
            )
        }

        val lines = reindexLines(deduped)
        val blocks = lines.map { it.text().trim() }
        val text = blocks.joinToString("\n").trim()
        val avg = if (lines.isEmpty()) 0f else lines.map { it.confidence() }.average().toFloat()
        return OcrDocument(
            text, blocks, lines, "ppocr-$model", avg, 0.0, imageWidth, imageHeight,
        )
    }

    /**
     * Paddle DB post-processing can return nested/near-identical contours for the same visual text.
     * Keep exactly one logical OCR line before any ML Kit geometry rescue so selection never sees
     * two overlapping copies of the same text.
     */
    private fun dedupeOverlappingLines(source: List<OcrDocument.Line>): List<OcrDocument.Line> {
        if (source.size < 2) return source

        // Higher-confidence results win. For effectively equal confidence, the tighter box wins.
        val ranked = source.sortedWith(
            compareByDescending<OcrDocument.Line> { it.confidence() }
                .thenBy { area(it.bounds()) }
        )
        val kept = mutableListOf<OcrDocument.Line>()
        for (candidate in ranked) {
            val duplicate = kept.any { existing -> duplicateVisualLine(existing, candidate) }
            if (!duplicate) kept += candidate
        }

        return kept.sortedWith(
            compareBy<OcrDocument.Line> { it.bounds().centerY() }
                .thenBy { it.bounds().left }
        )
    }

    private fun duplicateVisualLine(a: OcrDocument.Line, b: OcrDocument.Line): Boolean {
        val ar = a.bounds()
        val br = b.bounds()
        if (ar.isEmpty || br.isEmpty || !Rect.intersects(ar, br)) return false

        val intersection = Rect()
        if (!intersection.setIntersect(ar, br)) return false
        val overlap = area(intersection).toFloat()
        val aArea = max(1L, area(ar))
        val bArea = max(1L, area(br))
        val minCoverage = overlap / min(aArea, bArea).toFloat()
        val union = max(1f, (aArea + bArea).toFloat() - overlap)
        val iou = overlap / union

        val at = compactText(a.text())
        val bt = compactText(b.text())
        if (at.isEmpty() || bt.isEmpty()) return false
        val exact = at == bt
        val related = at.contains(bt) || bt.contains(at)
        val lengthRatio = min(at.codePointCount(0, at.length), bt.codePointCount(0, bt.length)).toFloat() /
            max(1, max(at.codePointCount(0, at.length), bt.codePointCount(0, bt.length))).toFloat()

        // Exact duplicates can differ slightly because DB's unclip creates nested rectangles.
        if (exact && (minCoverage >= 0.52f || iou >= 0.38f)) return true

        // A duplicate recognizer pass can lose or add one punctuation/character. Require much
        // stronger spatial containment before treating those lines as the same visual line.
        if (related && lengthRatio >= 0.78f && minCoverage >= 0.72f) return true

        // Final guard for two almost-identical boxes with a one-character OCR disagreement.
        if (lengthRatio >= 0.88f && minCoverage >= 0.90f && normalizedEditSimilarity(at, bt) >= 0.82f) return true
        return false
    }

    private fun normalizedEditSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val aa = a.codePoints().toArray()
        val bb = b.codePoints().toArray()
        var prev = IntArray(bb.size + 1) { it }
        for (i in aa.indices) {
            val cur = IntArray(bb.size + 1)
            cur[0] = i + 1
            for (j in bb.indices) {
                val cost = if (aa[i] == bb[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = cur
        }
        val distance = prev[bb.size]
        return 1f - distance / max(aa.size, bb.size).toFloat()
    }

    private fun compactText(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return buildString(value.length) {
            value.codePoints().forEach { cp ->
                if (!Character.isWhitespace(cp)) appendCodePoint(Character.toLowerCase(cp))
            }
        }.lowercase()
    }

    private fun reindexLines(source: List<OcrDocument.Line>): List<OcrDocument.Line> {
        val out = mutableListOf<OcrDocument.Line>()
        var order = 0
        var group = 0
        source.forEachIndexed { lineIndex, line ->
            val chars = mutableListOf<OcrDocument.CharUnit>()
            var currentOldGroup: Int? = null
            var currentNewGroup = group++
            line.chars().forEach { c ->
                if (currentOldGroup == null) {
                    currentOldGroup = c.group()
                } else if (c.group() != currentOldGroup) {
                    currentOldGroup = c.group()
                    currentNewGroup = group++
                }
                chars += OcrDocument.CharUnit(
                    c.text(), c.bounds(), c.confidence(), lineIndex, currentNewGroup, order++,
                )
            }
            out += OcrDocument.Line(line.text(), line.bounds(), line.confidence(), chars)
        }
        return out
    }

    private fun area(rect: Rect): Long = max(0, rect.width()).toLong() * max(0, rect.height()).toLong()

    private fun boxRect(box: OCRBox, imageWidth: Int, imageHeight: Int): Rect? {
        val points = box.points
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
            val created = if (!OpenCVUtils.init(context)) {
                val detail = OpenCVUtils.lastError()?.takeIf { it.isNotBlank() }
                    ?: "unknown native loader error"
                DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_failed detail=$detail")
                throw IllegalStateException("OpenCV 初始化失败: $detail")
            } else {
                DiagnosticLog.i(context, "PPOCRV6_BRIDGE", "opencv_init_ok")
                if (!OcrModelManager.isReady(context, model)) throw IllegalStateException("model_not_downloaded")
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
