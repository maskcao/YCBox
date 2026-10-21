package com.github.tvbox.osc.ui.activity

import android.content.Intent
import android.text.TextUtils
import android.view.View
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.bean.Source
import com.github.tvbox.osc.bean.Subscription
import com.github.tvbox.osc.databinding.ActivitySubscriptionBinding
import com.github.tvbox.osc.ui.adapter.SubscriptionAdapter
import com.github.tvbox.osc.ui.dialog.ChooseSourceDialog
import com.github.tvbox.osc.ui.dialog.SubsTipDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog
import com.github.tvbox.osc.ui.dialog.SubsciptionDialog.OnSubsciptionListener
import com.github.tvbox.osc.util.BuiltinConfig
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.LiveConfigImporter
import com.github.tvbox.osc.util.LiveSubscriptionStore
import com.github.tvbox.osc.util.Utils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.lxj.xpopup.XPopup
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response
import com.obsez.android.lib.filechooser.ChooserDialog
import com.orhanobut.hawk.Hawk
import java.util.function.Consumer

class SubscriptionActivity : BaseVbActivity<ActivitySubscriptionBinding>() {

    private var mBeforeUrl = Hawk.get(HawkConfig.API_URL, "")
    /** 进入页面时生效的直播源，返回时用于判断是否需要重载 */
    private var mBeforeLiveUrl = Hawk.get(HawkConfig.LIVE_URL, "")
    private var mSelectedUrl = ""
    private var mSubscriptions: MutableList<Subscription> = Hawk.get(HawkConfig.SUBSCRIPTIONS, ArrayList())
    /** 直播订阅，切到「直播订阅」页时才加载 */
    private var mLiveSubscriptions: MutableList<Subscription> = ArrayList()
    private var mLiveLoaded = false
    /** false = 配置订阅，true = 直播订阅 */
    private var mTabLive = false
    private var mSubscriptionAdapter = SubscriptionAdapter()
    private val mSources: MutableList<Source> = ArrayList()

