package com.dnsoverride.app.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import java.text.DecimalFormat

/**
 * 轻量动画工具，提升界面切换与卡片出现的质感（不引入新依赖）。
 * 提供入场、错峰、数字滚动、弹性缩放、alpha 变化等常用动效。
 */
object AnimExt {

    /** 单个视图淡入 + 轻微上移，可设置起始延迟形成 stagger 效果。 */
    fun fadeSlideIn(view: View, delayMs: Long = 0, distance: Float = 16f) {
        view.alpha = 0f
        view.translationY = distance
        view.doOnPreDraw {
            ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, distance, 0f)
            ).apply {
                duration = 320
                startDelay = delayMs
                interpolator = DecelerateInterpolator(1.2f)
            }.start()
        }
    }

    /** 对一组子视图依次错峰入场。 */
    fun stagger(vararg views: View, stepMs: Long = 60) {
        views.forEachIndexed { index, v -> fadeSlideIn(v, index * stepMs) }
    }

    /**
     * 数字滚动动画：将 [TextView] 从 old 值平滑滚动到 new 值，并格式化显示。
     * 首次（old == null）直接显示，避免从 0 滚动导致的闪烁。
     */
    fun countUp(textView: TextView, newValue: Long, oldValue: Long? = null, formatter: (Long) -> String = { it.toString() }) {
        val from = oldValue ?: newValue
        if (from == newValue) {
            textView.text = formatter(newValue)
            return
        }
        ValueAnimator.ofFloat(from.toFloat(), newValue.toFloat()).apply {
            duration = 500
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { anim ->
                textView.text = formatter((anim.animatedValue as Float).toLong())
            }
            start()
        }
    }

    /** 数值格式化：千位分隔，如 1,234。 */
    fun formatCount(value: Long): String =
        DecimalFormat("#,##0").format(value)

    /** 弹性放大闪现（如状态图标切换、批量操作反馈）。 */
    fun popIn(view: View, scaleFrom: Float = 0.7f) {
        view.alpha = 0f
        view.scaleX = scaleFrom
        view.scaleY = scaleFrom
        view.doOnPreDraw {
            ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, scaleFrom, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, scaleFrom, 1f)
            ).apply {
                duration = 280
                interpolator = DecelerateInterpolator(1.1f)
            }.start()
        }
    }
}
