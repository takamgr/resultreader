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
                .map { it.trimStart('﻿').trim() }

            val entryCol = header.indexOf("EntryNo")
            if (entryCol == -1) {
                return Progress(total, 0, 0)
            }

            val amGCol = header.indexOf("AmG")
            val pmGCol = header.indexOf("PmG")
            val inputCol = header.indexOf("入力")

            val amSet = mutableSetOf<Int>()
            val pmSet = mutableSetOf<Int>()

            lines.drop(1).forEach { line ->
                val cols = line.split(",")
                val entryNo = cols.getOrNull(entryCol)?.trim()?.toIntOrNull() ?: return@forEach
                val amG = cols.getOrNull(amGCol)?.trim()
                val pmG = cols.getOrNull(pmGCol)?.trim()
                val inputType = cols.getOrNull(inputCol)?.trim() ?: ""
                val isDnfOrDns = inputType.contains("DNF") || inputType.contains("DNS")
                if (!amG.isNullOrBlank() || isDnfOrDns) amSet.add(entryNo) // 変更
                if (!pmG.isNullOrBlank() || isDnfOrDns) pmSet.add(entryNo) // 変更
            }

            Progress(totalEntries = total, amDone = amSet.size, pmDone = pmSet.size)
        } catch (_: Exception) {
            Progress(totalEntries = total, amDone = 0, pmDone = 0)
        }
    }
}
