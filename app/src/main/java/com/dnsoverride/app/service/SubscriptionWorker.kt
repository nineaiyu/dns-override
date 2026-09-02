package com.dnsoverride.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dnsoverride.app.store.SettingsStore
import com.dnsoverride.app.store.SubscriptionUpdater
import java.util.concurrent.TimeUnit

/**
 * 每日自动更新过期订阅（即使 VPN 未运行也生效）。
 *
 * 仅当用户开启「订阅自动更新」时执行；未过期的组会被 [SubscriptionUpdater] 跳过，
 * 因此这是一次轻量检查。
 *
 * 排期要点：
 * - 使用 [ExistingPeriodicWorkPolicy.KEEP]：若用 UPDATE，每次打开 App 都会把
 *   周期重新计时，高频使用的用户永远等不到第一次执行；
 * - 约束为联网状态，避免在飞行模式下白跑一趟。
 */
class SubscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SettingsStore.get(applicationContext).subscriptionAutoUpdate) return Result.success()
        val results = runCatching {
            SubscriptionUpdater.refreshOutdated(applicationContext, force = false)
        }.getOrElse { return Result.retry() }
        // 只要有一个订阅明确拉取失败就重试；全部跳过/成功都算成功
        return if (results.values.any { it is SubscriptionUpdater.Result.Failed }) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val NAME = "subscription_auto_update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.MINUTES
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
