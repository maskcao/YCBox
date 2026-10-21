package com.github.tvbox.osc.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseVbFragment
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.cache.VodCollect
import com.github.tvbox.osc.databinding.FragmentHistoryCollectBinding
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.activity.DetailActivity
import com.github.tvbox.osc.ui.activity.FastSearchActivity
import com.github.tvbox.osc.ui.adapter.CollectAdapter
import com.github.tvbox.osc.ui.adapter.HistoryAdapter
import com.github.tvbox.osc.util.FastClickCheckUtil
import com.github.tvbox.osc.util.Utils
import com.lxj.xpopup.XPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

/**
 * 历史与收藏合并页（底部 dock 的第三个页面，与「首页」「我的」同级）。
 *
 * <p>用 TabLayout 在两个数据集之间切换，复用原有的 HistoryAdapter / CollectAdapter，
 * 不额外引入页面层级；点击进入详情、长按删除单条、右上角清空当前分类。
 */
class HistoryCollectFragment : BaseVbFragment<FragmentHistoryCollectBinding>() {

    private var historyAdapter: HistoryAdapter? = null
    private val collectAdapter = CollectAdapter()

    /** 0 = 观看历史，1 = 收藏 */
    private var currentTab = 0

    /** 本页是否可见：ViewPager 只把当前页置为 RESUMED，事件到达时用它挡掉后台无意义的加载 */
    private var pageVisible = false

