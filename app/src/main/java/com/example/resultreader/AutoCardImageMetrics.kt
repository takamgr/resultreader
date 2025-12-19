package com.example.resultreader

import androidx.camera.core.ImageProxy
import kotlin.math.max

/**
 * ImageProxy(Y)から
 * - avgLuma: 平均輝度（0..255）
 * - whiteRatio: 白画素率（0..1）
 *
 * 速さ優先：だいたい 40x40 へ間引きサンプリング
 */
object AutoCardImageMetrics {
    fun from(image: ImageProxy): Pair<Float, Float> {
        val yPlane = image.planes[0]
        val buffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride

        val width = image.width
        val height = image.height

        val stepX = max(1, width / 40)
        val stepY = max(1, height / 40)

        val whiteThr = 235

        var sum = 0L
        var count = 0L
        var whiteCount = 0L

        buffer.rewind()

        for (y in 0 until height step stepY) {
            val rowStart = y * rowStride
            for (x in 0 until width step stepX) {
                val index = rowStart + x * pixelStride
                if (index < 0 || index >= buffer.limit()) continue
                val v = buffer.get(index).toInt() and 0xFF
                sum += v
                count++
                if (v >= whiteThr) whiteCount++
            }
        }

        if (count <= 0) return 0f to 0f

        val avg = (sum.toDouble() / count.toDouble()).toFloat()
        val whiteRatio = (whiteCount.toDouble() / count.toDouble()).toFloat()
        return avg to whiteRatio
    }
}
