// 🔒 RRv1.6 Copilot Guard (ID: RRv1_6_GUARD)
// Do NOT modify ROI/GuideOverlay constants, CSV columns/order, filename rule, save path, or ranking rules.
// Only non-breaking bug fixes around them are allowed. If unsure: STOP and ask.

package com.example.resultreader

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var statusColor: String = "red"
    private val roiRects = mutableListOf<RectF>()

    /** 検出状態が変化したときに呼ばれるコールバック（"red" / "yellow" / "green"） */
    var onStatusChanged: ((String) -> Unit)? = null

    // 🔧 赤枠だけの描画位置を調整したいときはここをいじる（左上に-値でズレる）
    private val DRAW_OFFSET_X = -0f
    private val DRAW_OFFSET_Y = -0f

    // 背景色（成功/失敗/注意）
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#80FF0000") // 初期赤
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // 赤の輪郭線
    private val redStrokePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // 四隅マーカー
    private val strokePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 15f
        isAntiAlias = true
    }

    // ROI（水色）
    private val roiPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    fun setDetected(status: String) {
        statusColor = status
        fillPaint.color = when (statusColor) {
            "green" -> Color.parseColor("#8032CD32") // 成功：緑
            "yellow" -> Color.parseColor("#80FFD700") // 注意：黄色
            else -> Color.parseColor("#80FF0000") // 待機・失敗：赤
        }
        Log.d("GuideOverlay", "色変更: $statusColor")
        onStatusChanged?.invoke(statusColor)
        invalidate()
    }

    fun updateRoiRects(rects: List<RectF>) {
        roiRects.clear()
        roiRects.addAll(rects)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 赤枠・背景・四隅マーカーの描画は非表示。
        // 状態管理（setDetected / onStatusChanged）は有効。
    }

}