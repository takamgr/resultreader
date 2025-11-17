// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*

object CsvExporter {

    fun appendResultToCsv(
        context: Context,
        currentSession: String,
        entryNo: Int,
        amScore: Int,
        amClean: Int,
        pmScore: Int,
        pmClean: Int,
        allScores: List<Int?>,
        isManual: Boolean,
        amCount: Int,
        pattern: TournamentPattern,
        entryMap: Map<Int, Pair<String, String>>,
        status: String? = null   // ★ DNF/DNS 対応追加
    ) {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val today = dateFormat.format(Date())
        val fileName = "result_${pattern.patternCode}_$today.csv"
        val saveDir = File(context.getExternalFilesDir("ResultReader"), "")
        if (!saveDir.exists()) saveDir.mkdirs()
        val csvFile = File(saveDir, fileName)

        val totalCount = amCount * 2
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // ★ 入力列のラベルを DNF / DNS 対応に変更
        val label = when (status) {
            "DNF" -> if (isManual) "手入力-DNF" else "OCR-DNF"
            "DNS" -> "DNS"
            else -> if (isManual) "手入力" else "OCR"
        }

        if (!entryMap.containsKey(entryNo)) {
            Toast.makeText(context, "⚠️ EntryNo=$entryNo は未登録です。保存されませんでした。", Toast.LENGTH_LONG).show()
            Log.w("CSV_EXPORT", "⛔ 未登録のEntryNo=$entryNo → 保存中止")
            return
        }

        val rows = if (csvFile.exists()) {
            csvFile.readLines(Charsets.UTF_8).drop(1).map { it.split(",").toMutableList() }.toMutableList()
        } else mutableListOf()

        val header = generateCsvHeader(pattern).toMutableList()
        header.add(1, "Name")
        header.add(2, "Class")

        val baseRow = MutableList(header.size) { "" }
        baseRow[0] = entryNo.toString()
        val (name, clazz) = entryMap[entryNo] ?: "" to ""
        baseRow[1] = name
        baseRow[2] = clazz

        val filledScores =
            (allScores + List(maxOf(0, totalCount - allScores.size)) { null }).take(totalCount)
        val amScores = filledScores.take(amCount)
        val pmScores = filledScores.drop(amCount)

        // インデックス取得（絶対領域・列順固定）
        val agIndex = header.indexOf("AmG")
        val acIndex = header.indexOf("AmC")
        val amRankIndex = header.indexOf("AmRank")
        val pgIndex = header.indexOf("PmG")
        val pcIndex = header.indexOf("PmC")
        val pmRankIndex = header.indexOf("PmRank")
        val totalGIndex = header.indexOf("TotalG")
        val totalCIndex = header.indexOf("TotalC")
        val totalRankIndex = header.indexOf("TotalRank")
        val timeIndex = header.indexOf("時刻")
        val inputTypeIndex = header.indexOf("入力")
        val sessionIndex = header.indexOf("セッション")

        val secIndices = header.withIndex().filter { it.value.matches(Regex("Sec\\d{2}")) }.map { it.index }
        val amSecIndices = secIndices.take(amCount)
        val pmSecIndices = secIndices.drop(amCount)

        val existingIndex = rows.indexOfFirst { it.firstOrNull() == entryNo.toString() }
        val row = if (existingIndex >= 0) rows[existingIndex] else baseRow

        // AM/PM スコア埋め込み
        if (currentSession == "AM") {
            amSecIndices.forEachIndexed { i, idx ->
                row[idx] = filledScores.getOrNull(i)?.toString() ?: ""
            }
            row[agIndex] = amScore.toString()
            row[acIndex] = amClean.toString()
        } else {
            pmSecIndices.forEachIndexed { i, idx ->
                row[idx] = filledScores.getOrNull(i + amCount)?.toString() ?: ""
            }
            row[pgIndex] = pmScore.toString()
            row[pcIndex] = pmClean.toString()
        }

        val totalG = listOfNotNull(
            row.getOrNull(agIndex)?.toIntOrNull(),
            row.getOrNull(pgIndex)?.toIntOrNull()
        ).sum()

        val totalC = listOfNotNull(
            row.getOrNull(acIndex)?.toIntOrNull(),
            row.getOrNull(pcIndex)?.toIntOrNull()
        ).sum()

        row[totalGIndex] = totalG.toString()
        row[totalCIndex] = totalC.toString()
        row[timeIndex] = currentTime
        row[inputTypeIndex] = label     // ★ ここに DNF / DNS ラベルが入る
        row[sessionIndex] = currentSession

        if (existingIndex >= 0) {
            rows[existingIndex] = row
        } else {
            if (baseRow[1].isNotBlank() && baseRow[2].isNotBlank()) {
                rows.add(row)
            } else {
                Log.w("CSV_EXPORT", "⚠️ EntryNo=$entryNo はName/Classが空なので除外")
            }
        }

        // ランク計算（絶対領域・不変）
        fun assignClassRank(index: Int, scoreGetter: (List<String>) -> Int?) {
            val classGroups = rows.groupBy { it.getOrNull(2) ?: "?" }
            for ((clazz, group) in classGroups) {
                if (clazz.isBlank() || clazz == "?") continue
                group
                    .mapNotNull { row -> scoreGetter(row)?.let { score -> row to score } }
                    .sortedWith(
                        compareBy({ it.second }, { -((it.first.getOrNull(acIndex)?.toIntOrNull()) ?: 0) })
                    )
                    .forEachIndexed { i, (r, _) -> r[index] = (i + 1).toString() }
            }
        }

        assignClassRank(amRankIndex) { it.getOrNull(agIndex)?.toIntOrNull() }
        assignClassRank(pmRankIndex) { it.getOrNull(pgIndex)?.toIntOrNull() }
        assignClassRank(totalRankIndex) { it.getOrNull(totalGIndex)?.toIntOrNull() }

        // クラス並び替え（絶対領域）
        val classOrderMap = when (
            context.getSharedPreferences("ResultReaderPrefs", Context.MODE_PRIVATE)
                .getString("tournamentType", "beginner")
        ) {
            "championship" -> listOf("IA", "IB", "NA", "NB")
            else -> listOf("オープン", "ビギナー")
        }.withIndex().associate { it.value to it.index }

        val classIndex = 2

        val finalSorted = rows.sortedWith(
            compareBy<List<String>> {
                classOrderMap[it.getOrNull(classIndex) ?: ""] ?: Int.MAX_VALUE
            }.thenBy {
                it.getOrNull(totalGIndex)?.toIntOrNull() ?: Int.MAX_VALUE
            }.thenByDescending {
                it.getOrNull(totalCIndex)?.toIntOrNull() ?: Int.MIN_VALUE
            }
        )

        BufferedWriter(OutputStreamWriter(FileOutputStream(csvFile, false), Charsets.UTF_8)).use { writer ->
            writer.write("\uFEFF" + header.joinToString(","))
            writer.newLine()
            finalSorted.forEach { writer.write(it.joinToString(",")); writer.newLine() }
        }

        Toast.makeText(context, "✅ $fileName に保存＋クラス内順位＋並び替えOK", Toast.LENGTH_SHORT).show()
        Log.d("CSV_EXPORT", "保存＋ClassRank再計算完了：$fileName")
    }

    fun generateCsvHeader(pattern: TournamentPattern): List<String> {
        val headers = mutableListOf("EntryNo")
        val totalCount = when (pattern) {
            TournamentPattern.PATTERN_4x2 -> 16
            TournamentPattern.PATTERN_4x3 -> 24
            TournamentPattern.PATTERN_5x2 -> 20
        }
        val amCount = totalCount / 2

        for (i in 1..amCount) headers.add("Sec%02d".format(i))
        headers.addAll(listOf("AmG", "AmC", "AmRank"))
        for (i in amCount + 1..totalCount) headers.add("Sec%02d".format(i))
        headers.addAll(listOf("PmG", "PmC", "PmRank"))
        headers.addAll(listOf("TotalG", "TotalC", "TotalRank"))
        headers.addAll(listOf("時刻", "入力", "セッション"))

        return headers
    }
}
