package com.example.resultreader

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResultChecker(private val context: Context) {

    // 変更: クラスソート順定義（3関数共通）
    private val classOrder = mapOf("IA" to 0, "IB" to 1, "NA" to 2, "NB" to 3, "オープン" to 4, "ビギナー" to 5, "SP" to 6)

    // 変更: クラス順・EntryNo昇順でソートし「No:X 氏名 [クラス]」形式の文字列を返す
    private fun sortedMissingMessage(missing: List<Int>, entryMap: Map<Int, Pair<String, String>>): String =
        missing
            .sortedWith(compareBy({ classOrder[entryMap[it]?.second] ?: 99 }, { it }))
            .joinToString("\n") { "No:$it ${entryMap[it]?.first ?: ""} [${entryMap[it]?.second ?: ""}]" }

    // ------------------------------------------------------------
    // AMチェック（AM同点＋AM未集計）
    // ------------------------------------------------------------
    fun checkAmStatus(selectedPattern: TournamentPattern, entryMap: Map<Int, Pair<String, String>>) {
        try {
            // 変更: entryMapが空の場合はトーストで通知して終了
            if (entryMap.isEmpty()) {
                Toast.makeText(context, "エントリーリストが読み込まれていません", Toast.LENGTH_SHORT).show()
                return
            }

            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            // 変更: CSVがない場合は全エントリーを未集計扱いで表示（早期returnしない）
            if (!csvFile.exists()) {
                AlertDialog.Builder(context)
                    .setTitle("AM未集計エントリー")
                    .setMessage(sortedMissingMessage(entryMap.keys.toList(), entryMap))
                    .setPositiveButton("OK", null)
                    .show()
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
                .map { it.replace("﻿", "").trim() }   // ← ここ大事
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
                    .setMessage(sortedMissingMessage(missing, entryMap)) // 変更: クラス付き・ソート済み
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
            // 変更: entryMapが空の場合はトーストで通知して終了
            if (entryMap.isEmpty()) {
                Toast.makeText(context, "エントリーリストが読み込まれていません", Toast.LENGTH_SHORT).show()
                return
            }

            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            // 変更: CSVがない場合は全エントリーを未集計扱いで表示（早期returnしない）
            if (!csvFile.exists()) {
                AlertDialog.Builder(context)
                    .setTitle("${session}未集計エントリー")
                    .setMessage(sortedMissingMessage(entryMap.keys.toList(), entryMap))
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val allLines = csvFile.readLines(Charsets.UTF_8)
            if (allLines.isEmpty()) return

            val header = allLines.first()
                .split(",")
                .map { it.replace("﻿", "").trim() }
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
                    .setMessage(sortedMissingMessage(missing, entryMap)) // 変更: クラス付き・ソート済み
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
            // 変更: entryMapが空の場合はトーストで通知して終了
            if (entryMap.isEmpty()) {
                Toast.makeText(context, "エントリーリストが読み込まれていません", Toast.LENGTH_SHORT).show()
                return
            }

            val pattern = selectedPattern ?: return
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
            val fileName = "result_${pattern.patternCode}_$today.csv"
            val csvFile = File(context.getExternalFilesDir("ResultReader"), fileName)

            // 変更: CSVがない場合は全エントリーを未集計扱いで表示（早期returnしない）
            if (!csvFile.exists()) {
                AlertDialog.Builder(context)
                    .setTitle("最終チェック")
                    .setMessage("最終同点はありません\n\nPM未集計エントリー:\n${sortedMissingMessage(entryMap.keys.toList(), entryMap)}")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val allLines = csvFile.readLines(Charsets.UTF_8)
            if (allLines.isEmpty()) return

            val header = allLines.first()
                .split(",")
                .map { it.replace("﻿", "").trim() }
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
                    "PM未集計エントリー:\n" + sortedMissingMessage(missing, entryMap) // 変更: クラス付き・ソート済み
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
