package com.dnsoverride.app.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dnsoverride.app.R
import com.dnsoverride.app.store.StatsStore
import kotlin.math.max

/**
 * 24 小时查询趋势条形图：每根柱子表示一小时的查询总量（品牌渐变），
 * 叠加表示被拦截数量（红色）。支持入场动画（柱子从底部升起）。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val brandColor = ContextCompat.getColor(context, R.color.brand_primary)
    private val dangerColor = ContextCompat.getColor(context, R.color.danger)

    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.md_theme_light_outlineVariant)
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val rect = RectF()

    private var data: List<StatsStore.TrendPoint> = emptyList()
    private var progress = 1f
    private var animStart = 0L
    private val animDuration = 700L

    /** 设置数据并触发入场动画。 */
    fun setData(points: List<StatsStore.TrendPoint>) {
        data = points
        progress = 0f
        animStart = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 垂直渐变：顶部实色 → 底部 25% 透明，柱子更通透
        totalPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            brandColor, brandColor and 0x40FFFFFF.toInt(),
            Shader.TileMode.CLAMP
        )
        blockedPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            dangerColor, dangerColor and 0x40FFFFFF.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    private val barSpacingPx: Float
        get() = 3f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || data.isEmpty()) return

        // 入场动画插值（easeOutQuad）
        if (animStart > 0) {
            val t = ((System.currentTimeMillis() - animStart).toFloat() / animDuration).coerceIn(0f, 1f)
            progress = 1f - (1f - t) * (1f - t)
            if (t < 1f) postInvalidateOnAnimation()
        }

        val maxVal = (data.maxOfOrNull { it.total } ?: 0L).toFloat()
        val peak = max(maxVal, 1f)
        val barWidth = (w - barSpacingPx * (data.size - 1)) / data.size
        val baseline = h

        data.forEachIndexed { i, point ->
            val left = i * (barWidth + barSpacingPx)
            val totalH = (point.total.toFloat() / peak) * h * progress
            val blockedH = (point.blocked.toFloat() / peak) * h * progress

            // 总量柱（品牌渐变）
            rect.set(left, baseline - totalH, left + barWidth, baseline)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, totalPaint)

            // 拦截叠加（红色，覆盖在总量柱上方）
            if (blockedH > 0f) {
                rect.set(left, baseline - blockedH, left + barWidth, baseline)
                canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, blockedPaint)
            }
        }

        // 底部基线
        canvas.drawRect(0f, baseline - 1f, w, baseline, gridPaint)
    }
}
