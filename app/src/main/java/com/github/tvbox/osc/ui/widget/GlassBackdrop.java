package com.github.tvbox.osc.ui.widget;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;

import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;

/**
 * 通用磨砂玻璃底，做法与 NewBox 的 {@code ui.widget.GlassBackdrop} 一致（它同样持有
 * Bitmap 字段）：把「这个 view 背后的界面内容」按 1/4 尺寸重画一遍，1.5 倍饱和 +
 * 80dp 级别的盒式模糊，再当作 shader 铺到玻璃表面下。背后的内容被糊成柔和的色块，
 * 海报轮廓还能隐约透出来。
 *
 * <p>刷新由 {@link ViewTreeObserver.OnPreDrawListener} 驱动，只在「内容滚动了」
 * 「自己位置变了」或有人显式 {@link #refresh()} 的那一帧重录，静止时不产生额外帧。
 * 捕获区按 4 倍模糊半径向外扩一圈，玻璃边缘才有真实内容可糊（只按自身尺寸取再
 * CLAMP 会把边框像素拖脏）。同窗口几块玻璃共用一份底图，新底图与旧底图去色淡入。
 *
 * <p>没走 {@code RenderNode + RenderEffect} 的 GPU 路线：实测半径大到 200dp 时 HWUI
 * 会静默丢掉整个 effect；把半径降到能生效后，录进节点的窗口内容会连同玻璃面自己的
 * RenderNode 一起被引用（父节点的显示列表是缓存的，临时 INVISIBLE 也躲不掉），玻璃
 * 画共享节点、共享节点又画玻璃 —— 渲染线程在 {@code RenderNode::prepareTreeImpl}
 * 里无限递归直接栈溢出。同窗口内「镜像自己背后」这件事只有软件重画做得到。
 */
public final class GlassBackdrop {

    private static final float BLUR_RADIUS_DP = 20f;
    /** 捕获额外外扩 = 3 倍模糊半径（NewBox 的 20f * 3f） */
    private static final float CAPTURE_MARGIN_FACTOR = 4f;
    /**
     * 模糊量倍数：有效模糊 ≈ 20dp * 此值。10 是 NewBox 导航栏的「重糊」——内容被融成
     * 一片纯色块，玻璃后面什么也认不出；这里降到 4（≈80dp），海报轮廓还能隐约透出来。
     */
    private static final float BLUR_MELT_FACTOR = 4f;
    private static final float SATURATION = 1.5f;
    /** 胶囊投影（NewBox GlassCapsule 的 density * 8f = 0x4100） */
    private static final float ELEVATION_DP = 8f;
    /**
     * 降采样倍数：模糊半径同步缩小，CPU 代价按平方下降。1/8 会把轮廓细节先抹掉一层，
     * 要「隐约透出内容」就得用 1/4 多留一点细节。
     */
    private static final float CAPTURE_SCALE = 4f;
    /** 盒式模糊趟数，越多越接近高斯，不会留下方框形的痕迹 */
    private static final int BLUR_PASSES = 2;
    /**
     * 同一窗口的所有玻璃共用一份全窗底图后的录制节奏。实测一次共享录制 3~7ms、
     * 每块玻璃裁切+模糊约 2ms，整窗五块玻璃一周期约 20ms；再往下压到 45ms 时
     * 横滑的中位帧耗时从 81ms 涨到 117ms，得不偿失，故停在 60ms（≈16fps 刷新），
     * 台阶感交给下面的淡入去抹。
     */
    private static final long RECAPTURE_INTERVAL_MS = 60L;
    /** 每块玻璃的刷新节奏再错开 0~15ms，避免同一帧里五份裁切+模糊叠在一起 */
    private static final long RECAPTURE_JITTER_MS = 15L;
    /**
     * 新旧底图的去色淡入时长。底图最快也只有 16fps，直接换帧会看到背景色一格一格跳；
     * 淡入把台阶抹平成连续的溶化，代价只是淡入期间每帧多画一个圆角矩形。
     */
    private static final long FADE_MS = 120L;

    private static final ColorMatrixColorFilter SATURATION_FILTER = saturate(SATURATION);

    /** 当前窗口的共享底图；一次只服务一个 DecorView，换窗口（弹窗）就重建 */
    private static SharedCapture sharedCapture;
    /** 活着的玻璃清单，录共享底图时用来临时隐藏同窗口的玻璃面 */
    private static final List<WeakReference<GlassBackdrop>> INSTANCES = new ArrayList<>();
    private static final List<View> hiddenHosts = new ArrayList<>();

