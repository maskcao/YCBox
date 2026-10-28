package com.github.tvbox.osc.util;

import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.squareup.picasso.Picasso;

/**
 * 封面图统一加载入口。
 *
 * <p>背景：项目里封面图一直用 Picasso（注释写明"部分电视机使用 glide 报错"），
 * 但 **Picasso 不支持 data: URI**。而 jar 爬虫（如 csp_PanConfigGuard 带来的
 * 夸克网盘/UC网盘/百度网盘工具入口）给的 vod_pic 恰恰是
 * `data:image/png;base64,...` 这种内嵌图，Picasso 直接抛异常走 error，
 * 于是一排卡片全是灰色占位图。
 *
 * <p>所以这里按前缀分流：data: URI 走 Glide（原生支持），其余仍走 Picasso，
 * 不改变原有网络加载路径，避免影响电视盒子上的兼容性。
 */
public final class ThumbLoader {

    private ThumbLoader() {
    }

    /**
     * 通栏大图专用：先把爬虫给的缩略图档位换成原图档，再走统一加载。
     *
     * <p>豆瓣的 s_ratio_poster 只有 270x405，铺满 1080x2400 的 hero 要放大 4 倍，
     * 同一路径的 l_ratio_poster 是 1080x1620；TMDB 的 w500 同理换 original。
     * 只给 hero 用是因为流量差 9 倍，网格小卡换档不划算。
     */
    public static void loadLarge(ImageView iv, String url) {
        load(iv, enlargePicUrl(url));
    }

    private static String enlargePicUrl(String url) {
        if (TextUtils.isEmpty(url) || url.startsWith("data:")) {
            return url;
        }
        return url.replace("/s_ratio_poster/", "/l_ratio_poster/")
                .replace("/view/photo/s/", "/view/photo/l/")
                .replace("/view/photo/m/", "/view/photo/l/")
                .replaceAll("/t/p/w\\d+/", "/t/p/original/");
    }

    public static void load(ImageView iv, String url) {
        load(iv, url, R.drawable.img_loading_placeholder);
    }

    public static void load(ImageView iv, String url, @DrawableRes int placeholder) {
        if (iv == null) {
            return;
        }
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(url.trim())) {
            iv.setImageResource(placeholder);
            return;
        }
        String src = url.trim();
        if (src.startsWith("data:")) {
            Glide.with(iv)
                    .load(src)
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(iv);
        } else {
            Picasso.get()
                    .load(DefaultConfig.checkReplaceProxy(src))
                    .placeholder(placeholder)
                    .error(placeholder)
                    .into(iv);
        }
    }
}
