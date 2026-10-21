package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HistoryCollectFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.orhanobut.hawk.Hawk
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

        applyDockVisibility()

        // 主题切换会重启应用，这里恢复到「我的」页，便于用户确认结果
        if (Hawk.get(HawkConfig.THEME_RELAUNCH_TO_MY, false)) {
            Hawk.delete(HawkConfig.THEME_RELAUNCH_TO_MY)
            mBinding.vp.setCurrentItem(pageIndex(R.id.navigation_dashboard), false)
            mBinding.bottomNav.menu.findItem(R.id.navigation_dashboard)?.isChecked = true
        }

        mBinding.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                // 直播是独立 Activity，不占用 ViewPager 页位，
                // 因此这里直接启动并返回 false，避免把底部选中态切走
                R.id.navigation_live -> {
                    ActivityUtils.startActivity(this, LiveActivity::class.java)
                    false
                }
                // 其余项都对应一个 ViewPager 页位，用 pageMenuIds 反查，
                // 不能按 menuItem.order 或写死序号：历史/收藏可隐藏，页位会变
                else -> {
                    val index = pageIndex(menuItem.itemId)
                    if (index >= 0) {
                        mBinding.vp.setCurrentItem(index, false)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                // 按 ViewPager 页位反查 menu item，直播项不在 ViewPager 中，
                // 不能用 getItem(position)（menu 项数与页位数不对齐）
                pageMenuIds.getOrNull(position)?.let { itemId ->
                    mBinding.bottomNav.menu.findItem(itemId)?.isChecked = true
                }
            }
        })
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

    /** 设置里勾选的 dock 入口显隐 */
    private fun applyDockVisibility() {
        val menu = mBinding.bottomNav.menu
        menu.findItem(R.id.navigation_live)?.isVisible =
            Hawk.get(HawkConfig.DOCK_SHOW_LIVE, true)
        menu.findItem(R.id.navigation_history_collect)?.isVisible =
            Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)
    }

    /** 菜单 id -> ViewPager 页位，不存在时返回 -1 */
    private fun pageIndex(menuId: Int): Int = pageMenuIds.indexOf(menuId)

    /**
     * 从 LiveActivity 返回时同步底部选中态。
     *
     * <p>点击直播项时 listener 返回 false，BottomNavigationView 不会自动切回原选中项，
     * 因此返回本 Activity 时高亮可能仍停留在「直播」上，这里主动纠正为当前页。
     */
    override fun onResume() {
        super.onResume()
        pageMenuIds.getOrNull(mBinding.vp.currentItem)?.let { itemId ->
            mBinding.bottomNav.menu.findItem(itemId)?.isChecked = true
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