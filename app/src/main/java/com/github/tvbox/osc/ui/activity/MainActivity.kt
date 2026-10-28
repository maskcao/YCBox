package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ConvertUtils
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

    /** 首页铺满整屏：内容绘制到状态栏/手势条后面，dock 作为悬浮层盖在内容上 */
    override fun isEdgeToEdge(): Boolean = true

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

        // 保留全部页面不销毁：ViewPager 默认 offscreenPageLimit=1，
        // 而首页(0)与「我的」(n-1)相距 2 页时会触发 FragmentPagerAdapter 的 detach，
        // 导致首页 onDestroyView → 切回时重建视图并重新 initData()，海报全部重新加载。
        // 页位最多 3 个（首页/历史收藏/我的），全部常驻的内存代价可接受。
        mBinding.vp.offscreenPageLimit = (fragments.size - 1).coerceAtLeast(1)

        buildDock()
        applyDockWindowInsets()

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
                showDock(false)
                // 新页的列表内容是异步填充的，补一次重录（下一帧生效，不再是定时器）
                mBinding.vp.postDelayed({ mBinding.dockBar.refreshBackdrop() }, 260)
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

    /** 上次构建 dock 时的显隐配置签名；配置未变就不重建 */
    private var dockConfigSig: String? = null

    /** dock 是否已收起：手指上滑收起，下滑弹出（见 handleDockGesture） */
    private var dockHidden = false

    /** 收起：逐渐下移、消失于底部；时长略放宽，弱机上被卡顿时仍能看出位移过程 */
    private val dockEasing = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val dockTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    /** 手指竖向累计位移超过 32dp 才切换显隐，避免轻点或抖动误触 */
    private val dockHideThreshold by lazy { ConvertUtils.dp2px(32f).toFloat() }
    private val dockLocation = IntArray(2)
    private var dockPointerId = -1
    private var dockDownX = 0f
    private var dockDownY = 0f
    private var dockLastY = 0f
    private var dockDragSum = 0f
    private var dockDragging = false
    private var dockTrackTouch = false

    /**
     * dock 悬浮在内容之上：底部只让出手势条高度 + 12dp 间距。
     * 父布局不做系统栏 padding（见 BaseActivity.isEdgeToEdge），避让全靠这里。
     */
    private fun applyDockWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.dockBar) { v, insets ->
            val navInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val lp = v.layoutParams as FrameLayout.LayoutParams
            val bottom = navInset + ConvertUtils.dp2px(12f)
            if (lp.bottomMargin != bottom) {
                lp.bottomMargin = bottom
                v.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(mBinding.dockBar)
    }

    /**
     * 与 NewBox 相同的显隐触发方式：在 Activity 层跟踪竖向手势。
     *
     * <p>手指累计上滑 32dp 收起 dock，累计下滑 32dp 弹出；横向滑动（切换
     * ViewPager）和从 dock 上开始的触摸不参与，避免误触。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleDockGesture(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun handleDockGesture(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dockPointerId = ev.getPointerId(0)
                dockDownX = ev.x
                dockDownY = ev.y
                dockLastY = ev.y
                dockDragSum = 0f
                dockDragging = false
                // 收起状态下滑动一定是在唤出；展开时手指落在 dock 上则不算
                dockTrackTouch = dockHidden || !isInsideDock(ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dockTrackTouch) return
                val index = ev.findPointerIndex(dockPointerId)
                if (index < 0) return
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (!dockDragging) {
                    val dx = kotlin.math.abs(x - dockDownX)
                    val dy = kotlin.math.abs(y - dockDownY)
                    if (dx < dockTouchSlop && dy < dockTouchSlop) return
                    dockDragging = true
                    if (dx > dy) {
                        // 横向是切页手势，交给 ViewPager
                        dockTrackTouch = false
                        return
                    }
                }
                val dy = y - dockLastY
                dockLastY = y
                if (dy == 0f) return
                // 反向滑动时重新累计，不把来回抖动算在一起
                if (dockDragSum != 0f && (dy > 0f) != (dockDragSum > 0f)) {
                    dockDragSum = 0f
                }
                dockDragSum += dy
                when {
                    dockDragSum <= -dockHideThreshold -> {
                        hideDock()
                        dockDragSum = 0f
                    }
                    dockDragSum >= dockHideThreshold -> {
                        showDock()
                        dockDragSum = 0f
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dockTrackTouch = false

            MotionEvent.ACTION_POINTER_UP ->
                if (ev.getPointerId(ev.actionIndex) == dockPointerId) dockTrackTouch = false
        }
    }

    private fun isInsideDock(x: Float, y: Float): Boolean {
        val bar = mBinding.dockBar
        if (bar.width == 0 || bar.height == 0) return false
        bar.getLocationInWindow(dockLocation)
        return x >= dockLocation[0] && x <= dockLocation[0] + bar.width &&
                y >= dockLocation[1] && y <= dockLocation[1] + bar.height
    }

    /** 上滑收起：整条 dock 向下平移自身高度 + 底部间距，滑出屏幕 */
    private fun hideDock() {
        val bar = mBinding.dockBar
        if (dockHidden || !bar.isLaidOut) return
        dockHidden = true
        bar.animate().cancel()
        bar.animate()
            .translationY(dockHideOffset())
            .setDuration(260L)
            .setInterpolator(dockEasing)
            .start()
    }

    /** 下滑弹出；换页时也用它把 dock 复位 */
    private fun showDock(animate: Boolean = true) {
        val bar = mBinding.dockBar
        val atRest = !dockHidden && bar.translationY == 0f
        dockHidden = false
        if (atRest) return
        bar.animate().cancel()
        if (animate) {
            bar.animate()
                .translationY(0f)
                .setDuration(300L)
                .setInterpolator(dockEasing)
                .withEndAction { bar.refreshBackdrop(0) }
                .start()
        } else {
            bar.translationY = 0f
            bar.refreshBackdrop(40)
        }
    }

    /** 收起的位移 = 底部间距（手势条 inset + 12dp）+ dock 自身高度，与 NewBox 一致 */
    private fun dockHideOffset(): Float {
        val bar = mBinding.dockBar
        val gap = (bar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin
            ?: ConvertUtils.dp2px(12f)
        val height = if (bar.height > 0) bar.height else ConvertUtils.dp2px(56f)
        return (gap + height).toFloat()
    }

    /** 依据设置里的显隐开关构建自绘 dock（首页/我的固定显示） */
    private fun buildDock() {
        val showLive = Hawk.get(HawkConfig.DOCK_SHOW_LIVE, true)
        val showHistory = Hawk.get(HawkConfig.DOCK_SHOW_HISTORY, true)
        val sig = "$showLive/$showHistory"
        // 显隐配置没变时只同步选中态。
        // 旧实现每次 onResume 都 removeAllViews + 重新 inflate，而 onResume 时窗口已经可见，
        // 这个"先清空再填满"的空档会被看到一帧，表现就是底部导航栏闪一下。
        if (sig == dockConfigSig && dockViews.isNotEmpty()) {
            selectDock(currentDockId())
            return
        }
        dockConfigSig = sig

        val bar = mBinding.dockBar
        bar.removeAllViews()
        dockViews.clear()
        val entries = listOf(
            DockEntry(R.id.navigation_home, R.drawable.nav_ic_home, "首页", true),
            DockEntry(R.id.navigation_live, R.drawable.ic_live_nav, "直播", false),
            DockEntry(R.id.navigation_history_collect, R.drawable.nav_ic_history, "历史/收藏", showHistory),
            DockEntry(R.id.navigation_dashboard, R.drawable.nav_ic_my, "我的", true)
        )
        val inflater = android.view.LayoutInflater.from(this)
        for (e in entries) {
            val visible = when (e.id) {
                R.id.navigation_live -> showLive
                R.id.navigation_history_collect -> showHistory
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

    /**
     * 选中指定 dock 条目：橙色圆角方块 + 白色图标 + 橙色标签，其余为正文色。
     *
     * <p>只改 selected 状态，具体配色交给资源里的 selector：
     * 图标 tint = res/color/dock_icon_tint，图标底 = res/drawable/bg_dock_tile_selector，
     * 标签色 = res/color/dock_label_color，三者都按 state_selected 解析。
     *
     * <p>这样 inflate 出来的第一帧就是最终配色。旧实现是"XML 不写颜色 + 代码逐个
     * setColorFilter/setTextColor"，首帧会露出矢量的原始白色（叠在 #E3E3E3 药丸上几乎看不见），
     * 下一帧才被染成正文色，表现为刚进软件 / 切主题时导航栏闪一下。
     */
    private fun selectDock(id: Int) {
        for ((entryId, view) in dockViews) {
            view.isSelected = entryId == id
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
        showDock(false)
        mBinding.dockBar.refreshBackdrop(200)
    }

    /** 壁纸被换掉/删除时由「我的」页发事件过来，立刻刷新全局背景 */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onWallpaperChanged(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_WALLPAPER) {
            refreshWallpaper()
            // 壁纸图是异步解码的，隔一会儿再取一次玻璃底图
            mBinding.dockBar.refreshBackdrop(300)
            mBinding.dockBar.refreshBackdrop(1200)
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