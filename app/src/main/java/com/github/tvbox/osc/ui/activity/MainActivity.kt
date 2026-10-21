package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HistoryCollectFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.orhanobut.hawk.Hawk
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    /** ViewPager 页面，随底部导航栏勾选动态增减 */
    var fragments: List<Fragment> = emptyList()
    /** 与 fragments 一一对应的菜单 id：页位 -> menu item id */
    private var pageMenuIds: List<Int> = emptyList()
    var useCacheConfig = false
    private var exitTime = 0L

    override fun init() {

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false)?:false

        buildPages()

        mBinding.vp.adapter = object : FragmentPagerAdapter(supportFragmentManager) {
            override fun getItem(position: Int): Fragment {
                return fragments[position]
            }

            override fun getCount(): Int {
                return fragments.size
            }
        }

        buildDock()

        // 主题切换会重启应用，这里恢复到「我的」页，便于用户确认结果
        if (Hawk.get(HawkConfig.THEME_RELAUNCH_TO_MY, false)) {
            Hawk.delete(HawkConfig.THEME_RELAUNCH_TO_MY)
            mBinding.vp.setCurrentItem(pageIndex(R.id.navigation_dashboard), false)
        }

        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                // 按 ViewPager 页位反查 dock 条目，直播项不在 ViewPager 中，
                // 不能用 getItem(position)（条目数与页位数不对齐）
                pageMenuIds.getOrNull(position)?.let { selectDock(it) }
            }
        })
    }

    /** dock 条目定义（id 与 ViewPager 页位反查沿用 navigation_* 常量） */
    private data class DockEntry(
        val id: Int,
        val iconRes: Int,
        val label: String,
        val isPage: Boolean
    )

    private val dockViews = mutableMapOf<Int, View>()

    /** 依据设置里的显隐开关构建自绘 dock（首页/我的固定显示） */
    private fun buildDock() {
        val bar = mBinding.dockBar
        bar.removeAllViews()
        dockViews.clear()
        val entries = listOf(
            DockEntry(R.id.navigation_home, R.drawable.nav_ic_home, "首页", true),
            DockEntry(R.id.navigation_live, R.drawable.ic_live_nav, "直播", false),
            DockEntry(
                R.id.navigation_history_collect,
                R.drawable.nav_ic_history,
                "历史/收藏",
                Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)
            ),
            DockEntry(R.id.navigation_dashboard, R.drawable.nav_ic_my, "我的", true)
        )
        val inflater = android.view.LayoutInflater.from(this)
        for (e in entries) {
            val visible = when (e.id) {
                R.id.navigation_live -> Hawk.get(HawkConfig.DOCK_SHOW_LIVE, true)
                R.id.navigation_history_collect -> Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)
                else -> true
            }
            if (!visible) continue
            val v = inflater.inflate(R.layout.view_dock_item, bar, false)
            v.findViewById<ImageView>(R.id.dock_icon).setImageResource(e.iconRes)
            v.findViewById<TextView>(R.id.dock_label).text = e.label
            v.setOnClickListener { onDockClicked(e) }
            bar.addView(v)
            dockViews[e.id] = v
        }
        selectDock(currentDockId())
    }

    /** 当前应选中的 dock 条目：按 ViewPager 页位反查；异常情况兜底到首页 */
    private fun currentDockId(): Int =
        pageMenuIds.getOrNull(mBinding.vp.currentItem) ?: R.id.navigation_home

    private fun onDockClicked(e: DockEntry) {
        if (e.isPage) {
            val index = pageIndex(e.id)
            if (index >= 0) mBinding.vp.setCurrentItem(index, false)
            selectDock(e.id)
        } else {
            // 直播是独立 Activity，不改变 dock 选中态
            ActivityUtils.startActivity(this, LiveActivity::class.java)
        }
    }

    /** 选中指定 dock 条目：橙色圆角方块 + 白色图标 + 橙色标签，其余为正文色 */
    private fun selectDock(id: Int) {
        for ((entryId, view) in dockViews) {
            val checked = entryId == id
            val icon = view.findViewById<ImageView>(R.id.dock_icon)
            val label = view.findViewById<TextView>(R.id.dock_label)
            icon.setBackgroundResource(if (checked) R.drawable.bg_dock_tile else 0)
            // 图标矢量是白色填充，未选中时染成正文色保证浅色药丸上的对比度
            icon.setColorFilter(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (checked) android.R.color.white else R.color.text_foreground
                )
            )
            label.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this, if (checked) R.color.dock_accent else R.color.text_foreground
                )
            )
        }
    }

    /**
     * 依据底部导航栏勾选构建 ViewPager 页面。
     * 首页与「我的」固定存在，历史/收藏隐藏时该页直接从 ViewPager 中移除。
     */
    private fun buildPages() {
        val frags = ArrayList<Fragment>()
        val ids = ArrayList<Int>()
        frags.add(HomeFragment())
        ids.add(R.id.navigation_home)
        if (Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)) {
            frags.add(HistoryCollectFragment())
            ids.add(R.id.navigation_history_collect)
        }
        frags.add(MyFragment())
        ids.add(R.id.navigation_dashboard)
        fragments = frags
        pageMenuIds = ids
    }

    /** dock 条目显隐有变化时重建（设置里开关直播/历史收藏入口） */
    private fun applyDockVisibility() {
        buildDock()
    }

    /** 菜单 id -> ViewPager 页位，不存在时返回 -1 */
    private fun pageIndex(menuId: Int): Int = pageMenuIds.indexOf(menuId)

    /**
     * 从 LiveActivity 返回时同步底部选中态（直播不改变选中项）。
     */
    override fun onResume() {
        super.onResume()
        buildDock()
    }

    /** 壁纸被换掉/删除时由「我的」页发事件过来，立刻刷新全局背景 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onWallpaperChanged(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_WALLPAPER) {
            refreshWallpaper()
        }
    }

    override fun onBackPressed() {
        // 不在首页时先回到首页（历史收藏/我的都在 ViewPager 中）
        if (mBinding.vp.currentItem != 0) {
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}