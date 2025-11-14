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
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.PopupMenu



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

    // 手入力でEntryNoが確定済みかどうかの簡易フラグ
    private var manualEntryCommitted: Boolean = false
    // C案: OCRで直近にEntryNoが読めたか／画面にスコア解析結果があるか
    private var lastOcrHadEntry: Boolean = false  // 直近のOCRでEntryNoが読めたか
    private var hasScoreResult: Boolean = false   // 画面にスコア解析結果が出ているか
    // 現在画面に表示されているエントリのクラス（UIで上書き可能）
    private var currentRowClass: String? = null
    // 判定音の再生抑止用タイムスタンプ（ミリ秒）
    private var lastJudgePlayTime: Long = 0L
    // SoundPool ベースの効果音（短い音向け）
    private var judgeSoundPool: android.media.SoundPool? = null
    private var judgeSoundOkId: Int = 0
    private var judgeSoundCheckId: Int = 0
    private var judgeSoundsLoaded: Boolean = false
    // 個別ロード完了フラグ（SoundPool の各サンプルがロード済みか）
    private var judgeOkLoaded: Boolean = false
    private var judgeCheckLoaded: Boolean = false
    // 直近に再生した判定（null=なし, true=OK, false=NG）を保持して同一状態の連続再生を抑止
    private var lastJudgeState: Boolean? = null
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





    // ★ 判定音を鳴らす（true = 正解, false = 要確認）
    // 変更: SoundPool を優先し、MediaPlayer をフォールバックとして使う。短い効果音は SoundPool が信頼性高い。
    private fun ensureJudgeSoundsLoaded() {
        if (judgeSoundsLoaded) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                judgeSoundPool = android.media.SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                judgeSoundPool = android.media.SoundPool(2, android.media.AudioManager.STREAM_MUSIC, 0)
            }

            // リソースは .wav でも .mp3 でも R.raw.* で参照できる
            judgeOkLoaded = false
            judgeCheckLoaded = false
            judgeSoundOkId = judgeSoundPool?.load(this, R.raw.judge_ok, 1) ?: 0
            judgeSoundCheckId = judgeSoundPool?.load(this, R.raw.judge_check, 1) ?: 0

            judgeSoundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                Log.d("JUDGE_SOUND", "onLoadComplete id=$sampleId status=$status")
                try {
                    if (sampleId == judgeSoundOkId && judgeSoundOkId != 0) judgeOkLoaded = true
                    if (sampleId == judgeSoundCheckId && judgeSoundCheckId != 0) judgeCheckLoaded = true
                    // judgeSoundsLoaded はどちらかがロード済みなら true とする（個別判定で再生可否を判断する）
                    judgeSoundsLoaded = judgeOkLoaded || judgeCheckLoaded
                } catch (e: Exception) {
                    Log.w("JUDGE_SOUND", "onLoadComplete handling failed", e)
                }
            }
         } catch (e: Exception) {
             Log.e("JUDGE_SOUND", "SoundPool init failed", e)
             judgeSoundPool = null
             judgeSoundsLoaded = false
         }
     }

    // 再生中フラグ（重複再生防止）
    private var isPlayingJudge: Boolean = false

    // ★ 判定音を鳴らす（true = 正解, false = 要確認）
    private fun playJudgeSound(isOk: Boolean) {
        val resId = if (isOk) R.raw.judge_ok else R.raw.judge_check

        try {
            val mp = android.media.MediaPlayer.create(this, resId)
            if (mp == null) {
                Log.e("JUDGE_SOUND", "MediaPlayer.create() returned null for res=$resId")
                return
            }

            mp.setOnCompletionListener {
                try {
                    it.release()
                } catch (_: Exception) { }
                Log.d("JUDGE_SOUND", "completed res=$resId isOk=$isOk")
            }

            mp.setOnErrorListener { player, what, extra ->
                try {
                    player.release()
                } catch (_: Exception) { }
                Log.e("JUDGE_SOUND", "error what=$what extra=$extra for res=$resId")
                true
            }

            mp.start()
            Log.d("JUDGE_SOUND", "start play res=$resId isOk=$isOk")
        } catch (e: Exception) {
            Log.e("JUDGE_SOUND", "play error for res=$resId", e)
        }
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

            // ★ 要確認音を1回だけ鳴らす
            playJudgeSound(false)
        } else {
            pointText.text = "G:　$totalScore"
            cleanText.text = "C:　$cleanCount"
            confirmButton.visibility = View.VISIBLE
            guideOverlay.setDetected("green")

            // ★ 正解音を1回だけ鳴らす
            playJudgeSound(true)
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
        // 追加: EntryNo を手入力で修正できるようにする
        resultText.setOnClickListener { showEntryNoEditDialog() }
        resultText.setOnLongClickListener { showEntryNoEditDialog(); true }
        // 追加: tournamentInfoText を長押し/タップでクラス編集ダイアログを開く
        tournamentInfoText.setOnClickListener { showClassPickerDialog() }
        tournamentInfoText.setOnLongClickListener { showClassPickerDialog(); true }
        prepareButton = findViewById(R.id.prepareButton)
        confirmButton = findViewById(R.id.confirmButton)
        flashToggleButton = findViewById(R.id.flashToggleButton)
        guideOverlay = findViewById(R.id.guideOverlay)
        scorePreview = findViewById(R.id.scorePreview)
        previewView = findViewById(R.id.previewView)

        // 追加: 掲示用出力ボタンのクリックリスナー（R生成前の環境でも安全に動作するよう動的取得）
        val exportS1ButtonId = resources.getIdentifier("exportS1Button", "id", packageName)
        if (exportS1ButtonId != 0) {
            val exportS1Button: Button? = findViewById(exportS1ButtonId)
            exportS1Button?.setOnClickListener {
                PrintableExporter.exportS1CsvToDownloads(this, selectedPattern)
            }
            // hide visible HTML button if exists
            findViewById<View?>(exportS1ButtonId)?.visibility = View.VISIBLE
        }

        // 追加: 安全に btnExportS1Csv / btnExportHtml / btnExportPdf にハンドラを登録（IDがあれば動作）
        val idBtnExportS1 = resources.getIdentifier("btnExportS1Csv", "id", packageName)
        if (idBtnExportS1 != 0) {
            findViewById<ImageButton?>(idBtnExportS1)?.setOnClickListener {
                PrintableExporter.exportS1CsvToDownloads(this, selectedPattern)
            }
        }

        // HTML and older PDF buttons are disabled/hidden. Hook unified Canvas exporter if legacy IDs used.
        val idBtnExportHtml = resources.getIdentifier("btnExportHtml", "id", packageName)
        if (idBtnExportHtml != 0) {
            findViewById<ImageButton?>(idBtnExportHtml)?.setOnClickListener {
                PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 15)
            }
        }

        val idBtnExportPdf = resources.getIdentifier("btnExportPdf", "id", packageName)
        if (idBtnExportPdf != 0) {
            findViewById<ImageButton?>(idBtnExportPdf)?.setOnClickListener {
                PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 15)
            }
        }

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
                    val fileTarget = csvFiles[position]
                    val items = arrayOf("開く", "ダウンロードへコピー", "共有", "削除", "キャンセル")

                    AlertDialog.Builder(this)
                        .setTitle(fileTarget.name)
                        .setItems(items) { d, which ->
                             when (which) {
                                 0 -> { // 開く
                                     openCsvFile(fileTarget)
                                 }
                                 1 -> { // ダウンロードへコピー
                                     val uri = copyToDownloads(fileTarget)
                                     if (uri != null) {
                                         Toast.makeText(this, "📂 ダウンロードにコピーしました\n${fileTarget.name}", Toast.LENGTH_LONG).show()
                                     } else {
                                         Toast.makeText(this, "コピーに失敗しました", Toast.LENGTH_SHORT).show()
                                     }
                                 }
                                 2 -> { // 共有
                                     shareCsvFile(fileTarget)
                                 }
                                 3 -> { // 削除（既存仕様を維持）
                                     AlertDialog.Builder(this)
                                         .setTitle("削除確認")
                                         .setMessage("「${fileTarget.name}」を削除しますか？")
                                         .setPositiveButton("削除") { _, _ ->
                                             if (fileTarget.delete()) {
                                                 Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                                                 // 一覧更新
                                                 findViewById<ImageButton>(R.id.openCsvImageButton).performClick()
                                             } else {
                                                 Toast.makeText(this, "削除に失敗しました", Toast.LENGTH_SHORT).show()
                                             }
                                             dialog.dismiss()
                                         }
                                         .setNegativeButton("キャンセル", null)
                                         .show()
                                 }
                                 else -> d.dismiss()
                             }
                         }
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
                // 保存不可時はチェック音を鳴らす
                playJudgeSound(false)
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

        // --- 追記: 下部の出力ボタンをランタイムで非表示にする（XMLは変更しない） ---
        listOf("exportHtmlButton", "exportS1Button", "printButton").forEach { idName ->
            val id = resources.getIdentifier(idName, "id", packageName)
            if (id != 0) {
                findViewById<View?>(id)?.visibility = View.GONE
            }
        }

        // --- 追記: 右上のCSVボタン候補を探索し、長押しで出力メニューを表示 ---
        fun setupExportLongPress(anchor: View) {
            val popup = PopupMenu(this, anchor)

            popup.menu.add(0, 3, 2, "CSVを保存")
            // Canvasベースの安定版PDF出力を追加
            popup.menu.add(0, 5, 3, "PDFを保存（Canvas）")
            // optional: popup.menu.add(0, 4, 3, "PDFを開く/印刷")

            popup.setOnMenuItemClickListener { item ->
                 when (item.itemId) {
                    1 -> {
                        // S1版CSV（HTML path removed; keep S1 CSV)
                        PrintableExporter.exportS1CsvToDownloads(this, selectedPattern)
                        true
                    }
                    2 -> {
                        // Canvas unified PDF (per-class single page behavior folded into unified renderer)
                        PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 15)
                        true
                    }



                    5 -> {
                        // Canvas PDF with per-row thin lines enabled for visual verification
                        PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 15)
                        true
                    }

                    3 -> {
                        PrintableExporter.exportS1CsvToDownloads(this, selectedPattern)
                        true
                    }
                    4 -> {
                        // legacy: map to unified Canvas exporter
                        PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 15)
                        true
                    }
                     else -> false
                 }
             }

            popup.show()
        }

            val candidateIds = listOf("openCsvImageButton", "openCsvButton", "csvOpenButton", "resultCsvButton")
        val anchorView = candidateIds
            .map { resources.getIdentifier(it, "id", packageName) }
            .mapNotNull { findViewById<View?>(it) }
            .firstOrNull()

        anchorView?.setOnLongClickListener {
            setupExportLongPress(it)
            true
        }
    }

    private fun proceedWithSave(entryNumber: Int) {
        pendingSaveBitmap?.let {
            // 1) 認識画像の保存（既存）
            saveImage(it)

            // 2) セクション数の算出（既存ロジック）
            val amCount = when (selectedPattern) {
                TournamentPattern.PATTERN_4x2 -> 8
                TournamentPattern.PATTERN_4x3 -> 12
                TournamentPattern.PATTERN_5x2 -> 10
            }
            val totalCount = amCount * 2
            val pmCount = totalCount - amCount

            // 3) AM/PM に応じてスコア配列を構成（既存ロジック）
            val scoreList = when (currentSession) {
                "AM" -> scoreLabelViews.map { it.text.toString().toIntOrNull() } + List(pmCount) { null }
                "PM" -> List(amCount) { null } + scoreLabelViews.map { it.text.toString().toIntOrNull() }
                else -> List(amCount + pmCount) { null }
            }.take(amCount + pmCount)

            val amScores = scoreList.take(amCount)
            val pmScores = scoreList.drop(amCount).take(pmCount)

            var amScore = 0
            var amClean = 0
            for (v in amScores) {
                if (v != null) {
                    amScore += v
                    if (v == 0) amClean++
                }
            }

            var pmScore = 0
            var pmClean = 0
            for (v in pmScores) {
                if (v != null) {
                    pmScore += v
                    if (v == 0) pmClean++
                }
            }

            // 4) 手入力（黄色背景）判定（既存）
            val isManual = resultText.background != null &&
                    (resultText.background as? ColorDrawable)?.color == Color.parseColor("#FFE599")

            // 5) クラス変更の可否チェック（既存）
            if (currentRowClass != null && !entryMap.containsKey(entryNumber)) {
                Toast.makeText(this, "⚠️ エントリーが未登録のためクラスを変更できません", Toast.LENGTH_LONG).show()
                return@let
            }

            // 6) 保存用の entryMap（既存）
            val effectiveEntryMap = entryMap.toMutableMap()
            if (currentRowClass != null) {
                val existing = entryMap[entryNumber]
                val name = existing?.first ?: ""
                effectiveEntryMap[entryNumber] = Pair(name, currentRowClass!!)
            }

            // 7) CSV 追記（仕様ガード遵守：列順・マージは CsvExporter 側に委譲）
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
                entryMap = effectiveEntryMap
            )

            // 8) UI 戻し＋保存ボタン隠し（既存）
            guideOverlay.setDetected("red")
            confirmButton.visibility = View.GONE

            // 9) ここで認識UIを丸ごと初期化（新規追加）
            clearRecognitionUi()

            // 10) 手動クラス指定はクリア（既存）
            currentRowClass = null
            updateTournamentInfoText()
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
        // 新規OCR開始時はスコア未解析扱いにリセット
        hasScoreResult = false
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
                    // エラー時はチェック音を鳴らす
                    playJudgeSound(false)
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
                    // OCRで番号が読めた
                    lastOcrHadEntry = true
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
                        // 現在行のクラスをセットして UI に反映
                        currentRowClass = clazz
                        // tournamentInfoText にクラスを追加表示（既存表示を壊さない）。ここではローカルprefsを使う
                        val prefsLocal = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
                        val typeLabel = if (prefsLocal.getString("tournamentType", "unknown") == "championship") "選手権" else "ビギナー"
                        tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession / $typeLabel / ${currentRowClass ?: "-"}"
                    } else {
                        Toast.makeText(this, "⚠️ EntryNo=$entryNumber は未登録です", Toast.LENGTH_LONG).show()
                        currentRowClass = null
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
                    // OCRで番号が読めなかった
                    lastOcrHadEntry = false
                    guideOverlay.setDetected("red")
                    Toast.makeText(this, "認識エラー: $rawText", Toast.LENGTH_SHORT).show()
                    callback(null)
                }
            }
            .addOnFailureListener {
                lastOcrHadEntry = false
                guideOverlay.setDetected("red")
                Toast.makeText(this, "OCR失敗", Toast.LENGTH_SHORT).show()
                callback(null)
            }
    }



    private fun updateScoreUi(result: ScoreAnalyzer.ScoreResult) {
        // スコアが適用された → 画面に解析結果あり
        hasScoreResult = true
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

        // --- 追加: 大会名と開催日をダイアログに動的追加（Plan B: XML変更なし）
        val prefsForDialog = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val initialName = prefsForDialog.getString("tournamentName", "") ?: ""
        val initialDate = prefsForDialog.getString("eventDate", "") ?: ""

        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * resources.displayMetrics.density).toInt()

        // 大会名入力
        val editTournamentName = EditText(this).apply {
            hint = "大会名（例：第3回 オラガバレーTRIALS）"
            setText(initialName)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = lp
            id = View.generateViewId()
        }

        // 開催日入力（非直接編集：DatePicker を開く）
        val editEventDate = EditText(this).apply {
            hint = "開催日 (YYYY-MM-DD)"
            setText(initialDate)
            isFocusable = false
            isClickable = true
            layoutParams = lp
            id = View.generateViewId()
            setOnClickListener {
                // 現在の値をベースにカレンダー表示
                val now = Calendar.getInstance()
                val parts = text.toString().trim().takeIf { it.isNotEmpty() }?.let { s ->
                    try {
                        if (s.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                            val p = s.split("-")
                            Triple(p[0].toInt(), p[1].toInt()-1, p[2].toInt())
                        } else if (s.matches(Regex("\\d{8}"))) {
                            Triple(s.substring(0,4).toInt(), s.substring(4,6).toInt()-1, s.substring(6,8).toInt())
                        } else null
                    } catch (_: Exception) { null }
                }
                val y = parts?.first ?: now.get(Calendar.YEAR)
                val m = parts?.second ?: now.get(Calendar.MONTH)
                val d = parts?.third ?: now.get(Calendar.DAY_OF_MONTH)
                android.app.DatePickerDialog(this@CameraActivity, { _, yy, mm, dd ->
                    val ys = String.format(Locale.getDefault(), "%04d-%02d-%02d", yy, mm+1, dd)
                    this@apply.setText(ys)
                 }, y, m, d).show()
            }
        }

        // ルートに追加（既存レイアウトの末尾に縦追加）
        (dialogView as? LinearLayout)?.addView(editTournamentName)
        (dialogView as? LinearLayout)?.addView(editEventDate)
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
                    putString("tournamentType", tournamentType)
                    // 保存: 大会名 / 開催日（ダイアログの動的フィールドがあれば反映）
                    try {
                        val nameText = dialogView.findViewById<EditText>(editTournamentName.id)?.text?.toString()?.trim()
                        val dateText = dialogView.findViewById<EditText>(editEventDate.id)?.text?.toString()?.trim()
                        if (!nameText.isNullOrBlank()) putString("tournamentName", nameText)
                        if (!dateText.isNullOrBlank()) putString("eventDate", dateText)
                    } catch (_: Exception) { /* ignore */ }
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

    // ヘルパー: Downloads にコピー（API29+ は MediaStore、旧API は直接コピー）
    private fun copyToDownloads(src: File): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                val destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, src.name)
                src.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            Log.e("EXPORT", "Downloadコピー失敗: ${src.name}", e)
            null
        }
    }

    // ヘルパー: CSV を共有
    private fun shareCsvFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "com.example.resultreader.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "CSVを共有"))
    }

    // エントリNo 手入力ダイアログ
    private fun showEntryNoEditDialog() {
        val currentNo = resultText.text.toString().replace(Regex("[^0-9]"), "")
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(2)) // 1〜99想定
            setText(currentNo)
            hint = "1〜99"
            setSelection(text?.length ?: 0)
        }

        AlertDialog.Builder(this)
            .setTitle("エントリー番号を入力")
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                val no = et.text.toString().toIntOrNull()
                if (no == null || no !in 1..99) {
                    Toast.makeText(this, "1〜99の数字を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // 表示＆手入力フラグ（黄色）
                resultText.text = "No: $no"
                resultText.setBackgroundColor(Color.parseColor("#FFE599"))

                // 登録確認メッセージ
                val entry = entryMap[no]
                if (entry != null) {
                    val (name, clazz) = entry
                    Toast.makeText(this, "✅ $name さん [$clazz] を選択", Toast.LENGTH_SHORT).show()
                    currentRowClass = clazz
                    tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession / ${currentRowClass ?: "-"}"
                } else {
                    Toast.makeText(this, "⚠️ EntryNo=$no は未登録です（保存時は拒否されます）", Toast.LENGTH_LONG).show()
                    currentRowClass = null
                }

                // C案: OCRで直近に番号が読めていたかどうかで分岐
                if (!lastOcrHadEntry) {
                    // OCRで番号未認識 → 確認ダイアログを出してからスコア解析を実行
                    AlertDialog.Builder(this)
                        .setTitle("スコア解析しますか？")
                        .setMessage("入力したエントリー番号でスコア解析を実行します。")
                        .setPositiveButton("実行") { _, _ ->
                            captureScoreOnlyMultiple()
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                } else {
                    // OCRで番号が読めていたケース
                    // 基本は既にスコア解析済みだが、万一未解析なら解析を開始
                    if (!hasScoreResult) {
                        captureScoreOnlyMultiple()
                    }
                    // 解析済みなら何もしない（再解析不要）
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // 手入力確定時にスコアのみを撮影・解析してUI更新する（既存のOCR->スコア読み取りの挙動に合わせる）
    private fun captureScoreOnlyMultiple() {
        // スコア単体読取を開始するため、解析未済にリセット
        hasScoreResult = false
        val currentImageCapture = imageCapture
        if (currentImageCapture == null) {
            // カメラ未起動なら起動→少し待ってから開始
            startCamera()
            isCameraReady = true
            isManualCameraControl = false
            Handler(Looper.getMainLooper()).postDelayed({ captureScoreOnlyMultiple() }, 300)
            return
        }

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
                    Toast.makeText(this, "⚠️ 判定一致せず：手動確認してください", Toast.LENGTH_LONG).show()
                    // エラー時はチェック音を鳴らす
                    playJudgeSound(false)
                    confirmButton.visibility = View.VISIBLE
                }

                // 自動モード時のみ停止（既存と同等）
                if (!isManualCameraControl) {
                    val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                    cameraProvider.unbindAll()
                    camera = null
                    imageCapture = null
                    isCameraReady = false

                    previewView.visibility = View.GONE
                    previewView.alpha = 0f
                    previewView.setBackgroundColor(Color.BLACK)
                    guideOverlay.visibility = View.GONE
                    findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.BLACK)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.d("CAMERA", "📴 手入力EntryNoからのスコア読取後に自動停止")
                }
                return
            }

            val photoFile = File.createTempFile("score_only_$count", ".jpg", cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            currentImageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                            val rotated = rotateBitmap(bitmap, 90f)

                            // pendingSaveBitmap を設定（保存時に使うため）
                            pendingSaveBitmap = rotated

                            // ▼ recognizeText() 内と同じROI計算をそのまま使用
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
                                rotated,
                                (bx + roiOffsetX).coerceIn(0, rotated.width - bw),
                                (by + roiOffsetY).coerceIn(0, rotated.height - bh),
                                bw,
                                bh
                            )
                            val scoreBitmap = rawScoreBitmap.copy(Bitmap.Config.ARGB_8888, true)

                            // デバッググリッド（既存と同等）
                            showDebugScoreOnPreview(scoreBitmap)

                            val result = ScoreAnalyzer.analyze(scoreBitmap)
                            scorePreview.setImageBitmap(scoreBitmap)
                            scorePreview.visibility = View.VISIBLE
                            result?.let { results.add(it) }
                        } catch (e: Exception) {
                            Log.e("SCORE_ONLY", "スコア抽出失敗", e)
                        } finally {
                            takeNext(count + 1)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        Toast.makeText(applicationContext, "撮影エラー", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // UI初期化（既存の startOcrCapture と揃える）
        resultText.text = (resultText.text.takeIf { it.toString().contains(Regex("\\d")) } ?: "No: -")
        guideOverlay.setDetected("red")
        confirmButton.visibility = View.GONE
        scorePreview.visibility = View.GONE

        takeNext(0)
    }

    // クラス編集ダイアログ（Prefs の大会種別に応じた選択肢）
    private fun showClassPickerDialog() {
        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val tType = prefs.getString("tournamentType", "beginner") ?: "beginner"
        val options = when (tType) {
            "championship" -> arrayOf("IA", "IB", "NA", "NB")
            else -> arrayOf("オープン", "ビギナー")
        }

        val currentIndex = options.indexOf(currentRowClass).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("クラスを選択")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selected = options[which]
                currentRowClass = selected
                // UI 表示を更新
                tournamentInfoText.text = "${selectedPattern.patternCode} / $currentSession / ${currentRowClass ?: "-"}"
                Toast.makeText(this, "クラスを $selected に変更しました", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
    // 認識結果を安全に初期化（UIのみ）
    private fun clearRecognitionUi() {
        // スコアラベルを空に＆背景リセット
        scoreLabelViews.forEach { label ->
            label.text = ""
            label.setBackgroundResource(R.drawable.bg_score_blank)
        }

        // 合計表示クリア
        findViewById<TextView>(R.id.scorePointText).text = "G:　-"
        findViewById<TextView>(R.id.scoreCleanText).text = "C:　-"

        // エントリー番号表示クリア
        resultText.text = "No: -"
        resultText.setBackgroundColor(Color.TRANSPARENT)

        // プレビュー画像クリア
        scorePreview.setImageDrawable(null)
        scorePreview.visibility = View.GONE

        // 状態フラグと一時画像をクリア
        hasScoreResult = false
        lastOcrHadEntry = false
        pendingSaveBitmap = null

        // 枠は待機（赤）に戻す & 保存ボタンは隠す
        guideOverlay.setDetected("red")
        confirmButton.visibility = View.GONE
    }


}
