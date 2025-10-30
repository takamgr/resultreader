// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

import android.content.Context
import android.util.Log
import java.io.File

object CsvUtils {

    fun loadEntryMapFromCsv(context: Context): Map<Int, Pair<String, String>> {
        val entryMap = mutableMapOf<Int, Pair<String, String>>()
        val file = File(context.getExternalFilesDir("ResultReader"), "EntryList.csv")

        if (!file.exists()) {
            Log.e("EntryListLoader", "EntryList.csv not found.")
            return emptyMap()
        }

        file.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 3) {
                    val entryNo = parts[0].toIntOrNull()
                    val name = parts[1].trim()
                    val clazz = parts[2].trim()
                    if (entryNo != null) {
                        entryMap[entryNo] = Pair(name, clazz)
                    }
                }
            }
        }

        return entryMap
    }
}