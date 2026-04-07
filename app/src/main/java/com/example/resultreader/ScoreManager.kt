package com.example.resultreader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class ScoreManager(
    private val context: AppCompatActivity,
    private val scoreLabelViews: List<TextView>,
    private val resultText: TextView,
    private val scorePreview: ImageView,
    private val guideOverlay: GuideOverlayView,
    private val confirmButton: Button,
    private val getSelectedPattern: () -> TournamentPattern,
    private val onPlayJudgeSound: (Boolean) -> Unit,
    private val onSetHasScoreResult: (Boolean) -> Unit,
    private val onSetLastOcrHadEntry: (Boolean) -> Unit,
    private val onSetPendingBitmap: (Bitmap?) -> Unit
) {

    fun recalculateScore() {
        var totalScore = 0
        var cleanCount = 0
        var hasError = false

        val totalCount = when (getSelectedPattern()) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }

        for ((index, label) in scoreLabelViews.withIndex()) {
            var scoreText = label.text.toString().trim()

            // 🔥 設定外の位置にスコアがある → 強制99
            if (index >= totalCount && scoreText in listOf("0", "1", "2", "3", "5")) {
                scoreText = "99"
                label.text = "99"
                label.setBackgroundResource(R.drawable.bg_score_unknown)
            }

            // ✅ 有効範囲に99・空欄・ダッシュなど → エラー
            if (index < totalCount && scoreText in listOf("", "-", "ー", "―", "99")) {
                hasError = true
            }

            Log.d("SAVE_CHECK", "ラベル $index = \"$scoreText\"")

            when (scoreText) {
                "0" -> cleanCount++
                "1" -> totalScore += 1
                "2" -> totalScore += 2
                "3" -> totalScore += 3
                "5" -> totalScore += 5
            }
        }

        val pointText = context.findViewById<TextView>(R.id.scorePointText)
        val cleanText = context.findViewById<TextView>(R.id.scoreCleanText)

        if (hasError) {
            pointText.text = "G:　-"
            cleanText.text = "C:　-"
            confirmButton.visibility = View.GONE
            guideOverlay.setDetected("yellow")
            Toast.makeText(context, "⚠️ スコアに空欄やエラー（99など）が含まれています", Toast.LENGTH_SHORT).show()

            // ★ 要確認音を1回だけ鳴らす
            onPlayJudgeSound(false)
        } else {
            pointText.text = "G:　$totalScore"
            cleanText.text = "C:　$cleanCount"
            confirmButton.visibility = View.VISIBLE
            guideOverlay.setDetected("green")

            // ★ 正解音を1回だけ鳴らす
            onPlayJudgeSound(true)
        }
    }

    fun showScoreInputDialog(targetLabel: TextView) {
        val options = arrayOf("0", "1", "2", "3", "5", "-")

        val builder = AlertDialog.Builder(context)
        builder.setTitle("スコアを選択してください")
        builder.setItems(options) { _, which ->
            val selected = options[which]
            targetLabel.text = selected

            val entryNoLabel: TextView = resultText
            entryNoLabel.setBackgroundColor(Color.parseColor("#FFE599"))

            when (selected) {
                "0" -> targetLabel.setBackgroundResource(R.drawable.bg_score_clean)
                "1", "2", "3", "5" -> targetLabel.setBackgroundResource(R.drawable.bg_score_deduction)
                "-" -> targetLabel.setBackgroundResource(R.drawable.bg_score_blank)
            }

            // 🔥ここでスコア再計算を明示的に実行！
            recalculateScore()

            Toast.makeText(context, "※ 手入力でスコアを修正しました", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("キャンセル", null)
        builder.show()
    }

    fun updateScoreUi(result: ScoreAnalyzer.ScoreResult) {
        // スコアが適用された → 画面に解析結果あり
        onSetHasScoreResult(true)
        val activeCount = when (getSelectedPattern()) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }

        context.findViewById<TextView>(R.id.scorePointText).text = "G:　${result.totalScore}"
        context.findViewById<TextView>(R.id.scoreCleanText).text = "C:　${result.cleanCount}"
        resultText.setBackgroundColor(Color.WHITE)

        result.sectionScores.forEachIndexed { index, score ->
            val label = scoreLabelViews.getOrNull(index)

            if (label != null) {
                // 👇 地獄回避ロジック：使うセクション内で空欄 = 99強制
                val safeScore = when {
                    index >= activeCount && score != null -> 99                     // 設定外にスコア → 99
                    index < activeCount && score == null -> 99                     // 設定内に空欄 → 打ち忘れ → 99
                    else -> score                                                  // 通常通り
                }

                label.text = safeScore?.toString() ?: ""

                when (safeScore) {
                    null -> label.setBackgroundResource(R.drawable.bg_score_blank)
                    0 -> label.setBackgroundResource(R.drawable.bg_score_clean)
                    in listOf(1, 2, 3, 5) -> label.setBackgroundResource(R.drawable.bg_score_deduction)
                    99 -> label.setBackgroundResource(R.drawable.bg_score_unknown) // 99専用背景
                    else -> label.setBackgroundResource(R.drawable.bg_score_unknown)
                }
            }
        }

        // ✅ OCR反映後に封鎖チェック（Phase1）
        recalculateScore()
    }

    fun clearRecognitionUi() {
        // スコアラベルを空に＆背景リセット
        scoreLabelViews.forEach { label ->
            label.text = ""
            label.setBackgroundResource(R.drawable.bg_score_blank)
        }

        // 合計表示クリア
        context.findViewById<TextView>(R.id.scorePointText).text = "G:　-"
        context.findViewById<TextView>(R.id.scoreCleanText).text = "C:　-"

        // エントリー番号表示クリア
        resultText.text = "No: -"
        resultText.setBackgroundColor(Color.TRANSPARENT)

        // プレビュー画像クリア
        scorePreview.setImageDrawable(null)
        scorePreview.visibility = View.GONE

        // 状態フラグと一時画像をクリア
        onSetHasScoreResult(false)
        onSetLastOcrHadEntry(false)
        onSetPendingBitmap(null)

        // 枠は待機（赤）に戻す & 保存ボタンは隠す
        guideOverlay.setDetected("red")
        confirmButton.visibility = View.GONE
    }
}
