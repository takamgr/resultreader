// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import androidx.camera.core.Camera
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.example.resultreader.TournamentPattern
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.resultreader.BuildConfig



class CameraActivity : AppCompatActivity() {

    private val inactivityTimeout = 10000L  // ← 5秒でスリープ
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var isCameraSuspended = false

    private var isManualCameraControl = false
    private lateinit var guideToggleButton: ImageButton

    private var isCameraReady: Boolean = false
    private var entryMap: Map<Int, Pair<String, String>> = emptyMap()







    private var selectedPattern: TournamentPattern = TournamentPattern.PATTERN_4x2
    private val OCR_LEFT = 445
    private val OCR_TOP = 750
    private val OCR_WIDTH = 280
    private val OCR_HEIGHT = 220
    private val OCR_RECT_PX = Rect(OCR_LEFT, OCR_TOP, OCR_LEFT + OCR_WIDTH, OCR_TOP + OCR_HEIGHT)

    private var lastSavedCsvFile: File? = null

    private var currentSession: String = "AM"

    private lateinit var flashToggleButton: ImageButton
    private lateinit var tournamentSettingButton: ImageButton  // ← Button から変更

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var prepareButton: Button
    private lateinit var confirmButton: Button
    private lateinit var guideOverlay: GuideOverlayView
    private lateinit var scorePreview: ImageView
    private lateinit var tournamentInfoText: TextView




    private var isOcrRunning = false

    private var isFlashOn = false
    private var pendingSaveBitmap: Bitmap? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private lateinit var scoreLabelViews: List<TextView>



