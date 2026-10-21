package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
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

import com.blankj.utilcode.util.ActivityUtils;
import com.blankj.utilcode.util.AppUtils;
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
        ImmersionBar.with(this)
                .statusBarDarkFont(!Utils.isDarkTheme())
                .titleBar(findTitleBar(getWindow().getDecorView().findViewById(android.R.id.content)))
                .init();
        // 底部手势条区域：导航栏透明 + 内容延伸到它后面，壁纸才能从最底下透出来。
        // 只开 LAYOUT_HIDE_NAVIGATION（状态栏行为完全不动，避免之前状态栏沉浸的问题），
        // 内容的具体避让由 attachWallpaperLayer 里的 insets 监听处理。
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        // 关掉系统对透明导航栏的「对比度保护」：否则 Android 15+ 会在手势条下面
        // 自动垫一层半透明白色圆角蒙层，看起来就是 dock 底下多出一块白。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
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

    @Override
    protected void onResume() {
        super.onResume();
        // 从「我的」页换完壁纸回来、或进程重启后，保证壁纸是最新的
        refreshWallpaper();
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