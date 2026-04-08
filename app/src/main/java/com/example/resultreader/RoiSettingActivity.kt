package com.example.resultreader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class RoiSettingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "ResultReaderPrefs"
        private const val KEY_LEFT     = "base_x"
        private const val KEY_TOP      = "base_y"
        private const val KEY_WIDTH    = "base_width"
        private const val KEY_HEIGHT   = "base_height"
        private const val KEY_ROTATION = "roi_rotation"
        private const val KEY_DEVICE   = "roi_device"

        private const val DEF_LEFT     = 436
        private const val DEF_TOP      = 750
        private const val DEF_WIDTH    = 2033
        private const val DEF_HEIGHT   = 1229
        private const val DEF_ROTATION = 90

        private const val REQUEST_CAMERA = 201

        // 縦横比（固定）：ビジネスカード標準 91:55
        private const val ASPECT = 55f / 91f
    }

    // UI
    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: RoiOverlayView
    private lateinit var editX: EditText
    private lateinit var editY: EditText
    private lateinit var editWidth: EditText
    private lateinit var editHeight: EditText
    private lateinit var editDevice: EditText
    private lateinit var btnSave: Button
    private lateinit var btn0: Button
    private lateinit var btn90: Button
    private lateinit var btn180: Button
    private lateinit var btn270: Button

    // 現在の設定値（画像座標px）
    private var currentLeft    = DEF_LEFT
    private var currentTop     = DEF_TOP
    private var currentWidth   = DEF_WIDTH
    private var currentRotation = DEF_ROTATION
    private var currentDevice  = ""

    // EditText の変更ループ防止フラグ
    private var suppressTextChange = false

    // プレビューのスケール係数（イメージ座標 → View座標）
    // previewView のサイズが確定してから計算する
    private var scaleToView = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_setting)

        bindViews()
        loadPrefs()
        setupRotationButtons()
        setupTextWatchers()
        setupSaveButton()

        roiOverlay.onFrameChanged = { left, top, _ ->
            // View座標 → 画像座標に変換してEditTextへ反映
            val imgLeft = (left / scaleToView).toInt()
            val imgTop  = (top  / scaleToView).toInt()
            suppressTextChange = true
            editX.setText(imgLeft.toString())
            editY.setText(imgTop.toString())
            suppressTextChange = false
            currentLeft = imgLeft
            currentTop  = imgTop
        }

        // previewView のサイズが確定したら枠をViewに反映
        previewView.post {
            recalcScale()
            syncOverlayFromCurrent()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun bindViews() {
        previewView = findViewById(R.id.roiPreviewView)
        roiOverlay  = findViewById(R.id.roiOverlayView)
        editX       = findViewById(R.id.roiEditX)
        editY       = findViewById(R.id.roiEditY)
        editWidth   = findViewById(R.id.roiEditWidth)
        editHeight  = findViewById(R.id.roiEditHeight)
        editDevice  = findViewById(R.id.roiEditDevice)
        btnSave     = findViewById(R.id.roiSaveButton)
        btn0        = findViewById(R.id.roiBtn0)
        btn90       = findViewById(R.id.roiBtn90)
        btn180      = findViewById(R.id.roiBtn180)
        btn270      = findViewById(R.id.roiBtn270)
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentLeft     = p.getInt(KEY_LEFT,     DEF_LEFT)
        currentTop      = p.getInt(KEY_TOP,      DEF_TOP)
        currentWidth    = p.getInt(KEY_WIDTH,    DEF_WIDTH)
        currentRotation = p.getInt(KEY_ROTATION, DEF_ROTATION)
        currentDevice   = p.getString(KEY_DEVICE, "") ?: ""

        suppressTextChange = true
        editX.setText(currentLeft.toString())
        editY.setText(currentTop.toString())
        editWidth.setText(currentWidth.toString())
        editHeight.setText(calcHeight(currentWidth).toString())
        editDevice.setText(currentDevice)
        suppressTextChange = false

        updateRotationButtonHighlight()
    }

    private fun setupRotationButtons() {
        listOf(btn0 to 0, btn90 to 90, btn180 to 180, btn270 to 270).forEach { (btn, deg) ->
            btn.setOnClickListener {
                currentRotation = deg
                updateRotationButtonHighlight()
            }
        }
    }

    private fun updateRotationButtonHighlight() {
        val selectedColor = getColor(android.R.color.holo_blue_dark)
        val defaultColor  = getColor(android.R.color.darker_gray)
        listOf(btn0 to 0, btn90 to 90, btn180 to 180, btn270 to 270).forEach { (btn, deg) ->
            btn.setBackgroundColor(if (deg == currentRotation) selectedColor else defaultColor)
        }
    }

    private fun setupTextWatchers() {
        // X
        editX.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextChange) return
                val v = s?.toString()?.toIntOrNull() ?: return
                currentLeft = v
                syncOverlayFromCurrent()
            }
        })
        // Y
        editY.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextChange) return
                val v = s?.toString()?.toIntOrNull() ?: return
                currentTop = v
                syncOverlayFromCurrent()
            }
        })
        // Width → Height を自動計算
        editWidth.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextChange) return
                val v = s?.toString()?.toIntOrNull() ?: return
                currentWidth = v
                suppressTextChange = true
                editHeight.setText(calcHeight(v).toString())
                suppressTextChange = false
                roiOverlay.updateFrameWidth(v * scaleToView)
                roiOverlay.onFrameChanged?.invoke(
                    roiOverlay.frameLeft, roiOverlay.frameTop, roiOverlay.frameWidth
                )
            }
        })
    }

    private fun setupSaveButton() {
        btnSave.setOnClickListener {
            val w = editWidth.text.toString().toIntOrNull() ?: currentWidth
            val h = calcHeight(w)
            val device = editDevice.text.toString().trim()

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                putInt(KEY_LEFT,     currentLeft)
                putInt(KEY_TOP,      currentTop)
                putInt(KEY_WIDTH,    w)
                putInt(KEY_HEIGHT,   h)
                putInt(KEY_ROTATION, currentRotation)
                putString(KEY_DEVICE, device)
                apply()
            }
            Toast.makeText(
                this,
                "保存しました（${device.ifBlank { "機種名未入力" }}）",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "カメラ起動失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    /** 画像座標 → View座標のスケールを計算（previewView サイズ確定後に呼ぶ） */
    private fun recalcScale() {
        // 回転後の画像解像度をざっくり想定（縦持ち撮影 → 90度回転後は landscape）
        // ここでは previewView の幅を基準に、roi_width のデフォルト画像幅(1080想定)でスケールする
        // 実際には機種によって異なるが、調整画面なので目安でよい
        val imageWidth = 4032f  // 回転後の想定幅（4K撮影時）
        scaleToView = previewView.width.toFloat() / imageWidth
        if (scaleToView <= 0f) scaleToView = 1f
    }

    /** currentLeft/Top/Width から roiOverlay の枠位置を更新 */
    private fun syncOverlayFromCurrent() {
        val vLeft  = currentLeft  * scaleToView
        val vTop   = currentTop   * scaleToView
        val vWidth = currentWidth * scaleToView
        roiOverlay.updateFrameWidth(vWidth)
        roiOverlay.setFramePosition(vLeft, vTop)
    }

    private fun calcHeight(width: Int): Int = (width * ASPECT).toInt()
}