    private val entryListPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                copyCsvToAppStorage(uri)
            } else {
                Toast.makeText(this, "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show()
            }
        }





    private fun suspendCameraAndScreen() {
        if (isCameraSuspended || isOcrRunning) return  // ← これ！！
        isCameraSuspended = true


        // CameraX完全停止
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
            camera = null
        } catch (e: Exception) {
            Log.e("SLEEP", "Camera停止失敗", e)
        }

        // 表示系停止＋背景黒
        previewView.visibility = View.GONE
        guideOverlay.visibility = View.GONE
        findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.BLACK)

        // スクリーンONフラグ外す
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.d("SLEEP", "💤 スリープモード突入")
    }




    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacksAndMessages(null)
        inactivityHandler.postDelayed({ suspendCameraAndScreen() }, inactivityTimeout)
    }





    private fun recalculateScore() {
        var totalScore = 0
        var cleanCount = 0
        var hasError = false

        val totalCount = when (selectedPattern) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }

        for ((index, label) in scoreLabelViews.withIndex()) {
            var scoreText = label.text.toString().trim()

            // 🔥 設定外の位置にスコアがある → 強制99
            if (index >= totalCount && scoreText in listOf("0", "1", "2", "3", "5")) {
                scoreText = "99"
                label.text = "99"
                label.setBackgroundResource(R.drawable.bg_score_unknown)
            }

            // ✅ 有効範囲に99・空欄・ダッシュなど → エラー
            if (index < totalCount && scoreText in listOf("", "-", "ー", "―", "99")) {
                hasError = true
            }

            Log.d("SAVE_CHECK", "ラベル $index = \"$scoreText\"")

            when (scoreText) {
                "0" -> cleanCount++
                "1" -> totalScore += 1
                "2" -> totalScore += 2
                "3" -> totalScore += 3
                "5" -> totalScore += 5
            }
        }

        val pointText = findViewById<TextView>(R.id.scorePointText)
        val cleanText = findViewById<TextView>(R.id.scoreCleanText)

        if (hasError) {
            pointText.text = "G:　-"
            cleanText.text = "C:　-"
            confirmButton.visibility = View.GONE
            guideOverlay.setDetected("yellow")
            Toast.makeText(this, "⚠️ スコアに空欄やエラー（99など）が含まれています", Toast.LENGTH_SHORT).show()
        } else {
            pointText.text = "G:　$totalScore"
            cleanText.text = "C:　$cleanCount"
            confirmButton.visibility = View.VISIBLE
            guideOverlay.setDetected("green")
        }
    }






    // 画像保存を無効化（今後は保存しない）
    private fun saveImage(bitmap: Bitmap) {
        // スコア画像の保存は行いません（デバッグ用途の保存機能は廃止）
        Log.d("SaveImage", "スコア画像保存は現在無効です")
    }


    private fun showScoreInputDialog(targetLabel: TextView) {
        val options = arrayOf("0", "1", "2", "3", "5", "-")

        val builder = AlertDialog.Builder(this)
        builder.setTitle("スコアを選択してください")
        builder.setItems(options) { _, which ->
            val selected = options[which]
            targetLabel.text = selected

            val entryNoLabel: TextView = findViewById(R.id.resultText)
            entryNoLabel.setBackgroundColor(Color.parseColor("#FFE599"))

            when (selected) {
                "0" -> targetLabel.setBackgroundResource(R.drawable.bg_score_clean)
                "1", "2", "3", "5" -> targetLabel.setBackgroundResource(R.drawable.bg_score_deduction)
                "-" -> targetLabel.setBackgroundResource(R.drawable.bg_score_blank)
            }

            // 🔥ここでスコア再計算を明示的に実行！
            recalculateScore()

            Toast.makeText(this, "※ 手入力でスコアを修正しました", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("キャンセル", null)
        builder.show()
    }
    private fun copyCsvToAppStorage(uri: Uri) {
        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val isDebug = BuildConfig.IS_DEBUG
        val alreadyLoaded = prefs.getBoolean("entrylist_loaded_once", false)

        if (alreadyLoaded && !isDebug) {
            Toast.makeText(this, "⚠️ entrylist.csv はすでに読み込まれています", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = "EntryList.csv"
        val destFile = File(getExternalFilesDir("ResultReader"), fileName)

        val inputStream = contentResolver.openInputStream(uri)
        if (inputStream == null) {
            Toast.makeText(this, "❌ entrylist 読み込みに失敗しました", Toast.LENGTH_LONG).show()
            return
        }

        inputStream.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }

        val reader = BufferedReader(InputStreamReader(destFile.inputStream(), Charsets.UTF_8))
        val lines = reader.readLines()

        entryMap = lines.drop(1).mapNotNull { line ->
            val parts = line.split(",")
            val no = parts.getOrNull(0)?.toIntOrNull()
            val name = parts.getOrNull(1)?.trim()
            val clazz = parts.getOrNull(2)?.trim()
            if (no != null && !name.isNullOrBlank() && !clazz.isNullOrBlank()) {
                no to (name to clazz)
            } else null
        }.toMap()

        // 大会種別をCSVから判定
        val clazzes = entryMap.values.map { it.second }.toSet()
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
            AlertDialog.Builder(this)
                .setTitle("⚠️ 大会種別の変更確認")
                .setMessage("現在の設定：$currentType\nCSVから検出：$detectedType\n\n大会設定を変更しますか？")
                .setPositiveButton("変更する") { _, _ ->
                    prefs.edit()
                        .putString("tournamentType", detectedType)
                        .putBoolean("entrylist_loaded_once", true)
                        .apply()
                    updateTournamentInfoText()
                    Toast.makeText(this, "✅ エントリーリスト読み込み完了\n$toastText", Toast.LENGTH_LONG).show()

                    // 読み込み完了後にボタンを非表示に！
                    findViewById<ImageButton>(R.id.entryListImportButton).visibility = View.GONE
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            prefs.edit()
                .putString("tournamentType", detectedType)
                .putBoolean("entrylist_loaded_once", true)
                .apply()
            updateTournamentInfoText()
            Toast.makeText(this, "✅ エントリーリスト読み込み完了\n$toastText", Toast.LENGTH_LONG).show()

            // 読み込み完了後にボタンを非表示に！
            findViewById<ImageButton>(R.id.entryListImportButton).visibility = View.GONE
        }
    }





    private fun updateTournamentInfoText() {
        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val type = when (prefs.getString("tournamentType", "unknown")) {
            "championship" -> "選手権"
            "beginner" -> "ビギナー"
            else -> "不明"
        }
        val patternText = selectedPattern.patternCode
        tournamentInfoText.text = "$patternText / $currentSession / $type"

        prefs.edit().putBoolean("entrylist_loaded_once", true).apply()



    }










    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // まずレイアウトを先に読み込む！
        setContentView(R.layout.activity_camera)

        guideToggleButton = findViewById(R.id.guideToggleButton)  // ← これ忘れずに！
        entryMap = CsvUtils.loadEntryMapFromCsv(this)

        guideToggleButton.setOnClickListener {
            if (!isCameraReady) {
                startCamera()
                isCameraReady = true
                isManualCameraControl = true
                Toast.makeText(this, "📷 手動でカメラ起動", Toast.LENGTH_SHORT).show()
            } else {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                camera = null
                imageCapture = null
                isCameraReady = false
                isManualCameraControl = false

                // 🔥 表示を完全にOFF
                previewView.visibility = View.GONE
                previewView.alpha = 0f
                previewView.setBackgroundColor(Color.BLACK)

                guideOverlay.visibility = View.GONE
                findViewById<FrameLayout>(R.id.previewContainer)
                    .setBackgroundColor(Color.BLACK)

                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                Toast.makeText(this, "📴 カメラ手動OFF", Toast.LENGTH_SHORT).show()
            }
        }

        // 🔽 onCreate() 内の setContentView() のあと、Viewの初期化のすぐ後あたりに追記
        scoreLabelViews = listOf(
            findViewById(R.id.scoreLabel1),
            findViewById(R.id.scoreLabel2),
            findViewById(R.id.scoreLabel3),
            findViewById(R.id.scoreLabel4),
            findViewById(R.id.scoreLabel5),
            findViewById(R.id.scoreLabel6),
            findViewById(R.id.scoreLabel7),
            findViewById(R.id.scoreLabel8),
            findViewById(R.id.scoreLabel9),
            findViewById(R.id.scoreLabel10),
            findViewById(R.id.scoreLabel11),
            findViewById(R.id.scoreLabel12),

            )
        // スコアラベルにクリックリスナーを設定（手入力対応）
        scoreLabelViews.forEach { label ->
            label.setOnClickListener {
                showScoreInputDialog(label)
            }
        }

        resultText = findViewById(R.id.resultText)
        tournamentInfoText = findViewById(R.id.tournamentInfoText)
        prepareButton = findViewById(R.id.prepareButton)
        confirmButton = findViewById(R.id.confirmButton)
        flashToggleButton = findViewById(R.id.flashToggleButton)
        guideOverlay = findViewById(R.id.guideOverlay)
        scorePreview = findViewById(R.id.scorePreview)
        previewView = findViewById(R.id.previewView)

        // その後に SharedPreferences → 設定ダイアログ呼び出し！
        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastSetDate = prefs.getString("lastSetDate", null)

        if (today != lastSetDate) {
            showInitialTournamentSettingDialog {
                prefs.edit().apply {
                    putString("lastSetDate", today)
                    putString("lastPattern", selectedPattern.name)
                    putString("lastSession", currentSession)
                    apply()
                }
                tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession"


            }
        } else {


            // すでに設定済 → 保存内容を反映
            val savedPattern = prefs.getString("lastPattern", null)
            val savedSession = prefs.getString("lastSession", null)

            if (savedPattern != null) {
                selectedPattern = TournamentPattern.valueOf(savedPattern)
                Log.d("INIT", "🟢 Saved pattern loaded: $selectedPattern")
            }

            if (savedSession != null) {
                currentSession = savedSession
                Log.d("INIT", "🟢 Saved session loaded: $currentSession")
                Toast.makeText(this, "【復元】$currentSession セッションで起動", Toast.LENGTH_SHORT)
                    .show()

                tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession"



            } else {
                Log.w("INIT", "⚠️ Saved session not found, defaulting to AM")
            }
        }

        val openCsvImageButton = findViewById<ImageButton>(R.id.openCsvImageButton)
        openCsvImageButton.setOnClickListener {
            val csvDir = getExternalFilesDir("ResultReader")
            val csvFiles = csvDir?.listFiles { file -> file.extension == "csv" } ?: emptyArray()

            if (csvFiles.isEmpty()) {
                Toast.makeText(this, "保存されたCSVが見つかりません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val fileNames = csvFiles.map { it.name }

            val builder = AlertDialog.Builder(this)
            builder.setTitle("CSVファイル一覧")

            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, fileNames)

            builder.setAdapter(adapter) { _, which ->
                // タップ → 開く
                openCsvFile(csvFiles[which])
            }

            val dialog = builder.create()

            dialog.setOnShowListener {
                dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                    val fileToDelete = csvFiles[position]
                    AlertDialog.Builder(this)
                        .setTitle("削除確認")
                        .setMessage("「${fileToDelete.name}」を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            if (fileToDelete.delete()) {
                                Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                                openCsvImageButton.performClick() // 再表示！
                            } else {
                                Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                    true
                }
            }

            dialog.show()
        }





        // 初期表示設定
        guideOverlay.bringToFront()
        guideOverlay.setDetected("red")
        confirmButton.visibility = View.GONE
        scorePreview.visibility = View.GONE

        // フラッシュ

        flashToggleButton = findViewById(R.id.flashToggleButton)
        flashToggleButton.setOnClickListener {
            isFlashOn = !isFlashOn
            camera?.cameraControl?.enableTorch(isFlashOn)
            flashToggleButton.setImageResource(
                if (isFlashOn) R.drawable.ic_flash_off else R.drawable.ic_flash
            )
        }

        tournamentSettingButton = findViewById(R.id.tournamentSettingButton)
        tournamentSettingButton.setOnClickListener {
            showInitialTournamentSettingDialog {}
        }


        // 撮影準備
        prepareButton.setOnClickListener {
            if (!isCameraReady) {
                startCamera()
                isCameraReady = true
                isManualCameraControl = false

                Handler(Looper.getMainLooper()).postDelayed({
                    startOcrCapture()
                }, 300)
                return@setOnClickListener
            }

            startOcrCapture()


            resultText.text = "認識中…"
            guideOverlay.setDetected("red")
            confirmButton.visibility = View.GONE
            scorePreview.visibility = View.GONE
            captureAndAnalyzeMultiple()
        }

        // 保存処理
        confirmButton.setOnClickListener {
            val totalCount = when (selectedPattern) {
                TournamentPattern.PATTERN_4x2 -> 8
                TournamentPattern.PATTERN_4x3 -> 12
                TournamentPattern.PATTERN_5x2 -> 10
            }
            val hasInvalid = scoreLabelViews
                .take(totalCount)
                .any { it.text.toString().trim() in listOf("99", "", "-", "ー", "―") }

            if (hasInvalid) {
                Toast.makeText(this, "❌ スコアに空欄やエラー（99など）が含まれているため保存できません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val entryNumber = resultText.text.toString().replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val patternCode = selectedPattern.patternCode
            val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            val today = dateFormat.format(Date())
            val csvFileName = "result_${patternCode}_$today.csv"
            val csvFile = File(getExternalFilesDir("ResultReader"), csvFileName)

            if (csvFile.exists()) {
                val header = csvFile.bufferedReader().readLine()
                    ?.split(",")
                    ?.map { it.replace("\uFEFF", "").trim() } ?: emptyList()

                val lines = csvFile.readLines().drop(1)

                val entryCol = header.indexOf("EntryNo")
                val sessionCol = header.indexOf("セッション")

                Log.d("CSV_DEBUG", "列一覧 = $header")
                Log.d("CSV_DEBUG", "EntryCol = $entryCol / SessionCol = $sessionCol")
                if (entryCol == -1 || sessionCol == -1) {
                    Log.e("CSV_DEBUG", "列名が見つかりません！セッション or EntryNo")
                }

            // ✅ ① 同じセッション＆同じEntryNo → 上書き確認
                val alreadyHasThisSession = lines.any { line ->
                    val cols = line.split(",")
                    cols.getOrNull(entryCol)?.toIntOrNull() == entryNumber &&
                            cols.getOrNull(sessionCol) == currentSession
                }

                if (alreadyHasThisSession) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ 上書き確認")
                        .setMessage("エントリー${entryNumber}はすでに $currentSession に記録があります。\nこのまま保存すると上書きされます。よろしいですか？")
                        .setPositiveButton("続けて保存") { _, _ ->
                            proceedWithSave(entryNumber)
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                    return@setOnClickListener
                }

                // ✅ ② AM保存済みで再びAM → セッション切替警告
                val alreadyHasAM = lines.any { line ->
                    val cols = line.split(",")
                    cols.getOrNull(entryCol)?.toIntOrNull() == entryNumber &&
                            cols.getOrNull(sessionCol) == "AM"
                }

                if (currentSession == "AM" && alreadyHasAM) {
                    AlertDialog.Builder(this)
                        .setTitle("⚠️ セッション切替確認")
                        .setMessage("このエントリー番号はすでにAMに保存されています。\nPMに切り替え忘れていませんか？")
                        .setPositiveButton("続けて保存") { _, _ ->
                            proceedWithSave(entryNumber)
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                    return@setOnClickListener
                }
            }

            // ✅ ③ 午後の時間帯でAMセッション → リマインド
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (currentHour >= 13 && currentSession == "AM") {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 時間によるセッション警告")
                    .setMessage("現在13時を過ぎていますが、セッションはAMのままです。\n切り替え忘れていませんか？")
                    .setPositiveButton("続けて保存") { _, _ ->
                        proceedWithSave(entryNumber)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                return@setOnClickListener
            }

            // ✅ 上記に引っかからなければそのまま保存実行
            proceedWithSave(entryNumber)
        }

        findViewById<ImageButton>(R.id.entryListImportButton).setOnClickListener {
            entryListPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv"))
        }
        val prefsEntryCheck = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val alreadyLoaded = prefsEntryCheck.getBoolean("entrylist_loaded_once", false)
        val entryListImportButton = findViewById<ImageButton>(R.id.entryListImportButton)

// ✅ 1日1回制限：読み込み済なら非表示
        if (alreadyLoaded) {
            entryListImportButton.visibility = View.GONE
        } else {
            entryListImportButton.setOnClickListener {
                entryListPickerLauncher.launch(
                    arrayOf("text/csv", "text/comma-separated-values", "application/csv")
                )
            }
        }

// ✅ 長押しで解除（トースト付き）
        entryListImportButton.setOnLongClickListener {
            prefsEntryCheck.edit().putBoolean("entrylist_loaded_once", false).apply()
            Toast.makeText(this, "🔓 entrylist の再読み込みが有効になりました", Toast.LENGTH_SHORT).show()
            true
        }








        cameraExecutor = Executors.newSingleThreadExecutor()
        if (allPermissionsGranted())
            else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        val tournamentSettingButton = findViewById<ImageButton>(R.id.tournamentSettingButton)
        tournamentSettingButton.setOnClickListener {
            val prefsEntryCheck = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            val alreadyLoaded = prefsEntryCheck.getBoolean("entrylist_loaded_once", false)

            if (alreadyLoaded) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ 大会設定の変更確認")
                    .setMessage("エントリーリストを読み込んだ後に大会設定を変更すると、保存形式と不一致が発生する可能性があります。\n\nそれでも変更しますか？")
                    .setPositiveButton("はい（変更する）") { _, _ ->
                        showInitialTournamentSettingDialog {}
                    }
                    .setNegativeButton("いいえ（キャンセル）", null)
                    .show()
            } else {
                // 未読み込み時はそのまま変更可能
                showInitialTournamentSettingDialog {}
            }
        }




// ← 長押しで entrylist 再読み込み解除＋ボタン復活
        tournamentSettingButton.setOnLongClickListener {
            val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            prefs.edit().putBoolean("entrylist_loaded_once", false).apply()

            val entryListImportButton = findViewById<ImageButton>(R.id.entryListImportButton)
            entryListImportButton.visibility = View.VISIBLE

            Toast.makeText(this, "🔓 entrylist 再読み込みが有効になりました", Toast.LENGTH_SHORT).show()
            true
        }

        resetInactivityTimer()  // ← 初期化時にも起動しておく

        entryMap = loadEntryMap()


    }
    private fun proceedWithSave(entryNumber: Int) {
        pendingSaveBitmap?.let {
            saveImage(it)

            val amCount = when (selectedPattern) {
                TournamentPattern.PATTERN_4x2 -> 8
                TournamentPattern.PATTERN_4x3 -> 12
                TournamentPattern.PATTERN_5x2 -> 10
            }
            val totalCount = amCount * 2
            val pmCount = totalCount - amCount

            val scoreList = when (currentSession) {
                "AM" -> scoreLabelViews.map { it.text.toString().toIntOrNull() } + List(pmCount) { null }
                "PM" -> List(amCount) { null } + scoreLabelViews.map { it.text.toString().toIntOrNull() }
                else -> List(amCount + pmCount) { null }
            }.take(amCount + pmCount)

            val amScores = scoreList.take(amCount)
            val pmScores = scoreList.drop(amCount)

            val amScore = amScores.filterNotNull().sum()
            val amClean = amScores.count { it == 0 }
            val pmScore = pmScores.filterNotNull().sum()
            val pmClean = pmScores.count { it == 0 }

            val isManual = resultText.background != null &&
                    (resultText.background as? ColorDrawable)?.color == Color.parseColor("#FFE599")

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
                entryMap = entryMap
            )

            guideOverlay.setDetected("red")
            confirmButton.visibility = View.GONE
        }
    }



    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)  // ← これ追加！

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "カメラパーミッションが必要です", Toast.LENGTH_SHORT).show()
                finish()
            }

        }


    }

    private fun startOcrCapture() {
        resultText.text = "認識中…"
        guideOverlay.setDetected("red")
        confirmButton.visibility = View.GONE
        scorePreview.visibility = View.GONE
        captureAndAnalyzeMultiple()
    }







    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                // 🔥 表示系を正しく復元
                previewView.visibility = View.VISIBLE
                previewView.alpha = 1f
                previewView.setBackgroundColor(Color.TRANSPARENT)

                guideOverlay.visibility = View.VISIBLE
                findViewById<FrameLayout>(R.id.previewContainer)
                    .setBackgroundColor(Color.TRANSPARENT)

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                isCameraReady = true
                Log.d("CAMERA", "📷 カメラ起動完了")
            } catch (exc: Exception) {
                Log.e("CAMERA", "❌ カメラ起動失敗", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }



    private fun captureAndAnalyzeMultiple() {
        val currentImageCapture = imageCapture ?: return
        val results = mutableListOf<ScoreAnalyzer.ScoreResult>()

        fun takeNext(count: Int) {
            if (count >= 3) {
                val grouped = results.groupBy { it.sectionScores }
                val majority = grouped.maxByOrNull { it.value.size }?.value?.firstOrNull()

                if (majority != null) {
                    updateScoreUi(majority)
                    guideOverlay.setDetected("green")
                    confirmButton.visibility = View.VISIBLE
                } else {
                    guideOverlay.setDetected("red")
                    Toast.makeText(
                        this,
                        "⚠️ 判定一致せず：手動確認して修正してください",
                        Toast.LENGTH_LONG
                    ).show()
                    confirmButton.visibility = View.VISIBLE
                }

                // 🔥 自動モード時のみカメラ完全停止！
                if (!isManualCameraControl) {
                    val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                    cameraProvider.unbindAll()
                    camera = null
                    imageCapture = null
                    isCameraReady = false

                    // 🔥 プレビューを完全OFF
                    previewView.visibility = View.GONE
                    previewView.alpha = 0f
                    previewView.setBackgroundColor(Color.BLACK)

                    guideOverlay.visibility = View.GONE
                    findViewById<FrameLayout>(R.id.previewContainer)
                        .setBackgroundColor(Color.BLACK)

                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                    Log.d("CAMERA", "📴 OCR完了後にカメラ自動停止")
                }



                return
            }

            val photoFile = File.createTempFile("ocr_temp_$count", ".jpg", cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            currentImageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        val rotated = rotateBitmap(bitmap, 90f)
                        val cropped = Bitmap.createBitmap(
                            rotated,
                            OCR_RECT_PX.left,
                            OCR_RECT_PX.top,
                            OCR_RECT_PX.width(),
                            OCR_RECT_PX.height()
                        )

                        recognizeText(cropped, rotated) { result ->
                            result?.let { results.add(it) }
                            takeNext(count + 1)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        Toast.makeText(applicationContext, "撮影エラー", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        takeNext(0)


}



    private fun recognizeText(
        bitmap: Bitmap,
        fullImage: Bitmap,
        callback: (ScoreAnalyzer.ScoreResult?) -> Unit
    ) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val cleanText = rawText.replace(Regex("[^0-9]"), "").trim()
                val entryNumber = cleanText.toIntOrNull()
                val isWarningId = cleanText in listOf("6", "9", "06", "09", "60", "90", "69", "96")

                if (entryNumber != null && entryNumber in 1..99) {
                    resultText.text = "No: $entryNumber"
                    pendingSaveBitmap = fullImage

                    if (isWarningId) {
                        guideOverlay.setDetected("yellow")
                        Toast.makeText(this, "⚠️ 誤認注意番号", Toast.LENGTH_SHORT).show()
                    }

                    // 🔥 登録確認とトースト表示（ここが今回の追加）
                    val entry = entryMap[entryNumber]
                    if (entry != null) {
                        val (name, clazz) = entry
                        Toast.makeText(this, "✅ $name さん [$clazz]", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ EntryNo=$entryNumber は未登録です", Toast.LENGTH_LONG).show()
                    }

                    val scoreX = 570
                    val scoreY = 1030
                    val scoreWidth = 990
                    val scoreHeight = 400

                    val scaleX = 1.67f
                    val scaleY = 2.20f
                    val bx = (scoreX * scaleX).toInt() - 540
                    val by = (scoreY * scaleY).toInt() - 1160
                    val bw = (scoreWidth * scaleX).toInt() + 380
                    val bh = (scoreHeight * scaleY).toInt() - 70

                    val roiOffsetX = 25
                    val roiOffsetY = 50
                    val rawScoreBitmap = Bitmap.createBitmap(
                        fullImage,
                        (bx + roiOffsetX).coerceIn(0, fullImage.width - bw),
                        (by + roiOffsetY).coerceIn(0, fullImage.height - bh),
                        bw,
                        bh
                    )

                    val scoreBitmap = rawScoreBitmap.copy(Bitmap.Config.ARGB_8888, true)

                    // ← マゼンタ罫線などのデバッグ描画
                    showDebugScoreOnPreview(scoreBitmap)

                    val result = ScoreAnalyzer.analyze(scoreBitmap)
                    scorePreview.setImageBitmap(scoreBitmap)
                    scorePreview.visibility = View.VISIBLE
                    callback(result)
                } else {
                    guideOverlay.setDetected("red")
                    Toast.makeText(this, "認識エラー: $rawText", Toast.LENGTH_SHORT).show()
                    callback(null)
                }
            }
            .addOnFailureListener {
                guideOverlay.setDetected("red")
                Toast.makeText(this, "OCR失敗", Toast.LENGTH_SHORT).show()
                callback(null)
            }
    }



    private fun updateScoreUi(result: ScoreAnalyzer.ScoreResult) {
        val activeCount = when (selectedPattern) {
            TournamentPattern.PATTERN_4x2 -> 8
            TournamentPattern.PATTERN_4x3 -> 12
            TournamentPattern.PATTERN_5x2 -> 10
        }

        findViewById<TextView>(R.id.scorePointText).text = "G:　${result.totalScore}"
        findViewById<TextView>(R.id.scoreCleanText).text = "C:　${result.cleanCount}"
        resultText.setBackgroundColor(Color.WHITE)

        result.sectionScores.forEachIndexed { index, score ->
            val label = scoreLabelViews.getOrNull(index)

            if (label != null) {
                // 👇 地獄回避ロジック：使うセクション内で空欄 = 99強制
                val safeScore = when {
                    index >= activeCount && score != null -> 99                     // 設定外にスコア → 99
                    index < activeCount && score == null -> 99                     // 設定内に空欄 → 打ち忘れ → 99
                    else -> score                                                  // 通常通り
                }

                label.text = safeScore?.toString() ?: ""

                when (safeScore) {
                    null -> label.setBackgroundResource(R.drawable.bg_score_blank)
                    0 -> label.setBackgroundResource(R.drawable.bg_score_clean)
                    in listOf(1, 2, 3, 5) -> label.setBackgroundResource(R.drawable.bg_score_deduction)
                    99 -> label.setBackgroundResource(R.drawable.bg_score_unknown) // 99専用背景
                    else -> label.setBackgroundResource(R.drawable.bg_score_unknown)
                }
            }
        }

        // ✅ OCR反映後に封鎖チェック（Phase1）
        recalculateScore()
    }


    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10

    }



    private fun showDebugScoreOnPreview(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)

        val paintGrid = Paint().apply {
            color = Color.MAGENTA
            strokeWidth = 12f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val paintRoi = Paint().apply {
            color = Color.CYAN
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val rows = 5
        val cols = 12
        val cellWidth = bitmap.width / cols
        val cellHeight = bitmap.height / rows

        canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paintGrid)

        for (row in 0..rows) {
            val y = row * cellHeight.toFloat()
            canvas.drawLine(0f, y, bitmap.width.toFloat(), y, paintGrid)
        }

        for (col in 0..cols) {
            val x = col * cellWidth.toFloat()
            canvas.drawLine(x, 0f, x, bitmap.height.toFloat(), paintGrid)
        }

        val roiSize = 58
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = col * cellWidth + cellWidth / 2
                val cy = row * cellHeight + cellHeight / 2
                val left = (cx - roiSize).toFloat()
                val top = (cy - roiSize).toFloat()
                val right = (cx + roiSize).toFloat()
                val bottom = (cy + roiSize).toFloat()
                canvas.drawRect(left, top, right, bottom, paintRoi)
            }
        }

        scorePreview.setImageBitmap(bitmap)
        scorePreview.visibility = View.VISIBLE
    }
    // CameraActivity.kt にまるっと追加するコード
// 大会設定のダイアログ（起動時表示 + 選択保持 + UI反映）



    private fun showInitialTournamentSettingDialog(onComplete: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tournament_setting, null)

        val radioGroupPattern = dialogView.findViewById<RadioGroup>(R.id.radioGroupPattern)
        val radio4x2 = dialogView.findViewById<RadioButton>(R.id.radioPattern4x2)
        val radio4x3 = dialogView.findViewById<RadioButton>(R.id.radioPattern4x3)
        val radio5x2 = dialogView.findViewById<RadioButton>(R.id.radioPattern5x2)
        val amButton = dialogView.findViewById<Button>(R.id.buttonAM)
        val pmButton = dialogView.findViewById<Button>(R.id.buttonPM)

        // ⬇ 追加：大会種別（ビギナー／選手権）
        val beginnerRadioButton = dialogView.findViewById<RadioButton>(R.id.radioBeginner)
        val championshipRadioButton = dialogView.findViewById<RadioButton>(R.id.radioChampionship)

        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        when (prefs.getString("tournamentType", "beginner")) {
            "championship" -> championshipRadioButton.isChecked = true
            else -> beginnerRadioButton.isChecked = true
        }

        // 既存値をUIに反映
        when (selectedPattern) {
            TournamentPattern.PATTERN_4x2 -> radio4x2.isChecked = true
            TournamentPattern.PATTERN_4x3 -> radio4x3.isChecked = true
            TournamentPattern.PATTERN_5x2 -> radio5x2.isChecked = true
        }

        updateSessionButtons(amButton, pmButton)

        amButton.setOnClickListener {
            currentSession = "AM"
            val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            prefs.edit().putString("lastSession", "AM").apply()
            updateSessionButtons(amButton, pmButton)
        }

        pmButton.setOnClickListener {
            currentSession = "PM"
            val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            prefs.edit().putString("lastSession", "PM").apply()
            updateSessionButtons(amButton, pmButton)
        }

        AlertDialog.Builder(this)
            .setTitle("本日の大会設定を確認してください")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                selectedPattern = when (radioGroupPattern.checkedRadioButtonId) {
                    R.id.radioPattern4x2 -> TournamentPattern.PATTERN_4x2
                    R.id.radioPattern4x3 -> TournamentPattern.PATTERN_4x3
                    R.id.radioPattern5x2 -> TournamentPattern.PATTERN_5x2
                    else -> TournamentPattern.PATTERN_4x2
                }

                val tournamentType = if (championshipRadioButton.isChecked) "championship" else "beginner"

                val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                prefs.edit().apply {
                    putString("lastSetDate", today)
                    putString("lastPattern", selectedPattern.name)
                    putString("lastSession", currentSession)
                    putString("tournamentType", tournamentType) // ← 🔥 追加ここ！
                    apply()
                }

                tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession"

                Toast.makeText(this, "設定: ${selectedPattern.name} [$currentSession]", Toast.LENGTH_SHORT).show()
                updateTournamentInfoText()

                onComplete()
            }
            .show()
    }

    private fun loadEntryMap(): Map<Int, Pair<String, String>> {
        val entryMap = mutableMapOf<Int, Pair<String, String>>()
        val csvFile = File(getExternalFilesDir("ResultReader"), "EntryList.csv")
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



    private fun updateSessionButtons(amButton: Button, pmButton: Button) {
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
    private fun openCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "com.example.resultreader.fileprovider", // ← 固定authority
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "CSVを開くアプリが見つかりません", Toast.LENGTH_SHORT).show()
        }




    }




}

