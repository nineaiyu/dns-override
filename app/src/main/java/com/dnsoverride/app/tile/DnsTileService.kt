package com.dnsoverride.app.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.dnsoverride.app.MainActivity
import com.dnsoverride.app.R
import com.dnsoverride.app.service.DnsVpnService

/**
 * Quick Settings Tile：在通知栏快捷开关中切换 VPN。
 *
 * 注意：Tile 点击无法弹出 VPN 权限对话框，首次需通过 Activity 请求权限。
 */
@RequiresApi(Build.VERSION_CODES.N)
class DnsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val running = DnsVpnService.STATE == DnsVpnService.State.RUNNING
        if (running) {
            startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            return
        }
        // Tile 点击无法弹出 VPN 权限对话框，首次需通过 Activity 请求权限
        val launchIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_REQUEST_VPN, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 带 PendingIntent 的重载是 Android 14(API 34) 才加入的，
            // 低版本直接调用会 NoSuchMethodError
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // Intent 重载在 API 34 被废弃（targetSdk >= 34 时平台会抛异常），
            // 但 PendingIntent 重载 API 34 才存在，低版本只能用这个。
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launchIntent)
        }
    }

    private fun updateTile() {
        val running = DnsVpnService.STATE == DnsVpnService.State.RUNNING
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.app_name)
            // tile subtitle 是 Android 10(API 29) 才加入的，低版本调用会 NoSuchMethodError
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(if (running) R.string.tile_state_on else R.string.tile_state_off)
            }
            icon = Icon.createWithResource(this@DnsTileService, R.drawable.ic_tile)
            updateTile()
        }
    }
}
