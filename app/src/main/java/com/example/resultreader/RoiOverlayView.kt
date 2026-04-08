package com.example.resultreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * カメラプレビュー上に重ねるROI枠ビュー。
 * 縦横比は固定（AspectRatio = baseWidth : baseHeight）。
 * ドラッグで移動できる。
 *
 * 座標はすべて「フルイメージ上のpx」ではなく「このViewのdp/px」で保持し、
 * 呼び出し側が previewView のサイズとイメージ解像度を元にスケール変換する。
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // 枠の位置（View座標系 px）
    var frameLeft: Float = 100f
    var frameTop: Float = 100f
    var frameWidth: Float = 200f
    var frameHeight: Float = 121f   // width * 55 / 91

    // 縦横比（固定）：ビジネスカード標準 91:55
    private val aspectRatio: Float = 55f / 91f

    // ドラッグ用
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    // 変更通知コールバック
    var onFrameChanged: ((left: Float, top: Float, width: Float) -> Unit)? = null

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

    private val paintDim = Paint().apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val l = frameLeft
        val t = frameTop
        val r = frameLeft + frameWidth
        val b = frameTop + frameHeight

        // 枠外を薄暗く
        canvas.drawRect(0f, 0f, width.toFloat(), t, paintDim)
        canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), paintDim)
        canvas.drawRect(0f, t, l, b, paintDim)
        canvas.drawRect(r, t, width.toFloat(), b, paintDim)

        // 赤枠
        canvas.drawRect(l, t, r, b, paintFrame)

        // 四隅に太いL字マーク
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 枠の内側 or 近傍をタッチしたときだけドラッグ開始
                val margin = 60f
                if (event.x >= frameLeft - margin && event.x <= frameLeft + frameWidth + margin &&
                    event.y >= frameTop - margin && event.y <= frameTop + frameHeight + margin
                ) {
                    isDragging = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return true
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                frameLeft = (frameLeft + dx).coerceIn(0f, width - frameWidth)
                frameTop = (frameTop + dy).coerceIn(0f, height - frameHeight)

                invalidate()
                onFrameChanged?.invoke(frameLeft, frameTop, frameWidth)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    /** 縦横比を維持しながら幅を更新する */
    fun updateFrameWidth(newWidth: Float) {
        frameWidth = newWidth.coerceAtLeast(40f)
        frameHeight = frameWidth * aspectRatio
        // はみ出し補正
        frameLeft = frameLeft.coerceIn(0f, (width - frameWidth).coerceAtLeast(0f))
        frameTop = frameTop.coerceIn(0f, (height - frameHeight).coerceAtLeast(0f))
        invalidate()
    }

    /** 枠位置をView座標で直接セット */
    fun setFramePosition(left: Float, top: Float) {
        frameLeft = left.coerceIn(0f, (width - frameWidth).coerceAtLeast(0f))
        frameTop = top.coerceIn(0f, (height - frameHeight).coerceAtLeast(0f))
        invalidate()
    }
}
