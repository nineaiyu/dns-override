package com.dnsoverride.app.ui

import android.graphics.Color
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.dnsoverride.app.R
import com.google.android.material.snackbar.Snackbar

/**
 * 全局 UI 反馈工具：用 Material Snackbar 替代原生 Toast，
 * 视觉更统一、更有质感，支持带操作按钮与深浅色自适应。
 */
object Ui {

    /** 默认信息提示（短时长，无操作）。 */
    fun snack(root: View, message: String) {
        snack(root, message, actionLabel = null)
    }

    /** 带可选操作按钮的信息提示。 */
    fun snack(root: View, message: String, actionLabel: String?, action: (() -> Unit)? = null) {
        val sb = Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
        if (actionLabel != null && action != null) {
            sb.setAction(actionLabel) { action() }
            sb.setActionTextColor(colorOf(root, R.color.brand_secondary))
        }
        sb.show()
    }

    /** 成功提示（绿色主调）。 */
    fun success(root: View, message: String) {
        val sb = Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
        sb.setBackgroundTint(colorOf(root, R.color.success))
        sb.setTextColor(Color.WHITE)
        sb.show()
    }

    /** 错误提示（红色主调）。 */
    fun error(root: View, message: String) {
        val sb = Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        sb.setBackgroundTint(colorOf(root, R.color.danger))
        sb.setTextColor(Color.WHITE)
        sb.show()
    }

    /** Context#getColor 是 API 23 才有的，统一走 ContextCompat 兼容到 minSdk 21。 */
    private fun colorOf(view: View, @ColorRes resId: Int): Int =
        ContextCompat.getColor(view.context, resId)
}
