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
        invalidate()
    }

    fun updateRoiRects(rects: List<RectF>) {
        roiRects.clear()
        roiRects.addAll(rects)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val shrinkRight = 100f
        val left = 0f
        val top = 0f
        val right = width.toFloat() - shrinkRight
        val bottom = height.toFloat()

        // 背景（赤・緑・黄）
        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, redStrokePaint)

        // 四隅マーカー
        val len = 25f
        canvas.drawLine(left, top, left + len, top, strokePaint)
        canvas.drawLine(left, top, left, top + len, strokePaint)
        canvas.drawLine(right, top, right - len, top, strokePaint)
        canvas.drawLine(right, top, right, top + len, strokePaint)
        canvas.drawLine(left, bottom, left + len, bottom, strokePaint)
        canvas.drawLine(left, bottom, left, bottom - len, strokePaint)
        canvas.drawLine(right, bottom, right - len, bottom, strokePaint)
        canvas.drawLine(right, bottom, right, bottom - len, strokePaint)

        // ROI（水色）
        for (rect in roiRects) {
            canvas.drawRect(rect, roiPaint)
        }
    }

}