    /** 首次已经加载过，后续 onResume 的静默刷新就不必再走一遍 loading */
    private var loadedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        super.onDestroy()
    }

    override fun init() {
        setLoadSir(mBinding.mGridView)
        mBinding.mGridView.setHasFixedSize(true)
        mBinding.mGridView.setLayoutManager(GridLayoutManager(mContext, 3))

        setupAdapters()

        mBinding.tabLayout.addTab(mBinding.tabLayout.newTab().setText("观看历史"))
        mBinding.tabLayout.addTab(mBinding.tabLayout.newTab().setText("收藏"))
        // 显式选中第一个 Tab：addTab 的默认选中行为在部分 API 上不可靠
        mBinding.tabLayout.getTabAt(0)?.select()
        mBinding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                switchTab(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        mBinding.ivClear.setOnClickListener { clearCurrentTab() }

        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        pageVisible = true
        // 从播放页退出回到这里时，观看历史已经多了一条。
        // 原实现只在首次创建和切 Tab 时加载，所以必须切到「收藏」再切回来才看得到 ——
        // 这里补一次静默刷新（不重新走 loading，页面已有数据时不会闪）。
        if (loadedOnce) loadCurrentTab(silent = true)
    }

    override fun onPause() {
        pageVisible = false
        super.onPause()
    }

    /**
     * 观看历史变化由 DetailActivity.saveVod 发事件通知（TYPE_HISTORY_REFRESH）。
     * 原版接收方是被删掉的「我的-观看历史」页，合并到本页后就没人订阅了，
     * 事件一直只发不收，这里补回来。
     */
    @Subscribe
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type != RefreshEvent.TYPE_HISTORY_REFRESH) return
        if (!pageVisible || currentTab != 0) return
        loadCurrentTab(silent = true)
    }

    private fun setupAdapters() {
        historyAdapter = HistoryAdapter()

        historyAdapter!!.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                historyAdapter!!.remove(position)
                RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo)
                if (historyAdapter!!.data.isEmpty()) {
                    mBinding.topTip.visibility = View.GONE
                }
                true
            }

        historyAdapter!!.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { _: BaseQuickAdapter<*, *>?, view: View?, position: Int ->
                FastClickCheckUtil.check(view)
                val vodInfo = historyAdapter!!.data[position]
                val bundle = Bundle()
                bundle.putString("id", vodInfo.id)
                bundle.putString("sourceKey", vodInfo.sourceKey)
                jumpActivity(DetailActivity::class.java, bundle)
            }

        collectAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    collectAdapter.remove(position)
                    RoomDataManger.deleteVodCollect(vodInfo.id)
                }
                if (collectAdapter.data.isEmpty()) {
                    mBinding.topTip.visibility = View.GONE
                }
                true
            }

        collectAdapter.onItemClickListener =
            BaseQuickAdapter.OnItemClickListener { _, view, position ->
                FastClickCheckUtil.check(view)
                val vodInfo = collectAdapter.data[position]
                if (vodInfo != null) {
                    if (ApiConfig.get().getSource(vodInfo.sourceKey) != null) {
                        val bundle = Bundle()
                        bundle.putString("id", vodInfo.vodId)
                        bundle.putString("sourceKey", vodInfo.sourceKey)
                        jumpActivity(DetailActivity::class.java, bundle)
                    } else {
                        val newIntent = Intent(mContext, FastSearchActivity::class.java)
                        newIntent.putExtra("title", vodInfo.name)
                        newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(newIntent)
                    }
                }
            }
    }

    private fun clearCurrentTab() {
        val isHistory = currentTab == 0
        val tip = if (isHistory) "确定清空全部历史记录?" else "确定清空全部收藏?"
        XPopup.Builder(mActivity)
            .isDarkTheme(Utils.isDarkTheme())
            .asConfirm("提示", tip) {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (isHistory) {
                        RoomDataManger.deleteVodRecordAll()
                    } else {
                        RoomDataManger.deleteVodCollectAll()
                    }
                    withContext(Dispatchers.Main) {
                        if (isHistory) {
                            historyAdapter!!.setNewData(ArrayList())
                        } else {
                            collectAdapter.setNewData(ArrayList())
                        }
                        mBinding.topTip.visibility = View.GONE
                        showEmpty()
                    }
                }
            }.show()
    }

    private fun switchTab(index: Int) {
        currentTab = index
        mBinding.topTip.visibility = View.GONE
        mBinding.mGridView.adapter = if (index == 0) historyAdapter else collectAdapter
        loadCurrentTab()
    }

    private fun loadCurrentTab(silent: Boolean = false) {
        val isHistory = currentTab == 0
        if (!silent) showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            if (isHistory) {
                val allVodRecord = RoomDataManger.getAllVodRecord(100)
                val list: MutableList<VodInfo> = ArrayList()
                for (vodInfo in allVodRecord) {
                    if (vodInfo.playNote != null && vodInfo.playNote.isNotEmpty()) {
                        vodInfo.note = vodInfo.playNote
                    }
                    list.add(vodInfo)
                }
                withContext(Dispatchers.Main) {
                    loadedOnce = true
                    // 数据完全没变就别动 adapter：每次切 dock 都 setNewData 的话
                    // 列表会闪一下，滚动位置也会被重置
                    if (silent && !historyChanged(list)) return@withContext
                    historyAdapter!!.setNewData(list)
                    if (list.isNotEmpty()) {
                        showSuccess()
                        mBinding.topTip.visibility = View.VISIBLE
                    } else {
                        showEmpty()
                        mBinding.topTip.visibility = View.GONE
                    }
                }
            } else {
                val allVodCollect = RoomDataManger.getAllVodCollect()
                val list: MutableList<VodCollect> = ArrayList()
                for (vodInfo in allVodCollect) {
                    list.add(vodInfo)
                }
                withContext(Dispatchers.Main) {
                    loadedOnce = true
                    if (silent && !collectChanged(list)) return@withContext
                    collectAdapter.setNewData(list)
                    if (list.isNotEmpty()) {
                        showSuccess()
                        mBinding.topTip.visibility = View.VISIBLE
                    } else {
                        showEmpty()
                        mBinding.topTip.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** 用 id + last(观看时间) 判定历史是否真的变了，重播同一集 last 也会更新 */
    private fun historyChanged(list: List<VodInfo>): Boolean {
        val cur = historyAdapter?.data ?: return true
        if (cur.size != list.size) return true
        for (i in cur.indices) {
            if (cur[i].id != list[i].id || cur[i].last != list[i].last) return true
        }
        return false
    }

    private fun collectChanged(list: List<VodCollect>): Boolean {
        val cur = collectAdapter.data ?: return true
        if (cur.size != list.size) return true
        for (i in cur.indices) {
            if (cur[i].vodId != list[i].vodId || cur[i].updateTime != list[i].updateTime) return true
        }
        return false
    }
}
