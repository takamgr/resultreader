// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

enum class TournamentPattern(
    val label: String,
    val sectionPerHalf: Int  // ← 午前のセクション数
) {
    PATTERN_4x2("4セク×2ラップ", 8),
    PATTERN_4x3("4セク×3ラップ", 12),
    PATTERN_5x2("5セク×2ラップ", 10);

    val totalSectionCount: Int
        get() = sectionPerHalf * 2

    val patternCode: String
        get() = when (this) {
            PATTERN_4x2 -> "4x2"
            PATTERN_4x3 -> "4x3"
            PATTERN_5x2 -> "5x2"
        }

    /**
     * CSVのヘッダーを生成（クラスや名前列は含まず、従来通り）
     */
    fun generateCsvHeader(): List<String> {
        val header = mutableListOf<String>()
        header.add("EntryNo")

        // AM分のセクション（Sec01〜）
        for (i in 1..sectionPerHalf) {
            header.add("Sec%02d".format(i))
        }

        // PM分のセクション（Sec(N+1)〜）
        for (i in (sectionPerHalf + 1)..totalSectionCount) {
            header.add("Sec%02d".format(i))
        }

        header.addAll(
            listOf(
                "AmG", "AmC", "AmRank",
                "PmG", "PmC", "PmRank",
                "TotalG", "TotalC", "TotalRank",
                "時刻", "入力", "セッション", "名前", "クラス"
            )
        )

        return header
    }
}