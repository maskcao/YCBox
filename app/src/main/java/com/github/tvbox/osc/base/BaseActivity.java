package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DeviceProfile;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ThemeHelper;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.util.WallpaperHelper;
import com.gyf.immersionbar.ImmersionBar;
import com.hjq.bar.OnTitleBarListener;
import com.hjq.bar.TitleBar;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.impl.LoadingPopupView;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import me.jessyan.autosize.internal.CustomAdapt;

public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt, OnTitleBarListener {
    protected Context mContext;
    private LoadService mLoadService;

    private ImmersionBar mImmersionBar;
    private TitleBar mTitleBar;
    private LoadingPopupView loadingPopup;

    /** 全局壁纸层（注入在 android.R.id.content 最底层） */
    private ImageView mWallpaperView;
    private View mWallpaperScrim;

    /**
     * 按已保存的主题强制 Activity 的 uiMode。
     *
     * <p>主题切换的根本保证点：无论 Activity 首次创建还是 recreate，
     * 都会拿到 ThemeHelper.wrap() 产出的正确配置，不依赖 appcompat
     * 运行时的 applyDayNight 行为（该行为在本项目场景下不可靠）。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeHelper.wrap(newBase));
    }

    /**
     * 配置覆盖的最终生效点。
     *
     * <p>AppCompatDelegate 应用夜间模式时也走这个方法（把 delegate 的
     * sDefaultNightMode 写进 overrideConfiguration）。这里按已保存的主题
     * 再改写一次 uiMode，确保**最终生效的配置一定与用户选择一致**，
     * 不会被 appcompat 的运行时状态覆盖 —— 这是"不重启也能正确切换"的关键。
     */
    @Override
    public void applyOverrideConfiguration(Configuration overrideConfiguration) {
        if (overrideConfiguration != null) {
            int tag = ThemeHelper.getTag();
            if (tag == 1 || tag == 2) {
                overrideConfiguration.uiMode =
                        (overrideConfiguration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                                | (tag == 2 ? Configuration.UI_MODE_NIGHT_YES
                                : Configuration.UI_MODE_NIGHT_NO);
            }
        }
        super.applyOverrideConfiguration(overrideConfiguration);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);



        if (getLayoutResID()==-1){
            initVb();
        }else {
            setContentView(getLayoutResID());
        }
        mContext = this;
        AppManager.getInstance().addActivity(this);
        applyToastStyle();
        initStatusBar();
        if (supportWallpaper()) {
            attachWallpaperLayer();
        }
        initTitleBar();
        init();
        if (!App.getInstance().isNormalStart){
            AppUtils.relaunchApp(true);
        }
    }

    /**
     * Toast 配色跟随当前主题。
     *
     * <p>utilcode 的 Toast 样式是写死在库资源里的浅色药丸（背景 #E6EEEEEE、文字
     * #DE000000），且没有 night 变体，深色主题下会突然冒出一块白。它是全局单例配置，
     * 所以每个 Activity 创建时按自己的主题重设一次即可 —— 换主题会重建 Activity，
     * 不必额外找切换点通知它。
     */
    private void applyToastStyle() {
        ToastUtils.getDefaultMaker()
                .setBgColor(ContextCompat.getColor(this, R.color.toast_bg))
                .setTextColor(ContextCompat.getColor(this, R.color.toast_fg));
    }

    /**
     * 屏幕形态变化（旋转、折叠屏展开、投屏/外接显示器导致的尺寸变化）时重置设备档位缓存。
     *
     * <p>manifest 中多数 Activity 声明了 configChanges="orientation|screenSize"，
     * 旋转不会重建 Activity，Configuration 由 onConfigurationChanged 回调；
     * 若不清缓存，DeviceProfile 会一直沿用首次进入时的档位，导致大屏/小屏下列数不更新。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DeviceProfile.reset();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {

    }


    private void initStatusBar(){
        final boolean lightIcon = !isDarkNow();
        // 和 NewBox 的 BaseActivity 一样：所有页面的系统导航栏都保持透明，
        // 手势条后面由窗口底色 / 壁纸层 / 页面背景自然透出（真正的导航栏沉浸）。
        // ImmersionBar 内部 BarParams 的初值是纯黑，且 setBar() 会在 onResume 再次下发，
        // 所以透明值也必须显式交给它，否则浅色页底部会冒出一条黑带。
        final int navColor = Color.TRANSPARENT;
        // 壁纸模式下，窗口底色也要跟着壁纸走。
        // 否则冷启动前几帧是主题白（windowBackground），壁纸层是 Glide 异步加载的，
        // 等它出来才变成壁纸色 —— 实测前 4 帧纯白、第 6 帧才变 #FDFCF6，
        // 表现为"刚进来底色先白一下"。把窗口底色设成同一个取样色，这段跳变就没了。
        // 导航栏透明后这个底色还兼作手势条后面的兜底色：窗口重建期间也不会露出黑色。
        if (supportWallpaper() && WallpaperHelper.isEnabled() && WallpaperHelper.hasWallpaper(this)) {
            int windowBg = WallpaperHelper.navBarColor(this, isDarkNow());
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(windowBg));
        }
        ImmersionBar bar = ImmersionBar.with(this)
                .statusBarDarkFont(lightIcon)
                .navigationBarColorInt(navColor)
                .navigationBarDarkIcon(lightIcon)
                .titleBar(findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content)));
        if (isEdgeToEdge()) {
            bar.statusBarColorInt(Color.TRANSPARENT);
        }
        bar.init();
        // 内容延伸到系统栏后面，具体避让由 attachWallpaperLayer 里的 insets 监听处理。
        // isEdgeToEdge() 的页面（首页）连状态栏也一起铺满，系统栏颜色保持全透明。
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // 关掉系统对透明系统栏的「对比度保护」：否则 Android 10+ 会在手势条/状态栏下面
        // 自动垫一层半透明蒙层，看起来就是 dock 底下或顶部多出一块色带。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        applyNavBarAppearance();
    }

    /**
     * 当前该给导航栏上什么色。
     *
     * <p>所有页面统一透明（NewBox BaseActivity 的做法）：内容延伸到手势条后面，
     * 壁纸层或窗口底色负责兜底，因此不再区分是否有壁纸、是否首页。
     * 对比度保护已在 initStatusBar 里关掉，避免系统自动垫一层半透明蒙层。
     */
    private int currentNavBarColor() {
        return Color.TRANSPARENT;
    }

    /**
     * 当前是否深色主题。
     *
     * <p>**不能用 {@link Utils#isDarkTheme()} 作为唯一依据**：它读的是 Application 的
     * uiMode，而主题切换只重建了 Activity（新 Activity 在 attachBaseContext 里被强塞了
     * 正确的 uiMode），Application 的配置可能还停留在切换前的值。表现就是切到深色后
     * 页面已经变暗、导航栏图标却仍按浅色渲染（白底黑手势条）。
     *
     * <p>Activity 自身的配置经过 {@link ThemeHelper#wrap} 强制改写，才是权威值；
     * 只有在它还处于 UI_MODE_NIGHT_UNDEFINED 时才回落到全局判断。
     */
    private boolean isDarkNow() {
        int mode = getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (mode == Configuration.UI_MODE_NIGHT_YES) return true;
        if (mode == Configuration.UI_MODE_NIGHT_NO) return false;
        return Utils.isDarkTheme();
    }

    /**
     * 声明导航栏（底部手势条）图标的明暗，跟随当前主题。
     *
     * <p>这一位不只是控制手势条本身的黑白，它同时是给系统的「背景明暗声明」：
     * 当导航栏是透明的时候，系统会据此决定要不要垫一层暗色对比保护层。
     * 不声明（默认按浅色图标处理）时，浅色页面上会出现两种难看的结果——
     * 一是白色手势条压在白色背景上完全看不见，二是系统为了保住白图标
     * 自动垫一层暗色蒙层，表现为底部突然出现一条黑边。
     */
    private void applyNavBarAppearance() {
        final boolean lightIcon = !isDarkNow();
        final int navColor = currentNavBarColor();
        Window window = getWindow();
        View decor = window.getDecorView();
        // 内容延伸到导航栏后面这一步必须每次 onResume 重做：
        // ImmersionBar 在 onResume 的 setBar() 会整体重写 systemUiVisibility，
        // 把 LAYOUT_HIDE_NAVIGATION 抹掉，窗口就退回"内容不延伸"的状态。
        // 一旦导航栏还是透明/半透明，那一块就没有任何内容绘制，露出黑色。
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | (isEdgeToEdge() ? View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN : 0));
        // 图标明暗要最后写：setSystemUiVisibility 是整体覆盖，先写会被打回去。
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, decor);
        controller.setAppearanceLightNavigationBars(lightIcon);
        if (isEdgeToEdge()) {
            // 首页铺满全屏：状态栏也透明，图标明暗跟随主题
            controller.setAppearanceLightStatusBars(lightIcon);
            window.setStatusBarColor(Color.TRANSPARENT);
        }
        // 颜色也重算一次：换壁纸后导航栏取样色要跟着变（ImmersionBar 里存的是
        // init 时的旧值，不会自己更新）。
        window.setNavigationBarColor(navColor);

        // XPopup 的弹窗是一个**独立窗口**，导航栏颜色不跟随 Activity ——
        // 不设全局值它就用自己默认的黑色，弹窗一出现底部立刻是一条黑带 + 白色手势条。
        // 它提供了静态全局配置，在这里同步一次，20 处调用点不用逐个改。
        // 弹窗内容不铺满屏幕，不能用透明值，回退到主题底色。
        XPopup.setNavigationBarColor(navColor == Color.TRANSPARENT
                ? ContextCompat.getColor(this, R.color.windowBackground) : navColor);
        XPopup.setIsLightNavigationBar(lightIcon);
    }

    /**
     * 全局壁纸层：所有页面共用一层铺在内容最底下的壁纸。
     *
     * <p>注入到 {@code android.R.id.content} 的最底层（index 0/1），所以主界面与
     * 订阅管理、设置等二级页面都会透出同一张壁纸；页面自己那些卡片要透出壁纸，
     * 背景色得是半透明的（见 colors 里的 bg_gray / bg_surface / dock_bg）。
     *
     * <p>没有壁纸、或壁纸关闭时两层都隐藏，各页面照常显示纯色背景。
     * 壁纸是否虚化由 {@link WallpaperHelper#isBlurEnabled()} 决定（虚化图预生成、直接命中缓存）。
     */
    private void attachWallpaperLayer() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || mWallpaperView != null) {
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setContentDescription(getString(R.string.app_name));
        content.addView(iv, 0);
        mWallpaperView = iv;

        View scrim = new View(this);
        scrim.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setBackgroundColor(ContextCompat.getColor(this, R.color.wallpaper_scrim));
        content.addView(scrim, 1);
        mWallpaperScrim = scrim;

        // 导航栏透明后，页面内容会延伸到手势条下面：给页面根布局（壁纸层之上的所有子 View）
        // 垫上导航栏高度，让 dock 等贴底控件抬到手势条之上；壁纸/遮罩层保持全屏铺满。
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            // 全屏页面自己处理系统栏避让（dock 按 nav inset 上移），
            // 父层不能再垫 padding，否则内容就被挡在导航栏之上，做不出沉浸效果。
            if (isEdgeToEdge()) {
                return insets;
            }
            int bottom = insets.getSystemWindowInsetBottom();
            for (int i = content.getChildCount() - 1; i >= 2; i--) {
                View child = content.getChildAt(i);
                child.setPadding(child.getPaddingLeft(), child.getPaddingTop(),
                        child.getPaddingRight(), bottom);
            }
            return insets;
        });

        WallpaperHelper.apply(mWallpaperView, mWallpaperScrim);
        // 标题栏也是不透明的话会把壁纸挡住，统一换成半透明底色。
        // hjq TitleBar 内部子容器自带背景，只设外层会被盖住，所以递归替换一遍。
        TitleBar titleBar = getTitleBar();
        if (titleBar != null) {
            int barColor = ContextCompat.getColor(this, R.color.title_bar_bg);
            applyTitleBarColor(titleBar, barColor);
        }
    }

    private void applyTitleBarColor(ViewGroup group, int color) {
        if (group.getBackground() != null) {
            group.setBackgroundColor(color);
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                applyTitleBarColor((ViewGroup) child, color);
            } else if (child.getBackground() != null) {
                child.setBackgroundColor(color);
            }
        }
    }

    /** 刷新壁纸（壁纸被换掉/删除时由子类或事件调用） */
    protected void refreshWallpaper() {
        if (mWallpaperView != null) {
            WallpaperHelper.apply(mWallpaperView, mWallpaperScrim);
        }
    }

    /** 不想显示壁纸的页面（比如全屏播放）覆写返回 false */
    protected boolean supportWallpaper() {
        return true;
    }

    /**
     * 内容是否铺满整屏（状态栏、导航栏都透明，页面绘制到系统栏后面）。
     *
     * <p>默认关闭：二级页面带标题栏，铺满后标题会顶到状态栏下面。
     * 目前只有主界面（首页 + 悬浮 dock）需要 NewBox 那种全屏效果。
     */
    protected boolean isEdgeToEdge() {
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「我的」页换完壁纸回来、或进程重启后，保证壁纸是最新的
        refreshWallpaper();
        // 从全屏播放等页面回来时，导航栏图标明暗可能被别处改过，回前台重新对齐主题
        applyNavBarAppearance();
    }

    private void initTitleBar(){
        if (getTitleBar() != null) {
            getTitleBar().setOnTitleBarListener(this);
        }
    }

    /**
     * 递归获取 ViewGroup 中的 TitleBar 对象
     */
    private TitleBar findTitleBar(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View view = group.getChildAt(i);
            if ((view instanceof TitleBar)) {
                return (TitleBar) view;
            } else if (view instanceof ViewGroup) {
                TitleBar titleBar = findTitleBar((ViewGroup) view);
                if (titleBar != null) {
                    return titleBar;
                }
            }
        }
        return null;
    }

    private TitleBar getTitleBar() {
        if (mTitleBar == null) {
            mTitleBar = findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content));
        }
        return mTitleBar;
    }


    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void initVb() {

    }

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
        if (DetailActivity.class.isAssignableFrom(clazz) && Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2) {
            //1.重新打开singleTask的页面(关闭小窗) 2.关闭画中画，重进detail再开启画中画会闪退
            ActivityUtils.finishActivity(DetailActivity.class);
        }
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return isBaseOnWidth() ? 360 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        return true;
    }

    @Override
    public void onLeftClick(TitleBar titleBar) {
        finish();
    }


    /**
     * 显示加载框
     */
    public void showLoadingDialog() {
        if (loadingPopup == null) {
            loadingPopup = new XPopup.Builder(this)
                    .isLightNavigationBar(true)
                    .hasShadowBg(false)
                    .asLoading();
        }
        loadingPopup.show();
    }

    /**
     * 隐藏加载框
     */
    public void dismissLoadingDialog() {
        if (loadingPopup != null && loadingPopup.isShow()) {
            loadingPopup.dismiss();
        }
    }

}
