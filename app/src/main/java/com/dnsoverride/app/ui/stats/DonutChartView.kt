package com.dnsoverride.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dnsoverride.app.R
import kotlin.math.min

/**
 * 轻量环形进度图：展示 0~1 的比率（如拦截率）。
 * Aurora 版：靛→青扫描渐变弧 + 细腻轨道，支持入场动画（[setRate] 带缓动）。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var sweepAngle = 0f
    private var targetRate = 0f
    private val rect = RectF()
    private var animStart = 0L
    private val animDuration = 700L

    // Aurora 靛 → 青渐变
    private val gradientColors = intArrayOf(
        Color.parseColor("#2F50E1"),
        Color.parseColor("#5B3DF5"),
        Color.parseColor("#22D3EE"),
        Color.parseColor("#2F50E1")
    )

    init {
        val density = context.resources.displayMetrics.density
        trackPaint.strokeWidth = 14f * density
        arcPaint.strokeWidth = 14f * density
        trackPaint.color = ContextCompat.getColor(context, R.color.md_theme_light_surfaceContainerHighest)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 扫描渐变从顶部（-90°）开始
        val shader = SweepGradient(w / 2f, h / 2f, gradientColors, null)
        val matrix = Matrix()
        matrix.setRotate(-90f, w / 2f, h / 2f)
        shader.setLocalMatrix(matrix)
        arcPaint.shader = shader
    }

    fun setRate(rate: Float, animate: Boolean = true) {
        targetRate = rate.coerceIn(0f, 1f)
        if (animate) {
            animStart = System.currentTimeMillis()
            postInvalidateOnAnimation()
        } else {
            sweepAngle = targetRate * 360f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (min(w, h) - trackPaint.strokeWidth) / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 入场动画插值
        if (animStart > 0) {
            val t = ((System.currentTimeMillis() - animStart).toFloat() / animDuration).coerceIn(0f, 1f)
            val eased = 1f - (1f - t) * (1f - t) // easeOutQuad
            sweepAngle = targetRate * 360f * eased
            if (t < 1f) postInvalidateOnAnimation() else animStart = 0
        }

        canvas.drawCircle(cx, cy, radius, trackPaint)
        canvas.drawArc(rect, -90f, sweepAngle, false, arcPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        )
        setMeasuredDimension(size, size)
    }
}
