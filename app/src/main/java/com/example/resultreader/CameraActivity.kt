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

    internal lateinit var autoModeText: TextView
    internal var previewUseCase: Preview? = null
    internal var isPreviewVisibleInAutoMode = true
    internal var isAutoModeEnabled: Boolean = false
    internal var pendingManualCapture: Boolean = false
    internal var startedCameraForManualShot: Boolean = false
    internal var ocrSoundPlayed = false
    internal var imageAnalysis: ImageAnalysis? = null
    internal var autoCaptureArmed: Boolean = true
    internal var stableFrameCount: Int = 0
    internal var lastAvgLuma: Float = -1f
    internal var lastWhiteRatio: Float = -1f
    internal var autoTempPreviewShown: Boolean = false
    internal var autoCardTickCount: Int = 0
    internal val CARD_RECT = RectF(0.125f, 0.35f, 0.875f, 0.85f)
    internal val AUTO_CENTER_WIDTH_RATIO = 0.4f
    internal val AUTO_CENTER_HEIGHT_RATIO = 0.4f

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
    internal lateinit var entryProgressText: TextView




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
                startCamera()
                return@setOnClickListener
            }
            if (isAutoModeEnabled) {
                if (isPreviewVisibleInAutoMode) {
                    hidePreviewOnly()
                    Toast.makeText(this, "🔦 Preview OFF（自動モード）", Toast.LENGTH_SHORT).show()
                } else {
                    showPreviewOnly()
                    Toast.makeText(this, "🔦 Preview ON（自動モード）", Toast.LENGTH_SHORT).show()
                }
            } else {
                stopCameraAndPreview()
                Toast.makeText(this, "📴 カメラOFF（手動）", Toast.LENGTH_SHORT).show()
            }
            window.decorView.setBackgroundColor(Color.BLACK)
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
        entryProgressText = findViewById(R.id.entryProgressText)
        autoModeText = findViewById(R.id.autoModeText)
        updateAutoModeText()
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

        // 検出状態変化 → autoModeText・scoreLabels の背景色を連動させる
        val scoreLabelsContainer = findViewById<android.widget.LinearLayout>(R.id.scoreLabels)
        guideOverlay.onStatusChanged = { status ->
            val autoModeBg = when (status) {
                "green"  -> android.graphics.Color.parseColor("#CC228822")
                "yellow" -> android.graphics.Color.parseColor("#CCAA8800")
                else     -> android.graphics.Color.parseColor("#CCCC2222")
            }
            // scoreLabels: 透過なし・ベタ塗り
            val scoreBg = when (status) {
                "green"  -> android.graphics.Color.parseColor("#4CAF50")
                "yellow" -> android.graphics.Color.parseColor("#FFC107")
                else     -> android.graphics.Color.parseColor("#F44336")
            }
            autoModeText.setBackgroundColor(autoModeBg)
            autoModeText.setTextColor(android.graphics.Color.WHITE)
            scoreLabelsContainer.setBackgroundColor(scoreBg)
        }

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
            getBaseX = { loadBaseX() },
            getBaseY = { loadBaseY() },
            getBaseWidth = { loadBaseWidth() },
            getRotationDegrees = { loadRotation() },
            getImageCapture = { imageCapture },
            getIsManualCameraControl = { isManualCameraControl || isAutoModeEnabled },
            getEntryMap = { entryMap },
            getSelectedPattern = { selectedPattern },
            getCurrentSession = { currentSession },
            getCurrentRowClass = { currentRowClass },
            onUpdateScoreUi = { scoreManager.updateScoreUi(it) },
            onPlayErrorSound = { soundManager.playJudgeSound(false) },
            onCameraStop = { camera = null; imageCapture = null; imageAnalysis = null; isCameraReady = false },
            onStartCamera = { startCamera() },
            onSetHasScoreResult = { hasScoreResult = it },
            onSetLastOcrHadEntry = { lastOcrHadEntry = it },
            onSetPendingBitmap = { pendingSaveBitmap = it },
            onSetCurrentRowClass = { currentRowClass = it }
        )

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
                updateEntryProgressDisplay()
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
                updateEntryProgressDisplay()

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
            tournamentManager.showInitialTournamentSettingDialog(entryMap) {
                updateEntryProgressDisplay()
            }
        }


        // 撮影準備
        prepareButton.setOnClickListener {
            isAutoModeEnabled = false
            isManualCameraControl = true
            if (!isCameraReady) {
                pendingManualCapture = true
                startedCameraForManualShot = true
                startCamera()
            } else {
                startedCameraForManualShot = false
                startOcrCapture()
            }
        }

        prepareButton.setOnLongClickListener {
            isAutoModeEnabled = !isAutoModeEnabled
            if (isAutoModeEnabled) {
                isManualCameraControl = false
                autoCaptureArmed = true
                stableFrameCount = 0
                lastAvgLuma = -1f
                lastWhiteRatio = -1f
                startCamera()
                Toast.makeText(this, "🤖 自動モードON：カードを赤枠に入れると連続で読み取ります", Toast.LENGTH_SHORT).show()
            } else {
                val cameraProvider = ProcessCameraProvider.getInstance(this).get()
                cameraProvider.unbindAll()
                camera = null
                imageCapture = null
                imageAnalysis = null
                isCameraReady = false
                previewView.visibility = View.GONE
                previewView.alpha = 0f
                previewView.setBackgroundColor(Color.BLACK)
                guideOverlay.visibility = View.GONE
                findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.BLACK)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Toast.makeText(this, "🛑 自動モードOFF", Toast.LENGTH_SHORT).show()
            }
            updateAutoModeText()
            true
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
            onClearRecognitionUi = {
                scoreManager.clearRecognitionUi()
                if (isAutoModeEnabled) {
                    autoCaptureArmed = true
                    stableFrameCount = 0
                    lastAvgLuma = -1f
                    lastWhiteRatio = -1f
                }
            },
            onClassCleared = { currentRowClass = null },
            onTournamentInfoUpdate = { tournamentManager.updateTournamentInfoText(); updateEntryProgressDisplay() }
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

        // ↓ボタンは常時表示
        val entryListImportButton = findViewById<ImageButton>(R.id.entryListImportButton)
        entryListImportButton.setOnClickListener {
            entryListPickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv"))
        }
        // 長押し：読み込み済みフラグをリセットして再読み込みを可能にする
        entryListImportButton.setOnLongClickListener {
            val prefs = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            prefs.edit().putBoolean("entrylist_loaded_once", false).apply()
            Toast.makeText(this, "🔓 再読み込みが有効になりました。↓ボタンでCSVを選択してください", Toast.LENGTH_SHORT).show()
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




// ← 長押しでROI設定画面を開く
        tournamentSettingButton.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("ROI設定")
                .setMessage("ROI調整画面を開きますか？\n（カメラのOCR読み取り位置を調整します）")
                .setPositiveButton("開く") { _, _ ->
                    startActivity(android.content.Intent(this, RoiSettingActivity::class.java))
                }
                .setNegativeButton("キャンセル", null)
                .show()
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
        ocrProcessor.captureAndAnalyzeMultiple()
    }







    private fun updateEntryProgressDisplay() {
        if (!::entryProgressText.isInitialized) return

        val p = EntryProgressCounter.calc(
            context = this,
            pattern = selectedPattern,
            entryMap = entryMap
        )

        entryProgressText.text =
            "AM ${p.amDone}/${p.totalEntries}（残${p.amRemain}）  PM ${p.pmDone}/${p.totalEntries}（残${p.pmRemain}）"
    }

    /** 現在アクティブな機種名を取得。未設定なら空文字 */
    private fun activeDevice(): String =
        getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
            .getString("roi_active_device", "") ?: ""

    private fun loadBaseX(): Int {
        val p = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val dev = activeDevice()
        return if (dev.isBlank()) p.getInt("base_x", 436)
               else p.getInt("roi_${dev}_base_x", 436)
    }

    private fun loadBaseY(): Int {
        val p = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val dev = activeDevice()
        return if (dev.isBlank()) p.getInt("base_y", 750)
               else p.getInt("roi_${dev}_base_y", 750)
    }

    private fun loadBaseWidth(): Int {
        val p = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val dev = activeDevice()
        return if (dev.isBlank()) p.getInt("base_width", 2033)
               else p.getInt("roi_${dev}_base_width", 2033)
    }

    private fun loadRotation(): Float {
        val p = getSharedPreferences("ResultReaderPrefs", MODE_PRIVATE)
        val dev = activeDevice()
        return if (dev.isBlank()) p.getInt("roi_rotation", 90).toFloat()
               else p.getInt("roi_${dev}_rotation", 90).toFloat()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        // ROI設定画面から戻ったとき、最新の base_x/base_y/base_width/rotation を反映する。
        // OcrProcessor の getBaseX 等はラムダなので次の撮影時に自動で再読み込みされるが、
        // ここで明示的に再初期化することで確実に最新値を使う。
        ocrProcessor = OcrProcessor(
            context = this,
            guideOverlay = guideOverlay,
            confirmButton = confirmButton,
            scorePreview = scorePreview,
            resultText = resultText,
            tournamentInfoText = tournamentInfoText,
            previewView = previewView,
            getBaseX = { loadBaseX() },
            getBaseY = { loadBaseY() },
            getBaseWidth = { loadBaseWidth() },
            getRotationDegrees = { loadRotation() },
            getImageCapture = { imageCapture },
            getIsManualCameraControl = { isManualCameraControl || isAutoModeEnabled },
            getEntryMap = { entryMap },
            getSelectedPattern = { selectedPattern },
            getCurrentSession = { currentSession },
            getCurrentRowClass = { currentRowClass },
            onUpdateScoreUi = { scoreManager.updateScoreUi(it) },
            onPlayErrorSound = { soundManager.playJudgeSound(false) },
            onCameraStop = { camera = null; imageCapture = null; imageAnalysis = null; isCameraReady = false },
            onStartCamera = { startCamera() },
            onSetHasScoreResult = { hasScoreResult = it },
            onSetLastOcrHadEntry = { lastOcrHadEntry = it },
            onSetPendingBitmap = { pendingSaveBitmap = it },
            onSetCurrentRowClass = { currentRowClass = it }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        soundManager.release()
    }














    // ===== AUTO/MANUAL モード関連関数 =====

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Preview 用意
            val preview = Preview.Builder().build()
            previewUseCase = preview

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                if (isAutoModeEnabled) {
                    // 🤖 自動モード：ImageAnalysis + Preview（表示は後でON/OFF）

                    imageAnalysis = buildAutoCardAnalysisUseCase()

                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )

                    // 自動モードで Preview をどうスタートさせるか
                    if (isPreviewVisibleInAutoMode) {
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        previewView.visibility = View.VISIBLE
                    } else {
                        preview.setSurfaceProvider(null)
                        previewView.visibility = View.GONE
                    }

                    Toast.makeText(this, "🤖 自動モード（カメラON）", Toast.LENGTH_SHORT).show()

                } else {
                    // 🧑 手動モード：Preview + ImageCapture のみ
                    imageAnalysis = null

                    camera = cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageCapture
                    )

                    // 手動モードは常にPreview表示
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    previewView.visibility = View.VISIBLE

                    Toast.makeText(this, "📷 手動モード（カメラON）", Toast.LENGTH_SHORT).show()
                }

                previewView.alpha = 1f
                previewView.setBackgroundColor(Color.TRANSPARENT)
                guideOverlay.visibility = View.VISIBLE
                findViewById<FrameLayout>(R.id.previewContainer)
                    .setBackgroundColor(Color.TRANSPARENT)

                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                isCameraReady = true

                // 🔽 カメラ起動直後に、手動撮影が保留されていればここで実行
                if (isManualCameraControl && !isAutoModeEnabled && pendingManualCapture) {
                    pendingManualCapture = false
                    startOcrCapture()
                }

            } catch (exc: Exception) {
                Log.e("CAMERA", "❌ カメラ起動失敗", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildAutoCardAnalysisUseCase(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { analysis ->

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    autoCardTickCount++
                    if (autoCardTickCount % 10 == 0) {
                        Log.d("AutoCard", "tick armed=$autoCaptureArmed ocr=$isOcrRunning result=$hasScoreResult suspended=false")
                    }


                    try {
                        // ---- GUARD（スリープは一旦無視する）----
                        if (isOcrRunning || hasScoreResult || !autoCaptureArmed) {
                            Log.d(
                                "AutoCard",
                                "GUARD ocr=$isOcrRunning result=$hasScoreResult armed=$autoCaptureArmed"
                            )
                            imageProxy.close()
                            return@setAnalyzer
                        }
// ---- ここまで ----



                        // アームされていないなら何もしない
                        if (!autoCaptureArmed) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        // 画面サイズ
                        val width = imageProxy.width
                        val height = imageProxy.height

                        // 1) まずは赤枠（カード全体）の画素座標を求める
                        val cardLeft = CARD_RECT.left * width
                        val cardRight = CARD_RECT.right * width
                        val cardTop = CARD_RECT.top * height
                        val cardBottom = CARD_RECT.bottom * height

                        val cardCenterX = (cardLeft + cardRight) / 2f
                        val cardCenterY = (cardTop + cardBottom) / 2f
                        val cardWidth = cardRight - cardLeft
                        val cardHeight = cardBottom - cardTop

                        // 2) その中の「中央ミニ領域」（中央40%×40%）をROIとして使う
                        val miniWidth = cardWidth * AUTO_CENTER_WIDTH_RATIO
                        val miniHeight = cardHeight * AUTO_CENTER_HEIGHT_RATIO

                        val roiLeftF = cardCenterX - miniWidth / 2f
                        val roiRightF = cardCenterX + miniWidth / 2f
                        val roiTopF = cardCenterY - miniHeight / 2f
                        val roiBottomF = cardCenterY + miniHeight / 2f

                        val roiLeft = roiLeftF.toInt().coerceIn(0, width - 1)
                        val roiRight = roiRightF.toInt().coerceIn(roiLeft + 1, width)
                        val roiTop = roiTopF.toInt().coerceIn(0, height - 1)
                        val roiBottom = roiBottomF.toInt().coerceIn(roiTop + 1, height)

                        // Y平面取得（明るさ情報）
                        val yPlane = imageProxy.planes[0]
                        val buffer = yPlane.buffer
                        val rowStride = yPlane.rowStride
                        val pixelStride = yPlane.pixelStride

                        buffer.rewind() // 念のため毎回先頭に戻す

                        var sumLuma = 0f
                        var whiteCount = 0
                        var sampleCount = 0

                        // すべてのピクセルを見ると重いので、適当に間引き
                        val stepX = 8
                        val stepY = 8
                        val whiteThreshold = 170 // ちょっと低めにしておく

                        for (y in roiTop until roiBottom step stepY) {
                            val rowOffset = y * rowStride
                            for (x in roiLeft until roiRight step stepX) {
                                val index = rowOffset + x * pixelStride
                                if (index >= buffer.limit()) continue

                                val yValue = buffer.get(index).toInt() and 0xFF
                                sumLuma += yValue
                                if (yValue >= whiteThreshold) whiteCount++
                                sampleCount++
                            }
                        }

                        if (sampleCount == 0) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val avgLuma = sumLuma / sampleCount
                        val whiteRatio = whiteCount.toFloat() / sampleCount

                        // avgLuma / whiteRatio を出した直後に追加する（ここが"今の状態"を見る場所）
                        if (autoCardTickCount % 10 == 0) { // 10フレームに1回で間引き
                            Log.d(
                                "AutoCard",
                                "RAW avg=$avgLuma white=$whiteRatio " +
                                        "stable=$stableFrameCount armed=$autoCaptureArmed " +
                                        "ocr=$isOcrRunning result=$hasScoreResult suspended=false"
                            )
                        }



                        // ---- 安定判定：AEの微振動でも溜まる方式（完成版・重複なし） ----

// 先に「カードがあるっぽい」条件
                        val isWhiteEnough  = whiteRatio >= 0.35f
                        val isBrightEnough = avgLuma    >=65f


// 安定判定の閾値（AEがフラフラしても通す）
                        val lumaThr  = 15f
                        val whiteThr = 0.35f

// 「白い状態が続いているなら」stable を溜める
                        if (isWhiteEnough && isBrightEnough) {


                            if (lastAvgLuma < 0f || lastWhiteRatio < 0f) {
                                // 初回は比較できないので 1 から開始
                                stableFrameCount = 1
                            } else {
                                val lumaDiff  = kotlin.math.abs(avgLuma - lastAvgLuma)
                                val whiteDiff = kotlin.math.abs(whiteRatio - lastWhiteRatio)

                                if (lumaDiff < lumaThr && whiteDiff < whiteThr) {
                                    stableFrameCount++
                                } else {
                                    // いきなり0に戻さず1に落とす（白い状態は維持してる前提）
                                    stableFrameCount = 1
                                }
                            }

                        } else {
                            // 白いカードが居ないなら完全リセット
                            stableFrameCount = 0
                        }


// 次フレーム比較用
                        lastAvgLuma = avgLuma
                        lastWhiteRatio = whiteRatio

// 最終トリガー条件（静止判定カウント
                        val isStableEnough = stableFrameCount >= 20

// ---- 静止白カード発火（ここだけ）----
                        if (isWhiteEnough && isBrightEnough && isStableEnough) {

                            Log.d(
                                "AutoCard",
                                "✅ 静止白カード判定：avg=$avgLuma white=$whiteRatio stable=$stableFrameCount"
                            )

                            // 同じカードで何度も発火しないようにアーム解除
                            autoCaptureArmed = false
                            stableFrameCount = 0
                            lastAvgLuma = -1f
                            lastWhiteRatio = -1f

                            runOnUiThread { startOcrCapture() }
                        }
// ---- ここまで ----



                    } catch (e: Exception) {
                        Log.e("AutoCard", "静止カード解析中にエラー", e)
                    } finally {
                        imageProxy.close()
                    }
                }
            }
    }

    // ===== PATCH: OCR完了後の復帰処理を共通化 =====
    private fun finishOcrAndRearm(reason: String) {
        isOcrRunning = false

        // 自動モード運用なら次カード待ちに戻す
        if (isAutoModeEnabled && !isManualCameraControl) {
            autoCaptureArmed = true
            stableFrameCount = 0
            lastAvgLuma = -1f
            lastWhiteRatio = -1f
        }

        Log.d("AutoCard", "FINISH_OCR($reason) ocr=$isOcrRunning armed=$autoCaptureArmed")
    }

    // カメラとプレビューを完全に停止する共通処理
    private fun stopCameraAndPreview() {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider.unbindAll()
        } catch (e: Exception) {
            Log.e("CAMERA", "カメラ停止失敗", e)
        }

        camera = null
        imageCapture = null
        imageAnalysis = null
        isCameraReady = false

        previewView.visibility = View.GONE
        previewView.alpha = 0f
        previewView.setBackgroundColor(Color.BLACK)
        guideOverlay.visibility = View.GONE
        findViewById<FrameLayout>(R.id.previewContainer)
            .setBackgroundColor(Color.BLACK)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // 🔦 Preview（画面表示）だけを OFF にする（自動モード専用）
    private fun hidePreviewOnly() {
        previewUseCase?.setSurfaceProvider(null)

        // ★GONEだと背面の白が見えたり、復帰で破綻しやすいので INVISIBLE 推奨
        previewView.visibility = View.INVISIBLE
        previewView.alpha = 0f
        previewView.setBackgroundColor(Color.BLACK)

        // ★コンテナも黒で固定（白背景対策）
        findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.BLACK)

        // 枠色（赤/黄/緑）は見えるように残す
        guideOverlay.visibility = View.VISIBLE

        isPreviewVisibleInAutoMode = false
    }

    // 🔦 Preview を ON（画面を復帰）
    private fun showPreviewOnly() {
        previewUseCase?.setSurfaceProvider(previewView.surfaceProvider)

        previewView.visibility = View.VISIBLE
        previewView.alpha = 1f
        previewView.setBackgroundColor(Color.TRANSPARENT)

        findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.TRANSPARENT)
        guideOverlay.visibility = View.VISIBLE

        isPreviewVisibleInAutoMode = true
    }

    private fun armAutoCaptureForNextCard(reason: String) {
        if (!isAutoModeEnabled) return

        // 結果表示を解除して次カード待ちへ
        hasScoreResult = false
        isOcrRunning = false  // 念のため（OCR終了後に呼ぶ想定）

        autoCaptureArmed = true
        stableFrameCount = 0
        lastAvgLuma = -1f
        lastWhiteRatio = -1f

        Log.d("AutoCard", "📷 次カード待ちに復帰：$reason (armed=$autoCaptureArmed result=$hasScoreResult)")
    }

    private fun updateAutoModeText() {
        if (!::autoModeText.isInitialized) return
        val label = if (isAutoModeEnabled) "AUTO" else "MANUAL"
        autoModeText.text = label
        autoModeText.setTextColor(android.graphics.Color.WHITE)
    }

    // ===== AUTO/MANUAL モード関連関数ここまで =====

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
