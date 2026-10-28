package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.blankj.utilcode.util.AppUtils;
import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.base.BaseVbFragment;
import com.github.tvbox.osc.databinding.FragmentMyBinding;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.LiveActivity;
import com.github.tvbox.osc.ui.activity.LocalPlayActivity;
import com.github.tvbox.osc.ui.activity.MovieFoldersActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.SubscriptionActivity;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter.SelectDialogInterface;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.WallpaperDialog;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ThemeHelper;
import com.github.tvbox.osc.util.Utils;
import com.github.tvbox.osc.util.WallpaperHelper;
import org.greenrobot.eventbus.EventBus;
import com.orhanobut.hawk.Hawk;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.lxj.xpopup.XPopup;
import com.lxj.xpopup.interfaces.OnInputConfirmListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class MyFragment extends BaseVbFragment<FragmentMyBinding> {

    /** 打开壁纸弹窗后需要等选图结果，暂存一下 */
    private WallpaperDialog wallpaperDialog;
    /**
     * 选图 launcher。必须用 registerForActivityResult 而不是
     * Activity.startActivityForResult —— 后者的结果只在 Activity 层回到
     * Fragment.onActivityResult，ViewPager 里的 fragment 实测收不到。
     */
    private androidx.activity.result.ActivityResultLauncher<Intent> wallpaperPicker;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wallpaperPicker = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Intent data = result.getData();
                    Uri uri = data == null ? null : data.getData();
                    if (uri == null) {
                        if (wallpaperDialog != null) wallpaperDialog.onPickedResult(false, "");
                        return;
                    }
                    WallpaperHelper.onPicked(mContext, uri, (ok, msg) -> {
                        if (wallpaperDialog != null) wallpaperDialog.onPickedResult(ok, msg);
                    });
                });
    }

    @Override
    protected void init() {
        mBinding.tvVersion.setText("v"+ AppUtils.getAppVersionName());

        initWallpaperEntry();
        initThemeEntry();

        mBinding.addrPlay.setOnClickListener(v ->{
            CharSequence clip = ClipboardUtils.getText();
            String clipText = clip == null ? "" : clip.toString();
            new XPopup.Builder(getContext())
                    .asInputConfirm("播放", "", isPush(clipText)?clipText:"", "地址", text -> {
                        if (!TextUtils.isEmpty(text)){
                            Intent newIntent = new Intent(mContext, DetailActivity.class);
                            newIntent.putExtra("id", text);
                            newIntent.putExtra("sourceKey", "push_agent");
                            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(newIntent);
                        }
                    }, null, R.layout.dialog_input).show();
        });

        mBinding.tvSetting.setOnClickListener(v -> jumpActivity(SettingActivity.class));



        mBinding.tvLocal.setOnClickListener(v -> {
            if (!XXPermissions.isGranted(mContext, Permission.MANAGE_EXTERNAL_STORAGE)) {
                showPermissionTipPopup();
            } else {
                jumpActivity(MovieFoldersActivity.class);
            }
        });

        mBinding.llSubscription.setOnClickListener(v -> jumpActivity(SubscriptionActivity.class));

        // 常用功能磁贴：搜索（快速搜索页）
        mBinding.tileSearch.setOnClickListener(v -> jumpActivity(FastSearchActivity.class));

        mBinding.llAbout.setOnClickListener(v -> {
            new XPopup.Builder(mActivity)
                    .asCustom(new AboutDialog(mActivity))
                    .show();
        });
    }

    /**
     * 「我的」页壁纸：顶部 ImageView 显示本地壁纸，菜单行打开换壁纸弹窗。
     *
     * <p>壁纸来源见 {@link com.github.tvbox.osc.util.WallpaperHelper}：
     * 订阅配置的 wallpaper 地址（随机壁纸接口）或用户相册里的图。
     */
    /**
     * 「我的」页壁纸入口：打开换壁纸弹窗。
     *
     * <p>壁纸本体铺在 MainActivity 的全局背景层（整页），所以这里不直接控制显示，
     * 只负责发起事件让 MainActivity 刷新 —— 见 {@link com.github.tvbox.osc.util.WallpaperHelper}。
     */
    private void initWallpaperEntry() {
        mBinding.llWallpaper.setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            wallpaperDialog = new WallpaperDialog(mActivity,
                    () -> wallpaperPicker.launch(WallpaperHelper.buildPickImageIntent()),
                    MyFragment::notifyWallpaperChanged);
            new XPopup.Builder(mActivity)
                    .isDarkTheme(Utils.isDarkTheme())
                    .asCustom(wallpaperDialog)
                    .show();
        });
    }

    /** 通知 MainActivity 刷新全局壁纸 */
    private static void notifyWallpaperChanged() {
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_WALLPAPER));
    }

    /**
     * 主题切换入口：「我的」页菜单行 + 三选弹窗（跟随系统/日间/夜间），
     * 与设置页的 SelectDialog 交互一致。
     *
     * <p>选择持久化到 HawkConfig.THEME_TAG（0=跟随系统 1=日间 2=夜间），
     * 下次启动由 App 初始化时的 Utils.initTheme() 恢复；
     * 当前生效则由 AppCompatDelegate.setDefaultNightMode 立即重建所有界面。
     */
    private void initThemeEntry() {
        mBinding.tvTheme.setText(ThemeHelper.getName(ThemeHelper.getTag()));
        // 主题磁贴（常用功能）与设置行两个入口都打开同一个三选弹窗
        View.OnClickListener openThemeDialog = v -> {
            FastClickCheckUtil.check(v);
            int current = ThemeHelper.getTag();
            ArrayList<String> modes = new ArrayList<>();
            for (String name : ThemeHelper.NAMES) {
                modes.add(name);
            }
            SelectDialog<String> dialog = new SelectDialog<>(mActivity);
            dialog.setTip("主题切换");
            dialog.setAdapter(new SelectDialogInterface<String>() {
                @Override
                public void click(String value, int pos) {
                    mBinding.tvTheme.setText(value);
                    try {
                        if (dialog.isShowing()) dialog.dismiss();
                    } catch (Exception ignored) {
                    }
                    // 与设置页共用同一套切换逻辑（写入 + 立即生效 + 重建）
                    ThemeHelper.apply(mActivity, pos);
                }

                @Override
                public String getDisplay(String name) {
                    return name == null ? "" : name;
                }
            }, SelectDialogAdapter.stringDiff, modes, current);
            dialog.show();
        };
        mBinding.llTheme.setOnClickListener(openThemeDialog);
        mBinding.llThemeRow.setOnClickListener(openThemeDialog);
    }

    private void showPermissionTipPopup(){
        new XPopup.Builder(mActivity)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("提示","为了播放视频、音频等,我们需要访问您设备文件的读写权限", () -> {
                    getPermission();
                }).show();
    }

    private void getPermission(){
        XXPermissions.with(this)
                .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        if (all) {
                            jumpActivity(MovieFoldersActivity.class);
                        }else {
                            ToastUtils.showLong("部分权限未正常授予,请授权");
                        }
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        if (never) {
                            ToastUtils.showLong("读写文件权限被永久拒绝，请手动授权");
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(mActivity, permissions);
                        } else {
                            ToastUtils.showShort("获取权限失败");
                            showPermissionTipPopup();
                        }
                    }
                });
    }

    private boolean isPush(String text) {
        return !TextUtils.isEmpty(text) && Arrays.asList("smb", "http", "https", "thunder", "magnet", "ed2k", "mitv", "jianpian").contains(Uri.parse(text).getScheme());
    }

}