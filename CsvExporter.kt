// ...existing code...

// Step5: appendResultToCsv のシグネチャ変更
fun appendResultToCsv(
    context: Context,// ...既存コード...

    // 1. 保存種別のenum
    private enum class SaveStatus { NORMAL, DNF, DNS }
    private var currentSaveStatus: SaveStatus = SaveStatus.NORMAL

    // 2. confirmButtonのクリック処理
    confirmButton.setOnClickListener {
        currentSaveStatus = SaveStatus.NORMAL
        requestSaveWithStatus(SaveStatus.NORMAL)
    }
    confirmButton.setOnLongClickListener {
        val items = arrayOf(
            "DNF（途中リタイア）として保存",
            "DNS（出走せず）として保存",
            "キャンセル"
        )
        AlertDialog.Builder(this)
            .setTitle("このエントリーの保存種別")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> requestSaveWithStatus(SaveStatus.DNF)
                    1 -> requestSaveWithStatus(SaveStatus.DNS)
                    else -> dialog.dismiss()
                }
            }
            .show()
        true
    }

    // 3. DNF/DNS保存ルート
    private fun requestSaveWithStatus(status: SaveStatus) {
        // EntryNo抽出（既存ロジックそのまま）
        val entryNoText = resultText.text.toString().replace(Regex("[^0-9]"), "")
        val entryNumber = entryNoText.toIntOrNull()
        if (entryNumber == null || !entryMap.containsKey(entryNumber)) {
            Toast.makeText(this, "⚠️ EntryNoが未入力または未登録です", Toast.LENGTH_SHORT).show()
            return
        }

        // DNF/DNS時は99/空欄チェックをスキップ
        if (status == SaveStatus.NORMAL) {
            // 既存の保存不可判定（99/空欄/‐）をそのまま使う
            var hasInvalid = false
            val totalCount = when (selectedPattern) {
                TournamentPattern.PATTERN_4x2 -> 8
                TournamentPattern.PATTERN_4x3 -> 12
                TournamentPattern.PATTERN_5x2 -> 10
            }
            for (i in 0 until totalCount) {
                val scoreText = scoreLabelViews[i].text.toString().trim()
                if (scoreText in listOf("", "-", "ー", "―", "99")) {
                    hasInvalid = true
                    break
                }
            }
            if (hasInvalid) {
                Toast.makeText(this, "❌ スコアに空欄やエラー（99など）が含まれているため保存できません", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 4. proceedWithSave呼び出し
        proceedWithSave(entryNumber, status)
    }

    // 5. 保存処理（既存ロジックを流用、G/C/Rankは空欄で保存）
    private fun proceedWithSave(entryNumber: Int, status: SaveStatus) {
        // 既存のスコア集計・クラス取得ロジックはそのまま
        val scoreList = scoreLabelViews.map { it.text.toString().toIntOrNull() }
        val isManual = resultText.currentTextColor == android.graphics.Color.parseColor("#FFE599")
        val amCount = when (selectedPattern) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }
        val currentSession = if (isAmSession()) "AM" else "PM"
        val effectiveEntryMap = entryMap

        // DNF/DNS時はG/C/Rankを空欄で保存
        val amScore: Int = if (status == SaveStatus.NORMAL) {
            scoreList.take(amCount).filterNotNull().filter { it != 99 }.sum()
        } else 0
        val amClean: Int = if (status == SaveStatus.NORMAL) {
            scoreList.take(amCount).count { it == 0 }
        } else 0
        val pmScore: Int = if (status == SaveStatus.NORMAL) {
            scoreList.drop(amCount).filterNotNull().filter { it != 99 }.sum()
        } else 0
        val pmClean: Int = if (status == SaveStatus.NORMAL) {
            scoreList.drop(amCount).count { it == 0 }
        } else 0

        // statusを文字列化
        val statusStr = when (status) {
            SaveStatus.DNF -> "DNF"
            SaveStatus.DNS -> "DNS"
            else -> null
        }

        // CsvExporterへ保存（既存ロジックは不変）
        CsvExporter.appendResultToCsv(
            context = this,
            currentSession = currentSession,
            entryNo = entryNumber,
            amScore = if (status == SaveStatus.NORMAL) amScore else 0,
            amClean = if (status == SaveStatus.NORMAL) amClean else 0,
            pmScore = if (status == SaveStatus.NORMAL) pmScore else 0,
            pmClean = if (status == SaveStatus.NORMAL) pmClean else 0,
            allScores = scoreList,
            isManual = isManual,
            amCount = amCount,
            pattern = selectedPattern,
            entryMap = effectiveEntryMap,
            status = statusStr
        )

        // 完了トースト
        val msg = when (status) {
            SaveStatus.DNF -> "DNFとして保存しました"
            SaveStatus.DNS -> "DNSとして保存しました"
            else -> "通常保存しました"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ...既存コード...
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
    status: String? = null
) {
    // ...existing code...

    // Step5: 「入力」列のラベル決定ロジックを status 対応に
    val label = when (status) {
        "DNF" -> if (isManual) "手入力-DNF" else "OCR-DNF"
        "DNS" -> "DNS"
        else -> if (isManual) "手入力" else "OCR"
    }

    // ...existing code...
    // label を「入力」列に使用している箇所はそのまま label を使ってください
    // それ以外のロジックは一切変更しないこと

    // ...existing code...

        val secIndices = header.withIndex().filter { it.value.matches(Regex("Sec\\d{2}")) }.map { it.index }
        val amSecIndices = secIndices.take(amCount)
        val pmSecIndices = secIndices.drop(amCount)

        val existingIndex = rows.indexOfFirst { it.firstOrNull() == entryNo.toString() }
        val row = if (existingIndex >= 0) rows[existingIndex] else baseRow

        val isDnfOrDns = (status == "DNF" || status == "DNS")

        // AM/PM スコア埋め込み
        if (currentSession == "AM") {
            // セクションスコアは DNF/DNS でも「ログとして」残す
            amSecIndices.forEachIndexed { i, idx ->
                row[idx] = filledScores.getOrNull(i)?.toString() ?: ""
            }

            if (isDnfOrDns) {
                // DNF / DNS は G/C を空欄にしてランキング対象外にする
                row[agIndex] = ""
                row[acIndex] = ""
            } else {
                row[agIndex] = amScore.toString()
                row[acIndex] = amClean.toString()
            }
        } else {
            // PM セッション
            pmSecIndices.forEachIndexed { i, idx ->
                row[idx] = filledScores.getOrNull(i + amCount)?.toString() ?: ""
            }

            if (isDnfOrDns) {
                // DNF / DNS は G/C を空欄にしてランキング対象外にする
                row[pgIndex] = ""
                row[pcIndex] = ""
            } else {
                row[pgIndex] = pmScore.toString()
                row[pcIndex] = pmClean.toString()
            }
        }

        // TotalG / TotalC は DNF / DNS の場合は空欄にしておく
        if (isDnfOrDns) {
            row[totalGIndex] = ""
            row[totalCIndex] = ""
        } else {
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
        }

        row[timeIndex] = currentTime
        row[inputTypeIndex] = label     // ★ ここに DNF / DNS ラベルが入る
        row[sessionIndex] = currentSession

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

        // DNF / DNS 行は Rank 列を空欄にしておく
        if (status == "DNF" || status == "DNS") {
            val targetRow = rows.find { it.firstOrNull() == entryNo.toString() }
            targetRow?.let { r ->
                if (amRankIndex >= 0 && amRankIndex < r.size) r[amRankIndex] = ""
                if (pmRankIndex >= 0 && pmRankIndex < r.size) r[pmRankIndex] = ""
                if (totalRankIndex >= 0 && totalRankIndex < r.size) r[totalRankIndex] = ""
            }
        }

        // ...existing code...
}