    private final View host;
    private final float density;
    /** 录底图时用它包一层 saveLayer：饱和度作用在合成后的整体上 */
    private final Paint capturePaint = saturationPaint();
    private final int[] hostLoc = new int[2];
    private final Rect visible = new Rect();
    private final Matrix shaderMatrix = new Matrix();

    /** &lt;0 表示按短边一半自动取（胶囊和 dock 都是胶囊状） */
    private float cornerRadiusPx = -1f;
    private boolean pending;
    private boolean attached;
    private boolean listenerAdded;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private long lastCaptureAt;
    private final long recaptureInterval;
    private int margin;
    private int captureW;
    private int captureH;

    /** 双缓冲底图：{@code buffers[shown]} 是当前在显示的，新底图永远画进另一块 */
    private final Bitmap[] buffers = new Bitmap[2];
    private int shown = 1;
    /** 上一次捕获的底图，淡入期间和新的叠着画；可能为 null（首帧） */
    private Bitmap previous;
    private BitmapShader curShader;
    private BitmapShader prevShader;
    private long fadeStartAt;
    private final Paint curPaint = backdropPaint();
    private final Paint prevPaint = backdropPaint();
    private int[] blurPixels;
    private int[] blurTemp;

    private ViewTreeObserver watchedObserver;

