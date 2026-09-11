package com.woodblock.ar.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.woodblock.ar.vision.YoloResult

/**
 * 实时 YOLO 视觉取景辅助层：
 * 在真实摄像头取景画面上实时高亮渲染 YOLO 神经网络预测的木块主体、贯通圆孔与弧形滑道。
 */
class VisionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var yoloResult: YoloResult? = null

    // 1. 木块主体画笔 (荧光亮绿)
    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val woodFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1500E676")
        style = Paint.Style.FILL
    }

    // 2. 贯通圆孔画笔 (科技亮青)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val holeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2000E5FF")
        style = Paint.Style.FILL
    }

    // 3. 弧形滑道凹槽画笔 (活力暖橙)
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9100")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val grooveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#20FF9100")
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun updateYoloResult(result: YoloResult) {
        this.yoloResult = result
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = yoloResult ?: return

        for (box in result.allBoxes) {
            val r = box.rect
            val (strokePaint, fillPaint, badgeColor, labelName) = when (box.classId) {
                0 -> Quad(woodPaint, woodFillPaint, Color.parseColor("#00C853"), "木块 ${(box.score * 100).toInt()}%")
                1 -> Quad(holePaint, holeFillPaint, Color.parseColor("#00B8D4"), "圆孔 ${(box.score * 100).toInt()}%")
                2 -> Quad(groovePaint, grooveFillPaint, Color.parseColor("#FF6D00"), "滑槽 ${(box.score * 100).toInt()}%")
                else -> Quad(woodPaint, woodFillPaint, Color.DKGRAY, "${box.className} ${(box.score * 100).toInt()}%")
            }

            // 绘制真实轮廓几何：圆孔使用圆形/椭圆拟合瞄准环，其余使用圆角框
            if (box.classId == 1) {
                canvas.drawOval(r, fillPaint)
                canvas.drawOval(r, strokePaint)
                val cx = r.centerX()
                val cy = r.centerY()
                val arm = kotlin.math.min(r.width(), r.height()) * 0.25f
                canvas.drawLine(cx - arm, cy, cx + arm, cy, strokePaint)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, strokePaint)
            } else {
                canvas.drawRoundRect(r, 12f, 12f, fillPaint)
                canvas.drawRoundRect(r, 12f, 12f, strokePaint)
            }

            // 绘制顶部标签胶囊
            val textWidth = textPaint.measureText(labelName)
            val badgeHeight = 44f
            val badgeRect = RectF(r.left, r.top - badgeHeight, r.left + textWidth + 24f, r.top)
            badgeBgPaint.color = badgeColor
            canvas.drawRoundRect(badgeRect, 8f, 8f, badgeBgPaint)
            canvas.drawText(labelName, r.left + 12f, r.top - 10f, textPaint)
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
