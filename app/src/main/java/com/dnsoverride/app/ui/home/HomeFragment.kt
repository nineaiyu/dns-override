package com.dnsoverride.app.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dnsoverride.app.R
import com.dnsoverride.app.databinding.FragmentHomeBinding
import com.dnsoverride.app.databinding.ItemLogCompactBinding
import com.dnsoverride.app.databinding.ItemMetricCardBinding
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.LogBuffer
import com.dnsoverride.app.store.SettingsStore
import com.dnsoverride.app.store.StatsStore
import com.dnsoverride.app.ui.AnimExt
import com.dnsoverride.app.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 首页 Fragment（概览仪表盘）：
 * - 品牌渐变 Hero 卡：VPN 大开关 + 实时运行时长 + 当前上游
 * - 2x2 指标卡：查询总数 / 已拦截 / 缓存命中 / 已转发
 * - 最近活动：实时展示最新 DNS 拦截/转发记录
 *
 * 状态变化通过 [DnsVpnService.ACTION_STATE] 广播接收；指标与活动每秒刷新。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsStore
    private val activityAdapter = RecentActivityAdapter()

    private var vpnStartedAt = 0L

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stateName = intent.getStringExtra(DnsVpnService.EXTRA_STATE) ?: return
            updateUiForState(DnsVpnService.State.valueOf(stateName))
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            startService(buildVpnIntent(DnsVpnService.ACTION_START))
        } else {
            binding.switchProtect.isChecked = false
            Ui.snack(binding.root, getString(R.string.vpn_permission_denied))
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户拒绝也不影响 VPN 运行，仅影响通知显示 */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsStore.get(requireContext())

        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentList.adapter = activityAdapter
        binding.recentList.isNestedScrollingEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.switchProtect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startVpn() else stopVpn()
        }

        binding.btnGoRules.setOnClickListener {
            (requireActivity() as? com.dnsoverride.app.MainActivity)?.switchToTab(R.id.nav_rules)
        }

        updateUiForState(DnsVpnService.STATE)
        startRealtimeLoop()

        // 卡片错峰入场
        AnimExt.stagger(
            binding.heroCard,
            binding.metricQueries.root,
            binding.metricBlocked.root,
            binding.metricCache.root,
            binding.metricForward.root
        )
    }

    override fun onStart() {
        super.onStart()
        // 用 ContextCompat 的重载：它内部按 API 级别选择正确的注册方式
        // （带 flags 的三参重载 API 26 才有，低版本直接调用会 NoSuchMethodError），
        // 且能保证非导出的接收器不会被其他应用触发。
        ContextCompat.registerReceiver(
            requireContext(),
            stateReceiver,
            IntentFilter(DnsVpnService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateUiForState(DnsVpnService.STATE)
        refreshMetrics()
        refreshRecent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------- VPN 控制 -------------------------

    private fun startVpn() {
        val prep = VpnService.prepare(requireContext())
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            startService(buildVpnIntent(DnsVpnService.ACTION_START))
        }
    }

    private fun stopVpn() {
        startService(buildVpnIntent(DnsVpnService.ACTION_STOP))
    }

    private fun buildVpnIntent(action: String): Intent =
        Intent(requireContext(), DnsVpnService::class.java).setAction(action)

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            intent.action == DnsVpnService.ACTION_START
        ) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun updateUiForState(state: DnsVpnService.State) {
        val running = state == DnsVpnService.State.RUNNING
        if (running && vpnStartedAt == 0L) vpnStartedAt = SystemClock.elapsedRealtime()
        if (!running) vpnStartedAt = 0L

        binding.switchProtect.setOnCheckedChangeListener(null)
        if (state != DnsVpnService.State.STARTING) {
            binding.switchProtect.isChecked = running
        }
        binding.switchProtect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startVpn() else stopVpn()
        }

        binding.statusTitle.text = when (state) {
            DnsVpnService.State.RUNNING -> getString(R.string.home_status_protected)
            DnsVpnService.State.STARTING -> getString(R.string.state_starting)
            DnsVpnService.State.STOPPING -> getString(R.string.state_stopping)
            else -> getString(R.string.home_status_unprotected)
        }
        binding.statusSub.text = when (state) {
            DnsVpnService.State.RUNNING -> getString(R.string.home_vpn_active)
            DnsVpnService.State.STARTING -> getString(R.string.state_starting_desc)
            DnsVpnService.State.STOPPING -> getString(R.string.state_stopping_desc)
            else -> getString(R.string.home_vpn_inactive)
        }
        // 保护中/未保护使用不同盾牌图标，玻璃圆底保持一致，状态由脉冲环传达
        binding.statusIcon.setImageResource(
            if (running) R.drawable.ic_shield_check else R.drawable.ic_shield_alert
        )
        // 保护中：显示呼吸脉冲环动画；否则隐藏
        binding.statusPulse.visibility = if (running) View.VISIBLE else View.GONE
        if (running && binding.statusPulse.animation == null) {
            binding.statusPulse.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(), com.dnsoverride.app.R.anim.pulse
                )
            )
        }
        binding.switchProtect.isEnabled = state == DnsVpnService.State.RUNNING ||
            state == DnsVpnService.State.STOPPED

        updateUpstreamText()
        refreshMetrics()
    }

    private fun updateUpstreamText() {
        val label = when (settings.upstreamMode) {
            SettingsStore.UpstreamMode.DOH ->
                getString(R.string.home_upstream_doh, settings.dohProviderUrl)
            else -> getString(
                R.string.home_upstream_udp,
                settings.upstreamServerList().firstOrNull() ?: DEFAULT_UPSTREAM_FALLBACK
            )
        }
        binding.upstreamText.text = getString(R.string.home_upstream, label)
    }

    // ------------------------- 实时刷新 -------------------------

    /**
     * 实时刷新循环。
     *
     * 用 `isResumed` 收敛刷新时机：Fragment 被切走（例如停在「规则」Tab）时不再
     * 每秒读 SharedPreferences 并重建列表，避免无谓的 IO 与主线程开销。
     */
    private fun startRealtimeLoop() {
        lifecycleScope.launch {
            while (isActive) {
                if (isResumed && vpnStartedAt != 0L) {
                    val secs = (SystemClock.elapsedRealtime() - vpnStartedAt) / 1000
                    binding.uptimeText.text = getString(R.string.home_uptime, formatDuration(secs))
                } else if (isResumed) {
                    binding.uptimeText.text = getString(R.string.home_uptime, PLACEHOLDER_NONE)
                }
                delay(1000)
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                if (isResumed) {
                    refreshMetrics()
                    refreshRecent()
                }
                delay(1500)
            }
        }
    }

    private fun formatDuration(totalSecs: Long): String {
        val h = totalSecs / 3600
        val m = (totalSecs % 3600) / 60
        val s = totalSecs % 60
        // 显式指定 Locale：默认 Locale 在部分语言下会把 ASCII 数字替换为本地数字，
        // 且 lint 的 DefaultLocale 检查会告警
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.getDefault(), "%dm %02ds", m, s)
            else -> String.format(Locale.getDefault(), "%ds", s)
        }
    }

    // 记录上一轮指标值，用于数字滚动
    private var lastTotal = 0L
    private var lastBlocked = 0L
    private var lastCache = 0L
    private var lastForwarded = 0L

    private fun refreshMetrics() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { StatsStore.get(requireContext()).snapshot() }
            withContext(Dispatchers.Main) {
                bindMetric(
                    include = binding.metricQueries,
                    icon = R.drawable.ic_discover,
                    value = snap.totalQueries,
                    labelRes = R.string.home_metric_queries,
                    accentRes = R.color.brand_primary,
                    old = lastTotal
                )
                bindMetric(
                    include = binding.metricBlocked,
                    icon = R.drawable.ic_warning,
                    value = snap.blockedCount,
                    labelRes = R.string.home_metric_blocked,
                    accentRes = R.color.danger,
                    old = lastBlocked
                )
                bindMetric(
                    include = binding.metricCache,
                    icon = R.drawable.ic_arrow_down,
                    value = snap.cacheHits,
                    labelRes = R.string.home_metric_cache,
                    accentRes = R.color.success,
                    old = lastCache
                )
                bindMetric(
                    include = binding.metricForward,
                    icon = R.drawable.ic_arrow_up,
                    value = snap.forwardedCount,
                    labelRes = R.string.home_metric_forward,
                    accentRes = R.color.info,
                    old = lastForwarded
                )
                lastTotal = snap.totalQueries
                lastBlocked = snap.blockedCount
                lastCache = snap.cacheHits
                lastForwarded = snap.forwardedCount
            }
        }
    }

    /**
     * 绑定单个指标卡：彩色图标底色 + 数字滚动动画。
     */
    private fun bindMetric(
        include: ItemMetricCardBinding,
        @DrawableRes icon: Int,
        value: Long,
        labelRes: Int,
        accentRes: Int,
        old: Long
    ) {
        include.metricIcon.setImageResource(icon)
        include.metricIcon.setColorFilter(ContextCompat.getColor(requireContext(), accentRes))
        include.iconContainer.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(), accentRes
        )?.withAlpha(0x1F)
        include.metricValue.setTextColor(ContextCompat.getColor(requireContext(), accentRes))
        include.metricLabel.setText(labelRes)
        AnimExt.countUp(include.metricValue, value, old, AnimExt::formatCount)
    }

    private fun refreshRecent() {
        lifecycleScope.launch {
            val logs = withContext(Dispatchers.IO) {
                LogBuffer.logs.take(6).map { RecentItem(it) }
            }
            withContext(Dispatchers.Main) {
                val has = logs.isNotEmpty()
                binding.recentEmpty.visibility = if (has) View.GONE else View.VISIBLE
                binding.recentList.visibility = if (has) View.VISIBLE else View.GONE
                activityAdapter.submitList(logs)
            }
        }
    }

    // ------------------------- 最近活动列表 -------------------------

    data class RecentItem(val entry: com.dnsoverride.app.service.DnsInterceptor.QueryLog)

    private inner class RecentActivityAdapter :
        ListAdapter<RecentItem, RecentActivityAdapter.VH>(DiffRecent) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemLogCompactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        inner class VH(val b: ItemLogCompactBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: RecentItem) {
                val e = item.entry
                b.domain.text = e.domain
                val ctx = itemView.context
                val (action, icon, tint) = when (e.source) {
                    "block" -> Triple(
                        ctx.getString(R.string.log_blocked), R.drawable.ic_warning, R.color.danger
                    )
                    "block-aaaa" -> Triple(
                        ctx.getString(R.string.log_blocked_aaaa), R.drawable.ic_warning, R.color.danger
                    )
                    "rule" -> Triple(
                        ctx.getString(R.string.log_redirected, e.resultIp),
                        R.drawable.ic_discover,
                        R.color.brand_primary
                    )
                    "cache" -> Triple(
                        ctx.getString(R.string.log_cache_hit), R.drawable.ic_arrow_down, R.color.success
                    )
                    "upstream" -> Triple(
                        ctx.getString(R.string.log_forwarded, e.resultIp),
                        R.drawable.ic_arrow_up,
                        R.color.info
                    )
                    "servfail" -> Triple(
                        ctx.getString(R.string.log_upstream_failed), R.drawable.ic_warning, R.color.warning
                    )
                    else -> Triple(e.resultIp, R.drawable.ic_discover, R.color.brand_primary)
                }
                b.result.text = action
                b.time.text = formatClock(e.timestamp)
                b.icon.setImageResource(icon)
                b.icon.setColorFilter(ContextCompat.getColor(itemView.context, tint))
            }
        }
    }

    private object DiffRecent : DiffUtil.ItemCallback<RecentItem>() {
        override fun areItemsTheSame(old: RecentItem, new: RecentItem): Boolean =
            old.entry === new.entry
        override fun areContentsTheSame(old: RecentItem, new: RecentItem): Boolean =
            old.entry == new.entry
    }

    private fun formatClock(ts: Long): String = clockFormat.format(java.util.Date(ts))

    private companion object {
        /** 上游未配置时展示的兜底地址（仅用于文案展示）。 */
        const val DEFAULT_UPSTREAM_FALLBACK = "8.8.8.8"

        /** 运行时长为空时展示的占位符。 */
        const val PLACEHOLDER_NONE = "—"

        // SimpleDateFormat 非线程安全，每个 Fragment 实例持有一份，避免每次格式化都新建
        val clockFormat = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }
}
