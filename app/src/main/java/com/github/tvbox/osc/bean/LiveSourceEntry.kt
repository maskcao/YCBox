package com.github.tvbox.osc.bean

/**
 * 从配置订阅里解析出来的一条直播源，用于同步到「订阅管理 → 直播订阅」。
 */
data class LiveSourceEntry(
    val name: String,
    val url: String
)
