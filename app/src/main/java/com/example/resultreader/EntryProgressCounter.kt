package com.example.resultreader

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EntryProgressCounter {

    data class Progress(
        val totalEntries: Int,
        val amDone: Int,
        val pmDone: Int
    ) {
        val amRemain: Int get() = (totalEntries - amDone).coerceAtLeast(0)
        val pmRemain: Int get() = (totalEntries - pmDone).coerceAtLeast(0)
    }

    fun calc(
        context: Context,
        pattern: TournamentPattern,
        entryMap: Map<Int, Pair<String, String>>,
        date: Date = Date()
    ): Progress {
        val total = entryMap.size

        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
        val csvFileName = "result_${pattern.patternCode}_$today.csv"
        val csvFile = File(context.getExternalFilesDir("ResultReader"), csvFileName)

        if (!csvFile.exists() || total == 0) {
            return Progress(totalEntries = total, amDone = 0, pmDone = 0)
        }

        return try {
            val lines = csvFile.readLines(Charsets.UTF_8)
            if (lines.isEmpty()) return Progress(total, 0, 0)

            val header = lines.first()
                .split(",")
                .map { it.replace("\uFEFF", "").trim() }

            val entryCol = header.indexOf("EntryNo")
            val sessionCol = header.indexOf("セッション")
            if (entryCol == -1 || sessionCol == -1) {
                return Progress(total, 0, 0)
            }

            val amSet = mutableSetOf<Int>()
            val pmSet = mutableSetOf<Int>()

            lines.drop(1).forEach { line ->
                val cols = line.split(",")
                val entryNo = cols.getOrNull(entryCol)?.trim()?.toIntOrNull() ?: return@forEach
                val session = cols.getOrNull(sessionCol)?.trim() ?: return@forEach

                // ★「保存された人数」を数えるだけなので、DNF/DNSも “提出済み” としてカウントする
                when (session) {
                    "AM" -> amSet.add(entryNo)
                    "PM" -> pmSet.add(entryNo)
                }
            }

            Progress(totalEntries = total, amDone = amSet.size, pmDone = pmSet.size)
        } catch (_: Exception) {
            Progress(totalEntries = total, amDone = 0, pmDone = 0)
        }
    }
}
