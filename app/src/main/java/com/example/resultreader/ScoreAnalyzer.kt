// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

object ScoreAnalyzer {
    data class ScoreResult(
        val totalScore: Int,
        val cleanCount: Int,
        val sectionScores: List<Int?> // ← Int → Int? に変更！
    )

    private const val roiSize = 40
    private const val threshold = 0.05f

    fun analyze(scoreBitmap: Bitmap): ScoreResult {
        val width = scoreBitmap.width
        val height = scoreBitmap.height

        val rows = 5
        val cols = 12

        val cellWidth = width / cols
        val cellHeight = height / rows

        var totalScore = 0
        var cleanCount = 0
        val sectionScores = mutableListOf<Int?>()

        for (col in 0 until cols) {
            val detectedScores = mutableListOf<Int>()
            val blackRates = mutableListOf<Float>()
            var punchCount = 0

            for (row in 0 until rows) {
                val centerX = col * cellWidth + cellWidth / 2
                val centerY = row * cellHeight + cellHeight / 2

                val blackRate = calcBlackRate(scoreBitmap, centerX, centerY)
                blackRates.add(blackRate)

                if (blackRate >= threshold) {
                    punchCount++
                    val score = when (row) {
                        0 -> 0
                        1 -> 1
                        2 -> 2
                        3 -> 3
                        4 -> 5
                        else -> -1
                    }
                    if (score != -1) detectedScores.add(score)
                    Log.d("ScoreAnalyzer", "セクション$col 点$row: 黒率=$blackRate ← パンチ！")
                } else {
                    Log.d("ScoreAnalyzer", "セクション$col 点$row: 黒率=$blackRate")
                }
            }

            val finalScore: Int? = when {
                punchCount == 0 -> null // ← パンチ無しはスコアなし！
                punchCount == 1 -> detectedScores.firstOrNull()
                punchCount == 4 -> (0..4).toSet().minus(detectedScores.toSet()).firstOrNull() ?: 0
                punchCount == 5 -> 99
                else -> 99
            }

            sectionScores.add(finalScore)

            if (finalScore != null && finalScore != 99) {
                totalScore += finalScore
                if (finalScore == 0) cleanCount++
            }
        }

        return ScoreResult(totalScore, cleanCount, sectionScores)
    }

    private fun calcBlackRate(bitmap: Bitmap, centerX: Int, centerY: Int): Float {
        val width = bitmap.width
        val height = bitmap.height
        var blackPixels = 0
        var totalPixels = 0

        for (dy in -roiSize..roiSize) {
            for (dx in -roiSize..roiSize) {
                val x = centerX + dx
                val y = centerY + dy

                if (x in 0 until width && y in 0 until height) {
                    val pixel = bitmap.getPixel(x, y)
                    val brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
                    if (brightness < 300) blackPixels++
                    totalPixels++
                }
            }
        }

        return if (totalPixels > 0) blackPixels.toFloat() / totalPixels else 0f
    }
}