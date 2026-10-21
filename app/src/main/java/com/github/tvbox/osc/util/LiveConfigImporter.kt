package com.github.tvbox.osc.util

import android.net.Uri
import android.util.Base64
import com.github.tvbox.osc.bean.LiveSourceEntry
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 从一个配置订阅的 JSON 里把 lives(直播源)全部读出来。
 *
 * <p>原版 ApiConfig 只取 `lives[0]`，配置里排在第二条的直播源永远不会被看到；
 * 而且只有"该配置被设为当前生效配置并重新加载"时才会解析 —— 用户在订阅管理里
 * 新增一个订阅但不启用它，里面的直播源就一条都不会同步。
 *
 * <p>这里把这段解析独立出来，供两个入口共用：
 * <ul>
 *   <li>{@code ApiConfig.parseJson} —— 配置生效时（保持原行为不变，只是补全全部条目）</li>
 *   <li>{@code SubscriptionActivity.addSubscription} —— 添加订阅时立刻同步</li>
 * </ul>
 */
object LiveConfigImporter {

    @JvmStatic
    fun parse(jsonStr: String?): List<LiveSourceEntry> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(jsonStr).asJsonObject
            parse(root)
        } catch (th: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun parse(root: JsonObject?): List<LiveSourceEntry> {
        val result = ArrayList<LiveSourceEntry>()
        val lives = root?.get("lives") ?: return result
        if (!lives.isJsonArray) return result
        for (ele in lives.asJsonArray) {
            if (!ele.isJsonObject) continue
            val obj = ele.asJsonObject
            val name = safeStr(obj, "name")

            // 形态一：{ "type": 0, "url": "http..." }  —— FongMi / 常见格式
            val direct = safeStr(obj, "url")
            if (direct.isNotBlank()) {
                val type = safeStr(obj, "type")
                // type 省略或为 0 才是普通直播源地址，1/2 等是别的东西
                val usable = type.isEmpty() || type == "0"
                if (usable && direct.startsWith("http")) {
                    addUnique(result, LiveSourceEntry(name, direct))
                    continue
                }
                val proxyExt = extFromProxy(direct)
                if (proxyExt != null) {
                    addUnique(result, LiveSourceEntry(name, proxyExt))
                    continue
                }
            }

            // 形态二：整个对象里塞了 "proxy://...&ext=xxx"（clan 型配置）
            val raw = obj.toString()
            val idx = raw.indexOf("proxy://")
            if (idx >= 0) {
                val end = raw.lastIndexOf('"')
                val proxyUrl = raw.substring(idx, if (end > idx) end else raw.length)
                val proxyExt = extFromProxy(proxyUrl)
                if (proxyExt != null) {
                    addUnique(result, LiveSourceEntry(name, proxyExt))
                }
            }
        }
        return result
    }

    /** 从配置里读出直播源后直接同步到「直播订阅」，返回新增条数 */
    @JvmStatic
    fun importFrom(jsonStr: String?, sourceName: String): Int {
        val entries = parse(jsonStr)
        if (entries.isEmpty()) return 0
        return LiveSubscriptionStore.importLives(entries, sourceName)
    }

    @JvmStatic
    fun importFrom(root: JsonObject?, sourceName: String): Int {
        val entries = parse(root)
        if (entries.isEmpty()) return 0
        return LiveSubscriptionStore.importLives(entries, sourceName)
    }

    /**
     * 从 proxy:// 地址里取 ext 参数：明文 http 直接用，base64 的先解；
     * 解出来是 clan:// 的那些这里处理不了（需要配置自己的地址上下文），直接放弃。
     */
    private fun extFromProxy(proxyUrl: String): String? {
        return try {
            val ext = Uri.parse(proxyUrl).getQueryParameter("ext") ?: return null
            var url = ext
            if (!url.startsWith("http")) {
                url = try {
                    String(Base64.decode(url, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                } catch (th: Throwable) {
                    try {
                        String(Base64.decode(ext, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (th2: Throwable) {
                        return null
                    }
                }
            }
            if (url.startsWith("http")) url.trim() else null
        } catch (th: Throwable) {
            null
        }
    }

    private fun addUnique(list: ArrayList<LiveSourceEntry>, entry: LiveSourceEntry) {
        if (entry.url.isBlank()) return
        if (list.any { it.url == entry.url }) return
        list.add(entry)
    }

    private fun safeStr(obj: JsonObject, key: String): String {
        return try {
            val ele = obj.get(key) ?: return ""
            if (!ele.isJsonPrimitive) return ""
            ele.asString.trim()
        } catch (th: Throwable) {
            ""
        }
    }
}