    override fun init() {

        mBinding.rv.setAdapter(mSubscriptionAdapter)
        ensureBuiltinSubscription()
        mSubscriptions.forEach(Consumer { item: Subscription ->
            if (item.isChecked) {
                mSelectedUrl = item.url
            }
        })

        mBinding.tvTabConfig.setOnClickListener { switchTab(false) }
        mBinding.tvTabLive.setOnClickListener { switchTab(true) }

        mSubscriptionAdapter.setNewData(mSubscriptions)
        updateTabStyle()
        mBinding.ivUseTip.setOnClickListener {
            XPopup.Builder(this)
                .asCustom(SubsTipDialog(this))
                .show()
        }

        mBinding.titleBar.rightView.setOnClickListener {//添加订阅
            if (mTabLive) {
                showAddLiveDialog()
                return@setOnClickListener
            }
            XPopup.Builder(this)
                .autoFocusEditText(false)
                .asCustom(
                    SubsciptionDialog(
                        this,
                        "订阅: " + (mSubscriptions.size + 1),
                        object : OnSubsciptionListener {
                            override fun onConfirm(
                                name: String,
                                url: String,
                                checked: Boolean
                            ) { //只有addSub2List用到,看注释,单线路才生效,其余方法仅作为参数继续传递
                                for (item in mSubscriptions) {
                                    if (item.url == url) {
                                        ToastUtils.showLong("订阅地址与" + item.name + "相同")
                                        return
                                    }
                                }
                                addSubscription(name, url, checked)
                            }

                            override fun chooseLocal(checked: Boolean) { //本地导入
                                if (!XXPermissions.isGranted(
                                        mContext,
                                        Permission.MANAGE_EXTERNAL_STORAGE
                                    )
                                ) {
                                    showPermissionTipPopup(checked)
                                } else {
                                    pickFile(checked)
                                }
                            }
                        })
                ).show()
        }

        mSubscriptionAdapter.setOnItemChildClickListener { _: BaseQuickAdapter<*, *>?, view: View, position: Int ->
            LogUtils.d("删除订阅")
            if (view.id == R.id.iv_del) {
                deleteAt(position)
            }
        }

        mSubscriptionAdapter.setOnItemClickListener { _: BaseQuickAdapter<*, *>?, _: View?, position: Int ->  //选择订阅
            val list = currentList()
            if (position >= list.size) return@setOnItemClickListener
            if (mTabLive) {
                val picked = list[position]
                Hawk.put(HawkConfig.LIVE_URL, picked.url)
                syncLiveChecked()
                mSubscriptionAdapter.setNewData(list)
                ToastUtils.showShort("已切换到该直播源")
            } else {
                for (i in list.indices) {
                    val subscription = list[i]
                    if (i == position) {
                        subscription.setChecked(true)
                        mSelectedUrl = subscription.url
                    } else {
                        subscription.setChecked(false)
                    }
                }
                //删除/选择只刷新,不触发重新排序
                mSubscriptionAdapter.notifyDataSetChanged()
            }
        }

        mSubscriptionAdapter.onItemLongClickListener =
            BaseQuickAdapter.OnItemLongClickListener { adapter: BaseQuickAdapter<*, *>?, view: View, position: Int ->
                val list = currentList()
                if (position >= list.size) return@OnItemLongClickListener true
                val item = list[position]
                if (mTabLive) {
                    showLiveItemMenu(view, item, position, list)
                    return@OnItemLongClickListener true
                }
                // 内置源多一项"重置"：远端同步下来的副本万一有问题，可以退回安装包里那份
                val menus = if (BuiltinConfig.isBuiltin(item.url)) {
                    arrayOf(
                        if (item.isTop) "取消置顶" else "置顶",
                        "重命名",
                        "复制地址",
                        "重置内置源"
                    )
                } else {
                    arrayOf(
                        if (item.isTop) "取消置顶" else "置顶",
                        "重命名",
                        "复制地址"
                    )
                }
                XPopup.Builder(this)
                    .atView(view.findViewById(R.id.tv_name))
                    .hasShadowBg(false)
                    .asAttachList(
                        menus, null
                    ) { index: Int, _: String? ->
                        when (index) {
                            0 -> {
                                item.isTop = !item.isTop
                                list[position] = item
                                afterItemMutated()
                            }
                            1 -> {
                                XPopup.Builder(this)
                                    .asInputConfirm(
                                        "更改为",
                                        "",
                                        item.name,
                                        "新的订阅名",
                                        { text ->
                                            if (!TextUtils.isEmpty(text)) {
                                                if (text.trim { it <= ' ' }.length > 8) {
                                                    ToastUtils.showShort("不要过长,不方便记忆")
                                                } else {
                                                    item.name = text.trim { it <= ' ' }
                                                    mSubscriptionAdapter.notifyItemChanged(position)
                                                    persistCurrentList()
                                                }
                                            }
                                        },
                                        null,
                                        R.layout.dialog_input
                                    ).show()
                            }
                            2 -> {
                                ClipboardUtils.copyText(list[position].url)
                                ToastUtils.showLong("已复制")
                            }
                            3 -> {
                                BuiltinConfig.localCopyOf(item.url)?.delete()
                                ToastUtils.showLong("已恢复为安装包内置版本，重启后生效")
                            }
                        }
                    }.show()
                true
            }
    }

    // ---------------- 两个 Tab 的通用逻辑 ----------------

    private fun currentList(): MutableList<Subscription> =
        if (mTabLive) mLiveSubscriptions else mSubscriptions

