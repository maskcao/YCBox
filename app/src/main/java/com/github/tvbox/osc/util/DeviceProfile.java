package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 设备档位判定。
 *
 * <p>本项目使用 AndroidAutoSize，设计基准固定为 360dp x 820dp（见 AndroidManifest 中的
 * design_width_in_dp / design_height_in_dp）。AutoSize 会把所有屏幕等比缩放到这个基准，
 * 因此<b>不同设备的实际像素分辨率差异不会反映到 dp 上</b>，真正需要区分的是：
 *
 * <ul>
 *   <li><b>屏幕形态</b>：UI_MODE_TYPE_TELEVISION 判定为 TV，其余按 smallestScreenWidth 区分手机/平板；</li>
 *   <li><b>视觉距离</b>：TV 通常 3 米外观看，平板 30cm，因此同样的 dp 在 TV 上需要更大的点击目标与更少的列数。</li>
 * </ul>
 *
 * <p>注意：项目已引入 com.owen.tvrecyclerview 作为 TV 焦点框架，焦点移动与焦点框由该库在运行时接管，
 * 因此<b>无需</b>在 layout 中声明 android:nextFocus。此类只负责「列数 / 间距 / 字号」这类尺寸决策。
 */
public class DeviceProfile {

    /** 设备形态 */
    public enum Form {
        /** 手机（竖屏设计基准） */
        PHONE,
        /** 平板（smallestScreenWidth >= 600dp） */
        TABLET,
        /** Android TV / 遥控器设备 */
        TV
    }

    private static volatile Form cachedForm = null;
    private static volatile int cachedSmallestWidth = -1;

    private DeviceProfile() {
    }

    /**
     * 获取当前设备形态。
     *
     * <p>优先级：TV 判定 &gt; 平板判定 &gt; 手机。
     * TV 判定放在最前，因为部分 TV 机型的 smallestScreenWidth 可能落在 300~400dp 区间，
     * 仅靠宽度无法识别，必须依据 UI_MODE_TYPE_TELEVISION。
     */
    public static Form getForm(Context context) {
        Form form = cachedForm;
        if (form != null) {
            return form;
        }
        synchronized (DeviceProfile.class) {
            if (cachedForm != null) {
                return cachedForm;
            }
            Configuration cfg = context.getResources().getConfiguration();
            int uiMode = cfg.uiMode & Configuration.UI_MODE_TYPE_MASK;
            if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) {
                form = Form.TV;
            } else if (cfg.smallestScreenWidthDp >= 600) {
                form = Form.TABLET;
            } else {
                form = Form.PHONE;
            }
            cachedForm = form;
            return form;
        }
    }

    /** 是否 TV 设备（含 Android TV 机顶盒、电视一体机） */
    public static boolean isTv(Context context) {
        return getForm(context) == Form.TV;
    }

    /** 是否平板设备（不含 TV） */
    public static boolean isTablet(Context context) {
        return getForm(context) == Form.TABLET;
    }

    /** 是否大屏设备（平板或 TV）。手机横屏虽然也是大屏，但布局仍按手机基准设计，故不算。 */
    public static boolean isLargeScreen(Context context) {
        return getForm(context) != Form.PHONE;
    }

    /** 当前 smallestScreenWidthDp，未初始化时返回 0 */
    public static int getSmallestWidth(Context context) {
        if (cachedSmallestWidth < 0) {
            cachedSmallestWidth = context.getResources().getConfiguration().smallestScreenWidthDp;
        }
        return cachedSmallestWidth;
    }

    /**
     * 依据屏幕可用宽度（dp）计算海报网格列数。
     *
     * <p>AutoSize 已把物理分辨率归一到 360dp 基准，因此这里<b>不能</b>直接用像素宽度，
     * 而应使用 AutoSize 归一后的逻辑宽度，否则在 4K TV 上会算出荒谬的列数。
     *
     * <p>参考列宽（海报约 110dp、间距 10dp）：
     * <ul>
     *   <li>手机：3 列（360dp 宽下 3 列约 110dp/列，与设计稿一致）</li>
     *   <li>平板：5 列（10 寸屏观感更合适，避免元素过小、留白过多）</li>
     *   <li>TV：4 列（3 米外观看，列数过多会导致海报过小、焦点框难以辨认）</li>
     * </ul>
     *
     * @param context 上下文
     * @param baseCount 手机基准列数，各调用点按自身卡片比例可微调
     */
    public static int getPosterSpanCount(Context context, int baseCount) {
        switch (getForm(context)) {
            case TABLET:
                return baseCount + 2;
            case TV:
                return baseCount + 1;
            case PHONE:
            default:
                return baseCount;
        }
    }

    /**
     * 海报网格列数（基准 3 列）。
     * 用于首页 GridFragment、我的收藏等海报墙。
     */
    public static int getPosterSpanCount(Context context) {
        return getPosterSpanCount(context, 3);
    }

    /**
     * 条目列表列数（基准 2 列）。
     * 用于搜索多选框、副标题选择等文本型网格。
     */
    public static int getItemSpanCount(Context context) {
        return getPosterSpanCount(context, 2);
    }

    /**
     * TV 端焦点缩放系数。
     *
     * <p>TV 观看距离约 3 米，是平板（30cm）的 10 倍。按等距视角换算，
     * 同样视觉大小的元素在 TV 上物理尺寸需放大约 2 倍；此处取较温和的 1.5 倍，
     * 避免大屏上元素过大导致一屏放不下几个卡片。
     *
     * @return TV 返回 1.5f，其余返回 1.0f
     */
    public static float getFocusScale(Context context) {
        return isTv(context) ? 1.5f : 1.0f;
    }

    /**
     * 网络请求页容量系数。
     *
     * <p>AutoSize 归一后平板/TV 与手机的逻辑宽度都是 360dp，一页 18 条在大屏上会显得稀疏。
     * 大屏按更多条目请求可减少翻页等待。
     */
    public static int getPageScale(Context context) {
        return isLargeScreen(context) ? 2 : 1;
    }

    /**
     * 屏幕旋转或形态变化后重置缓存。
     * 由 Activity 的 onConfigurationChanged 调用。
     */
    public static void reset() {
        cachedForm = null;
        cachedSmallestWidth = -1;
    }
}
