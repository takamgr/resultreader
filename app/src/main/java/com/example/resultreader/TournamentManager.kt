package com.example.resultreader

import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.resultreader.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class TournamentManager(
    private val context: AppCompatActivity,
    private val resultText: TextView,
    private val tournamentInfoText: TextView,
    private val resultChecker: ResultChecker,
    private val getSelectedPattern: () -> TournamentPattern,
    private val getCurrentSession: () -> String,
    private val getCurrentRowClass: () -> String?,
    private val onPatternChanged: (TournamentPattern) -> Unit,
    private val onSessionChanged: (String) -> Unit,
    private val onEntryMapUpdated: (Map<Int, Pair<String, String>>) -> Unit,
    private val onClassChanged: (String?) -> Unit,
    private val onCaptureScoreRequested: () -> Unit
) {

    fun updateTournamentInfoText() {
        val prefs = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
        val type = when (prefs.getString("tournamentType", "unknown")) {
            "championship" -> "選手権"
            "beginner" -> "ビギナー"
            else -> "不明"
        }
        val patternText = getSelectedPattern().patternCode
        tournamentInfoText.text = "$patternText / ${getCurrentSession()} / $type"
    }

    fun copyCsvToAppStorage(uri: Uri) {
        val prefs = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
        val isDebug = BuildConfig.IS_DEBUG
        val alreadyLoaded = prefs.getBoolean("entrylist_loaded_once", false)

        if (alreadyLoaded && !isDebug) {
            Toast.makeText(context, "⚠️ entrylist.csv はすでに読み込まれています", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "EntryList.csv"
        val destFile = File(context.getExternalFilesDir("ResultReader"), fileName)

        val inputStream = context.contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Toast.makeText(context, "❌ entrylist 読み込みに失敗しました", Toast.LENGTH_LONG).show()
            return
        }

        inputStream.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val reader = BufferedReader(InputStreamReader(destFile.inputStream(), Charsets.UTF_8))
        val lines = reader.readLines()

        val newEntryMap = lines.drop(1).mapNotNull { line ->
            val parts = line.split(",")
            val no = parts.getOrNull(0)?.toIntOrNull()
            val name = parts.getOrNull(1)?.trim()
            val clazz = parts.getOrNull(2)?.trim()
            if (no != null && !name.isNullOrBlank() && !clazz.isNullOrBlank()) {
                no to (name to clazz)
            } else null
        }.toMap()

        onEntryMapUpdated(newEntryMap)

        // 大会種別をCSVから判定
        val clazzes = newEntryMap.values.map { it.second }.toSet()
        val detectedType = when {
            clazzes.any { it in listOf("IA", "IB", "NA", "NB") } -> "championship"
            clazzes.any { it in listOf("オープン", "ビギナー") } -> "beginner"
            else -> "unknown"
        }

        val currentType = prefs.getString("tournamentType", "unknown")
        val toastText = when (detectedType) {
            "championship" -> "選手権大会と判定しました"
            "beginner" -> "ビギナー大会と判定しました"
            else -> "大会種別を判定できませんでした"
        }

        if (currentType != detectedType && detectedType != "unknown") {
            AlertDialog.Builder(context)
                .setTitle("⚠️ 大会種別の変更確認")
                .setMessage("現在の設定：$currentType\nCSVから検出：$detectedType\n\n大会設定を変更しますか？")
                .setPositiveButton("変更する") { _, _ ->
                    prefs.edit()
                        .putString("tournamentType", detectedType)
                        .putBoolean("entrylist_loaded_once", true)
                        .apply()
                    updateTournamentInfoText()
                    Toast.makeText(context, "✅ エントリーリスト読み込み完了\n$toastText", Toast.LENGTH_LONG).show()

                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            prefs.edit()
                .putString("tournamentType", detectedType)
                .putBoolean("entrylist_loaded_once", true)
                .apply()
            updateTournamentInfoText()
            Toast.makeText(context, "✅ エントリーリスト読み込み完了\n$toastText", Toast.LENGTH_LONG).show()
        }
    }

    fun loadEntryMap(): Map<Int, Pair<String, String>> {
        val entryMap = mutableMapOf<Int, Pair<String, String>>()
        val csvFile = File(context.getExternalFilesDir("ResultReader"), "EntryList.csv")
        if (!csvFile.exists()) {
            Log.e("ENTRY_MAP", "EntryList.csv が見つかりません")
            return entryMap
        }

        val lines = csvFile.readLines(Charsets.UTF_8).drop(1) // ヘッダスキップ
        for (line in lines) {
            val cols = line.split(",")
            val entryNo = cols.getOrNull(0)?.toIntOrNull()
            val name = cols.getOrNull(1)?.trim()
            val clazz = cols.getOrNull(2)?.trim()
            if (entryNo != null && name != null && clazz != null) {
                entryMap[entryNo] = Pair(name, clazz)
            }
        }

        Log.d("ENTRY_MAP", "読み込み完了: ${entryMap.size}件")
        return entryMap
    }

    private fun updateSessionButtons(currentSession: String, amButton: Button, pmButton: Button) {
        if (currentSession == "AM") {
            amButton.setBackgroundColor(Color.RED)
            amButton.setTextColor(Color.WHITE)
            pmButton.setBackgroundColor(Color.LTGRAY)
            pmButton.setTextColor(Color.BLACK)
        } else {
            pmButton.setBackgroundColor(Color.BLUE)
            pmButton.setTextColor(Color.WHITE)
            amButton.setBackgroundColor(Color.LTGRAY)
            amButton.setTextColor(Color.BLACK)
        }
    }

    fun showInitialTournamentSettingDialog(
        getEntryMap: () -> Map<Int, Pair<String, String>>,
        onComplete: () -> Unit
    ) {
        val dialogView = context.layoutInflater.inflate(R.layout.dialog_tournament_setting, null)

        // ▼ 既存UI
        val radioGroupPattern = dialogView.findViewById<RadioGroup>(R.id.radioGroupPattern)
        val radio4x2 = dialogView.findViewById<RadioButton>(R.id.radioPattern4x2)
        val radio4x3 = dialogView.findViewById<RadioButton>(R.id.radioPattern4x3)
        val radio5x2 = dialogView.findViewById<RadioButton>(R.id.radioPattern5x2)

        val amButton = dialogView.findViewById<Button>(R.id.buttonAM)
        val pmButton = dialogView.findViewById<Button>(R.id.buttonPM)

        // ▼ 大会種別
        val beginnerRadioButton = dialogView.findViewById<RadioButton>(R.id.radioBeginner)
        val championshipRadioButton = dialogView.findViewById<RadioButton>(R.id.radioChampionship)

        val prefs = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
        when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> championshipRadioButton.isChecked = true
            else -> beginnerRadioButton.isChecked = true
        }

        // ▼ パターン
        when (getSelectedPattern()) {
            TournamentPattern.PATTERN_4x2 -> radio4x2.isChecked = true
            TournamentPattern.PATTERN_4x3 -> radio4x3.isChecked = true
            TournamentPattern.PATTERN_5x2 -> radio5x2.isChecked = true
        }

        updateSessionButtons(getCurrentSession(), amButton, pmButton)

        amButton.setOnClickListener {
            onSessionChanged("AM")
            prefs.edit().putString("lastSession", "AM").apply()
            updateSessionButtons(getCurrentSession(), amButton, pmButton)
        }

        pmButton.setOnClickListener {
            onSessionChanged("PM")
            prefs.edit().putString("lastSession", "PM").apply()
            updateSessionButtons(getCurrentSession(), amButton, pmButton)
        }

        // ▼ チェック系3ボタン
        dialogView.findViewById<Button>(R.id.buttonCheckAm).setOnClickListener {
            resultChecker.checkAmStatus(getSelectedPattern(), getEntryMap())
        }

        dialogView.findViewById<Button>(R.id.buttonCheckMissing).setOnClickListener {
            resultChecker.checkMissingEntries(getCurrentSession(), getSelectedPattern(), getEntryMap())
        }

        dialogView.findViewById<Button>(R.id.buttonCheckFinal).setOnClickListener {
            resultChecker.checkFinalStatus(getSelectedPattern(), getEntryMap())
        }

        // ▼ 大会名・開催日 入力
        val prefsForDialog = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
        val initialName = prefsForDialog.getString("tournamentName", "") ?: ""
        val initialDate = prefsForDialog.getString("eventDate", "") ?: ""

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8 * context.resources.displayMetrics.density).toInt()

        val editTournamentName = EditText(context).apply {
            hint = "大会名（例：第3回 オラガバレーTRIALS）"
            setText(initialName)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = lp
            id = View.generateViewId()
        }

        val editEventDate = EditText(context).apply {
            hint = "開催日 (YYYY-MM-DD)"
            setText(initialDate)
            isFocusable = false
            isClickable = true
            layoutParams = lp
            id = View.generateViewId()
            setOnClickListener {
                val now = Calendar.getInstance()
                val parts = text.toString().trim().takeIf { it.isNotEmpty() }?.let { s ->
                    try {
                        if (s.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            val p = s.split("-")
                            Triple(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
                        } else if (s.matches(Regex("\\d{8}"))) {
                            Triple(
                                s.substring(0, 4).toInt(),
                                s.substring(4, 6).toInt() - 1,
                                s.substring(6, 8).toInt()
                            )
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }

                val y = parts?.first ?: now.get(Calendar.YEAR)
                val m = parts?.second ?: now.get(Calendar.MONTH)
                val d = parts?.third ?: now.get(Calendar.DAY_OF_MONTH)

                android.app.DatePickerDialog(
                    context,
                    { _, yy, mm, dd ->
                        val ys = String.format(Locale.getDefault(), "%04d-%02d-%02d", yy, mm + 1, dd)
                        this@apply.setText(ys)
                    },
                    y, m, d
                ).show()
            }
        }

        (dialogView as? LinearLayout)?.addView(editTournamentName)
        (dialogView as? LinearLayout)?.addView(editEventDate)

        // ▼ ダイアログ構築
        val dialog = AlertDialog.Builder(context)
            .setTitle("本日の大会設定")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->

                val newPattern = when (radioGroupPattern.checkedRadioButtonId) {
                    R.id.radioPattern4x2 -> TournamentPattern.PATTERN_4x2
                    R.id.radioPattern4x3 -> TournamentPattern.PATTERN_4x3
                    R.id.radioPattern5x2 -> TournamentPattern.PATTERN_5x2
                    else -> TournamentPattern.PATTERN_4x2
                }
                onPatternChanged(newPattern)

                val tournamentType =
                    if (championshipRadioButton.isChecked) "championship" else "beginner"

                val prefs2 = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

                prefs2.edit().apply {
                    putString("lastSetDate", today)
                    putString("lastPattern", getSelectedPattern().name)
                    putString("lastSession", getCurrentSession())
                    putString("tournamentType", tournamentType)

                    try {
                        val nameText = dialogView.findViewById<EditText>(editTournamentName.id)?.text?.toString()?.trim()
                        val dateText = dialogView.findViewById<EditText>(editEventDate.id)?.text?.toString()?.trim()
                        if (!nameText.isNullOrBlank()) putString("tournamentName", nameText)
                        if (!dateText.isNullOrBlank()) putString("eventDate", dateText)
                    } catch (_: Exception) {}

                    apply()
                }

                tournamentInfoText.text = "${getSelectedPattern().patternCode} / ${getCurrentSession()}"
                Toast.makeText(
                    context,
                    "設定: ${getSelectedPattern().name} [${getCurrentSession()}]",
                    Toast.LENGTH_SHORT
                ).show()

                updateTournamentInfoText()
                onComplete()
            }
            .create()

        dialog.show()

        // ▼ ダイアログを画面ほぼいっぱいまで拡大する（97%）
        val width = (context.resources.displayMetrics.widthPixels * 0.97).toInt()
        dialog.window?.setLayout(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun showEntryNoEditDialog(
        entryMap: Map<Int, Pair<String, String>>,
        lastOcrHadEntry: Boolean,
        hasScoreResult: Boolean
    ) {
        val currentNo = resultText.text.toString().replace(Regex("[^0-9]"), "")
        val et = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2)) // 1〜99想定
            setText(currentNo)
            hint = "1〜99"
            setSelection(text?.length ?: 0)
        }

        AlertDialog.Builder(context)
            .setTitle("エントリー番号を入力")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val no = et.text.toString().toIntOrNull()
                if (no == null || no !in 1..99) {
                    Toast.makeText(context, "1〜99の数字を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 表示＆手入力フラグ（黄色）
                resultText.text = "No: $no"
                resultText.setBackgroundColor(Color.parseColor("#FFE599"))

                // 登録確認メッセージ
                val entry = entryMap[no]
                if (entry != null) {
                    val (name, clazz) = entry
                    Toast.makeText(context, "✅ $name さん [$clazz] を選択", Toast.LENGTH_SHORT).show()
                    onClassChanged(clazz)
                    tournamentInfoText.text = "${getSelectedPattern().patternCode} / ${getCurrentSession()} / ${getCurrentRowClass() ?: "-"}"
                } else {
                    Toast.makeText(context, "⚠️ EntryNo=$no は未登録です（保存時は拒否されます）", Toast.LENGTH_LONG).show()
                    onClassChanged(null)
                }

                // C案: OCRで直近に番号が読めていたかどうかで分岐
                if (!lastOcrHadEntry) {
                    // OCRで番号未認識 → 確認ダイアログを出してからスコア解析を実行
                    AlertDialog.Builder(context)
                        .setTitle("スコア解析しますか？")
                        .setMessage("入力したエントリー番号でスコア解析を実行します。")
                        .setPositiveButton("実行") { _, _ ->
                            onCaptureScoreRequested()
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                } else {
                    // OCRで番号が読めていたケース
                    // 基本は既にスコア解析済みだが、万一未解析なら解析を開始
                    if (!hasScoreResult) {
                        onCaptureScoreRequested()
                    }
                    // 解析済みなら何もしない（再解析不要）
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // クラス編集ダイアログ（Prefs の大会種別に応じた選択肢）
    fun showClassPickerDialog() {
        val prefs = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
        val tType = prefs.getString("tournamentType", "beginner") ?: "beginner"
        val options = when (tType) {
            "championship" -> arrayOf("IA", "IB", "NA", "NB")
            else -> arrayOf("オープン", "ビギナー")
        }

        val currentIndex = options.indexOf(getCurrentRowClass()).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle("クラスを選択")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = options[which]
                onClassChanged(selected)
                // UI 表示を更新
                tournamentInfoText.text = "${getSelectedPattern().patternCode} / ${getCurrentSession()} / ${getCurrentRowClass() ?: "-"}"
                Toast.makeText(context, "クラスを $selected に変更しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
