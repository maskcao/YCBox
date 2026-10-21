package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;


import com.bumptech.glide.Glide;
import com.github.tvbox.osc.api.ApiConfig;
import com.orhanobut.hawk.Hawk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 「我的」页壁纸工具。
 *
 * <p>壁纸来源两种：订阅配置里的 {@code wallpaper} 地址（一般指向随机壁纸接口，
 * 每次请求都会换一张），或者用户从相册里选的图。两者最终都统一存到 app 私有目录
 * {@code filesDir/wallpaper.jpg}，只把路径记在 {@link HawkConfig#WALLPAPER_PATH}，
 * 这样换设备/清缓存后不会指向失效的外部路径。
 */
public final class WallpaperHelper {

    private static final String FILE_NAME = "wallpaper.jpg";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private WallpaperHelper() {
    }

    public static File wallpaperFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** 是否存在可用壁纸文件 */
    public static boolean hasWallpaper(Context context) {
        String path = Hawk.get(HawkConfig.WALLPAPER_PATH, "");
        if (TextUtils.isEmpty(path)) return false;
        File file = new File(path);
        return file.exists() && file.length() > 0;
    }

    public static boolean isEnabled() {
        return Hawk.get(HawkConfig.WALLPAPER_ON, false);
    }

    public static void setEnabled(boolean on) {
        Hawk.put(HawkConfig.WALLPAPER_ON, on);
    }

    /**
     * 壁纸签名：用文件修改时间做 key，而不是时间戳。
     *
     * <p>Glide 的缓存 key 里包含 signature。之前用 {@code System.currentTimeMillis()}，
     * 等于每次调用都让缓存失效 —— 二级页面各自重新解码、每次 onResume 重新解码，
     * 于是出现「先没壁纸、加载才出来」的现象。改成 mtime 后：进程内内存缓存可复用
     * （二级页面秒出），而换壁纸（覆盖同一文件）会让 mtime 变化、自动失效刷新。
     */
    private static com.bumptech.glide.signature.ObjectKey signatureOf(File f) {
        return new com.bumptech.glide.signature.ObjectKey(f.lastModified());
    }

    /** 把当前壁纸显示到全局背景层；没有壁纸或开关关闭时隐藏（遮罩一起隐藏） */
    public static void apply(ImageView iv, View scrim) {
        apply(iv, scrim, null);
    }

    private static void apply(ImageView iv, View scrim, Runnable onReady) {
        if (iv == null) return;
        boolean show = isEnabled() && hasWallpaper(iv.getContext());
        iv.setVisibility(show ? ImageView.VISIBLE : ImageView.GONE);
        if (scrim != null) {
            scrim.setVisibility(show ? ImageView.VISIBLE : ImageView.GONE);
        }
        if (!show) return;
        File file = new File(Hawk.get(HawkConfig.WALLPAPER_PATH, ""));
        if (!file.exists() || file.length() <= 0) return;
        // 壁纸走 Glide：既有内存缓存（进程级，二级页面可复用），又支持 data URI。
        // signature 用文件 mtime：稳定 key 保证缓存命中，换图时自动失效。
        if (!isBlurEnabled()) {
            loadInto(iv, file);
            return;
        }
        // 虚化图是提前生成好的一张小图，解码几乎零耗时、不会再有空白期。
        File blurred = blurFile(iv.getContext());
        if (isBlurFresh(iv.getContext(), blurred, file)) {
            loadInto(iv, blurred);
            return;
        }
        // 虚化图还没生成：先把原图顶上（免得空白），后台生成完再替换成虚化图
        loadInto(iv, file);
        buildBlurAsync(iv.getContext(), file, () -> iv.post(() -> {
            File b = blurFile(iv.getContext());
            if (b.exists() && b.length() > 0) loadInto(iv, b);
            if (onReady != null) onReady.run();
        }));
    }

    private static void loadInto(ImageView iv, File file) {
        Glide.with(iv)
                .load(file)
                .signature(signatureOf(file))
                .centerCrop()
                .into(iv);
    }

    /**
     * 进程启动时预热壁纸到 Glide 内存缓存，这样 App 首个页面首帧就能直接命中缓存、
     * 不再有空白期。用 Application Context，避免和任何页面生命周期绑定。
     */
    public static void preload(Context context) {
        if (!isEnabled() || !hasWallpaper(context)) return;
        File file = new File(Hawk.get(HawkConfig.WALLPAPER_PATH, ""));
        if (!file.exists() || file.length() <= 0) return;
        if (!isBlurEnabled()) {
            Glide.with(context.getApplicationContext())
                    .load(file).signature(signatureOf(file)).centerCrop().preload();
            return;
        }
        // 虚化开着：预热虚化图；若还没生成，则先预热原图并顺手在后台生成虚化图
        File blurred = blurFile(context);
        if (isBlurFresh(context, blurred, file)) {
            Glide.with(context.getApplicationContext())
                    .load(blurred).signature(signatureOf(blurred)).centerCrop().preload();
        } else {
            Glide.with(context.getApplicationContext())
                    .load(file).signature(signatureOf(file)).centerCrop().preload();
            buildBlurAsync(context, file, null);
        }
    }

    // ==================== 壁纸虚化 ====================

    private static final String BLUR_FILE_NAME = "wallpaper_blur.jpg";
    /**
     * 虚化图的目标宽度。故意取得很小：一方面模糊后的图本来就没有细节，放大显示完全够用；
     * 另一方面小图解码/缓存几乎零成本，进页面不会再有「加载感」。
     */
    private static final int BLUR_TARGET_WIDTH = 160;
    /** 盒式模糊半径（{@link #BLUR_TARGET_WIDTH} 尺度上的像素），配合多趟逼近高斯 */
    private static final int BLUR_RADIUS = 4;
    private static final int BLUR_PASSES = 2;

    private static final AtomicBoolean BLUR_BUILDING = new AtomicBoolean(false);
    /**
     * 正在等虚化图的回调。
     *
     * <p>App 启动时 {@link #preload(Context)} 已经占住生成锁，页面此刻 apply 会先显示原图；
     * 如果这时把回调直接丢掉，页面就会一直停在未虚化的原图上（实测就是这个现象）。
     * 所以后续请求一律排队，等生成完成统一在主线程回调、各自换成虚化图。
     */
    private static final java.util.List<Runnable> BLUR_WAITERS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static boolean isBlurEnabled() {
        return Hawk.get(HawkConfig.WALLPAPER_BLUR, true);
    }

    public static void setBlurEnabled(boolean on) {
        Hawk.put(HawkConfig.WALLPAPER_BLUR, on);
    }

    private static File blurFile(Context context) {
        return new File(context.getFilesDir(), BLUR_FILE_NAME);
    }

    /** 虚化图是否与当前源壁纸匹配（源壁纸被换掉后 mtime 变化 → 判定过期、重新生成） */
    private static boolean isBlurFresh(Context context, File blurred, File src) {
        if (blurred == null || !blurred.exists() || blurred.length() <= 0) return false;
        long recorded = Hawk.get(HawkConfig.WALLPAPER_BLUR_SRC_MTIME, 0L);
        return recorded == src.lastModified();
    }

    /** 换壁纸后调用：丢弃旧虚化图（下次显示时按需重建） */
    private static void invalidateBlur(Context context) {
        File blurred = blurFile(context);
        if (blurred.exists()) {
            //noinspection ResultOfMethodCallIgnored
            blurred.delete();
        }
        Hawk.delete(HawkConfig.WALLPAPER_BLUR_SRC_MTIME);
    }

    private static void buildBlurAsync(Context context, File src, Runnable onDone) {
        if (context == null || src == null) return;
        if (!BLUR_BUILDING.compareAndSet(false, true)) {
            // 已经有任务在跑：排进等待队列，生成完成后统一回调
            if (onDone != null) BLUR_WAITERS.add(onDone);
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File built = null;
            try {
                built = buildBlurFile(app, src);
            } catch (Throwable th) {
                android.util.Log.e("Wallpaper", "生成虚化壁纸失败", th);
            } finally {
                BLUR_BUILDING.set(false);
            }
            final boolean ok = built != null;
            if (ok) android.util.Log.i("Wallpaper", "虚化图已生成 " + built.length() + "B");
            Runnable[] waiters;
            synchronized (BLUR_WAITERS) {
                waiters = BLUR_WAITERS.toArray(new Runnable[0]);
                BLUR_WAITERS.clear();
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!ok) return;
                for (Runnable r : waiters) r.run();
                if (onDone != null) onDone.run();
            });
        }, "wallpaper-blur").start();
    }

    /** 生成虚化图并落盘（后台线程调用） */
    private static File buildBlurFile(Context context, File src) {
        Bitmap small = decodeSmall(src, BLUR_TARGET_WIDTH);
        if (small == null) return null;
        int w = small.getWidth();
        int h = small.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pix = new int[w * h];
        small.getPixels(pix, 0, w, 0, 0, w, h);
        boxBlur(pix, w, h, BLUR_RADIUS);
        Bitmap out = Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888);
        small.recycle();
        File target = blurFile(context);
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, os);
        } catch (Throwable th) {
            android.util.Log.e("Wallpaper", "虚化图写盘失败", th);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        } finally {
            out.recycle();
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(target);
        Hawk.put(HawkConfig.WALLPAPER_BLUR_SRC_MTIME, src.lastModified());
        return target.length() > 0 ? target : null;
    }

    /** 按目标宽度采样解码（inSampleSize 只能是 2 的幂，最后再精确缩放一次） */
    private static Bitmap decodeSmall(File src, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;
        int sample = 1;
        while (w / (sample << 1) >= targetWidth) {
            sample <<= 1;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
        if (bmp == null) return null;
        if (bmp.getWidth() > targetWidth) {
            int th = Math.max(1, Math.round(bmp.getHeight() * (targetWidth / (float) bmp.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetWidth, th, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    /** 可分离盒式模糊（横一趟 + 竖一趟算一趟，跑 BLUR_PASSES 趟），近似高斯 */
    private static void boxBlur(int[] pix, int w, int h, int r) {
        if (r < 1) return;
        int[] tmp = new int[pix.length];
        for (int pass = 0; pass < BLUR_PASSES; pass++) {
            blurHorizontal(pix, tmp, w, h, r);
            blurVertical(tmp, pix, w, h, r);
        }
    }

    private static void blurHorizontal(int[] src, int[] dst, int w, int h, int r) {
        float div = 1f / (r + r + 1);
        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = x + k;
                    if (xx < 0) xx = 0;
                    else if (xx >= w) xx = w - 1;
                    int p = src[base + xx];
                    sumA += p >>> 24;
                    sumR += (p >> 16) & 0xFF;
                    sumG += (p >> 8) & 0xFF;
                    sumB += p & 0xFF;
                }
                dst[base + x] = ((int) (sumA * div) << 24)
                        | ((int) (sumR * div) << 16)
                        | ((int) (sumG * div) << 8)
                        | (int) (sumB * div);
            }
        }
    }

    private static void blurVertical(int[] src, int[] dst, int w, int h, int r) {
        float div = 1f / (r + r + 1);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = y + k;
                    if (yy < 0) yy = 0;
                    else if (yy >= h) yy = h - 1;
                    int p = src[yy * w + x];
                    sumA += p >>> 24;
                    sumR += (p >> 16) & 0xFF;
                    sumG += (p >> 8) & 0xFF;
                    sumB += p & 0xFF;
                }
                dst[y * w + x] = ((int) (sumA * div) << 24)
                        | ((int) (sumR * div) << 16)
                        | ((int) (sumG * div) << 8)
                        | (int) (sumB * div);
            }
        }
    }

    /**
     * 从当前订阅的 wallpaper 地址下载一张。
     *
     * @param onDone  (成功?, 失败原因) —— 在主线程回调
     */
    public static void downloadRandom(Context context, OnResult onDone) {
        String url = ApiConfig.get().wallpaper;
        if (TextUtils.isEmpty(url)) {
            onDone.onResult(false, "当前订阅未提供壁纸地址");
            return;
        }
        new Thread(() -> {
            File target = wallpaperFile(context);
            try {
                Request request = new Request.Builder().url(url).build();
                try (Response response = CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        onDone.onResult(false, "壁纸下载失败 " + response.code());
                        return;
                    }
                    ResponseBody body = response.body();
                    try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) != -1) {
                            out.write(buf, 0, len);
                        }
                    }
                }
                if (target.length() <= 0) {
                    onDone.onResult(false, "壁纸内容为空");
                    return;
                }
                Hawk.put(HawkConfig.WALLPAPER_PATH, target.getAbsolutePath());
                setEnabled(true);
                invalidateBlur(context);
                onDone.onResult(true, "");
            } catch (Throwable th) {
                android.util.Log.e("Wallpaper", th.getMessage(), th);
                onDone.onResult(false, "壁纸下载失败");
            }
        }, "wallpaper-dl").start();
    }

    /**
     * 构造"从相册选图"的 Intent。
     *
     * <p>不直接替调用方 startActivityForResult：由 Fragment 用
     * registerForActivityResult 发起，否则 Activity 发出的结果不会回到 Fragment
     * 的 onActivityResult（ViewPager 里的 fragment 尤其容易收不到）。
     */
    public static android.content.Intent buildPickImageIntent() {
        android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        return intent;
    }

    /**
     * 处理相册返回的 uri：直接把字节流复制成壁纸文件。
     *
     * <p>不用 Bitmap 解码再压缩：大图容易 OOM；原图也可能是 WebP/HEIC，
     * 直接复制保留原格式（文件名后缀只是个名字，Glide 按内容嗅探，照样能显示）。
     */
    public static void onPicked(Context context, Uri uri, OnResult onDone) {
        new Thread(() -> {
            try {
                File target = wallpaperFile(context);
                long copied;
                try (InputStream in = context.getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(target)) {
                    copied = copy(in, out);
                }
                if (copied <= 0) {
                    android.util.Log.e("Wallpaper", "相册图片为空: " + uri, null);
                    onDone.onResult(false, "图片读取失败");
                    return;
                }
                android.util.Log.i("Wallpaper", "picked " + uri + " size=" + copied);
                Hawk.put(HawkConfig.WALLPAPER_PATH, target.getAbsolutePath());
                setEnabled(true);
                invalidateBlur(context);
                onDone.onResult(true, "");
            } catch (Throwable th) {
                android.util.Log.e("Wallpaper", "读取相册图片失败: " + uri, th);
                onDone.onResult(false, "图片读取失败");
            }
        }, "wallpaper-pick").start();
    }

    private static long copy(InputStream in, OutputStream out) throws Throwable {
        byte[] buf = new byte[8192];
        long total = 0;
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
            total += len;
        }
        out.flush();
        return total;
    }

    /** 把当前壁纸导出到系统相册 */
    public static void saveToGallery(Context context, OnResult onDone) {
        if (!hasWallpaper(context)) {
            onDone.onResult(false, "还没有壁纸");
            return;
        }
        new Thread(() -> {
            try {
                File src = new File(Hawk.get(HawkConfig.WALLPAPER_PATH, ""));
                Bitmap bitmap = BitmapFactory.decodeFile(src.getAbsolutePath());
                if (bitmap == null) {
                    onDone.onResult(false, "壁纸读取失败");
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ 分区存储：写 MediaStore，不再需要存储权限
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "YCBox_wallpaper.jpg");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/YCBox");
                    Uri uri = context.getContentResolver().insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) {
                        onDone.onResult(false, "保存到相册失败");
                        return;
                    }
                    try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                } else {
                    File dir = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "YCBox");
                    if (!dir.exists() && !dir.mkdirs()) {
                        onDone.onResult(false, "保存到相册失败");
                        return;
                    }
                    try (OutputStream out = new FileOutputStream(new File(dir, "YCBox_wallpaper.jpg"))) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                }
                bitmap.recycle();
                onDone.onResult(true, "");
            } catch (Throwable th) {
                android.util.Log.e("Wallpaper", th.getMessage(), th);
                onDone.onResult(false, "保存到相册失败");
            }
        }, "wallpaper-save").start();
    }

    /** 重置壁纸：删本地文件、关开关 */
    public static void reset(Context context) {
        File file = wallpaperFile(context);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        invalidateBlur(context);
        Hawk.delete(HawkConfig.WALLPAPER_PATH);
        setEnabled(false);
    }

    public interface OnResult {
        void onResult(boolean ok, String message);
    }
}
