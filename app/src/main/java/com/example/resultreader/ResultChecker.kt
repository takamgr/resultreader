package com.example.resultreader

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultChecker(private val context: Context) {

    // ------------------------------------------------------------
    // AMチェック（AM同点＋AM未集計）
    // ------------------------------------------------------------
    fun checkAmStatus(selectedPattern: TournamentPattern, entryMap: Map<Int, Pair<String, String>>) {
        try {
            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            if (!csvFile.exists()) {
                Toast.makeText(context, "CSVがまだありません", Toast.LENGTH_SHORT).show()
                return
            }

            // ★ 1行目のBOMを除去してヘッダーを作る
            val allLines = csvFile.readLines(Charsets.UTF_8)
            if (allLines.isEmpty()) {
                Toast.makeText(context, "CSVが空です", Toast.LENGTH_SHORT).show()
                return
            }

            val header = allLines.first()
                .split(",")
                .map { it.replace("\uFEFF", "").trim() }   // ← ここ大事
            val rows = allLines.drop(1).map { it.split(",") }

            val entryNoIdx = header.indexOf("EntryNo")
            val inputIdx   = header.indexOf("入力")
            val amGIdx     = header.indexOf("AmG")
            val amCIdx     = header.indexOf("AmC")

            if (entryNoIdx == -1 || inputIdx == -1 || amGIdx == -1 || amCIdx == -1) {
                Toast.makeText(context, "ヘッダー解析エラー(AM)", Toast.LENGTH_LONG).show()
                return
            }

            // ■ AM完全同点グループ検出（DNSは除外）
            val amGroups = rows
                .filter { it.getOrNull(inputIdx) != "DNS" }
                .groupBy { row -> "${row.getOrNull(amGIdx)}_${row.getOrNull(amCIdx)}" }
                .filter { (_, group) ->
                    group.size > 1 && group.firstOrNull()?.getOrNull(amGIdx)?.isNullOrBlank() == false
                }

            if (amGroups.isNotEmpty()) {
                Toast.makeText(context, "AMに完全同点があります。掲示前に確認してください", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "AM同点はありません", Toast.LENGTH_SHORT).show()
            }

            // ■ 未読み取り（AM）チェック（EntryList 基準）
            val missing = entryMap.keys.filter { entryNo ->
                val row = rows.find { it.getOrNull(entryNoIdx)?.toIntOrNull() == entryNo }
                row == null ||
                        (row.getOrNull(amGIdx).isNullOrBlank()
                                && row.getOrNull(amCIdx).isNullOrBlank()
                                && row.getOrNull(inputIdx) != "DNS")
            }

            if (missing.isNotEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("AM未集計エントリー")
                    .setMessage(missing.joinToString("\n") { "No:$it ${entryMap[it]?.first ?: ""}" })
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(context, "AM未集計はありません", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(context, "AMチェックでエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }



    // ------------------------------------------------------------
    // 未集計チェック（AM / PM 共通）
    // ------------------------------------------------------------
    fun checkMissingEntries(session: String, selectedPattern: TournamentPattern, entryMap: Map<Int, Pair<String, String>>) {
        try {
            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            if (!csvFile.exists()) {
                Toast.makeText(context, "CSVがまだありません", Toast.LENGTH_SHORT).show()
                return
            }

            val allLines = csvFile.readLines(Charsets.UTF_8)
            if (allLines.isEmpty()) return

            val header = allLines.first()
                .split(",")
                .map { it.replace("\uFEFF", "").trim() }
            val rows = allLines.drop(1).map { it.split(",") }

            val entryNoIdx = header.indexOf("EntryNo")
            val inputIdx   = header.indexOf("入力")
            val amGIdx     = header.indexOf("AmG")
            val amCIdx     = header.indexOf("AmC")
            val pmGIdx     = header.indexOf("PmG")
            val pmCIdx     = header.indexOf("PmC")

            if (entryNoIdx == -1 || inputIdx == -1 ||
                amGIdx == -1 || amCIdx == -1 || pmGIdx == -1 || pmCIdx == -1
            ) {
                Toast.makeText(context, "ヘッダー解析エラー(未集計)", Toast.LENGTH_LONG).show()
                return
            }

            val missing = entryMap.keys.filter { entryNo ->
                val row = rows.find { it.getOrNull(entryNoIdx)?.toIntOrNull() == entryNo }

                if (session == "AM") {
                    row == null ||
                            (row.getOrNull(amGIdx).isNullOrBlank()
                                    && row.getOrNull(amCIdx).isNullOrBlank()
                                    && row.getOrNull(inputIdx) != "DNS")
                } else {
                    row == null ||
                            (row.getOrNull(pmGIdx).isNullOrBlank()
                                    && row.getOrNull(pmCIdx).isNullOrBlank()
                                    && row.getOrNull(inputIdx) != "DNS")
                }
            }

            if (missing.isNotEmpty()) {
                AlertDialog.Builder(context)
                    .setTitle("${session}未集計エントリー")
                    .setMessage(missing.joinToString("\n") { "No:$it ${entryMap[it]?.first ?: ""}" })
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                Toast.makeText(context, "${session}未集計はありません", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Toast.makeText(context, "未集計チェックでエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------
    // 最終チェック（Total同点＋PM未集計）
    // ------------------------------------------------------------
    fun checkFinalStatus(selectedPattern: TournamentPattern, entryMap: Map<Int, Pair<String, String>>) {
        try {
            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            if (!csvFile.exists()) {
                Toast.makeText(context, "CSVがまだありません", Toast.LENGTH_SHORT).show()
                return
            }

            val allLines = csvFile.readLines(Charsets.UTF_8)
            if (allLines.isEmpty()) return

            val header = allLines.first()
                .split(",")
                .map { it.replace("\uFEFF", "").trim() }
            val rows = allLines.drop(1).map { it.split(",") }

            val entryNoIdx  = header.indexOf("EntryNo")
            val inputIdx    = header.indexOf("入力")
            val totalGIdx   = header.indexOf("TotalG")
            val totalCIdx   = header.indexOf("TotalC")
            val pmGIdx      = header.indexOf("PmG")
            val pmCIdx      = header.indexOf("PmC")

            if (entryNoIdx == -1 || inputIdx == -1 ||
                totalGIdx == -1 || totalCIdx == -1 ||
                pmGIdx == -1 || pmCIdx == -1
            ) {
                Toast.makeText(context, "ヘッダー解析エラー(最終)", Toast.LENGTH_LONG).show()
                return
            }

            // ■ Total完全同点（DNS除外）
            val totalGroups = rows
                .filter { it.getOrNull(inputIdx) != "DNS" }
                .groupBy { row -> "${row.getOrNull(totalGIdx)}_${row.getOrNull(totalCIdx)}" }
                .filter { (_, group) ->
                    group.size > 1 && group.firstOrNull()?.getOrNull(totalGIdx)?.isNullOrBlank() == false
                }

            val tieMsg =
                if (totalGroups.isNotEmpty()) "最終同点があります。掲示前に確認してください"
                else "最終同点はありません"

            // ■ PM未集計
            val missing = entryMap.keys.filter { entryNo ->
                val row = rows.find { it.getOrNull(entryNoIdx)?.toIntOrNull() == entryNo }
                row == null ||
                        (row.getOrNull(pmGIdx).isNullOrBlank()
                                && row.getOrNull(pmCIdx).isNullOrBlank()
                                && row.getOrNull(inputIdx) != "DNS")
            }

            val missingMsg =
                if (missing.isNotEmpty()) {
                    "PM未集計エントリー:\n" +
                            missing.joinToString("\n") { "No:$it ${entryMap[it]?.first ?: ""}" }
                } else {
                    "PM未集計はありません"
                }

            AlertDialog.Builder(context)
                .setTitle("最終チェック")
                .setMessage("$tieMsg\n\n$missingMsg")
                .setPositiveButton("OK", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(context, "最終チェックでエラー: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
