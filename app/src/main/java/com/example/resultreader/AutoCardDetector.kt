package com.example.resultreader

/**
 * 最小・堅牢：カードが「無い→ある」になった瞬間だけ Fire
 * - OCR中は絶対にFireしない
 * - 置きっぱ連発しない（presentの立ち上がりだけ）
 */
//自動発火感度調整
class AutoCardDetector(
    private val presentWhiteRatioThr: Float = 0.06f,
    private val presentAvgLumaThr: Float = 55f,
    private val cooldownMs: Long = 800L,
    private val logEveryNFrames: Int = 10
)

{
    sealed class Decision {
        data object None : Decision()
        data object Fire : Decision()
    }

    private var frameCounter = 0
    private var lastPresent = false
    private var lastFireAt = 0L

    fun reset(reason: String = "") {
        lastPresent = false
        lastFireAt = 0L
        // println("AutoCard reset: $reason")
    }

    fun onFrame(avgLuma: Float, whiteRatio: Float, isOcrRunning: Boolean): Decision {
        frameCounter++

        val present = (whiteRatio >= presentWhiteRatioThr) && (avgLuma >= presentAvgLumaThr)

        // OCR中は絶対にFireしない + 状態も進めない（ここ重要）
        if (isOcrRunning) {
            logIfNeeded(avgLuma, whiteRatio, present, fired = false, isOcrRunning = true)
            return Decision.None
        }

        if (!present) {
            lastPresent = false
            logIfNeeded(avgLuma, whiteRatio, present, fired = false, isOcrRunning = false)
            return Decision.None
        }

        val risingEdge = !lastPresent && present
        lastPresent = true

        if (!risingEdge) {
            logIfNeeded(avgLuma, whiteRatio, present, fired = false, isOcrRunning = false)
            return Decision.None
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFireAt < cooldownMs) {
            logIfNeeded(avgLuma, whiteRatio, present, fired = false, isOcrRunning = false)
            return Decision.None
        }

        lastFireAt = now
        logIfNeeded(avgLuma, whiteRatio, present, fired = true, isOcrRunning = false)
        return Decision.Fire
    }


    private fun logIfNeeded(
        avgLuma: Float,
        whiteRatio: Float,
        present: Boolean,
        fired: Boolean,
        isOcrRunning: Boolean
    ) {
        if (frameCounter % logEveryNFrames != 0 && !fired) return
        println("AutoCard avg=$avgLuma white=$whiteRatio present=$present ocr=$isOcrRunning fired=$fired")
    }
}
