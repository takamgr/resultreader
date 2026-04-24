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

    // 変更: ピンチ用
    private var isPinching = false
    private var initialPinchDist = 0f
    private var initialPinchWidth = 0f
    // 変更ここまで

    // 変更: リサイズハンドル用
    private var isResizing = false
    private val handleRadius = 20f        // 描画半径
    private val handleTouchRadius = 48f   // タッチ判定半径
    // 変更ここまで

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

    // 変更: リサイズハンドル用Paint
    private val paintHandleFill = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val paintHandleStroke = Paint().apply {
        color = Color.RED
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    // 変更ここまで

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

        // 変更: 右下コーナーにリサイズハンドル（●）を描画
        canvas.drawCircle(r, b, handleRadius, paintHandleFill)
        canvas.drawCircle(r, b, handleRadius, paintHandleStroke)
        // 変更ここまで
    }

    override fun onTouchEvent(event: MotionEvent): Boolean { // 変更
        when (event.actionMasked) { // 変更: action → actionMasked（マルチタッチ対応）
            MotionEvent.ACTION_DOWN -> {
                val handleX = frameLeft + frameWidth
                val handleY = frameTop + frameHeight
                val dx = event.x - handleX
                val dy = event.y - handleY
                if (Math.hypot(dx.toDouble(), dy.toDouble()) <= handleTouchRadius) {
                    // 変更: ●タッチ → リサイズ開始
                    isResizing = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                } else {
                    // 枠の内側 or 近傍をタッチしたときだけドラッグ開始
                    val margin = 60f
                    if (event.x >= frameLeft - margin && event.x <= frameLeft + frameWidth + margin &&
                        event.y >= frameTop - margin && event.y <= frameTop + frameHeight + margin
                    ) {
                        isDragging = true
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                } // 変更ここまで
            }
            MotionEvent.ACTION_POINTER_DOWN -> { // 変更: 2本目の指が触れたらピンチ開始
                if (event.pointerCount == 2) {
                    isPinching = true
                    isDragging = false
                    initialPinchDist = pinchDistance(event)
                    initialPinchWidth = frameWidth
                }
            } // 変更ここまで
            MotionEvent.ACTION_MOVE -> {
                if (isPinching && event.pointerCount >= 2) { // 変更: ピンチ中は幅を更新
                    val dist = pinchDistance(event)
                    if (initialPinchDist > 0f) {
                        val newWidth = initialPinchWidth * dist / initialPinchDist
                        updateFrameWidth(newWidth)
                        onFrameChanged?.invoke(frameLeft, frameTop, frameWidth)
                    }
                } else if (isResizing) { // 変更: ハンドルドラッグ中はリサイズ
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x
                    lastTouchY = event.y
                    updateFrameWidth(frameWidth + dx)
                    onFrameChanged?.invoke(frameLeft, frameTop, frameWidth)
                } else if (isDragging) { // 変更: 1本指はドラッグ移動
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    frameLeft = (frameLeft + dx).coerceIn(0f, width - frameWidth)
                    frameTop = (frameTop + dy).coerceIn(0f, height - frameHeight)

                    invalidate()
                    onFrameChanged?.invoke(frameLeft, frameTop, frameWidth)
                } // 変更ここまで
            }
            MotionEvent.ACTION_POINTER_UP -> { // 変更: 1本指に戻ったらピンチ終了
                isPinching = false
            } // 変更ここまで
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isPinching = false // 変更
                isResizing = false // 変更
            }
        }
        return true
    }

    // 変更: 2点間の距離を計算するヘルパー
    private fun pinchDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }
    // 変更ここまで

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
