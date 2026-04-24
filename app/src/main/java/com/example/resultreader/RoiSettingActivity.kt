package com.example.resultreader

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class RoiSettingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME        = "ResultReaderPrefs"
        private const val KEY_DEVICE_LIST   = "roi_device_list"
        private const val KEY_ACTIVE_DEVICE = "roi_active_device"

        private const val DEF_BASE_X     = 436
        private const val DEF_BASE_Y     = 750
        private const val DEF_BASE_WIDTH = 2033
        private const val DEF_ROTATION   = 90

        // ビジネスカード縦横比 91:55
        private const val ASPECT = 55f / 91f

        private const val REQUEST_CAMERA = 201
    }

    // ── UI ──────────────────────────────────────────────────────────────────
    private lateinit var capturedImageView: ImageView
    private lateinit var roiOverlay: RoiOverlayView
    private lateinit var hintLayout: View
    private lateinit var captureButton: Button
    private lateinit var activeDeviceLabel: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var deleteDeviceButton: Button
    private lateinit var deviceNameEdit: EditText
    private lateinit var editX: EditText
    private lateinit var editY: EditText
    private lateinit var editWidth: EditText
    private lateinit var editHeight: EditText
    private lateinit var btn0: Button
    private lateinit var btn90: Button
    private lateinit var btn180: Button
    private lateinit var btn270: Button
    private lateinit var btnSave: Button

    // ── 状態 ────────────────────────────────────────────────────────────────
    private var capturedBitmap: Bitmap? = null

    // fitCenter 変換パラメータ（撮影後に計算）
    private var imgScale    = 1f
    private var imgOffsetX  = 0f
    private var imgOffsetY  = 0f

    private var currentLeft     = DEF_BASE_X
    private var currentTop      = DEF_BASE_Y
    private var currentWidth    = DEF_BASE_WIDTH
    private var currentRotation = DEF_ROTATION

    private var suppressTextChange = false
    private var suppressSpinner    = false

    private var deviceList = mutableListOf<String>()

    // ── カメラ ───────────────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_setting)

        bindViews()
        loadDeviceList()
        setupDeviceSpinner()
        setupRotationButtons()
        setupTextWatchers()
        setupButtons()

        // オーバーレイドラッグ → 画像座標に変換してEditTextへ反映
        roiOverlay.onFrameChanged = { left, top, width ->
            if (capturedBitmap != null) {
                val imgX = ((left  - imgOffsetX) / imgScale).toInt().coerceAtLeast(0)
                val imgY = ((top   - imgOffsetY) / imgScale).toInt().coerceAtLeast(0)
                val imgW = (width / imgScale).toInt().coerceAtLeast(1)
                suppressTextChange = true
                editX.setText(imgX.toString())
                editY.setText(imgY.toString())
                editWidth.setText(imgW.toString())
                editHeight.setText(calcHeight(imgW).toString())
                suppressTextChange = false
                currentLeft  = imgX
                currentTop   = imgY
                currentWidth = imgW
            }
        }

        if (hasCameraPermission()) setupCamera()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
        )
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
        ) setupCamera()
        else Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private fun bindViews() {
        capturedImageView   = findViewById(R.id.capturedImageView)
        roiOverlay          = findViewById(R.id.roiOverlayView)
        hintLayout          = findViewById(R.id.hintLayout)
        captureButton       = findViewById(R.id.captureButton)
        activeDeviceLabel   = findViewById(R.id.activeDeviceLabel)
        deviceSpinner       = findViewById(R.id.deviceSpinner)
        deleteDeviceButton  = findViewById(R.id.deleteDeviceButton)
        deviceNameEdit      = findViewById(R.id.deviceNameEdit)
        editX               = findViewById(R.id.roiEditX)
        editY               = findViewById(R.id.roiEditY)
        editWidth           = findViewById(R.id.roiEditWidth)
        editHeight          = findViewById(R.id.roiEditHeight)
        btn0                = findViewById(R.id.roiBtn0)
        btn90               = findViewById(R.id.roiBtn90)
        btn180              = findViewById(R.id.roiBtn180)
        btn270              = findViewById(R.id.roiBtn270)
        btnSave             = findViewById(R.id.roiSaveButton)
    }

    // ── デバイスリスト管理 ────────────────────────────────────────────────────

    private fun loadDeviceList() {
        val raw = prefs().getString(KEY_DEVICE_LIST, "") ?: ""
        deviceList = raw.split(",").filter { it.isNotBlank() }.toMutableList()
    }

    private fun setupDeviceSpinner() {
        refreshSpinner()

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressSpinner) return
                val name = deviceList.getOrNull(position) ?: return
                suppressSpinner = true
                deviceNameEdit.setText(name)
                suppressSpinner = false
                loadDeviceSettings(name)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // アクティブ機種を選択状態に
        val active = prefs().getString(KEY_ACTIVE_DEVICE, "") ?: ""
        updateActiveDeviceLabel(active)
        val idx = deviceList.indexOf(active)
        if (idx >= 0) {
            suppressSpinner = true
            deviceSpinner.setSelection(idx)
            suppressSpinner = false
            deviceNameEdit.setText(active)
            loadDeviceSettings(active)
        } else if (deviceList.isNotEmpty()) {
            deviceNameEdit.setText(deviceList[0])
            loadDeviceSettings(deviceList[0])
        }
    }

    private fun refreshSpinner() {
        val adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, deviceList.toList()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinner = true
        deviceSpinner.adapter = adapter
        suppressSpinner = false
    }

    private fun loadDeviceSettings(device: String) {
        val p = prefs()
        currentLeft     = p.getInt("roi_${device}_base_x",     DEF_BASE_X)
        currentTop      = p.getInt("roi_${device}_base_y",     DEF_BASE_Y)
        currentWidth    = p.getInt("roi_${device}_base_width",  DEF_BASE_WIDTH)
        currentRotation = p.getInt("roi_${device}_rotation",   DEF_ROTATION)

        suppressTextChange = true
        editX.setText(currentLeft.toString())
        editY.setText(currentTop.toString())
        editWidth.setText(currentWidth.toString())
        editHeight.setText(calcHeight(currentWidth).toString())
        suppressTextChange = false

        updateRotationButtonHighlight()

        // 画像表示中なら枠を更新
        if (capturedBitmap != null) syncOverlayFromCurrent()
    }

    private fun saveDevice(deviceName: String) {
        if (deviceName.isBlank()) {
            Toast.makeText(this, "機種名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        val h = calcHeight(currentWidth)
        prefs().edit().apply {
            putInt("roi_${deviceName}_base_x",     currentLeft)
            putInt("roi_${deviceName}_base_y",     currentTop)
            putInt("roi_${deviceName}_base_width",  currentWidth)
            putInt("roi_${deviceName}_base_height", h)
            putInt("roi_${deviceName}_rotation",   currentRotation)
            // 変更: テスト撮影済みの場合、画像サイズを保存（メイン画面のROI表示に使用）
            capturedBitmap?.let {
                putInt("roi_${deviceName}_image_width",  it.width)
                putInt("roi_${deviceName}_image_height", it.height)
            }
            // 変更ここまで
            putString(KEY_ACTIVE_DEVICE, deviceName)
            if (!deviceList.contains(deviceName)) {
                deviceList.add(deviceName)
                putString(KEY_DEVICE_LIST, deviceList.joinToString(","))
            }
            apply()
        }
        refreshSpinner()
        val idx = deviceList.indexOf(deviceName)
        if (idx >= 0) {
            suppressSpinner = true
            deviceSpinner.setSelection(idx)
            suppressSpinner = false
        }
        updateActiveDeviceLabel(deviceName)

        Toast.makeText(this, "保存しました：$deviceName（使用中）", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun deleteDevice(deviceName: String) {
        if (deviceName.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("削除確認")
            .setMessage("「$deviceName」の設定を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                deviceList.remove(deviceName)
                prefs().edit().apply {
                    remove("roi_${deviceName}_base_x")
                    remove("roi_${deviceName}_base_y")
                    remove("roi_${deviceName}_base_width")
                    remove("roi_${deviceName}_base_height")
                    remove("roi_${deviceName}_rotation")
                    putString(KEY_DEVICE_LIST, deviceList.joinToString(","))
                    val active = prefs().getString(KEY_ACTIVE_DEVICE, "")
                    if (active == deviceName) remove(KEY_ACTIVE_DEVICE)
                    apply()
                }
                refreshSpinner()
                if (deviceList.isNotEmpty()) {
                    deviceNameEdit.setText(deviceList[0])
                    loadDeviceSettings(deviceList[0])
                } else {
                    deviceNameEdit.setText("")
                }
                updateActiveDeviceLabel(prefs().getString(KEY_ACTIVE_DEVICE, "") ?: "")
                Toast.makeText(this, "「$deviceName」を削除しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateActiveDeviceLabel(active: String) {
        activeDeviceLabel.text = if (active.isBlank()) "使用中: (未設定)" else "使用中: $active"
    }

    // ── カメラ・撮影 ──────────────────────────────────────────────────────────

    private fun setupCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                // Preview なし（ImageCapture のみ）
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture!!)
            } catch (e: Exception) {
                Toast.makeText(this, "カメラ起動失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeTestShot() {
        val ic = imageCapture ?: run {
            Toast.makeText(this, "カメラ準備中です。しばらく待ってから再試行してください", Toast.LENGTH_SHORT).show()
            return
        }
        captureButton.isEnabled = false
        captureButton.text = "撮影中..."

        val photoFile = File.createTempFile("roi_test", ".jpg", cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        ic.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(photoFile.absolutePath)
                    val rotated = rotateBitmap(bmp, currentRotation.toFloat())
                    capturedBitmap = rotated

                    capturedImageView.setImageBitmap(rotated)
                    hintLayout.visibility = View.GONE
                    roiOverlay.visibility = View.VISIBLE

                    // ImageView レイアウト確定後にスケール計算
                    capturedImageView.post {
                        computeDisplayTransform(rotated)
                        syncOverlayFromCurrent()
                        Log.d("ROI_SHOT",
                            "撮影完了: imageSize=${rotated.width}x${rotated.height}  scale=$imgScale  offset=($imgOffsetX, $imgOffsetY)")
                    }

                    captureButton.isEnabled = true
                    captureButton.text = "テスト撮影"
                }

                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@RoiSettingActivity, "撮影エラー: ${e.message}", Toast.LENGTH_SHORT).show()
                    captureButton.isEnabled = true
                    captureButton.text = "テスト撮影"
                }
            }
        )
    }

    /**
     * ImageView(fitCenter) が bitmap をどう表示しているかを計算する。
     * 変換式: viewX = imgX * imgScale + imgOffsetX
     *         imgX  = (viewX - imgOffsetX) / imgScale
     */
    private fun computeDisplayTransform(bitmap: Bitmap) {
        val vw = capturedImageView.width.toFloat()
        val vh = capturedImageView.height.toFloat()
        val iw = bitmap.width.toFloat()
        val ih = bitmap.height.toFloat()
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) return

        imgScale   = minOf(vw / iw, vh / ih)
        imgOffsetX = (vw - iw * imgScale) / 2f
        imgOffsetY = (vh - ih * imgScale) / 2f
    }

    /** 現在の画像座標値をオーバーレイ（View座標）に反映 */
    private fun syncOverlayFromCurrent() {
        val vLeft  = currentLeft  * imgScale + imgOffsetX
        val vTop   = currentTop   * imgScale + imgOffsetY
        val vWidth = currentWidth * imgScale
        roiOverlay.updateFrameWidth(vWidth)
        roiOverlay.setFramePosition(vLeft, vTop)
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // ── UI セットアップ ────────────────────────────────────────────────────────

    private fun setupButtons() {
        captureButton.setOnClickListener { takeTestShot() }

        btnSave.setOnClickListener {
            saveDevice(deviceNameEdit.text.toString().trim())
        }

        deleteDeviceButton.setOnClickListener {
            val idx = deviceSpinner.selectedItemPosition
            val name = deviceList.getOrNull(idx) ?: ""
            if (name.isBlank()) {
                Toast.makeText(this, "削除する機種を選択してください", Toast.LENGTH_SHORT).show()
            } else {
                deleteDevice(name)
            }
        }
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
        val selected = getColor(android.R.color.holo_blue_dark)
        val default  = getColor(android.R.color.darker_gray)
        listOf(btn0 to 0, btn90 to 90, btn180 to 180, btn270 to 270).forEach { (btn, deg) ->
            btn.setBackgroundColor(if (deg == currentRotation) selected else default)
        }
    }

    private fun setupTextWatchers() {
        editX.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextChange) return
                val v = s?.toString()?.toIntOrNull() ?: return
                currentLeft = v
                if (capturedBitmap != null) syncOverlayFromCurrent()
            }
        })
        editY.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressTextChange) return
                val v = s?.toString()?.toIntOrNull() ?: return
                currentTop = v
                if (capturedBitmap != null) syncOverlayFromCurrent()
            }
        })
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
                if (capturedBitmap != null) syncOverlayFromCurrent()
            }
        })
    }

    // ── ユーティリティ ─────────────────────────────────────────────────────────

    private fun calcHeight(width: Int): Int = (width * ASPECT).toInt()

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
}
