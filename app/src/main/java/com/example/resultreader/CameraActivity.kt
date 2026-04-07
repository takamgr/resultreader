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
import android.view.ViewGroup




class CameraActivity : AppCompatActivity() {

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
    private lateinit var soundManager: SoundManager
    private lateinit var resultChecker: ResultChecker
    private lateinit var csvFileManager: CsvFileManager
    private lateinit var saveManager: SaveManager
    private lateinit var cameraManager: CameraManager
    private lateinit var tournamentManager: TournamentManager
    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var scoreManager: ScoreManager
    private val entryListPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                tournamentManager.copyCsvToAppStorage(uri)
            } else {
                Toast.makeText(this, "ファイルが選択されませんでした", Toast.LENGTH_SHORT).show()
            }
        }














    // 画像保存を無効化（今後は保存しない）
    private fun saveImage(bitmap: Bitmap) {
        // スコア画像の保存は行いません（デバッグ用途の保存機能は廃止）
        Log.d("SaveImage", "スコア画像保存は現在無効です")
    }












    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // まずレイアウトを先に読み込む！
        setContentView(R.layout.activity_camera)

        soundManager = SoundManager(this)
        resultChecker = ResultChecker(this)
        csvFileManager = CsvFileManager(this)

        guideToggleButton = findViewById(R.id.guideToggleButton)  // ← これ忘れずに！
        entryMap = CsvUtils.loadEntryMapFromCsv(this)

        // 🔹撮影準備ボタン長押し → 保存ボタン長押し（DNF/DNSダイアログ）と同じ動作
        guideToggleButton.setOnLongClickListener {
            confirmButton.performLongClick()
            true
        }

        guideToggleButton.setOnClickListener {
            if (!isCameraReady) {
                cameraManager.startCamera()
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
                scoreManager.showScoreInputDialog(label)
            }
        }

        resultText = findViewById(R.id.resultText)
        tournamentInfoText = findViewById(R.id.tournamentInfoText)
        // 追加: EntryNo を手入力で修正できるようにする
        resultText.setOnClickListener { tournamentManager.showEntryNoEditDialog(entryMap, lastOcrHadEntry, hasScoreResult) }
        resultText.setOnLongClickListener { tournamentManager.showEntryNoEditDialog(entryMap, lastOcrHadEntry, hasScoreResult); true }
        // 追加: tournamentInfoText を長押し/タップでクラス編集ダイアログを開く
        tournamentInfoText.setOnClickListener { tournamentManager.showClassPickerDialog() }
        tournamentInfoText.setOnLongClickListener { tournamentManager.showClassPickerDialog(); true }
        prepareButton = findViewById(R.id.prepareButton)
        confirmButton = findViewById(R.id.confirmButton)
        flashToggleButton = findViewById(R.id.flashToggleButton)
        guideOverlay = findViewById(R.id.guideOverlay)
        scorePreview = findViewById(R.id.scorePreview)
        previewView = findViewById(R.id.previewView)

        scoreManager = ScoreManager(
            context = this,
            scoreLabelViews = scoreLabelViews,
            resultText = resultText,
            scorePreview = scorePreview,
            guideOverlay = guideOverlay,
            confirmButton = confirmButton,
            getSelectedPattern = { selectedPattern },
            onPlayJudgeSound = { ok -> soundManager.playJudgeSound(ok) },
            onSetHasScoreResult = { hasScoreResult = it },
            onSetLastOcrHadEntry = { lastOcrHadEntry = it },
            onSetPendingBitmap = { pendingSaveBitmap = it }
        )

        cameraManager = CameraManager(
            context = this,
            previewView = previewView,
            guideOverlay = guideOverlay,
            getIsOcrRunning = { isOcrRunning },
            onCameraStarted = { ic, cam -> imageCapture = ic; camera = cam; isCameraReady = true },
            onCameraCleared = { camera = null }
        )

        tournamentManager = TournamentManager(
            context = this,
            resultText = resultText,
            tournamentInfoText = tournamentInfoText,
            resultChecker = resultChecker,
            getSelectedPattern = { selectedPattern },
            getCurrentSession = { currentSession },
            getCurrentRowClass = { currentRowClass },
            onPatternChanged = { selectedPattern = it },
            onSessionChanged = { currentSession = it },
            onEntryMapUpdated = { entryMap = it },
            onClassChanged = { currentRowClass = it },
            onCaptureScoreRequested = { ocrProcessor.captureScoreOnlyMultiple() }
        )

        ocrProcessor = OcrProcessor(
            context = this,
            guideOverlay = guideOverlay,
            confirmButton = confirmButton,
            scorePreview = scorePreview,
            resultText = resultText,
            tournamentInfoText = tournamentInfoText,
            previewView = previewView,
            ocrRectPx = OCR_RECT_PX,
            getImageCapture = { imageCapture },
            getIsManualCameraControl = { isManualCameraControl },
            getEntryMap = { entryMap },
            getSelectedPattern = { selectedPattern },
            getCurrentSession = { currentSession },
            getCurrentRowClass = { currentRowClass },
            onUpdateScoreUi = { scoreManager.updateScoreUi(it) },
            onPlayErrorSound = { soundManager.playJudgeSound(false) },
            onCameraStop = { camera = null; imageCapture = null; isCameraReady = false },
            onStartCamera = { cameraManager.startCamera(); isCameraReady = true; isManualCameraControl = false },
            onSetHasScoreResult = { hasScoreResult = it },
            onSetLastOcrHadEntry = { lastOcrHadEntry = it },
            onSetPendingBitmap = { pendingSaveBitmap = it },
            onSetCurrentRowClass = { currentRowClass = it }
        )

        prepareButton.setOnLongClickListener {
            confirmButton.performLongClick()
            true
        }

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
                PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 20)
            }
        }

        val idBtnExportPdf = resources.getIdentifier("btnExportPdf", "id", packageName)
        if (idBtnExportPdf != 0) {
            findViewById<ImageButton?>(idBtnExportPdf)?.setOnClickListener {
                PrintableExporter.exportPrintablePdfStyledSplitByClass(this, selectedPattern, rowsPerPage = 20)
            }
        }

        // その後に SharedPreferences → 設定ダイアログ呼び出し！
        val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastSetDate = prefs.getString("lastSetDate", null)

        if (today != lastSetDate) {
            tournamentManager.showInitialTournamentSettingDialog(entryMap) {
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
            csvFileManager.showCsvListDialog()
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
            tournamentManager.showInitialTournamentSettingDialog(entryMap) {}
        }


        // 撮影準備
        prepareButton.setOnClickListener {
            if (!isCameraReady) {
                cameraManager.startCamera()
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
            ocrProcessor.captureAndAnalyzeMultiple()
        }

        // 保存処理
        saveManager = SaveManager(
            context = this,
            scoreLabelViews = scoreLabelViews,
            resultText = resultText,
            guideOverlay = guideOverlay,
            confirmButton = confirmButton,
            onPlayErrorSound = { soundManager.playJudgeSound(false) },
            onSaveImage = { saveImage(it) },
            onClearRecognitionUi = { scoreManager.clearRecognitionUi() },
            onClassCleared = { currentRowClass = null },
            onTournamentInfoUpdate = { tournamentManager.updateTournamentInfoText() }
        )
        confirmButton.setOnClickListener {
            saveManager.requestSaveWithStatus(
                SaveManager.SaveStatus.NORMAL,
                selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap
            )
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
                        0 -> saveManager.requestSaveWithStatus(
                            SaveManager.SaveStatus.DNF,
                            selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap
                        )
                        1 -> saveManager.requestSaveWithStatus(
                            SaveManager.SaveStatus.DNS,
                            selectedPattern, currentSession, pendingSaveBitmap, currentRowClass, entryMap
                        )
                        else -> dialog.dismiss()
                    }
                }
                .show()
            true
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
                        tournamentManager.showInitialTournamentSettingDialog(entryMap) {}
                    }
                    .setNegativeButton("いいえ（キャンセル）", null)
                    .show()
            } else {
                // 未読み込み時はそのまま変更可能
                tournamentManager.showInitialTournamentSettingDialog(entryMap) {}
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

        cameraManager.resetInactivityTimer()  // ← 初期化時にも起動しておく

        entryMap = tournamentManager.loadEntryMap()

        // --- 追記: 下部の出力ボタンをランタイムで非表示にする（XMLは変更しない） ---
        listOf("exportHtmlButton", "exportS1Button", "printButton").forEach { idName ->
            val id = resources.getIdentifier(idName, "id", packageName)
            if (id != 0) {
                findViewById<View?>(id)?.visibility = View.GONE
            }
        }

        // --- 追記: 右上のCSVボタン候補を探索し、長押しで出力メニューを表示 ---
        val candidateIds = listOf("openCsvImageButton", "openCsvButton", "csvOpenButton", "resultCsvButton")
        val anchorView = candidateIds
            .map { resources.getIdentifier(it, "id", packageName) }
            .mapNotNull { findViewById<View?>(it) }
            .firstOrNull()

        anchorView?.setOnLongClickListener {
            csvFileManager.showExportPopupMenu(it, selectedPattern)
            true
        }
    }





    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        cameraManager.resetInactivityTimer()
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
                cameraManager.startCamera()
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
        soundManager.release()
    }














    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10

    }



    // CameraActivity.kt にまるっと追加するコード
// 大会設定のダイアログ（起動時表示 + 選択保持 + UI反映）







    // エントリNo 手入力ダイアログ

    // 手入力確定時にスコアのみを撮影・解析してUI更新する（既存のOCR->スコア読み取りの挙動に合わせる）

    // クラス編集ダイアログ（Prefs の大会種別に応じた選択肢）

    // 認識結果を安全に初期化（UIのみ）


}
