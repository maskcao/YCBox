package com.github.tvbox.osc.util

import android.net.Uri
import com.github.tvbox.osc.bean.LiveSourceEntry
import com.github.tvbox.osc.bean.Subscription
import com.orhanobut.hawk.Hawk
import java.util.ArrayList

/**
 * 直播订阅的统一读写入口。
 *
 * <p>历史实现里直播源只是一份 `ArrayList<String>`(HawkConfig.LIVE_HISTORY)，
 * 没有名字、不能置顶。订阅管理里要当成"订阅"来管理，就得有名字，
 * 因此新增 LIVE_SUBSCRIPTIONS 存 Subscription 对象；
 * 同时**继续把地址镜像回 LIVE_HISTORY**，保证设置页的历史直播源弹窗等老逻辑不失效。
 */
object LiveSubscriptionStore {

    /** 与 LIVE_HISTORY 保持一致的上限，防止无限增长 */
    private const val MAX = 20

    /**
     * 读取直播订阅。首次进入会自动把 LIVE_HISTORY 里的旧地址迁移过来，
     * 并保证当前生效的 LIVE_URL 一定在列表里（否则用户看不到自己在用哪条）。
     */
    @JvmStatic
    fun load(): MutableList<Subscription> {
        val stored = Hawk.get(HawkConfig.LIVE_SUBSCRIPTIONS, ArrayList<Subscription>())
        val list = ArrayList<Subscription>(stored)

        val history = Hawk.get(HawkConfig.LIVE_HISTORY, ArrayList<String>())
        for (url in history) {
            if (url.isNullOrBlank()) continue
            if (list.none { it.url == url }) {
                list.add(Subscription(guessName(url, list.size), url))
            }
        }

        val current = Hawk.get(HawkConfig.LIVE_URL, "") ?: ""
        if (current.isNotBlank() && list.none { it.url == current }) {
            list.add(0, Subscription(guessName(current, 0), current))
        }
        return list
    }

    /** 写回订阅列表，并同步镜像地址到 LIVE_HISTORY */
    @JvmStatic
    fun save(list: List<Subscription>) {
        val clean = ArrayList<Subscription>()
        for (item in list) {
            if (!item.url.isNullOrBlank() && clean.none { it.url == item.url }) {
                clean.add(item)
            }
        }
        Hawk.put(HawkConfig.LIVE_SUBSCRIPTIONS, clean)

        val urls = ArrayList<String>()
        for (item in clean) urls.add(item.url!!)
        if (urls.size > MAX) urls.subList(MAX, urls.size).clear()
        Hawk.put(HawkConfig.LIVE_HISTORY, urls)
    }

    /** 新增一条（已存在则跳过），用于订阅配置解析 / 设置页手填时回写 */
    @JvmStatic
    fun add(url: String?) {
        if (url.isNullOrBlank()) return
        val list = load()
        if (list.any { it.url == url }) return
        list.add(0, Subscription(guessName(url, 0), url))
        save(list)
    }

    /**
     * 把一个配置订阅里的 lives 全部同步进来，并打上来源标记。
     *
     * <p>已存在的地址不会再加一条，但如果这条以前是手填进来的、没有来源，
     * 就把它补齐成这个配置的名字 —— 用户看到的"来自：xxx"才不会莫名其妙是空的。
     *
     * @return 新增条数
     */
    @JvmStatic
    fun importLives(entries: List<LiveSourceEntry>?, sourceName: String): Int {
        if (entries.isNullOrEmpty()) return 0
        val list = load()
        var added = 0
        var dirty = false
        for (entry in entries) {
            val url = entry.url
            if (url.isBlank()) continue
            val exist = list.firstOrNull { it.url == url }
            if (exist != null) {
                // 名字还是地址推导出来的默认名，说明用户没手动改过，换成配置里写的名字
                val defaultName = guessName(url, list.indexOf(exist))
                if (entry.name.isNotBlank() && (exist.name.isNullOrBlank() || exist.name == defaultName)) {
                    if (exist.name != entry.name) {
                        exist.name = entry.name
                        dirty = true
                    }
                }
                // 同一个直播源经常出现在多份配置里，来源逐个记下来，
                // 否则先加载的那份配置会一直霸占"来自：xxx"，用户刚加的配置反而看不见
                if (sourceName.isNotBlank() && !hasSource(exist.from, sourceName)) {
                    exist.from = appendSource(exist.from, sourceName)
                    dirty = true
                }
                continue
            }
            val item = Subscription(entry.name.ifBlank { guessName(url, list.size) }, url)
            item.from = sourceName
            // 追加而不是插到最前，保持配置里 lives 的原始顺序
            list.add(item)
            added++
        }
        if (added > 0 || dirty) save(list)
        return added
    }

    private fun hasSource(from: String?, name: String): Boolean {
        if (from.isNullOrBlank()) return false
        return from.split('、').any { it.trim() == name }
    }

    /**
     * 配置订阅被删除后，把它从所有直播订阅的来源标记里摘掉。
     *
     * <p>否则列表上会出现"来自：xxx"，而那个 xxx 已经被用户删了，属于脏数据。
     * 摘完还剩别的来源就保留，摘完一个不剩就置空（回到"没来源"的样子）。
     *
     * @return 是否发生了变化
     */
    @JvmStatic
    fun removeSource(sourceName: String): Boolean {
        if (sourceName.isBlank()) return false
        val list = load()
        var dirty = false
        for (item in list) {
            val from = item.from
            if (from.isNullOrBlank() || !hasSource(from, sourceName)) continue
            val rest = from.split('、').map { it.trim() }.filter { it.isNotEmpty() && it != sourceName }
            item.from = if (rest.isEmpty()) null else rest.joinToString("、")
            dirty = true
        }
        if (dirty) save(list)
        return dirty
    }

    /** 多个来源用顿号串起来，只留最近 4 个，配置多了这行也不会长得离谱 */
    private fun appendSource(from: String?, name: String): String {
        val parts = if (from.isNullOrBlank()) mutableListOf() else
            from.split('、').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        parts.add(name)
        while (parts.size > 4) parts.removeAt(0)
        return parts.joinToString("、")
    }

    /**
     * 从地址推一个可读的默认名字：优先取文件名(去扩展名)，取不到再退回域名。
     * 用户可在订阅管理里长按重命名。
     */
    @JvmStatic
    fun guessName(url: String, index: Int): String {
        var name = ""
        try {
            val uri = Uri.parse(url)
            val seg = uri.lastPathSegment ?: ""
            name = seg.substringAfterLast('/').substringBeforeLast('.')
        } catch (th: Throwable) {
            name = ""
        }
        // 文件名为空 / 太短 / 无意义(如 lives、proxy)，改用域名
        if (name.length < 2 || name.equals("lives", true) || name.equals("proxy", true)) {
            name = try {
                Uri.parse(url).host ?: ""
            } catch (th: Throwable) {
                ""
            }
        }
        if (name.length > 24) name = name.substring(0, 24)
        return if (name.isBlank()) "直播源 ${index + 1}" else name
    }
}
