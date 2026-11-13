package com.camera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 色卡四角点选。
 *
 * 色卡被拍进去之后是带透视的四边形，要按格取样就得先知道这四条边在哪儿。
 * 自动识别角点要写一堆轮廓检测，还容易在复杂背景上翻车；
 * 让用户点四下，稳定性和工作量都最优——反正是标定，只做一次。
 */
class QuadPickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val points = ArrayList<PointF>(4)

    /** 四个角都点完之后回调（顺序：左上、右上、右下、左下） */
    var onComplete: ((List<PointF>) -> Unit)? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D9E75")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D9E75")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun reset() {
        points.clear()
        invalidate()
    }

    val pointCount: Int get() = points.size

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        if (points.size >= 4) points.clear()
        points.add(PointF(event.x, event.y))
        invalidate()
        if (points.size == 4) onComplete?.invoke(points.toList())
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.size >= 2) {
            for (i in 0 until points.size - 1) {
                canvas.drawLine(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y, linePaint)
            }
            if (points.size == 4) {
                canvas.drawLine(points[3].x, points[3].y, points[0].x, points[0].y, linePaint)
            }
        }

        for ((index, p) in points.withIndex()) {
            canvas.drawCircle(p.x, p.y, 16f, dotPaint)
            canvas.drawCircle(p.x, p.y, 16f, ringPaint)
            canvas.drawText("${index + 1}", p.x, p.y + 12f, textPaint)
        }
    }
}
