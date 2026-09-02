package com.dnsoverride.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.store.SettingsStore

/**
 * 开机自启：若用户在设置中启用了"开机自启"，则开机后自动启动 VPN。
 *
 * 前置校验：必须先确认系统已授予 VPN 权限（[VpnService.prepare] 返回 null）。
 * 否则 `establish()` 会失败，服务空转并留一条无法工作的前台通知。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore.get(context).bootAutoStart) return

        if (VpnService.prepare(context) != null) {
            Log.i(TAG, "VPN permission not granted yet, skip auto start")
            return
        }

        val startIntent = Intent(context, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }.onFailure {
            // Android 12+ 在某些情况下会限制后台启动 FGS，失败时不应让接收器崩溃
            Log.w(TAG, "auto start failed: ${it.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
