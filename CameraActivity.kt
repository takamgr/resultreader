// ...existing code...

// Step1: 保存ステータス用 enum とフィールド追加
private enum class SaveStatus { NORMAL, DNF, DNS }
private var currentSaveStatus: SaveStatus = SaveStatus.NORMAL

// ...existing code...

override fun onCreate(savedInstanceState: Bundle?) {
    // ...existing code...

    // Step3: confirmButton の短押し
    confirmButton.setOnClickListener {
        currentSaveStatus = SaveStatus.NORMAL
        requestSaveWithStatus(SaveStatus.NORMAL)
    }

    // Step4: confirmButton の長押し
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
                    0 -> {
                        currentSaveStatus = SaveStatus.DNF
                        requestSaveWithStatus(SaveStatus.DNF)
                    }
                    1 -> {
                        currentSaveStatus = SaveStatus.DNS
                        requestSaveWithStatus(SaveStatus.DNS)
                    }
                    else -> {
                        // キャンセル
                        dialog.dismiss()
                    }
                }
            }
            .show()
        true
    }

    // ...existing code...
}

// 既存の proceedWithSave(entryNumber) 呼び出し箇所をすべて
// proceedWithSave(entryNumber, currentSaveStatus) または
// requestSaveWithStatus(currentSaveStatus) に修正してください

// Step2: 保存フローを「ステータス付き」で呼べるように
private fun proceedWithSave(entryNumber: Int, status: SaveStatus) {
    // ...existing code...
    // isManual 判定の直前あたりで statusString を作成
    val statusString = when (status) {
        SaveStatus.DNF -> "DNF"
        SaveStatus.DNS -> "DNS"
        SaveStatus.NORMAL -> null
    }
    // ...existing code...
    CsvExporter.appendResultToCsv(
        context = this,
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
        entryMap = entryMap,
        status = statusString
    )
    // ...existing code...
}

// Step3: proceedWithSave の呼び出し箇所をすべて status 付きに修正
private fun requestSaveWithStatus(status: SaveStatus) {
    // ...existing code...
    // 保存フローに入る箇所を proceedWithSave(entryNumber, status) に変更
    proceedWithSave(entryNumber, status)
    // ...existing code...
}

// 既存の proceedWithSave(entryNumber) 呼び出し箇所をすべて
// proceedWithSave(entryNumber, currentSaveStatus) または
// requestSaveWithStatus(currentSaveStatus) に修正してください

// ...existing code...
