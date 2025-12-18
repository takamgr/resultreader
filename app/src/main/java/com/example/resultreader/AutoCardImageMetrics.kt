package com.example.resultreader

import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

object AutoCardImageMetrics {

/**
 * 超軽量：Y平面だけを見る
 * - avgLuma: 明るさ平均（0-255）
 * - whiteRatio: 「白っぽい画素」の割合
 */
fun from(imageProxy: ImageProxy): Pair<Float, Float> {
    val yBuffer = imageProxy.planes[0].buffer
    val ySize = yBuffer.remaining()
    if (ySize <= 0) return 0f to 0f

    val yBytes = ByteArray(ySize)
    yBuffer.get(yBytes)

    var sum = 0L
    var whiteCount = 0L

    // 「白」と判定する閾値（ここは後で調整ポイント）
    val whiteThr = 210

    for (b in yBytes) {
        val v = b.toInt() and 0xFF
        sum += v
        if (v >= whiteThr) whiteCount++
    }

    val avg = (sum.toDouble() / ySize.toDouble()).toFloat()
    val ratio = (whiteCount.toDouble() / ySize.toDouble()).toFloat()

    return avg to ratio
}
}
