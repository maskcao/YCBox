package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import com.angcyo.tablayout.delegate.ViewPager1Delegate.Companion.install
import com.blankj.utilcode.util.ConvertUtils
import com.blankj.utilcode.util.ScreenUtils
import com.blankj.utilcode.util.ToastUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.ApiConfig.LoadConfigCallback
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.base.BaseLazyFragment
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.MovieSort.SortData
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.FragmentHomeBinding
import com.github.tvbox.osc.server.ControlManager
import com.github.tvbox.osc.ui.activity.CollectActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.activity.HistoryActivity
import com.github.tvbox.osc.ui.activity.MainActivity
import com.github.tvbox.osc.ui.activity.SubscriptionActivity
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface
import com.github.tvbox.osc.ui.dialog.LastViewedDialog
import com.github.tvbox.osc.ui.dialog.BottomPickDialog
import com.github.tvbox.osc.ui.dialog.SubscriptionPickDialog
import com.github.tvbox.osc.ui.dialog.TipDialog
import com.github.tvbox.osc.ui.widget.GlassBackdrop
import com.github.tvbox.osc.util.BuiltinConfig
import com.github.tvbox.osc.util.DefaultConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HomeCache
import com.github.tvbox.osc.util.LiveSubscriptionStore
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.lxj.xpopup.XPopup
import com.orhanobut.hawk.Hawk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : BaseVbFragment<FragmentHomeBinding>() {

    /**
     * 提供给主页返回操作
     */
    val tabIndex: Int
        get() = mBinding.tabLayout.currentItemIndex

    /**
     * 提供给主页返回操作
     */
    val allFragments: List<BaseLazyFragment>
        get() = fragments

    private var sourceViewModel: SourceViewModel? = null
    private val fragments: MutableList<BaseLazyFragment> = ArrayList()
    private val mHandler = Handler()

    /**
     * 顶部tabs分类集合,用于渲染tab页,每个tab对应fragment内的数据
     */
    private var mSortDataList: List<SortData> = ArrayList()
    private var dataInitOk = false
    private var jarInitOk = false

    var errorTipDialog: TipDialog? = null

    /**
     * true: 配置变更重载
     * false: 全部重载(api变更、重启app等)
     */
    var onlyConfigChanged = false

    override fun init() {
        ControlManager.get().startServer()
        mBinding.subContainer.setOnClickListener {
            showSubscriptionSwitch()
        }
        mBinding.nameContainer.setOnClickListener {
            if (dataInitOk && jarInitOk) {
                showSiteSwitch()
            } else {
                ToastUtils.showShort("数据源未加载，长按刷新或切换订阅")
            }
        }
        mBinding.nameContainer.setOnLongClickListener {
            refreshHomeSources()
            true
        }
        mBinding.search.setOnClickListener {
            jumpActivity(FastSearchActivity::class.java)
        }
        // 四枚顶栏胶囊换成实时磨砂（NewBox 的 GlassCapsule 每枚都自己模糊背后内容）
        GlassBackdrop.installCapsule(mBinding.subContainer, 16f)
        GlassBackdrop.installCapsule(mBinding.nameContainer, 16f)
        GlassBackdrop.installCapsule(mBinding.search, 16f)
        GlassBackdrop.installCapsule(mBinding.tabGlassBar, 16f)
        setLoadSir(mBinding.contentLayout)
        initViewModel()
        // 主题切换会重建界面并停在「我的」页，此时首页不可见：
        // 先不阻塞界面，稍后在后台预热数据；用户切回首页时通常已就绪。
        if (com.github.tvbox.osc.util.ThemeHelper.consumeSkipHomeFirstLoad()) {
            mPendingFirstLoad = true
            // 预热延迟：仅等首帧渲染完成即开始拉数据（300ms 足够界面稳定，
            // 再长就会让用户切回首页时还在等待）
            mBinding.root.postDelayed({
                if (mPendingFirstLoad && isAdded) {
                    mPendingFirstLoad = false
                    initData()
                }
            }, 300)
        } else {
            initData()
        }
    }

    /** 主题切换重建后暂缓的首页首次加载标记 */
    private var mPendingFirstLoad = false

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        if (isVisibleToUser && mPendingFirstLoad) {
            mPendingFirstLoad = false
            initData()
        }
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel?.sortResult?.observe(this) { absXml: AbsSortXml? ->
            // 写入内存缓存，供主题切换重建后立即渲染
            HomeCache.setSource(ApiConfig.get().homeSourceBean.key)
            if (absXml == null) {
                // 网络失败时 sortResult 推 null。已经渲染过分类就保持现状：
                // 否则 tab 栏会被重置成只剩「主页」，缓存的推荐位也一起丢掉。
                if (mSortDataList.isEmpty()) {
                    mSortDataList = DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
                    initViewPager(null)
                }
                showSuccess()
                retrySort()
                return@observe
            }
            mSortRetries = 0
            HomeCache.putSort(absXml)
            mSortDataList =
                if (absXml.classes != null && absXml.classes.sortList != null) {
                    DefaultConfig.adjustSort(
                        ApiConfig.get().homeSourceBean.key,
                        absXml.classes.sortList,
                        true
                    )
                } else {
                    DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
                }
            initViewPager(absXml)
            showSuccess()
        }
    }

    /** 分类拉取失败次数 / 上限：网络抖动后自动再拉，恢复后 tab 栏能自己长回来 */
    private var mSortRetries = 0

    private fun retrySort() {
        if (mSortRetries >= 2) return
        val delay = 3000L * (mSortRetries + 1)
        mSortRetries++
        mBinding.root.postDelayed({
            if (isAdded) sourceViewModel?.getSort(ApiConfig.get().homeSourceBean.key)
        }, delay)
    }

    private fun initData() {
        val mainActivity = mActivity as MainActivity
        onlyConfigChanged = mainActivity.useCacheConfig

        val home = ApiConfig.get().homeSourceBean
        if (home != null && !home.name.isNullOrEmpty()) {
            // 顶栏原样显示配置里的源名，和切源面板里那一条逐字对得上。
            // （之前会剥掉两头的 🐮 装饰，看着像少了一段。）
            mBinding.tvName.text = home.name
            mBinding.tvName.postDelayed({ mBinding.tvName.isSelected = true }, 2000)
        }
        loadSourceLogo()
        updateSubscriptionLabel()

        // 主题切换重建后：先用上次缓存的分类立即渲染，再走网络静默刷新
        var renderedFromCache = false
        val cachedSort = HomeCache.getSort()
        if (cachedSort != null && mSortDataList.isEmpty()) {
            HomeCache.setSource(home?.key)
            mSortDataList = if (cachedSort.classes != null && cachedSort.classes.sortList != null) {
                DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, cachedSort.classes.sortList, true)
            } else {
                DefaultConfig.adjustSort(ApiConfig.get().homeSourceBean.key, ArrayList(), true)
            }
            if (mSortDataList.isNotEmpty()) {
                initViewPager(cachedSort)
                renderedFromCache = true
            }
        }

        // 命中缓存时不要切 loading，否则会把刚渲染的内容盖住
        if (!renderedFromCache) {
            showLoading()
        }
        when{
            dataInitOk && jarInitOk -> {
                //正常初始化会先加载,最终到这,此时数据有以下几种情况
                // 1. api/jar/spider等均加载完,正常显示数据。2. 缺失spider(存疑?)/api配置有问题同样加载(最后空布局 或 只有豆瓣首页)
                sourceViewModel?.getSort(ApiConfig.get().homeSourceBean.key)
            }
            dataInitOk && !jarInitOk -> {
                loadJar()
            }
            else -> {
                loadConfig()
            }
        }
    }

    /**
     * 顶栏「订阅」胶囊显示当前生效的订阅名（原来是固定的"订阅"二字，看不出用的是哪份配置）。
     *
     * <p>名字可能来自两种途径：订阅列表里那条记录，或列表里还没有内置源时按地址识别。
     * 都对不上（手填地址、老配置）就退回"订阅"，不留空胶囊。
     *
     * <p>和切换面板里那条一样原样显示，不做任何裁剪，方便一眼核对当前用的是哪份配置。
     */
    private fun updateSubscriptionLabel() {
        val url = Hawk.get(HawkConfig.API_URL, "")
        val subs: MutableList<Subscription> = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList())
        val name = subs.firstOrNull { it.url == url }?.name
            ?: if (BuiltinConfig.isBuiltin(url)) BuiltinConfig.DEFAULT_NAME else null
        mBinding.subContainer.text = if (name.isNullOrEmpty()) "订阅" else name
    }

    /**
     * 显示订阅配置里声明的 logo（首页左上角"播放源头像"）。
     *
     * <p>配置没写 logo 时整块 GONE —— 留一个 26dp 的空洞比不显示更难看。
     *
     * <p>用 asBitmap 只取首帧：源上给的多半是 gif（当前这个 320x320、1.2MB、上百帧），
     * 在 26dp 的坑位里循环播放既费电、又抢视线，静态首帧观感反而更稳。
     *
     * <p>用圆角而非正圆：部分源的 logo 是带文字的方形图，正圆会把四角文字切掉。
     */
    private fun loadSourceLogo() {
        val logo = ApiConfig.get().logo
        if (logo.isNullOrEmpty()) {
            mBinding.ivSourceLogo.visibility = View.GONE
            return
        }
        // 先占位显示，加载失败再撤掉，避免闪一下又空出来
        mBinding.ivSourceLogo.visibility = View.VISIBLE
        Glide.with(this)
            .asBitmap()
            .load(logo)
            .apply(
                RequestOptions()
                    .transform(RoundedCorners(ConvertUtils.dp2px(8f)))
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            )
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Bitmap>,
                    isFirstResource: Boolean
                ): Boolean {
                    mBinding.ivSourceLogo.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Bitmap,
                    model: Any,
                    target: Target<Bitmap>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(mBinding.ivSourceLogo)
    }

    private fun loadConfig(){
        ApiConfig.get().loadConfig(onlyConfigChanged, object : LoadConfigCallback {

            override fun retry() {
                mHandler.post { initData() }
            }

            override fun success() {
                dataInitOk = true
                if (ApiConfig.get().spider.isEmpty()) {
                    jarInitOk = true
                }
                mHandler.postDelayed({ initData() }, 50)
            }

            override fun error(msg: String) {
                if (msg.equals("-1", ignoreCase = true)) {
                    mHandler.post {
                        dataInitOk = true
                        jarInitOk = true
                        initData()
                    }
                } else {
                    showTipDialog(msg)
                }
            }
        }, activity)
    }

    private fun loadJar(){
        if (!ApiConfig.get().spider.isNullOrEmpty()) {
            ApiConfig.get().loadJar(
                onlyConfigChanged,
                ApiConfig.get().spider,
                object : LoadConfigCallback {
                    override fun success() {
                        jarInitOk = true
                        mHandler.postDelayed({
                            if (!onlyConfigChanged) {
                                queryHistory()
                            }
                            initData()
                        }, 50)
                    }

                    override fun retry() {}
                    override fun error(msg: String) {
                        jarInitOk = true
                        mHandler.post {
                            ToastUtils.showShort("更新订阅失败")
                            initData()
                        }
                    }
                })
        }
    }

    private fun showTipDialog(msg: String) {
        // 拉配置是异步的，error 最晚要等 10s 超时回来才走到这里。这期间用户切走 tab、
        // 息屏、或系统重建界面，fragment 已经脱离 activity：此时 requireActivity() 抛
        // IllegalStateException，把弹窗 show 到已销毁的 window 上抛 BadTokenException，
        // 表现就是"订阅加载失败之后过一会儿闪退"。
        val act = activity ?: return
        if (isDetached || act.isFinishing || act.isDestroyed) return
        if (errorTipDialog == null) {
            errorTipDialog =
                TipDialog(act, msg, "重试", "取消", object : TipDialog.OnListener {
                    override fun left() {
                        reloadFromDialog(false)
                    }

                    override fun right() {
                        reloadFromDialog(true)
                    }

                    override fun cancel() {
                        reloadFromDialog(true)
                    }

                    override fun onTitleClick() {
                        errorTipDialog?.hide()
                        jumpActivity(SubscriptionActivity::class.java)
                    }
                })
        }
        val dialog = errorTipDialog ?: return
        if (!dialog.isShowing) dialog.show()
    }

    /**
     * 弹窗三个按钮最终都是「关掉弹窗 + 重新走一遍加载」。
     *
     * <p>markInited 对应原来「取消/换源」那两条：先把两个 init 标记置上，
     * initData 才会跳过等回调的分支、直接重拉。
     *
     * <p>post 里再过一次 attach：弹窗比 fragment 活得久的情况（旋转、系统回收）真实存在，
     * 那时 initData() 摸 mBinding 就是 NPE。
     */
    private fun reloadFromDialog(markInited: Boolean) {
        mHandler.post {
            errorTipDialog?.hide()
            if (!isAdded || view == null) return@post
            if (markInited) {
                dataInitOk = true
                jarInitOk = true
            }
            initData()
        }
    }

    private fun getTabTextView(text: String): TextView {
        val textView = TextView(mContext)
        textView.text = text
        textView.gravity = Gravity.CENTER
        textView.setPadding(
            ConvertUtils.dp2px(14f),
            0,
            ConvertUtils.dp2px(14f),
            0
        )
        return textView
    }

    private fun initViewPager(absXml: AbsSortXml?) {
        if (mSortDataList.isNotEmpty()) {
            mBinding.tabLayout.removeAllViews()
            fragments.clear()
            for (data in mSortDataList) {
                mBinding.tabLayout.addView(getTabTextView(data.name))
                if (data.id == "my0") { //tab是主页：站点推荐(源有推荐数据时常驻) + 豆瓣分区
                    if (absXml != null && absXml.videoList != null && absXml.videoList.size > 0) { //站点推荐
                        fragments.add(UserFragment.newInstance(absXml.videoList))
                    } else { //无推荐数据：仅豆瓣分区
                        fragments.add(UserFragment.newInstance(null))
                    }
                } else { //来自源的分类
                    fragments.add(GridFragment.newInstance(data))
                }
            }
            if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) { //关闭主页
                mBinding.tabLayout.removeViewAt(0)
                fragments.removeAt(0)
            }

            //重新渲染vp
            mBinding.mViewPager.adapter =
                object : FragmentStatePagerAdapter(getChildFragmentManager()) {
                    override fun getItem(position: Int): Fragment {
                        return fragments[position]
                    }

                    override fun getCount(): Int {
                        return fragments.size
                    }
                }
            //tab和vp绑定。第三个参数是 forceSmoothScroll：开着时点任意 tab 都会逐页滚过去，
            //而主页那一页是整屏的轮播大图、分类页又是透明底，滚过的路上等于把轮播图
            //铺到了其他分类页上（看着就像别的 tab 也在放轮播）。所以这里要 false：点 tab 直接切。
            install(mBinding.mViewPager, mBinding.tabLayout, false)
        }
    }

    /**
     * 提供给主页返回操作
     */
    fun scrollToFirstTab(): Boolean {
        return if (mBinding.tabLayout.currentItemIndex != 0) {
            mBinding.mViewPager.setCurrentItem(0, false)
            true
        } else {
            false
        }
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 首页数据源（站点）切换：底部弹出面板。
     */
    private fun showSiteSwitch() {
        val sites = ApiConfig.get().sourceBeanList
        if (sites.size > 0) {
            showPickDialog(
                "请选择首页数据源",
                sites,
                sites.indexOf(ApiConfig.get().homeSourceBean),
                2,
                null, null,
                { source ->
                    applyHomeSource(source)
                },
                { source -> source?.name ?: "" }
            )
        } else {
            ToastUtils.showLong("暂无可用数据源")
        }
    }

    /**
     * 换首页数据源：只重刷首页，不重启 Activity。
     *
     * <p>订阅配置（sites / jar / lives）本来就在内存里，换源只是改「哪个站点当首页」，
     * 再走一遍 loadConfig + loadJar 等于把整份订阅重新加载一遍：白等几秒，还会闪一下加载遮罩。
     * 这里只把新源的分类重新拉一次 —— 数据回来后 {@link #initViewPager} 会重建 tab 和页面，
     * 直播 / 历史收藏 / 我的三页与首页数据源无关，保持原样不动。
     */
    private fun applyHomeSource(source: SourceBean) {
        ApiConfig.get().setSourceBean(source)
        mSortRetries = 0
        initData()
    }

    /**
     * 订阅切换：底部弹出面板，顶部「配置订阅 / 直播订阅」分段切换。
     *
     * <p>面板和列表条目都复用订阅管理页那一套（SubscriptionAdapter），只是把删除按钮收起来：
     * 这里只做「选」，增删改、重命名、置顶仍回订阅管理页做。
     *
     * <p>两类订阅各有各的生效项（配置订阅看 API_URL，直播订阅看 LIVE_URL），混在一个列表里
     * 容易点错 —— 切直播源顺手把片源也换掉，所以分成两段各选各的。
     *
     * <p>内置源可能还不在列表里（老版本升级上来、或从没进过订阅管理页），这里补一条，
     * 免得面板是空的、点上去反而跳走了整页。生效地址不在列表里时也不跳走：
     * 让面板照常出现，只是暂时没有选中项，用户点哪条就切到哪条。
     */
    private fun showSubscriptionSwitch() {
        val stored: MutableList<Subscription> = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList())
        val currentUrl = Hawk.get(HawkConfig.API_URL, "")
        val subs = ArrayList<Subscription>(stored)
        if (subs.none { BuiltinConfig.isBuiltin(it.url) }) {
            val builtin = Subscription(BuiltinConfig.DEFAULT_NAME, BuiltinConfig.DEFAULT_URL)
            builtin.isTop = true
            builtin.setChecked(stored.none { it.isChecked } || currentUrl == builtin.url)
            subs.add(0, builtin)
        }
        val lives = ArrayList(LiveSubscriptionStore.load())
        // 直播订阅的选中态由当前生效的 LIVE_URL 决定，不额外存一份，避免两处不一致
        val currentLiveUrl = Hawk.get(HawkConfig.LIVE_URL, "")
        for (item in lives) {
            item.setChecked(item.url == currentLiveUrl)
        }
        XPopup.Builder(mActivity)
            .asCustom(
                SubscriptionPickDialog(requireActivity(), subs, lives)
                    .setOnPick { item, live ->
                        if (live) applyLiveSubscription(item) else applySubscription(item, subs)
                    }
            )
            .show()
    }

    /** 面板里点中某条订阅：改选中态并落盘，再重载首页让新配置生效 */
    private fun applySubscription(picked: Subscription?, subs: MutableList<Subscription>) {
        val url = picked?.url ?: return
        for (item in subs) {
            item.setChecked(item.url == url)
        }
        Hawk.put(HawkConfig.API_URL, url)
        Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, subs)
        refreshHomeSources()
    }

    /**
     * 面板里点中某个直播源：只换 LIVE_URL，不动配置订阅。
     *
     * <p>内存里那条 live 地址是加载配置时对 LIVE_URL 的快照，不刷新的话下一次开播还是旧源，
     * 所以补一次 {@link ApiConfig#syncLiveUrl}；直播是独立 Activity，回不进面板这条链路，
     * 用户下次点直播就是新源。
     */
    private fun applyLiveSubscription(picked: Subscription) {
        Hawk.put(HawkConfig.LIVE_URL, picked.url)
        ApiConfig.get().syncLiveUrl()
        ToastUtils.showShort("已切换到该直播源")
    }

    private fun <T> showPickDialog(
        title: String,
        data: List<T>,
        select: Int,
        spanCount: Int,
        manageLabel: String?,
        onManage: Runnable?,
        onPick: (T) -> Unit,
        display: (T?) -> String
    ) {
        val dialog = BottomPickDialog(
            requireActivity(),
            title,
            object : SelectDialogInterface<T> {
                override fun click(value: T?, pos: Int) {}

                override fun getDisplay(value: T?): String = display(value)
            },
            object : DiffUtil.ItemCallback<T>() {
                override fun areItemsTheSame(oldItem: T, newItem: T): Boolean =
                    oldItem === newItem

                override fun areContentsTheSame(oldItem: T, newItem: T): Boolean =
                    oldItem == newItem
            },
            data, select, spanCount, manageLabel, onManage
        ).setOnPick { value, _ -> onPick(value) }
        XPopup.Builder(mActivity)
            .asCustom(dialog)
            .show()
    }

    private fun refreshHomeSources() {
        val intent = Intent(App.getInstance(), MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val bundle = Bundle()
        bundle.putBoolean(IntentKey.CACHE_CONFIG_CHANGED, true)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        ControlManager.get().stopServer()
    }

    private fun queryHistory() {
        lifecycleScope.launch {
            val vodInfoList = withContext(Dispatchers.IO) {
                val allVodRecord = RoomDataManger.getAllVodRecord(100)
                val vodInfoList: MutableList<VodInfo?> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) vodInfo.note =
                        vodInfo.playNote
                    vodInfoList.add(vodInfo)
                }
                vodInfoList
            }

            // 查询完成后更新UI
            if (vodInfoList.isNotEmpty() && vodInfoList[0] != null) {
                // 位置固定紧贴悬浮 dock 上方（无缝隙），弹窗高/宽与 dock 一致（高 76dp，宽=屏宽-2×14dp）：
                // offsetY = 屏高 - 导航栏避让 - (dock 底边距 10dp + dock 56dp) - 弹窗高 76dp
                val density = resources.displayMetrics.density
                val decor = requireActivity().window.decorView
                val insets = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets)
                val navInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                val offsetY = (ScreenUtils.getAppScreenHeight() - navInset -
                        (10 + 56 + 76) * density).toInt()
                XPopup.Builder(context)
                    .hasShadowBg(false)
                    .isDestroyOnDismiss(true)
                    .isCenterHorizontal(true)
                    .isTouchThrough(true)
                    .offsetY(offsetY)
                    .asCustom(LastViewedDialog(requireContext(), vodInfoList[0]))
                    .show()
                    .delayDismiss(4000)
            }
        }
    }
}