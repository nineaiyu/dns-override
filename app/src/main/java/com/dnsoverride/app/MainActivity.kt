package com.dnsoverride.app

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentTransaction
import com.dnsoverride.app.R
import com.dnsoverride.app.databinding.ActivityMainBinding
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.service.SubscriptionWorker
import com.dnsoverride.app.ui.home.HomeFragment
import com.dnsoverride.app.ui.rules.RulesFragment
import com.dnsoverride.app.ui.settings.SettingsFragment
import com.dnsoverride.app.ui.stats.StatsFragment

/**
 * 宿主 Activity：底部导航栏 + Fragment 容器。
 *
 * 四个 Tab：
 * - 首页（[HomeFragment]）：VPN 状态卡片 + 所有规则组扁平列表
 * - 规则（[RulesFragment]）：当前激活规则组的 CRUD + 导入导出
 * - 统计（[StatsFragment]）：统计卡片 + Top 10 + 最近查询日志
 * - 设置（[SettingsFragment]）：上游 DNS / 缓存 / 主题等
 *
 * Fragment 使用 show/hide 切换，保留各 Tab 的滚动位置与表单状态。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTabId: Int = R.id.nav_home
    private var menuClickListener: ((android.view.MenuItem) -> Boolean)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService(ACTION_START)
        } else {
            Toast.makeText(this, getString(R.string.vpn_permission_denied_short), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式边到边：内容延伸至状态栏/导航栏后方，由 insets 补偿安全区
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 系统栏 insets：顶栏下移避让状态栏，悬浮 Dock 避让手势导航条
        val dockBottomMargin = resources.getDimensionPixelSize(R.dimen.space_sm)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topAppBar.updatePadding(top = sysBars.top)
            binding.navRail.updatePadding(top = sysBars.top)
            binding.bottomNav.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = dockBottomMargin + sysBars.bottom
            }
            insets
        }

        // 注册每日订阅自动更新（即使 VPN 未运行也生效），仅在设置开启时执行
        SubscriptionWorker.schedule(this)

        if (savedInstanceState == null) {
            // 首次添加所有 Fragment，全部隐藏后只显示首页
            // 注意：hide 必须传入 add 时的同一个实例，否则 hide 的是未被添加的新实例
            val home = HomeFragment()
            val rules = RulesFragment()
            val stats = StatsFragment()
            val settings = SettingsFragment()
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragment_container, home, TAG_HOME)
                add(R.id.fragment_container, rules, TAG_RULES).hide(rules)
                add(R.id.fragment_container, stats, TAG_STATS).hide(stats)
                add(R.id.fragment_container, settings, TAG_SETTINGS).hide(settings)
            }.commit()
            currentTabId = R.id.nav_home
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        // 大屏（横屏/平板）使用 NavigationRail，窄屏该视图不可见但存在
        binding.navRail.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }

        // 顶部应用栏：品牌标题随 Tab 切换；菜单事件委托给当前 Fragment 注册的回调
        binding.topAppBar.setTitle(R.string.app_name)
        binding.topAppBar.setOnMenuItemClickListener { item ->
            menuClickListener?.invoke(item) ?: false
        }
        updateAppBarTitle(R.id.nav_home)
        // 首页不显示任何功能菜单
        hideRulesMenu()

        // Quick Settings Tile 跳转过来时携带 EXTRA_REQUEST_VPN
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_VPN)
            startVpnFromTile()
        }
    }

    /** 公开：供子 Fragment 触发 Tab 切换（如首页"规则"按钮）。 */
    fun switchToTab(menuItemId: Int) {
        switchTab(menuItemId)
    }

    /** 公开：让规则页 Fragment 注册其菜单点击回调（菜单的显示/隐藏由本类按 Tab 控制）。 */
    fun registerMenuCallback(listener: ((android.view.MenuItem) -> Boolean)?) {
        menuClickListener = listener
    }

    /** 在顶部应用栏显示规则页功能菜单。 */
    fun showRulesMenu() {
        binding.topAppBar.menu.clear()
        binding.topAppBar.inflateMenu(R.menu.rules_menu)
    }

    /** 隐藏顶部应用栏的功能菜单。 */
    fun hideRulesMenu() {
        binding.topAppBar.menu.clear()
    }

    /** 切换 Tab：隐藏当前 Fragment，显示目标 Fragment，并带轻量转场。 */
    private fun switchTab(menuItemId: Int) {
        if (menuItemId == currentTabId) return
        val targetTag = tagFor(menuItemId)
        val target = supportFragmentManager.findFragmentByTag(targetTag) ?: return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fragment_fade_in,
                R.anim.fragment_fade_out,
                R.anim.fragment_fade_in,
                R.anim.fragment_fade_out
            )
            .hide(supportFragmentManager.findFragmentByTag(tagFor(currentTabId))!!)
            .show(target)
            .commit()
        currentTabId = menuItemId
        updateAppBarTitle(menuItemId)
        // 仅规则页显示功能菜单
        if (menuItemId == R.id.nav_rules) showRulesMenu() else hideRulesMenu()
        // 同步两个导航视图的选中态
        if (binding.navRail.selectedItemId != menuItemId) {
            binding.navRail.selectedItemId = menuItemId
        }
        if (binding.bottomNav.selectedItemId != menuItemId) {
            binding.bottomNav.selectedItemId = menuItemId
        }
    }

    private fun updateAppBarTitle(menuItemId: Int) {
        val title = when (menuItemId) {
            R.id.nav_rules -> R.string.tab_rules
            R.id.nav_stats -> R.string.tab_stats
            R.id.nav_settings -> R.string.tab_settings
            else -> R.string.app_name
        }
        binding.topAppBar.setTitle(title)
    }

    private fun tagFor(menuItemId: Int): String = when (menuItemId) {
        R.id.nav_home -> TAG_HOME
        R.id.nav_rules -> TAG_RULES
        R.id.nav_stats -> TAG_STATS
        R.id.nav_settings -> TAG_SETTINGS
        else -> TAG_HOME
    }

    private fun startVpnFromTile() {
        val prep = VpnService.prepare(this)
        if (prep != null) {
            vpnPermissionLauncher.launch(prep)
        } else {
            startVpnService(ACTION_START)
        }
    }

    private fun startVpnService(action: String) {
        val intent = Intent(this, DnsVpnService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == ACTION_START) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        const val EXTRA_REQUEST_VPN = "request_vpn"

        private const val ACTION_START = "com.dnsoverride.app.START"
        private const val TAG_HOME = "home"
        private const val TAG_RULES = "rules"
        private const val TAG_STATS = "stats"
        private const val TAG_SETTINGS = "settings"
    }
}
