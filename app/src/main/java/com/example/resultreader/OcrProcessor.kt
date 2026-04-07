package com.example.resultreader

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

class OcrProcessor(
    private val context: AppCompatActivity,
    private val guideOverlay: GuideOverlayView,
    private val confirmButton: Button,
    private val scorePreview: ImageView,
    private val resultText: TextView,
    private val tournamentInfoText: TextView,
    private val previewView: androidx.camera.view.PreviewView,
    private val ocrRectPx: Rect,
    private val getImageCapture: () -> ImageCapture?,
    private val getIsManualCameraControl: () -> Boolean,
    private val getEntryMap: () -> Map<Int, Pair<String, String>>,
    private val getSelectedPattern: () -> TournamentPattern,
    private val getCurrentSession: () -> String,
    private val getCurrentRowClass: () -> String?,
    private val onUpdateScoreUi: (ScoreAnalyzer.ScoreResult) -> Unit,
    private val onPlayErrorSound: () -> Unit,
    private val onCameraStop: () -> Unit,
    private val onStartCamera: () -> Unit,
    private val onSetHasScoreResult: (Boolean) -> Unit,
    private val onSetLastOcrHadEntry: (Boolean) -> Unit,
    private val onSetPendingBitmap: (Bitmap?) -> Unit,
    private val onSetCurrentRowClass: (String?) -> Unit
) {

    // カメラ自動停止（captureAndAnalyzeMultiple / captureScoreOnlyMultiple 共通）
    private fun stopCameraIfAutoMode() {
        if (getIsManualCameraControl()) return
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (e: Exception) {
            Log.e("CAMERA", "カメラ停止失敗", e)
        }
        onCameraStop()  // camera=null, imageCapture=null, isCameraReady=false

        // 🔥 プレビューを完全OFF
        previewView.visibility = View.GONE
        previewView.alpha = 0f
        previewView.setBackgroundColor(Color.BLACK)

        guideOverlay.visibility = View.GONE
        context.findViewById<android.widget.FrameLayout>(R.id.previewContainer)
            .setBackgroundColor(Color.BLACK)

        context.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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
                    onSetLastOcrHadEntry(true)
                    resultText.text = "No: $entryNumber"
                    onSetPendingBitmap(fullImage)

                    if (isWarningId) {
                        guideOverlay.setDetected("yellow")
                        Toast.makeText(context, "⚠️ 誤認注意番号", Toast.LENGTH_SHORT).show()
                    }

                    // 🔥 登録確認とトースト表示（ここが今回の追加）
                    val entry = getEntryMap()[entryNumber]
                    if (entry != null) {
                        val (name, clazz) = entry
                        Toast.makeText(context, "✅ $name さん [$clazz]", Toast.LENGTH_SHORT).show()
                        // 現在行のクラスをセットして UI に反映
                        onSetCurrentRowClass(clazz)
                        // tournamentInfoText にクラスを追加表示（既存表示を壊さない）。ここではローカルprefsを使う
                        val prefsLocal = context.getSharedPreferences("ResultReaderPrefs", AppCompatActivity.MODE_PRIVATE)
                        val typeLabel = if (prefsLocal.getString("tournamentType", "unknown") == "championship") "選手権" else "ビギナー"
                        tournamentInfoText.text = "${getSelectedPattern().patternCode} / ${getCurrentSession()} / $typeLabel / ${getCurrentRowClass() ?: "-"}"
                    } else {
                        Toast.makeText(context, "⚠️ EntryNo=$entryNumber は未登録です", Toast.LENGTH_LONG).show()
                        onSetCurrentRowClass(null)
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
                    onSetLastOcrHadEntry(false)
                    guideOverlay.setDetected("red")
                    Toast.makeText(context, "認識エラー: $rawText", Toast.LENGTH_SHORT).show()
                    callback(null)
                }
            }
            .addOnFailureListener {
                onSetLastOcrHadEntry(false)
                guideOverlay.setDetected("red")
                Toast.makeText(context, "OCR失敗", Toast.LENGTH_SHORT).show()
                callback(null)
            }
    }

    fun captureAndAnalyzeMultiple() {
        val currentImageCapture = getImageCapture() ?: return
        val results = mutableListOf<ScoreAnalyzer.ScoreResult>()

        fun takeNext(count: Int) {
            if (count >= 3) {
                val grouped = results.groupBy { it.sectionScores }
                val majority = grouped.maxByOrNull { it.value.size }?.value?.firstOrNull()

                if (majority != null) {
                    onUpdateScoreUi(majority)
                    guideOverlay.setDetected("green")
                    confirmButton.visibility = View.VISIBLE
                } else {
                    guideOverlay.setDetected("red")
                    Toast.makeText(
                        context,
                        "⚠️ 判定一致せず：手動確認して修正してください",
                        Toast.LENGTH_LONG
                    ).show()
                    // エラー時はチェック音を鳴らす
                    onPlayErrorSound()
                    confirmButton.visibility = View.VISIBLE
                }

                // 🔥 自動モード時のみカメラ完全停止！
                stopCameraIfAutoMode()
                Log.d("CAMERA", "📴 OCR完了後にカメラ自動停止")

                return
            }

            val photoFile = File.createTempFile("ocr_temp_$count", ".jpg", context.cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            currentImageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                        val rotated = rotateBitmap(bitmap, 90f)
                        val cropped = Bitmap.createBitmap(
                            rotated,
                            ocrRectPx.left,
                            ocrRectPx.top,
                            ocrRectPx.width(),
                            ocrRectPx.height()
                        )

                        recognizeText(cropped, rotated) { result ->
                            result?.let { results.add(it) }
                            takeNext(count + 1)
                        }
                    }

                    override fun onError(e: ImageCaptureException) {
                        Toast.makeText(context.applicationContext, "撮影エラー", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        takeNext(0)
    }

    fun captureScoreOnlyMultiple() {
        // スコア単体読取を開始するため、解析未済にリセット
        onSetHasScoreResult(false)
        val currentImageCapture = getImageCapture()
        if (currentImageCapture == null) {
            // カメラ未起動なら起動→少し待ってから開始
            onStartCamera()
            Handler(Looper.getMainLooper()).postDelayed({ captureScoreOnlyMultiple() }, 300)
            return
        }

        val results = mutableListOf<ScoreAnalyzer.ScoreResult>()

        fun takeNext(count: Int) {
            if (count >= 3) {
                val grouped = results.groupBy { it.sectionScores }
                val majority = grouped.maxByOrNull { it.value.size }?.value?.firstOrNull()

                if (majority != null) {
                    onUpdateScoreUi(majority)
                    guideOverlay.setDetected("green")
                    confirmButton.visibility = View.VISIBLE
                } else {
                    guideOverlay.setDetected("red")
                    Toast.makeText(context, "⚠️ 判定一致せず：手動確認してください", Toast.LENGTH_LONG).show()
                    // エラー時はチェック音を鳴らす
                    onPlayErrorSound()
                    confirmButton.visibility = View.VISIBLE
                }

                // 自動モード時のみ停止（既存と同等）
                stopCameraIfAutoMode()
                Log.d("CAMERA", "📴 手入力EntryNoからのスコア読取後に自動停止")
                return
            }

            val photoFile = File.createTempFile("score_only_$count", ".jpg", context.cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            currentImageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                            val rotated = rotateBitmap(bitmap, 90f)

                            // pendingSaveBitmap を設定（保存時に使うため）
                            onSetPendingBitmap(rotated)

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
                        Toast.makeText(context.applicationContext, "撮影エラー", Toast.LENGTH_SHORT).show()
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
}
