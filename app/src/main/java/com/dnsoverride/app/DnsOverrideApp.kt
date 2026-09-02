package com.dnsoverride.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.dnsoverride.app.store.RuleStore
import com.dnsoverride.app.store.SettingsStore

/**
 * Application 入口：在启动时应用主题设置，并写入首次启动的默认规则。
 */
class DnsOverrideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(
            when (SettingsStore.get(this).themeMode) {
                SettingsStore.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                SettingsStore.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        // 首次启动写入 assets 里的默认规则组
        RuleStore.get(this).ensureDefaultSeed()
    }
}
