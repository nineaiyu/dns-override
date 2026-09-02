package com.dnsoverride.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.dnsoverride.app.R
import com.dnsoverride.app.databinding.FragmentSettingsBinding
import com.dnsoverride.app.doh.DohProviders
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.store.SettingsStore
import com.dnsoverride.app.ui.Ui
import com.dnsoverride.app.util.DnsDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 Fragment：上游 DNS 模式（UDP/DoH）与自定义上游、DNS 缓存、屏蔽方式、
 * 按应用排除、诊断工具、开机自启、订阅自动更新、主题。
 * 设置项变更后会通知正在运行的 VPN Service 重新加载。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val settings by lazy { SettingsStore.get(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUpstream()
        setupCache()
        setupBlocking()
        setupAppExclusion()
        setupDiagnostics()
        setupBehavior()
        setupTheme()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ------------------------- 上游 DNS -------------------------

    private fun setupUpstream() {
        when (settings.upstreamMode) {
            SettingsStore.UpstreamMode.PLAIN_UDP -> binding.radioUdp.isChecked = true
            SettingsStore.UpstreamMode.DOH -> binding.radioDoh.isChecked = true
        }
        binding.groupUpstream.setOnCheckedChangeListener { _, checkedId ->
            settings.upstreamMode = when (checkedId) {
                R.id.radioDoh -> SettingsStore.UpstreamMode.DOH
                else -> SettingsStore.UpstreamMode.PLAIN_UDP
            }
            applyUpstreamChange()
        }

        binding.spinnerDohProvider.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            DohProviders.all.map { it.name }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val currentIdx = DohProviders.all.indexOfFirst { it.url == settings.dohProviderUrl }
        if (currentIdx >= 0) binding.spinnerDohProvider.setSelection(currentIdx)
        binding.textDohUrl.text = settings.dohProviderUrl

        binding.spinnerDohProvider.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    val provider = DohProviders.all[position]
                    if (provider.url != settings.dohProviderUrl) {
                        settings.dohProviderUrl = provider.url
                        binding.textDohUrl.text = provider.url
                        applyUpstreamChange()
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        binding.editCustomUpstreams.setText(settings.customUpstreams)
        binding.editCustomUpstreams.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.customUpstreams = binding.editCustomUpstreams.text.toString().trim()
                applyUpstreamChange()
            }
        }
    }

    // ------------------------- DNS 缓存 -------------------------

    private fun setupCache() {
        binding.switchCache.isChecked = settings.cacheEnabled
        binding.switchCache.setOnCheckedChangeListener { _, v ->
            settings.cacheEnabled = v
            applyUpstreamChange()
        }

        // SeekBar 范围 100..5000，步进 100
        binding.seekCacheSize.max = (5000 - 100) / 100
        binding.seekCacheSize.progress = (settings.cacheMaxEntries - 100) / 100
        binding.textCacheSize.text =
            getString(R.string.settings_cache_size_value, settings.cacheMaxEntries)
        binding.seekCacheSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 100 + progress * 100
                binding.textCacheSize.text = getString(R.string.settings_cache_size_value, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = 100 + (seekBar?.progress ?: 0) * 100
                settings.cacheMaxEntries = value
                applyUpstreamChange()
            }
        })

        binding.editTtl.setText(settings.defaultTtl.toString())
        binding.editTtl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val ttl = binding.editTtl.text.toString().trim().toIntOrNull()
                if (ttl != null && ttl in 1..86_400) {
                    settings.defaultTtl = ttl
                    applyUpstreamChange()
                } else {
                    binding.editTtl.setText(settings.defaultTtl.toString())
                }
            }
        }

        binding.editForwardTtl.setText(settings.forwardTtl.toString())
        binding.editForwardTtl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val ttl = binding.editForwardTtl.text.toString().trim().toIntOrNull()
                if (ttl != null && ttl in 0..300) {
                    settings.forwardTtl = ttl
                    applyUpstreamChange()
                } else {
                    binding.editForwardTtl.setText(settings.forwardTtl.toString())
                }
            }
        }
    }

    // ------------------------- 屏蔽设置 -------------------------

    private fun setupBlocking() {
        binding.switchBlockAaaa.isChecked = settings.blockAaaa
        binding.switchBlockAaaa.setOnCheckedChangeListener { _, v ->
            settings.blockAaaa = v
            applyUpstreamChange()
        }
        if (settings.blockModeNxdomain) binding.radioBlockNx.isChecked = true
        else binding.radioBlockZero.isChecked = true
        binding.radioBlockZero.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                settings.blockModeNxdomain = false
                applyUpstreamChange()
            }
        }
        binding.radioBlockNx.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                settings.blockModeNxdomain = true
                applyUpstreamChange()
            }
        }
    }

    // ------------------------- 按应用排除 -------------------------

    private fun setupAppExclusion() {
        updateExclusionSummary()
        binding.btnExcludedApps.setOnClickListener { showAppPicker() }
    }

    private fun updateExclusionSummary() {
        val n = settings.excludedApps.size
        binding.textExcludedApps.text = if (n == 0) {
            getString(R.string.settings_app_exclusion_none)
        } else {
            getString(R.string.settings_app_exclusion_count, n)
        }
    }

    private fun showAppPicker() {
        lifecycleScope.launch {
            val pm = requireContext().packageManager
            val apps = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .map { it to it.loadLabel(pm).toString() }
                    .distinctBy { it.first.activityInfo.packageName }
                    .sortedBy { it.second.lowercase() }
            }
            if (apps.isEmpty()) {
                Ui.snack(binding.root, getString(R.string.settings_picker_empty))
                return@launch
            }
            val excluded = settings.excludedApps
            val checked = apps.map { it.first.activityInfo.packageName in excluded }.toBooleanArray()
            val names = apps.map { "${it.second} (${it.first.activityInfo.packageName})" }.toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_picker_title)
                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.action_save) { _, _ ->
                    settings.excludedApps = apps
                        .filterIndexed { i, _ -> checked[i] }
                        .map { it.first.activityInfo.packageName }
                        .toSet()
                    updateExclusionSummary()
                    applyUpstreamChange() // RELOAD 检测到变化会重建隧道
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    // ------------------------- 诊断 -------------------------

    private fun setupDiagnostics() {
        binding.btnDiagResolve.setOnClickListener {
            val domain = binding.editDiagDomain.text.toString().trim()
            if (domain.isEmpty()) {
                Ui.snack(binding.root, getString(R.string.settings_diag_empty_domain))
                return@setOnClickListener
            }
            runDiagnostics { DnsDiagnostics.resolveReport(requireContext(), domain) }
        }
        binding.btnDiagUpstream.setOnClickListener {
            runDiagnostics { DnsDiagnostics.upstreamReport(requireContext()) }
        }
    }

    private fun runDiagnostics(block: () -> List<String>) {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching(block).getOrElse {
                    listOf(getString(R.string.settings_diag_failed, it.message))
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_diag_title)
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    // ------------------------- 行为 -------------------------

    private fun setupBehavior() {
        binding.switchBootStart.isChecked = settings.bootAutoStart
        binding.switchBootStart.setOnCheckedChangeListener { _, v ->
            settings.bootAutoStart = v
        }
        binding.switchSubAutoUpdate.isChecked = settings.subscriptionAutoUpdate
        binding.switchSubAutoUpdate.setOnCheckedChangeListener { _, v ->
            settings.subscriptionAutoUpdate = v
        }
    }

    // ------------------------- 主题 -------------------------

    private fun setupTheme() {
        when (settings.themeMode) {
            SettingsStore.ThemeMode.SYSTEM -> binding.radioSystem.isChecked = true
            SettingsStore.ThemeMode.LIGHT -> binding.radioLight.isChecked = true
            SettingsStore.ThemeMode.DARK -> binding.radioDark.isChecked = true
        }
        binding.groupTheme.setOnCheckedChangeListener { _, checkedId ->
            settings.themeMode = when (checkedId) {
                R.id.radioLight -> SettingsStore.ThemeMode.LIGHT
                R.id.radioDark -> SettingsStore.ThemeMode.DARK
                else -> SettingsStore.ThemeMode.SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(
                when (settings.themeMode) {
                    SettingsStore.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    SettingsStore.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    /**
     * 设置项变更后通知 VPN Service 重新加载。
     *
     * 仅在 VPN 已运行时才发 RELOAD：
     * - 设置本身已经持久化，下次启动 VPN 时会重新读取，不会丢失；
     * - 未运行时唤醒 Service 纯属浪费——`onStartCommand(RELOAD)` 发现没有隧道
     *   会立刻 `stopSelf()`，还会额外产生一次无意义的前后台切换。
     */
    private fun applyUpstreamChange() {
        if (DnsVpnService.STATE != DnsVpnService.State.RUNNING) return
        runCatching {
            requireContext().startService(
                Intent(requireContext(), DnsVpnService::class.java)
                    .setAction(DnsVpnService.ACTION_RELOAD)
            )
        }
    }
}
