// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0

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
     * Convert CTC time-axis fractions back to source-image geometry. Horizontal lines map along X;
     * vertical crops are rotated by QuadTextCrop, so their recognition X axis maps top-to-bottom Y.
     */
    private fun buildCharacters(box: OCRBox, decoded: CTCDecoder.DecodedText): List<OCRCharacter> {
        if (decoded.chars.isEmpty() || box.points.isEmpty()) return emptyList()
        val minX = box.points.minOf { it.x }
        val maxX = box.points.maxOf { it.x }
        val minY = box.points.minOf { it.y }
        val maxY = box.points.maxOf { it.y }
        val width = max(1f, maxX - minX)
        val height = max(1f, maxY - minY)
        val vertical = height / width >= 1.5f

        return decoded.chars.mapNotNull { c ->
            if (c.text.isEmpty()) return@mapNotNull null
            val start = min(c.startFraction, c.endFraction).coerceIn(0f, 1f)
            val end = max(c.startFraction, c.endFraction).coerceIn(start, 1f)
            val charBox = if (vertical) {
                val top = minY + height * start
                val bottom = minY + height * end
                rectBox(minX, top, maxX, max(bottom, top + 1f))
            } else {
                val left = minX + width * start
                val right = minX + width * end
                rectBox(left, minY, max(right, left + 1f), maxY)
            }
            OCRCharacter(c.text, charBox, c.confidence)
        }
    }

    private fun rectBox(left: Float, top: Float, right: Float, bottom: Float): OCRBox = OCRBox(
        listOf(
            PointF(left, top), PointF(right, top),
            PointF(right, bottom), PointF(left, bottom),
        )
    )

    fun release() { ortManager.release() }
}
