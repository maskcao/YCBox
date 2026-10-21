package com.github.tvbox.osc.util;

import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 内置订阅源。
 *
 * <p>与"订阅管理里手填的源"的区别：内置源的配置 JSON 直接打包进 APK 的
 * {@code assets/builtin/}，用 {@code builtin://<文件名>} 作为订阅地址。
 * 因此它不依赖网络就能加载 —— 断网、远端挂了、分享出去给别人装，都能直接用。
 *
 * <p>同时保留 {@link #REMOTE_URL}：每次成功加载内置源后，会静默拉一次远端覆盖
 * 本地副本，这样源作者更新配置后，用户重启一次 App 就能拿到新内容；
 * 拉取失败完全不影响，仍然用 APK 里那份。
 */
public class BuiltinConfig {

    /** 内置源的地址协议。以它开头的订阅地址一律走 assets / 本地副本，不发网络请求。 */
    public static final String SCHEME = "builtin://";

    /** assets 下的目录。 */
    private static final String ASSET_DIR = "builtin";

    /** 默认内置配置文件名。 */
    public static final String DEFAULT_FILE = "ycbox.json";

    /** 订阅管理里显示的默认名称。 */
    public static final String DEFAULT_NAME = "内置源";

    /** 默认内置源的订阅地址。 */
    public static final String DEFAULT_URL = SCHEME + DEFAULT_FILE;

    /**
     * 内置源的远端地址，仅用于"同步更新"。
     *
     * <p>加载流程始终以本地为准，这个地址只在后台拉取成功后写入副本，
     * 下次启动才生效。所以远端不可达不会导致内置源失效。
     */
    public static final String REMOTE_URL =
            "https://codeberg.org/maskcao/maskcao/raw/branch/main/ycbox.json";

    /** 判断一个订阅地址是不是内置源。 */
    public static boolean isBuiltin(String url) {
        return !TextUtils.isEmpty(url) && url.startsWith(SCHEME);
    }

    /** 从 {@code builtin://xxx.json} 里取出文件名部分。 */
    public static String fileNameOf(String url) {
        if (!isBuiltin(url)) return "";
        String name = url.substring(SCHEME.length()).trim();
        // 不允许带路径，避免 ../ 之类的越权读取
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) return "";
        return name;
    }

    /** 本地副本路径：远端同步下来的内容存这里，优先于 assets 里的版本。 */
    public static File localCopyOf(String url) {
        String name = fileNameOf(url);
        if (name.isEmpty()) return null;
        File dir = new File(App.getInstance().getFilesDir(), ASSET_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    /** 读取 APK 里打包的内置配置。 */
    public static String readAsset(String url) throws Throwable {
        String name = fileNameOf(url);
        if (name.isEmpty()) throw new IllegalArgumentException("非法内置源地址: " + url);
        InputStream is = App.getInstance().getAssets().open(ASSET_DIR + "/" + name);
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        is.close();
        return sb.toString();
    }

    /**
     * 把远端内容写成本地副本。
     *
     * <p>只在内容非空时写入：远端返回 404 页面 / 空串时不覆盖，
     * 否则一份坏配置会把好用的内置源顶掉。
     */
    public static boolean writeLocalCopy(String url, String json) {
        if (TextUtils.isEmpty(json) || !json.trim().startsWith("{")) return false;
        File f = localCopyOf(url);
        if (f == null) return false;
        try {
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            if (f.exists()) f.delete();
            return tmp.renameTo(f);
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return false;
    }
}
