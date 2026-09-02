package com.dnsoverride.app.ui.rules

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.dnsoverride.app.R
import com.dnsoverride.app.databinding.DialogRuleEditBinding
import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.util.IpValidator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson

/**
 * 添加 / 编辑规则对话框。动作三选一：
 * - 覆盖：域名解析为指定 IP（需填 IP）
 * - 屏蔽：返回 0.0.0.0 / NXDOMAIN（无需 IP）
 * - 直连：白名单，不拦截走上游（无需 IP）
 *
 * 实现约束：
 * - 必须提供无参构造函数并只通过 [newInstance] 传递参数，否则配置变更
 *   （旋转屏幕、深色模式切换）重建 Fragment 时会抛 `Fragment$InstantiationException`。
 * - 「保存」按钮的点击在 [onResume] 中被接管：默认的 `setPositiveButton`
 *   回调一旦触发就会关闭对话框，校验失败时无法保留用户输入。
 * - 结果通过 Fragment Result API 回传，避免持有 Activity 引用的回调泄漏。
 */
class RuleEditDialog : DialogFragment() {

    private var _binding: DialogRuleEditBinding? = null
    private val binding get() = _binding!!

    /** 编辑模式下的原始规则；新建模式为 null。来自 arguments，可安全跨重建恢复。 */
    private val initialRule: DnsRule?
        get() = arguments?.getString(ARG_RULE)?.let { json ->
            runCatching { Gson().fromJson(json, DnsRule::class.java) }.getOrNull()
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogRuleEditBinding.inflate(layoutInflater)
        val initial = initialRule

        if (initial != null) {
            binding.editDomain.setText(initial.domain)
            // 屏蔽 / 直连不填 IP，避免把上一动作的 0.0.0.0 回显给用户造成困惑
            binding.editIp.setText(
                if (initial.effectiveAction() == RuleAction.OVERRIDE) initial.ip else ""
            )
            binding.editNote.setText(initial.note)
            binding.checkEnabled.isChecked = initial.enabled
            when (initial.effectiveAction()) {
                RuleAction.OVERRIDE -> binding.radioOverride.isChecked = true
                RuleAction.BLOCK -> binding.radioBlock.isChecked = true
                RuleAction.DIRECT -> binding.radioDirect.isChecked = true
            }
        }
        applyActionVisibility()
        binding.groupAction.setOnCheckedStateChangeListener { _, _ -> applyActionVisibility() }

        return MaterialAlertDialogBuilder(requireActivity())
            .setTitle(
                getString(
                    if (initial == null) R.string.rule_edit_title_add else R.string.rule_edit_title_edit
                )
            )
            .setView(binding.root)
            // 先传 null，真正点击逻辑在 onResume 中接管，以便校验失败时保留对话框
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    override fun onResume() {
        super.onResume()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (commitIfValid()) dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 校验并回传结果。返回 false 表示校验失败，对话框保持打开。 */
    private fun commitIfValid(): Boolean {
        val domain = binding.editDomain.text?.toString()?.trim().orEmpty()
        val ip = binding.editIp.text?.toString()?.trim().orEmpty()
        val note = binding.editNote.text?.toString()?.trim().orEmpty()
        val enabled = binding.checkEnabled.isChecked
        val action = selectedAction()

        if (!IpValidator.isValidDomain(domain)) {
            binding.editDomain.error = getString(R.string.rule_error_domain_invalid)
            binding.editDomain.requestFocus()
            return false
        }
        if (action == RuleAction.OVERRIDE && !IpValidator.isValidIp(ip)) {
            binding.editIp.error = getString(R.string.rule_error_ip_invalid)
            binding.editIp.requestFocus()
            return false
        }

        val initial = initialRule
        val saved = if (initial != null) {
            initial.copy(
                domain = domain,
                ip = ip,
                note = note,
                action = action,
                whitelist = action == RuleAction.DIRECT,
                enabled = enabled
            )
        } else {
            DnsRule(
                domain = domain,
                ip = ip,
                note = note,
                action = action,
                whitelist = action == RuleAction.DIRECT,
                enabled = enabled
            )
        }
        // 走 Fragment Result API：结果存在 FragmentManager 中，
        // 配置变更重建后仍能被新实例取到，且不会持有 Activity 引用
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf(ARG_RULE to Gson().toJson(saved)))
        return true
    }

    private fun selectedAction(): RuleAction = when (binding.groupAction.checkedChipId) {
        R.id.radioBlock -> RuleAction.BLOCK
        R.id.radioDirect -> RuleAction.DIRECT
        else -> RuleAction.OVERRIDE
    }

    /** 只有「覆盖」需要填 IP。 */
    private fun applyActionVisibility() {
        binding.layoutIp.visibility =
            if (selectedAction() == RuleAction.OVERRIDE) View.VISIBLE else View.GONE
    }

    companion object {
        const val TAG = "rule_edit"
        const val RESULT_KEY = "rule_edit_result"
        private const val ARG_RULE = "arg_rule"

        /** 唯一构造入口：参数必须走 arguments，保证配置变更后可重建。 */
        fun newInstance(rule: DnsRule?): RuleEditDialog = RuleEditDialog().apply {
            arguments = bundleOf(
                ARG_RULE to rule?.let { Gson().toJson(it) }
            )
        }

        /** 从结果 Bundle 中取回保存的规则。 */
        fun resultFrom(bundle: Bundle): DnsRule? =
            bundle.getString(ARG_RULE)?.let { json ->
                runCatching { Gson().fromJson(json, DnsRule::class.java) }.getOrNull()
            }
    }
}
