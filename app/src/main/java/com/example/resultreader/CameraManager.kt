package com.example.resultreader

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameLayout
import android.view.View
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

class CameraManager(
    private val context: AppCompatActivity,
    private val previewView: PreviewView,
    private val guideOverlay: GuideOverlayView,
    private val getIsOcrRunning: () -> Boolean,
    private val onCameraStarted: (ImageCapture, Camera?) -> Unit,
    private val onCameraCleared: () -> Unit
) {

    private val inactivityTimeout = 10000L  // ← 5秒でスリープ
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private var isCameraSuspended = false

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val newImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val newCamera = cameraProvider.bindToLifecycle(
                    context, cameraSelector, preview, newImageCapture
                )

                // 🔥 表示系を正しく復元
                previewView.visibility = View.VISIBLE
                previewView.alpha = 1f
                previewView.setBackgroundColor(Color.TRANSPARENT)

                guideOverlay.visibility = View.VISIBLE
                context.findViewById<FrameLayout>(R.id.previewContainer)
                    .setBackgroundColor(Color.TRANSPARENT)

                context.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onCameraStarted(newImageCapture, newCamera)
                Log.d("CAMERA", "📷 カメラ起動完了")
            } catch (exc: Exception) {
                Log.e("CAMERA", "❌ カメラ起動失敗", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun suspendCameraAndScreen() {
        if (isCameraSuspended || getIsOcrRunning()) return  // ← これ！！
        isCameraSuspended = true

        // CameraX完全停止
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            onCameraCleared()
        } catch (e: Exception) {
            Log.e("SLEEP", "Camera停止失敗", e)
        }

        // 表示系停止＋背景黒
        previewView.visibility = View.GONE
        guideOverlay.visibility = View.GONE
        context.findViewById<FrameLayout>(R.id.previewContainer).setBackgroundColor(Color.BLACK)

        // スクリーンONフラグ外す
        context.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Log.d("SLEEP", "💤 スリープモード突入")
    }

    fun resetInactivityTimer() {
        inactivityHandler.removeCallbacksAndMessages(null)
        inactivityHandler.postDelayed({ suspendCameraAndScreen() }, inactivityTimeout)
    }
}
