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

package com.paddle.ocr.util

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader

object OpenCVUtils {

    @Volatile
    private var initialized = false

    @Volatile
    private var initError: String? = null

    fun init(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (initialized) return true

        synchronized(this) {
            if (initialized) return true

            try {
                val ok = OpenCVLoader.initDebug()
                if (ok) {
                    initialized = true
                    initError = null
                    Log.i("OpenCVUtils", "OpenCV initialized successfully")
                } else {
                    initError = "OpenCVLoader.initDebug() returned false"
                    Log.e("OpenCVUtils", initError ?: "OpenCV initialization failed")
                }
            } catch (t: Throwable) {
                initError = describeThrowable(t)
                Log.e("OpenCVUtils", "Failed to initialize OpenCV: $initError", t)
            }
            return initialized
        }
    }

    fun lastError(): String? = initError

    private fun describeThrowable(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
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
