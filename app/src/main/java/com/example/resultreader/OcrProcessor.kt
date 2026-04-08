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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrProcessor(
    private val context: AppCompatActivity,
    private val guideOverlay: GuideOverlayView,
    private val confirmButton: Button,
    private val scorePreview: ImageView,
    private val resultText: TextView,
    private val tournamentInfoText: TextView,
    private val previewView: androidx.camera.view.PreviewView,
    private val getBaseX: () -> Int,
    private val getBaseY: () -> Int,
    private val getBaseWidth: () -> Int,
    private val getRotationDegrees: () -> Float,
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

        // 🔧 デバッグ用：スコアグリッド画像をDownloadsフォルダに保存
        saveDebugBitmapToDownloads(bitmap, "score_grid")
    }

    private fun saveDebugBitmapToDownloads(bitmap: Bitmap, prefix: String) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val file = File(dir, "debug_${prefix}_$ts.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("ROI_DEBUG", "デバッグ画像保存: ${file.absolutePath}")
            Toast.makeText(context, "DEBUG: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ROI_DEBUG", "デバッグ画像保存失敗", e)
        }
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

                    val scoreBitmap = calcScoreRoiBitmap(fullImage)

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
                        val rotated = rotateBitmap(bitmap, getRotationDegrees())
                        // 🔧 デバッグ用：回転後フル画像を保存（座標確認用）
                        saveDebugBitmapToDownloads(rotated, "full_ocr")
                        val rect = calcOcrRect()
                        val cropped = Bitmap.createBitmap(
                            rotated,
                            rect.left.coerceIn(0, rotated.width - rect.width()),
                            rect.top.coerceIn(0, rotated.height - rect.height()),
                            rect.width(),
                            rect.height()
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
                            val rotated = rotateBitmap(bitmap, getRotationDegrees())
                            // 🔧 デバッグ用：回転後フル画像を保存（座標確認用）
                            saveDebugBitmapToDownloads(rotated, "full_score")

                            // pendingSaveBitmap を設定（保存時に使うため）
                            onSetPendingBitmap(rotated)

                            val scoreBitmap = calcScoreRoiBitmap(rotated)

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

    /**
     * EntryNo OCR 枠を相対比率から計算して返す。
     * x = baseX + 0.0044 × baseWidth
     * y = baseY
     * w = 0.1377 × baseWidth
     * h = 0.1082 × baseWidth
     */
    private fun calcOcrRect(): Rect {
        val bx = getBaseX()
        val by = getBaseY()
        val bw = getBaseWidth()
        Log.d("ROI_CALC", "calcOcrRect: baseX=$bx  baseY=$by  baseWidth=$bw")
        val left   = (bx + 0.0044f * bw).toInt()
        val top    = by
        val width  = (0.1377f * bw).toInt()
        val height = (0.1082f * bw).toInt()
        return Rect(left, top, left + width, top + height)
    }

    /**
     * スコアグリッド領域を相対比率から計算して切り出す。
     * x = baseX
     * y = baseY + 0.1997 × baseWidth
     * w = baseWidth
     * h = 0.3984 × baseWidth
     */
    private fun calcScoreRoiBitmap(fullImage: Bitmap): Bitmap {
        val bx = getBaseX()
        val by = getBaseY()
        val bw = getBaseWidth()
        Log.d("ROI_CALC", "calcScoreRoi: baseX=$bx  baseY=$by  baseWidth=$bw  imageSize=${fullImage.width}x${fullImage.height}")
        val left   = bx
        val top    = (by + 0.1997f * bw).toInt()
        val width  = bw
        val height = (0.3984f * bw).toInt()
        return Bitmap.createBitmap(
            fullImage,
            left.coerceIn(0, fullImage.width - width),
            top.coerceIn(0, fullImage.height - height),
            width,
            height
        ).copy(Bitmap.Config.ARGB_8888, true)
    }
}
