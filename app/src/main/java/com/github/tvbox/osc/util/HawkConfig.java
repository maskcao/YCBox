package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    public static final String LIVE_URL = "live_url";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String SUBSCRIPTIONS = "api_history";
    public static final String LIVE_HISTORY = "live_history";
    /**
     * 直播订阅列表(含自定义名称/置顶)，订阅管理→直播订阅页的数据源。
     * 老版本只有 LIVE_HISTORY 纯地址列表，由 LiveSubscriptionStore 自动迁移。
     */
    public static final String LIVE_SUBSCRIPTIONS = "live_subscriptions";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    /** 上次首页源的显示名：首帧就有名字可显示，不必等配置解析完 */
    public static final String HOME_SOURCE_NAME = "home_source_name";
    /** 上次首页源的 logo 地址：同样为了首帧就能命中 Glide 磁盘缓存 */
    public static final String HOME_SOURCE_LOGO = "home_source_logo";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//0 系统 1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    /**
     * 0 豆瓣热播 1 数据源推荐 2 关闭主页
     */
    public static final String HOME_REC = "home_rec";
    public static final String HISTORY_NUM = "history_num";
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    /**
     * 无痕浏览
     */
    public static final String PRIVATE_BROWSING = "private_browsing";
    /**
     * 底部导航栏(dock)是否显示「直播」入口
     */
    public static final String DOCK_SHOW_LIVE = "dock_show_live";
    /**
     * 底部导航栏(dock)是否显示「历史/收藏」入口
     */
    public static final String DOCK_SHOW_HISTORY = "dock_show_history";
    /**
     * 主题,跟随系统0,浅1,深2
     */
    public static final String THEME_TAG = "theme_tag";
    /** 主题切换后重启应用，标记下次启动落到「我的」页 */
    public static final String THEME_RELAUNCH_TO_MY = "theme_relaunch_to_my";
    /**
     * 「我的」页壁纸：是否显示壁纸（换壁纸/选图后自动置 true，重置壁纸时置 false）
     */
    public static final String WALLPAPER_ON = "wallpaper_on";
    /**
     * 「我的」页壁纸：是否启用「壁纸虚化」（默认开；关闭时显示原图）
     */
    public static final String WALLPAPER_BLUR = "wallpaper_blur";
    /**
     * 虚化图对应的源壁纸修改时间，用于判断缓存的虚化图是否过期
     */
    public static final String WALLPAPER_BLUR_SRC_MTIME = "wallpaper_blur_src_mtime";
    /**
     * 「我的」页壁纸：本地文件绝对路径（下载或从相册选来的图片都存到 app 私有目录）
     */
    public static final String WALLPAPER_PATH = "wallpaper_path";
    /**
     * 后台播放模式 0 关闭,1 开启,2 画中画
     */
    public static final String BACKGROUND_PLAY_TYPE = "background_play_type";
    /**
     * 广告过滤
     */
    public static final String VIDEO_PURIFY = "video_purify";
    /**
     * 长按的倍速播放设置
     */
    public static final String VIDEO_SPEED = "video_speed";
    /**
     * 搜索记录
     */
    public static final String HISTORY_SEARCH = "history_search";
}