    private final ViewTreeObserver.OnPreDrawListener preDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (attached && shouldCapture()) {
                        long now = SystemClock.uptimeMillis();
                        // 节流：不更新 lastX/lastY 也不清 pending，下一帧仍会重试
                        if (now - lastCaptureAt < recaptureInterval) return true;
                        lastCaptureAt = now;
                        pending = false;
                        lastX = hostLoc[0];
                        lastY = hostLoc[1];
                        capture();
                        // 底图变了要重画自己；这一次 invalidate 不会再触发捕获，不会自激成空转
                        host.invalidate();
                    }
                    return true;
                }
            };

    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            () -> pending = true;

    private final View.OnAttachStateChangeListener attachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                    attachObservers();
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    releaseObservers();
                }
            };

    public GlassBackdrop(View host) {
        this.host = host;
        this.density = host.getResources().getDisplayMetrics().density;
        this.recaptureInterval = RECAPTURE_INTERVAL_MS
                + (host.hashCode() & 0x7fffffff) % RECAPTURE_JITTER_MS;
        host.setTag(R.id.tag_glass_backdrop, this);
        cleanInstances();
        INSTANCES.add(new WeakReference<>(this));
    }

    /** 圆角半径（px）；不设置则取短边一半 */
    public GlassBackdrop cornerRadius(float px) {
        cornerRadiusPx = px;
        return this;
    }

    /** 挂上观察者让玻璃生效；重复调用无副作用 */
    public void attach() {
        if (!listenerAdded) {
            host.addOnAttachStateChangeListener(attachListener);
            listenerAdded = true;
        }
        if (host.isAttachedToWindow()) attachObservers();
    }

    public void detach() {
        if (listenerAdded) {
            host.removeOnAttachStateChangeListener(attachListener);
            listenerAdded = false;
        }
        releaseObservers();
        host.setTag(R.id.tag_glass_backdrop, null);
        for (int i = 0; i < INSTANCES.size(); i++) {
            if (INSTANCES.get(i).get() == this) INSTANCES.remove(i);
        }
        cleanInstances();
        if (INSTANCES.isEmpty() && sharedCapture != null) {
            sharedCapture.recycle();
            sharedCapture = null;
        }
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null && !buffers[i].isRecycled()) buffers[i].recycle();
            buffers[i] = null;
        }
        previous = null;
        curShader = null;
        prevShader = null;
        blurPixels = null;
        blurTemp = null;
        pending = false;
    }

    /** 请求重录底图（异步内容加载完、换壁纸之类），下一帧的 preDraw 里统一处理 */
    public void refresh() {
        pending = true;
        // 光挂 pending 不够：页面静止时不会有任何帧被排上，preDraw 不触发，
        // 底图就一直停在旧内容上——切页后玻璃「来不及变色」就是这么来的
        host.invalidate();
    }

    /**
     * 把模糊后的底图画到 (0,0)-(w,h)。
     *
     * @return false 表示还没有可用底图，调用方自己铺一层底色
     */
    public boolean draw(Canvas canvas, int w, int h) {
        if (w <= 0 || h <= 0) return false;
        Bitmap cur = buffers[shown];
        if (cur == null || cur.isRecycled() || curShader == null) return false;
        float radius = corner(w, h);
        long age = SystemClock.uptimeMillis() - fadeStartAt;
        if (previous != null && !previous.isRecycled() && prevShader != null && age < FADE_MS) {
            // 旧底图铺底、新底图按进度淡入：背景色是「溶过去」的，
            // 而不是每 45ms 硬跳一格——跳格就是用户看到的「颜色切换」
            prevPaint.setShader(prevShader);
            prevPaint.setAlpha(255);
            canvas.drawRoundRect(0, 0, w, h, radius, radius, prevPaint);
            curPaint.setShader(curShader);
            curPaint.setAlpha((int) (255f * age / FADE_MS));
            canvas.drawRoundRect(0, 0, w, h, radius, radius, curPaint);
            host.postInvalidateOnAnimation();
            return true;
        }
        previous = null;
        curPaint.setShader(curShader);
        curPaint.setAlpha(255);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, curPaint);
        return true;
    }

    /** 玻璃在窗口里可见、尺寸有效，并把窗口坐标写进 {@link #hostLoc} */
    private boolean shouldCapture() {
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0 || !host.isShown()) return false;
        if (!host.getGlobalVisibleRect(visible) || visible.isEmpty()) return false;
        // 只剩一小条在屏幕里就不值得再录了
        if (w > visible.width() * 2 || h > visible.height() * 2) return false;
        host.getLocationInWindow(hostLoc);
        return pending || hostLoc[0] != lastX || hostLoc[1] != lastY;
    }

    private void capture() {
        View root = contentRoot();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        margin = Math.round(density * BLUR_RADIUS_DP * CAPTURE_MARGIN_FACTOR);
        captureW = host.getWidth() + margin * 2;
        captureH = host.getHeight() + margin * 2;
        int bw = Math.max(1, Math.round(captureW / CAPTURE_SCALE));
        int bh = Math.max(1, Math.round(captureH / CAPTURE_SCALE));
        Bitmap shared = ensureShared(root, lastCaptureAt);
        if (shared == null) return;
        // 双缓冲：新底图画进「当前没在显示」的那块，画完再交换。
        // 单缓冲会在淡入途中被 Canvas 改写，正在溶化的那一帧就闪了。
        ensureBuffers(bw, bh);
        int target = 1 - shown;
        Bitmap bmp = buffers[target];
        Canvas canvas = new Canvas(bmp);
        // 透明底：玻璃后面没画到的地方直接露出真实内容，不会发黑
        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        // 共享底图的原点在窗口坐标 (-margin,-margin)，自己那块捕获区从 (hostX,hostY) 起
        canvas.drawBitmap(shared, -Math.round(hostLoc[0] / CAPTURE_SCALE),
                -Math.round(hostLoc[1] / CAPTURE_SCALE), null);
        int radius = Math.round(density * BLUR_RADIUS_DP * BLUR_MELT_FACTOR / CAPTURE_SCALE);
        boxBlur(bmp, radius, BLUR_PASSES);
        shown = target;
        curShader = shaderFor(bmp);
        prevShader = buffers[1 - target] == null ? null : shaderFor(buffers[1 - target]);
        previous = buffers[1 - target];
        fadeStartAt = SystemClock.uptimeMillis();
    }

    private void ensureBuffers(int bw, int bh) {
        if (buffers[0] != null && !buffers[0].isRecycled()
                && buffers[0].getWidth() == bw && buffers[0].getHeight() == bh) return;
        for (int i = 0; i < 2; i++) {
            if (buffers[i] != null && !buffers[i].isRecycled()) buffers[i].recycle();
            buffers[i] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        }
        previous = null;
        fadeStartAt = 0L;
    }

    private BitmapShader shaderFor(Bitmap bmp) {
        BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shaderMatrix.setScale((float) captureW / bmp.getWidth(),
                (float) captureH / bmp.getHeight());
        shaderMatrix.postTranslate(-margin, -margin);
        shader.setLocalMatrix(shaderMatrix);
        return shader;
    }

    /**
     * 同一窗口里所有玻璃共用一份全窗底图。轮播一动，OnScrollChangedListener 会把窗口里
     * 六块玻璃同时挂起，若各自走一遍 DecorView 重画就是六倍的开销——首页轮播动画期间
     * gfxinfo 掉帧率 100% 就是这么来的。改成每份窗口底图一个录制周期内只录一次，
     * 其余玻璃只裁自己的区域再糊（裁切和糊都是小块面积，代价可以忽略）。
     */
    private Bitmap ensureShared(View root, long now) {
        SharedCapture sc = sharedCapture;
        if (sc != null && sc.rootRef.get() != root) {
            sc.recycle();
            sc = null;
        }
        if (sc != null && (sc.bitmap.isRecycled() || sc.margin != margin)) {
            sc.recycle();
            sc = null;
        }
        if (sc == null || now - sc.capturedAt >= RECAPTURE_INTERVAL_MS) {
            int sw = Math.max(1, Math.round((root.getWidth() + margin * 2) / CAPTURE_SCALE));
            int sh = Math.max(1, Math.round((root.getHeight() + margin * 2) / CAPTURE_SCALE));
            Bitmap bmp = sc != null && sc.bitmap.getWidth() == sw && sc.bitmap.getHeight() == sh
                    ? sc.bitmap : Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            canvas.scale(1f / CAPTURE_SCALE, 1f / CAPTURE_SCALE);
            int save = canvas.saveLayer(0, 0, root.getWidth(), root.getHeight(), capturePaint);
            canvas.translate(margin, margin);
            // 玻璃只占窗口顶上和底下两条横带，中间一大段是浪费的软件光栅化：
            // 裁到所有玻璃捕获区的并集，Record 成本跟着玻璃面积走而不是窗口面积走
            hideGlasses(root, margin);
            if (!captureClip.isEmpty()) canvas.clipRect(captureClip);
            root.draw(canvas);
            showGlasses();
            canvas.restoreToCount(save);
            if (sc == null) sc = new SharedCapture(root, bmp, margin);
            else sc.bitmap = bmp;
            sc.capturedAt = now;
            sharedCapture = sc;
        }
        return sc.bitmap;
    }

    /**
     * 录共享底图前把同窗口的玻璃面全部临时隐藏：玻璃表面画的是「上一帧的糊底 + 自己的
     * 文字」，录进去等于把玻璃自己糊进玻璃里，描边和文字会在底图上留下清晰的影子。
     * 同时把各玻璃捕获区收进 {@link #captureClip} 当作录制裁剪框。
     */
    private static final Rect captureClip = new Rect();
    private static final int[] hideLoc = new int[2];

    private static void hideGlasses(View root, int margin) {
        cleanInstances();
        captureClip.setEmpty();
        for (WeakReference<GlassBackdrop> ref : INSTANCES) {
            GlassBackdrop glass = ref.get();
            View h = glass == null ? null : glass.host;
            if (h == null || h.getRootView() != root) continue;
            if (h.getVisibility() != View.VISIBLE) continue;
            h.getLocationInWindow(hideLoc);
            captureClip.union(hideLoc[0] - margin, hideLoc[1] - margin,
                    hideLoc[0] + h.getWidth() + margin, hideLoc[1] + h.getHeight() + margin);
            h.setVisibility(View.INVISIBLE);
            hiddenHosts.add(h);
        }
    }

    private static void showGlasses() {
        for (int i = 0; i < hiddenHosts.size(); i++) {
            hiddenHosts.get(i).setVisibility(View.VISIBLE);
        }
        hiddenHosts.clear();
    }

    private static void cleanInstances() {
        for (int i = INSTANCES.size() - 1; i >= 0; i--) {
            if (INSTANCES.get(i).get() == null) INSTANCES.remove(i);
        }
    }

    private static final class SharedCapture {
        final WeakReference<View> rootRef;
        Bitmap bitmap;
        final int margin;
        long capturedAt;

        SharedCapture(View root, Bitmap bitmap, int margin) {
            this.rootRef = new WeakReference<>(root);
            this.bitmap = bitmap;
            this.margin = margin;
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            bitmap = null;
        }
    }

    /**
     * 录 DecorView 而不是 {@code android.R.id.content}：窗口背景（壁纸、根布局底色）画在
     * content 之外，只录 content 会在玻璃底留下透明的洞，糊开之后洞里的黑色把整块底图
     * 压暗，玻璃就比 NewBox 黑一档。
     */
    private View contentRoot() {
        View root = host.getRootView();
        if (root instanceof ViewGroup) return root;
        return null;
    }

    private float corner(int w, int h) {
        float halfMin = Math.min(w, h) / 2f;
        return cornerRadiusPx > 0f ? Math.min(cornerRadiusPx, halfMin) : halfMin;
    }

    private void attachObservers() {
        applyShadowColors();
        ViewTreeObserver observer = host.getViewTreeObserver();
        if (!observer.isAlive() || watchedObserver == observer) return;
        // 换观察者优先级时先摘旧的（releaseObservers 会把 attached 置回 false），再标记挂上
        releaseObservers();
        watchedObserver = observer;
        observer.addOnPreDrawListener(preDrawListener);
        observer.addOnScrollChangedListener(scrollListener);
        attached = true;
        pending = true;
    }

    private void releaseObservers() {
        if (watchedObserver != null && watchedObserver.isAlive()) {
            watchedObserver.removeOnPreDrawListener(preDrawListener);
            watchedObserver.removeOnScrollChangedListener(scrollListener);
        }
        watchedObserver = null;
        attached = false;
    }

    private static ColorMatrixColorFilter saturate(float s) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(s);
        return new ColorMatrixColorFilter(matrix);
    }

    private static Paint saturationPaint() {
        Paint paint = new Paint();
        paint.setColorFilter(SATURATION_FILTER);
        return paint;
    }

    /** 底图放大用：饱和度已经烘进底图，这里只做双线性放大 */
    private static Paint backdropPaint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    /** 盒式模糊，在 1/4 尺寸的小图上跑 */
    private void boxBlur(Bitmap bitmap, int radius, int passes) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int size = w * h;
        int r = Math.max(1, Math.min(radius, Math.min(w, h) / 2));
        if (blurPixels == null || blurTemp == null || blurPixels.length != size) {
            blurPixels = new int[size];
            blurTemp = new int[size];
        }
        int[] pixels = blurPixels;
        int[] temp = blurTemp;
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        // 必须先转预乘再糊：捕获区外扩出来的那一圈是透明的 (0,0,0,0)，
        // 直接平均各通道会把边缘像素的 RGB 拖向黑色，玻璃因此整体偏暗。
        for (int i = 0; i < size; i++) {
            int c = pixels[i];
            int a = c >>> 24;
            if (a == 255) continue;
            pixels[i] = (a << 24) | (((c >> 16 & 0xff) * a / 255) << 16)
                    | (((c >> 8 & 0xff) * a / 255) << 8) | ((c & 0xff) * a / 255);
        }
        for (int i = 0; i < passes; i++) {
            blurHorizontal(pixels, temp, w, h, r);
            blurVertical(temp, pixels, w, h, r);
        }
        for (int i = 0; i < size; i++) {
            int c = pixels[i];
            int a = c >>> 24;
            if (a <= 0) {
                pixels[i] = 0;
            } else if (a < 255) {
                pixels[i] = (a << 24) | Math.min(255, ((c >> 16 & 0xff) * 255 + a / 2) / a) << 16
                        | Math.min(255, ((c >> 8 & 0xff) * 255 + a / 2) / a) << 8
                        | Math.min(255, ((c & 0xff) * 255 + a / 2) / a);
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    /**
     * 横向盒式模糊。窗口越界按边缘钳位取样，完全透明的取样点直接跳过并按有效个数归一：
     * 捕获区外扩出来的那一圈在屏幕外、根本没有内容，若把它算进平均值，玻璃中心区域的
     * alpha 会被拖到 180 左右，真实屏幕就有近三成直接透出来，dock 上能看到清晰的原文字。
     */
    private void blurHorizontal(int[] src, int[] dst, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            int n = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + Math.max(0, Math.min(w - 1, i))];
                if (c >>> 24 == 0) continue;
                a += c >>> 24;
                red += (c >> 16) & 0xff;
                green += (c >> 8) & 0xff;
                blue += c & 0xff;
                n++;
            }
            for (int x = 0; x < w; x++) {
                if (n == 0) {
                    dst[row + x] = 0;
                } else {
                    dst[row + x] = ((a / n) << 24) | ((red / n) << 16)
                            | ((green / n) << 8) | (blue / n);
                }
                int add = src[row + Math.min(w - 1, x + r + 1)];
                int sub = src[row + Math.max(0, x - r)];
                if (add >>> 24 != 0) {
                    a += add >>> 24;
                    red += (add >> 16) & 0xff;
                    green += (add >> 8) & 0xff;
                    blue += add & 0xff;
                    n++;
                }
                if (sub >>> 24 != 0) {
                    a -= sub >>> 24;
                    red -= (sub >> 16) & 0xff;
                    green -= (sub >> 8) & 0xff;
                    blue -= sub & 0xff;
                    n--;
                }
            }
        }
    }

    private void blurVertical(int[] src, int[] dst, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            int a = 0;
            int red = 0;
            int green = 0;
            int blue = 0;
            int n = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[Math.max(0, Math.min(h - 1, i)) * w + x];
                if (c >>> 24 == 0) continue;
                a += c >>> 24;
                red += (c >> 16) & 0xff;
                green += (c >> 8) & 0xff;
                blue += c & 0xff;
                n++;
            }
            for (int y = 0; y < h; y++) {
                if (n == 0) {
                    dst[y * w + x] = 0;
                } else {
                    dst[y * w + x] = ((a / n) << 24) | ((red / n) << 16)
                            | ((green / n) << 8) | (blue / n);
                }
                int add = src[Math.min(h - 1, y + r + 1) * w + x];
                int sub = src[Math.max(0, y - r) * w + x];
                if (add >>> 24 != 0) {
                    a += add >>> 24;
                    red += (add >> 16) & 0xff;
                    green += (add >> 8) & 0xff;
                    blue += add & 0xff;
                    n++;
                }
                if (sub >>> 24 != 0) {
                    a -= sub >>> 24;
                    red -= (sub >> 16) & 0xff;
                    green -= (sub >> 8) & 0xff;
                    blue -= sub & 0xff;
                    n--;
                }
            }
        }
    }

    /**
     * 给任意 view 装上 NewBox 同款玻璃背景：背景换成「实时模糊底 + 暗雾 + 亮描边」，
     * 并按圆角裁切、抬起 8dp 投影。布局里原来的静态胶囊 drawable 只作为未安装成功时的兜底。
     */
    public static GlassBackdrop installCapsule(View view, float cornerDp) {
        float radius = cornerDp * view.getResources().getDisplayMetrics().density;
        GlassBackdrop glass = new GlassBackdrop(view).cornerRadius(radius);
        view.setBackground(new CapsuleSurface(glass, view));
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                float r = Math.min(radius, Math.min(v.getWidth(), v.getHeight()) / 2f);
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        view.setClipToOutline(true);
        view.setElevation(ELEVATION_DP * view.getResources().getDisplayMetrics().density);
        glass.attach();
        return glass;
    }

    /** 投影颜色要等夜间模式定下来，挂到 attach 里再取 */
    private void applyShadowColors() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        int shadow = ContextCompat.getColor(host.getContext(), R.color.glass_shadow);
        host.setOutlineAmbientShadowColor(shadow);
        host.setOutlineSpotShadowColor(shadow);
    }

    /**
     * 玻璃胶囊的背景：模糊底 -&gt; 雾面 -&gt; 细描边。
     * 后两层照抄 NewBox 的 {@code qf0}：40% 黑压暗 + 1dp 40% 白内描边，
     * 不再叠顶部高光——高光是上一版的自创，正是「奶」而不是「烟」的原因。
     */
    private static final class CapsuleSurface extends Drawable {

        private final GlassBackdrop glass;
        private final View host;
        private final float corner;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CapsuleSurface(GlassBackdrop glass, View host) {
            this.glass = glass;
            this.host = host;
            corner = glass.cornerRadiusPx;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f,
                    host.getResources().getDisplayMetrics().density));
        }

        @ColorInt
        private int color(int resId) {
            return ContextCompat.getColor(host.getContext(), resId);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int w = bounds.width();
            int h = bounds.height();
            if (w <= 0 || h <= 0) return;
            float radius = Math.min(corner, Math.min(w, h) / 2f);
            int save = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            // 取色放在绘制时：构造期 appcompat 还没把夜间模式定下来，会拿到日间值
            fillPaint.setColor(glass.draw(canvas, w, h)
                    ? color(R.color.glass_fill) : color(R.color.glass_fill_static));
            canvas.drawRoundRect(0, 0, w, h, radius, radius, fillPaint);
            strokePaint.setColor(color(R.color.glass_stroke));
            float inset = strokePaint.getStrokeWidth() / 2f;
            canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                    Math.max(0f, radius - inset), Math.max(0f, radius - inset), strokePaint);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
