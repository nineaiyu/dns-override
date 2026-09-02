package com.dnsoverride.app.ui.rules

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dnsoverride.app.R
import com.dnsoverride.app.data.preset.PresetSubscription
import com.dnsoverride.app.data.preset.PresetSubscriptions
import com.dnsoverride.app.databinding.FragmentRulesBinding
import com.dnsoverride.app.databinding.ItemPresetDiscoverBinding
import com.dnsoverride.app.databinding.ItemRuleBinding
import com.dnsoverride.app.hosts.HostsExporter
import com.dnsoverride.app.hosts.HostsParser
import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.model.RuleGroup
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.store.RuleStore
import com.dnsoverride.app.store.SubscriptionUpdater
import com.dnsoverride.app.ui.AnimExt
import com.dnsoverride.app.ui.Ui
import com.dnsoverride.app.util.ConflictDetector
import com.dnsoverride.app.util.SubscriptionUrl
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashSet
import java.util.Locale

/**
 * 规则管理 Fragment：
 * - 顶部搜索栏 + 类型筛选 Chip（重定向/拦截/直连）
 * - 列表项支持拖拽排序、开关、编辑、删除、冲突标记、动作 Chip
 * - 长按进入多选模式，底部批量操作栏（批量启停 / 删除 / 取消）
 * - 冲突检测横幅；规则组切换、订阅、导入导出（通过顶部菜单）
 *
 * 实现约定：
 * - 规则读写全部走 [writeDispatcher]（单并发），避免并发写 SharedPreferences 丢更新；
 * - 冲突集合每次数据刷新只算一次并缓存，绝不在 `onBindViewHolder` 里现算
 *   （原实现每条 item 绑定都跑一遍全量冲突检测，列表一长就是 O(n²) 卡顿）；
 * - 所有弹窗在 [onDestroyView] 中统一回收，避免 `requireContext()` 抛异常与窗口泄漏。
 */