    private fun switchTab(live: Boolean) {
        if (mTabLive == live) return
        mTabLive = live
        if (live) ensureLiveLoaded()
        // 清理过期来源标记：配置订阅改名/删除后，「来自：xxx」会残留旧名字
        if (live) {
            val valid = mSubscriptions.map { it.name }.toSet()
            val cleaned = LiveSubscriptionStore.retainSources(valid)
            mLiveSubscriptions.clear()
            mLiveSubscriptions.addAll(cleaned)
        }
        // "来自：xxx"只有直播订阅才有意义
        mSubscriptionAdapter.setShowFrom(live)
        updateTabStyle()
        mSubscriptionAdapter.setNewData(currentList())
        mBinding.tvEmpty.visibility =
            if (live && mLiveSubscriptions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateTabStyle() {
        val ctx = mBinding.tvTabConfig.context
        val selectedBg = ContextCompat.getDrawable(ctx, R.drawable.bg_tab_selected)
        val selectedText = ContextCompat.getColor(ctx, R.color.on_accent_text)
        val normalText = ContextCompat.getColor(ctx, R.color.text_foreground)

        mBinding.tvTabConfig.background = if (!mTabLive) selectedBg else null
        mBinding.tvTabConfig.setTextColor(if (!mTabLive) selectedText else normalText)
        mBinding.tvTabLive.background = if (mTabLive) selectedBg else null
        mBinding.tvTabLive.setTextColor(if (mTabLive) selectedText else normalText)
    }

    private fun ensureLiveLoaded() {
        if (mLiveLoaded) {
            syncLiveChecked()
            return
        }
        mLiveSubscriptions = LiveSubscriptionStore.load()
        mLiveLoaded = true
        syncLiveChecked()
    }

    /** 直播订阅的选中态由当前生效的 LIVE_URL 决定，不额外存一份，避免两处不一致 */
    private fun syncLiveChecked() {
        val current = Hawk.get(HawkConfig.LIVE_URL, "")
        for (item in mLiveSubscriptions) {
            item.setChecked(item.url == current)
        }
    }

    /**
     * 删除/重命名/置顶这类"不改选中态"的操作立即落盘。
     * 只等 onPause 保存的话，进程被系统回收或直接覆盖安装就会丢掉这些改动。
     */
    private fun persistCurrentList() {
        if (mTabLive) {
            if (mLiveLoaded) LiveSubscriptionStore.save(mLiveSubscriptions)
        } else {
            Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, mSubscriptions)
        }
    }

    private fun deleteAt(position: Int) {
        val list = currentList()
        if (position >= list.size) return
        val item = list[position]
        if (!mTabLive && BuiltinConfig.isBuiltin(item.url)) {
            ToastUtils.showShort("内置源不能删除")
            return
        }
        if (item.isChecked) {
            ToastUtils.showShort("不能删除当前使用的订阅")
            return
        }
        // 注意参数顺序：XPopup 2.10.0 这个重载里，第 3 个文案落在左边按钮(绑 cancel)，
        // 第 4 个落在右边按钮(绑 confirm)。按直觉写会把"删除"绑到取消上，点了没反应。
        XPopup.Builder(this@SubscriptionActivity)
            .isDarkTheme(Utils.isDarkTheme())
            .asConfirm("删除订阅", "确定删除订阅吗？", "取消", "删除", {
                list.removeAt(position)
                // 配置订阅删掉了，它带进来的直播源来源标记也要跟着摘掉，
                // 否则列表上会留着"来自：xxx"，而 xxx 已经不存在了
                if (!mTabLive) LiveSubscriptionStore.removeSource(item.name)
                //删除/选择只刷新,不触发重新排序
                afterItemMutated()
                mBinding.tvEmpty.visibility =
                    if (mTabLive && list.isEmpty()) View.VISIBLE else View.GONE
            }, null, false).show()
    }

    private fun afterItemMutated() {
        mSubscriptionAdapter.setNewData(currentList())
        persistCurrentList()
    }

    private fun showLiveItemMenu(
        view: View,
        item: Subscription,
        position: Int,
        list: MutableList<Subscription>
    ) {
        XPopup.Builder(this)
            .atView(view.findViewById(R.id.tv_name))
            .hasShadowBg(false)
            .asAttachList(
                arrayOf(
                    if (item.isTop) "取消置顶" else "置顶",
                    "重命名",
                    "复制地址"
                ), null
            ) { index: Int, _: String? ->
                when (index) {
                    0 -> {
                        item.isTop = !item.isTop
                        list[position] = item
                        afterItemMutated()
                    }
                    1 -> {
                        XPopup.Builder(this)
                            .asInputConfirm(
                                "更改为",
                                "",
                                item.name,
                                "新的直播订阅名",
                                { text ->
                                    if (!TextUtils.isEmpty(text)) {
                                        if (text.trim { it <= ' ' }.length > 12) {
                                            ToastUtils.showShort("不要过长,不方便记忆")
                                        } else {
                                            item.name = text.trim { it <= ' ' }
                                            afterItemMutated()
                                        }
                                    }
                                },
                                null,
                                R.layout.dialog_input
                            ).show()
                    }
                    2 -> {
                        ClipboardUtils.copyText(item.url)
                        ToastUtils.showLong("已复制")
                    }
                }
            }.show()
    }

    private fun showAddLiveDialog() {
        XPopup.Builder(this)
            .autoFocusEditText(false)
            .asInputConfirm(
                "添加直播订阅",
                "支持 m3u / txt / json 直播源地址",
                "",
                "直播源地址(m3u/txt/json)",
                { text ->
                    val url = text?.trim { it <= ' ' } ?: ""
                    addLiveSubscription(url)
                },
                null,
                R.layout.dialog_input
            ).show()
    }

    private fun addLiveSubscription(url: String) {
        if (!(url.startsWith("http") || url.startsWith("clan://"))) {
            ToastUtils.showShort("直播源地址不正确")
            return
        }
        if (mLiveSubscriptions.any { it.url == url }) {
            ToastUtils.showShort("该直播源已存在")
            return
        }
        val item = Subscription(LiveSubscriptionStore.guessName(url, mLiveSubscriptions.size), url)
        // 之前一条都没有/没生效的源时，加进来直接启用，省得用户再点一次
        if (Hawk.get(HawkConfig.LIVE_URL, "").isBlank()) {
            item.setChecked(true)
            Hawk.put(HawkConfig.LIVE_URL, url)
        }
        mLiveSubscriptions.add(0, item)
        afterItemMutated()
        mBinding.tvEmpty.visibility = View.GONE
        ToastUtils.showShort("已添加，点击列表项启用")
    }

    /**
     * 保证内置源始终出现在订阅列表里。
     *
     * <p>首次安装由 App.putDefaultApi() 写入；这里是给"老版本升级上来"的用户补一条，
     * 否则他们永远看不到内置源，等于没内置。
     *
     * <p>只有当列表里一条选中的都没有时才自动启用内置源 —— 用户已经选了别的订阅，
     * 就不要抢他的选择。
     */
    private fun ensureBuiltinSubscription() {
        if (mSubscriptions.any { BuiltinConfig.isBuiltin(it.url) }) {
            return
        }
        val builtin = Subscription(BuiltinConfig.DEFAULT_NAME, BuiltinConfig.DEFAULT_URL)
        builtin.isTop = true
        if (mSubscriptions.none { it.isChecked }) {
            builtin.setChecked(true)
            mSelectedUrl = builtin.url
        }
        mSubscriptions.add(builtin)
    }

    private fun showPermissionTipPopup(checked: Boolean) {
        XPopup.Builder(this@SubscriptionActivity)
            .isDarkTheme(Utils.isDarkTheme())
            .asConfirm("提示", "这将访问您设备文件的读取权限") {
                XXPermissions.with(this)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(object : OnPermissionCallback {
                        override fun onGranted(permissions: List<String>, all: Boolean) {
                            if (all) {
                                pickFile(checked)
                            } else {
                                ToastUtils.showLong("部分权限未正常授予,请授权")
                            }
                        }

                        override fun onDenied(permissions: List<String>, never: Boolean) {
                            if (never) {
                                ToastUtils.showLong("读写文件权限被永久拒绝，请手动授权")
                                // 如果是被永久拒绝就跳转到应用权限系统设置页面
                                XXPermissions.startPermissionActivity(
                                    this@SubscriptionActivity,
                                    permissions
                                )
                            } else {
                                ToastUtils.showShort("获取权限失败")
                                showPermissionTipPopup(checked)
                            }
                        }
                    })
            }.show()
    }

    /**
     *
     * @param checked 与showPermissionTipPopup一样,只记录并传递选中状态
     */
    private fun pickFile(checked: Boolean) {
        ChooserDialog(this@SubscriptionActivity, R.style.FileChooser)
            .withFilter(false, false, "txt", "json")
            .withStartFile(
                if (TextUtils.isEmpty(Hawk.get("before_selected_path"))) "/storage/emulated/0/Download" else Hawk.get(
                    "before_selected_path"
                )
            )
            .withChosenListener(ChooserDialog.Result { _, pathFile ->
                Hawk.put("before_selected_path", pathFile.parent)
                val clanPath =
                    pathFile.absolutePath.replace("/storage/emulated/0", "clan://localhost")
                for (item in mSubscriptions) {
                    if (item.url == clanPath) {
                        ToastUtils.showLong("订阅地址与" + item.name + "相同")
                        return@Result
                    }
                }
                addSubscription(pathFile.name, clanPath, checked)
            })
            .build()
            .show()
    }

    private fun addSubscription(name: String, url: String, checked: Boolean) {
        if (url.startsWith("clan://")) {
            addSub2List(name, url, checked)
            mSubscriptionAdapter.setNewData(mSubscriptions)
        } else if (url.startsWith("http")) {
            showLoadingDialog()
            OkGo.get<String>(url)
                .tag("get_subscription")
                .execute(object : AbsCallback<String?>() {
                    override fun onSuccess(response: Response<String?>) {
                        dismissLoadingDialog()
                        try {
                            val json = JsonParser.parseString(response.body()).asJsonObject
                            // 添加订阅就把它自带的 lives 同步进「直播订阅」：
                            // 原逻辑只有在"这个配置变成当前生效配置并重新加载"时才会解析 lives，
                            // 用户添加但不启用的话就一条都看不到。这里立刻同步，并打上来源标记。
                            val entries = LiveConfigImporter.parse(json)
                            if (entries.isNotEmpty()) {
                                val added = LiveSubscriptionStore.importLives(entries, name)
                                // 直播 tab 可能已经加载过，置为未加载让它下次重新读
                                mLiveLoaded = false
                                // 一份配置里的直播源往往和别的配置重复，一条都没新增时也要说清楚，
                                // 否则用户以为没同步成功
                                ToastUtils.showShort(
                                    if (added > 0) "已同步 $added 个直播源"
                                    else "这 ${entries.size} 个直播源已经在列表里了"
                                )
                            }
                            // 多线路?
                            val urls = json["urls"]
                            // 多仓?
                            val storeHouse = json["storeHouse"]
                            if (urls != null && urls.isJsonArray) { // 多线路
                                if (checked) {
                                    ToastUtils.showLong("多条线路请主动选择")
                                }
                                val urlList = urls.asJsonArray
                                if (urlList != null && urlList.size() > 0 && urlList[0].isJsonObject
                                    && urlList[0].asJsonObject.has("url")
                                    && urlList[0].asJsonObject.has("name")
                                ) { //多线路格式
                                    for (i in 0 until urlList.size()) {
                                        val obj = urlList[i] as JsonObject
                                        val name = obj["name"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["url"].asString.trim { it <= ' ' }
                                        mSubscriptions.add(Subscription(name, url))
                                    }
                                }
                            } else if (storeHouse != null && storeHouse.isJsonArray) { // 多仓
                                val storeHouseList = storeHouse.asJsonArray
                                if (storeHouseList != null && storeHouseList.size() > 0 && storeHouseList[0].isJsonObject
                                    && storeHouseList[0].asJsonObject.has("sourceName")
                                    && storeHouseList[0].asJsonObject.has("sourceUrl")
                                ) { //多仓格式
                                    mSources.clear()
                                    for (i in 0 until storeHouseList.size()) {
                                        val obj = storeHouseList[i] as JsonObject
                                        val name = obj["sourceName"].asString.trim { it <= ' ' }
                                            .replace("<|>|《|》|-".toRegex(), "")
                                        val url = obj["sourceUrl"].asString.trim { it <= ' ' }
                                        mSources.add(Source(name, url))
                                    }
                                    XPopup.Builder(this@SubscriptionActivity)
                                        .asCustom(
                                            ChooseSourceDialog(
                                                this@SubscriptionActivity,
                                                mSources
                                            ) { position: Int, _: String? ->
                                                // 再根据多线路格式获取配置,如果仓内是正常多线路模式,name没用,直接使用线路的命名
                                                addSubscription(
                                                    mSources[position].sourceName,
                                                    mSources[position].sourceUrl,
                                                    checked
                                                )
                                            })
                                        .show()
                                }
                            } else { // 单线路/其余
                                addSub2List(name, url, checked)
                            }
                        } catch (th: Throwable) {
                            addSub2List(name, url, checked)
                        }
                        mSubscriptionAdapter.setNewData(mSubscriptions)
                    }

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        return response.body()!!.string()
                    }

                    override fun onError(response: Response<String?>) {
                        super.onError(response)
                        dismissLoadingDialog()
                        ToastUtils.showLong("订阅失败,请检查地址或网络状态")
                    }
                })
        } else {
            ToastUtils.showShort("订阅格式不正确")
        }
    }

    /**
     * 仅当选中本地文件和添加的为单线路时,使用此订阅生效。多线路会直接解析全部并添加,多仓会展开并选择,最后也按多线路处理,直接添加
     * @param name
     * @param url
     * @param checkNewest
     */
    private fun addSub2List(name: String, url: String, checkNewest: Boolean) {
        if (checkNewest) { //选中最新的,清除以前的选中订阅
            for (subscription in mSubscriptions) {
                if (subscription.isChecked) {
                    subscription.setChecked(false)
                }
            }
            mSelectedUrl = url
            mSubscriptions.add(Subscription(name, url).setChecked(true))
        } else {
            mSubscriptions.add(Subscription(name, url).setChecked(false))
        }
    }

    override fun onPause() {
        super.onPause()
        // 更新缓存
        Hawk.put(HawkConfig.API_URL, mSelectedUrl)
        Hawk.put<List<Subscription>?>(HawkConfig.SUBSCRIPTIONS, mSubscriptions)
        // 没进过直播订阅页就不要写，免得把还没加载的数据覆盖掉
        if (mLiveLoaded) {
            LiveSubscriptionStore.save(mLiveSubscriptions)
        }
    }

    override fun finish() {
        //切换了订阅地址或直播源,都需要重载主页让配置生效
        val liveChanged = mBeforeLiveUrl != Hawk.get(HawkConfig.LIVE_URL, "")
        if ((!TextUtils.isEmpty(mSelectedUrl) && mBeforeUrl != mSelectedUrl) || liveChanged) {
            val intent = Intent(this, MainActivity::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
        super.finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        OkGo.getInstance().cancelTag("get_subscription")
    }
}
