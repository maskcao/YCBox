package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.orhanobut.hawk.Hawk;

/**
 * 主题切换统一入口。
 *
 * <p>「我的」页与设置页的主题入口都走这里，避免两处逻辑不一致：
 * 原设置页只写 HawkConfig.THEME_TAG 而不调用 setDefaultNightMode，
 * 导致选完不立即生效（需重启才看到变化）。
 *
 * <p>tag 语义：0=跟随系统，1=浅色（日间），2=深色（夜间）。
 */
public class ThemeHelper {

    public static final String[] NAMES = {"跟随系统", "浅色", "深色"};

    /**
     * 主题切换导致界面重建时置 true：首页此时不可见，
     * 应把数据加载推迟到用户真正切回首页，避免重建后白白等待网络。
     * 由 HomeFragment 首次可见时消费（consume）并清零。
     */
    private static boolean skipHomeFirstLoad = false;

    /** 读取并清零「跳过首页首次加载」标记 */
    public static boolean consumeSkipHomeFirstLoad() {
        boolean v = skipHomeFirstLoad;
        skipHomeFirstLoad = false;
        return v;
    }

    private ThemeHelper() {
    }

    /** 当前已选主题的显示名 */
    public static String getName(int tag) {
        if (tag < 0 || tag >= NAMES.length) {
            return NAMES[0];
        }
        return NAMES[tag];
    }

    /**
     * 按已保存的主题给 Context 包一层固定 uiMode 的配置。
     *
     * <p>在 Activity.attachBaseContext 中调用。这是主题生效的**根本保证**：
     * 无论 Activity 是首次创建还是 recreate，都会拿到正确的 uiMode，
     * 不依赖 appcompat 1.3.0 运行时的 applyDayNight/recreate 行为
     * （该行为在本项目的 ViewPager + 嵌套 Fragment 场景下不可靠，
     * 实测会出现"弹窗已变白但页面仍是深色"的半生效状态）。
     *
     * <p>tag=0（跟随系统）时原样返回，交由系统 uiMode 决定。
     */
    public static Context wrap(Context base) {
        int tag;
        try {
            tag = Hawk.get(HawkConfig.THEME_TAG, 0);
        } catch (Exception e) {
            // Hawk 尚未初始化（理论上 Activity 创建晚于 Application.onCreate，此处仅兜底）
            return base;
        }
        if (tag != 1 && tag != 2) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        int night = tag == 2 ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return base.createConfigurationContext(cfg);
    }

    /** 当前已保存的主题 tag */
    public static int getTag() {
        return Hawk.get(HawkConfig.THEME_TAG, 0);
    }

    /**
     * 应用主题并立即重建当前 Activity。
     *
     * <p>不能只调 setDefaultNightMode：本项目的 appcompat 为 1.3.0，
     * 对已存在 Activity 的自动重建存在竞态（实测会出现文字/图标已切换
     * 但界面配色未变），因此这里显式 recreate 兜底。
     *
     * @param activity 调用方 Activity，可为 null（仅保存全局模式）
     * @param tag      0/1/2
     */
    public static void apply(Activity activity, int tag) {
        Hawk.put(HawkConfig.THEME_TAG, tag);
        if (activity == null) {
            AppCompatDelegate.setDefaultNightMode(toNightMode(tag));
            return;
        }
        // 把调用方 Activity 固定在自己当前的明暗配置上。
        //
        // setDefaultNightMode 会让 appcompat 对**所有**存活的 Activity 调 applyDayNight，
        // 其中就包括马上要 finish 的调用方：它会额外 recreate 一次。
        // 而下面又要新建一个 MainActivity，于是同一次切换会多出一个实例——
        // 新实例显示期间，旧主题的窗口/dock 还在同一任务里，看起来就是 dock 闪一下或叠影。
        // 设成本身已有的模式后 applyDayNight 发现配置没变，就不会 recreate。
        if (activity instanceof androidx.appcompat.app.AppCompatActivity) {
            boolean dark = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            ((androidx.appcompat.app.AppCompatActivity) activity).getDelegate()
                    .setLocalNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES
                            : AppCompatDelegate.MODE_NIGHT_NO);
        }
        AppCompatDelegate.setDefaultNightMode(toNightMode(tag));
        // 用「启动新 MainActivity + finish 旧实例」替换当前界面，而不是 recreate()。
        //
        // recreate() 虽然能让新 Activity 拿到正确配置，但旧 Activity 的窗口内容会
        // 残留在屏幕上（必须切一次后台才刷新），因此改为创建全新实例：
        // 旧窗口随 finish() 销毁，新窗口从头绘制，无残留。
        //
        // 延迟 60ms：仅用于让调用方的 dialog.dismiss() 先提交关闭动作，
        // 尽量缩短用户等待（实测重建本身约 0.6s，主要耗时在动画与窗口切换）。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                // 重启后落到「我的」页，用户能直接看到切换结果
                Hawk.put(HawkConfig.THEME_RELAUNCH_TO_MY, true);
                // 首页此时不可见，其数据加载推迟到真正切回首页时进行
                skipHomeFirstLoad = true;
                android.content.Intent intent = new android.content.Intent(
                        activity, com.github.tvbox.osc.ui.activity.MainActivity.class);
                activity.startActivity(intent);
                activity.finish();
                // 关闭 Activity 切换动画，避免"刷新重进"的观感
                activity.overridePendingTransition(0, 0);
            } catch (Exception ignored) {
            }
        }, 60);
    }

    private static int toNightMode(int tag) {
        return tag == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : tag == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
