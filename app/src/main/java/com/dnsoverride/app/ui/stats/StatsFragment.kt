package com.dnsoverride.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dnsoverride.app.LogBuffer
import com.dnsoverride.app.R
import com.dnsoverride.app.databinding.FragmentStatsBinding
import com.dnsoverride.app.databinding.ItemLogBinding
import com.dnsoverride.app.databinding.ItemStatBinding
import com.dnsoverride.app.model.DomainStat
import com.dnsoverride.app.service.DnsInterceptor
import com.dnsoverride.app.store.StatsStore
import com.dnsoverride.app.ui.AnimExt
import com.dnsoverride.app.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统计日志 Fragment：
 * - 4 张统计卡片（总查询/拦截/转发/缓存命中）
 * - 命中率进度条
 * - 拦截/转发域名 Top 10
 * - 最近查询日志（订阅 [LogBuffer]，跨 Fragment 切换不丢失）
 * - 重置统计 + 导出/清空日志
 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val store by lazy { StatsStore.get(requireContext()) }
    private val logAdapter = LogAdapter()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var logFilter: String = ""

    private val logListener: (List<DnsInterceptor.QueryLog>) -> Unit = {
        logAdapter.submitList(filterLogs())
    }

    /** 根据搜索词过滤日志（域名 / 结果 IP / 来源）。 */
    private fun filterLogs(): List<DnsInterceptor.QueryLog> {
        val logs = LogBuffer.logs
        if (logFilter.isEmpty()) return logs
        return logs.filter {
            it.domain.lowercase(Locale.getDefault()).contains(logFilter) ||
                it.resultIp.lowercase(Locale.getDefault()).contains(logFilter) ||
                it.source.lowercase(Locale.getDefault()).contains(logFilter)
        }
    }

    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            val snapshot = LogBuffer.logs
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                    w.write("timestamp,domain,result_ip,hit,source\n")
                    val csvFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    snapshot.forEach { l ->
                        val ts = csvFmt.format(Date(l.timestamp))
                        val domain = l.domain.csvEscape()
                        val ip = l.resultIp.csvEscape()
                        val source = l.source.csvEscape()
                        w.write("$ts,$domain,$ip,${l.hit},$source\n")
                    }
                }
            }
            showMsg(getString(R.string.stats_exported, snapshot.size))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.listTopBlocked.layoutManager = LinearLayoutManager(requireContext())
        binding.listTopForwarded.layoutManager = LinearLayoutManager(requireContext())
        binding.listLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.listLogs.adapter = logAdapter

        // 日志搜索过滤
        binding.editLogSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                logFilter = s?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
                logAdapter.submitList(filterLogs())
            }
        })

        binding.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.stats_reset_title)
                .setMessage(R.string.stats_reset_message)
                .setPositiveButton(R.string.stats_reset_confirm) { _, _ -> store.reset(); refresh() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        binding.btnClearLogs.setOnClickListener {
            LogBuffer.clear()
        }

        binding.btnExportLogs.setOnClickListener {
            if (LogBuffer.logs.isEmpty()) {
                showMsg(getString(R.string.stats_no_logs))
                return@setOnClickListener
            }
            exportLogLauncher.launch("dns_override_logs_${System.currentTimeMillis()}.csv")
        }

        AnimExt.stagger(
            binding.cardRate,
            binding.listTopBlocked,
            binding.listLogs,
            binding.btnReset
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        LogBuffer.subscribe(logListener)
    }

    override fun onStop() {
        LogBuffer.unsubscribe(logListener)
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        val s = store.snapshot()
        binding.textTotal.text = s.totalQueries.toString()
        binding.textBlocked.text = s.blockedCount.toString()
        binding.textForwarded.text = s.forwardedCount.toString()
        binding.textCacheHits.text = s.cacheHits.toString()
        val cacheRate = if (s.totalQueries == 0L) 0f else s.cacheHits.toFloat() / s.totalQueries
        binding.textBlockedRate.text =
            getString(R.string.stats_cache_rate_value, cacheRate * 100)

        // 环形拦截率图
        val ratePct = (s.blockedRate * 100).toInt()
        binding.textRateCenter.text = "$ratePct%"
        binding.donutChart.setRate(s.blockedRate, animate = true)

        val empty = s.totalQueries == 0L
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.cardRate.visibility = if (empty) View.GONE else View.VISIBLE
        binding.cardTrend.visibility = if (empty) View.GONE else View.VISIBLE

        // 24h 趋势
        val trend = store.hourlyTrend()
        binding.trendChart.setData(trend)
        val peak = trend.maxOfOrNull { it.total } ?: 0L
        binding.textTrendSummary.text =
            getString(R.string.stats_peak, AnimExt.formatCount(peak))

        binding.listTopBlocked.adapter = StatAdapter(s.topBlockedDomains)
        binding.listTopForwarded.adapter = StatAdapter(s.topForwardedDomains)
    }

    private fun showMsg(text: String) {
        Ui.snack(binding.root, text)
    }

    // ------------------------- Adapters -------------------------

    private inner class StatAdapter(private val items: List<DomainStat>) :
        RecyclerView.Adapter<StatAdapter.VH>() {
        private val maxCount = items.maxOfOrNull { it.count }?.toFloat() ?: 1f
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemStatBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.b.domain.text = item.domain
            h.b.count.text = AnimExt.formatCount(item.count)
            h.b.bar.progress = (item.count.toFloat() / maxCount * 100).toInt().coerceAtLeast(4)
            h.b.rank.text = (pos + 1).toString()
        }
        inner class VH(val b: ItemStatBinding) : RecyclerView.ViewHolder(b.root)
    }

    private inner class LogAdapter :
        ListAdapter<DnsInterceptor.QueryLog, LogAdapter.VH>(DiffLog) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val log = getItem(position)
            holder.b.textTime.text = timeFmt.format(Date(log.timestamp))
            holder.b.textDomain.text = log.domain
            holder.b.textResult.text = getString(R.string.log_result_arrow, log.resultIp)
            holder.b.textTag.text =
                getString(if (log.hit) R.string.log_tag_hit else R.string.log_tag_forward)
            holder.b.textTag.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (log.hit) R.color.success else R.color.warning
                )
            )
        }

        inner class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)
    }

    private object DiffLog : DiffUtil.ItemCallback<DnsInterceptor.QueryLog>() {
        override fun areItemsTheSame(
            old: DnsInterceptor.QueryLog,
            new: DnsInterceptor.QueryLog
        ) = old.timestamp == new.timestamp && old.domain == new.domain

        override fun areContentsTheSame(
            old: DnsInterceptor.QueryLog,
            new: DnsInterceptor.QueryLog
        ) = old == new
    }
}

/** 简单 CSV 字段转义：若含逗号/引号/换行，用双引号包裹并把内部引号翻倍。 */
private fun String.csvEscape(): String =
    if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + replace("\"", "\"\"") + "\""
    } else this
