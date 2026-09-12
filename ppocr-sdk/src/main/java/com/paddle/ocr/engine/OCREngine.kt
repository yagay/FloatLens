// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.engine

import android.content.Context
import android.graphics.PointF
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.model.OCRBox
import com.paddle.ocr.model.OCRCharacter
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRResult
import com.paddle.ocr.postprocess.BoxSorter
import com.paddle.ocr.postprocess.CTCDecoder
import com.paddle.ocr.postprocess.QuadTextCrop
import com.paddle.ocr.util.BitmapUtils
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OCREngine(
    context: Context,
    private val config: PaddleOCRConfig,
    engineConfig: EngineConfig,
    detModelAsset: String = "models/det/inference.onnx",
    recModelAsset: String = "models/rec/inference.onnx",
    recConfigAsset: String = "models/rec/inference.yml",
) {
    private val ortManager = ORTSessionManager(context, engineConfig)
    private val detectionEngine: DetectionEngine
    private val recognitionEngine: RecognitionEngine
    val coldLoadTimeMs: Long get() = ortManager.coldLoadTimeMs

    init {
        val configured = try {
            ortManager.loadModels(detModelAsset, recModelAsset)
            ModelConfig.parse(context, recConfigAsset)
        } catch (t: Throwable) {
            ortManager.release()
            throw t
        }
        detectionEngine = DetectionEngine(ortManager, config)
        recognitionEngine = RecognitionEngine(ortManager, configured.characterList)
    }

    fun run(bitmap: android.graphics.Bitmap): OCREngineResult {
        val srcMat = BitmapUtils.bitmapToBGRMat(bitmap)
        return runWithOwnedMat(srcMat)
    }

    fun run(imageBytes: ByteArray): OCREngineResult {
        val srcMat = BitmapUtils.imdecodeBGR(imageBytes)
        if (srcMat.empty()) {
            srcMat.release()
            throw OCRError.InvalidImage()
        }
        return runWithOwnedMat(srcMat)
    }

    private fun runWithOwnedMat(srcMat: org.opencv.core.Mat): OCREngineResult {
        return try { run(srcMat) } finally { srcMat.release() }
    }

    private fun run(srcMat: org.opencv.core.Mat): OCREngineResult {
        val totalStart = System.currentTimeMillis()
        val detResult = detectionEngine.detect(srcMat)
        val boxes = detResult.boxes

        if (boxes.isEmpty()) {
            val elapsed = System.currentTimeMillis() - totalStart
            return OCREngineResult(
                results = emptyList(), detectionTimeMs = detResult.timeMs,
                recognitionTimeMs = 0, totalTimeMs = elapsed, lineCount = 0,
                detPreprocessMs = detResult.preprocessMs, detInferenceMs = detResult.inferenceMs,
                detPostprocessMs = detResult.postprocessMs, detInputShape = detResult.inputShape,
                coldLoadTimeMs = ortManager.coldLoadTimeMs,
            )
        }

        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)
        var totalRecPreMs = 0L
        var totalRecInfMs = 0L
        var totalRecPostMs = 0L
        var totalRecMs = 0L
        val allResults = mutableListOf<OCRResult>()
        val recInputShapes = mutableListOf<List<Int>>()
        val perLineRecMs = mutableListOf<Long>()
        val batchSize = config.recBatchSize.coerceAtLeast(1)

        var i = 0
        while (i < sortedBoxes.size) {
            val batchCrops = mutableListOf<org.opencv.core.Mat>()
            val batchBoxIndices = mutableListOf<Int>()
            var next = i
            while (next < sortedBoxes.size && batchCrops.size < batchSize) {
                val crop = QuadTextCrop.crop(srcMat, sortedBoxes[next])
                if (crop.rows() > 0 && crop.cols() > 0) {
                    batchCrops.add(crop)
                    batchBoxIndices.add(next)
                } else crop.release()
                next++
            }

            try {
                if (batchCrops.isNotEmpty()) {
                    val batchResult = recognitionEngine.recognize(batchCrops)
                    totalRecPreMs += batchResult.preprocessMs
                    totalRecInfMs += batchResult.inferenceMs
                    totalRecPostMs += batchResult.postprocessMs
                    totalRecMs += batchResult.timeMs
                    recInputShapes.add(batchResult.inputShape)
                    if (batchSize == 1) perLineRecMs.add(batchResult.timeMs)

                    for (j in batchResult.texts.indices) {
                        val boxIdx = batchBoxIndices[j]
                        val decoded = batchResult.texts[j]
                        if (decoded.confidence >= config.recScoreThresh) {
                            val lineBox = sortedBoxes[boxIdx]
                            val characters = buildCharacters(lineBox, decoded)
                            allResults.add(
                                OCRResult(
                                    box = lineBox,
                                    text = decoded.text,
                                    confidence = decoded.confidence,
                                    characters = characters,
                                )
                            )
                        }
                    }
                }
            } finally {
                batchCrops.forEach { it.release() }
            }
            i = next
        }

        val totalElapsed = System.currentTimeMillis() - totalStart
        val pipelineOverhead = totalElapsed - detResult.timeMs - totalRecMs
        return OCREngineResult(
            results = allResults,
            detectionTimeMs = detResult.timeMs,
            recognitionTimeMs = totalRecMs,
            totalTimeMs = totalElapsed,
            lineCount = allResults.size,
            detPreprocessMs = detResult.preprocessMs,
            detInferenceMs = detResult.inferenceMs,
            detPostprocessMs = detResult.postprocessMs,
            recPreprocessMs = totalRecPreMs,
            recInferenceMs = totalRecInfMs,
            recPostprocessMs = totalRecPostMs,
            pipelineOverheadMs = pipelineOverhead,
            coldLoadTimeMs = ortManager.coldLoadTimeMs,
            detInputShape = detResult.inputShape,
            recInputShapes = recInputShapes,
            perLineRecMs = perLineRecMs,
        )
    }

    /**
     * Convert CTC time-axis alignment back to source-image character geometry.
     *
     * Detection boxes are deliberately expanded for OCR recognition, so using the whole detected
     * line height/width for every character makes selection rectangles much larger than the glyph.
     * It is especially bad for a one-character CJK line: an expanded narrow box can be rotated as a
     * vertical crop and the old mapping then produced a very thin character occupying the full line.
     *
     * The mapping below keeps the detector's real quadrilateral orientation, expands the raw CTC run
     * only toward neighbouring character centres, and tightens both axes. CJK/full-width characters
     * are additionally constrained to a near-square cell, matching their visual glyph geometry.
     */
    private fun buildCharacters(box: OCRBox, decoded: CTCDecoder.DecodedText): List<OCRCharacter> {
        val decodedChars = decoded.chars.filter { it.text.isNotEmpty() }
        if (decodedChars.isEmpty() || box.points.size != 4) return emptyList()

        // DBPostProcessor guarantees TL, TR, BR, BL order.
        val tl = box.points[0]
        val tr = box.points[1]
        val br = box.points[2]
        val bl = box.points[3]
        val horizontalLength = max(1f, (distance(tl, tr) + distance(bl, br)) * 0.5f)
        val verticalLength = max(1f, (distance(tl, bl) + distance(tr, br)) * 0.5f)
        // Must match QuadTextCrop's rotation rule so recognition X maps to the correct source axis.
        val verticalCrop = verticalLength / horizontalLength >= 1.5f
        val majorLength = if (verticalCrop) verticalLength else horizontalLength
        val crossLength = if (verticalCrop) horizontalLength else verticalLength

        val rawStarts = FloatArray(decodedChars.size)
        val rawEnds = FloatArray(decodedChars.size)
        val centers = FloatArray(decodedChars.size)
        decodedChars.forEachIndexed { index, c ->
            val start = min(c.startFraction, c.endFraction).coerceIn(0f, 1f)
            val end = max(c.startFraction, c.endFraction).coerceIn(start, 1f)
            rawStarts[index] = start
            rawEnds[index] = end
            centers[index] = (start + end) * 0.5f
        }

        return decodedChars.mapIndexedNotNull { index, c ->
            val rawStart = rawStarts[index]
            val rawEnd = rawEnds[index]
            val center = centers[index]
            val rawSpan = max(1f / max(1f, majorLength), rawEnd - rawStart)

            val leftCell = when {
                index > 0 -> (centers[index - 1] + center) * 0.5f
                decodedChars.size > 1 -> center - (centers[1] - center) * 0.5f
                else -> center - rawSpan * 0.5f
            }.coerceIn(0f, center)
            val rightCell = when {
                index + 1 < decodedChars.size -> (center + centers[index + 1]) * 0.5f
                decodedChars.size > 1 -> center + (center - centers[index - 1]) * 0.5f
                else -> center + rawSpan * 0.5f
            }.coerceIn(center, 1f)

            val wideGlyph = isWideGlyph(c.text)
            // CTC activation is narrower than the visible glyph. Blend toward neighbour midpoints,
            // more strongly for CJK/full-width characters and conservatively for Latin text.
            val cellBlend = if (wideGlyph) 0.72f else 0.38f
            var start = mix(rawStart, leftCell, cellBlend).coerceIn(0f, center)
            var end = mix(rawEnd, rightCell, cellBlend).coerceIn(center, 1f)

            // A single CJK character must not occupy an entire tall/narrow expanded detection box.
            // Clamp its primary-axis size to roughly one glyph relative to the cross axis.
            if (wideGlyph) {
                val currentSpanPx = max(1f, majorLength * (end - start))
                val maxGlyphMajorPx = max(2f, crossLength * 1.12f)
                if (currentSpanPx > maxGlyphMajorPx) {
                    val half = (maxGlyphMajorPx / majorLength) * 0.5f
                    start = (center - half).coerceAtLeast(0f)
                    end = (center + half).coerceAtMost(1f)
                }
            }

            val majorSpanPx = max(1f, majorLength * (end - start))
            val targetCrossPx = if (wideGlyph) {
                min(crossLength * 0.86f, max(crossLength * 0.42f, majorSpanPx * 0.96f))
            } else {
                min(crossLength * 0.76f, max(crossLength * 0.50f, majorSpanPx * 1.55f))
            }
            val crossFill = (targetCrossPx / crossLength).coerceIn(0.30f, 0.90f)
            val crossInset = (1f - crossFill) * 0.5f

            val charPoints = if (verticalCrop) {
                val leftStart = lerp(tl, bl, start)
                val rightStart = lerp(tr, br, start)
                val leftEnd = lerp(tl, bl, end)
                val rightEnd = lerp(tr, br, end)
                listOf(
                    lerp(leftStart, rightStart, crossInset),
                    lerp(leftStart, rightStart, 1f - crossInset),
                    lerp(leftEnd, rightEnd, 1f - crossInset),
                    lerp(leftEnd, rightEnd, crossInset),
                )
            } else {
                val topStart = lerp(tl, tr, start)
                val bottomStart = lerp(bl, br, start)
                val topEnd = lerp(tl, tr, end)
                val bottomEnd = lerp(bl, br, end)
                listOf(
                    lerp(topStart, bottomStart, crossInset),
                    lerp(topEnd, bottomEnd, crossInset),
                    lerp(topEnd, bottomEnd, 1f - crossInset),
                    lerp(topStart, bottomStart, 1f - crossInset),
                )
            }
            OCRCharacter(c.text, OCRBox(charPoints), c.confidence)
        }
    }

    private fun lerp(a: PointF, b: PointF, t: Float): PointF {
        val v = t.coerceIn(0f, 1f)
        return PointF(a.x + (b.x - a.x) * v, a.y + (b.y - a.y) * v)
    }

    private fun mix(a: Float, b: Float, t: Float): Float {
        val v = t.coerceIn(0f, 1f)
        return a + (b - a) * v
    }

    private fun distance(a: PointF, b: PointF): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun isWideGlyph(value: String): Boolean {
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            if ((cp in 0x3400..0x4DBF)
                || (cp in 0x4E00..0x9FFF)
                || (cp in 0xF900..0xFAFF)
                || (cp in 0x20000..0x2FA1F)
                || (cp in 0x3000..0x303F)
                || (cp in 0xFF01..0xFF60)
                || (cp in 0xFFE0..0xFFE6)) return true
        }
        return false
    }

    fun release() { ortManager.release() }
}
