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

package com.paddle.ocr.postprocess

object CTCDecoder {
    private const val BLANK_IDX = 0

    data class DecodedChar(
        val text: String,
        val confidence: Float,
        /** Horizontal position in the unpadded recognition crop, in [0, 1]. */
        val startFraction: Float,
        val endFraction: Float,
    )

    data class DecodedText(
        val text: String,
        val confidence: Float,
        val chars: List<DecodedChar>,
    )

    /**
     * Decode CTC while retaining character-to-time-axis alignment. validTimeSteps removes the
     * right-side batch padding introduced by RecPreprocessor so character fractions describe the
     * real crop rather than the padded tensor.
     */
    fun decode(
        output: FloatArray,
        shape: LongArray,
        characterList: List<String>,
        validTimeSteps: IntArray? = null,
    ): List<DecodedText> {
        val batchSize = shape[0].toInt()
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()
        val results = mutableListOf<DecodedText>()

        for (b in 0 until batchSize) {
            val baseOffset = b * timeSteps * numClasses
            val limit = (validTimeSteps?.getOrNull(b) ?: timeSteps).coerceIn(1, timeSteps)
            val indices = IntArray(limit)
            val probs = FloatArray(limit)

            for (t in 0 until limit) {
                val offset = baseOffset + t * numClasses
                var maxIdx = 0
                var maxVal = output[offset]
                for (c in 1 until numClasses) {
                    val v = output[offset + c]
                    if (v > maxVal) {
                        maxVal = v
                        maxIdx = c
                    }
                }
                indices[t] = maxIdx
                probs[t] = maxVal
            }

            val chars = mutableListOf<DecodedChar>()
            val sb = StringBuilder()
            var runStart = 0
            var runIdx = indices[0]

            fun finishRun(endExclusive: Int) {
                if (runIdx == BLANK_IDX) return
                val charIdx = runIdx - 1
                if (charIdx !in characterList.indices) return
                val token = characterList[charIdx]
                if (token.isEmpty()) return
                var sum = 0f
                var count = 0
                for (t in runStart until endExclusive.coerceAtMost(limit)) {
                    sum += probs[t]
                    count++
                }
                val confidence = if (count > 0) sum / count else 0f
                val start = (runStart / limit.toFloat()).coerceIn(0f, 1f)
                val end = (endExclusive / limit.toFloat()).coerceIn(start, 1f)
                sb.append(token)
                chars += DecodedChar(token, confidence, start, end)
            }

            for (t in 1 until limit) {
                val idx = indices[t]
                if (idx != runIdx) {
                    finishRun(t)
                    runStart = t
                    runIdx = idx
                }
            }
            finishRun(limit)

            val confidence = if (chars.isEmpty()) 0f else chars.map { it.confidence }.average().toFloat()
            results += DecodedText(sb.toString(), confidence, chars)
        }
        return results
    }
}