class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: RuleStore
    private val ruleAdapter = RuleListAdapter()

    /** 规则写入串行化：拖排序等高频写操作不会互相覆盖。 */
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 当前正在编辑的规则组 id。 */
    private var editingGroupId: String? = null
    private var rules: List<DnsRule> = emptyList()

    /** 冲突涉及的规则 id 集合，数据刷新时更新一次，供 Adapter 直接查表。 */
    private var conflictRuleIds: Set<String> = emptySet()

    // 筛选
    private var filterQuery: String = ""
    private var filterType: RuleAction? = null
    // 多选
    private val selection = LinkedHashSet<String>()

    private var progressDialog: AlertDialog? = null

    private lateinit var touchHelper: ItemTouchHelper
    private val dragListener = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(
            recyclerView: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == to || from < 0 || to < 0) return false

            val visible = ruleAdapter.currentList
            if (from >= visible.size || to >= visible.size) return false

            // 关键：列表可能处于「搜索/筛选」状态，可见位置 ≠ 组内真实位置。
            // 必须把可见下标映射回 [rules] 的真实下标再重排，
            // 否则筛选状态下拖一条会把整组顺序彻底打乱。
            val realFrom = rules.indexOfFirst { it.id == visible[from].id }
            val realTo = rules.indexOfFirst { it.id == visible[to].id }
            if (realFrom < 0 || realTo < 0) return false

            val groupId = editingGroupId ?: return false

            // 先更新内存态，保证连续拖动时下标映射始终基于最新顺序
            rules = rules.toMutableList().apply { add(realTo, removeAt(realFrom)) }
            ruleAdapter.submitList(visible.toMutableList().apply { add(to, removeAt(from)) })

            viewLifecycleOwner.lifecycleScope.launch(writeDispatcher) {
                store.reorderRules(groupId, realFrom, realTo)
                reloadVpn()
            }
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?.let { HostsParser.parse(it) }
                }.getOrNull() ?: emptyList()
            }
            if (parsed.isEmpty()) {
                showMsg(getString(R.string.rules_import_empty))
                return@launch
            }
            showImportTargetDialog(parsed, uri.lastPathSegment ?: getString(R.string.rules_import))
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val group = withContext(writeDispatcher) { currentEditingGroup() }
            if (group == null) {
                showMsg(getString(R.string.rules_no_group))
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(HostsExporter.export(group))
                    }
                }.isSuccess
            }
            if (ok) {
                showMsg(getString(R.string.rules_exported, group.rules.size))
            } else {
                showMsg(getString(R.string.common_error_generic))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = RuleStore.get(requireContext())

        // 恢复配置变更前的状态：编辑组、多选集合、搜索词、筛选类型
        savedInstanceState?.let { restoreState(it) }

        parentFragmentManager.setFragmentResultListener(
            RuleEditDialog.RESULT_KEY, viewLifecycleOwner
        ) { _, bundle ->
            val saved = RuleEditDialog.resultFrom(bundle) ?: return@setFragmentResultListener
            saveRule(saved)
        }

        // 注册规则页菜单点击回调；菜单显示由 MainActivity 按 Tab 控制，避免双标题重复
        (requireActivity() as? com.dnsoverride.app.MainActivity)?.registerMenuCallback { item ->
            when (item.itemId) {
                R.id.menu_import -> importLauncher.launch(arrayOf("text/plain", "*/*"))
                R.id.menu_export -> {
                    val g = currentEditingGroup()
                    if (g == null) {
                        showMsg(getString(R.string.rules_no_group))
                        return@registerMenuCallback true
                    }
                    exportLauncher.launch(exportFileName(g.name))
                }
                R.id.menu_new_group -> showNewGroupDialog()
                R.id.menu_rename_group -> showRenameGroupDialog()
                R.id.menu_delete_group -> showDeleteGroupDialog()
                R.id.menu_add_subscription -> showAddSubscriptionDialog()
                R.id.menu_refresh_subscription -> refreshSubscription()
                R.id.menu_discover -> showDiscoverDialog()
            }
            true
        }

        binding.listRules.layoutManager = LinearLayoutManager(requireContext())
        binding.listRules.adapter = ruleAdapter
        touchHelper = ItemTouchHelper(dragListener)
        touchHelper.attachToRecyclerView(binding.listRules)

        binding.fabAdd.setOnClickListener { showEditDialog(null) }
        binding.btnSwitchGroup.setOnClickListener { showSwitchGroupDialog() }

        binding.editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                filterQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            filterType = when (checkedIds.firstOrNull()) {
                R.id.chipOverride -> RuleAction.OVERRIDE
                R.id.chipBlock -> RuleAction.BLOCK
                R.id.chipDirect -> RuleAction.DIRECT
                else -> null
            }
            applyFilter()
        }
        binding.btnConflictDetail.setOnClickListener { showConflictDetail() }
        binding.btnBatchToggle.setOnClickListener { batchToggle() }
        binding.btnBatchDelete.setOnClickListener { batchDelete() }
        binding.btnBatchClear.setOnClickListener { exitSelection() }
        binding.imgSelectAll.setOnClickListener { selectAllVisible() }

        // 恢复搜索词与筛选态到控件
        if (filterQuery.isNotEmpty()) binding.editSearch.setText(filterQuery)
        when (filterType) {
            RuleAction.OVERRIDE -> binding.chipOverride.isChecked = true
            RuleAction.BLOCK -> binding.chipBlock.isChecked = true
            RuleAction.DIRECT -> binding.chipDirect.isChecked = true
            null -> binding.chipAll.isChecked = true
        }

        AnimExt.stagger(
            binding.searchBox,
            binding.filterChips,
            binding.listRules,
            binding.fabAdd
        )

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        editingGroupId?.let { outState.putString(KEY_GROUP_ID, it) }
        outState.putStringArrayList(KEY_SELECTION, ArrayList(selection))
        outState.putString(KEY_QUERY, filterQuery)
        filterType?.let { outState.putString(KEY_FILTER_TYPE, it.name) }
    }

    override fun onDestroyView() {
        // 统一回收仍可能显示的对话框，避免 BadTokenException / 窗口泄漏
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroyView()
        _binding = null
    }

    private fun restoreState(bundle: Bundle) {
        editingGroupId = bundle.getString(KEY_GROUP_ID)
        selection.clear()
        bundle.getStringArrayList(KEY_SELECTION)?.let { selection.addAll(it) }
        filterQuery = bundle.getString(KEY_QUERY).orEmpty()
        filterType = bundle.getString(KEY_FILTER_TYPE)?.let {
            runCatching { RuleAction.valueOf(it) }.getOrNull()
        }
    }

    // ------------------------- 数据 -------------------------

    private fun currentEditingGroup(): RuleGroup? {
        val id = editingGroupId ?: return null
        return store.listGroups().firstOrNull { it.id == id }
    }

    private fun refresh() {
        if (_binding == null) return
        lifecycleScope.launch {
            val groups = withContext(writeDispatcher) { store.listGroups() }
            if (editingGroupId == null || groups.none { it.id == editingGroupId }) {
                editingGroupId = groups.firstOrNull()?.id
            }
            val editing = groups.firstOrNull { it.id == editingGroupId }
            rules = editing?.rules ?: emptyList()

            if (_binding == null) return@launch

            val enabledCount = rules.count { it.enabled }
            binding.textActiveGroup.text = if (editing?.isSubscription == true) {
                getString(
                    R.string.rules_active_group_subscription,
                    editing.name, rules.size, enabledCount
                )
            } else {
                getString(
                    R.string.rules_active_group,
                    editing?.name ?: getString(R.string.common_none),
                    rules.size, enabledCount
                )
            }

            updateFilterChipCounts(rules)
            applyFilter()
            refreshConflicts()
        }
    }

    private fun applyFilter() {
        if (_binding == null) return
        val q = filterQuery.lowercase(Locale.getDefault())
        val filtered = rules.filter { rule ->
            val matchType = filterType == null || rule.effectiveAction() == filterType
            val matchQuery = q.isEmpty() ||
                rule.domain.lowercase(Locale.getDefault()).contains(q) ||
                rule.note.lowercase(Locale.getDefault()).contains(q)
            matchType && matchQuery
        }
        ruleAdapter.submitList(filtered)
        // 空态：过滤后为空时展示引导（仅当规则组本身非空、确实是被过滤为空）
        val emptyNow = filtered.isEmpty()
        binding.emptyState.visibility = if (emptyNow) View.VISIBLE else View.GONE
        binding.listRules.visibility = if (emptyNow) View.GONE else View.VISIBLE
    }

    /** 在筛选 Chip 上展示各类型规则数量，让用户直观了解分布。 */
    private fun updateFilterChipCounts(all: List<DnsRule>) {
        val override = all.count { it.effectiveAction() == RuleAction.OVERRIDE }
        val block = all.count { it.effectiveAction() == RuleAction.BLOCK }
        val direct = all.count { it.effectiveAction() == RuleAction.DIRECT }
        binding.chipAll.text = getString(R.string.rules_filter_all)
        binding.chipOverride.text = getString(R.string.rules_filter_override, override)
        binding.chipBlock.text = getString(R.string.rules_filter_block, block)
        binding.chipDirect.text = getString(R.string.rules_filter_direct, direct)
    }

    /**
     * 重算冲突集合。只在数据刷新时调用一次并缓存结果。
     *
     * 注意：检测会遍历所有组的所有规则，且要读一遍 SharedPreferences，
     * 因此**不能**在 `onBindViewHolder` 里调用。
     */
    private fun refreshConflicts() {
        lifecycleScope.launch {
            val conflicts: List<ConflictDetector.Conflict> =
                withContext(writeDispatcher) { store.detectConflicts() }
            if (_binding == null) return@launch
            conflictRuleIds = conflicts.flatMapTo(HashSet()) { it.ruleIds }
            if (conflicts.isEmpty()) {
                binding.cardConflicts.visibility = View.GONE
            } else {
                binding.cardConflicts.visibility = View.VISIBLE
                binding.textConflictSummary.text =
                    getString(R.string.rules_conflict, conflicts.size)
            }
            // 冲突高亮变化不影响条目内容（DnsRule 未变），DiffUtil 不会重绑，
            // 因此必须显式通知，否则标记不会刷新
            val count = ruleAdapter.itemCount
            if (count > 0) ruleAdapter.notifyItemRangeChanged(0, count, PAYLOAD_CONFLICT)
        }
    }

    private fun showConflictDetail() {
        lifecycleScope.launch {
            val conflicts = withContext(writeDispatcher) { store.detectConflicts() }
            if (conflicts.isEmpty() || _binding == null) return@launch
            val lines = conflicts.joinToString("\n\n") {
                getString(R.string.rules_conflict_item, it.message)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.rules_conflict, conflicts.size))
                .setMessage(lines)
                .setPositiveButton(R.string.action_confirm, null)
                .show()
        }
    }

    // ------------------------- 多选 -------------------------

    private fun enterSelection(rule: DnsRule) {
        selection.add(rule.id)
        updateSelectionUi()
        notifyRuleChanged(rule)
    }

    private fun toggleSelection(rule: DnsRule, checked: Boolean) {
        if (checked) selection.add(rule.id) else selection.remove(rule.id)
        if (selection.isEmpty()) exitSelection() else updateSelectionUi()
        notifyRuleChanged(rule)
    }

    private fun exitSelection() {
        selection.clear()
        updateSelectionUi()
        refreshSelectionVisuals()
    }

    private fun notifyRuleChanged(rule: DnsRule) {
        val index = ruleAdapter.currentList.indexOfFirst { it.id == rule.id }
        if (index >= 0) ruleAdapter.notifyItemChanged(index)
    }

    private fun updateSelectionUi() {
        if (_binding == null) return
        val active = selection.isNotEmpty()
        binding.batchBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.fabAdd.visibility = if (active) View.GONE else View.VISIBLE
        binding.textSelectionCount.text = getString(R.string.rules_selected_count, selection.size)
        // 全选态：当前可见列表全部选中时显示已全选图标状态
        val visible = ruleAdapter.currentList
        val allSelected = visible.isNotEmpty() && visible.all { it.id in selection }
        binding.imgSelectAll.setColorFilter(
            ContextCompat.getColor(
                requireContext(),
                if (allSelected) R.color.success else R.color.md_theme_light_onSurfaceVariant
            )
        )
    }

    /** 全选 / 反选当前可见（过滤后）的规则。 */
    private fun selectAllVisible() {
        val visible = ruleAdapter.currentList
        if (visible.isEmpty()) {
            showMsg(getString(R.string.rules_nothing_to_select))
            return
        }
        val allSelected = visible.all { it.id in selection }
        if (allSelected) {
            visible.forEach { selection.remove(it.id) }
        } else {
            visible.forEach { selection.add(it.id) }
        }
        updateSelectionUi()
        refreshSelectionVisuals()
    }

    /** 多选状态只影响 item 的可见性，内容未变，需显式触发重绑。 */
    private fun refreshSelectionVisuals() {
        val count = ruleAdapter.itemCount
        if (count > 0) ruleAdapter.notifyItemRangeChanged(0, count, PAYLOAD_SELECTION)
    }

    private fun batchToggle() {
        val targets = rules.filter { it.id in selection }
        if (targets.isEmpty()) return
        val group = currentEditingGroup() ?: return
        val anyOn = targets.any { it.enabled }
        val newRules = group.rules.map { r ->
            if (r.id in selection) r.copy(enabled = !anyOn) else r
        }
        lifecycleScope.launch {
            withContext(writeDispatcher) {
                store.upsertGroup(group.copy(rules = newRules))
                reloadVpn()
            }
            showMsg(
                getString(
                    if (anyOn) R.string.rules_batch_disabled else R.string.rules_batch_enabled,
                    targets.size
                )
            )
            exitSelection()
            refresh()
        }
    }

    private fun batchDelete() {
        if (selection.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_delete_selected)
            .setMessage(getString(R.string.rules_delete_confirm, selection.size))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val group = currentEditingGroup() ?: return@setPositiveButton
                val removed = selection.toSet()
                lifecycleScope.launch {
                    withContext(writeDispatcher) {
                        store.upsertGroup(
                            group.copy(rules = group.rules.filterNot { it.id in removed })
                        )
                        reloadVpn()
                    }
                    exitSelection()
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------- 增删改 -------------------------

    private fun showEditDialog(rule: DnsRule?) {
        if (editingGroupId == null) {
            showMsg(getString(R.string.rules_no_group_to_create))
            return
        }
        // 结果通过 Fragment Result API 回传，避免回调持有 Fragment/Activity 引用
        RuleEditDialog.newInstance(rule).show(parentFragmentManager, RuleEditDialog.TAG)
    }

    /** 落盘一条规则：组内已存在同 id 则替换，否则追加。 */
    private fun saveRule(saved: DnsRule) {
        val groupId = editingGroupId ?: run {
            showMsg(getString(R.string.rules_no_group_to_create))
            return
        }
        lifecycleScope.launch {
            withContext(writeDispatcher) {
                val group = store.listGroups().firstOrNull { it.id == groupId }
                    ?: return@withContext
                val newRules = if (group.rules.any { it.id == saved.id }) {
                    group.rules.map { if (it.id == saved.id) saved else it }
                } else {
                    group.rules + saved
                }
                store.upsertGroup(group.copy(rules = newRules))
                reloadVpn()
            }
            refresh()
        }
    }

    private fun deleteRule(rule: DnsRule) {
        val groupId = editingGroupId ?: return
        lifecycleScope.launch {
            withContext(writeDispatcher) {
                val group = store.listGroups().firstOrNull { it.id == groupId }
                    ?: return@withContext
                store.upsertGroup(group.copy(rules = group.rules.filterNot { it.id == rule.id }))
                reloadVpn()
            }
            refresh()
        }
    }

    private fun toggleRule(rule: DnsRule, enabled: Boolean) {
        val groupId = editingGroupId ?: return
        lifecycleScope.launch {
            withContext(writeDispatcher) {
                val group = store.listGroups().firstOrNull { it.id == groupId }
                    ?: return@withContext
                val newRules = group.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }
                store.upsertGroup(group.copy(rules = newRules))
                reloadVpn()
            }
        }
    }

    // ------------------------- 订阅 -------------------------

    private fun showAddSubscriptionDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rules_subscription_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(56, 32, 56, 16); addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_subscription_title)
            .setMessage(R.string.rules_subscription_message)
            .setView(container)
            .setPositiveButton(R.string.rules_subscription_add, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dialog ->
                // 接管按钮点击：URL 非法时保留对话框（默认回调会直接关闭）
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = input.text.toString().trim()
                        if (!SubscriptionUrl.isValid(url)) {
                            input.error = getString(R.string.rules_subscription_invalid_url)
                            input.requestFocus()
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        fetchSubscription(url)
                    }
                }
                dialog.show()
            }
    }

    private fun fetchSubscription(url: String) {
        lifecycleScope.launch {
            showProgress(getString(R.string.rules_subscription_fetching))
            try {
                val group = RuleGroup(
                    name = SubscriptionUrl.displayName(url).ifBlank { url },
                    sourceUrl = url
                )
                val result = withContext(Dispatchers.IO) {
                    store.upsertGroup(group)
                    SubscriptionUpdater.refreshGroup(requireContext(), group, force = true)
                }
                editingGroupId = group.id
                refresh()
                reloadVpn()
                showMsg(
                    when (result) {
                        is SubscriptionUpdater.Result.Updated ->
                            getString(R.string.rules_subscription_success, result.ruleCount)
                        is SubscriptionUpdater.Result.Skipped -> result.reason
                        is SubscriptionUpdater.Result.Failed -> result.reason
                    }
                )
            } finally {
                hideProgress()
            }
        }
    }

    private fun refreshSubscription() {
        val g = currentEditingGroup()
        if (g == null || !g.isSubscription) {
            showMsg(getString(R.string.rules_subscription_not_subscription))
            return
        }
        lifecycleScope.launch {
            showProgress(getString(R.string.rules_subscription_updating))
            try {
                val result = withContext(Dispatchers.IO) {
                    SubscriptionUpdater.refreshGroup(requireContext(), g, force = true)
                }
                refresh()
                reloadVpn()
                showMsg(
                    when (result) {
                        is SubscriptionUpdater.Result.Updated ->
                            getString(R.string.rules_subscription_updated, result.ruleCount)
                        is SubscriptionUpdater.Result.Skipped -> result.reason
                        is SubscriptionUpdater.Result.Failed -> result.reason
                    }
                )
            } finally {
                hideProgress()
            }
        }
    }

    /** 「发现常用规则」：展示内置的公开订阅，一键添加为订阅组并拉取。 */
    private fun showDiscoverDialog() {
        val items = PresetSubscriptions.items
        if (items.isEmpty()) {
            showMsg(getString(R.string.rules_discover_empty))
            return
        }
        lifecycleScope.launch {
            // 枚举规则组涉及反序列化，放到 IO 线程
            val existingUrls = withContext(writeDispatcher) {
                store.listGroups().mapNotNull { it.sourceUrl }.toSet()
            }
            if (_binding == null) return@launch

            // 使用自定义 RecyclerView 对话框，避免 setMessage+setItems 在部分主题下
            // 相互覆盖导致列表项不显示，同时让每项信息（名称/分类/描述/状态）更清晰。
            val dialogView = layoutInflater.inflate(R.layout.dialog_preset_list, null, false)
            val list = dialogView.findViewById<RecyclerView>(R.id.presetList)
            list.layoutManager = LinearLayoutManager(requireContext())
            list.adapter = PresetAdapter(items, existingUrls) { preset, alreadyAdded ->
                if (alreadyAdded) {
                    showMsg(getString(R.string.rules_discover_already))
                    return@PresetAdapter
                }
                addPresetSubscription(preset)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rules_discover_title)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /** 「发现常用规则」列表适配器：展示内置订阅的名称、分类、描述与添加状态。 */
    private class PresetAdapter(
        private val items: List<PresetSubscription>,
        private val existingUrls: Set<String>,
        private val onClick: (PresetSubscription, Boolean) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {

        class VH(val binding: ItemPresetDiscoverBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemPresetDiscoverBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = items[position]
            val alreadyAdded = preset.url in existingUrls
            val b = holder.binding
            b.textName.text = preset.name
            b.textMeta.text = b.root.context.getString(R.string.rules_discover_meta, preset.category)
            b.textDesc.text = preset.description
            b.textState.text = b.root.context.getString(
                if (alreadyAdded) R.string.rules_discover_state_added
                else R.string.rules_discover_state_add
            )
            b.textState.setTextColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (alreadyAdded) R.color.success else R.color.brand_primary
                )
            )
            // 已添加置灰，弱化其强调感
            b.root.alpha = if (alreadyAdded) 0.65f else 1f
            b.root.setOnClickListener { onClick(preset, alreadyAdded) }
        }
    }

    private fun addPresetSubscription(preset: PresetSubscription) {
        lifecycleScope.launch {
            showProgress(getString(R.string.rules_subscription_preset_fetching, preset.name))
            try {
                val group = RuleGroup(name = preset.name, sourceUrl = preset.url)
                val result = withContext(Dispatchers.IO) {
                    store.upsertGroup(group)
                    SubscriptionUpdater.refreshGroup(requireContext(), group, force = true)
                }
                editingGroupId = group.id
                refresh()
                reloadVpn()
                showMsg(
                    when (result) {
                        is SubscriptionUpdater.Result.Updated -> getString(
                            R.string.rules_subscription_preset_success, preset.name, result.ruleCount
                        )
                        is SubscriptionUpdater.Result.Skipped -> result.reason
                        is SubscriptionUpdater.Result.Failed -> result.reason
                    }
                )
            } finally {
                hideProgress()
            }
        }
    }

    /** 网络拉取期间的加载提示对话框（不可取消，完成后手动 dismiss）。 */
    private fun showProgress(message: String) {
        if (_binding == null) return
        progressDialog?.dismiss()
        progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(buildProgressView(message))
            .setCancelable(false)
            .show()
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun buildProgressView(message: String): View {
        val density = resources.displayMetrics.density
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(
                (24 * density).toInt(), (20 * density).toInt(),
                (24 * density).toInt(), (20 * density).toInt()
            )
            addView(android.widget.ProgressBar(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (28 * density).toInt(), (28 * density).toInt()
                )
            })
            addView(android.widget.TextView(context).apply {
                text = message
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (16 * density).toInt() }
            })
        }
    }

    private fun showSwitchGroupDialog() {
        lifecycleScope.launch {
            val groups = withContext(writeDispatcher) { store.listGroups() }
            if (_binding == null) return@launch
            if (groups.isEmpty()) {
                showMsg(getString(R.string.rules_no_group_available))
                return@launch
            }
            val names = groups.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rules_switch_group_title)
                .setItems(names) { _, which ->
                    editingGroupId = groups[which].id
                    exitSelection()
                    refresh()
                }
                .show()
        }
    }

    private fun showNewGroupDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rules_new_group_hint)
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(56, 32, 56, 16); addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_new_group_title)
            .setView(container)
            .setPositiveButton(R.string.rules_create, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            input.error = getString(R.string.rules_name_empty)
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        lifecycleScope.launch {
                            val group = RuleGroup(name = name)
                            withContext(writeDispatcher) {
                                store.upsertGroup(group)
                                editingGroupId = group.id
                                reloadVpn()
                            }
                            refresh()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showRenameGroupDialog() {
        val g = currentEditingGroup() ?: run {
            showMsg(getString(R.string.rules_no_group))
            return
        }
        val input = EditText(requireContext()).apply { setText(g.name) }
        val container = LinearLayout(requireContext()).apply {
            setPadding(56, 32, 56, 16); addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_rename_group_title)
            .setView(container)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) {
                            input.error = getString(R.string.rules_name_empty)
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        lifecycleScope.launch {
                            withContext(writeDispatcher) { store.upsertGroup(g.copy(name = name)) }
                            refresh()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showDeleteGroupDialog() {
        val g = currentEditingGroup() ?: run {
            showMsg(getString(R.string.rules_no_group))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rules_delete_group_title)
            .setMessage(getString(R.string.rules_delete_group_message, g.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(writeDispatcher) {
                        store.deleteGroup(g.id)
                        editingGroupId = null
                        reloadVpn()
                    }
                    exitSelection()
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showImportTargetDialog(parsed: List<HostsParser.ParsedRule>, fileName: String) {
        if (_binding == null) return
        val total = parsed.size
        fun toRule(it: HostsParser.ParsedRule) = DnsRule(
            domain = it.domain, ip = it.ip, note = it.note,
            action = if (HostsParser.isAdBlockStyle(it.ip)) RuleAction.BLOCK else RuleAction.OVERRIDE
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rules_import_title, total))
            .setMessage(getString(R.string.rules_import_target, fileName))
            .setPositiveButton(R.string.rules_import_new_group) { _, _ ->
                val groupName = fileName.substringBeforeLast(".")
                lifecycleScope.launch {
                    val group = RuleGroup(name = groupName, rules = parsed.map(::toRule))
                    withContext(writeDispatcher) {
                        store.upsertGroup(group)
                        editingGroupId = group.id
                        reloadVpn()
                    }
                    refresh()
                    showMsg(getString(R.string.rules_imported, total, groupName))
                }
            }
            .setNegativeButton(R.string.rules_import_append) { _, _ ->
                val g = currentEditingGroup()
                if (g == null) {
                    showMsg(getString(R.string.rules_no_group))
                    return@setNegativeButton
                }
                lifecycleScope.launch {
                    withContext(writeDispatcher) {
                        store.upsertGroup(g.copy(rules = g.rules + parsed.map(::toRule)))
                        reloadVpn()
                    }
                    refresh()
                    showMsg(getString(R.string.rules_appended, total))
                }
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    /** 只在 VPN 运行时才发 RELOAD：未运行时服务会直接 stopSelf，没必要唤醒它。 */
    private fun reloadVpn() {
        val ctx = context ?: return
        if (DnsVpnService.STATE != DnsVpnService.State.RUNNING) return
        val intent = Intent(ctx, DnsVpnService::class.java)
            .setAction(DnsVpnService.ACTION_RELOAD)
        runCatching { ctx.startService(intent) }
    }

    /** 导出文件名去非法字符，避免 SAF 创建文件失败。 */
    private fun exportFileName(groupName: String): String {
        val safe = groupName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "rules" }
        return "dns_override_${safe}_${System.currentTimeMillis()}.txt"
    }

    private fun showMsg(text: String) {
        val root = _binding?.root ?: return
        Ui.snack(root, text)
    }

    // ------------------------- Adapter -------------------------

    private inner class RuleListAdapter :
        ListAdapter<DnsRule, RuleListAdapter.VH>(DiffRule) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

        /** 局部刷新：仅重画多选态 / 冲突标记，避免整项重绑。 */
        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
                return
            }
            val rule = getItem(position)
            if (PAYLOAD_SELECTION in payloads) holder.bindSelection(rule)
            if (PAYLOAD_CONFLICT in payloads) holder.bindConflict(rule)
        }

        inner class VH(val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root) {
            fun bindSelection(rule: DnsRule) {
                val selected = rule.id in selection
                b.checkSelect.visibility = if (selection.isEmpty()) View.GONE else View.VISIBLE
                b.checkSelect.setOnCheckedChangeListener(null)
                b.checkSelect.isChecked = selected
                b.checkSelect.setOnCheckedChangeListener { _, checked ->
                    toggleSelection(rule, checked)
                }
                b.imgDrag.visibility = if (selection.isEmpty()) View.VISIBLE else View.GONE
            }

            fun bindConflict(rule: DnsRule) {
                val isConflict = rule.id in conflictRuleIds
                b.imgConflict.visibility = if (isConflict) View.VISIBLE else View.GONE
                b.imgConflict.setColorFilter(
                    ContextCompat.getColor(itemView.context, R.color.warning)
                )
            }

            fun bind(rule: DnsRule) {
                val action = rule.effectiveAction()
                // 用 Bean 承载三元组，避免 Triple 的语义不明
                val style = actionStyle(action)
                b.iconAction.setImageResource(style.icon)
                b.iconAction.setColorFilter(ContextCompat.getColor(itemView.context, style.color))
                b.iconBadgeBg.backgroundTintList =
                    ContextCompat.getColorStateList(itemView.context, style.color)?.withAlpha(0x1F)

                b.textDomain.text = rule.domain
                b.textIp.text = when (action) {
                    RuleAction.OVERRIDE -> "→ ${rule.ip}"
                    RuleAction.BLOCK -> getString(R.string.rule_result_blocked)
                    RuleAction.DIRECT -> getString(R.string.rule_result_direct)
                }
                b.chipAction.text = getText(style.label)
                b.chipAction.chipBackgroundColor =
                    ContextCompat.getColorStateList(itemView.context, style.color)?.withAlpha(0x2A)
                b.chipAction.setTextColor(ContextCompat.getColor(itemView.context, style.color))

                if (rule.note.isNotBlank()) {
                    b.textNote.visibility = View.VISIBLE
                    b.textNote.text = rule.note
                } else {
                    b.textNote.visibility = View.GONE
                }

                // 查表即可，此处绝不做冲突检测
                bindConflict(rule)

                b.switchEnabled.setOnCheckedChangeListener(null)
                b.switchEnabled.isChecked = rule.enabled
                b.switchEnabled.setOnCheckedChangeListener { _, v -> toggleRule(rule, v) }

                b.btnEdit.setOnClickListener { showEditDialog(rule) }
                b.btnDelete.setOnClickListener { deleteRule(rule) }

                b.imgDrag.visibility = if (selection.isEmpty()) View.VISIBLE else View.GONE
                b.imgDrag.setOnTouchListener { _, motionEvent ->
                    if (motionEvent.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (filterQuery.isNotEmpty() || filterType != null) {
                            showMsg(getString(R.string.rules_reorder_requires_no_filter))
                        } else {
                            touchHelper.startDrag(this)
                        }
                    }
                    false
                }
                b.root.setOnLongClickListener { enterSelection(rule); true }

                bindSelection(rule)
            }
        }
    }

    private data class ActionStyle(
        @androidx.annotation.DrawableRes val icon: Int,
        @androidx.annotation.ColorRes val color: Int,
        @androidx.annotation.StringRes val label: Int
    )

    private fun actionStyle(action: RuleAction): ActionStyle = when (action) {
        RuleAction.OVERRIDE -> ActionStyle(
            R.drawable.ic_discover, R.color.brand_primary, R.string.rule_action_override
        )
        RuleAction.BLOCK -> ActionStyle(
            R.drawable.ic_warning, R.color.danger, R.string.rule_action_block
        )
        RuleAction.DIRECT -> ActionStyle(
            R.drawable.ic_arrow_up, R.color.success, R.string.rule_action_direct
        )
    }

    private object DiffRule : DiffUtil.ItemCallback<DnsRule>() {
        override fun areItemsTheSame(a: DnsRule, b: DnsRule) = a.id == b.id
        override fun areContentsTheSame(a: DnsRule, b: DnsRule) = a == b
    }

    private companion object {
        const val KEY_GROUP_ID = "key_editing_group_id"
        const val KEY_SELECTION = "key_selection"
        const val KEY_QUERY = "key_filter_query"
        const val KEY_FILTER_TYPE = "key_filter_type"

        /** DiffUtil payload：只更新冲突标记与多选态，避免整项重绑。 */
        const val PAYLOAD_CONFLICT = "payload_conflict"
        const val PAYLOAD_SELECTION = "payload_selection"
    }
}
