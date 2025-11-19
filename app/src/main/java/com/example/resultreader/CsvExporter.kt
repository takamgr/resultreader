// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
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
        status: String? = null   // ★ DNF/DNS 対応
    ) {
        // 1) ファイル準備（不変領域）
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val today = dateFormat.format(Date())
        val fileName = "result_${pattern.patternCode}_$today.csv"
        val saveDir = File(context.getExternalFilesDir("ResultReader"), "")
        if (!saveDir.exists()) saveDir.mkdirs()
        val csvFile = File(saveDir, fileName)

        val totalCount = amCount * 2
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // 2) 入力列ラベル（DNF/DNS 対応）
        val label = when (status) {
            "DNF" -> if (isManual) "手入力-DNF" else "OCR-DNF"
            "DNS" -> "DNS"
            else -> if (isManual) "手入力" else "OCR"
        }

        // 3) EntryNo がエントリリストに存在しない場合は保存中止
        if (!entryMap.containsKey(entryNo)) {
            Toast.makeText(
                context,
                "⚠️ EntryNo=$entryNo は未登録です。保存されませんでした。",
                Toast.LENGTH_LONG
            ).show()
            Log.w("CSV_EXPORT", "⛔ 未登録のEntryNo=$entryNo → 保存中止")
            return
        }

        // 4) 既存行読み込み（ヘッダ除外）
        val rows = if (csvFile.exists()) {
            csvFile.readLines(Charsets.UTF_8)
                .drop(1)
                .map { it.split(",").toMutableList() }
                .toMutableList()
        } else {
            mutableListOf()
        }

        // 5) ヘッダ生成（不変領域）
        val header = generateCsvHeader(pattern).toMutableList()
        header.add(1, "Name")
        header.add(2, "Class")

        // 6) ベース行作成（Name / Class）
        val baseRow = MutableList(header.size) { "" }
        baseRow[0] = entryNo.toString()
        val (name, clazz) = entryMap[entryNo] ?: ("" to "")
        baseRow[1] = name
        baseRow[2] = clazz

        // 7) スコア配列整形
        val filledScores =
            (allScores + List(maxOf(0, totalCount - allScores.size)) { null }).take(totalCount)

        // ★ 7.5) CSV 出力用に 99 → null（＝空欄）へ正規化
        //      ・ScoreAnalyzer / UI の中では 99 を使うが、
        //      ・ここから先（CSV保存以降）は 99 を見せたくないので空欄扱いにする
        val normalizedScores: List<Int?> =
            filledScores.map { v -> if (v == 99) null else v }

        // 8) 列インデックス取得（絶対領域・列名は固定）
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

        val secIndices = header.withIndex()
            .filter { it.value.matches(Regex("Sec\\d{2}")) }
            .map { it.index }
        val amSecIndices = secIndices.take(amCount)
        val pmSecIndices = secIndices.drop(amCount)

        // 9) 既存行の有無を確認
        val existingIndex = rows.indexOfFirst { it.firstOrNull() == entryNo.toString() }
        val row = if (existingIndex >= 0) rows[existingIndex] else baseRow

        val isDnfOrDns = (status == "DNF" || status == "DNS")

        // 10) Sec列と AmG/AmC / PmG/PmC を埋める
        if (currentSession == "AM") {
            // Sec01〜を埋める（DNF/DNS でもログとして残す）
            amSecIndices.forEachIndexed { i, idx ->
                // ★ normalizedScores を使用：99 はここで空欄になる
                row[idx] = normalizedScores.getOrNull(i)?.toString() ?: ""
            }

            if (isDnfOrDns) {
                // DNF/DNS → このエントリーはランキング対象外にするため G/C 全消し
                if (agIndex >= 0) row[agIndex] = ""
                if (acIndex >= 0) row[acIndex] = ""
                if (pgIndex >= 0) row[pgIndex] = ""
                if (pcIndex >= 0) row[pcIndex] = ""
            } else {
                // 通常 → AM側だけ更新（PM側は既存値を維持）
                if (agIndex >= 0) row[agIndex] = amScore.toString()
                if (acIndex >= 0) row[acIndex] = amClean.toString()
            }
        } else {
            // currentSession == "PM"
            pmSecIndices.forEachIndexed { i, idx ->
                // ★ normalizedScores を使用：99 はここで空欄になる
                row[idx] = normalizedScores.getOrNull(i + amCount)?.toString() ?: ""
            }

            if (isDnfOrDns) {
                // DNF/DNS → このエントリーはランキング対象外にするため G/C 全消し
                if (agIndex >= 0) row[agIndex] = ""
                if (acIndex >= 0) row[acIndex] = ""
                if (pgIndex >= 0) row[pgIndex] = ""
                if (pcIndex >= 0) row[pcIndex] = ""
            } else {
                // 通常 → PM側だけ更新（AM側は既存値を維持）
                if (pgIndex >= 0) row[pgIndex] = pmScore.toString()
                if (pcIndex >= 0) row[pcIndex] = pmClean.toString()
            }
        }

        // 11) TotalG / TotalC 計算
        if (isDnfOrDns) {
            // DNF/DNS はトータルも空欄にしてランキングから外す
            if (totalGIndex >= 0) row[totalGIndex] = ""
            if (totalCIndex >= 0) row[totalCIndex] = ""
        } else {
            val totalG = listOfNotNull(
                row.getOrNull(agIndex)?.toIntOrNull(),
                row.getOrNull(pgIndex)?.toIntOrNull()
            ).sum()

            val totalC = listOfNotNull(
                row.getOrNull(acIndex)?.toIntOrNull(),
                row.getOrNull(pcIndex)?.toIntOrNull()
            ).sum()

            if (totalGIndex >= 0) row[totalGIndex] = totalG.toString()
            if (totalCIndex >= 0) row[totalCIndex] = totalC.toString()
        }

        // 12) 共通情報の更新
        if (timeIndex >= 0) row[timeIndex] = currentTime
        if (inputTypeIndex >= 0) row[inputTypeIndex] = label
        if (sessionIndex >= 0) row[sessionIndex] = currentSession

        // 13) rows に反映
        if (existingIndex >= 0) {
            rows[existingIndex] = row
        } else {
            if (baseRow[1].isNotBlank() && baseRow[2].isNotBlank()) {
                rows.add(row)
            } else {
                Log.w("CSV_EXPORT", "⚠️ EntryNo=$entryNo はName/Classが空なので除外")
            }
        }

        // 14) クラス内ランク計算（絶対領域・手を加えない）
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

        // 15) DNF/DNS 行は Rank を空欄に戻す（並び替えロジック自体は変更しない）
        if (isDnfOrDns) {
            val targetRow = rows.find { it.firstOrNull() == entryNo.toString() }
            targetRow?.let { r ->
                if (amRankIndex >= 0 && amRankIndex < r.size) r[amRankIndex] = ""
                if (pmRankIndex >= 0 && pmRankIndex < r.size) r[pmRankIndex] = ""
                if (totalRankIndex >= 0 && totalRankIndex < r.size) r[totalRankIndex] = ""
            }
        }

        // 16) クラス並び替え（絶対領域・不変）
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

        // 17) 書き出し
        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(csvFile, false),
                Charsets.UTF_8
            )
        ).use { writer ->
            writer.write("\uFEFF" + header.joinToString(","))
            writer.newLine()
            finalSorted.forEach {
                writer.write(it.joinToString(","))
                writer.newLine()
            }
        }

        Toast.makeText(
            context,
            "✅ $fileName に保存＋クラス内順位＋並び替えOK",
            Toast.LENGTH_SHORT
        ).show()
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
