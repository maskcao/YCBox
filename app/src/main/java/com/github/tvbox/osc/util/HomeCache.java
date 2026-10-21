package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;

import java.util.HashMap;
import java.util.Map;

/**
 * 首页数据的内存缓存。
 *
 * <p>背景：切换主题会重建整个 Activity，首页的 ViewModel（分类、各 tab 列表）随之销毁，
 * 重建后所有数据都要重新走网络，导致切回首页时出现空白等待。
 *
 * <p>这里在内存中保留最近一次的分类与各 tab 列表：重建后先用缓存立即渲染，
 * 网络返回后再静默覆盖。切换数据源时自动失效，避免显示上一个源的旧内容。
 */
public class HomeCache {

    private static String sourceKey;
    private static AbsSortXml sortXml;
    private static final Map<String, AbsXml> LISTS = new HashMap<>();

    /** 记录当前数据源；源变化时清空缓存 */
    public static void setSource(String key) {
        if (key == null) {
            return;
        }
        if (!key.equals(sourceKey)) {
            LISTS.clear();
            sortXml = null;
            sourceKey = key;
        }
    }

    public static void putSort(AbsSortXml xml) {
        sortXml = xml;
    }

    public static AbsSortXml getSort() {
        return sortXml;
    }

    public static void putList(String sortId, AbsXml xml) {
        if (sortId != null && xml != null) {
            LISTS.put(sortId, xml);
        }
    }

    public static AbsXml getList(String sortId) {
        return sortId == null ? null : LISTS.get(sortId);
    }
}
