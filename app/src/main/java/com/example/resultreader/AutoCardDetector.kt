package com.example.resultreader

import kotlin.math.abs

/**
 * 白カード検出 → 安定判定 → 発火(FIRE) をまとめた独立ロジック
 * CameraActivity は「数値を渡す」「FIREならstartOcrCapture」だけにする。
 *
 * 【狙い】
 * 1) カード無しでは絶対発火しない（誤発火ゼロ最優先）
 * 2) カード無し→置く→発火
 * 3) 保存後に resetForNextCard() で次カード待ちに復帰（カード置いたままでも再発火可能）
 */
class AutoCardDetector(
    // --- カード存在判定（最優先ガード） ---
    // 現場ログで「カードあり」が white≈0.15〜0.20 になるケースがあるため、ここは低めに設定。
    private val presentWhiteRatioThr: Float = 0.12f,
    private val presentAvgLumaThr: Float = 55f,

    // --- 安定判定（AE微振動を許容） ---
    // AEが揺れても stable が溜まるように、差分閾値は大きめ。
    private val stableLumaDiffThr: Float = 40f,
    private val stableWhiteDiffThr: Float = 0.30f,

    // 何フレーム連続で安定したら発火するか（誤発火抑えつつ確実に）
    private val stableFramesToFire: Int = 2,

    // ログ間引き
    private val logEveryNFrames: Int = 10,
) {

    data class Inputs(
        val avgLuma: Float,
        val whiteRatio: Float,

        // 発火を止める条件（CameraActivityの状態をそのまま渡す）
        val armed: Boolean,
        val suspended: Boolean,
        val isOcrRunning: Boolean,
        val hasScoreResult: Boolean,
    )

    sealed class Decision {
        data object None : Decision()
        data object Fire : Decision()
    }

    private var stableFrameCount: Int = 0
    private var lastAvgLuma: Float = -1f
    private var lastWhiteRatio: Float = -1f

    // 「同じカードで連続発火」防止用（保存後に必ずresetする運用）
    private var firedOnceOnThisCard: Boolean = false

    private var frameCounter: Int = 0

    /**
     * 保存後 / OCR完了後に「次カード待ち」へ復帰させるために呼ぶ
     * ※カードが置いたままでも、ここで firedOnceOnThisCard を解除できるので再発火できる。
     */
    fun resetForNextCard(reason: String = "") {
        stableFrameCount = 0
        lastAvgLuma = -1f
        lastWhiteRatio = -1f
        firedOnceOnThisCard = false
        // 必要ならデバッグログ追加OK
        // println("AutoCard resetForNextCard: $reason")
    }

    fun onFrame(input: Inputs): Decision {
        frameCounter++

        // 1) まず「カードがあるか」を最優先（カード無しで絶対に積まない）
        val isCardPresent =
            (input.whiteRatio >= presentWhiteRatioThr) &&
                    (input.avgLuma >= presentAvgLumaThr)

        if (!isCardPresent) {
            // カード無し：安定カウントを必ず0に戻す（誤発火ゼロの要）
            stableFrameCount = 0
            lastAvgLuma = -1f
            lastWhiteRatio = -1f

            // 次カードを確実に拾うため、カードが消えたら解除
            firedOnceOnThisCard = false

            logIfNeeded(input, present = false)
            return Decision.None
        }

        // 2) 発火の前提条件（要件固定ガード）
        val canConsiderFire =
            input.armed &&
                    !input.suspended &&
                    !input.isOcrRunning &&
                    !input.hasScoreResult &&
                    !firedOnceOnThisCard

        // 3) 安定判定（present=true の時だけ stable を溜める）
        //    ※canConsiderFire=falseでも last は更新しておく（再アーム直後に積みやすくする）
        if (lastAvgLuma < 0f || lastWhiteRatio < 0f) {
            // presentになった初回は 1 から開始（重要）
            stableFrameCount = 1
        } else {
            val lumaDiff = abs(input.avgLuma - lastAvgLuma)
            val whiteDiff = abs(input.whiteRatio - lastWhiteRatio)

            val ok = (lumaDiff <= stableLumaDiffThr) && (whiteDiff <= stableWhiteDiffThr)
            stableFrameCount = if (ok) stableFrameCount + 1 else 0
        }

        // 次フレーム比較用は最後に更新（順番重要）
        lastAvgLuma = input.avgLuma
        lastWhiteRatio = input.whiteRatio

        // 発火できない状態ならここで終了（stableは溜めるが、発火はしない）
        if (!canConsiderFire) {
            logIfNeeded(input, present = true)
            return Decision.None
        }

        // 4) 発火
        val shouldFire = stableFrameCount >= stableFramesToFire
        if (shouldFire) {
            firedOnceOnThisCard = true
            stableFrameCount = 0
            logIfNeeded(input, present = true, fired = true)
            return Decision.Fire
        }

        logIfNeeded(input, present = true)
        return Decision.None
    }

    private fun logIfNeeded(input: Inputs, present: Boolean, fired: Boolean = false) {
        if (frameCounter % logEveryNFrames != 0 && !fired) return

        println(
            "AutoCardRAW avg=${input.avgLuma} white=${input.whiteRatio} " +
                    "present=$present stable=$stableFrameCount armed=${input.armed} " +
                    "suspended=${input.suspended} ocr=${input.isOcrRunning} " +
                    "result=${input.hasScoreResult} fired=$fired"
        )
    }
}
