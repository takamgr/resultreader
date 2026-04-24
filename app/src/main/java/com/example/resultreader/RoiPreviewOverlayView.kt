package com.example.resultreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class RoiPreviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var baseX = 0f
    private var baseY = 0f
    private var baseW = 0f
    private var baseH = 0f
    private var imageW = 0f
    private var imageH = 0f
    private var hasData = false

    private val paintFrame = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val paintCorner = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    /** ROI情報をセット。imageW/imageHは撮影画像のピクセルサイズ（回転後） */
    fun setRoi(baseX: Int, baseY: Int, baseWidth: Int, imageW: Int, imageH: Int) {
        this.baseX = baseX.toFloat()
        this.baseY = baseY.toFloat()
        this.baseW = baseWidth.toFloat()
        this.baseH = baseWidth * 55f / 91f   // 縦横比91:55固定
        this.imageW = imageW.toFloat()
        this.imageH = imageH.toFloat()
        hasData = imageW > 0 && imageH > 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasData) return

        val vw = width.toFloat()
        val vh = height.toFloat()

        // FIT_CENTER変換（fitCenterと同じ式）
        val scale = minOf(vw / imageW, vh / imageH)
        val offsetX = (vw - imageW * scale) / 2f
        val offsetY = (vh - imageH * scale) / 2f

        val l = baseX * scale + offsetX
        val t = baseY * scale + offsetY
        val r = l + baseW * scale
        val b = t + baseH * scale

        // 赤枠
        canvas.drawRect(l, t, r, b, paintFrame)

        // 四隅L字マーク
        val cs = 24f
        canvas.drawLine(l, t, l + cs, t, paintCorner)
        canvas.drawLine(l, t, l, t + cs, paintCorner)
        canvas.drawLine(r - cs, t, r, t, paintCorner)
        canvas.drawLine(r, t, r, t + cs, paintCorner)
        canvas.drawLine(l, b - cs, l, b, paintCorner)
        canvas.drawLine(l, b, l + cs, b, paintCorner)
        canvas.drawLine(r, b - cs, r, b, paintCorner)
        canvas.drawLine(r - cs, b, r, b, paintCorner)
    }
}
