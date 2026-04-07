package com.example.resultreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SaveManager(
    private val context: AppCompatActivity,
    private val scoreLabelViews: List<TextView>,
    private val resultText: TextView,
    private val guideOverlay: GuideOverlayView,
    private val confirmButton: Button,
    private val onPlayErrorSound: () -> Unit,
    private val onSaveImage: (Bitmap) -> Unit,
    private val onClearRecognitionUi: () -> Unit,
    private val onClassCleared: () -> Unit,
    private val onTournamentInfoUpdate: () -> Unit
) {

    enum class SaveStatus { NORMAL, DNF, DNS }

    fun requestSaveWithStatus(
        status: SaveStatus,
        selectedPattern: TournamentPattern,
        currentSession: String,
        pendingSaveBitmap: Bitmap?,
        currentRowClass: String?,
        entryMap: Map<Int, Pair<String, String>>
    ) {
        // 1) 99 / 空欄チェック（NORMAL のときだけ有効）
        val totalCount = when (selectedPattern) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }
        if (status == SaveStatus.NORMAL) {
            val hasInvalid = scoreLabelViews
                .take(totalCount)
                .any { it.text.toString().trim() in listOf("99", "", "-", "ー", "―") }
            if (hasInvalid) {
                Toast.makeText(
                    context,
                    "❌ スコアに空欄やエラー（99など）が含まれているため保存できません",
                    Toast.LENGTH_SHORT
                ).show()
                onPlayErrorSound()
                return
            }
        }
        // 2) EntryNo 抽出
        val entryNumber = resultText.text.toString()
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull() ?: 0
        val patternCode = selectedPattern.patternCode
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val today = dateFormat.format(Date())
        val csvFileName = "result_${patternCode}_$today.csv"
        val csvFile = File(context.getExternalFilesDir("ResultReader"), csvFileName)
        // 3) 既存 CSV のセッション／上書きチェック（NORMAL / DNF / DNS 共通）
        if (csvFile.exists()) {
            val header = csvFile.bufferedReader().readLine()
                ?.split(",")
                ?.map { it.replace("\uFEFF", "").trim() } ?: emptyList()
            val lines = csvFile.readLines().drop(1)
            val entryCol = header.indexOf("EntryNo")
            val sessionCol = header.indexOf("セッション")
            Log.d("CSV_DEBUG", "列一覧 = $header")
            Log.d("CSV_DEBUG", "EntryCol = $entryCol / SessionCol = $sessionCol")
            if (entryCol == -1 || sessionCol == -1) {
                Log.e("CSV_DEBUG", "列名が見つかりません！セッション or EntryNo")
            }
            // ① 同じセッション＆同じEntryNo → 上書き確認
            val alreadyHasThisSession = lines.any { line ->
                val cols = line.split(",")
                cols.getOrNull(entryCol)?.toIntOrNull() == entryNumber &&
                        cols.getOrNull(sessionCol) == currentSession
            }
            if (alreadyHasThisSession) {
                AlertDialog.Builder(context)
                    .setTitle("⚠️ 上書き確認")
                    .setMessage("エントリー${entryNumber}はすでに $currentSession に記録があります。\nこのまま保存すると上書きされます。よろしいですか？")
                    .setPositiveButton("続けて保存") { _, _ ->
                        proceedWithSave(entryNumber, status, selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                return
            }
            // ② AM保存済みで再びAM → セッション切替警告
            val alreadyHasAM = lines.any { line ->
                val cols = line.split(",")
                cols.getOrNull(entryCol)?.toIntOrNull() == entryNumber &&
                        cols.getOrNull(sessionCol) == "AM"
            }
            if (currentSession == "AM" && alreadyHasAM) {
                AlertDialog.Builder(context)
                    .setTitle("⚠️ セッション切替確認")
                    .setMessage("このエントリー番号はすでにAMに保存されています。\nPMに切り替え忘れていませんか？")
                    .setPositiveButton("続けて保存") { _, _ ->
                        proceedWithSave(entryNumber, status, selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                return
            }
        }
        // ③ 午後の時間帯でAMセッション → リマインド
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (currentHour >= 13 && currentSession == "AM") {
            AlertDialog.Builder(context)
                .setTitle("⚠️ 時間によるセッション警告")
                .setMessage("現在13時を過ぎていますが、セッションはAMのままです。\n切り替え忘れていませんか？")
                .setPositiveButton("続けて保存") { _, _ ->
                    proceedWithSave(entryNumber, status, selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap)
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        // ここまで全部OKなら保存へ
        proceedWithSave(entryNumber, status, selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap)
    }

    private fun proceedWithSave(
        entryNumber: Int,
        status: SaveStatus,
        selectedPattern: TournamentPattern,
        currentSession: String,
        pendingSaveBitmap: Bitmap?,
        currentRowClass: String?,
        entryMap: Map<Int, Pair<String, String>>
    ) {
        pendingSaveBitmap?.let {
            // 1) 認識画像の保存（既存）
            onSaveImage(it)
            // 2) セクション数の算出（既存ロジック）
            val amCount = when (selectedPattern) {
                TournamentPattern.PATTERN_4x2 -> 8
                TournamentPattern.PATTERN_4x3 -> 12
                TournamentPattern.PATTERN_5x2 -> 10
            }
            val totalCount = amCount * 2
            val pmCount = totalCount - amCount
            // 3) AM/PM に応じてスコア配列を構成（既存ロジック）
            val scoreList = when (currentSession) {
                "AM" -> scoreLabelViews.map { it.text.toString().toIntOrNull() } + List(pmCount) { null }
                "PM" -> List(amCount) { null } + scoreLabelViews.map { it.text.toString().toIntOrNull() }
                else -> List(amCount + pmCount) { null }
            }.take(amCount + pmCount)
            val amScores = scoreList.take(amCount)
            val pmScores = scoreList.drop(amCount).take(pmCount)
            var amScore = 0
            var amClean = 0
            for (v in amScores) {
                if (v != null) {
                    amScore += v
                    if (v == 0) amClean++
                }
            }
            var pmScore = 0
            var pmClean = 0
            for (v in pmScores) {
                if (v != null) {
                    pmScore += v
                    if (v == 0) pmClean++
                }
            }
            // 4) 手入力（黄色背景）判定（既存）
            val isManual = resultText.background != null &&
                    (resultText.background as? ColorDrawable)?.color == Color.parseColor("#FFE599")
            // 5) クラス変更の可否チェック（既存）
            if (currentRowClass != null && !entryMap.containsKey(entryNumber)) {
                Toast.makeText(context, "⚠️ エントリーが未登録のためクラスを変更できません", Toast.LENGTH_LONG).show()
                return@let
            }
            // 6) 保存用の entryMap（既存）
            val effectiveEntryMap = entryMap.toMutableMap()
            if (currentRowClass != null) {
                val existing = entryMap[entryNumber]
                val name = existing?.first ?: ""
                effectiveEntryMap[entryNumber] = Pair(name, currentRowClass)
            }
            // 7) 保存種別を文字列化して CsvExporter へ
            val statusStr = when (status) {
                SaveStatus.DNF -> "DNF"
                SaveStatus.DNS -> "DNS"
                else -> null
            }
            CsvExporter.appendResultToCsv(
                context = context,
                currentSession = currentSession,
                entryNo = entryNumber,
                amScore = amScore,
                amClean = amClean,
                pmScore = pmScore,
                pmClean = pmClean,
                allScores = scoreList,
                isManual = isManual,
                amCount = amCount,
                pattern = selectedPattern,
                entryMap = effectiveEntryMap,
                status = statusStr
            )
            // 8) UI 戻し＋保存ボタン隠し（既存）
            guideOverlay.setDetected("red")
            confirmButton.visibility = View.GONE
            // 9) 認識UIを初期化（既存）
            onClearRecognitionUi()
            // 10) 手動クラス指定はクリア（既存）
            onClassCleared()
            onTournamentInfoUpdate()
            // 11) 保存種別に応じたトースト
            val toastMsg = when (status) {
                SaveStatus.DNF -> "DNFとして保存しました"
                SaveStatus.DNS -> "DNSとして保存しました"
                else -> "保存しました"
            }
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }
}
